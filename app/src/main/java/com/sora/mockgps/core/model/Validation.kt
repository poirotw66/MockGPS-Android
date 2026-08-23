package com.sora.mockgps.core.model

sealed interface PayloadValidationError {
    data class InvalidLatitude(val value: Double) : PayloadValidationError
    data class InvalidLongitude(val value: Double) : PayloadValidationError
    data class InvalidAccuracy(val value: Float) : PayloadValidationError
    data class InvalidAltitude(val value: Double) : PayloadValidationError
    data class InvalidSpeed(val value: Float) : PayloadValidationError
    data class InvalidBearing(val value: Float) : PayloadValidationError
}

object MockPayloadValidator {
    fun validate(coordinate: Coordinate, options: MockPayloadOptions): PayloadValidationError? = when {
        !coordinate.latitude.isFinite() || coordinate.latitude !in -90.0..90.0 ->
            PayloadValidationError.InvalidLatitude(coordinate.latitude)
        !coordinate.longitude.isFinite() || coordinate.longitude !in -180.0..180.0 ->
            PayloadValidationError.InvalidLongitude(coordinate.longitude)
        !options.accuracyMeters.isFinite() || options.accuracyMeters <= 0f ->
            PayloadValidationError.InvalidAccuracy(options.accuracyMeters)
        options.altitudeMeters?.isFinite() == false ->
            PayloadValidationError.InvalidAltitude(requireNotNull(options.altitudeMeters))
        options.speedMetersPerSecond != null &&
            (!options.speedMetersPerSecond.isFinite() || options.speedMetersPerSecond < 0f) ->
            PayloadValidationError.InvalidSpeed(requireNotNull(options.speedMetersPerSecond))
        options.bearingDegrees != null &&
            (!options.bearingDegrees.isFinite() || options.bearingDegrees < 0f || options.bearingDegrees >= 360f) ->
            PayloadValidationError.InvalidBearing(requireNotNull(options.bearingDegrees))
        else -> null
    }
}
