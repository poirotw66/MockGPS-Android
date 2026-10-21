import { describe, expect, it } from "vitest";
import {
  estimateMaximumCredits,
  OWNER_ACCEPTED_ROUTE_CREDITS,
  OWNER_ACCEPTED_ROUTE_EVIDENCE,
} from "../src/geo-budget";
import { planRoute } from "../src/providers/geoapify";
import type { GatewayErrorBody, RouteRequest, RouteResponse } from "../src/contracts/index";
import { GatewayError } from "../src/contracts/index";
import { jsonOf, route, uuid } from "./helpers";
import routeRequest from "./fixtures/route-request.json";
import routeResponse from "./fixtures/route-response.json";

describe("POST /v1/route", () => {
  it("refuses routing without the owner-accepted reservation", async () => {
    const estimate = estimateMaximumCredits(
      { operation: "route" },
      {
        version: "search-1-credit-v1",
        evidence: "documented search only",
        documentedMaximumCredits: { search: 1 },
      },
    );
    expect(estimate.kind).toBe("unsupported");
    const wrongReservation = estimateMaximumCredits(
      { operation: "route" },
      {
        version: "owner-accepted-route-exposure-v1",
        evidence: OWNER_ACCEPTED_ROUTE_EVIDENCE,
        documentedMaximumCredits: { route: 1 },
      },
    );
    expect(wrongReservation.kind).toBe("unsupported");
    const ledger = `unsupported-route-${uuid()}`;
    const response = await route({
      "X-Test-Ledger": ledger,
      "X-Test-Billing-Policy": JSON.stringify({
        version: "search-1-credit-v1",
        evidence: "documented search only",
        documentedMaximumCredits: { search: 1 },
      }),
    });
    expect(response.status).toBe(503);
    expect((await jsonOf<GatewayErrorBody>(response)).error.code).toBe("SERVICE_DISABLED");
  });

  it("reserves 25 credits when the owner-accepted route policy is present", () => {
    const estimate = estimateMaximumCredits(
      { operation: "route" },
      {
        version: "owner-accepted-route-exposure-v1",
        evidence: `partial public formula; ${OWNER_ACCEPTED_ROUTE_EVIDENCE}; not a provider hard cap`,
        documentedMaximumCredits: { search: 1, route: OWNER_ACCEPTED_ROUTE_CREDITS },
      },
    );
    expect(estimate).toEqual({
      kind: "bounded",
      credits: OWNER_ACCEPTED_ROUTE_CREDITS,
      policyVersion: "owner-accepted-route-exposure-v1",
    });
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

  it("accepts nested Geoapify route instructions", async () => {
    const payload = {
      type: "FeatureCollection",
      features: [
        {
          type: "Feature",
          properties: {
            distance: 120,
            time: 40,
            legs: [
              {
                steps: [
                  {
                    instruction: { text: "Head north", type: "continue" },
                  },
                ],
              },
            ],
          },
          geometry: {
            type: "MultiLineString",
            coordinates: [
              [
                [121.564468, 25.033964],
                [121.5652, 25.0348],
              ],
            ],
          },
        },
      ],
    };
    const result = await planRoute(routeRequest as RouteRequest, {
      mode: "live",
      apiKey: "test-key",
      timeoutMs: 1000,
      fetchImpl: async () => new Response(JSON.stringify(payload), { status: 200 }),
    });
    expect(result.points).toEqual([
      { latitude: 25.033964, longitude: 121.564468 },
      { latitude: 25.0348, longitude: 121.5652 },
    ]);
    expect(result.distanceMeters).toBe(120);
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
