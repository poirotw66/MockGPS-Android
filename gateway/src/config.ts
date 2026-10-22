import {
  CONFIG_SCHEMA_VERSION,
  PROVIDER_REVISION,
  ROUTING_MAX_INPUT_DISTANCE_METERS,
  ROUTING_MAX_WAYPOINTS,
  type BillingPolicy,
  type PublicConfig,
} from "./contracts/index";

export const SEARCH_BODY_LIMIT_BYTES = 4 * 1024;
export const ROUTE_BODY_LIMIT_BYTES = 16 * 1024;
export const PURCHASE_BODY_LIMIT_BYTES = 16 * 1024;
export const ERROR_BODY_LIMIT_BYTES = 4 * 1024;
export const CONFIG_CACHE_SECONDS = 300;
export const JSON_MAX_DEPTH = 8;
export const JSON_MAX_ARRAY_LENGTH = 10_000;
export const JSON_MAX_OBJECT_KEYS = 64;

export interface OperationalLimits {
  creditBudget24h: number;
  globalMinIntervalMs: number;
  clientSearchLimit24h: number;
  clientRouteLimit24h: number;
  clientMinIntervalMs: number;
  ipRequestLimitPerMinute: number;
  providerTimeoutMs: number;
  maxInFlightGeo: number;
  clockMarginMs: number;
}

export const DEFAULT_LIMITS: OperationalLimits = {
  creditBudget24h: 2700,
  globalMinIntervalMs: 300,
  clientSearchLimit24h: 30,
  clientRouteLimit24h: 10,
  clientMinIntervalMs: 2000,
  ipRequestLimitPerMinute: 120,
  providerTimeoutMs: 10_000,
  maxInFlightGeo: 4,
  clockMarginMs: 60_000,
};

/** Comma-separated install ids. Empty or unset grants privilege to nobody. */
export function privilegedInstallIds(raw: string | undefined): ReadonlySet<string> {
  if (raw === undefined || raw.trim() === "") {
    return new Set();
  }
  return new Set(
    raw
      .split(",")
      .map((part) => part.trim())
      .filter((part) => part.length > 0),
  );
}

export function isPrivilegedInstall(raw: string | undefined, installId: string): boolean {
  return privilegedInstallIds(raw).has(installId);
}

export function parseBooleanFlag(value: string | undefined, fallback = false): boolean {
  if (value === undefined || value === "") {
    return fallback;
  }
  return value === "true" || value === "1";
}

export function parsePositiveInt(value: string | undefined, fallback: number): number {
  if (value === undefined || value === "") {
    return fallback;
  }
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < 0) {
    throw new Error("invalid numeric configuration");
  }
  return parsed;
}

export function readLimits(env: Env): OperationalLimits {
  return {
    creditBudget24h: parsePositiveInt(env.GEO_CREDIT_BUDGET_24H, DEFAULT_LIMITS.creditBudget24h),
    globalMinIntervalMs: parsePositiveInt(
      env.GEO_GLOBAL_MIN_INTERVAL_MS,
      DEFAULT_LIMITS.globalMinIntervalMs,
    ),
    clientSearchLimit24h: parsePositiveInt(
      env.CLIENT_SEARCH_LIMIT_24H,
      DEFAULT_LIMITS.clientSearchLimit24h,
    ),
    clientRouteLimit24h: parsePositiveInt(
      env.CLIENT_ROUTE_LIMIT_24H,
      DEFAULT_LIMITS.clientRouteLimit24h,
    ),
    clientMinIntervalMs: parsePositiveInt(
      env.CLIENT_MIN_INTERVAL_MS,
      DEFAULT_LIMITS.clientMinIntervalMs,
    ),
    ipRequestLimitPerMinute: parsePositiveInt(
      env.IP_REQUEST_LIMIT_PER_MINUTE,
      DEFAULT_LIMITS.ipRequestLimitPerMinute,
    ),
    providerTimeoutMs: parsePositiveInt(env.PROVIDER_TIMEOUT_MS, DEFAULT_LIMITS.providerTimeoutMs),
    maxInFlightGeo: parsePositiveInt(env.MAX_IN_FLIGHT_GEO, DEFAULT_LIMITS.maxInFlightGeo),
    clockMarginMs: parsePositiveInt(env.CLOCK_MARGIN_MS, DEFAULT_LIMITS.clockMarginMs),
  };
}

export function parseBillingPolicy(raw: string | undefined): BillingPolicy | undefined {
  if (raw === undefined || raw.trim() === "") {
    return undefined;
  }
  const parsed: unknown = JSON.parse(raw);
  if (typeof parsed !== "object" || parsed === null) {
    return undefined;
  }
  const record = parsed as Record<string, unknown>;
  if (typeof record.version !== "string" || record.version.trim() === "") {
    return undefined;
  }
  if (typeof record.evidence !== "string" || record.evidence.trim() === "") {
    return undefined;
  }
  const documented = record.documentedMaximumCredits;
  let documentedMaximumCredits: BillingPolicy["documentedMaximumCredits"];
  if (documented !== undefined) {
    if (typeof documented !== "object" || documented === null) {
      return undefined;
    }
    const credits: BillingPolicy["documentedMaximumCredits"] = {};
    const search = (documented as Record<string, unknown>).search;
    const route = (documented as Record<string, unknown>).route;
    if (search !== undefined) {
      if (typeof search !== "number" || !Number.isInteger(search) || search <= 0) {
        return undefined;
      }
      credits.search = search;
    }
    if (route !== undefined) {
      if (typeof route !== "number" || !Number.isInteger(route) || route <= 0) {
        return undefined;
      }
      credits.route = route;
    }
    documentedMaximumCredits = credits;
  }
  return {
    version: record.version,
    evidence: record.evidence,
    documentedMaximumCredits,
  };
}

export function isProductionLike(env: Env): boolean {
  return env.ENVIRONMENT === "production";
}

function rejectInvalidPublicUrl(url: string, env: Env): string {
  if (url.includes(".invalid") && isProductionLike(env)) {
    return "";
  }
  return url;
}

export function publicConfig(env: Env): PublicConfig {
  return {
    schemaVersion: CONFIG_SCHEMA_VERSION,
    revision: PROVIDER_REVISION,
    searchEnabled: parseBooleanFlag(env.SEARCH_ENABLED),
    routingEnabled: parseBooleanFlag(env.ROUTING_ENABLED),
    purchasesEnabled: parseBooleanFlag(env.PURCHASES_ENABLED),
    adsEnabled: parseBooleanFlag(env.ADS_ENABLED),
    routingMaxWaypoints: ROUTING_MAX_WAYPOINTS,
    routingMaxInputDistanceMeters: ROUTING_MAX_INPUT_DISTANCE_METERS,
    privacyPolicyUrl: rejectInvalidPublicUrl(env.PRIVACY_POLICY_URL ?? "", env),
    supportUrl: rejectInvalidPublicUrl(env.SUPPORT_URL ?? "", env),
  };
}

export function readClockMs(env: Env): number {
  if (env.ENVIRONMENT === "test" && env.TEST_NOW_MS) {
    const parsed = Number(env.TEST_NOW_MS);
    if (Number.isFinite(parsed) && parsed > 0) {
      return parsed;
    }
  }
  return Date.now();
}
