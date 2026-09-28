import type { Attribution, Coordinate } from "./search";

export const ROUTE_MODES = ["walk", "bicycle", "drive"] as const;
export type RouteMode = (typeof ROUTE_MODES)[number];

export interface RouteRequest {
  mode: RouteMode;
  waypoints: Coordinate[];
}

export interface RouteResponse {
  requestId: string;
  providerRevision: string;
  mode: RouteMode;
  points: Coordinate[];
  distanceMeters: number;
  providerDurationSeconds: number;
  attribution: Attribution[];
}

export const ROUTE_MIN_WAYPOINTS = 2;
export const ROUTE_MAX_WAYPOINTS = 25;
export const ROUTE_MAX_GEOMETRY_POINTS = 10_000;
export const ROUTE_RESPONSE_MAX_BYTES = 512 * 1024;
