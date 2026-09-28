import {
  ERROR_BODY_LIMIT_BYTES,
  SEARCH_BODY_LIMIT_BYTES,
  ROUTE_BODY_LIMIT_BYTES,
  PURCHASE_BODY_LIMIT_BYTES,
} from "./config";
import { GatewayError, type ErrorCode, type GatewayErrorBody } from "./contracts/index";
import { assertUuid } from "./validation";

export interface RequestContext {
  requestId: string;
  installId: string;
  clientVersion: string;
}

export function readRequestContext(request: Request, requireInstall: boolean): RequestContext {
  const requestId = request.headers.get("X-Request-ID")?.trim() ?? "";
  if (requestId === "") {
    throw new GatewayError("INVALID_REQUEST", "X-Request-ID is required");
  }
  assertUuid(requestId, "X-Request-ID");
  const clientVersion = request.headers.get("X-Client-Version")?.trim() ?? "";
  if (clientVersion === "" || clientVersion.length > 64) {
    throw new GatewayError("INVALID_REQUEST", "X-Client-Version is required");
  }
  const installId = request.headers.get("X-Install-ID")?.trim() ?? "";
  if (requireInstall) {
    if (installId === "") {
      throw new GatewayError("INVALID_REQUEST", "X-Install-ID is required");
    }
    assertUuid(installId, "X-Install-ID");
  }
  return { requestId, installId, clientVersion };
}

export async function readBoundedBody(request: Request, limitBytes: number): Promise<string> {
  const declared = request.headers.get("content-length");
  if (declared !== null) {
    const length = Number(declared);
    if (Number.isFinite(length) && length > limitBytes) {
      throw new GatewayError("REQUEST_TOO_LARGE", "request exceeds size limit");
    }
  }
  const buffer = await request.arrayBuffer();
  if (buffer.byteLength > limitBytes) {
    throw new GatewayError("REQUEST_TOO_LARGE", "request exceeds size limit");
  }
  return new TextDecoder("utf-8").decode(buffer);
}

export function bodyLimitForPath(pathname: string): number {
  if (pathname === "/v1/search") {
    return SEARCH_BODY_LIMIT_BYTES;
  }
  if (pathname === "/v1/route") {
    return ROUTE_BODY_LIMIT_BYTES;
  }
  return PURCHASE_BODY_LIMIT_BYTES;
}

export function jsonResponse(
  body: unknown,
  status: number,
  requestId: string,
  extraHeaders?: HeadersInit,
): Response {
  const payload = JSON.stringify(body);
  const headers = new Headers(extraHeaders);
  headers.set("content-type", "application/json; charset=utf-8");
  headers.set("X-Request-ID", requestId);
  return new Response(payload, { status, headers });
}

export function errorResponse(requestId: string, error: GatewayError): Response {
  const body: GatewayErrorBody = {
    requestId,
    error: { code: error.code },
  };
  if (error.retryAfterSeconds !== undefined) {
    body.error.retryAfterSeconds = error.retryAfterSeconds;
  }
  const encoded = JSON.stringify(body);
  const payload =
    encoded.length > ERROR_BODY_LIMIT_BYTES
      ? JSON.stringify({
          requestId,
          error: { code: error.code },
        } satisfies GatewayErrorBody)
      : encoded;
  const headers = new Headers({
    "content-type": "application/json; charset=utf-8",
    "X-Request-ID": requestId,
    "Cache-Control": "no-store",
  });
  if (error.retryAfterSeconds !== undefined) {
    headers.set("Retry-After", String(error.retryAfterSeconds));
  }
  return new Response(payload, { status: error.status, headers });
}

export function errorFromUnknown(requestId: string, thrown: unknown): Response {
  if (thrown instanceof GatewayError) {
    return errorResponse(requestId, thrown);
  }
  return errorResponse(requestId, new GatewayError("PROVIDER_UNAVAILABLE", "unavailable"));
}

export function typedError(code: ErrorCode, retryAfterSeconds?: number): GatewayError {
  return new GatewayError(code, code, { retryAfterSeconds });
}
