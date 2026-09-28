import { describe, expect, it } from "vitest";
import { estimateMaximumCredits } from "../src/geo-budget";
import { planRoute } from "../src/providers/geoapify";
import type { GatewayErrorBody, RouteRequest, RouteResponse } from "../src/contracts/index";
import { GatewayError } from "../src/contracts/index";
import { jsonOf, route, uuid } from "./helpers";
import routeRequest from "./fixtures/route-request.json";
import routeResponse from "./fixtures/route-response.json";

describe("POST /v1/route", () => {
  it("refuses production-like routing when B01 is unresolved", async () => {
    const estimate = estimateMaximumCredits(
      { operation: "route" },
      {
        version: "search-1-credit-v1",
        evidence: "documented search only",
        documentedMaximumCredits: { search: 1 },
      },
    );
    expect(estimate.kind).toBe("unsupported");
    const ledger = `unsupported-route-${uuid()}`;
    const response = await route({ "X-Test-Ledger": ledger });
    expect(response.status).toBe(503);
    expect((await jsonOf<GatewayErrorBody>(response)).error.code).toBe("SERVICE_DISABLED");
  });

  it("returns the fixture route when a test-only accounting policy is present", async () => {
    const requestId = "7fa29c99-6507-4618-94d8-4af824f57e64";
    const response = await route(
      { "X-Test-Ledger": `route-ok-${uuid()}`, "X-Test-Allow-Route": "1" },
      routeRequest,
      { requestId },
    );
    expect(response.status).toBe(200);
    const body = await jsonOf<RouteResponse>(response);
    expect(body.mode).toBe(routeResponse.mode);
    expect(body.points).toEqual(routeResponse.points);
    expect(body.distanceMeters).toBe(routeResponse.distanceMeters);
  });

  it("rejects unknown modes, adjacent duplicates, and oversize input distance", async () => {
    const unknown = await route(
      { "X-Test-Allow-Route": "1", "X-Test-Ledger": uuid() },
      {
        mode: "hovercraft",
        waypoints: routeRequest.waypoints,
      },
    );
    expect((await jsonOf<GatewayErrorBody>(unknown)).error.code).toBe("INVALID_REQUEST");

    const duplicate = await route(
      { "X-Test-Allow-Route": "1", "X-Test-Ledger": uuid() },
      {
        mode: "walk",
        waypoints: [
          { latitude: 25.0, longitude: 121.0 },
          { latitude: 25.0, longitude: 121.0 },
        ],
      },
    );
    expect((await jsonOf<GatewayErrorBody>(duplicate)).error.code).toBe("INVALID_REQUEST");

    const far = await route(
      { "X-Test-Allow-Route": "1", "X-Test-Ledger": uuid() },
      {
        mode: "drive",
        waypoints: [
          { latitude: 25.0, longitude: 121.0 },
          { latitude: 35.0, longitude: 139.0 },
        ],
      },
    );
    expect(far.status).toBe(422);
    expect((await jsonOf<GatewayErrorBody>(far)).error.code).toBe("ROUTE_OUT_OF_SCOPE");
  });

  it("validates provider geometry and never returns a truncated route", async () => {
    await expect(
      planRoute(routeRequest as RouteRequest, {
        mode: "stub",
        timeoutMs: 1000,
        behavior: "geometry-gap",
      }),
    ).rejects.toBeInstanceOf(GatewayError);
    await expect(
      planRoute(routeRequest as RouteRequest, {
        mode: "stub",
        timeoutMs: 1000,
        behavior: "negative-distance",
      }),
    ).rejects.toMatchObject({ code: "INVALID_PROVIDER_RESPONSE" });
    await expect(
      planRoute(routeRequest as RouteRequest, {
        mode: "stub",
        timeoutMs: 1000,
        behavior: "no-route",
      }),
    ).rejects.toMatchObject({ code: "NO_ROUTE" });
  });

  it("does not follow caller-controlled provider urls", async () => {
    const response = await route(
      { "X-Test-Allow-Route": "1", "X-Test-Ledger": uuid() },
      {
        ...routeRequest,
        url: "http://127.0.0.1/routing",
      },
    );
    expect((await jsonOf<GatewayErrorBody>(response)).error.code).toBe("INVALID_REQUEST");
  });
});
