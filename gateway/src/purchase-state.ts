import { DurableObject } from "cloudflare:workers";
import { decryptUtf8, encryptUtf8, hmacHex } from "./crypto-utils";
import { GatewayError, type ErrorCode, type PurchaseStatus } from "./contracts/index";
import {
  acknowledgeGooglePurchase,
  assertAllowlistedPurchase,
  buildReceiptClaims,
  lookupGooglePurchase,
  parseStubTokens,
  signReceipt,
  statusFromGoogle,
  type GooglePurchaseLookup,
  type StubTokenRecord,
} from "./purchase-verifier";

const MINUTE_MS = 60_000;
const DAY_MS = 24 * 60 * 60 * 1000;
const SUCCESS_CACHE_MS = 15 * MINUTE_MS;
const ACK_DEADLINE_MS = 48 * 60 * MINUTE_MS;
const ACK_GAPS_MS = [
  MINUTE_MS,
  5 * MINUTE_MS,
  30 * MINUTE_MS,
  2 * 60 * MINUTE_MS,
  6 * 60 * MINUTE_MS,
];
const INSTALL_PER_MINUTE = 5;
const INSTALL_PER_DAY = 30;

export interface VerifyPurchaseInput {
  nowMs: number;
  purchaseToken: string;
  installId: string;
  hmacSecret: string;
  encryptionKey: string;
  playMode: "stub" | "live";
  stubTokensJson?: string;
  serviceAccountEmail?: string;
  serviceAccountPrivateKey?: string;
  receiptPrivateKey?: string;
  receiptKid?: string;
  receiptIssuer: string;
  receiptAudience: string;
}

export interface VerifyPurchaseResult {
  status: PurchaseStatus;
  receipt?: string;
  acknowledgementPending: boolean;
  serverTime: string;
}

export type VerifyOutcome =
  | { kind: "ok"; result: VerifyPurchaseResult }
  | {
      kind: "error";
      code: ErrorCode;
      retryAfterSeconds?: number;
      message: string;
    };

export interface PurchaseInspect {
  grantCount: number;
  pendingAck: number;
  ackedCount: number;
  lastAlarmAt: number | null;
  deadlineExceeded: boolean;
  pendingAttempts: number;
  nextAckAt: number | null;
  purchasedAt: number | null;
}

function sqlText(value: unknown): string {
  if (typeof value !== "string") {
    throw new GatewayError("PROVIDER_UNAVAILABLE", "invalid purchase ledger text");
  }
  return value;
}

function sqlNumber(value: unknown): number {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    throw new GatewayError("PROVIDER_UNAVAILABLE", "invalid purchase ledger number");
  }
  return value;
}

function nextAckAt(purchasedAt: number, attempts: number, nowMs: number): number | null {
  if (nowMs - purchasedAt >= ACK_DEADLINE_MS) {
    return null;
  }
  const gap = ACK_GAPS_MS[Math.min(attempts, ACK_GAPS_MS.length - 1)] ?? 6 * 60 * MINUTE_MS;
  return nowMs + gap;
}

export class Purchase extends DurableObject<Env> {
  private failNextWrite = false;
  private testNowMs: number | null = null;
  private lastAlarmAt: number | null = null;
  private deadlineExceeded = false;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.ctx.storage.sql.exec(`
      CREATE TABLE IF NOT EXISTS grants (
        token_hmac TEXT PRIMARY KEY,
        encrypted_token TEXT,
        product_id TEXT NOT NULL,
        grant_status TEXT NOT NULL,
        ack_status TEXT NOT NULL,
        install_id TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        purchased_at INTEGER,
        attempts INTEGER NOT NULL,
        next_ack_at INTEGER,
        last_success_at INTEGER
      );
      CREATE TABLE IF NOT EXISTS install_events (
        actor_hmac TEXT NOT NULL,
        occurred_at INTEGER NOT NULL
      );
      CREATE INDEX IF NOT EXISTS idx_install_events_actor ON install_events(actor_hmac, occurred_at);
    `);
  }

  async failNextTransaction(): Promise<void> {
    this.failNextWrite = true;
  }

  async setNow(nowMs: number): Promise<void> {
    this.testNowMs = nowMs;
  }

  async inspect(): Promise<PurchaseInspect> {
    const grants = this.ctx.storage.sql.exec("SELECT COUNT(*) AS n FROM grants").toArray();
    const pending = this.ctx.storage.sql
      .exec(
        "SELECT COUNT(*) AS n FROM grants WHERE ack_status = 'pending' AND grant_status = 'owned'",
      )
      .toArray();
    const acked = this.ctx.storage.sql
      .exec(
        "SELECT COUNT(*) AS n FROM grants WHERE ack_status = 'acked' AND grant_status = 'owned'",
      )
      .toArray();
    const attempts = this.ctx.storage.sql
      .exec(
        "SELECT COALESCE(MAX(attempts), 0) AS n FROM grants WHERE ack_status = 'pending' AND grant_status = 'owned'",
      )
      .toArray();
    const next = this.ctx.storage.sql
      .exec("SELECT MIN(next_ack_at) AS next_at FROM grants WHERE next_ack_at IS NOT NULL")
      .toArray()[0]?.next_at;
    const purchased = this.ctx.storage.sql
      .exec("SELECT MIN(purchased_at) AS purchased_at FROM grants WHERE purchased_at IS NOT NULL")
      .toArray()[0]?.purchased_at;
    return {
      grantCount: sqlNumber(grants[0]?.n ?? 0),
      pendingAck: sqlNumber(pending[0]?.n ?? 0),
      ackedCount: sqlNumber(acked[0]?.n ?? 0),
      lastAlarmAt: this.lastAlarmAt,
      deadlineExceeded: this.deadlineExceeded,
      pendingAttempts: sqlNumber(attempts[0]?.n ?? 0),
      nextAckAt: typeof next === "number" ? next : null,
      purchasedAt: typeof purchased === "number" ? purchased : null,
    };
  }

  async verify(input: VerifyPurchaseInput): Promise<VerifyOutcome> {
    try {
      return { kind: "ok", result: await this.verifyInner(input) };
    } catch (error) {
      if (error instanceof GatewayError) {
        return {
          kind: "error",
          code: error.code,
          retryAfterSeconds: error.retryAfterSeconds,
          message: error.message,
        };
      }
      return { kind: "error", code: "PROVIDER_UNAVAILABLE", message: "purchase verify failed" };
    }
  }

  private async verifyInner(input: VerifyPurchaseInput): Promise<VerifyPurchaseResult> {
    if (this.failNextWrite) {
      this.failNextWrite = false;
      throw new GatewayError("PROVIDER_UNAVAILABLE", "purchase store failure");
    }
    const nowMs = this.testNowMs ?? input.nowMs;
    const tokenHmac = await hmacHex(input.hmacSecret, `purchase:${input.purchaseToken}`);
    const actorHmac = await hmacHex(input.hmacSecret, `install:${input.installId}`);
    this.enforceInstallLimits(actorHmac, nowMs);

    const cached = this.readCached(tokenHmac, nowMs);
    if (cached) {
      if (cached.status === "owned") {
        if (!input.receiptPrivateKey || !input.receiptKid) {
          throw new GatewayError("PROVIDER_UNAVAILABLE", "purchase signing key missing");
        }
        cached.receipt = await signReceipt({
          privateKeyPem: input.receiptPrivateKey,
          kid: input.receiptKid,
          claims: buildReceiptClaims({
            issuer: input.receiptIssuer,
            audience: input.receiptAudience,
            installId: input.installId,
            verifiedAt: new Date(nowMs).toISOString(),
          }),
        });
      }
      return cached;
    }

    const stubs = parseStubTokens(input.stubTokensJson);
    const lookup = await lookupGooglePurchase({
      mode: input.playMode,
      purchaseToken: input.purchaseToken,
      stubs,
      serviceAccountEmail: input.serviceAccountEmail,
      serviceAccountPrivateKey: input.serviceAccountPrivateKey,
      allowTestPurchases: this.env.ENVIRONMENT !== "production",
    });
    assertAllowlistedPurchase(lookup);
    const status = statusFromGoogle(lookup.state);
    if (status === "pending") {
      const pending = await this.persistPending({
        tokenHmac,
        input,
        nowMs,
      });
      return pending;
    }
    if (status === "revoked") {
      this.persistRevoked(tokenHmac, input.installId, nowMs);
      return {
        status: "revoked",
        acknowledgementPending: false,
        serverTime: new Date(nowMs).toISOString(),
      };
    }
    return this.persistOwned({ tokenHmac, input, lookup, stubs, nowMs });
  }

  override async alarm(): Promise<void> {
    const nowMs = this.testNowMs ?? Date.now();
    this.lastAlarmAt = nowMs;
    const due = this.ctx.storage.sql
      .exec(
        "SELECT token_hmac, encrypted_token, purchased_at, attempts FROM grants WHERE grant_status = 'owned' AND ack_status = 'pending' AND next_ack_at IS NOT NULL AND next_ack_at <= ?",
        nowMs,
      )
      .toArray();
    const stubs = parseStubTokens(this.env.GOOGLE_STUB_TOKENS_JSON);
    const encryptionKey = this.env.PURCHASE_TOKEN_ENCRYPTION_KEY;
    for (const row of due) {
      const purchasedAt = sqlNumber(row.purchased_at);
      const attempts = sqlNumber(row.attempts);
      if (nowMs - purchasedAt >= ACK_DEADLINE_MS) {
        this.deadlineExceeded = true;
        this.ctx.storage.sql.exec(
          "UPDATE grants SET next_ack_at = NULL, updated_at = ? WHERE token_hmac = ?",
          nowMs,
          sqlText(row.token_hmac),
        );
        continue;
      }
      if (!encryptionKey || typeof row.encrypted_token !== "string") {
        continue;
      }
      const token = await this.safeDecrypt(encryptionKey, row.encrypted_token);
      if (!token) {
        continue;
      }
      const result = await acknowledgeGooglePurchase({
        mode: this.env.PLAY_API_MODE === "live" ? "live" : "stub",
        purchaseToken: token,
        stubs,
        serviceAccountEmail: this.env.PLAY_SERVICE_ACCOUNT_EMAIL,
        serviceAccountPrivateKey: this.env.PLAY_SERVICE_ACCOUNT_PRIVATE_KEY,
        nowMs,
      });
      if (result === "acked") {
        this.ctx.storage.sql.exec(
          "UPDATE grants SET ack_status = 'acked', encrypted_token = NULL, next_ack_at = NULL, updated_at = ? WHERE token_hmac = ?",
          nowMs,
          sqlText(row.token_hmac),
        );
      } else {
        const next = nextAckAt(purchasedAt, attempts + 1, nowMs);
        this.ctx.storage.sql.exec(
          "UPDATE grants SET attempts = ?, next_ack_at = ?, updated_at = ? WHERE token_hmac = ?",
          attempts + 1,
          next,
          nowMs,
          sqlText(row.token_hmac),
        );
      }
    }
    await this.scheduleNextAlarm(nowMs);
  }

  private async safeDecrypt(keyHex: string, packed: string): Promise<string | null> {
    try {
      return await decryptUtf8(keyHex, packed);
    } catch {
      return null;
    }
  }

  private enforceInstallLimits(actorHmac: string, nowMs: number): void {
    this.ctx.storage.sql.exec(
      "DELETE FROM install_events WHERE occurred_at <= ? AND rowid IN (SELECT rowid FROM install_events WHERE occurred_at <= ? LIMIT 200)",
      nowMs - DAY_MS,
      nowMs - DAY_MS,
    );
    const minute = sqlNumber(
      this.ctx.storage.sql
        .exec(
          "SELECT COUNT(*) AS n FROM install_events WHERE actor_hmac = ? AND occurred_at > ?",
          actorHmac,
          nowMs - MINUTE_MS,
        )
        .toArray()[0]?.n ?? 0,
    );
    const day = sqlNumber(
      this.ctx.storage.sql
        .exec(
          "SELECT COUNT(*) AS n FROM install_events WHERE actor_hmac = ? AND occurred_at > ?",
          actorHmac,
          nowMs - DAY_MS,
        )
        .toArray()[0]?.n ?? 0,
    );
    if (minute >= INSTALL_PER_MINUTE || day >= INSTALL_PER_DAY) {
      throw new GatewayError("CLIENT_RATE_LIMITED", "purchase verify rate limited", {
        retryAfterSeconds: 60,
      });
    }
    this.ctx.storage.sql.exec(
      "INSERT INTO install_events (actor_hmac, occurred_at) VALUES (?, ?)",
      actorHmac,
      nowMs,
    );
  }

  private readCached(tokenHmac: string, nowMs: number): VerifyPurchaseResult | null {
    const rows = this.ctx.storage.sql
      .exec(
        "SELECT grant_status, ack_status, last_success_at FROM grants WHERE token_hmac = ?",
        tokenHmac,
      )
      .toArray();
    const row = rows[0];
    if (!row || typeof row.last_success_at !== "number") {
      return null;
    }
    if (nowMs - row.last_success_at > SUCCESS_CACHE_MS) {
      return null;
    }
    const status = sqlText(row.grant_status) as PurchaseStatus;
    if (status !== "owned" && status !== "pending" && status !== "revoked") {
      return null;
    }
    return {
      status,
      acknowledgementPending: status === "owned" && sqlText(row.ack_status) === "pending",
      serverTime: new Date(nowMs).toISOString(),
    };
  }

  private async persistPending(input: {
    tokenHmac: string;
    input: VerifyPurchaseInput;
    nowMs: number;
  }): Promise<VerifyPurchaseResult> {
    this.writeGrant({
      tokenHmac: input.tokenHmac,
      encryptedToken: null,
      status: "pending",
      ackStatus: "none",
      installId: input.input.installId,
      nowMs: input.nowMs,
      purchasedAt: null,
      attempts: 0,
      nextAckAt: null,
    });
    return {
      status: "pending",
      acknowledgementPending: false,
      serverTime: new Date(input.nowMs).toISOString(),
    };
  }

  private persistRevoked(tokenHmac: string, installId: string, nowMs: number): void {
    this.writeGrant({
      tokenHmac,
      encryptedToken: null,
      status: "revoked",
      ackStatus: "none",
      installId,
      nowMs,
      purchasedAt: null,
      attempts: 0,
      nextAckAt: null,
    });
  }

  private async persistOwned(input: {
    tokenHmac: string;
    input: VerifyPurchaseInput;
    lookup: GooglePurchaseLookup;
    stubs: Record<string, StubTokenRecord>;
    nowMs: number;
  }): Promise<VerifyPurchaseResult> {
    if (!input.input.encryptionKey || !input.input.receiptPrivateKey || !input.input.receiptKid) {
      throw new GatewayError("PROVIDER_UNAVAILABLE", "purchase signing or encryption key missing");
    }
    const encrypted = await encryptUtf8(input.input.encryptionKey, input.input.purchaseToken);
    const alreadyAcked = input.lookup.acknowledged;
    const existing = this.ctx.storage.sql
      .exec(
        "SELECT purchased_at, attempts, next_ack_at, ack_status FROM grants WHERE token_hmac = ?",
        input.tokenHmac,
      )
      .toArray()[0];
    const existingPurchasedAt =
      existing && typeof existing.purchased_at === "number" ? existing.purchased_at : null;
    const purchasedAt = existingPurchasedAt ?? input.nowMs;
    const keepPendingAck =
      !alreadyAcked &&
      existing !== undefined &&
      sqlText(existing.ack_status) === "pending" &&
      typeof existing.next_ack_at === "number";
    const ackStatus = alreadyAcked ? "acked" : "pending";
    const attempts = keepPendingAck ? sqlNumber(existing.attempts) : 0;
    const nextAck = alreadyAcked
      ? null
      : keepPendingAck
        ? sqlNumber(existing.next_ack_at)
        : nextAckAt(purchasedAt, 0, input.nowMs);
    try {
      this.writeGrant({
        tokenHmac: input.tokenHmac,
        encryptedToken: alreadyAcked ? null : encrypted,
        status: "owned",
        ackStatus,
        installId: input.input.installId,
        nowMs: input.nowMs,
        purchasedAt,
        attempts,
        nextAckAt: nextAck,
      });
    } catch {
      throw new GatewayError("PROVIDER_UNAVAILABLE", "durable grant could not be saved");
    }
    if (!alreadyAcked) {
      const alarmAt = nextAck ?? input.nowMs + MINUTE_MS;
      await this.ctx.storage.setAlarm(alarmAt);
      const ack = await acknowledgeGooglePurchase({
        mode: input.input.playMode,
        purchaseToken: input.input.purchaseToken,
        stubs: input.stubs,
        serviceAccountEmail: input.input.serviceAccountEmail,
        serviceAccountPrivateKey: input.input.serviceAccountPrivateKey,
        nowMs: input.nowMs,
      });
      if (ack === "acked") {
        this.ctx.storage.sql.exec(
          "UPDATE grants SET ack_status = 'acked', encrypted_token = NULL, next_ack_at = NULL, updated_at = ? WHERE token_hmac = ?",
          input.nowMs,
          input.tokenHmac,
        );
      }
    }
    const receipt = await signReceipt({
      privateKeyPem: input.input.receiptPrivateKey,
      kid: input.input.receiptKid,
      claims: buildReceiptClaims({
        issuer: input.input.receiptIssuer,
        audience: input.input.receiptAudience,
        installId: input.input.installId,
        verifiedAt: new Date(input.nowMs).toISOString(),
      }),
    });
    const current = this.ctx.storage.sql
      .exec("SELECT ack_status FROM grants WHERE token_hmac = ?", input.tokenHmac)
      .toArray()[0];
    return {
      status: "owned",
      receipt,
      acknowledgementPending: current ? sqlText(current.ack_status) === "pending" : false,
      serverTime: new Date(input.nowMs).toISOString(),
    };
  }

  private writeGrant(input: {
    tokenHmac: string;
    encryptedToken: string | null;
    status: PurchaseStatus;
    ackStatus: string;
    installId: string;
    nowMs: number;
    purchasedAt: number | null;
    attempts: number;
    nextAckAt: number | null;
  }): void {
    this.ctx.storage.sql.exec(
      `INSERT INTO grants (
        token_hmac, encrypted_token, product_id, grant_status, ack_status, install_id,
        created_at, updated_at, purchased_at, attempts, next_ack_at, last_success_at
      ) VALUES (?, ?, 'remove_ads_lifetime', ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(token_hmac) DO UPDATE SET
        encrypted_token = excluded.encrypted_token,
        grant_status = excluded.grant_status,
        ack_status = excluded.ack_status,
        install_id = excluded.install_id,
        updated_at = excluded.updated_at,
        purchased_at = COALESCE(grants.purchased_at, excluded.purchased_at),
        attempts = excluded.attempts,
        next_ack_at = excluded.next_ack_at,
        last_success_at = excluded.last_success_at`,
      input.tokenHmac,
      input.encryptedToken,
      input.status,
      input.ackStatus,
      input.installId,
      input.nowMs,
      input.nowMs,
      input.purchasedAt,
      input.attempts,
      input.nextAckAt,
      input.nowMs,
    );
  }

  private async scheduleNextAlarm(nowMs: number): Promise<void> {
    const next = this.ctx.storage.sql
      .exec("SELECT MIN(next_ack_at) AS next_at FROM grants WHERE next_ack_at IS NOT NULL")
      .toArray()[0]?.next_at;
    if (typeof next === "number" && next > nowMs) {
      await this.ctx.storage.setAlarm(next);
    }
  }
}
