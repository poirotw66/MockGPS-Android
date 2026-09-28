import { ALLOWED_PACKAGE_NAME, ALLOWED_PRODUCT_ID, GatewayError } from "./contracts/index";
import { signRs256, toBase64Url } from "./crypto-utils";
import type { GooglePurchaseLookup, GooglePurchaseState } from "./purchase-verifier";

const TOKEN_URL = "https://oauth2.googleapis.com/token";
const PUBLISHER_SCOPE = "https://www.googleapis.com/auth/androidpublisher";
const APPLICATIONS_URL = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications";
const PLAY_HTTP_TIMEOUT_MS = 10_000;

interface CachedAccessToken {
  value: string;
  expiresAtMs: number;
}

let cachedAccessToken: CachedAccessToken | null = null;

export function resetGoogleAccessTokenCache(): void {
  cachedAccessToken = null;
}

function asRecord(value: unknown): Record<string, unknown> | null {
  if (typeof value !== "object" || value === null) {
    return null;
  }
  return value as Record<string, unknown>;
}

function mapPurchaseState(raw: unknown): GooglePurchaseState {
  if (raw === "PURCHASED") {
    return "PURCHASED";
  }
  if (raw === "PENDING") {
    return "PENDING";
  }
  if (raw === "CANCELLED") {
    return "REVOKED";
  }
  throw new GatewayError("PROVIDER_UNAVAILABLE", "google purchase state unknown");
}

function matchingProductLine(record: Record<string, unknown>): Record<string, unknown> {
  const items = record.productLineItem;
  if (!Array.isArray(items) || items.length === 0) {
    throw new GatewayError("PROVIDER_UNAVAILABLE", "google purchase line items missing");
  }
  const matching = items.find((item) => {
    const line = asRecord(item);
    return line?.productId === ALLOWED_PRODUCT_ID;
  });
  const line = asRecord(matching);
  if (!line) {
    throw new GatewayError("INVALID_REQUEST", "product is not allowlisted");
  }
  return line;
}

function quantityFromOffer(offer: Record<string, unknown> | null): number {
  if (!offer) {
    return 1;
  }
  if (offer.rentOfferDetails !== undefined && offer.rentOfferDetails !== null) {
    throw new GatewayError("INVALID_REQUEST", "rental purchases are not supported");
  }
  if (offer.consumptionState === "CONSUMPTION_STATE_CONSUMED") {
    throw new GatewayError("INVALID_REQUEST", "consumed purchases are not supported");
  }
  const quantity = offer.quantity;
  if (quantity === undefined || quantity === null) {
    return 1;
  }
  if (typeof quantity !== "number" || !Number.isInteger(quantity) || quantity <= 0) {
    throw new GatewayError("PROVIDER_UNAVAILABLE", "google purchase quantity invalid");
  }
  return quantity;
}

/**
 * Maps ProductPurchaseV2 from purchases.productsv2.getproductpurchasev2 [S17].
 * Unknown / unspecified / malformed state is unavailable, never revoked.
 */
export function mapGoogleProductPurchaseV2(
  parsed: unknown,
  options?: { allowTestPurchases?: boolean },
): GooglePurchaseLookup {
  const record = asRecord(parsed);
  if (!record) {
    throw new GatewayError("PROVIDER_UNAVAILABLE", "google purchase payload invalid");
  }
  if (record.testPurchaseContext !== undefined && record.testPurchaseContext !== null) {
    if (options?.allowTestPurchases !== true) {
      throw new GatewayError("PROVIDER_UNAVAILABLE", "test purchase not allowed");
    }
  }
  const stateContext = asRecord(record.purchaseStateContext);
  if (!stateContext) {
    throw new GatewayError("PROVIDER_UNAVAILABLE", "google purchase state missing");
  }
  const state = mapPurchaseState(stateContext.purchaseState);
  const line = matchingProductLine(record);
  const offer = asRecord(line.productOfferDetails);
  return {
    state,
    acknowledged: record.acknowledgementState === "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
    productId: ALLOWED_PRODUCT_ID,
    packageName: ALLOWED_PACKAGE_NAME,
    quantity: quantityFromOffer(offer),
  };
}

async function googleAccessToken(input: {
  email: string;
  privateKeyPem: string;
  nowMs: number;
  fetchImpl: typeof fetch;
}): Promise<string> {
  if (cachedAccessToken && cachedAccessToken.expiresAtMs - 60_000 > input.nowMs) {
    return cachedAccessToken.value;
  }
  const issuedAt = Math.floor(input.nowMs / 1000);
  const header = toBase64Url(
    new TextEncoder().encode(JSON.stringify({ alg: "RS256", typ: "JWT" })),
  );
  const claims = toBase64Url(
    new TextEncoder().encode(
      JSON.stringify({
        iss: input.email,
        scope: PUBLISHER_SCOPE,
        aud: TOKEN_URL,
        iat: issuedAt,
        exp: issuedAt + 3600,
      }),
    ),
  );
  const signingInput = `${header}.${claims}`;
  const signature = await signRs256(input.privateKeyPem, signingInput);
  const assertion = `${signingInput}.${toBase64Url(signature)}`;
  const body = new URLSearchParams({
    grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
    assertion,
  });
  const response = await input.fetchImpl(TOKEN_URL, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: body.toString(),
  });
  if (!response.ok) {
    throw new GatewayError("PROVIDER_UNAVAILABLE", "google oauth unavailable");
  }
  const parsed: unknown = await response.json();
  const tokenRecord = asRecord(parsed);
  const token = tokenRecord?.access_token;
  const expiresIn = tokenRecord?.expires_in;
  if (typeof token !== "string" || token.length === 0) {
    throw new GatewayError("PROVIDER_UNAVAILABLE", "google oauth token missing");
  }
  const lifetimeMs =
    typeof expiresIn === "number" && Number.isFinite(expiresIn) ? expiresIn * 1000 : 3_000_000;
  cachedAccessToken = { value: token, expiresAtMs: input.nowMs + lifetimeMs };
  return token;
}

function publisherProductV2Url(purchaseToken: string): string {
  return `${APPLICATIONS_URL}/${encodeURIComponent(ALLOWED_PACKAGE_NAME)}/purchases/productsv2/tokens/${encodeURIComponent(purchaseToken)}`;
}

function publisherAcknowledgeUrl(purchaseToken: string): string {
  return `${APPLICATIONS_URL}/${encodeURIComponent(ALLOWED_PACKAGE_NAME)}/purchases/products/${encodeURIComponent(ALLOWED_PRODUCT_ID)}/tokens/${encodeURIComponent(purchaseToken)}:acknowledge`;
}

function playFetchInit(accessToken: string, method: "GET" | "POST", body?: string): RequestInit {
  const headers: Record<string, string> = { authorization: `Bearer ${accessToken}` };
  if (method === "POST") {
    headers["content-type"] = "application/json";
  }
  return {
    method,
    headers,
    body,
    redirect: "error",
    signal: AbortSignal.timeout(PLAY_HTTP_TIMEOUT_MS),
  };
}

function mapPlayHttpStatus(status: number, action: "lookup" | "acknowledge"): never {
  if (status === 401 || status === 403 || status === 404 || status === 429 || status >= 500) {
    throw new GatewayError("PROVIDER_UNAVAILABLE", `google purchase ${action} unavailable`);
  }
  throw new GatewayError("PROVIDER_UNAVAILABLE", `google purchase ${action} failed`);
}

export async function lookupLiveGooglePurchase(input: {
  purchaseToken: string;
  serviceAccountEmail: string;
  serviceAccountPrivateKey: string;
  nowMs?: number;
  fetchImpl?: typeof fetch;
  allowTestPurchases?: boolean;
}): Promise<GooglePurchaseLookup> {
  const fetchImpl = input.fetchImpl ?? fetch;
  const nowMs = input.nowMs ?? Date.now();
  try {
    const accessToken = await googleAccessToken({
      email: input.serviceAccountEmail,
      privateKeyPem: input.serviceAccountPrivateKey,
      nowMs,
      fetchImpl,
    });
    const response = await fetchImpl(
      publisherProductV2Url(input.purchaseToken),
      playFetchInit(accessToken, "GET"),
    );
    if (!response.ok) {
      mapPlayHttpStatus(response.status, "lookup");
    }
    return mapGoogleProductPurchaseV2(await response.json(), {
      allowTestPurchases: input.allowTestPurchases === true,
    });
  } catch (error) {
    if (error instanceof GatewayError) {
      throw error;
    }
    throw new GatewayError("PROVIDER_UNAVAILABLE", "google purchase lookup failed");
  }
}

export async function acknowledgeLiveGooglePurchase(input: {
  purchaseToken: string;
  serviceAccountEmail: string;
  serviceAccountPrivateKey: string;
  nowMs?: number;
  fetchImpl?: typeof fetch;
}): Promise<"acked" | "failed"> {
  const fetchImpl = input.fetchImpl ?? fetch;
  const nowMs = input.nowMs ?? Date.now();
  try {
    const accessToken = await googleAccessToken({
      email: input.serviceAccountEmail,
      privateKeyPem: input.serviceAccountPrivateKey,
      nowMs,
      fetchImpl,
    });
    const response = await fetchImpl(
      publisherAcknowledgeUrl(input.purchaseToken),
      playFetchInit(accessToken, "POST", "{}"),
    );
    if (response.ok || response.status === 400) {
      return "acked";
    }
    return "failed";
  } catch {
    return "failed";
  }
}
