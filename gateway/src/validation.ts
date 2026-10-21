import {
  GatewayError,
  ROUTE_MAX_WAYPOINTS,
  ROUTE_MIN_WAYPOINTS,
  ROUTE_MODES,
  ROUTING_MAX_INPUT_DISTANCE_METERS,
  SEARCH_LANGUAGES,
  SEARCH_QUERY_MAX_CODE_POINTS,
  SEARCH_QUERY_MIN_CODE_POINTS,
  type Coordinate,
  type RouteMode,
  type RouteRequest,
  type SearchLanguage,
  type SearchRequest,
} from "./contracts/index";
import { JSON_MAX_ARRAY_LENGTH, JSON_MAX_DEPTH, JSON_MAX_OBJECT_KEYS } from "./config";

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export function assertUuid(value: string, field: string): void {
  if (!UUID_PATTERN.test(value)) {
    throw new GatewayError("INVALID_REQUEST", `invalid ${field}`);
  }
}

export function isFiniteNumber(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value);
}

export function assertCoordinate(value: unknown, field: string): Coordinate {
  if (typeof value !== "object" || value === null) {
    throw new GatewayError("INVALID_REQUEST", `invalid ${field}`);
  }
  const record = value as Record<string, unknown>;
  if (!isFiniteNumber(record.latitude) || !isFiniteNumber(record.longitude)) {
    throw new GatewayError("INVALID_REQUEST", `invalid ${field}`);
  }
  if (record.latitude < -90 || record.latitude > 90) {
    throw new GatewayError("INVALID_REQUEST", `invalid ${field} latitude`);
  }
  if (record.longitude < -180 || record.longitude > 180) {
    throw new GatewayError("INVALID_REQUEST", `invalid ${field} longitude`);
  }
  return { latitude: record.latitude, longitude: record.longitude };
}

export interface JsonTreeLimits {
  maxDepth: number;
  maxArrayLength: number;
  maxObjectKeys: number;
}

const DEFAULT_JSON_LIMITS: JsonTreeLimits = {
  maxDepth: JSON_MAX_DEPTH,
  maxArrayLength: JSON_MAX_ARRAY_LENGTH,
  maxObjectKeys: JSON_MAX_OBJECT_KEYS,
};

/** Geoapify route GeoJSON nests legs, steps, and instructions deeper than client requests. */
export const PROVIDER_JSON_LIMITS: JsonTreeLimits = {
  maxDepth: 16,
  maxArrayLength: JSON_MAX_ARRAY_LENGTH,
  maxObjectKeys: 128,
};

export function inspectJsonTree(
  value: unknown,
  depth = 0,
  limits: JsonTreeLimits = DEFAULT_JSON_LIMITS,
): void {
  if (depth > limits.maxDepth) {
    throw new GatewayError("INVALID_REQUEST", "json nesting exceeds limit");
  }
  if (typeof value === "number" && !Number.isFinite(value)) {
    throw new GatewayError("INVALID_REQUEST", "non-finite number");
  }
  if (Array.isArray(value)) {
    if (value.length > limits.maxArrayLength) {
      throw new GatewayError("INVALID_REQUEST", "array exceeds limit");
    }
    for (const item of value) {
      inspectJsonTree(item, depth + 1, limits);
    }
    return;
  }
  if (typeof value === "object" && value !== null) {
    const keys = Object.keys(value);
    if (keys.length > limits.maxObjectKeys) {
      throw new GatewayError("INVALID_REQUEST", "object exceeds key limit");
    }
    for (const key of keys) {
      inspectJsonTree((value as Record<string, unknown>)[key], depth + 1, limits);
    }
  }
}

export function parseJsonObject(raw: string): Record<string, unknown> {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw) as unknown;
  } catch {
    throw new GatewayError("INVALID_REQUEST", "body is not json");
  }
  inspectJsonTree(parsed);
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    throw new GatewayError("INVALID_REQUEST", "body must be a json object");
  }
  return parsed as Record<string, unknown>;
}

export function normalizeQuery(query: string): string {
  return query.normalize("NFC").trim();
}

export function codePointLength(value: string): number {
  return [...value].length;
}

function hasDisallowedSearchFields(body: Record<string, unknown>): boolean {
  const allowed = new Set(["query", "language", "bias"]);
  return Object.keys(body).some((key) => !allowed.has(key));
}

export function parseSearchRequest(body: Record<string, unknown>): SearchRequest {
  if (hasDisallowedSearchFields(body)) {
    throw new GatewayError("INVALID_REQUEST", "unexpected search field");
  }
  if (typeof body.query !== "string") {
    throw new GatewayError("INVALID_REQUEST", "query is required");
  }
  const query = normalizeQuery(body.query);
  const length = codePointLength(query);
  if (length < SEARCH_QUERY_MIN_CODE_POINTS || length > SEARCH_QUERY_MAX_CODE_POINTS) {
    throw new GatewayError("INVALID_REQUEST", "query length out of range");
  }
  if (
    typeof body.language !== "string" ||
    !SEARCH_LANGUAGES.includes(body.language as SearchLanguage)
  ) {
    throw new GatewayError("INVALID_REQUEST", "language is not allowed");
  }
  if ("url" in body || "country" in body) {
    throw new GatewayError("INVALID_REQUEST", "caller urls and country filters are not allowed");
  }
  const request: SearchRequest = {
    query,
    language: body.language as SearchLanguage,
  };
  if (body.bias !== undefined) {
    request.bias = assertCoordinate(body.bias, "bias");
  }
  return request;
}

function hasDisallowedRouteFields(body: Record<string, unknown>): boolean {
  const allowed = new Set(["mode", "waypoints"]);
  return Object.keys(body).some((key) => !allowed.has(key));
}

export function haversineMeters(left: Coordinate, right: Coordinate): number {
  const earthMeters = 6_371_000;
  const toRad = (degrees: number): number => (degrees * Math.PI) / 180;
  const deltaLat = toRad(right.latitude - left.latitude);
  const deltaLon = toRad(right.longitude - left.longitude);
  const lat1 = toRad(left.latitude);
  const lat2 = toRad(right.latitude);
  const chord =
    Math.sin(deltaLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLon / 2) ** 2;
  return 2 * earthMeters * Math.asin(Math.min(1, Math.sqrt(chord)));
}

function samePoint(left: Coordinate, right: Coordinate): boolean {
  return left.latitude === right.latitude && left.longitude === right.longitude;
}

export function parseRouteRequest(body: Record<string, unknown>): RouteRequest {
  if (hasDisallowedRouteFields(body)) {
    throw new GatewayError("INVALID_REQUEST", "unexpected route field");
  }
  if (
    "avoid" in body ||
    "elevation" in body ||
    "route_details" in body ||
    "optimize_stops" in body ||
    "url" in body
  ) {
    throw new GatewayError("INVALID_REQUEST", "provider options are not allowed");
  }
  if (typeof body.mode !== "string" || !ROUTE_MODES.includes(body.mode as RouteMode)) {
    throw new GatewayError("INVALID_REQUEST", "unknown mode");
  }
  if (!Array.isArray(body.waypoints)) {
    throw new GatewayError("INVALID_REQUEST", "waypoints must be an array");
  }
  if (body.waypoints.length < ROUTE_MIN_WAYPOINTS || body.waypoints.length > ROUTE_MAX_WAYPOINTS) {
    throw new GatewayError("INVALID_REQUEST", "waypoint count out of range");
  }
  const waypoints = body.waypoints.map((point, index) =>
    assertCoordinate(point, `waypoints[${index}]`),
  );
  for (let index = 1; index < waypoints.length; index += 1) {
    const previous = waypoints[index - 1];
    const current = waypoints[index];
    if (previous && current && samePoint(previous, current)) {
      const isClosedLoop = index === waypoints.length - 1 && samePoint(waypoints[0]!, current);
      if (!isClosedLoop) {
        throw new GatewayError("INVALID_REQUEST", "adjacent duplicate waypoints");
      }
      if (waypoints.length === 2) {
        throw new GatewayError("INVALID_REQUEST", "adjacent duplicate waypoints");
      }
    }
  }
  let distance = 0;
  for (let index = 1; index < waypoints.length; index += 1) {
    distance += haversineMeters(waypoints[index - 1]!, waypoints[index]!);
  }
  if (distance > ROUTING_MAX_INPUT_DISTANCE_METERS) {
    throw new GatewayError("ROUTE_OUT_OF_SCOPE", "input distance exceeds abuse limit");
  }
  return { mode: body.mode as RouteMode, waypoints };
}

export function canonicalPayload(value: unknown): string {
  return JSON.stringify(value);
}

export function trustedClientIp(request: Request): string {
  const cloudflareIp = request.headers.get("CF-Connecting-IP");
  if (cloudflareIp && cloudflareIp.trim() !== "") {
    return cloudflareIp.trim();
  }
  return "unknown";
}
