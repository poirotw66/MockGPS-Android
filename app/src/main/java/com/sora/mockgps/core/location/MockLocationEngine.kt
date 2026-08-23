package com.sora.mockgps.core.location

import com.sora.mockgps.core.model.MockPayload

/**
 * A single location delivery backend. Implementations must make start and stop idempotent.
 * Failures are thrown so [MockLocationCoordinator] can retain their original causes.
 */
interface MockLocationEngine {
    val name: String

    suspend fun start()
    suspend fun push(payload: MockPayload)
    suspend fun stop()
}
