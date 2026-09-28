import { describe, expect, it } from "vitest";
import { inspectJsonTree, parseRouteRequest, parseSearchRequest } from "../src/validation";
import { GatewayError } from "../src/contracts/index";
import { jsonOf, search, uuid } from "./helpers";
import type { GatewayErrorBody } from "../src/contracts/index";

describe("untrusted json validation", () => {
  it("rejects nested, infinite, and oversized structures", () => {
    const nested = { a: { b: { c: { d: { e: { f: { g: { h: { i: 1 } } } } } } } } };
    expect(() => inspectJsonTree(nested)).toThrow(GatewayError);
    expect(() => inspectJsonTree({ value: Number.POSITIVE_INFINITY })).toThrow(GatewayError);
    expect(() => inspectJsonTree(Array.from({ length: 10_001 }, () => 1))).toThrow(GatewayError);
  });

  it("rejects search and route payloads that would enable SSRF or unknown modes", () => {
    expect(() =>
      parseSearchRequest({
        query: "台北車站",
        language: "zh-TW",
        url: "https://127.0.0.1",
      }),
    ).toThrow(GatewayError);
    expect(() =>
      parseRouteRequest({
        mode: "teleport",
        waypoints: [
          { latitude: 25, longitude: 121 },
          { latitude: 25.1, longitude: 121.1 },
        ],
      }),
    ).toThrow(GatewayError);
  });

  it("returns typed errors for invalid provider behavior without a public fallback", async () => {
    const nested = await search({
      "X-Test-Ledger": `nested-${uuid()}`,
      "X-Test-Provider-Behavior": "nested",
    });
    expect((await jsonOf<GatewayErrorBody>(nested)).error.code).toBe("INVALID_PROVIDER_RESPONSE");
    const infinite = await search({
      "X-Test-Ledger": `inf-${uuid()}`,
      "X-Test-Provider-Behavior": "infinite",
    });
    const infiniteBody = await jsonOf<GatewayErrorBody>(infinite);
    expect(infiniteBody.error.code).toBe("INVALID_PROVIDER_RESPONSE");
    expect(JSON.stringify(infiniteBody)).not.toContain("nominatim");
  });
});
