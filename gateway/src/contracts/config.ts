export interface PublicConfig {
  schemaVersion: number;
  revision: string;
  searchEnabled: boolean;
  routingEnabled: boolean;
  purchasesEnabled: boolean;
  adsEnabled: boolean;
  routingMaxWaypoints: number;
  routingMaxInputDistanceMeters: number;
  privacyPolicyUrl: string;
  supportUrl: string;
}

export const CONFIG_SCHEMA_VERSION = 1;
export const PROVIDER_REVISION = "geoapify-v1";
export const ROUTING_MAX_WAYPOINTS = 25;
export const ROUTING_MAX_INPUT_DISTANCE_METERS = 100_000;
