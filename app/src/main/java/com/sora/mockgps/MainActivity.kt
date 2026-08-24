package com.sora.mockgps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import com.sora.mockgps.feature.map.MapScreen
import com.sora.mockgps.ui.theme.BloomWalkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BloomWalkTheme { MapScreen() }
        }
    }
}
