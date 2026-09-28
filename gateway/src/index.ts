import {
  CONFIG_CACHE_SECONDS,
  parseBillingPolicy,
  parseBooleanFlag,
  publicConfig,
  PURCHASE_BODY_LIMIT_BYTES,
  readClockMs,
  readLimits,
} from "./config";
import {
  GatewayError,
  PROVIDER_REVISION,
  ROUTE_RESPONSE_MAX_BYTES,
  SEARCH_RESPONSE_MAX_BYTES,
  type BillingPolicy,
  type PurchaseVerifyResponse,
  type RouteResponse,
  type SearchResponse,
} from "./contracts/index";
import { hmacHex } from "./crypto-utils";
import { estimateMaximumCredits, GeoBudget } from "./geo-budget";
import {
  bodyLimitForPath,
  errorFromUnknown,
  jsonResponse,
  readBoundedBody,
  readRequestContext,
  typedError,
} from "./http";
import { logSafe } from "./logging";
import { Purchase } from "./purchase-state";
import { planRoute, searchPlaces, type ProviderBehavior } from "./providers/geoapify";
import {
  assertUuid,
  canonicalPayload,
  parseJsonObject,
  parseRouteRequest,
  parseSearchRequest,
  trustedClientIp,
} from "./validation";

export { GeoBudget, Purchase };

const TEST_ROUTE_POLICY: BillingPolicy = {
  version: "test-budget-accounting-v1",
  evidence: "TEST_ONLY_BUDGET_ACCOUNTING",
  documentedMaximumCredits: { route: 1, search: 1 },
};

function testHeader(request: Request, env: Env, name: string): string | null {
  if (env.ENVIRONMENT !== "test") {
    return null;
  }
  return request.headers.get(name);
}

function ledgerName(request: Request, env: Env): string {
  return testHeader(request, env, "X-Test-Ledger") ?? env.GEO_LEDGER_ID;
}

function billingPolicyFor(
  request: Request,
  env: Env,
  operation: "search" | "route",
): BillingPolicy | undefined {
  const override = testHeader(request, env, "X-Test-Billing-Policy");
  if (override) {
    return parseBillingPolicy(override);
  }
  if (operation === "route" && testHeader(request, env, "X-Test-Allow-Route") === "1") {
    return TEST_ROUTE_POLICY;
  }
  return parseBillingPolicy(env.BILLING_POLICY_JSON);
}

function providerBehavior(request: Request, env: Env): ProviderBehavior | undefined {
  const value = testHeader(request, env, "X-Test-Provider-Behavior");
  if (
    value === "ok" ||
    value === "timeout" ||
    value === "http500" ||
    value === "invalid-json" ||
    value === "oversized" ||
    value === "nested" ||
    value === "infinite" ||
    value === "no-route" ||
    value === "geometry-gap" ||
    value === "negative-distance" ||
    value === "lost"
  ) {
    return value;
  }
  return undefined;
}

function rateSecret(env: Env): string {
  if (!env.RATE_KEY_HMAC_SECRET) {
    if (env.ENVIRONMENT === "production") {
      throw new GatewayError("BUDGET_UNAVAILABLE", "rate key secret missing");
    }
    return "local-dev-rate-key-not-for-production";
  }
  return env.RATE_KEY_HMAC_SECRET;
}

function dateKey(nowMs: number): string {
  return new Date(nowMs).toISOString().slice(0, 10);
}

function minuteKey(nowMs: number): string {
  return new Date(nowMs).toISOString().slice(0, 16);
}

async function geoBudgetStub(request: Request, env: Env): Promise<DurableObjectStub<GeoBudget>> {
  const name = ledgerName(request, env);
  const id = env.GEO_BUDGET.idFromName(name);
  return env.GEO_BUDGET.get(id) as DurableObjectStub<GeoBudget>;
}

async function purchaseStub(env: Env): Promise<DurableObjectStub<Purchase>> {
  const id = env.PURCHASE.idFromName("purchase-v1");
  return env.PURCHASE.get(id) as DurableObjectStub<Purchase>;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    let requestId = request.headers.get("X-Request-ID")?.trim() ?? "";
    try {
      if (url.pathname === "/v1/config" && request.method === "GET") {
        if (requestId !== "") {
          assertUuid(requestId, "X-Request-ID");
        } else {
          requestId = "00000000-0000-4000-8000-000000000000";
        }
        return jsonResponse(publicConfig(env), 200, requestId, {
          "Cache-Control": `public, max-age=${CONFIG_CACHE_SECONDS}`,
        });
      }

      const context = readRequestContext(request, true);
      requestId = context.requestId;
      if (url.pathname === "/v1/search" && request.method === "POST") {
        return await handleSearch(request, env, context);
      }
      if (url.pathname === "/v1/route" && request.method === "POST") {
        return await handleRoute(request, env, context);
      }
      if (url.pathname === "/v1/purchases/verify" && request.method === "POST") {
        return await handlePurchase(request, env, context);
      }
      throw new GatewayError("INVALID_REQUEST", "unknown route");
    } catch (error) {
      logSafe("request_error", {
        path: url.pathname,
        method: request.method,
        requestId,
      });
      return errorFromUnknown(requestId || "00000000-0000-4000-8000-000000000000", error);
    }
  },
};

async function handleSearch(
  request: Request,
  env: Env,
  context: { requestId: string; installId: string },
): Promise<Response> {
  if (!parseBooleanFlag(env.SEARCH_ENABLED)) {
    throw typedError("SERVICE_DISABLED");
  }
  const raw = await readBoundedBody(request, bodyLimitForPath("/v1/search"));
  const body = parseJsonObject(raw);
  const search = parseSearchRequest(body);
  const policy = billingPolicyFor(request, env, "search");
  const estimate = estimateMaximumCredits({ operation: "search" }, policy);
  if (estimate.kind === "unsupported") {
    throw typedError("SERVICE_DISABLED");
  }
  const reservation = await admitGeo(request, env, context, "search", estimate.credits, search);
  const behavior = providerBehavior(request, env);
  try {
    const result = await searchPlaces(search, {
      mode: env.GEO_PROVIDER_MODE === "live" ? "live" : "stub",
      apiKey: env.GEOAPIFY_API_KEY,
      timeoutMs: readLimits(env).providerTimeoutMs,
      behavior,
    });
    if (behavior === "lost") {
      await reservation.finalize("lost");
      throw typedError("PROVIDER_UNAVAILABLE");
    }
    const response: SearchResponse = {
      requestId: context.requestId,
      providerRevision: result.providerRevision,
      results: result.results,
      attribution: result.attribution,
    };
    const encoded = JSON.stringify(response);
    if (new TextEncoder().encode(encoded).byteLength > SEARCH_RESPONSE_MAX_BYTES) {
      await reservation.finalize("oversized");
      throw typedError("INVALID_PROVIDER_RESPONSE");
    }
    await reservation.finalize("ok");
    logSafe("search_ok", { requestId: context.requestId, resultCount: result.results.length });
    return jsonResponse(response, 200, context.requestId, { "Cache-Control": "no-store" });
  } catch (error) {
    await reservation.finalize(error instanceof GatewayError ? error.code : "unknown");
    throw error;
  }
}

async function handleRoute(
  request: Request,
  env: Env,
  context: { requestId: string; installId: string },
): Promise<Response> {
  if (!parseBooleanFlag(env.ROUTING_ENABLED)) {
    throw typedError("SERVICE_DISABLED");
  }
  const raw = await readBoundedBody(request, bodyLimitForPath("/v1/route"));
  const body = parseJsonObject(raw);
  const route = parseRouteRequest(body);
  const policy = billingPolicyFor(request, env, "route");
  const estimate = estimateMaximumCredits({ operation: "route" }, policy);
  if (estimate.kind === "unsupported") {
    throw typedError("SERVICE_DISABLED");
  }
  const reservation = await admitGeo(request, env, context, "route", estimate.credits, route);
  const behavior = providerBehavior(request, env);
  try {
    const result = await planRoute(route, {
      mode: env.GEO_PROVIDER_MODE === "live" ? "live" : "stub",
      apiKey: env.GEOAPIFY_API_KEY,
      timeoutMs: readLimits(env).providerTimeoutMs,
      behavior,
    });
    if (behavior === "lost") {
      await reservation.finalize("lost");
      throw typedError("PROVIDER_UNAVAILABLE");
    }
    const response: RouteResponse = {
      requestId: context.requestId,
      providerRevision: result.providerRevision || PROVIDER_REVISION,
      mode: result.mode,
      points: result.points,
      distanceMeters: result.distanceMeters,
      providerDurationSeconds: result.providerDurationSeconds,
      attribution: result.attribution,
    };
    const encoded = JSON.stringify(response);
    if (new TextEncoder().encode(encoded).byteLength > ROUTE_RESPONSE_MAX_BYTES) {
      await reservation.finalize("oversized");
      throw typedError("INVALID_PROVIDER_RESPONSE");
    }
    await reservation.finalize("ok");
    logSafe("route_ok", { requestId: context.requestId, pointCount: result.points.length });
    return jsonResponse(response, 200, context.requestId, { "Cache-Control": "no-store" });
  } catch (error) {
    await reservation.finalize(error instanceof GatewayError ? error.code : "unknown");
    throw error;
  }
}

async function admitGeo(
  request: Request,
  env: Env,
  context: { requestId: string; installId: string },
  operation: "search" | "route",
  credits: number,
  payload: unknown,
): Promise<{ finalize: (status: string) => Promise<void> }> {
  const nowMs = readClockMs(env);
  const secret = rateSecret(env);
  const ip = trustedClientIp(request);
  const stub = await geoBudgetStub(request, env);
  const result = await stub.admit({
    expectedLedgerId: ledgerName(request, env),
    allowEmptyInit:
      parseBooleanFlag(env.ALLOW_EMPTY_LEDGER_INIT) && env.ENVIRONMENT !== "production",
    nowMs,
    operation,
    credits,
    requestHmac: await hmacHex(secret, `request:${context.requestId}`),
    payloadHmac: await hmacHex(secret, canonicalPayload(payload)),
    actorHmac: await hmacHex(secret, `install:${context.installId}`),
    ipDayHmac: await hmacHex(secret, `ip:${ip}:${dateKey(nowMs)}`),
    ipMinuteHmac: await hmacHex(secret, `ip:${ip}:${minuteKey(nowMs)}`),
    limits: readLimits(env),
  });
  if (result.outcome === "unavailable") {
    throw typedError("BUDGET_UNAVAILABLE");
  }
  if (result.outcome === "quota") {
    throw typedError("GLOBAL_QUOTA_EXHAUSTED", result.retryAfterSeconds);
  }
  if (result.outcome === "rate") {
    throw typedError("CLIENT_RATE_LIMITED", result.retryAfterSeconds);
  }
  if (result.outcome === "conflict") {
    throw new GatewayError("INVALID_REQUEST", "request id payload conflict", {
      requestConflict: true,
    });
  }
  if (result.outcome === "duplicate") {
    throw typedError("CLIENT_RATE_LIMITED", 60);
  }
  const reservationId = result.reservationId;
  return {
    finalize: async (status: string) => {
      await stub.finalize({ reservationId, nowMs: readClockMs(env), status });
    },
  };
}

async function handlePurchase(
  request: Request,
  env: Env,
  context: { requestId: string; installId: string },
): Promise<Response> {
  if (!parseBooleanFlag(env.PURCHASES_ENABLED)) {
    throw typedError("SERVICE_DISABLED");
  }
  const raw = await readBoundedBody(request, PURCHASE_BODY_LIMIT_BYTES);
  const body = parseJsonObject(raw);
  if (typeof body.purchaseToken !== "string" || body.purchaseToken.trim() === "") {
    throw typedError("INVALID_REQUEST");
  }
  if (typeof body.installId !== "string") {
    throw typedError("INVALID_REQUEST");
  }
  assertUuid(body.installId, "installId");
  if (body.installId !== context.installId) {
    throw typedError("INVALID_REQUEST");
  }
  const stub = await purchaseStub(env);
  const nowMs = readClockMs(env);
  const outcome = await stub.verify({
    nowMs,
    purchaseToken: body.purchaseToken,
    installId: body.installId,
    hmacSecret: rateSecret(env),
    encryptionKey: env.PURCHASE_TOKEN_ENCRYPTION_KEY ?? "",
    playMode: env.PLAY_API_MODE === "live" ? "live" : "stub",
    stubTokensJson: env.GOOGLE_STUB_TOKENS_JSON,
    serviceAccountEmail: env.PLAY_SERVICE_ACCOUNT_EMAIL,
    serviceAccountPrivateKey: env.PLAY_SERVICE_ACCOUNT_PRIVATE_KEY,
    receiptPrivateKey: env.RECEIPT_SIGNING_PRIVATE_KEY,
    receiptKid: env.RECEIPT_SIGNING_KID,
    receiptIssuer: env.RECEIPT_ISSUER,
    receiptAudience: env.RECEIPT_AUDIENCE,
  });
  if (outcome.kind === "error") {
    throw new GatewayError(outcome.code, outcome.message, {
      retryAfterSeconds: outcome.retryAfterSeconds,
    });
  }
  const verified = outcome.result;
  const response: PurchaseVerifyResponse = {
    status: verified.status,
    acknowledgementPending: verified.acknowledgementPending,
    serverTime: verified.serverTime,
  };
  if (verified.status === "owned" && verified.receipt) {
    response.receipt = verified.receipt;
  }
  logSafe("purchase_verify", {
    requestId: context.requestId,
    status: verified.status,
    acknowledgementPending: verified.acknowledgementPending,
  });
  return jsonResponse(response, 200, context.requestId, { "Cache-Control": "no-store" });
}
