import { env, runInDurableObject, SELF } from "cloudflare:test";
import { DEFAULT_LIMITS, type OperationalLimits } from "../src/config";
import type { GeoBudget } from "../src/geo-budget";
import type { Purchase } from "../src/purchase-state";
import searchRequest from "./fixtures/search-request.json";
import routeRequest from "./fixtures/route-request.json";
export {
  TEST_RECEIPT_PRIVATE_KEY,
  TEST_RECEIPT_PRIVATE_KEY_2,
  TEST_RECEIPT_PUBLIC_KEY,
  TEST_RECEIPT_PUBLIC_KEY_2,
} from "./test-keys";

export function uuid(): string {
  return crypto.randomUUID();
}

export function geoHeaders(
  extra?: Record<string, string>,
  ids?: { requestId?: string; installId?: string },
): Record<string, string> {
  return {
    "content-type": "application/json",
    "X-Install-ID": ids?.installId ?? uuid(),
    "X-Client-Version": "test/1.0.0",
    "X-Request-ID": ids?.requestId ?? uuid(),
    "CF-Connecting-IP": "203.0.113.10",
    ...extra,
  };
}

export async function search(
  extraHeaders?: Record<string, string>,
  body: unknown = searchRequest,
  ids?: { requestId?: string; installId?: string },
): Promise<Response> {
  return SELF.fetch("https://gateway.test/v1/search", {
    method: "POST",
    headers: geoHeaders(extraHeaders, ids),
    body: typeof body === "string" ? body : JSON.stringify(body),
  });
}

export async function route(
  extraHeaders?: Record<string, string>,
  body: unknown = routeRequest,
  ids?: { requestId?: string; installId?: string },
): Promise<Response> {
  return SELF.fetch("https://gateway.test/v1/route", {
    method: "POST",
    headers: geoHeaders(extraHeaders, ids),
    body: typeof body === "string" ? body : JSON.stringify(body),
  });
}

export async function purchase(
  extraHeaders?: Record<string, string>,
  body?: unknown,
  ids?: { requestId?: string; installId?: string },
): Promise<Response> {
  const installId = ids?.installId ?? "11111111-1111-4111-8111-111111111111";
  return SELF.fetch("https://gateway.test/v1/purchases/verify", {
    method: "POST",
    headers: geoHeaders(extraHeaders, { ...ids, installId }),
    body: JSON.stringify(
      body ?? {
        purchaseToken: "purchased-token-001",
        installId,
      },
    ),
  });
}

export function budgetStub(ledger: string): DurableObjectStub<GeoBudget> {
  return env.GEO_BUDGET.get(env.GEO_BUDGET.idFromName(ledger)) as DurableObjectStub<GeoBudget>;
}

export function purchaseDo(): DurableObjectStub<Purchase> {
  return env.PURCHASE.get(env.PURCHASE.idFromName("purchase-v1")) as DurableObjectStub<Purchase>;
}

export async function initLedger(
  ledger: string,
  limits?: Partial<OperationalLimits>,
): Promise<DurableObjectStub<GeoBudget>> {
  const stub = budgetStub(ledger);
  await runInDurableObject(stub, async (instance) => {
    await instance.initializeLedger({
      ledgerId: ledger,
      confirmedEmpty: true,
      limits: { ...DEFAULT_LIMITS, globalMinIntervalMs: 0, clientMinIntervalMs: 0, ...limits },
    });
  });
  return stub;
}

export async function jsonOf<T>(response: Response): Promise<T> {
  return (await response.json()) as T;
}
