package com.sora.mockgps.feature.map

import android.app.Application
import org.junit.Assert.assertNotNull
import org.junit.Test

class MapViewModelConstructionTest {
    @Test
    fun android_view_model_factory_constructor_is_available() {
        assertNotNull(MapViewModel::class.java.getConstructor(Application::class.java))
    }
}
