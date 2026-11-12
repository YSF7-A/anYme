package com.example.anilabclone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

data class AnimeMedia(
    val id: Int,
    val title: String,
    val romajiTitle: String,
    val coverImage: String,
    val bannerImage: String,
    val episodes: Int,
    val score: Int,
    val description: String
)

data class ScheduleItem(
    val id: Int,
    val title: String,
    val coverImage: String,
    val episode: Int,
    val airingAtSeconds: Long,
    val dayName: String,
    val formattedTime: String
)

data class StreamLink(
    val serverName: String,
    val url: String,
    val isM3u8: Boolean,
    val quality: String,
    val audioType: String,
    val referer: String? = null
)

object AnimeRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private const val ANILIST_URL = "https://graphql.anilist.co"

    private const val ALLANIME_API = "https://api.allanime.day/api"
    private const val ALLANIME_BASE = "https://allanime.day"
    private const val ALLANIME_REFERER = "https://allmanga.to"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    suspend fun fetchAnimeList(searchQuery: String? = null): List<AnimeMedia> = withContext(Dispatchers.IO) {
        val gql = if (searchQuery.isNullOrBlank()) {
            """
            query {
              Page(page: 1, perPage: 24) {
                media(type: ANIME, sort: TRENDING_DESC) {
                  id
                  title { english romaji }
                  coverImage { large }
                  bannerImage
                  episodes
                  averageScore
                  description(asHtml: false)
                }
              }
            }
            """.trimIndent()
        } else {
            """
            query (${'$'}search: String) {
              Page(page: 1, perPage: 24) {
                media(type: ANIME, search: ${'$'}search, sort: POPULARITY_DESC) {
                  id
                  title { english romaji }
                  coverImage { large }
                  bannerImage
                  episodes
                  averageScore
                  description(asHtml: false)
                }
              }
            }
            """.trimIndent()
        }

        val payload = JSONObject().apply {
            put("query", gql)
            if (!searchQuery.isNullOrBlank()) {
                put("variables", JSONObject().put("search", searchQuery))
            }
        }

        val request = Request.Builder()
            .url(ANILIST_URL)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        val list = mutableListOf<AnimeMedia>()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@withContext emptyList()
                val mediaArray = JSONObject(body)
                    .optJSONObject("data")
                    ?.optJSONObject("Page")
                    ?.optJSONArray("media") ?: return@withContext emptyList()

                for (i in 0 until mediaArray.length()) {
                    val item = mediaArray.getJSONObject(i)
                    val titleObj = item.getJSONObject("title")
                    val eng = titleObj.optString("english")
                    val romaji = titleObj.optString("romaji")
                    val displayName = eng.ifBlank { romaji }

                    list.add(
                        AnimeMedia(
                            id = item.getInt("id"),
                            title = displayName,
                            romajiTitle = romaji.ifBlank { displayName },
                            coverImage = item.getJSONObject("coverImage").optString("large"),
                            bannerImage = item.optString("bannerImage"),
                            episodes = item.optInt("episodes", 12).let { if (it <= 0) 24 else it },
                            score = item.optInt("averageScore", 80),
                            description = item.optString("description", "No synopsis available.")
                                .replace(Regex("<.*?>"), "")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list
    }

    suspend fun fetchWeeklySchedule(): List<ScheduleItem> = withContext(Dispatchers.IO) {
        val nowSec = System.currentTimeMillis() / 1000
        val nextWeekSec = nowSec + (7 * 24 * 3600)

        val gql = """
            query (${'$'}start: Int, ${'$'}end: Int) {
              Page(page: 1, perPage: 50) {
                airingSchedules(airingAt_greater: ${'$'}start, airingAt_lesser: ${'$'}end, sort: TIME) {
                  episode
                  airingAt
                  media {
                    id
                    title { english romaji }
                    coverImage { large }
                  }
                }
              }
            }
        """.trimIndent()

        val payload = JSONObject().apply {
            put("query", gql)
            put("variables", JSONObject().put("start", nowSec).put("end", nextWeekSec))
        }

        val request = Request.Builder()
            .url(ANILIST_URL)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        val schedules = mutableListOf<ScheduleItem>()
        val dayFormat = SimpleDateFormat("EEEE", Locale.ENGLISH)
        val timeFormat = SimpleDateFormat("hh:mm a", Locale.ENGLISH)

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@withContext emptyList()
                val arr = JSONObject(body)
                    .optJSONObject("data")
                    ?.optJSONObject("Page")
                    ?.optJSONArray("airingSchedules") ?: return@withContext emptyList()

                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val airingAt = obj.getLong("airingAt")
                    val date = Date(airingAt * 1000L)
                    val media = obj.getJSONObject("media")
                    val titleObj = media.getJSONObject("title")
                    val title = titleObj.optString("english").ifBlank { titleObj.optString("romaji") }

                    schedules.add(
                        ScheduleItem(
                            id = media.getInt("id"),
                            title = title,
                            coverImage = media.getJSONObject("coverImage").optString("large"),
                            episode = obj.getInt("episode"),
                            airingAtSeconds = airingAt,
                            dayName = dayFormat.format(date),
                            formattedTime = timeFormat.format(date)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        schedules
    }

    suspend fun resolveEpisodeStreams(
        animeTitle: String,
        romajiTitle: String,
        episodeNumber: Int,
        audioType: String
    ): List<StreamLink> = withContext(Dispatchers.IO) {
        val mode = if (audioType.equals("DUB", ignoreCase = true)) "dub" else "sub"
        val extractedStreams = mutableListOf<StreamLink>()

        try {
            val showId = findAllAnimeShowId(animeTitle, mode)
                ?: findAllAnimeShowId(romajiTitle, mode)

            if (showId != null) {
                val encryptedSources = fetchEpisodeSourceUrls(showId, episodeNumber.toString(), mode)

                for ((serverName, rawSource) in encryptedSources) {
                    val decodedPath = if (rawSource.startsWith("--")) {
                        decryptAllAnimeHex(rawSource.removePrefix("--"))
                    } else {
                        rawSource
                    }

                    if (decodedPath.contains("/apivtwo/clock")) {
                        val clockUrl = ALLANIME_BASE + decodedPath.replace("/apivtwo/clock?", "/apivtwo/clock.json?")
                        val links = extractLinksFromClockJson(clockUrl, serverName, audioType)
                        extractedStreams.addAll(links)
                    } else if (decodedPath.startsWith("http") && (decodedPath.contains(".m3u8") || decodedPath.contains(".mp4"))) {
                        extractedStreams.add(
                            StreamLink(
                                serverName = serverName,
                                url = decodedPath,
                                isM3u8 = decodedPath.contains(".m3u8"),
                                quality = if (decodedPath.contains(".m3u8")) "Auto HLS" else "Direct MP4",
                                audioType = audioType,
                                referer = ALLANIME_REFERER
                            )
                        )
                    }

                    if (extractedStreams.size >= 6) break
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        extractedStreams
    }

    private fun findAllAnimeShowId(queryTitle: String, mode: String): String? {
        val searchGql = """
            query(${'$'}search: SearchInput, ${'$'}limit: Int, ${'$'}page: Int, ${'$'}translationType: VaildTranslationTypeEnumType) {
              shows(search: ${'$'}search, limit: ${'$'}limit, page: ${'$'}page, translationType: ${'$'}translationType) {
                edges { _id name englishName }
              }
            }
        """.trimIndent()

        val variables = JSONObject().apply {
            put("search", JSONObject().apply {
                put("allowAdult", false)
                put("allowUnknown", false)
                put("query", queryTitle)
            })
            put("limit", 10)
            put("page", 1)
            put("translationType", mode)
        }

        val url = ALLANIME_API.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("variables", variables.toString())
            ?.addQueryParameter("query", searchGql)
            ?.build() ?: return null

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", ALLANIME_REFERER)
            .build()

        client.newCall(request).execute().use { res ->
            val body = res.body?.string() ?: return null
            val edges = JSONObject(body)
                .optJSONObject("data")
                ?.optJSONObject("shows")
                ?.optJSONArray("edges") ?: return null

            if (edges.length() == 0) return null
            return edges.getJSONObject(0).optString("_id").takeIf { it.isNotBlank() }
        }
    }

    private fun fetchEpisodeSourceUrls(showId: String, epNum: String, mode: String): List<Pair<String, String>> {
        val epGql = """
            query(${'$'}showId: String!, ${'$'}translationType: VaildTranslationTypeEnumType!, ${'$'}episodeString: String!) {
              episode(showId: ${'$'}showId, translationType: ${'$'}translationType, episodeString: ${'$'}episodeString) {
                episodeString
                sourceUrls
              }
            }
        """.trimIndent()

        val variables = JSONObject().apply {
            put("showId", showId)
            put("translationType", mode)
            put("episodeString", epNum)
        }

        val url = ALLANIME_API.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("variables", variables.toString())
            ?.addQueryParameter("query", epGql)
            ?.build() ?: return emptyList()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", ALLANIME_REFERER)
            .build()

        val priorityList = mutableListOf<Pair<String, String>>()
        val fallbackList = mutableListOf<Pair<String, String>>()
        val preferredServers = listOf("Default", "S-mp4", "Luf-mp4", "Yt-mp4", "Sak", "Kir")

        client.newCall(request).execute().use { res ->
            val body = res.body?.string() ?: return emptyList()
            val sourceArray: JSONArray = JSONObject(body)
                .optJSONObject("data")
                ?.optJSONObject("episode")
                ?.optJSONArray("sourceUrls") ?: return emptyList()

            for (i in 0 until sourceArray.length()) {
                val srcObj = sourceArray.getJSONObject(i)
                val srcUrl = srcObj.optString("sourceUrl")
                val srcName = srcObj.optString("sourceName", "Server-${i + 1}")

                if (srcUrl.isNotBlank()) {
                    if (srcName in preferredServers) {
                        priorityList.add(srcName to srcUrl)
                    } else {
                        fallbackList.add(srcName to srcUrl)
                    }
                }
            }
        }
        return priorityList + fallbackList
    }

    private fun decryptAllAnimeHex(hexStr: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < hexStr.length) {
            val hexPair = hexStr.substring(i, i + 2)
            val byteVal = hexPair.toIntOrNull(16)
            if (byteVal != null) {
                sb.append((byteVal xor 56).toChar())
            }
            i += 2
        }
        return sb.toString()
    }

    private fun extractLinksFromClockJson(
        clockUrl: String,
        serverName: String,
        audioType: String
    ): List<StreamLink> {
        val links = mutableListOf<StreamLink>()
        val request = Request.Builder()
            .url(clockUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", ALLANIME_REFERER)
            .build()

        client.newCall(request).execute().use { res ->
            if (!res.isSuccessful) return emptyList()
            val body = res.body?.string() ?: return emptyList()
            val jsonArray = JSONObject(body).optJSONArray("links") ?: return emptyList()

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val rawLink = item.optString("link")
                if (rawLink.isBlank()) continue

                val isHls = item.optBoolean("hls", rawLink.contains(".m3u8"))
                val resStr = item.optString("resolutionStr").ifBlank {
                    if (isHls) "Auto HLS" else "1080p MP4"
                }
                val customReferer = item.optJSONObject("headers")
                    ?.optString("Referer")
                    ?.ifBlank { ALLANIME_REFERER } ?: ALLANIME_REFERER

                links.add(
                    StreamLink(
                        serverName = serverName,
                        url = rawLink,
                        isM3u8 = isHls,
                        quality = resStr,
                        audioType = audioType,
                        referer = customReferer
                    )
                )
            }
        }
        return links
    }
}
