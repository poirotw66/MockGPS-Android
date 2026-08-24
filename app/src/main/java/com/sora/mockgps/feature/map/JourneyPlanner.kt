package com.sora.mockgps.feature.map

import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.route.GeoMath
import com.sora.mockgps.route.RoutePolyline
import com.sora.mockgps.route.RouteTransportMode
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

internal data class JourneyLandmark(val name: String, val coordinate: Coordinate)

internal enum class JourneyRegion(val landmarks: List<JourneyLandmark>) {
    Taiwan(
        listOf(
            JourneyLandmark("Taipei 101", Coordinate(25.0340, 121.5645)),
            JourneyLandmark("Chiang Kai-shek Memorial Hall", Coordinate(25.0346, 121.5218)),
            JourneyLandmark("National Palace Museum", Coordinate(25.1024, 121.5485)),
            JourneyLandmark("Longshan Temple", Coordinate(25.0368, 121.5000)),
            JourneyLandmark("Ximending", Coordinate(25.0422, 121.5073)),
            JourneyLandmark("Dadaocheng Wharf", Coordinate(25.0566, 121.5075)),
            JourneyLandmark("Tamsui Old Street", Coordinate(25.1676, 121.4450)),
            JourneyLandmark("Yehliu Geopark", Coordinate(25.2053, 121.6906)),
            JourneyLandmark("Jiufen Old Street", Coordinate(25.1099, 121.8452)),
            JourneyLandmark("Shifen Waterfall", Coordinate(25.0494, 121.7877)),
            JourneyLandmark("National Taichung Theater", Coordinate(24.1629, 120.6404)),
            JourneyLandmark("Rainbow Village", Coordinate(24.1337, 120.6098)),
            JourneyLandmark("Lukang Longshan Temple", Coordinate(24.0521, 120.4350)),
            JourneyLandmark("Sun Moon Lake Wenwu Temple", Coordinate(23.8691, 120.9270)),
            JourneyLandmark("Alishan Station", Coordinate(23.5102, 120.8014)),
            JourneyLandmark("Chihkan Tower", Coordinate(22.9975, 120.2025)),
            JourneyLandmark("Anping Fort", Coordinate(23.0016, 120.1606)),
            JourneyLandmark("Chimei Museum", Coordinate(22.9346, 120.2260)),
            JourneyLandmark("Fo Guang Shan", Coordinate(22.7551, 120.4451)),
            JourneyLandmark("Pier-2 Art Center", Coordinate(22.6206, 120.2813)),
            JourneyLandmark("Lotus Pond", Coordinate(22.6804, 120.2913)),
            JourneyLandmark("Taroko National Park Gate", Coordinate(24.1593, 121.6214)),
            JourneyLandmark("Qixingtan Beach", Coordinate(24.0314, 121.6273)),
            JourneyLandmark("Kenting National Park", Coordinate(21.9456, 120.7798)),
            JourneyLandmark("Sanxiantai", Coordinate(23.1239, 121.4090)),
        ),
    ),
    Japan(
        listOf(
            JourneyLandmark("Tokyo Tower", Coordinate(35.6586, 139.7454)),
            JourneyLandmark("Tokyo Skytree", Coordinate(35.7101, 139.8107)),
            JourneyLandmark("Senso-ji", Coordinate(35.7148, 139.7967)),
            JourneyLandmark("Shibuya Crossing", Coordinate(35.6595, 139.7005)),
            JourneyLandmark("Meiji Shrine", Coordinate(35.6764, 139.6993)),
            JourneyLandmark("Imperial Palace", Coordinate(35.6852, 139.7528)),
            JourneyLandmark("Odaiba", Coordinate(35.6272, 139.7768)),
            JourneyLandmark("Yokohama Landmark Tower", Coordinate(35.4548, 139.6317)),
            JourneyLandmark("Great Buddha of Kamakura", Coordinate(35.3167, 139.5357)),
            JourneyLandmark("Osaka Castle", Coordinate(34.6873, 135.5262)),
            JourneyLandmark("Dotonbori", Coordinate(34.6687, 135.5013)),
            JourneyLandmark("Universal Studios Japan", Coordinate(34.6654, 135.4323)),
            JourneyLandmark("Kiyomizu-dera", Coordinate(34.9949, 135.7850)),
            JourneyLandmark("Fushimi Inari Taisha", Coordinate(34.9671, 135.7727)),
            JourneyLandmark("Kinkaku-ji", Coordinate(35.0394, 135.7292)),
            JourneyLandmark("Arashiyama Bamboo Grove", Coordinate(35.0170, 135.6713)),
            JourneyLandmark("Todai-ji", Coordinate(34.6890, 135.8398)),
            JourneyLandmark("Himeji Castle", Coordinate(34.8394, 134.6939)),
            JourneyLandmark("Hiroshima Peace Memorial", Coordinate(34.3955, 132.4536)),
            JourneyLandmark("Itsukushima Shrine", Coordinate(34.2959, 132.3199)),
            JourneyLandmark("Fukuoka Tower", Coordinate(33.5933, 130.3515)),
            JourneyLandmark("Dazaifu Tenmangu", Coordinate(33.5215, 130.5348)),
            JourneyLandmark("Nagoya Castle", Coordinate(35.1856, 136.8990)),
            JourneyLandmark("Kenroku-en", Coordinate(36.5621, 136.6625)),
            JourneyLandmark("Matsumoto Castle", Coordinate(36.2387, 137.9690)),
            JourneyLandmark("Sapporo Clock Tower", Coordinate(43.0626, 141.3537)),
            JourneyLandmark("Otaru Canal", Coordinate(43.1988, 140.9947)),
            JourneyLandmark("Nikko Toshogu", Coordinate(36.7581, 139.5989)),
            JourneyLandmark("Kumamoto Castle", Coordinate(32.8062, 130.7058)),
            JourneyLandmark("Shuri Castle", Coordinate(26.2170, 127.7195)),
        ),
    ),
    SouthKorea(
        listOf(
            JourneyLandmark("Gyeongbokgung Palace", Coordinate(37.5796, 126.9770)),
            JourneyLandmark("N Seoul Tower", Coordinate(37.5512, 126.9882)),
            JourneyLandmark("Bukchon Hanok Village", Coordinate(37.5826, 126.9830)),
            JourneyLandmark("Dongdaemun Design Plaza", Coordinate(37.5670, 127.0095)),
            JourneyLandmark("Lotte World Tower", Coordinate(37.5125, 127.1025)),
            JourneyLandmark("Starfield COEX Mall", Coordinate(37.5117, 127.0592)),
            JourneyLandmark("Hongdae", Coordinate(37.5563, 126.9236)),
            JourneyLandmark("Banpo Bridge", Coordinate(37.5156, 126.9960)),
            JourneyLandmark("Incheon Chinatown", Coordinate(37.4759, 126.6170)),
            JourneyLandmark("Suwon Hwaseong Fortress", Coordinate(37.2882, 127.0144)),
            JourneyLandmark("Nami Island", Coordinate(37.7914, 127.5255)),
            JourneyLandmark("Haeundae Beach", Coordinate(35.1587, 129.1604)),
            JourneyLandmark("Gamcheon Culture Village", Coordinate(35.0975, 129.0106)),
            JourneyLandmark("Gwangalli Beach", Coordinate(35.1532, 129.1186)),
            JourneyLandmark("Haedong Yonggungsa", Coordinate(35.1883, 129.2233)),
            JourneyLandmark("Jagalchi Market", Coordinate(35.0967, 129.0305)),
            JourneyLandmark("Daegu 83 Tower", Coordinate(35.8533, 128.5665)),
            JourneyLandmark("Bulguksa", Coordinate(35.7900, 129.3320)),
            JourneyLandmark("Cheomseongdae", Coordinate(35.8347, 129.2191)),
            JourneyLandmark("Donggung Palace and Wolji Pond", Coordinate(35.8349, 129.2266)),
            JourneyLandmark("Jeonju Hanok Village", Coordinate(35.8149, 127.1530)),
            JourneyLandmark("Daejeon Expo Science Park", Coordinate(36.3751, 127.3862)),
            JourneyLandmark("Asia Culture Center", Coordinate(35.1468, 126.9199)),
            JourneyLandmark("Boseong Green Tea Fields", Coordinate(34.7199, 127.0817)),
            JourneyLandmark("Suncheon Bay Wetland", Coordinate(34.8854, 127.5090)),
            JourneyLandmark("Andong Hahoe Folk Village", Coordinate(36.5390, 128.5180)),
            JourneyLandmark("Seoraksan National Park", Coordinate(38.1194, 128.4656)),
            JourneyLandmark("Gyeongpo Beach", Coordinate(37.8057, 128.9087)),
            JourneyLandmark("Seongsan Ilchulbong", Coordinate(33.4581, 126.9425)),
            JourneyLandmark("Jeongbang Waterfall", Coordinate(33.2449, 126.5716)),
        ),
    ),
}

internal enum class JourneyDuration(val minutes: Int) {
    Short(30), Medium(60), Long(120),
}

internal data class AutoJourneyOptions(
    val region: JourneyRegion = JourneyRegion.Taiwan,
    val duration: JourneyDuration = JourneyDuration.Medium,
    val transportMode: RouteTransportMode = RouteTransportMode.Bicycle,
)

internal enum class RouteShape { Heart, Star, Circle, Cat, Dog, Rabbit, Fish, Butterfly, ChristmasTree }

internal data class GeneratedJourney(
    val shape: RouteShape,
    val center: Coordinate,
    val points: List<Coordinate>,
)

internal object JourneyPlanner {
    fun automaticJourney(
        options: AutoJourneyOptions,
        random: Random = Random.Default,
    ): GeneratedJourney {
        val targetDistanceMeters = (
            speedKilometersPerHour(options.transportMode) * 1_000.0 * options.duration.minutes / 60.0
        ).coerceIn(MINIMUM_JOURNEY_METERS, MAXIMUM_JOURNEY_METERS)
        val center = randomCenter(options.region, random)
        val shape = RouteShape.entries[random.nextInt(RouteShape.entries.size)]
        val baselineDistance = shapeDistanceMeters(center, shape)
        val radiusMeters = (DEFAULT_SHAPE_RADIUS_METERS * targetDistanceMeters / baselineDistance)
            .coerceIn(MINIMUM_SHAPE_RADIUS_METERS, MAXIMUM_SHAPE_RADIUS_METERS)
        return GeneratedJourney(shape, center, shapePoints(center, shape, radiusMeters))
    }

    private fun randomCenter(region: JourneyRegion, random: Random): Coordinate = GeoMath.destination(
        origin = region.landmarks[random.nextInt(region.landmarks.size)].coordinate,
        bearingDegrees = random.nextDouble(0.0, 360.0),
        distanceMeters = random.nextDouble(MINIMUM_CENTER_OFFSET_METERS, MAXIMUM_CENTER_OFFSET_METERS),
    )

    fun shapePoints(center: Coordinate, shape: RouteShape, radiusMeters: Double = DEFAULT_SHAPE_RADIUS_METERS): List<Coordinate> {
        require(radiusMeters in MINIMUM_SHAPE_RADIUS_METERS..MAXIMUM_SHAPE_RADIUS_METERS) {
            "Shape radius is out of range."
        }
        return when (shape) {
            RouteShape.Heart -> heartOffsets().map { (east, north) -> center.offset(east, north, radiusMeters) }
            RouteShape.Star -> starOffsets().map { (east, north) -> center.offset(east, north, radiusMeters) }
            RouteShape.Circle -> circleOffsets().map { (east, north) -> center.offset(east, north, radiusMeters) }
            RouteShape.Cat -> catOffsets().map { (east, north) -> center.offset(east, north, radiusMeters) }
            RouteShape.Dog -> dogOffsets().map { (east, north) -> center.offset(east, north, radiusMeters) }
            RouteShape.Rabbit -> rabbitOffsets().map { (east, north) -> center.offset(east, north, radiusMeters) }
            RouteShape.Fish -> fishOffsets().map { (east, north) -> center.offset(east, north, radiusMeters) }
            RouteShape.Butterfly -> butterflyOffsets().map { (east, north) -> center.offset(east, north, radiusMeters) }
            RouteShape.ChristmasTree -> christmasTreeOffsets().map { (east, north) -> center.offset(east, north, radiusMeters) }
        }
    }

    fun shapeDistanceMeters(center: Coordinate, shape: RouteShape): Double =
        RoutePolyline(shapePoints(center, shape)).totalDistanceMeters

    private fun heartOffsets(): List<Pair<Double, Double>> = (0..24).map { index ->
        val angle = index * 2.0 * PI / 24.0
        val east = 16.0 * sin(angle) * sin(angle) * sin(angle) / 18.0
        val north = (13.0 * cos(angle) - 5.0 * cos(2.0 * angle) -
            2.0 * cos(3.0 * angle) - cos(4.0 * angle)) / 18.0
        east to north
    }

    private fun starOffsets(): List<Pair<Double, Double>> = (0..10).map { index ->
        val pointIndex = index % 10
        val angle = -PI / 2.0 + pointIndex * PI / 5.0
        val radius = if (pointIndex % 2 == 0) 1.0 else 0.42
        cos(angle) * radius to -sin(angle) * radius
    }

    private fun circleOffsets(): List<Pair<Double, Double>> = (0..24).map { index ->
        val angle = index * 2.0 * PI / 24.0
        sin(angle) to cos(angle)
    }

    private fun catOffsets(): List<Pair<Double, Double>> = listOf(
        -0.52 to -0.82, -0.78 to -0.45, -0.84 to 0.18, -0.65 to 0.52,
        -0.76 to 1.0, -0.26 to 0.74, 0.26 to 0.74, 0.76 to 1.0,
        0.65 to 0.52, 0.84 to 0.18, 0.78 to -0.45, 0.52 to -0.82,
        -0.52 to -0.82,
    )

    private fun dogOffsets(): List<Pair<Double, Double>> = listOf(
        -0.45 to -0.75, -0.72 to -0.42, -0.68 to 0.25, -1.0 to 0.1,
        -0.9 to 0.88, -0.48 to 0.62, -0.3 to 0.82, 0.0 to 0.92,
        0.3 to 0.82, 0.48 to 0.62, 0.9 to 0.88, 1.0 to 0.1,
        0.68 to 0.25, 0.72 to -0.42, 0.45 to -0.75, 0.0 to -0.95,
        -0.45 to -0.75,
    )

    private fun rabbitOffsets(): List<Pair<Double, Double>> = listOf(
        -0.52 to -0.82, -0.76 to -0.42, -0.72 to 0.12, -0.48 to 0.42,
        -0.62 to 1.0, -0.38 to 0.98, -0.14 to 0.48, 0.14 to 0.48,
        0.38 to 0.98, 0.62 to 1.0, 0.48 to 0.42, 0.72 to 0.12,
        0.76 to -0.42, 0.52 to -0.82, -0.52 to -0.82,
    )

    private fun fishOffsets(): List<Pair<Double, Double>> = listOf(
        1.0 to 0.0, 0.52 to 0.5, -0.18 to 0.62, -0.62 to 0.34,
        -1.0 to 0.72, -0.82 to 0.0, -1.0 to -0.72, -0.62 to -0.34,
        -0.18 to -0.62, 0.52 to -0.5, 1.0 to 0.0,
    )

    private fun butterflyOffsets(): List<Pair<Double, Double>> = listOf(
        0.0 to 0.0, -0.24 to 0.2, -0.9 to 0.82, -1.0 to 0.2,
        -0.5 to 0.0, -0.9 to -0.78, -0.2 to -0.4, 0.0 to 0.0,
        0.2 to -0.4, 0.9 to -0.78, 0.5 to 0.0, 1.0 to 0.2,
        0.9 to 0.82, 0.24 to 0.2, 0.0 to 0.0,
    )

    private fun christmasTreeOffsets(): List<Pair<Double, Double>> = listOf(
        0.0 to 1.0, -0.35 to 0.5, -0.15 to 0.5, -0.6 to 0.0,
        -0.35 to 0.0, -0.85 to -0.55, -0.18 to -0.55, -0.18 to -0.9,
        0.18 to -0.9, 0.18 to -0.55, 0.85 to -0.55, 0.35 to 0.0,
        0.6 to 0.0, 0.15 to 0.5, 0.35 to 0.5, 0.0 to 1.0,
    )

    private fun Coordinate.offset(eastScale: Double, northScale: Double, radiusMeters: Double): Coordinate {
        val eastMeters = eastScale * radiusMeters
        val northMeters = northScale * radiusMeters
        return GeoMath.destination(
            origin = this,
            bearingDegrees = Math.toDegrees(atan2(eastMeters, northMeters)),
            distanceMeters = hypot(eastMeters, northMeters),
        )
    }

    private fun speedKilometersPerHour(mode: RouteTransportMode): Double = when (mode) {
        RouteTransportMode.Walk -> 5.0
        RouteTransportMode.Bicycle -> 18.0
        RouteTransportMode.Drive -> 50.0
    }

    private const val MINIMUM_JOURNEY_METERS = 1_500.0
    private const val MAXIMUM_JOURNEY_METERS = 100_000.0
    private const val MINIMUM_SHAPE_RADIUS_METERS = 100.0
    private const val MAXIMUM_SHAPE_RADIUS_METERS = 30_000.0
    private const val DEFAULT_SHAPE_RADIUS_METERS = 1_000.0
    private const val MINIMUM_CENTER_OFFSET_METERS = 100.0
    private const val MAXIMUM_CENTER_OFFSET_METERS = 1_000.0
}