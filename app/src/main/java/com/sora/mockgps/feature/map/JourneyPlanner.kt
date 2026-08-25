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

private const val DEFAULT_LANDMARK_RADIUS_METERS = 6_000.0

/** Stable English [name] for ids/matching; [nameZhTw] Traditional Chinese; [aliases] for search. */
internal data class JourneyLandmark(
    val name: String,
    val nameZhTw: String,
    val coordinate: Coordinate,
    val maximumShapeRadiusMeters: Double = DEFAULT_LANDMARK_RADIUS_METERS,
    val aliases: List<String> = emptyList(),
)

internal fun JourneyLandmark.displayName(useZhTw: Boolean): String =
    if (useZhTw) nameZhTw else name

/** Case-insensitive contains/prefix match on name, nameZhTw, and aliases (spaces normalized). */
internal fun matchLandmarks(query: String, landmarks: List<JourneyLandmark>): List<JourneyLandmark> {
    val needle = normalizeLandmarkSearchText(query)
    if (needle.length < 2) return emptyList()
    val needleCompact = needle.replace(" ", "")
    return landmarks.filter { landmark ->
        landmark.searchTexts().any { candidate ->
            val normalized = normalizeLandmarkSearchText(candidate)
            val compact = normalized.replace(" ", "")
            normalized.contains(needle) ||
                normalized.startsWith(needle) ||
                compact.contains(needleCompact) ||
                compact.startsWith(needleCompact)
        }
    }
}

private fun JourneyLandmark.searchTexts(): List<String> =
    buildList {
        add(name)
        add(nameZhTw)
        addAll(aliases)
    }

internal fun normalizeLandmarkSearchText(value: String): String =
    value.trim().lowercase().replace(Regex("\\s+"), " ")

internal fun JourneyRegion.nominatimCountryCode(): String = when (this) {
    JourneyRegion.Taiwan -> "tw"
    JourneyRegion.Japan -> "jp"
    JourneyRegion.SouthKorea -> "kr"
}

internal fun nearestJourneyRegion(coordinate: Coordinate): JourneyRegion =
    JourneyRegion.entries.minBy { region ->
        region.landmarks.minOf { GeoMath.distanceMeters(coordinate, it.coordinate) }
    }

internal enum class JourneyRegion(val landmarks: List<JourneyLandmark>) {
    Taiwan(
        listOf(
            JourneyLandmark(
                "Taipei 101", "台北101", Coordinate(25.0340, 121.5645),
                aliases = listOf("101", "台北101", "台北 101"),
            ),
            JourneyLandmark(
                "Chiang Kai-shek Memorial Hall", "中正紀念堂", Coordinate(25.0346, 121.5218),
                aliases = listOf("中正紀念堂", "CKS Memorial"),
            ),
            JourneyLandmark(
                "National Palace Museum", "國立故宮博物院", Coordinate(25.1024, 121.5485),
                aliases = listOf("故宮", "故宮博物院"),
            ),
            JourneyLandmark("Longshan Temple", "龍山寺", Coordinate(25.0368, 121.5000), aliases = listOf("龍山寺")),
            JourneyLandmark("Ximending", "西門町", Coordinate(25.0422, 121.5073), aliases = listOf("西門", "西門町")),
            JourneyLandmark("Dadaocheng Wharf", "大稻埕碼頭", Coordinate(25.0566, 121.5075), aliases = listOf("大稻埕")),
            JourneyLandmark("Tamsui Old Street", "淡水老街", Coordinate(25.1676, 121.4450), 2_000.0, listOf("淡水")),
            JourneyLandmark("Yehliu Geopark", "野柳地質公園", Coordinate(25.2053, 121.6906), 1_000.0, listOf("野柳")),
            JourneyLandmark("Jiufen Old Street", "九份老街", Coordinate(25.1099, 121.8452), 1_500.0, listOf("九份")),
            JourneyLandmark("Shifen Waterfall", "十分瀑布", Coordinate(25.0494, 121.7877), 1_500.0, listOf("十分")),
            JourneyLandmark(
                "National Taichung Theater", "臺中國家歌劇院", Coordinate(24.1629, 120.6404),
                aliases = listOf("台中歌劇院", "臺中歌劇院"),
            ),
            JourneyLandmark("Rainbow Village", "彩虹眷村", Coordinate(24.1337, 120.6098), aliases = listOf("彩虹眷村")),
            JourneyLandmark("Lukang Longshan Temple", "鹿港龍山寺", Coordinate(24.0521, 120.4350), aliases = listOf("鹿港")),
            JourneyLandmark(
                "Sun Moon Lake Wenwu Temple", "日月潭文武廟", Coordinate(23.8691, 120.9270), 1_500.0,
                listOf("日月潭", "文武廟"),
            ),
            JourneyLandmark("Alishan Station", "阿里山車站", Coordinate(23.5102, 120.8014), 1_000.0, listOf("阿里山")),
            JourneyLandmark("Chihkan Tower", "赤崁樓", Coordinate(22.9975, 120.2025), aliases = listOf("赤崁樓")),
            JourneyLandmark("Anping Fort", "安平古堡", Coordinate(23.0016, 120.1606), aliases = listOf("安平")),
            JourneyLandmark("Chimei Museum", "奇美博物館", Coordinate(22.9346, 120.2260), aliases = listOf("奇美")),
            JourneyLandmark("Fo Guang Shan", "佛光山", Coordinate(22.7551, 120.4451), aliases = listOf("佛光山")),
            JourneyLandmark(
                "Pier-2 Art Center", "駁二藝術特區", Coordinate(22.6206, 120.2813),
                aliases = listOf("駁二", "Pier-2"),
            ),
            JourneyLandmark("Lotus Pond", "蓮池潭", Coordinate(22.6804, 120.2913), aliases = listOf("蓮池潭")),
            JourneyLandmark(
                "Taroko National Park Gate", "太魯閣國家公園入口", Coordinate(24.1593, 121.6214), 1_000.0,
                listOf("太魯閣"),
            ),
            JourneyLandmark("Qixingtan Beach", "七星潭", Coordinate(24.0314, 121.6273), 1_000.0, listOf("七星潭")),
            JourneyLandmark(
                "Kenting National Park", "墾丁國家公園", Coordinate(21.9456, 120.7798), 1_500.0,
                listOf("墾丁"),
            ),
            JourneyLandmark("Sanxiantai", "三仙台", Coordinate(23.1239, 121.4090), 1_000.0, listOf("三仙台")),
        ),
    ),
    Japan(
        listOf(
            JourneyLandmark("Tokyo Tower", "東京鐵塔", Coordinate(35.6586, 139.7454), aliases = listOf("東京塔")),
            JourneyLandmark(
                "Tokyo Skytree", "東京晴空塔", Coordinate(35.7101, 139.8107),
                aliases = listOf("晴空塔", "Skytree"),
            ),
            JourneyLandmark("Senso-ji", "淺草寺", Coordinate(35.7148, 139.7967), aliases = listOf("淺草寺", "淺草")),
            JourneyLandmark("Shibuya Crossing", "澀谷十字路口", Coordinate(35.6595, 139.7005), aliases = listOf("澀谷")),
            JourneyLandmark("Meiji Shrine", "明治神宮", Coordinate(35.6764, 139.6993), aliases = listOf("明治神宮")),
            JourneyLandmark("Imperial Palace", "皇居", Coordinate(35.6852, 139.7528), aliases = listOf("皇居")),
            JourneyLandmark("Odaiba", "台場", Coordinate(35.6272, 139.7768), 2_000.0, listOf("台場", "お台場")),
            JourneyLandmark("Yokohama Landmark Tower", "橫濱地標塔", Coordinate(35.4548, 139.6317)),
            JourneyLandmark(
                "Great Buddha of Kamakura", "鎌倉大佛", Coordinate(35.3167, 139.5357), 1_500.0,
                listOf("鎌倉", "大佛"),
            ),
            JourneyLandmark("Osaka Castle", "大阪城", Coordinate(34.6873, 135.5262), aliases = listOf("大阪城")),
            JourneyLandmark("Dotonbori", "道頓堀", Coordinate(34.6687, 135.5013), aliases = listOf("道頓堀")),
            JourneyLandmark(
                "Universal Studios Japan", "日本環球影城", Coordinate(34.6654, 135.4323),
                aliases = listOf("USJ", "環球影城"),
            ),
            JourneyLandmark("Kiyomizu-dera", "清水寺", Coordinate(34.9949, 135.7850), aliases = listOf("清水寺")),
            JourneyLandmark(
                "Fushimi Inari Taisha", "伏見稻荷大社", Coordinate(34.9671, 135.7727),
                aliases = listOf("伏見稻荷", "千本鳥居"),
            ),
            JourneyLandmark("Kinkaku-ji", "金閣寺", Coordinate(35.0394, 135.7292), aliases = listOf("金閣寺")),
            JourneyLandmark(
                "Arashiyama Bamboo Grove", "嵐山竹林", Coordinate(35.0170, 135.6713),
                aliases = listOf("嵐山", "竹林"),
            ),
            JourneyLandmark("Todai-ji", "東大寺", Coordinate(34.6890, 135.8398), aliases = listOf("東大寺")),
            JourneyLandmark("Himeji Castle", "姬路城", Coordinate(34.8394, 134.6939), aliases = listOf("姬路城")),
            JourneyLandmark(
                "Hiroshima Peace Memorial", "廣島和平紀念公園", Coordinate(34.3955, 132.4536),
                aliases = listOf("原爆圓頂", "原爆穹頂"),
            ),
            JourneyLandmark(
                "Itsukushima Shrine", "嚴島神社", Coordinate(34.2959, 132.3199), 1_000.0,
                listOf("嚴島神社", "宮島"),
            ),
            JourneyLandmark("Fukuoka Tower", "福岡塔", Coordinate(33.5933, 130.3515)),
            JourneyLandmark("Dazaifu Tenmangu", "太宰府天滿宮", Coordinate(33.5215, 130.5348), aliases = listOf("太宰府")),
            JourneyLandmark("Nagoya Castle", "名古屋城", Coordinate(35.1856, 136.8990), aliases = listOf("名古屋城")),
            JourneyLandmark("Kenroku-en", "兼六園", Coordinate(36.5621, 136.6625), aliases = listOf("兼六園")),
            JourneyLandmark("Matsumoto Castle", "松本城", Coordinate(36.2387, 137.9690), aliases = listOf("松本城")),
            JourneyLandmark("Sapporo Clock Tower", "札幌時鐘台", Coordinate(43.0626, 141.3537), aliases = listOf("時計台")),
            JourneyLandmark("Otaru Canal", "小樽運河", Coordinate(43.1988, 140.9947), 1_500.0, listOf("小樽")),
            JourneyLandmark("Nikko Toshogu", "日光東照宮", Coordinate(36.7581, 139.5989), 1_500.0, listOf("日光", "東照宮")),
            JourneyLandmark("Kumamoto Castle", "熊本城", Coordinate(32.8062, 130.7058), aliases = listOf("熊本城")),
            JourneyLandmark("Shuri Castle", "首里城", Coordinate(26.2170, 127.7195), aliases = listOf("首里城")),
        ),
    ),
    SouthKorea(
        listOf(
            JourneyLandmark(
                "Gyeongbokgung Palace", "景福宮", Coordinate(37.5796, 126.9770),
                aliases = listOf("景福宮"),
            ),
            JourneyLandmark(
                "N Seoul Tower", "N首爾塔", Coordinate(37.5512, 126.9882),
                aliases = listOf("南山塔", "N塔"),
            ),
            JourneyLandmark(
                "Bukchon Hanok Village", "北村韓屋村", Coordinate(37.5826, 126.9830),
                aliases = listOf("北村"),
            ),
            JourneyLandmark(
                "Dongdaemun Design Plaza", "東大門設計廣場", Coordinate(37.5670, 127.0095),
                aliases = listOf("DDP", "東大門"),
            ),
            JourneyLandmark(
                "Lotte World Tower", "樂天世界塔", Coordinate(37.5125, 127.1025),
                aliases = listOf("樂天塔"),
            ),
            JourneyLandmark(
                "Starfield COEX Mall", "COEX商場", Coordinate(37.5117, 127.0592),
                aliases = listOf("COEX"),
            ),
            JourneyLandmark("Hongdae", "弘大", Coordinate(37.5563, 126.9236), aliases = listOf("弘大")),
            JourneyLandmark("Banpo Bridge", "盤浦大橋", Coordinate(37.5156, 126.9960), aliases = listOf("盤浦大橋")),
            JourneyLandmark("Incheon Chinatown", "仁川中華街", Coordinate(37.4759, 126.6170)),
            JourneyLandmark(
                "Suwon Hwaseong Fortress", "水原華城", Coordinate(37.2882, 127.0144),
                aliases = listOf("華城", "水原"),
            ),
            JourneyLandmark("Nami Island", "南怡島", Coordinate(37.7914, 127.5255), 1_500.0, listOf("南怡島")),
            JourneyLandmark("Haeundae Beach", "海雲台", Coordinate(35.1587, 129.1604), 1_500.0, listOf("海雲台")),
            JourneyLandmark(
                "Gamcheon Culture Village", "甘川文化村", Coordinate(35.0975, 129.0106),
                aliases = listOf("甘川"),
            ),
            JourneyLandmark("Gwangalli Beach", "廣安里", Coordinate(35.1532, 129.1186), 1_500.0, listOf("廣安里")),
            JourneyLandmark(
                "Haedong Yonggungsa", "海東龍宮寺", Coordinate(35.1883, 129.2233), 1_000.0,
                listOf("龍宮寺"),
            ),
            JourneyLandmark("Jagalchi Market", "札嘎其市場", Coordinate(35.0967, 129.0305), aliases = listOf("札嘎其")),
            JourneyLandmark("Daegu 83 Tower", "大邱83塔", Coordinate(35.8533, 128.5665), aliases = listOf("83塔")),
            JourneyLandmark("Bulguksa", "佛國寺", Coordinate(35.7900, 129.3320), aliases = listOf("佛國寺")),
            JourneyLandmark("Cheomseongdae", "瞻星台", Coordinate(35.8347, 129.2191), aliases = listOf("瞻星台")),
            JourneyLandmark(
                "Donggung Palace and Wolji Pond", "東宮與月池", Coordinate(35.8349, 129.2266),
                aliases = listOf("月池", "雁鴨池"),
            ),
            JourneyLandmark(
                "Jeonju Hanok Village", "全州韓屋村", Coordinate(35.8149, 127.1530),
                aliases = listOf("全州"),
            ),
            JourneyLandmark("Daejeon Expo Science Park", "大田世博科學公園", Coordinate(36.3751, 127.3862)),
            JourneyLandmark("Asia Culture Center", "亞洲文化殿堂", Coordinate(35.1468, 126.9199), aliases = listOf("ACC")),
            JourneyLandmark(
                "Boseong Green Tea Fields", "寶城綠茶田", Coordinate(34.7199, 127.0817), 1_500.0,
                listOf("寶城", "綠茶田"),
            ),
            JourneyLandmark(
                "Suncheon Bay Wetland", "順天灣濕地", Coordinate(34.8854, 127.5090), 1_000.0,
                listOf("順天灣"),
            ),
            JourneyLandmark(
                "Andong Hahoe Folk Village", "安東河回村", Coordinate(36.5390, 128.5180),
                aliases = listOf("河回村", "安東"),
            ),
            JourneyLandmark(
                "Seoraksan National Park", "雪嶽山國家公園", Coordinate(38.1194, 128.4656), 1_000.0,
                listOf("雪嶽山"),
            ),
            JourneyLandmark("Gyeongpo Beach", "鏡浦海水浴場", Coordinate(37.8057, 128.9087), 1_000.0, listOf("鏡浦")),
            JourneyLandmark(
                "Seongsan Ilchulbong", "城山日出峰", Coordinate(33.4581, 126.9425), 1_000.0,
                listOf("日出峰", "城山"),
            ),
            JourneyLandmark("Jeongbang Waterfall", "正房瀑布", Coordinate(33.2449, 126.5716), 1_000.0),
        ),
    ),
}

internal val journeyLandmarks: List<JourneyLandmark> = JourneyRegion.entries.flatMap { it.landmarks }

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
    val landmark: JourneyLandmark,
    val center: Coordinate,
    val points: List<Coordinate>,
)

internal object JourneyPlanner {
    fun automaticJourney(
        options: AutoJourneyOptions,
        random: Random = Random.Default,
        excludedLandmark: JourneyLandmark? = null,
    ): GeneratedJourney {
        val targetDistanceMeters = (
            speedKilometersPerHour(options.transportMode) * 1_000.0 * options.duration.minutes / 60.0
        ).coerceIn(MINIMUM_JOURNEY_METERS, MAXIMUM_JOURNEY_METERS)
        val landmark = randomLandmark(options.region, random, excludedLandmark)
        val center = randomCenter(landmark, random)
        val shape = RouteShape.entries[random.nextInt(RouteShape.entries.size)]
        val baselineDistance = shapeDistanceMeters(center, shape)
        val radiusMeters = (DEFAULT_SHAPE_RADIUS_METERS * targetDistanceMeters / baselineDistance)
            .coerceIn(
                MINIMUM_SHAPE_RADIUS_METERS,
                minOf(transportRadiusLimit(options.transportMode), landmark.maximumShapeRadiusMeters),
            )
        return GeneratedJourney(shape, landmark, center, shapePoints(center, shape, radiusMeters))
    }

    private fun randomLandmark(
        region: JourneyRegion,
        random: Random,
        excludedLandmark: JourneyLandmark?,
    ): JourneyLandmark {
        val candidates = region.landmarks.filterNot { it == excludedLandmark }.ifEmpty { region.landmarks }
        return candidates[random.nextInt(candidates.size)]
    }

    private fun randomCenter(landmark: JourneyLandmark, random: Random): Coordinate = GeoMath.destination(
        origin = landmark.coordinate,
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

    private fun transportRadiusLimit(mode: RouteTransportMode): Double = when (mode) {
        RouteTransportMode.Walk -> 1_500.0
        RouteTransportMode.Bicycle -> 4_000.0
        RouteTransportMode.Drive -> 8_000.0
    }

    private const val MINIMUM_JOURNEY_METERS = 1_500.0
    private const val MAXIMUM_JOURNEY_METERS = 100_000.0
    private const val MINIMUM_SHAPE_RADIUS_METERS = 100.0
    private const val MAXIMUM_SHAPE_RADIUS_METERS = 8_000.0
    private const val DEFAULT_SHAPE_RADIUS_METERS = 1_000.0
    private const val MINIMUM_CENTER_OFFSET_METERS = 100.0
    private const val MAXIMUM_CENTER_OFFSET_METERS = 1_000.0
}