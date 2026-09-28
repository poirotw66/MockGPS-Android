import { describe, expect, it } from "vitest";
import type { GatewayErrorBody, SearchResponse } from "../src/contracts/index";
import { jsonOf, search, uuid } from "./helpers";
import searchRequest from "./fixtures/search-request.json";
import searchResponse from "./fixtures/search-response.json";

describe("POST /v1/search", () => {
  it("returns fixture results for the documented query", async () => {
    const requestId = "7fa29c99-6507-4618-94d8-4af824f57e64";
    const response = await search({ "X-Test-Ledger": `search-${uuid()}` }, searchRequest, {
      requestId,
    });
    expect(response.status).toBe(200);
    expect(response.headers.get("Cache-Control")).toBe("no-store");
    expect(response.headers.get("X-Request-ID")).toBe(requestId);
    const body = await jsonOf<SearchResponse>(response);
    expect(body.providerRevision).toBe(searchResponse.providerRevision);
    expect(body.results).toEqual(searchResponse.results);
    expect(body.attribution).toEqual(searchResponse.attribution);
  });

  it("keeps Traditional Chinese and returns empty results as 200", async () => {
    const response = await search(
      { "X-Test-Ledger": `empty-${uuid()}` },
      {
        query: "繁體測試地點",
        language: "zh-TW",
      },
    );
    expect(response.status).toBe(200);
    const body = await jsonOf<SearchResponse>(response);
    expect(body.results).toEqual([]);
  });

  it("rejects oversized bodies before parse", async () => {
    const response = await search({ "X-Test-Ledger": `large-${uuid()}` }, `${"x".repeat(5000)}`);
    expect(response.status).toBe(413);
    const body = await jsonOf<GatewayErrorBody>(response);
    expect(body.error.code).toBe("REQUEST_TOO_LARGE");
  });

  it("rejects caller-provided urls and unknown languages", async () => {
    const urlResponse = await search(
      { "X-Test-Ledger": `ssrf-${uuid()}` },
      {
        query: "台北車站",
        language: "zh-TW",
        url: "https://evil.example/geocode",
      },
    );
    expect(urlResponse.status).toBe(400);
    const langResponse = await search(
      { "X-Test-Ledger": `lang-${uuid()}` },
      {
        query: "台北車站",
        language: "ja",
      },
    );
    expect((await jsonOf<GatewayErrorBody>(langResponse)).error.code).toBe("INVALID_REQUEST");
  });

  it("rejects oversized provider payloads as invalid without a public fallback", async () => {
    const response = await search({
      "X-Test-Ledger": `provider-oversize-${uuid()}`,
      "X-Test-Provider-Behavior": "oversized",
    });
    const body = await jsonOf<GatewayErrorBody>(response);
    expect(body.error.code).toBe("INVALID_PROVIDER_RESPONSE");
    expect(JSON.stringify(body)).not.toContain("nominatim");
  });
});
