package com.sora.mockgps.service

import com.sora.mockgps.core.model.Coordinate

/** The live service state. It is deliberately not persisted. */
sealed interface MockServiceState {
    data object Idle : MockServiceState
    data class Starting(val coordinate: Coordinate) : MockServiceState
    data class Active(val coordinate: Coordinate) : MockServiceState
    data class Error(val message: String) : MockServiceState
}
