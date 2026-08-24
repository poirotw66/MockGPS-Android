package com.sora.mockgps.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceSessionGateTest {
    @Test
    fun `new generation rejects commands and cleanup from an earlier session`() {
        var idCall = 0
        val gate = ServiceSessionGate { if (idCall++ == 0) "first" else "second" }
        val first = gate.begin()

        assertTrue(gate.accepts(first))
        assertTrue(gate.end(first))

        val second = gate.begin()
        assertEquals(2L, second.generation)
        assertFalse(gate.accepts(first))
        assertFalse(gate.end(first))
        assertEquals(second, gate.current())
    }

    @Test
    fun `untagged commands stay source compatible while tagged commands require an exact token`() {
        val gate = ServiceSessionGate { "active" }
        val active = gate.begin()

        assertTrue(gate.accepts(null))
        assertTrue(gate.accepts(active))
        assertFalse(gate.accepts(ServiceSessionToken("active", 2)))
        assertTrue(gate.end(active))
        assertNull(gate.current())
    }
}
