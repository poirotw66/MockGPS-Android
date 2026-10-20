import { DurableObject } from "cloudflare:workers";
import { DEFAULT_LIMITS, type OperationalLimits } from "./config";
import {
  GatewayError,
  type BillingPolicy,
  type CreditEstimate,
  type ErrorCode,
  type GeoOperation,
  type RouteRequest,
  type SearchRequest,
  type ValidatedGeoRequest,
} from "./contracts/index";
import {
  planRoute,
  searchPlaces,
  type ProviderBehavior,
  type RouteProviderResult,
  type SearchProviderResult,
} from "./providers/geoapify";

const DAY_MS = 24 * 60 * 60 * 1000;
const CLEANUP_BATCH = 200;

export interface AdmitInput {
  expectedLedgerId: string;
  allowEmptyInit: boolean;
  nowMs: number;
  operation: GeoOperation;
  credits: number;
  requestHmac: string;
  payloadHmac: string;
  actorHmac: string;
  ipDayHmac: string;
  ipMinuteHmac: string;
  limits: OperationalLimits;
}

export type AdmitResult =
  | { outcome: "admitted"; reservationId: string }
  | { outcome: "duplicate" }
  | { outcome: "conflict" }
  | { outcome: "quota"; retryAfterSeconds: number }
  | { outcome: "rate"; retryAfterSeconds: number }
  | { outcome: "unavailable"; reason: string };

export type RejectedAdmit = Exclude<AdmitResult, { outcome: "admitted" }>;

export interface DispatchProviderConfig {
  mode: "stub" | "live";
  apiKey?: string;
  timeoutMs: number;
  behavior?: ProviderBehavior;
}

export type DispatchSearchResult =
  | { kind: "rejected"; admit: RejectedAdmit }
  | { kind: "admitted"; reservationId: string; result: SearchProviderResult }
  | { kind: "failed"; reservationId: string; code: ErrorCode; message: string };

export type DispatchRouteResult =
  | { kind: "rejected"; admit: RejectedAdmit }
  | { kind: "admitted"; reservationId: string; result: RouteProviderResult }
  | { kind: "failed"; reservationId: string; code: ErrorCode; message: string };

export interface FinalizeInput {
  reservationId: string;
  nowMs: number;
  status: string;
}

export interface LedgerInspect {
  ledgerId: string | null;
  usedCredits: number;
  dispatchedCount: number;
  reservationCount: number;
  finalizedCount: number;
  sqlAvailable: boolean;
}

export function estimateMaximumCredits(
  validatedRequest: ValidatedGeoRequest,
  billingPolicy: BillingPolicy | undefined,
): CreditEstimate {
  if (
    !billingPolicy ||
    billingPolicy.version.trim() === "" ||
    billingPolicy.evidence.trim() === ""
  ) {
    return { kind: "unsupported", reason: "billingPolicy requires version and evidence" };
  }
  const documented = billingPolicy.documentedMaximumCredits?.[validatedRequest.operation];
  if (validatedRequest.operation === "search" && documented === 1) {
    return { kind: "bounded", credits: 1, policyVersion: billingPolicy.version };
  }
  if (validatedRequest.operation === "route") {
    const testOnly =
      billingPolicy.evidence.includes("TEST_ONLY_BUDGET_ACCOUNTING") &&
      typeof documented === "number" &&
      Number.isInteger(documented) &&
      documented > 0;
    if (testOnly) {
      return { kind: "bounded", credits: documented, policyVersion: billingPolicy.version };
    }
    return {
      kind: "unsupported",
      reason:
        "B01 unresolved: routing cost depends on waypoints, road distance, and options; no owner-verified billingPolicy bound exists",
    };
  }
  if (typeof documented === "number" && Number.isInteger(documented) && documented > 0) {
    return { kind: "bounded", credits: documented, policyVersion: billingPolicy.version };
  }
  return {
    kind: "unsupported",
    reason: `no owner-verified credit bound for ${validatedRequest.operation}`,
  };
}

function sqlNumber(value: unknown): number {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    throw new GatewayError("BUDGET_UNAVAILABLE", "invalid ledger number");
  }
  return value;
}

function sqlText(value: unknown): string {
  if (typeof value !== "string") {
    throw new GatewayError("BUDGET_UNAVAILABLE", "invalid ledger text");
  }
  return value;
}

export class GeoBudget extends DurableObject<Env> {
  private failNextWrite = false;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.ensureSchema();
  }

  private ensureSchema(): void {
    this.ctx.storage.sql.exec(`
      CREATE TABLE IF NOT EXISTS meta (
        k TEXT PRIMARY KEY,
        v TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS reservations (
        id TEXT PRIMARY KEY,
        request_hmac TEXT NOT NULL,
        payload_hmac TEXT NOT NULL,
        operation TEXT NOT NULL,
        credits INTEGER NOT NULL,
        actor_hmac TEXT NOT NULL,
        ip_day_hmac TEXT NOT NULL,
        ip_minute_hmac TEXT NOT NULL,
        reserved_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL,
        dispatched INTEGER NOT NULL,
        dispatched_at INTEGER,
        finalized INTEGER NOT NULL,
        finalize_status TEXT
      );
      CREATE INDEX IF NOT EXISTS idx_reservations_expires ON reservations(expires_at);
      CREATE INDEX IF NOT EXISTS idx_reservations_request ON reservations(request_hmac);
      CREATE INDEX IF NOT EXISTS idx_reservations_actor ON reservations(actor_hmac, reserved_at);
      CREATE INDEX IF NOT EXISTS idx_reservations_window ON reservations(reserved_at);
    `);
  }

  async initializeLedger(input: {
    ledgerId: string;
    confirmedEmpty: boolean;
    limits?: Partial<OperationalLimits>;
  }): Promise<void> {
    if (!input.confirmedEmpty) {
      throw new GatewayError("BUDGET_UNAVAILABLE", "empty ledger must be operator-confirmed");
    }
    this.ctx.storage.transactionSync(() => {
      const existing = this.ctx.storage.sql
        .exec("SELECT v FROM meta WHERE k = 'ledger_id'")
        .toArray();
      if (existing.length > 0) {
        return;
      }
      this.ctx.storage.sql.exec("INSERT INTO meta (k, v) VALUES ('ledger_id', ?)", input.ledgerId);
      this.ctx.storage.sql.exec(
        "INSERT INTO meta (k, v) VALUES ('initialized_at', ?)",
        String(Date.now()),
      );
      if (input.limits) {
        this.ctx.storage.sql.exec(
          "INSERT INTO meta (k, v) VALUES ('limits_json', ?)",
          JSON.stringify({ ...DEFAULT_LIMITS, ...input.limits }),
        );
      }
    });
  }

  async setStoredLedgerId(ledgerId: string): Promise<void> {
    this.ctx.storage.sql.exec(
      "INSERT INTO meta (k, v) VALUES ('ledger_id', ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v",
      ledgerId,
    );
  }

  async failNextTransaction(): Promise<void> {
    this.failNextWrite = true;
  }

  async rehydrateSchema(): Promise<void> {
    this.ensureSchema();
  }

  async inspect(nowMs: number): Promise<LedgerInspect> {
    try {
      const ledger = this.ctx.storage.sql
        .exec("SELECT v FROM meta WHERE k = 'ledger_id'")
        .toArray();
      const used = this.ctx.storage.sql
        .exec(
          "SELECT COALESCE(SUM(credits), 0) AS used FROM reservations WHERE expires_at > ?",
          nowMs,
        )
        .toArray();
      const counts = this.ctx.storage.sql
        .exec(
          "SELECT COUNT(*) AS total, COALESCE(SUM(dispatched), 0) AS dispatched, COALESCE(SUM(finalized), 0) AS finalized FROM reservations",
        )
        .toArray();
      return {
        ledgerId: ledger[0] ? sqlText(ledger[0].v) : null,
        usedCredits: sqlNumber(used[0]?.used ?? 0),
        dispatchedCount: sqlNumber(counts[0]?.dispatched ?? 0),
        reservationCount: sqlNumber(counts[0]?.total ?? 0),
        finalizedCount: sqlNumber(counts[0]?.finalized ?? 0),
        sqlAvailable: true,
      };
    } catch {
      return {
        ledgerId: null,
        usedCredits: 0,
        dispatchedCount: 0,
        reservationCount: 0,
        finalizedCount: 0,
        sqlAvailable: false,
      };
    }
  }

  async admit(input: AdmitInput): Promise<AdmitResult> {
    if (this.failNextWrite) {
      this.failNextWrite = false;
      return { outcome: "unavailable", reason: "quota store failure" };
    }
    try {
      return this.ctx.storage.transactionSync(() => this.admitSync(input));
    } catch (error) {
      if (error instanceof GatewayError && error.code === "BUDGET_UNAVAILABLE") {
        return { outcome: "unavailable", reason: error.message };
      }
      return { outcome: "unavailable", reason: "quota store failure" };
    }
  }

  async dispatchSearch(input: {
    admit: AdmitInput;
    search: SearchRequest;
    provider: DispatchProviderConfig;
    fetchImpl?: typeof fetch;
  }): Promise<DispatchSearchResult> {
    const admit = await this.admit(input.admit);
    if (admit.outcome !== "admitted") {
      return { kind: "rejected", admit };
    }
    try {
      const result = await searchPlaces(input.search, {
        mode: input.provider.mode,
        apiKey: input.provider.apiKey,
        timeoutMs: input.provider.timeoutMs,
        behavior: input.provider.behavior,
        fetchImpl: input.fetchImpl,
      });
      return { kind: "admitted", reservationId: admit.reservationId, result };
    } catch (error) {
      return failedDispatch(admit.reservationId, error);
    }
  }

  async dispatchRoute(input: {
    admit: AdmitInput;
    route: RouteRequest;
    provider: DispatchProviderConfig;
    fetchImpl?: typeof fetch;
  }): Promise<DispatchRouteResult> {
    const admit = await this.admit(input.admit);
    if (admit.outcome !== "admitted") {
      return { kind: "rejected", admit };
    }
    try {
      const result = await planRoute(input.route, {
        mode: input.provider.mode,
        apiKey: input.provider.apiKey,
        timeoutMs: input.provider.timeoutMs,
        behavior: input.provider.behavior,
        fetchImpl: input.fetchImpl,
      });
      return { kind: "admitted", reservationId: admit.reservationId, result };
    } catch (error) {
      return failedDispatch(admit.reservationId, error);
    }
  }

  async finalize(input: FinalizeInput): Promise<void> {
    this.ctx.storage.transactionSync(() => {
      const rows = this.ctx.storage.sql
        .exec("SELECT id, finalized FROM reservations WHERE id = ?", input.reservationId)
        .toArray();
      const row = rows[0];
      if (!row) {
        return;
      }
      if (sqlNumber(row.finalized) === 1) {
        return;
      }
      this.ctx.storage.sql.exec(
        "UPDATE reservations SET finalized = 1, finalize_status = ? WHERE id = ?",
        input.status,
        input.reservationId,
      );
    });
  }

  async releaseIfNotDispatched(reservationId: string): Promise<boolean> {
    return this.ctx.storage.transactionSync(() => {
      const rows = this.ctx.storage.sql
        .exec("SELECT dispatched FROM reservations WHERE id = ?", reservationId)
        .toArray();
      const row = rows[0];
      if (!row) {
        return false;
      }
      if (sqlNumber(row.dispatched) === 1) {
        return false;
      }
      this.ctx.storage.sql.exec("DELETE FROM reservations WHERE id = ?", reservationId);
      return true;
    });
  }

  private readMeta(key: string): string | null {
    const rows = this.ctx.storage.sql.exec("SELECT v FROM meta WHERE k = ?", key).toArray();
    const value = rows[0]?.v;
    return typeof value === "string" ? value : null;
  }

  private resolveLimits(input: AdmitInput): OperationalLimits {
    const stored = this.readMeta("limits_json");
    if (!stored) {
      return input.limits;
    }
    const parsed = JSON.parse(stored) as Partial<OperationalLimits>;
    return { ...input.limits, ...parsed };
  }

  private cleanupExpired(nowMs: number): void {
    this.ctx.storage.sql.exec(
      "DELETE FROM reservations WHERE id IN (SELECT id FROM reservations WHERE expires_at <= ? LIMIT ?)",
      nowMs,
      CLEANUP_BATCH,
    );
  }

  private admitSync(input: AdmitInput): AdmitResult {
    if (input.credits <= 0 || !Number.isInteger(input.credits)) {
      return { outcome: "unavailable", reason: "missing budget config" };
    }
    this.cleanupExpired(input.nowMs);
    const storedLedger = this.readMeta("ledger_id");
    if (!storedLedger) {
      if (!input.allowEmptyInit) {
        return { outcome: "unavailable", reason: "ledger is not initialized" };
      }
      this.ctx.storage.sql.exec(
        "INSERT INTO meta (k, v) VALUES ('ledger_id', ?)",
        input.expectedLedgerId,
      );
      this.ctx.storage.sql.exec(
        "INSERT INTO meta (k, v) VALUES ('initialized_at', ?)",
        String(input.nowMs),
      );
    } else if (storedLedger !== input.expectedLedgerId) {
      return { outcome: "unavailable", reason: "unknown ledger" };
    }

    const limits = this.resolveLimits(input);
    if (!Number.isInteger(limits.creditBudget24h) || limits.creditBudget24h <= 0) {
      return { outcome: "unavailable", reason: "missing budget config" };
    }

    const existing = this.ctx.storage.sql
      .exec(
        "SELECT payload_hmac, dispatched FROM reservations WHERE request_hmac = ? AND expires_at > ?",
        input.requestHmac,
        input.nowMs,
      )
      .toArray();
    const first = existing[0];
    if (first) {
      if (sqlText(first.payload_hmac) !== input.payloadHmac) {
        return { outcome: "conflict" };
      }
      return { outcome: "duplicate" };
    }

    const usedRows = this.ctx.storage.sql
      .exec(
        "SELECT COALESCE(SUM(credits), 0) AS used FROM reservations WHERE expires_at > ?",
        input.nowMs,
      )
      .toArray();
    const used = sqlNumber(usedRows[0]?.used ?? 0);
    if (used + input.credits > limits.creditBudget24h) {
      const oldest = this.ctx.storage.sql
        .exec("SELECT MIN(expires_at) AS exp FROM reservations WHERE expires_at > ?", input.nowMs)
        .toArray();
      const expiresAt = sqlNumber(oldest[0]?.exp ?? input.nowMs + DAY_MS);
      return {
        outcome: "quota",
        retryAfterSeconds: Math.max(1, Math.ceil((expiresAt - input.nowMs) / 1000)),
      };
    }

    const clientLimit =
      input.operation === "search" ? limits.clientSearchLimit24h : limits.clientRouteLimit24h;
    const clientCount = sqlNumber(
      this.ctx.storage.sql
        .exec(
          "SELECT COUNT(*) AS n FROM reservations WHERE actor_hmac = ? AND operation = ? AND expires_at > ?",
          input.actorHmac,
          input.operation,
          input.nowMs,
        )
        .toArray()[0]?.n ?? 0,
    );
    if (clientCount >= clientLimit) {
      return { outcome: "rate", retryAfterSeconds: 60 };
    }

    const lastClient = this.ctx.storage.sql
      .exec(
        "SELECT MAX(reserved_at) AS last_at FROM reservations WHERE actor_hmac = ? AND expires_at > ?",
        input.actorHmac,
        input.nowMs,
      )
      .toArray();
    const lastClientAt = lastClient[0]?.last_at;
    if (
      typeof lastClientAt === "number" &&
      input.nowMs - lastClientAt < limits.clientMinIntervalMs
    ) {
      return {
        outcome: "rate",
        retryAfterSeconds: Math.max(
          1,
          Math.ceil((limits.clientMinIntervalMs - (input.nowMs - lastClientAt)) / 1000),
        ),
      };
    }

    const ipCount = sqlNumber(
      this.ctx.storage.sql
        .exec(
          "SELECT COUNT(*) AS n FROM reservations WHERE ip_minute_hmac = ? AND reserved_at > ?",
          input.ipMinuteHmac,
          input.nowMs - 60_000,
        )
        .toArray()[0]?.n ?? 0,
    );
    if (ipCount >= limits.ipRequestLimitPerMinute) {
      return { outcome: "rate", retryAfterSeconds: 60 };
    }

    const inFlightDeadline = input.nowMs;
    const inFlight = sqlNumber(
      this.ctx.storage.sql
        .exec(
          "SELECT COUNT(*) AS n FROM reservations WHERE dispatched = 1 AND finalized = 0 AND dispatched_at IS NOT NULL AND dispatched_at + ? > ?",
          limits.providerTimeoutMs + limits.clockMarginMs,
          inFlightDeadline,
        )
        .toArray()[0]?.n ?? 0,
    );
    if (inFlight >= limits.maxInFlightGeo) {
      return {
        outcome: "rate",
        retryAfterSeconds: Math.max(1, Math.ceil(limits.providerTimeoutMs / 1000)),
      };
    }

    const lastGlobal = this.ctx.storage.sql
      .exec("SELECT MAX(dispatched_at) AS last_at FROM reservations WHERE dispatched = 1")
      .toArray();
    const lastGlobalAt = lastGlobal[0]?.last_at;
    if (
      typeof lastGlobalAt === "number" &&
      limits.globalMinIntervalMs > 0 &&
      input.nowMs - lastGlobalAt < limits.globalMinIntervalMs
    ) {
      return {
        outcome: "rate",
        retryAfterSeconds: Math.max(
          1,
          Math.ceil((limits.globalMinIntervalMs - (input.nowMs - lastGlobalAt)) / 1000),
        ),
      };
    }

    const reservationId = crypto.randomUUID();
    const expiresAt = input.nowMs + DAY_MS + limits.providerTimeoutMs + limits.clockMarginMs;
    this.ctx.storage.sql.exec(
      `INSERT INTO reservations (
        id, request_hmac, payload_hmac, operation, credits, actor_hmac, ip_day_hmac, ip_minute_hmac,
        reserved_at, expires_at, dispatched, dispatched_at, finalized
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, 0)`,
      reservationId,
      input.requestHmac,
      input.payloadHmac,
      input.operation,
      input.credits,
      input.actorHmac,
      input.ipDayHmac,
      input.ipMinuteHmac,
      input.nowMs,
      expiresAt,
      input.nowMs,
    );
    return { outcome: "admitted", reservationId };
  }
}

function failedDispatch(
  reservationId: string,
  error: unknown,
): { kind: "failed"; reservationId: string; code: ErrorCode; message: string } {
  return {
    kind: "failed",
    reservationId,
    code: error instanceof GatewayError ? error.code : "PROVIDER_UNAVAILABLE",
    message: error instanceof Error ? error.message : "provider failed",
  };
}
