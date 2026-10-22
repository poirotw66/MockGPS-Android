import { describe, expect, it } from "vitest";
import { runInDurableObject } from "cloudflare:test";
import { hmacHex } from "../src/crypto-utils";
import { estimateMaximumCredits } from "../src/geo-budget";
import type { GatewayErrorBody } from "../src/contracts/index";
import { DEFAULT_LIMITS } from "../src/config";
import { initLedger, jsonOf, route, search, uuid } from "./helpers";

const SECRET = "test-rate-key-hmac-secret-not-production";

async function admitArgs(input: {
  ledger: string;
  nowMs: number;
  operation: "search" | "route";
  credits: number;
  requestId: string;
  payload: string;
  installId?: string;
}) {
  return {
    expectedLedgerId: input.ledger,
    allowEmptyInit: false,
    nowMs: input.nowMs,
    operation: input.operation,
    credits: input.credits,
    requestHmac: await hmacHex(SECRET, `request:${input.requestId}`),
    payloadHmac: await hmacHex(SECRET, input.payload),
    actorHmac: await hmacHex(SECRET, `install:${input.installId ?? uuid()}`),
    ipDayHmac: await hmacHex(SECRET, `ip:203.0.113.10:2026-09-28`),
    ipMinuteHmac: await hmacHex(SECRET, `ip:203.0.113.10:2026-09-28T00:00`),
    limits: {
      ...DEFAULT_LIMITS,
      creditBudget24h: 10,
      globalMinIntervalMs: 0,
      clientMinIntervalMs: 0,
      clientSearchLimit24h: 1000,
      clientRouteLimit24h: 1000,
      ipRequestLimitPerMinute: 10_000,
      maxInFlightGeo: 100,
    },
  };
}

describe("GeoBudget Durable Object", () => {
  it("admits at most 10 of 100 parallel searches when 10 credits remain", async () => {
    const ledger = `parallel-${uuid()}`;
    const stub = await initLedger(ledger, {
      creditBudget24h: 10,
      globalMinIntervalMs: 0,
      clientMinIntervalMs: 0,
      clientSearchLimit24h: 1000,
      ipRequestLimitPerMinute: 10_000,
      maxInFlightGeo: 100,
    });
    const responses = await Promise.all(
      Array.from({ length: 100 }, () => search({ "X-Test-Ledger": ledger })),
    );
    const ok = responses.filter((response) => response.status === 200).length;
    const exhausted = responses.filter((response) => response.status === 429).length;
    expect(ok).toBeLessThanOrEqual(10);
    expect(exhausted).toBeGreaterThan(0);
    const inspect = await stub.inspect(Date.now());
    expect(inspect.dispatchedCount).toBeLessThanOrEqual(10);
    expect(inspect.usedCredits).toBeLessThanOrEqual(10);
  });

  it("does not oversell when different operation costs race", async () => {
    const ledger = `mixed-${uuid()}`;
    const stub = await initLedger(ledger, { creditBudget24h: 10, globalMinIntervalMs: 0 });
    const nowMs = Date.UTC(2026, 8, 28, 0, 0, 0);
    const results = await Promise.all(
      Array.from({ length: 20 }, async (_, index) => {
        const operation = index % 2 === 0 ? "route" : "search";
        return stub.admit(
          await admitArgs({
            ledger,
            nowMs,
            operation,
            credits: operation === "route" ? 4 : 1,
            requestId: uuid(),
            payload: `${operation}-${index}`,
          }),
        );
      }),
    );
    const admitted = results.filter((result) => result.outcome === "admitted");
    const used = admitted.reduce((sum, result) => {
      return sum + (result.outcome === "admitted" ? 1 : 0);
    }, 0);
    expect(used).toBeGreaterThan(0);
    const inspect = await stub.inspect(nowMs);
    expect(inspect.usedCredits).toBeLessThanOrEqual(10);
    expect(inspect.usedCredits % 1).toBe(0);
    expect([1, 4, 5, 8, 9, 10]).toContain(inspect.usedCredits);
  });

  it("dispatches zero routes when estimateMaximumCredits is Unsupported", async () => {
    const estimate = estimateMaximumCredits(
      { operation: "route" },
      { version: "v1", evidence: "search only", documentedMaximumCredits: { search: 1 } },
    );
    expect(estimate.kind).toBe("unsupported");
    const ledger = `zero-route-${uuid()}`;
    const stub = await initLedger(ledger);
    await route({
      "X-Test-Ledger": ledger,
      "X-Test-Billing-Policy": JSON.stringify({
        version: "search-1-credit-v1",
        evidence: "documented search only",
        documentedMaximumCredits: { search: 1 },
      }),
    });
    const inspect = await stub.inspect(Date.now());
    expect(inspect.dispatchedCount).toBe(0);
  });

  it("keeps used budget after schema rehydrate and refuses an unknown ledger", async () => {
    const ledger = `restart-${uuid()}`;
    const stub = await initLedger(ledger, { creditBudget24h: 10 });
    const nowMs = Date.UTC(2026, 8, 28);
    await stub.admit(
      await admitArgs({
        ledger,
        nowMs,
        operation: "search",
        credits: 4,
        requestId: uuid(),
        payload: "keep",
      }),
    );
    await stub.rehydrateSchema();
    expect((await stub.inspect(nowMs)).usedCredits).toBe(4);
    await stub.setStoredLedgerId("other-ledger");
    const refused = await stub.admit(
      await admitArgs({
        ledger,
        nowMs: nowMs + 1000,
        operation: "search",
        credits: 1,
        requestId: uuid(),
        payload: "after",
      }),
    );
    expect(refused.outcome).toBe("unavailable");
  });

  it("does not double-dispatch or double-release on timeout, 5xx, lost response, or duplicate ids", async () => {
    const ledger = `failures-${uuid()}`;
    const stub = await initLedger(ledger, { creditBudget24h: 20, maxInFlightGeo: 10 });
    const timeout = await search({
      "X-Test-Ledger": ledger,
      "X-Test-Provider-Behavior": "timeout",
    });
    expect((await jsonOf<GatewayErrorBody>(timeout)).error.code).toBe("PROVIDER_TIMEOUT");
    const five = await search({
      "X-Test-Ledger": ledger,
      "X-Test-Provider-Behavior": "http500",
    });
    expect((await jsonOf<GatewayErrorBody>(five)).error.code).toBe("PROVIDER_UNAVAILABLE");
    const lost = await search({
      "X-Test-Ledger": ledger,
      "X-Test-Provider-Behavior": "lost",
    });
    expect(lost.status).toBe(503);
    const requestId = uuid();
    const first = await search({ "X-Test-Ledger": ledger }, undefined, { requestId });
    expect(first.status).toBe(200);
    const duplicate = await search({ "X-Test-Ledger": ledger }, undefined, { requestId });
    expect(duplicate.status).toBe(429);
    const conflict = await search(
      { "X-Test-Ledger": ledger },
      { query: "另一個查詢", language: "zh-TW" },
      {
        requestId,
      },
    );
    expect(conflict.status).toBe(409);
    const inspect = await stub.inspect(Date.now());
    expect(inspect.usedCredits).toBe(4);
    expect(inspect.dispatchedCount).toBe(4);
  });

  it("applies a rolling window, clock margin, and in-flight limits", async () => {
    const ledger = `window-${uuid()}`;
    const stub = await initLedger(ledger, {
      creditBudget24h: 2,
      clockMarginMs: 60_000,
      providerTimeoutMs: 10_000,
      maxInFlightGeo: 1,
      globalMinIntervalMs: 0,
    });
    const t0 = Date.UTC(2026, 8, 28, 23, 0, 0);
    const first = await stub.admit(
      await admitArgs({
        ledger,
        nowMs: t0,
        operation: "search",
        credits: 1,
        requestId: uuid(),
        payload: "night",
      }),
    );
    expect(first.outcome).toBe("admitted");
    const nextDay = await stub.admit(
      await admitArgs({
        ledger,
        nowMs: t0 + 2 * 60 * 60 * 1000,
        operation: "search",
        credits: 1,
        requestId: uuid(),
        payload: "morning",
      }),
    );
    expect(nextDay.outcome).toBe("admitted");
    expect((await stub.inspect(t0 + 2 * 60 * 60 * 1000)).usedCredits).toBe(2);
    const firstExpired = t0 + 24 * 60 * 60 * 1000 + 10_000 + 60_000 + 1;
    expect((await stub.inspect(firstExpired)).usedCredits).toBe(1);
    const afterWindow = t0 + 2 * 60 * 60 * 1000 + 24 * 60 * 60 * 1000 + 10_000 + 60_000 + 1;
    expect((await stub.inspect(afterWindow)).usedCredits).toBe(0);
  });

  it("refuses dispatch when the quota store fails", async () => {
    const ledger = `sql-fail-${uuid()}`;
    const stub = await initLedger(ledger);
    await stub.failNextTransaction();
    const response = await search({ "X-Test-Ledger": ledger });
    expect((await jsonOf<GatewayErrorBody>(response)).error.code).toBe("BUDGET_UNAVAILABLE");
    expect((await stub.inspect(Date.now())).dispatchedCount).toBe(0);
  });

  it("keeps reserved credits after abort and does not release on a second finalize", async () => {
    const ledger = `abort-${uuid()}`;
    const stub = await initLedger(ledger, { creditBudget24h: 5, globalMinIntervalMs: 0 });
    const nowMs = Date.UTC(2026, 8, 28, 0, 0, 0);
    const admitted = await stub.admit(
      await admitArgs({
        ledger,
        nowMs,
        operation: "search",
        credits: 1,
        requestId: uuid(),
        payload: "abort-me",
      }),
    );
    expect(admitted.outcome).toBe("admitted");
    if (admitted.outcome !== "admitted") {
      return;
    }
    await stub.finalize({ reservationId: admitted.reservationId, nowMs, status: "abort" });
    await stub.finalize({ reservationId: admitted.reservationId, nowMs, status: "ok" });
    const inspect = await stub.inspect(nowMs);
    expect(inspect.usedCredits).toBe(1);
    expect(inspect.dispatchedCount).toBe(1);
    expect(inspect.finalizedCount).toBe(1);
  });

  it("lets a privileged install pass its own route cap without skipping the shared credit budget", async () => {
    const ledger = `privileged-${uuid()}`;
    const stub = await initLedger(ledger, {
      creditBudget24h: 2,
      globalMinIntervalMs: 0,
      clientMinIntervalMs: 60_000,
      clientRouteLimit24h: 1,
    });
    const nowMs = Date.UTC(2026, 8, 28, 0, 0, 0);
    const installId = uuid();
    const first = await stub.admit(
      await admitArgs({
        ledger,
        nowMs,
        operation: "route",
        credits: 1,
        requestId: uuid(),
        payload: "first-privileged",
        installId,
      }),
    );
    expect(first.outcome).toBe("admitted");
    const capped = await stub.admit({
      ...(await admitArgs({
        ledger,
        nowMs: nowMs + 1_000,
        operation: "route",
        credits: 1,
        requestId: uuid(),
        payload: "capped",
        installId,
      })),
      exemptClientQuota: false,
    });
    expect(capped.outcome).toBe("rate");
    const privileged = await stub.admit({
      ...(await admitArgs({
        ledger,
        nowMs: nowMs + 1_000,
        operation: "route",
        credits: 1,
        requestId: uuid(),
        payload: "privileged",
        installId,
      })),
      exemptClientQuota: true,
    });
    expect(privileged.outcome).toBe("admitted");
    const overBudget = await stub.admit({
      ...(await admitArgs({
        ledger,
        nowMs: nowMs + 2_000,
        operation: "route",
        credits: 1,
        requestId: uuid(),
        payload: "over-budget",
        installId,
      })),
      exemptClientQuota: true,
    });
    expect(overBudget.outcome).toBe("quota");
  });

  it("enforces client interval, in-flight, and IP limits with a fake clock", async () => {
    const ledger = `rate-clock-${uuid()}`;
    const stub = await initLedger(ledger, {
      creditBudget24h: 20,
      globalMinIntervalMs: 0,
      clientMinIntervalMs: 2_000,
      ipRequestLimitPerMinute: 2,
      maxInFlightGeo: 1,
      providerTimeoutMs: 10_000,
      clockMarginMs: 0,
    });
    const t0 = Date.UTC(2026, 8, 28, 0, 0, 0);
    const installId = uuid();
    const first = await stub.admit(
      await admitArgs({
        ledger,
        nowMs: t0,
        operation: "search",
        credits: 1,
        requestId: uuid(),
        payload: "first",
        installId,
      }),
    );
    expect(first.outcome).toBe("admitted");
    const tooSoon = await stub.admit(
      await admitArgs({
        ledger,
        nowMs: t0 + 1_000,
        operation: "search",
        credits: 1,
        requestId: uuid(),
        payload: "too-soon",
        installId,
      }),
    );
    expect(tooSoon.outcome).toBe("rate");
    const inFlight = await stub.admit(
      await admitArgs({
        ledger,
        nowMs: t0 + 2_000,
        operation: "search",
        credits: 1,
        requestId: uuid(),
        payload: "in-flight",
        installId,
      }),
    );
    expect(inFlight.outcome).toBe("rate");
    if (first.outcome === "admitted") {
      await stub.finalize({ reservationId: first.reservationId, nowMs: t0 + 2_000, status: "ok" });
    }
    const afterFinalize = await stub.admit(
      await admitArgs({
        ledger,
        nowMs: t0 + 2_000,
        operation: "search",
        credits: 1,
        requestId: uuid(),
        payload: "after-finalize",
        installId,
      }),
    );
    expect(afterFinalize.outcome).toBe("admitted");
    const ipThird = await stub.admit(
      await admitArgs({
        ledger,
        nowMs: t0 + 4_000,
        operation: "search",
        credits: 1,
        requestId: uuid(),
        payload: "ip-third",
        installId: uuid(),
      }),
    );
    expect(ipThird.outcome).toBe("rate");
  });

  it("keeps a shared ledger budget across restart and does not free credits before the window plus margin", async () => {
    const ledger = `shared-window-${uuid()}`;
    const stub = await initLedger(ledger, {
      creditBudget24h: 3,
      clockMarginMs: 60_000,
      providerTimeoutMs: 10_000,
      globalMinIntervalMs: 0,
    });
    const t0 = Date.UTC(2026, 8, 28, 12, 0, 0);
    const first = await stub.admit(
      await admitArgs({
        ledger,
        nowMs: t0,
        operation: "search",
        credits: 1,
        requestId: uuid(),
        payload: "staging-a",
      }),
    );
    const second = await stub.admit(
      await admitArgs({
        ledger,
        nowMs: t0 + 1_000,
        operation: "search",
        credits: 1,
        requestId: uuid(),
        payload: "staging-b",
      }),
    );
    expect(first.outcome).toBe("admitted");
    expect(second.outcome).toBe("admitted");
    await stub.rehydrateSchema();
    expect((await stub.inspect(t0 + 1_000)).usedCredits).toBe(2);
    const beforeMargin = t0 + 24 * 60 * 60 * 1000 + 10_000;
    expect((await stub.inspect(beforeMargin)).usedCredits).toBe(2);
    const refused = await stub.admit(
      await admitArgs({
        ledger,
        nowMs: beforeMargin,
        operation: "search",
        credits: 2,
        requestId: uuid(),
        payload: "too-soon",
      }),
    );
    expect(refused.outcome).toBe("quota");
    const afterMargin = t0 + 1_000 + 24 * 60 * 60 * 1000 + 10_000 + 60_000 + 1;
    expect((await stub.inspect(afterMargin)).usedCredits).toBe(0);
  });

  it("invokes the provider fetch inside the DO and does not fetch when min-interval refuses", async () => {
    const ledger = `dispatch-interval-${uuid()}`;
    const stub = await initLedger(ledger, {
      creditBudget24h: 20,
      globalMinIntervalMs: 300,
      clientMinIntervalMs: 0,
      maxInFlightGeo: 10,
    });
    const t0 = Date.UTC(2026, 8, 28, 0, 0, 0);
    const fetchTimes: number[] = [];
    const outcome = await runInDurableObject(stub, async (instance) => {
      const fetchImpl: typeof fetch = async () => {
        fetchTimes.push(Date.now());
        return new Response(
          JSON.stringify({
            results: [{ name: "x", formatted: "y", lat: 25.0478, lon: 121.517 }],
          }),
          { status: 200, headers: { "content-type": "application/json" } },
        );
      };
      const first = await instance.dispatchSearch({
        admit: await admitArgs({
          ledger,
          nowMs: t0,
          operation: "search",
          credits: 1,
          requestId: uuid(),
          payload: "dispatch-a",
        }),
        search: { query: "台北車站", language: "zh-TW" },
        provider: { mode: "live", apiKey: "test-key", timeoutMs: 10_000 },
        fetchImpl,
      });
      const second = await instance.dispatchSearch({
        admit: await admitArgs({
          ledger,
          nowMs: t0 + 100,
          operation: "search",
          credits: 1,
          requestId: uuid(),
          payload: "dispatch-b",
        }),
        search: { query: "台北車站", language: "zh-TW" },
        provider: { mode: "live", apiKey: "test-key", timeoutMs: 10_000 },
        fetchImpl,
      });
      return { first: first.kind, second: second.kind, secondOutcome: second };
    });
    expect(outcome.first).toBe("admitted");
    expect(outcome.second).toBe("rejected");
    expect(fetchTimes).toHaveLength(1);
  });
});
