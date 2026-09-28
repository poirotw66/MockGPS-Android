import { describe, expect, it } from "vitest";
import { parseBooleanFlag } from "../src/config";
import { SELF } from "cloudflare:test";
import type { PublicConfig } from "../src/contracts/index";
import configFixture from "./fixtures/config-success.json";

describe("feature flags", () => {
  it("treats missing and non-true values as disabled", () => {
    expect(parseBooleanFlag(undefined)).toBe(false);
    expect(parseBooleanFlag("")).toBe(false);
    expect(parseBooleanFlag("false")).toBe(false);
    expect(parseBooleanFlag("true")).toBe(true);
    expect(parseBooleanFlag("1")).toBe(true);
  });
});

describe("GET /v1/config", () => {
  it("returns public capabilities without secrets", async () => {
    const response = await SELF.fetch("https://gateway.test/v1/config");
    expect(response.status).toBe(200);
    expect(response.headers.get("Cache-Control")).toContain("max-age=300");
    const body = (await response.json()) as PublicConfig;
    expect(body.schemaVersion).toBe(configFixture.schemaVersion);
    expect(body.revision).toBe(configFixture.revision);
    expect(body.searchEnabled).toBe(true);
    expect(body.routingEnabled).toBe(true);
    expect(body.purchasesEnabled).toBe(true);
    expect(body.adsEnabled).toBe(false);
    expect(body.routingMaxWaypoints).toBe(25);
    expect(body.routingMaxInputDistanceMeters).toBe(100000);
    expect(body.privacyPolicyUrl).toBe(configFixture.privacyPolicyUrl);
    expect(JSON.stringify(body)).not.toContain("apiKey");
    expect(JSON.stringify(body)).not.toContain("GEOAPIFY");
  });
});
