package com.sora.mockgps.feature.routes.data

import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.feature.routes.domain.ImportedRoute
import com.sora.mockgps.feature.routes.domain.RecentRoute
import com.sora.mockgps.feature.routes.domain.RouteBackup
import com.sora.mockgps.feature.routes.domain.SavedRoute
import java.io.StringReader
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import org.xml.sax.InputSource

/** Strict limits shared by database input, GPX import, and JSON backup restore. */
internal object RouteDataValidator {
    const val MAX_NAME_LENGTH = 100
    const val MAX_POINTS = 2_000
    const val MAX_DISTANCE_METERS = 1_000_000.0

    fun name(value: String): String = value.trim().also {
        require(it.isNotEmpty()) { "Route name cannot be blank." }
        require(it.length <= MAX_NAME_LENGTH) { "Route name cannot exceed $MAX_NAME_LENGTH characters." }
    }

    fun points(value: List<Coordinate>): List<Coordinate> {
        require(value.size in 2..MAX_POINTS) { "A route must contain 2 to $MAX_POINTS points." }
        value.forEach { point ->
            require(point.latitude.isFinite() && point.latitude in -90.0..90.0) { "Route contains an invalid latitude." }
            require(point.longitude.isFinite() && point.longitude in -180.0..180.0) { "Route contains an invalid longitude." }
        }
        require(distanceMeters(value) in 0.01..MAX_DISTANCE_METERS) {
            "Route distance must be between 0.01 m and ${MAX_DISTANCE_METERS.toInt()} m."
        }
        return value.toList()
    }

    fun distanceMeters(points: List<Coordinate>): Double {
        var distance = 0.0
        for (index in 1 until points.size) distance += distanceMeters(points[index - 1], points[index])
        return distance
    }

    private fun distanceMeters(first: Coordinate, second: Coordinate): Double {
        val latitudeDelta = radians(second.latitude - first.latitude)
        val longitudeDelta = radians(normalizeLongitude(second.longitude - first.longitude))
        val firstLatitude = radians(first.latitude)
        val secondLatitude = radians(second.latitude)
        val a = sin(latitudeDelta / 2).let { it * it } +
            cos(firstLatitude) * cos(secondLatitude) * sin(longitudeDelta / 2).let { it * it }
        return EARTH_RADIUS_METERS * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    private fun radians(value: Double): Double = value * Math.PI / 180.0
    private fun normalizeLongitude(value: Double): Double = ((value + 540.0) % 360.0) - 180.0
    private const val EARTH_RADIUS_METERS = 6_371_008.8
}

/** Compact, SDK-independent storage format for a route's [latitude, longitude] pairs. */
object RouteGeometryCodec {
    fun encode(points: List<Coordinate>): String = RouteDataValidator.points(points).joinToString(
        prefix = "[",
        postfix = "]",
    ) { point -> "[${point.latitude},${point.longitude}]" }

    fun decode(serialized: String): List<Coordinate> = parseGeometry(StrictJson.parse(serialized))
}

private fun parseGeometry(value: JsonValue): List<Coordinate> {
    val entries = value.array("geometry")
    val points = entries.mapIndexed { index, entry ->
        val pair = entry.array("geometry[$index]")
        require(pair.size == 2) { "Each route coordinate must contain latitude and longitude." }
        Coordinate(pair[0].number("geometry[$index][0]"), pair[1].number("geometry[$index][1]"))
    }
    return RouteDataValidator.points(points)
}

object RouteGpxInterchange {
    private const val MAX_GPX_LENGTH = 1_000_000

    fun export(name: String, points: List<Coordinate>): String {
        val safeName = RouteDataValidator.name(name)
        val safePoints = RouteDataValidator.points(points)
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<gpx version=\"1.1\" creator=\"MockGPS-Android\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
            append("<trk><name>").append(safeName.xmlEscape()).append("</name><trkseg>")
            safePoints.forEach { point ->
                append("<trkpt lat=\"").append(point.latitude)
                    .append("\" lon=\"").append(point.longitude).append("\"/>")
            }
            append("</trkseg></trk></gpx>")
        }
    }

    fun import(serialized: String): ImportedRoute {
        require(serialized.length <= MAX_GPX_LENGTH) { "GPX file is too large." }
        require(!serialized.contains("<!DOCTYPE", ignoreCase = true) && !serialized.contains("<!ENTITY", ignoreCase = true)) {
            "GPX files cannot contain document type or entity declarations."
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(serialized)))
        require(document.documentElement?.localName == "gpx") { "GPX root element must be gpx." }
        val trackPoints = document.getElementsByTagNameNS("*", "trkpt").let { nodes ->
            (0 until nodes.length).map { index -> nodes.item(index) }
        }
        require(trackPoints.isNotEmpty()) { "GPX file does not contain a track." }
        val points = trackPoints.map { node ->
            val attributes = node.attributes
            val latitude = attributes.getNamedItem("lat")?.nodeValue?.toDoubleOrNull()
            val longitude = attributes.getNamedItem("lon")?.nodeValue?.toDoubleOrNull()
            require(latitude != null && longitude != null) { "Every GPX track point needs latitude and longitude." }
            Coordinate(latitude, longitude)
        }
        val routeName = document.getElementsByTagNameNS("*", "name")
            .item(0)?.textContent?.takeIf { it.isNotBlank() } ?: "Imported route"
        return ImportedRoute(RouteDataValidator.name(routeName), RouteDataValidator.points(points))
    }

    private fun String.xmlEscape(): String = replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}

/** Versioned JSON backup suitable for export to user-controlled storage. */
object RouteBackupJson {
    private const val VERSION = 1

    fun encode(backup: RouteBackup): String = buildString {
        append('{').append("\"version\":").append(VERSION)
        append(",\"savedRoutes\":[")
        backup.savedRoutes.joinTo(this, separator = ",") { route -> route.savedJson() }
        append("],\"recentRoutes\":[")
        backup.recentRoutes.joinTo(this, separator = ",") { route -> route.recentJson() }
        append("]}")
    }

    fun decode(serialized: String): RouteBackup {
        val root = StrictJson.parse(serialized).objectValue("backup")
        root.requireOnly("version", "savedRoutes", "recentRoutes")
        require(root.required("version").number("version") == VERSION.toDouble()) { "Unsupported route backup version." }
        val saved = root.required("savedRoutes").array("savedRoutes").mapIndexed { index, value ->
            value.savedRoute("savedRoutes[$index]")
        }
        val ids = saved.map(SavedRoute::id)
        require(ids.size == ids.toSet().size) { "Backup contains duplicate saved route IDs." }
        val savedIds = ids.toSet()
        require(saved.all { it.reversedFromRouteId == null || it.reversedFromRouteId in savedIds }) {
            "Backup contains a reversed route with an unknown source."
        }
        val recent = root.required("recentRoutes").array("recentRoutes").mapIndexed { index, value ->
            value.recentRoute("recentRoutes[$index]", savedIds)
        }
        require(recent.size <= 50) { "Backup contains more than 50 recent routes." }
        return RouteBackup(saved, recent)
    }

    private fun SavedRoute.savedJson(): String = "{" + listOf(
        "\"id\":$id",
        "\"name\":${name.jsonString()}",
        "\"geometry\":${RouteGeometryCodec.encode(points)}",
        "\"distanceMeters\":$distanceMeters",
        "\"createdAt\":$createdAt",
        "\"updatedAt\":$updatedAt",
        "\"reversedFromRouteId\":${reversedFromRouteId ?: "null"}",
    ).joinToString(",") + "}"

    private fun RecentRoute.recentJson(): String = "{" + listOf(
        "\"id\":$id",
        "\"name\":${name.jsonString()}",
        "\"geometry\":${RouteGeometryCodec.encode(points)}",
        "\"distanceMeters\":$distanceMeters",
        "\"usedAt\":$usedAt",
        "\"savedRouteId\":${savedRouteId ?: "null"}",
    ).joinToString(",") + "}"

    private fun JsonValue.savedRoute(path: String): SavedRoute {
        val objectValue = objectValue(path)
        objectValue.requireOnly("id", "name", "geometry", "distanceMeters", "createdAt", "updatedAt", "reversedFromRouteId")
        val points = parseGeometry(objectValue.required("geometry"))
        val distance = objectValue.required("distanceMeters").positiveNumber("$path.distanceMeters")
        val createdAt = objectValue.required("createdAt").nonNegativeLong("$path.createdAt")
        val updatedAt = objectValue.required("updatedAt").nonNegativeLong("$path.updatedAt")
        require(updatedAt >= createdAt) { "$path.updatedAt cannot precede createdAt." }
        return SavedRoute(
            id = objectValue.required("id").positiveLong("$path.id"),
            name = RouteDataValidator.name(objectValue.required("name").string("$path.name")),
            points = points,
            distanceMeters = distance,
            createdAt = createdAt,
            updatedAt = updatedAt,
            reversedFromRouteId = objectValue.required("reversedFromRouteId").nullablePositiveLong("$path.reversedFromRouteId"),
        )
    }

    private fun JsonValue.recentRoute(path: String, savedIds: Set<Long>): RecentRoute {
        val objectValue = objectValue(path)
        objectValue.requireOnly("id", "name", "geometry", "distanceMeters", "usedAt", "savedRouteId")
        val savedRouteId = objectValue.required("savedRouteId").nullablePositiveLong("$path.savedRouteId")
        require(savedRouteId == null || savedRouteId in savedIds) { "$path references an unknown saved route." }
        return RecentRoute(
            id = objectValue.required("id").positiveLong("$path.id"),
            name = RouteDataValidator.name(objectValue.required("name").string("$path.name")),
            points = parseGeometry(objectValue.required("geometry")),
            distanceMeters = objectValue.required("distanceMeters").positiveNumber("$path.distanceMeters"),
            usedAt = objectValue.required("usedAt").nonNegativeLong("$path.usedAt"),
            savedRouteId = savedRouteId,
        )
    }

    private fun String.jsonString(): String = buildString {
        append('\"')
        for (character in this@jsonString) when (character) {
            '\\' -> append("\\\\")
            '\"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) append("\\u%04x".format(Locale.US, character.code)) else append(character)
        }
        append('\"')
    }
}

private sealed interface JsonValue {
    fun objectValue(path: String): Map<String, JsonValue> = error("$path must be an object.")
    fun array(path: String): List<JsonValue> = error("$path must be an array.")
    fun string(path: String): String = error("$path must be a string.")
    fun number(path: String): Double = error("$path must be a number.")
}

private data class JsonObject(val values: Map<String, JsonValue>) : JsonValue {
    override fun objectValue(path: String) = values
}
private data class JsonArray(val values: List<JsonValue>) : JsonValue {
    override fun array(path: String) = values
}
private data class JsonString(val value: String) : JsonValue {
    override fun string(path: String) = value
}
private data class JsonNumber(val value: Double) : JsonValue {
    override fun number(path: String) = value
}
private data object JsonNull : JsonValue

private fun Map<String, JsonValue>.required(name: String): JsonValue =
    requireNotNull(this[name]) { "Backup is missing '$name'." }
private fun Map<String, JsonValue>.requireOnly(vararg names: String) {
    require(keys.all { it in names } && keys.size == names.size) { "Backup contains missing or unknown fields." }
}
private fun JsonValue.positiveNumber(path: String): Double = number(path).also {
    require(it.isFinite() && it in 0.01..RouteDataValidator.MAX_DISTANCE_METERS) { "$path is invalid." }
}
private fun JsonValue.positiveLong(path: String): Long = number(path).toLongStrict(path).also {
    require(it > 0) { "$path must be positive." }
}
private fun JsonValue.nonNegativeLong(path: String): Long = number(path).toLongStrict(path).also {
    require(it >= 0) { "$path must not be negative." }
}
private fun JsonValue.nullablePositiveLong(path: String): Long? = if (this === JsonNull) null else positiveLong(path)
private fun Double.toLongStrict(path: String): Long {
    require(isFinite() && this == kotlin.math.floor(this) && this in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()) {
        "$path must be an integer."
    }
    return toLong()
}

private object StrictJson {
    private const val MAX_LENGTH = 2_000_000
    fun parse(input: String): JsonValue {
        require(input.length <= MAX_LENGTH) { "JSON backup is too large." }
        return Reader(input).read()
    }

    private class Reader(private val input: String) {
        private var index = 0
        fun read(): JsonValue {
            skipWhitespace()
            val value = value()
            skipWhitespace()
            require(index == input.length) { "Unexpected trailing JSON content." }
            return value
        }
        private fun value(): JsonValue {
            require(index < input.length) { "Unexpected end of JSON." }
            return when (input[index]) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '\"' -> JsonString(stringValue())
                'n' -> { literal("null"); JsonNull }
                '-', in '0'..'9' -> JsonNumber(numberValue())
                else -> error("Invalid JSON value at character $index.")
            }
        }
        private fun objectValue(): JsonObject {
            index++
            skipWhitespace()
            val values = linkedMapOf<String, JsonValue>()
            if (consume('}')) return JsonObject(values)
            while (true) {
                skipWhitespace(); require(peek() == '\"') { "Expected JSON object key." }
                val key = stringValue(); require(values.put(key, valueAfterColon()) == null) { "Duplicate JSON key '$key'." }
                skipWhitespace()
                if (consume('}')) return JsonObject(values)
                require(consume(',')) { "Expected ',' in JSON object." }; skipWhitespace()
            }
        }
        private fun valueAfterColon(): JsonValue { skipWhitespace(); require(consume(':')) { "Expected ':' after JSON key." }; skipWhitespace(); return value() }
        private fun arrayValue(): JsonArray {
            index++; skipWhitespace(); val values = mutableListOf<JsonValue>()
            if (consume(']')) return JsonArray(values)
            while (true) {
                values += value(); skipWhitespace()
                if (consume(']')) return JsonArray(values)
                require(consume(',')) { "Expected ',' in JSON array." }; skipWhitespace()
            }
        }
        private fun stringValue(): String {
            require(consume('\"')) { "Expected JSON string." }; val output = StringBuilder()
            while (index < input.length) {
                when (val character = input[index++]) {
                    '\"' -> return output.toString()
                    '\\' -> output.append(escape())
                    else -> { require(character.code >= 0x20) { "Control character in JSON string." }; output.append(character) }
                }
            }
            error("Unterminated JSON string.")
        }
        private fun escape(): Char = when (val escaped = input.getOrNull(index++) ?: error("Bad JSON escape.")) {
            '\"', '\\', '/' -> escaped
            'b' -> '\b'; 'f' -> '\u000C'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
            'u' -> {
                require(index + 4 <= input.length) { "Incomplete JSON unicode escape." }
                input.substring(index, index + 4).also { index += 4 }.toIntOrNull(16)?.toChar()
                    ?: error("Invalid JSON unicode escape.")
            }
            else -> error("Invalid JSON escape.")
        }
        private fun numberValue(): Double {
            val start = index
            if (peek() == '-') index++
            require(peek() in '0'..'9') { "Invalid JSON number." }
            if (peek() == '0') index++ else while (peek() in '0'..'9') index++
            if (peek() == '.') { index++; require(peek() in '0'..'9') { "Invalid JSON number." }; while (peek() in '0'..'9') index++ }
            if (peek() == 'e' || peek() == 'E') { index++; if (peek() == '+' || peek() == '-') index++; require(peek() in '0'..'9') { "Invalid JSON number." }; while (peek() in '0'..'9') index++ }
            return input.substring(start, index).toDoubleOrNull()?.takeIf(Double::isFinite) ?: error("Invalid JSON number.")
        }
        private fun literal(value: String) { require(input.regionMatches(index, value, 0, value.length)) { "Invalid JSON literal." }; index += value.length }
        private fun skipWhitespace() { while (peek()?.isWhitespace() == true) index++ }
        private fun consume(value: Char): Boolean = (peek() == value).also { if (it) index++ }
        private fun peek(): Char? = input.getOrNull(index)
    }
}
