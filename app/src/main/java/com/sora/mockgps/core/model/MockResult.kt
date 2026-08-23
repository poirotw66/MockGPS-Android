package com.sora.mockgps.core.model

sealed interface MockResult<out T> {
    data class Success<T>(val value: T) : MockResult<T>
    data class Failure(val error: MockError) : MockResult<Nothing>
}

sealed interface MockError {
    data class InvalidPayload(val reason: PayloadValidationError) : MockError
    data object NotStarted : MockError
    data class StartFailed(
        val engineName: String,
        val cause: Throwable,
        val rollbackFailures: List<EngineFailure> = emptyList(),
    ) : MockError
    data class PushFailed(val failures: List<EngineFailure>) : MockError
    data class StopFailed(val failures: List<EngineFailure>) : MockError
}

data class EngineFailure(
    val engineName: String,
    val operation: EngineOperation,
    val cause: Throwable,
)

enum class EngineOperation { START, PUSH, STOP }
