package com.sora.mockgps.core.network

/**
 * Typed gateway failures. User-visible copy is resolved from string resources;
 * unknown server text is never shown as-is.
 */
sealed class GatewayException(
    val code: GatewayErrorCode,
    val httpStatus: Int,
    val requestId: String? = null,
    val retryAfterSeconds: Int? = null,
    cause: Throwable? = null,
) : Exception(code.name, cause) {
    class InvalidRequest(requestId: String? = null, cause: Throwable? = null) :
        GatewayException(GatewayErrorCode.INVALID_REQUEST, 400, requestId, cause = cause)

    class RequestTooLarge(requestId: String? = null) :
        GatewayException(GatewayErrorCode.REQUEST_TOO_LARGE, 413, requestId)

    class NoRoute(requestId: String? = null) :
        GatewayException(GatewayErrorCode.NO_ROUTE, 422, requestId)

    class RouteTooComplex(requestId: String? = null) :
        GatewayException(GatewayErrorCode.ROUTE_TOO_COMPLEX, 422, requestId)

    class RouteOutOfScope(requestId: String? = null) :
        GatewayException(GatewayErrorCode.ROUTE_OUT_OF_SCOPE, 422, requestId)

    class ClientRateLimited(requestId: String? = null, retryAfterSeconds: Int? = null) :
        GatewayException(GatewayErrorCode.CLIENT_RATE_LIMITED, 429, requestId, retryAfterSeconds)

    class GlobalQuotaExhausted(requestId: String? = null, retryAfterSeconds: Int? = null) :
        GatewayException(GatewayErrorCode.GLOBAL_QUOTA_EXHAUSTED, 429, requestId, retryAfterSeconds)

    class ServiceDisabled(requestId: String? = null) :
        GatewayException(GatewayErrorCode.SERVICE_DISABLED, 503, requestId)

    class BudgetUnavailable(requestId: String? = null) :
        GatewayException(GatewayErrorCode.BUDGET_UNAVAILABLE, 503, requestId)

    class ProviderUnavailable(requestId: String? = null, cause: Throwable? = null) :
        GatewayException(GatewayErrorCode.PROVIDER_UNAVAILABLE, 503, requestId, cause = cause)

    class InvalidProviderResponse(requestId: String? = null, cause: Throwable? = null) :
        GatewayException(GatewayErrorCode.INVALID_PROVIDER_RESPONSE, 502, requestId, cause = cause)

    class ProviderTimeout(requestId: String? = null, cause: Throwable? = null) :
        GatewayException(GatewayErrorCode.PROVIDER_TIMEOUT, 504, requestId, cause = cause)

    class Conflict(requestId: String? = null) :
        GatewayException(GatewayErrorCode.CONFLICT, 409, requestId)

    class Unavailable(requestId: String? = null, cause: Throwable? = null) :
        GatewayException(GatewayErrorCode.UNAVAILABLE, 503, requestId, cause = cause)
}

enum class GatewayErrorCode {
    INVALID_REQUEST,
    REQUEST_TOO_LARGE,
    NO_ROUTE,
    ROUTE_TOO_COMPLEX,
    ROUTE_OUT_OF_SCOPE,
    CLIENT_RATE_LIMITED,
    GLOBAL_QUOTA_EXHAUSTED,
    SERVICE_DISABLED,
    BUDGET_UNAVAILABLE,
    PROVIDER_UNAVAILABLE,
    INVALID_PROVIDER_RESPONSE,
    PROVIDER_TIMEOUT,
    CONFLICT,
    UNAVAILABLE,
}

fun GatewayException.isTransientUnavailable(): Boolean = when (this) {
    is GatewayException.ClientRateLimited,
    is GatewayException.GlobalQuotaExhausted,
    is GatewayException.ServiceDisabled,
    is GatewayException.BudgetUnavailable,
    is GatewayException.ProviderUnavailable,
    is GatewayException.ProviderTimeout,
    is GatewayException.Unavailable,
    -> true
    else -> false
}

fun gatewayExceptionFromCode(
    code: String?,
    httpStatus: Int,
    requestId: String?,
    retryAfterSeconds: Int?,
    cause: Throwable? = null,
): GatewayException = when (code) {
    GatewayErrorCode.INVALID_REQUEST.name -> GatewayException.InvalidRequest(requestId, cause)
    GatewayErrorCode.REQUEST_TOO_LARGE.name -> GatewayException.RequestTooLarge(requestId)
    GatewayErrorCode.NO_ROUTE.name -> GatewayException.NoRoute(requestId)
    GatewayErrorCode.ROUTE_TOO_COMPLEX.name -> GatewayException.RouteTooComplex(requestId)
    GatewayErrorCode.ROUTE_OUT_OF_SCOPE.name -> GatewayException.RouteOutOfScope(requestId)
    GatewayErrorCode.CLIENT_RATE_LIMITED.name ->
        GatewayException.ClientRateLimited(requestId, retryAfterSeconds)
    GatewayErrorCode.GLOBAL_QUOTA_EXHAUSTED.name ->
        GatewayException.GlobalQuotaExhausted(requestId, retryAfterSeconds)
    GatewayErrorCode.SERVICE_DISABLED.name -> GatewayException.ServiceDisabled(requestId)
    GatewayErrorCode.BUDGET_UNAVAILABLE.name -> GatewayException.BudgetUnavailable(requestId)
    GatewayErrorCode.PROVIDER_UNAVAILABLE.name ->
        GatewayException.ProviderUnavailable(requestId, cause)
    GatewayErrorCode.INVALID_PROVIDER_RESPONSE.name ->
        GatewayException.InvalidProviderResponse(requestId, cause)
    GatewayErrorCode.PROVIDER_TIMEOUT.name -> GatewayException.ProviderTimeout(requestId, cause)
    GatewayErrorCode.CONFLICT.name -> GatewayException.Conflict(requestId)
    else -> when (httpStatus) {
        400 -> GatewayException.InvalidRequest(requestId, cause)
        409 -> GatewayException.Conflict(requestId)
        413 -> GatewayException.RequestTooLarge(requestId)
        429 -> GatewayException.ClientRateLimited(requestId, retryAfterSeconds)
        502 -> GatewayException.InvalidProviderResponse(requestId, cause)
        504 -> GatewayException.ProviderTimeout(requestId, cause)
        else -> GatewayException.Unavailable(requestId, cause)
    }
}
