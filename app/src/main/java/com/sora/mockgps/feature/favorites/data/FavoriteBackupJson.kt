package com.sora.mockgps.feature.favorites.data

import com.sora.mockgps.feature.favorites.domain.FavoriteBackup
import com.sora.mockgps.feature.favorites.domain.FavoriteLocation
import com.sora.mockgps.feature.favorites.domain.RecentLocation
import java.util.Locale
import org.json.JSONObject

/** Versioned JSON backup for favorites and recent locations. */
object FavoriteBackupJson {
    private const val VERSION = 1
    private const val MAX_LENGTH = 2_000_000
    private const val MAX_FAVORITES = 500
    private const val MAX_RECENT = 50
    private const val MAX_NAME_LENGTH = 100

    fun encode(backup: FavoriteBackup): String {
        require(backup.favorites.size <= MAX_FAVORITES) { "Too many favorites to export." }
        require(backup.recentLocations.size <= MAX_RECENT) { "Too many recent locations to export." }
        return buildString {
            append("{\"version\":").append(VERSION)
            append(",\"favorites\":[")
            backup.favorites.joinTo(this, separator = ",") { it.toJsonObject() }
            append("],\"recentLocations\":[")
            backup.recentLocations.joinTo(this, separator = ",") { it.toJsonObject() }
            append("]}")
        }
    }

    fun decode(serialized: String): FavoriteBackup {
        require(serialized.length <= MAX_LENGTH) { "Favorites backup is too large." }
        val root = JSONObject(serialized)
        require(root.length() == 3) { "Favorites backup contains missing or unknown fields." }
        require(root.has("version") && root.has("favorites") && root.has("recentLocations")) {
            "Favorites backup contains missing or unknown fields."
        }
        require(root.getInt("version") == VERSION) { "Unsupported favorites backup version." }
        val favoritesArray = root.getJSONArray("favorites")
        require(favoritesArray.length() <= MAX_FAVORITES) { "Favorites backup has too many favorites." }
        val favorites = (0 until favoritesArray.length()).map { index ->
            favoritesArray.getJSONObject(index).toFavorite("favorites[$index]")
        }
        val ids = favorites.map(FavoriteLocation::id)
        require(ids.size == ids.toSet().size) { "Favorites backup contains duplicate IDs." }
        val recentArray = root.getJSONArray("recentLocations")
        require(recentArray.length() <= MAX_RECENT) { "Favorites backup has too many recent locations." }
        val recent = (0 until recentArray.length()).map { index ->
            recentArray.getJSONObject(index).toRecent("recentLocations[$index]")
        }
        return FavoriteBackup(favorites, recent)
    }

    private fun FavoriteLocation.toJsonObject(): String = "{" + listOf(
        "\"id\":$id",
        "\"name\":${name.jsonString()}",
        "\"latitude\":$latitude",
        "\"longitude\":$longitude",
        "\"createdAt\":$createdAt",
        "\"updatedAt\":$updatedAt",
    ).joinToString(",") + "}"

    private fun RecentLocation.toJsonObject(): String = "{" + listOf(
        "\"id\":$id",
        "\"latitude\":$latitude",
        "\"longitude\":$longitude",
        "\"usedAt\":$usedAt",
    ).joinToString(",") + "}"

    private fun JSONObject.toFavorite(path: String): FavoriteLocation {
        requireKeys("id", "name", "latitude", "longitude", "createdAt", "updatedAt")
        val name = getString("name").trim()
        require(name.isNotEmpty() && name.length <= MAX_NAME_LENGTH) { "$path.name is invalid." }
        val latitude = getDouble("latitude")
        val longitude = getDouble("longitude")
        FavoriteCoordinate(latitude, longitude)
        val createdAt = getLong("createdAt")
        val updatedAt = getLong("updatedAt")
        require(createdAt >= 0 && updatedAt >= createdAt) { "$path timestamps are invalid." }
        val id = getLong("id")
        require(id > 0) { "$path.id must be positive." }
        return FavoriteLocation(
            id = id,
            name = name,
            latitude = latitude,
            longitude = longitude,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun JSONObject.toRecent(path: String): RecentLocation {
        requireKeys("id", "latitude", "longitude", "usedAt")
        val latitude = getDouble("latitude")
        val longitude = getDouble("longitude")
        FavoriteCoordinate(latitude, longitude)
        val usedAt = getLong("usedAt")
        require(usedAt >= 0) { "$path.usedAt is invalid." }
        val id = getLong("id")
        require(id > 0) { "$path.id must be positive." }
        return RecentLocation(id = id, latitude = latitude, longitude = longitude, usedAt = usedAt)
    }

    private fun JSONObject.requireKeys(vararg names: String) {
        require(length() == names.size && names.all(::has)) { "Favorites backup contains missing or unknown fields." }
    }

    private fun String.jsonString(): String = buildString {
        append('\"')
        for (character in this@jsonString) when (character) {
            '\\' -> append("\\\\")
            '\"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u%04x".format(Locale.US, character.code))
            } else {
                append(character)
            }
        }
        append('\"')
    }
}
