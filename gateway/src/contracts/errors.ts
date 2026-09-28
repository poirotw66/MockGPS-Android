export const ERROR_CODES = [
  "INVALID_REQUEST",
  "REQUEST_TOO_LARGE",
  "NO_ROUTE",
  "ROUTE_TOO_COMPLEX",
  "ROUTE_OUT_OF_SCOPE",
  "CLIENT_RATE_LIMITED",
  "GLOBAL_QUOTA_EXHAUSTED",
  "SERVICE_DISABLED",
  "BUDGET_UNAVAILABLE",
  "PROVIDER_UNAVAILABLE",
  "INVALID_PROVIDER_RESPONSE",
  "PROVIDER_TIMEOUT",
] as const;

export type ErrorCode = (typeof ERROR_CODES)[number];

export interface GatewayErrorBody {
  requestId: string;
  error: {
    code: ErrorCode;
    retryAfterSeconds?: number;
  };
}

export const ERROR_HTTP_STATUS: Record<ErrorCode, number> = {
  INVALID_REQUEST: 400,
  REQUEST_TOO_LARGE: 413,
  NO_ROUTE: 422,
  ROUTE_TOO_COMPLEX: 422,
  ROUTE_OUT_OF_SCOPE: 422,
  CLIENT_RATE_LIMITED: 429,
  GLOBAL_QUOTA_EXHAUSTED: 429,
  SERVICE_DISABLED: 503,
  BUDGET_UNAVAILABLE: 503,
  PROVIDER_UNAVAILABLE: 503,
  INVALID_PROVIDER_RESPONSE: 502,
  PROVIDER_TIMEOUT: 504,
};

export class GatewayError extends Error {
  readonly code: ErrorCode;
  readonly status: number;
  readonly retryAfterSeconds?: number;
  readonly requestConflict: boolean;

  constructor(
    code: ErrorCode,
    message: string,
    options?: { retryAfterSeconds?: number; requestConflict?: boolean },
  ) {
    super(message);
    this.name = "GatewayError";
    this.code = code;
    this.status = options?.requestConflict === true ? 409 : ERROR_HTTP_STATUS[code];
    this.retryAfterSeconds = options?.retryAfterSeconds;
    this.requestConflict = options?.requestConflict === true;
  }
}
