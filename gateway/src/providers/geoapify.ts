import {
  GatewayError,
  PROVIDER_REVISION,
  ROUTE_MAX_GEOMETRY_POINTS,
  SEARCH_RESULT_LIMIT,
  type Attribution,
  type Coordinate,
  type PlaceResult,
  type RouteMode,
  type RouteRequest,
  type SearchRequest,
} from "../contracts/index";
import { inspectJsonTree, isFiniteNumber } from "../validation";

export const ATTRIBUTION_ALLOWLIST: Attribution[] = [
  { label: "Powered by Geoapify", url: "https://www.geoapify.com/" },
  { label: "© OpenStreetMap contributors", url: "https://www.openstreetmap.org/copyright" },
];

export const GEOAPIFY_HOST = "api.geoapify.com";
export const GEOAPIFY_SEARCH_PATH = "/v1/geocode/search";
export const GEOAPIFY_ROUTE_PATH = "/v1/routing";

export const LANGUAGE_MAP: Record<SearchRequest["language"], string> = {
  "zh-TW": "zh",
  en: "en",
};

export const MODE_MAP: Record<RouteMode, string> = {
  walk: "walk",
  bicycle: "bicycle",
  drive: "drive",
};

const FIXTURE_SEARCH_QUERY = "台北車站";
const FIXTURE_SEARCH_RESULTS: PlaceResult[] = [
  {
    name: "台北車站",
    address: "臺北市",
    coordinate: { latitude: 25.0478, longitude: 121.517 },
  },
];
const FIXTURE_ROUTE_START: Coordinate = { latitude: 25.033964, longitude: 121.564468 };
const FIXTURE_ROUTE_END: Coordinate = { latitude: 25.0478, longitude: 121.517 };

export interface ProviderDeps {
  mode: "stub" | "live";
  apiKey?: string;
  timeoutMs: number;
  fetchImpl?: typeof fetch;
  behavior?: ProviderBehavior;
}

export type ProviderBehavior =
  | "ok"
  | "timeout"
  | "http500"
  | "invalid-json"
  | "oversized"
  | "nested"
  | "infinite"
  | "no-route"
  | "geometry-gap"
  | "negative-distance"
  | "lost";

export interface SearchProviderResult {
  results: PlaceResult[];
  attribution: Attribution[];
  providerRevision: string;
}

export interface RouteProviderResult {
  mode: RouteMode;
  points: Coordinate[];
  distanceMeters: number;
  providerDurationSeconds: number;
  attribution: Attribution[];
  providerRevision: string;
}

function allowedAttribution(): Attribution[] {
  return ATTRIBUTION_ALLOWLIST.map((item) => ({ ...item }));
}

function abortError(): GatewayError {
  return new GatewayError("PROVIDER_TIMEOUT", "provider timeout");
}

async function fetchProvider(
  url: URL,
  timeoutMs: number,
  fetchImpl: typeof fetch,
  apiKey: string,
): Promise<Response> {
  url.searchParams.set("apiKey", apiKey);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetchImpl(url.toString(), {
      method: "GET",
      redirect: "manual",
      signal: controller.signal,
      headers: { accept: "application/json" },
    });
    const location = response.headers.get("location");
    if (response.status >= 300 && response.status < 400) {
      if (!location) {
        throw new GatewayError("INVALID_PROVIDER_RESPONSE", "redirect without location");
      }
      const redirected = new URL(location, url);
      if (redirected.hostname !== GEOAPIFY_HOST) {
        throw new GatewayError("INVALID_PROVIDER_RESPONSE", "refusing redirect to unknown host");
      }
      throw new GatewayError("INVALID_PROVIDER_RESPONSE", "unexpected provider redirect");
    }
    return response;
  } catch (error) {
    if (error instanceof GatewayError) {
      throw error;
    }
    if (error instanceof Error && error.name === "AbortError") {
      throw abortError();
    }
    throw new GatewayError("PROVIDER_UNAVAILABLE", "provider fetch failed");
  } finally {
    clearTimeout(timer);
  }
}

function parseProviderJson(text: string): unknown {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text) as unknown;
  } catch {
    throw new GatewayError("INVALID_PROVIDER_RESPONSE", "provider returned non-json");
  }
  try {
    inspectJsonTree(parsed);
  } catch {
    throw new GatewayError("INVALID_PROVIDER_RESPONSE", "provider json failed validation");
  }
  return parsed;
}

function asCoordinateFromLonLat(pair: unknown): Coordinate {
  if (!Array.isArray(pair) || pair.length < 2) {
    throw new GatewayError("INVALID_PROVIDER_RESPONSE", "invalid geometry pair");
  }
  const longitude = pair[0];
  const latitude = pair[1];
  if (!isFiniteNumber(latitude) || !isFiniteNumber(longitude)) {
    throw new GatewayError("INVALID_PROVIDER_RESPONSE", "non-finite coordinate");
  }
  if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
    throw new GatewayError("INVALID_PROVIDER_RESPONSE", "coordinate out of range");
  }
  return { latitude, longitude };
}

function mergeLine(points: Coordinate[], next: Coordinate[]): Coordinate[] {
  if (points.length === 0) {
    return [...next];
  }
  const last = points[points.length - 1];
  const first = next[0];
  if (!last || !first) {
    throw new GatewayError("INVALID_PROVIDER_RESPONSE", "geometry gap");
  }
  const continuous = last.latitude === first.latitude && last.longitude === first.longitude;
  if (!continuous) {
    throw new GatewayError("INVALID_PROVIDER_RESPONSE", "geometry gap");
  }
  return [...points, ...next.slice(1)];
}

export function mapSearchLanguage(language: SearchRequest["language"]): string {
  return LANGUAGE_MAP[language];
}

function applyBehavior(behavior: ProviderBehavior | undefined): void {
  if (behavior === "timeout") {
    throw abortError();
  }
  if (behavior === "http500") {
    throw new GatewayError("PROVIDER_UNAVAILABLE", "provider 5xx");
  }
  if (behavior === "invalid-json") {
    throw new GatewayError("INVALID_PROVIDER_RESPONSE", "provider returned non-json");
  }
  if (behavior === "oversized") {
    throw new GatewayError("INVALID_PROVIDER_RESPONSE", "provider response too large");
  }
  if (behavior === "nested") {
    throw new GatewayError("INVALID_PROVIDER_RESPONSE", "provider json failed validation");
  }
  if (behavior === "infinite") {
    throw new GatewayError("INVALID_PROVIDER_RESPONSE", "non-finite coordinate");
  }
  if (behavior === "no-route") {
    throw new GatewayError("NO_ROUTE", "no route");
  }
  if (behavior === "geometry-gap") {
    throw new GatewayError("INVALID_PROVIDER_RESPONSE", "geometry gap");
  }
  if (behavior === "negative-distance") {
    throw new GatewayError("INVALID_PROVIDER_RESPONSE", "negative distance");
  }
}

export async function searchPlaces(
  request: SearchRequest,
  deps: ProviderDeps,
): Promise<SearchProviderResult> {
  applyBehavior(deps.behavior);
  if (deps.mode === "stub") {
    if (request.query === FIXTURE_SEARCH_QUERY) {
      return {
        results: FIXTURE_SEARCH_RESULTS,
        attribution: allowedAttribution(),
        providerRevision: PROVIDER_REVISION,
      };
    }
    return { results: [], attribution: allowedAttribution(), providerRevision: PROVIDER_REVISION };
  }
  if (!deps.apiKey) {
    throw new GatewayError("PROVIDER_UNAVAILABLE", "geoapify key missing");
  }
  const url = new URL(`https://${GEOAPIFY_HOST}${GEOAPIFY_SEARCH_PATH}`);
  url.searchParams.set("text", request.query);
  url.searchParams.set("lang", mapSearchLanguage(request.language));
  url.searchParams.set("limit", String(SEARCH_RESULT_LIMIT));
  url.searchParams.set("format", "json");
  if (request.bias) {
    url.searchParams.set("bias", `proximity:${request.bias.longitude},${request.bias.latitude}`);
  }
  const response = await fetchProvider(url, deps.timeoutMs, deps.fetchImpl ?? fetch, deps.apiKey);
  if (response.status >= 500) {
    throw new GatewayError("PROVIDER_UNAVAILABLE", "provider 5xx");
  }
  if (response.status === 408 || response.status === 504) {
    throw abortError();
  }
  if (!response.ok) {
    throw new GatewayError("PROVIDER_UNAVAILABLE", "provider request failed");
  }
  const text = await response.text();
  if (new TextEncoder().encode(text).byteLength > 512 * 1024) {
    throw new GatewayError("INVALID_PROVIDER_RESPONSE", "provider response too large");
  }
  const parsed = parseProviderJson(text);
  const results = extractSearchResults(parsed);
  return {
    results: results.slice(0, SEARCH_RESULT_LIMIT),
    attribution: allowedAttribution(),
    providerRevision: PROVIDER_REVISION,
  };
}

function extractSearchResults(parsed: unknown): PlaceResult[] {
  if (typeof parsed !== "object" || parsed === null) {
    throw new GatewayError("INVALID_PROVIDER_RESPONSE", "unexpected search payload");
  }
  const record = parsed as Record<string, unknown>;
  const features = Array.isArray(record.features) ? record.features : record.results;
  if (!Array.isArray(features)) {
    throw new GatewayError("INVALID_PROVIDER_RESPONSE", "missing search results");
  }
  const results: PlaceResult[] = [];
  for (const feature of features) {
    if (typeof feature !== "object" || feature === null) {
      throw new GatewayError("INVALID_PROVIDER_RESPONSE", "invalid search feature");
    }
    const item = feature as Record<string, unknown>;
    const properties =
      typeof item.properties === "object" && item.properties !== null
        ? (item.properties as Record<string, unknown>)
        : item;
    const geometry =
      typeof item.geometry === "object" && item.geometry !== null
        ? (item.geometry as Record<string, unknown>)
        : undefined;
    let coordinate: Coordinate | undefined;
    if (geometry && Array.isArray(geometry.coordinates)) {
      coordinate = asCoordinateFromLonLat(geometry.coordinates);
    } else if (isFiniteNumber(properties.lat) && isFiniteNumber(properties.lon)) {
      coordinate = { latitude: properties.lat, longitude: properties.lon };
    }
    if (!coordinate) {
      throw new GatewayError("INVALID_PROVIDER_RESPONSE", "search result missing coordinate");
    }
    const name = typeof properties.name === "string" ? properties.name : "";
    const address =
      typeof properties.formatted === "string"
        ? properties.formatted
        : typeof properties.address_line2 === "string"
          ? properties.address_line2
          : "";
    results.push({ name, address, coordinate });
  }
  return results;
}

export async function planRoute(
  request: RouteRequest,
  deps: ProviderDeps,
): Promise<RouteProviderResult> {
  applyBehavior(deps.behavior);
  if (deps.mode === "stub") {
    const start = request.waypoints[0];
    const end = request.waypoints[request.waypoints.length - 1];
    if (
      start &&
      end &&
      start.latitude === FIXTURE_ROUTE_START.latitude &&
      start.longitude === FIXTURE_ROUTE_START.longitude &&
      end.latitude === FIXTURE_ROUTE_END.latitude &&
      end.longitude === FIXTURE_ROUTE_END.longitude
    ) {
      return {
        mode: request.mode,
        points: [...request.waypoints],
        distanceMeters: 4800,
        providerDurationSeconds: 1200,
        attribution: allowedAttribution(),
        providerRevision: PROVIDER_REVISION,
      };
    }
    throw new GatewayError("NO_ROUTE", "no route");
  }
  if (!deps.apiKey) {
    throw new GatewayError("PROVIDER_UNAVAILABLE", "geoapify key missing");
  }
  const url = new URL(`https://${GEOAPIFY_HOST}${GEOAPIFY_ROUTE_PATH}`);
  url.searchParams.set(
    "waypoints",
    request.waypoints.map((point) => `${point.latitude},${point.longitude}`).join("|"),
  );
  url.searchParams.set("mode", MODE_MAP[request.mode]);
  url.searchParams.set("format", "geojson");
  const response = await fetchProvider(url, deps.timeoutMs, deps.fetchImpl ?? fetch, deps.apiKey);
  if (response.status >= 500) {
    throw new GatewayError("PROVIDER_UNAVAILABLE", "provider 5xx");
  }
  if (response.status === 408 || response.status === 504) {
    throw abortError();
  }
  if (!response.ok) {
    throw new GatewayError("NO_ROUTE", "no route");
  }
  const text = await response.text();
  if (new TextEncoder().encode(text).byteLength > 512 * 1024) {
    throw new GatewayError("INVALID_PROVIDER_RESPONSE", "provider response too large");
  }
  const parsed = parseProviderJson(text);
  return extractRoute(parsed, request.mode);
}

function extractRoute(parsed: unknown, mode: RouteMode): RouteProviderResult {
  if (typeof parsed !== "object" || parsed === null) {
    throw new GatewayError("INVALID_PROVIDER_RESPONSE", "unexpected route payload");
  }
  const record = parsed as Record<string, unknown>;
  const features = record.features;
  if (!Array.isArray(features) || features.length === 0) {
    throw new GatewayError("NO_ROUTE", "no route");
  }
  let points: Coordinate[] = [];
  let distanceMeters = 0;
  let providerDurationSeconds = 0;
  for (const feature of features) {
    if (typeof feature !== "object" || feature === null) {
      throw new GatewayError("INVALID_PROVIDER_RESPONSE", "invalid route feature");
    }
    const item = feature as Record<string, unknown>;
    const properties =
      typeof item.properties === "object" && item.properties !== null
        ? (item.properties as Record<string, unknown>)
        : {};
    const geometry =
      typeof item.geometry === "object" && item.geometry !== null
        ? (item.geometry as Record<string, unknown>)
        : undefined;
    if (!geometry) {
      throw new GatewayError("INVALID_PROVIDER_RESPONSE", "missing route geometry");
    }
    const nextPoints = geometryToPoints(geometry);
    points = mergeLine(points, nextPoints);
    if (isFiniteNumber(properties.distance)) {
      if (properties.distance < 0) {
        throw new GatewayError("INVALID_PROVIDER_RESPONSE", "negative distance");
      }
      distanceMeters += properties.distance;
    }
    if (isFiniteNumber(properties.time)) {
      providerDurationSeconds += properties.time;
    }
  }
  if (points.length === 0) {
    throw new GatewayError("NO_ROUTE", "no route");
  }
  if (points.length > ROUTE_MAX_GEOMETRY_POINTS) {
    throw new GatewayError("ROUTE_TOO_COMPLEX", "geometry exceeds point limit");
  }
  return {
    mode,
    points,
    distanceMeters,
    providerDurationSeconds,
    attribution: allowedAttribution(),
    providerRevision: PROVIDER_REVISION,
  };
}

function geometryToPoints(geometry: Record<string, unknown>): Coordinate[] {
  const type = geometry.type;
  const coordinates = geometry.coordinates;
  if (type === "LineString" && Array.isArray(coordinates)) {
    return coordinates.map((pair) => asCoordinateFromLonLat(pair));
  }
  if (type === "MultiLineString" && Array.isArray(coordinates)) {
    let merged: Coordinate[] = [];
    for (const line of coordinates) {
      if (!Array.isArray(line)) {
        throw new GatewayError("INVALID_PROVIDER_RESPONSE", "invalid multiline");
      }
      merged = mergeLine(
        merged,
        line.map((pair) => asCoordinateFromLonLat(pair)),
      );
    }
    return merged;
  }
  throw new GatewayError("INVALID_PROVIDER_RESPONSE", "unsupported geometry");
}
