package com.sora.mockgps.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.sora.mockgps.R
import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.ui.theme.BloomWalkCoral
import com.sora.mockgps.ui.theme.BloomWalkGold
import com.sora.mockgps.ui.theme.BloomWalkSage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.expressions.dsl.Feature
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Position

@Composable
internal fun MapPicker(
    modifier: Modifier,
    mapType: MapDisplayType,
    mapRenderKey: Int,
    loadingState: MapLoadingState,
    pendingCoordinate: Coordinate,
    routePoints: List<Coordinate>,
    routeOrigin: Coordinate?,
    routeDestination: Coordinate?,
    routeWaypoints: List<Coordinate>,
    showRouteControlPoints: Boolean,
    showLandmarks: Boolean,
    activeRouteCoordinate: Coordinate?,
    /** Static mock active pin when the pending selection has not been applied yet. */
    pendingApplyActiveCoordinate: Coordinate? = null,
    cameraState: CameraState,
    onMapLoaded: () -> Unit,
    onMapLoadFailed: () -> Unit,
    onCameraIdle: (CameraPosition) -> Unit,
    onCoordinateSelected: (Coordinate) -> Unit,
    onRetry: () -> Unit,
) {
    val mapDescription = stringResource(R.string.map_picker_description)
    val tapScope = rememberCoroutineScope()
    val mapOptions = MapOptions(
        ornamentOptions = OrnamentOptions(
            padding = WindowInsets.safeDrawing.asPaddingValues(),
        ),
    )
    val useZhTwLabels = LocalConfiguration.current.locales[0].usesTraditionalChinese()
    var landmarkConfirmation by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(landmarkConfirmation) {
        if (landmarkConfirmation != null) {
            delay(2_000)
            landmarkConfirmation = null
        }
    }
    Box(modifier = modifier.semantics { contentDescription = mapDescription }) {
        key(mapRenderKey) {
            MaplibreMap(
                modifier = Modifier.fillMaxSize(),
                baseStyle = BaseStyle.Uri(mapType.styleUrl),
                cameraState = cameraState,
                options = mapOptions,
                onMapLoadFinished = onMapLoaded,
                onMapLoadFailed = { onMapLoadFailed() },
                onMapClick = { position, _ ->
                        onCoordinateSelected(Coordinate(position.latitude, position.longitude))
                    tapScope.launch { cameraState.animateTo(cameraState.position.copy(target = position)) }
                    ClickResult.Consume
                },
            ) {
                if (showLandmarks) {
                    LandmarkLayer(useZhTw = useZhTwLabels) { landmark ->
                        onCoordinateSelected(landmark.coordinate)
                        landmarkConfirmation = landmark.displayName(useZhTwLabels)
                        tapScope.launch {
                            cameraState.animateTo(
                                cameraState.position.copy(target = landmark.coordinate.toPosition()),
                            )
                        }
                    }
                }
                if (routePoints.size >= 2) RouteLine(routePoints)
                pendingApplyActiveCoordinate?.let { StaticActiveMarker(it) }
                SelectedLocationMarker(pendingCoordinate)
                activeRouteCoordinate?.let { RouteActiveMarker(it) }
            }
        }
        pendingApplyActiveCoordinate?.let {
            val pendingHint = stringResource(R.string.pending_apply_chip)
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 12.dp, start = 16.dp, end = 16.dp)
                    .semantics { contentDescription = pendingHint },
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.96f),
                shadowElevation = 2.dp,
            ) {
                Text(
                    text = pendingHint,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
        landmarkConfirmation?.let { name ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 88.dp, start = 16.dp, end = 16.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                shadowElevation = 2.dp,
            ) {
                Text(
                    text = stringResource(R.string.landmark_selected, name),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        if (loadingState == MapLoadingState.Ready && showRouteControlPoints) {
            val controlPoints = routeWaypoints.takeIf { it.size >= 2 }
                ?: listOfNotNull(routeOrigin, routeDestination)
            val visibleControlPoints = controlPoints.dropClosingDuplicate()
            cameraState.position
            cameraState.projection?.let { projection ->
                visibleControlPoints.forEachIndexed { index, coordinate ->
                    val position = projection.screenLocationFromPosition(coordinate.toPosition())
                    RouteControlMarker(
                        label = routePointLabel(index),
                        color = when (index) {
                            0 -> BloomWalkSage
                            visibleControlPoints.lastIndex -> BloomWalkCoral
                            else -> BloomWalkGold
                        },
                        modifier = Modifier.offset(x = position.x - 14.dp, y = position.y - 14.dp),
                    )
                }
            }
        }
        when (loadingState) {
            MapLoadingState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            MapLoadingState.Error -> Surface(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.map_load_failed), color = MaterialTheme.colorScheme.error)
                    Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }
            MapLoadingState.Ready -> Unit
        }
    }
    // CameraState survives overlay updates; committing only after movement settles avoids work
    // on every drag frame and keeps pan/zoom smooth.
    androidx.compose.runtime.LaunchedEffect(cameraState.isCameraMoving) {
        if (!cameraState.isCameraMoving) onCameraIdle(cameraState.position)
    }
}

@Composable
private fun LandmarkLayer(
    useZhTw: Boolean,
    onLandmarkSelected: (JourneyLandmark) -> Unit,
) {
    // OpenFreeMap glyphs only serve Noto Sans; MapLibre default Open Sans / Arial Unicode 404s.
    val data = remember(useZhTw) {
        GeoJsonData.JsonString(journeyLandmarks.toFeatureCollectionGeoJson(useZhTw))
    }
    val source = rememberGeoJsonSource(data)
    LaunchedEffect(source, data) { source.setData(data) }
    val onClick: (List<org.maplibre.spatialk.geojson.Feature<*, *>>) -> ClickResult = { features ->
        val name = features.firstNotNullOfOrNull { feature ->
            (feature.properties as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
        }
        val landmark = journeyLandmarks.firstOrNull { it.name == name }
        landmark?.let(onLandmarkSelected)
        if (landmark == null) ClickResult.Pass else ClickResult.Consume
    }
    CircleLayer(
        id = "journey-landmarks",
        source = source,
        minZoom = 10f,
        color = const(BloomWalkCoral),
        radius = const(5.dp),
        strokeColor = const(Color.White),
        strokeWidth = const(2.dp),
        onClick = onClick,
    )
    SymbolLayer(
        id = "journey-landmark-labels",
        source = source,
        minZoom = 11.5f,
        textField = format(span(Feature.get("label").cast<StringValue>())),
        textFont = const(listOf("Noto Sans Regular")),
        textSize = const(12.sp),
        textColor = const(MaterialTheme.colorScheme.onSurface),
        textHaloColor = const(MaterialTheme.colorScheme.surface),
        textHaloWidth = const(2.dp),
        textOffset = offset(0.em, -1.em),
        textAllowOverlap = const(false),
        onClick = onClick,
    )
}

@Composable
private fun RouteLine(points: List<Coordinate>) {
    val geoJson = remember(points) { points.toLineStringGeoJson() }
    val data = remember(geoJson) { GeoJsonData.JsonString(geoJson) }
    val source = rememberGeoJsonSource(data)
    LaunchedEffect(source, data) { source.setData(data) }
    LineLayer(
        id = "planned-bicycle-route-casing",
        source = source,
        color = const(Color.White),
        width = const(7.dp),
    )
    LineLayer(
        id = "planned-bicycle-route",
        source = source,
        color = const(BloomWalkCoral),
        width = const(4.dp),
    )
}

@Composable
private fun RouteControlMarker(label: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(28.dp),
        shape = CircleShape,
        color = color,
        contentColor = Color.White,
        shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun StaticActiveMarker(coordinate: Coordinate) {
    val data = remember(coordinate) { GeoJsonData.JsonString(coordinate.toPointGeoJson()) }
    val source = rememberGeoJsonSource(data)
    LaunchedEffect(source, data) { source.setData(data) }
    CircleLayer(
        id = "static-active-position",
        source = source,
        color = const(BloomWalkSage.copy(alpha = 0.55f)),
        radius = const(11.dp),
        strokeColor = const(Color.White),
        strokeWidth = const(2.dp),
    )
}

@Composable
private fun RouteActiveMarker(coordinate: Coordinate) {
    val data = remember(coordinate) { GeoJsonData.JsonString(coordinate.toPointGeoJson()) }
    val source = rememberGeoJsonSource(data)
    LaunchedEffect(source, data) { source.setData(data) }
    CircleLayer(
        id = "route-active-position",
        source = source,
        color = const(BloomWalkSage),
        radius = const(8.dp),
        strokeColor = const(Color.White),
        strokeWidth = const(3.dp),
    )
}

@Composable
private fun SelectedLocationMarker(coordinate: Coordinate) {
    val data = remember(coordinate) { GeoJsonData.JsonString(coordinate.toPointGeoJson()) }
    val source = rememberGeoJsonSource(data)
    LaunchedEffect(source, data) { source.setData(data) }
    CircleLayer(
        id = "selected-location-halo",
        source = source,
        color = const(BloomWalkGold.copy(alpha = 0.30f)),
        radius = const(18.dp),
        strokeColor = const(Color.Black.copy(alpha = 0.8f)),
        strokeWidth = const(2.dp),
    )
    CircleLayer(
        id = "selected-location-marker",
        source = source,
        color = const(BloomWalkGold),
        radius = const(9.dp),
        strokeColor = const(Color.Black),
        strokeWidth = const(3.dp),
    )
}

@Composable
internal fun rememberMapCameraState(camera: MapCamera): CameraState = rememberCameraState(
    firstPosition = CameraPosition(
        target = camera.coordinate.toPosition(),
        zoom = camera.zoom.toDouble(),
        tilt = camera.tilt.toDouble(),
        bearing = camera.bearing.toDouble(),
    ),
)

internal fun Coordinate.toPosition(): Position = Position(latitude = latitude, longitude = longitude)

private fun List<Coordinate>.dropClosingDuplicate(): List<Coordinate> =
    if (size > 2 && first() == last()) dropLast(1) else this
private fun List<Coordinate>.toLineStringGeoJson(): String = joinToString(
    prefix = """{"type":"Feature","geometry":{"type":"LineString","coordinates":[""",
    postfix = "]}}",
    separator = ",",
) { coordinate -> "[${coordinate.longitude},${coordinate.latitude}]" }

private fun Coordinate.toPointGeoJson(): String =
    """{"type":"Feature","geometry":{"type":"Point","coordinates":[$longitude,$latitude]}}"""

private val MapDisplayType.styleUrl: String
    get() = when (this) {
        MapDisplayType.Light -> "https://tiles.openfreemap.org/styles/positron"
        MapDisplayType.Dark -> "https://tiles.openfreemap.org/styles/dark"
    }
