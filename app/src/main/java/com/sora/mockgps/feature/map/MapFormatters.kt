package com.sora.mockgps.feature.map

import com.sora.mockgps.core.model.Coordinate
import java.util.Locale
import kotlin.math.cos
import kotlin.math.log2
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.spatialk.geojson.Position

internal fun List<Coordinate>.previewCameraPosition(fallback: CameraPosition): CameraPosition {
    if (size < 2) return fallback
    val minLatitude = minOf { it.latitude }
    val maxLatitude = maxOf { it.latitude }
    val minLongitude = minOf { it.longitude }
    val maxLongitude = maxOf { it.longitude }
    val centreLatitude = (minLatitude + maxLatitude) / 2.0
    val centreLongitude = (minLongitude + maxLongitude) / 2.0
    val longitudeScale = cos(Math.toRadians(centreLatitude)).coerceAtLeast(0.2)
    val span = maxOf(
        maxLatitude - minLatitude,
        (maxLongitude - minLongitude) * longitudeScale,
    ).coerceAtLeast(0.0005)
    return fallback.copy(
        target = Position(latitude = centreLatitude, longitude = centreLongitude),
        zoom = (log2(360.0 / span) - 1.8).coerceIn(3.0, 17.0),
        tilt = 0.0,
    )
}

internal fun routePointLabel(index: Int): String {
    require(index in 0 until 26) { "Route point index must fit A-Z" }
    return ('A'.code + index).toChar().toString()
}
internal fun Double.formatCoordinate(): String = String.format(Locale.US, "%.6f", this)
internal fun Double.formatDuration(): String {
    val totalMinutes = (this / 60.0).toInt().coerceAtLeast(1)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours == 0) "${minutes}m" else "${hours}h ${minutes}m"
}

internal fun List<JourneyLandmark>.toFeatureCollectionGeoJson(useZhTw: Boolean = false): String =
    buildJsonObject {
        put("type", "FeatureCollection")
        put("features", buildJsonArray {
            this@toFeatureCollectionGeoJson.forEach { landmark ->
                add(buildJsonObject {
                    put("type", "Feature")
                    put("properties", buildJsonObject {
                        put("name", landmark.name)
                        put("label", landmark.displayName(useZhTw))
                    })
                    put("geometry", buildJsonObject {
                        put("type", "Point")
                        put("coordinates", buildJsonArray {
                            add(landmark.coordinate.longitude)
                            add(landmark.coordinate.latitude)
                        })
                    })
                })
            }
        })
    }.toString()

internal fun Locale.usesTraditionalChinese(): Boolean =
    language == "zh" && (country.equals("TW", ignoreCase = true) || script == "Hant")

