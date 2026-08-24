package com.sora.mockgps.service

import java.util.UUID

/** Identifies one accepted foreground-service session and rejects stale control commands. */
data class ServiceSessionToken(
    val sessionId: String,
    val generation: Long,
) {
    init {
        require(sessionId.isNotBlank())
        require(generation > 0L)
    }

    internal companion object {
        /** A deliberately non-matchable token used to reject malformed tagged commands. */
        val INVALID = ServiceSessionToken("invalid-command-token", Long.MAX_VALUE)
    }
}

/** Pure, synchronized ownership gate. A session can only clear state that it still owns. */
class ServiceSessionGate(
    private val nextSessionId: () -> String = { UUID.randomUUID().toString() },
) {
    private var lastGeneration = 0L
    private var activeToken: ServiceSessionToken? = null

    @Synchronized
    fun begin(): ServiceSessionToken {
        check(activeToken == null) { "A session is already active" }
        check(lastGeneration < Long.MAX_VALUE) { "Session generation overflow" }
        return ServiceSessionToken(nextSessionId().also { require(it.isNotBlank()) }, ++lastGeneration).also {
            activeToken = it
        }
    }

    @Synchronized
    fun current(): ServiceSessionToken? = activeToken

    @Synchronized
    fun accepts(commandToken: ServiceSessionToken?): Boolean =
        commandToken == null || commandToken == activeToken

    @Synchronized
    fun end(token: ServiceSessionToken): Boolean {
        if (activeToken != token) return false
        activeToken = null
        return true
    }
}
