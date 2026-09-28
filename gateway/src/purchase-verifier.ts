import {
  ALLOWED_PACKAGE_NAME,
  ALLOWED_PRODUCT_ID,
  GatewayError,
  RECEIPT_VERSION,
  type PurchaseStatus,
  type ReceiptClaims,
} from "./contracts/index";
import { fromBase64Url, signEs256, toBase64Url, verifyEs256 } from "./crypto-utils";
import { acknowledgeLiveGooglePurchase, lookupLiveGooglePurchase } from "./play-api";

export type GooglePurchaseState = "PURCHASED" | "PENDING" | "REVOKED" | "UNAVAILABLE";

export interface GooglePurchaseLookup {
  state: GooglePurchaseState;
  acknowledged: boolean;
  productId: string;
  packageName: string;
  quantity: number;
}

export interface StubTokenRecord {
  state: GooglePurchaseState;
  acknowledged?: boolean;
  ackFails?: boolean;
  productId?: string;
  packageName?: string;
  quantity?: number;
}

export interface ReceiptKey {
  kid: string;
  publicKeyPem: string;
}

export function parseStubTokens(raw: string | undefined): Record<string, StubTokenRecord> {
  if (!raw || raw.trim() === "") {
    return {};
  }
  const parsed = JSON.parse(raw) as unknown;
  if (typeof parsed !== "object" || parsed === null) {
    return {};
  }
  return parsed as Record<string, StubTokenRecord>;
}

export async function lookupGooglePurchase(input: {
  mode: "stub" | "live";
  purchaseToken: string;
  stubs: Record<string, StubTokenRecord>;
  serviceAccountEmail?: string;
  serviceAccountPrivateKey?: string;
  nowMs?: number;
  fetchImpl?: typeof fetch;
  allowTestPurchases?: boolean;
}): Promise<GooglePurchaseLookup> {
  if (input.mode === "live") {
    if (!input.serviceAccountEmail || !input.serviceAccountPrivateKey) {
      throw new GatewayError("PROVIDER_UNAVAILABLE", "play secrets missing");
    }
    return lookupLiveGooglePurchase({
      purchaseToken: input.purchaseToken,
      serviceAccountEmail: input.serviceAccountEmail,
      serviceAccountPrivateKey: input.serviceAccountPrivateKey,
      nowMs: input.nowMs,
      fetchImpl: input.fetchImpl,
      allowTestPurchases: input.allowTestPurchases === true,
    });
  }
  const stub = input.stubs[input.purchaseToken];
  if (!stub) {
    throw new GatewayError("PROVIDER_UNAVAILABLE", "unknown purchase token");
  }
  if (stub.state === "UNAVAILABLE") {
    throw new GatewayError("PROVIDER_UNAVAILABLE", "google lookup unavailable");
  }
  return {
    state: stub.state,
    acknowledged: stub.acknowledged === true,
    productId: stub.productId ?? ALLOWED_PRODUCT_ID,
    packageName: stub.packageName ?? ALLOWED_PACKAGE_NAME,
    quantity: stub.quantity ?? 1,
  };
}

export function assertAllowlistedPurchase(lookup: GooglePurchaseLookup): void {
  if (lookup.packageName !== ALLOWED_PACKAGE_NAME || lookup.productId !== ALLOWED_PRODUCT_ID) {
    throw new GatewayError("INVALID_REQUEST", "product is not allowlisted");
  }
  if (lookup.quantity !== 1) {
    throw new GatewayError("INVALID_REQUEST", "unexpected quantity");
  }
}

export function statusFromGoogle(state: GooglePurchaseState): PurchaseStatus {
  if (state === "PURCHASED") {
    return "owned";
  }
  if (state === "PENDING") {
    return "pending";
  }
  return "revoked";
}

export async function signReceipt(input: {
  privateKeyPem: string;
  kid: string;
  claims: ReceiptClaims;
}): Promise<string> {
  const header = { alg: "ES256", kid: input.kid };
  const signingInput = `${toBase64Url(bytesFromJson(header))}.${toBase64Url(bytesFromJson(input.claims))}`;
  const signature = await signEs256(input.privateKeyPem, signingInput);
  return `${signingInput}.${toBase64Url(signature)}`;
}

export async function verifyReceipt(receipt: string, keys: ReceiptKey[]): Promise<ReceiptClaims> {
  const parts = receipt.split(".");
  if (parts.length !== 3 || !parts[0] || !parts[1] || !parts[2]) {
    throw new GatewayError("INVALID_REQUEST", "invalid receipt");
  }
  const header = JSON.parse(new TextDecoder().decode(fromBase64Url(parts[0]))) as { kid?: string };
  if (typeof header.kid !== "string") {
    throw new GatewayError("INVALID_REQUEST", "receipt kid missing");
  }
  const key = keys.find((item) => item.kid === header.kid);
  if (!key) {
    throw new GatewayError("INVALID_REQUEST", "unknown receipt kid");
  }
  const signingInput = `${parts[0]}.${parts[1]}`;
  const ok = await verifyEs256(key.publicKeyPem, signingInput, fromBase64Url(parts[2]));
  if (!ok) {
    throw new GatewayError("INVALID_REQUEST", "receipt signature invalid");
  }
  const claims = JSON.parse(new TextDecoder().decode(fromBase64Url(parts[1]))) as ReceiptClaims;
  if (
    typeof claims.iss !== "string" ||
    typeof claims.aud !== "string" ||
    typeof claims.productId !== "string" ||
    typeof claims.installId !== "string" ||
    typeof claims.verifiedAt !== "string" ||
    claims.receiptVersion !== RECEIPT_VERSION
  ) {
    throw new GatewayError("INVALID_REQUEST", "receipt claims invalid");
  }
  return claims;
}

export function parseVerifyKeys(raw: string | undefined): ReceiptKey[] {
  if (!raw || raw.trim() === "") {
    return [];
  }
  const parsed = JSON.parse(raw) as unknown;
  if (!Array.isArray(parsed)) {
    return [];
  }
  return parsed.filter((item): item is ReceiptKey => {
    return (
      typeof item === "object" &&
      item !== null &&
      typeof (item as ReceiptKey).kid === "string" &&
      typeof (item as ReceiptKey).publicKeyPem === "string"
    );
  });
}

export function buildReceiptClaims(input: {
  issuer: string;
  audience: string;
  installId: string;
  verifiedAt: string;
}): ReceiptClaims {
  return {
    iss: input.issuer,
    aud: input.audience,
    productId: ALLOWED_PRODUCT_ID,
    installId: input.installId,
    verifiedAt: input.verifiedAt,
    receiptVersion: RECEIPT_VERSION,
  };
}

function bytesFromJson(value: unknown): Uint8Array {
  return new TextEncoder().encode(JSON.stringify(value));
}

export async function acknowledgeGooglePurchase(input: {
  mode: "stub" | "live";
  purchaseToken: string;
  stubs: Record<string, StubTokenRecord>;
  serviceAccountEmail?: string;
  serviceAccountPrivateKey?: string;
  nowMs?: number;
  fetchImpl?: typeof fetch;
}): Promise<"acked" | "failed"> {
  if (input.mode === "live") {
    if (!input.serviceAccountEmail || !input.serviceAccountPrivateKey) {
      return "failed";
    }
    return acknowledgeLiveGooglePurchase({
      purchaseToken: input.purchaseToken,
      serviceAccountEmail: input.serviceAccountEmail,
      serviceAccountPrivateKey: input.serviceAccountPrivateKey,
      nowMs: input.nowMs,
      fetchImpl: input.fetchImpl,
    });
  }
  const stub = input.stubs[input.purchaseToken];
  if (!stub || stub.state !== "PURCHASED") {
    return "failed";
  }
  if (stub.ackFails === true) {
    return "failed";
  }
  stub.acknowledged = true;
  return "acked";
}
