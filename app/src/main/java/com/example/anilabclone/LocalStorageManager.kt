package com.example.anilabclone

import android.content.Context
import android.net.Uri
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class WatchHistoryItem(
    val anime: AnimeMedia,
    val episodeNumber: Int,
    val audioType: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
)

data class OfflineEpisodeItem(
    val fileName: String,
    val displayTitle: String,
    val episodeNumber: Int,
    val audioType: String,
    val fileUri: String,
    val sizeMb: String,
    val filePath: String
)

object LocalStorageManager {
    private const val PREF_NAME = "anilab_local_store"
    private const val KEY_BOOKMARKS = "bookmarks_json"
    private const val KEY_HISTORY = "watch_history_json"
    private const val KEY_EPISODE_POS_PREFIX = "ep_pos_"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getBookmarks(context: Context): List<AnimeMedia> {
        val raw = prefs(context).getString(KEY_BOOKMARKS, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<AnimeMedia>()
        for (i in 0 until arr.length()) {
            list.add(jsonToAnime(arr.getJSONObject(i)))
        }
        return list
    }

    fun isBookmarked(context: Context, animeId: Int): Boolean {
        return getBookmarks(context).any { it.id == animeId }
    }

    fun toggleBookmark(context: Context, anime: AnimeMedia): Boolean {
        val current = getBookmarks(context).toMutableList()
        val exists = current.any { it.id == anime.id }
        if (exists) {
            current.removeAll { it.id == anime.id }
        } else {
            current.add(0, anime)
        }
        val arr = JSONArray()
        current.forEach { arr.put(animeToJson(it)) }
        prefs(context).edit().putString(KEY_BOOKMARKS, arr.toString()).apply()
        return !exists
    }

    fun getSavedEpisodePosition(context: Context, animeId: Int, episodeNumber: Int): Long {
        return prefs(context).getLong("$KEY_EPISODE_POS_PREFIX${animeId}_$episodeNumber", 0L)
    }

    fun saveWatchProgress(
        context: Context,
        anime: AnimeMedia,
        episodeNumber: Int,
        audioType: String,
        positionMs: Long,
        durationMs: Long
    ) {
        if (positionMs <= 1000L) return

        prefs(context).edit()
            .putLong("$KEY_EPISODE_POS_PREFIX${anime.id}_$episodeNumber", positionMs)
            .apply()

        val historyList = getWatchHistory(context).toMutableList()
        historyList.removeAll { it.anime.id == anime.id }

        historyList.add(
            0,
            WatchHistoryItem(
                anime = anime,
                episodeNumber = episodeNumber,
                audioType = audioType,
                positionMs = positionMs,
                durationMs = durationMs.coerceAtLeast(1L),
                updatedAt = System.currentTimeMillis()
            )
        )

        val trimmed = historyList.take(20)
        val arr = JSONArray()
        trimmed.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("anime", animeToJson(item.anime))
                    put("episodeNumber", item.episodeNumber)
                    put("audioType", item.audioType)
                    put("positionMs", item.positionMs)
                    put("durationMs", item.durationMs)
                    put("updatedAt", item.updatedAt)
                }
            )
        }
        prefs(context).edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun getWatchHistory(context: Context): List<WatchHistoryItem> {
        val raw = prefs(context).getString(KEY_HISTORY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<WatchHistoryItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                WatchHistoryItem(
                    anime = jsonToAnime(obj.getJSONObject("anime")),
                    episodeNumber = obj.optInt("episodeNumber", 1),
                    audioType = obj.optString("audioType", "SUB"),
                    positionMs = obj.optLong("positionMs", 0L),
                    durationMs = obj.optLong("durationMs", 1L),
                    updatedAt = obj.optLong("updatedAt", 0L)
                )
            )
        }
        return list
    }

    fun removeFromWatchHistory(context: Context, animeId: Int) {
        val updated = getWatchHistory(context).filterNot { it.anime.id == animeId }
        val arr = JSONArray()
        updated.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("anime", animeToJson(item.anime))
                    put("episodeNumber", item.episodeNumber)
                    put("audioType", item.audioType)
                    put("positionMs", item.positionMs)
                    put("durationMs", item.durationMs)
                    put("updatedAt", item.updatedAt)
                }
            )
        }
        prefs(context).edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun getOfflineEpisodes(): List<OfflineEpisodeItem> {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "AniLab"
        )
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val files = dir.listFiles { file ->
            file.isFile && file.name.endsWith(".mp4", ignoreCase = true)
        }?.sortedByDescending { it.lastModified() } ?: return emptyList()

        return files.map { file ->
            val rawName = file.nameWithoutExtension
            val parts = rawName.split("_")
            val audio = parts.lastOrNull()?.takeIf { it == "SUB" || it == "DUB" } ?: "SUB"
            val epPart = parts.findLast { it.startsWith("Ep", ignoreCase = true) }
            val epNum = epPart?.removePrefix("Ep")?.removePrefix("ep")?.toIntOrNull() ?: 1
            val cleanTitle = rawName
                .substringBeforeLast("_Ep")
                .replace("_", " ")
                .ifBlank { rawName }

            val sizeInMb = String.format("%.1f MB", file.length().toDouble() / (1024.0 * 1024.0))

            OfflineEpisodeItem(
                fileName = file.name,
                displayTitle = cleanTitle,
                episodeNumber = epNum,
                audioType = audio,
                fileUri = Uri.fromFile(file).toString(),
                sizeMb = sizeInMb,
                filePath = file.absolutePath
            )
        }
    }

    fun deleteOfflineEpisode(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            if (file.exists()) file.delete() else false
        } catch (e: Exception) {
            false
        }
    }

    fun formatTimeMs(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0L)
        val mins = totalSec / 60
        val secs = totalSec % 60
        return String.format("%02d:%02d", mins, secs)
    }

    private fun animeToJson(anime: AnimeMedia): JSONObject {
        return JSONObject().apply {
            put("id", anime.id)
            put("title", anime.title)
            put("romajiTitle", anime.romajiTitle)
            put("coverImage", anime.coverImage)
            put("bannerImage", anime.bannerImage)
            put("episodes", anime.episodes)
            put("score", anime.score)
            put("description", anime.description)
        }
    }

    private fun jsonToAnime(obj: JSONObject): AnimeMedia {
        return AnimeMedia(
            id = obj.optInt("id"),
            title = obj.optString("title"),
            romajiTitle = obj.optString("romajiTitle"),
            coverImage = obj.optString("coverImage"),
            bannerImage = obj.optString("bannerImage"),
            episodes = obj.optInt("episodes", 12),
            score = obj.optInt("score", 80),
            description = obj.optString("description")
        )
    }
}
