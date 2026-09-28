import { describe, expect, it } from "vitest";
import { sanitizeLogFields } from "../src/logging";
import { purchase, route, search, uuid } from "./helpers";
import routeRequest from "./fixtures/route-request.json";

describe("safe logging", () => {
  it("drops query, coordinates, tokens, and apiKey fields before writing", () => {
    const sanitized = sanitizeLogFields({
      requestId: "abc",
      query: "secret-query-text",
      latitude: 25.0478,
      longitude: 121.517,
      purchaseToken: "purchased-token-001",
      apiKey: "test-api-key-should-never-appear",
      token: "raw-token",
      safeCount: 2,
      url: "https://api.geoapify.com/v1/geocode/search?apiKey=test-api-key-should-never-appear",
    });
    expect(sanitized).toEqual({ requestId: "abc", safeCount: 2 });
  });

  it("does not write query, coordinates, tokens, or apiKey to captured logs", async () => {
    const lines: string[] = [];
    const original = console.log;
    console.log = (...args: unknown[]) => {
      lines.push(args.map((value) => String(value)).join(" "));
    };
    try {
      await search(
        { "X-Test-Ledger": `logs-${uuid()}` },
        {
          query: "secret-query-text",
          language: "en",
          bias: { latitude: 25.0478, longitude: 121.517 },
        },
      );
      await route(
        { "X-Test-Ledger": `logs-route-${uuid()}` },
        {
          ...routeRequest,
          waypoints: [
            { latitude: 25.033964, longitude: 121.564468 },
            { latitude: 25.0478, longitude: 121.517 },
          ],
        },
      );
      await purchase(undefined, { purchaseToken: "purchased-token-001", installId: uuid() });
    } finally {
      console.log = original;
    }
    const captured = lines.join("\n");
    expect(captured).not.toContain("secret-query-text");
    expect(captured).not.toContain("25.0478");
    expect(captured).not.toContain("121.517");
    expect(captured).not.toContain("25.033964");
    expect(captured).not.toContain("test-api-key-should-never-appear");
    expect(captured.toLowerCase()).not.toContain("apikey=");
    expect(captured).not.toContain("purchased-token-001");
    expect(captured).not.toMatch(/Authorization/i);
  });
});
