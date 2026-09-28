package com.sora.mockgps.feature.map

import com.sora.mockgps.R
import com.sora.mockgps.route.RoutingNetworkException
import com.sora.mockgps.route.RoutingNoRouteException
import com.sora.mockgps.route.RoutingOutOfScopeException
import com.sora.mockgps.route.RoutingQuotaException
import com.sora.mockgps.route.RoutingServiceDisabledException
import com.sora.mockgps.route.RoutingTooComplexException
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingUserMessageTest {
    @Test
    fun `typed routing failures map to dedicated copy and never invent a public fallback`() {
        assertEquals(R.string.route_error_no_route, routingUserMessageId(RoutingNoRouteException()))
        assertEquals(R.string.route_error_too_complex, routingUserMessageId(RoutingTooComplexException()))
        assertEquals(R.string.route_error_out_of_scope, routingUserMessageId(RoutingOutOfScopeException()))
        assertEquals(R.string.route_error_quota, routingUserMessageId(RoutingQuotaException("GLOBAL_QUOTA_EXHAUSTED")))
        assertEquals(R.string.route_error_disabled, routingUserMessageId(RoutingServiceDisabledException()))
        assertEquals(R.string.route_error_network, routingUserMessageId(RoutingNetworkException("offline")))
        assertEquals(R.string.route_error_unavailable, routingUserMessageId(IllegalStateException("other")))
    }
}
