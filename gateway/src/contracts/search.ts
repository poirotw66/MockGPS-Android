export const SEARCH_LANGUAGES = ["zh-TW", "en"] as const;
export type SearchLanguage = (typeof SEARCH_LANGUAGES)[number];

export interface Coordinate {
  latitude: number;
  longitude: number;
}

export interface SearchRequest {
  query: string;
  language: SearchLanguage;
  bias?: Coordinate;
}

export interface PlaceResult {
  name: string;
  address: string;
  coordinate: Coordinate;
}

export interface Attribution {
  label: string;
  url: string;
}

export interface SearchResponse {
  requestId: string;
  providerRevision: string;
  results: PlaceResult[];
  attribution: Attribution[];
}

export const SEARCH_RESULT_LIMIT = 8;
export const SEARCH_QUERY_MIN_CODE_POINTS = 2;
export const SEARCH_QUERY_MAX_CODE_POINTS = 200;
export const SEARCH_RESPONSE_MAX_BYTES = 64 * 1024;
