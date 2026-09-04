@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONArray
import org.json.JSONObject

class FlixmetProvider : MainAPI() {
    override var mainUrl = "https://flixmet.net"
    override var name = "Flixmet"
    override val hasMainPage = true
    override var lang = "bn"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val playerBase = "https://flixplayer.top"

    private val ua =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private fun hdr(referer: String = mainUrl + "/"): Map<String, String> {
        return mapOf(
            "User-Agent" to ua,
            "Accept" to "application/json,text/plain,*/*",
            "Accept-Language" to "en-US,en;q=0.9,bn;q=0.8",
            "Referer" to referer
        )
    }

    private fun cleanTitle(raw: String): String {
        var t = raw
            .replace(Regex("""(?i)\s*Movie Download.*$"""), "")
            .replace(Regex("""(?i)\s*Download.*$"""), "")
            .replace(Regex("""(?i)\s*[|\-–].*$"""), "")
            .replace(Regex("""(?i)\s*\((?:WEB-?DL|BluRay|HDTC|HDRip)[^)]*\)"""), "")
            .replace(Regex("""(?i)\s*(480p|720p|1080p|2160p|4K|GDrive|&?\s*Watch).*$"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
        if (t.length < 3) t = raw.substringBefore("Download").trim()
        return t.ifBlank { raw.trim() }
    }

    private fun qualityFrom(text: String): Int {
        val t = text.lowercase()
        return when {
            "2160" in t || "4k" in t -> Qualities.P1080.value
            "1080" in t -> Qualities.P1080.value
            "720" in t -> Qualities.P720.value
            "480" in t -> Qualities.P480.value
            "360" in t -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun yearFrom(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("""\((19|20)\d{2}\)""").find(text)?.value?.trim('(', ')')?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(text)?.value?.toIntOrNull()
    }

    private fun isSeries(item: FlixItem): Boolean {
        val t = (item.type ?: "").lowercase()
        if (t == "show" || t == "series") return true
        val title = item.title ?: ""
        return title.contains("Season", true) || title.contains("Series", true)
    }

    data class FlixItem(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("poster_url") val posterUrl: String? = null,
        @JsonProperty("backdrop_url") val backdropUrl: String? = null,
        @JsonProperty("embed_code") val embedCode: String? = null,
        @JsonProperty("category") val category: String? = null,
        @JsonProperty("genre") val genre: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("year") val year: Any? = null,
        @JsonProperty("runtime") val runtime: Any? = null,
        @JsonProperty("duration") val duration: Any? = null,
        @JsonProperty("rating") val rating: Any? = null,
        @JsonProperty("tmdb_rating") val tmdbRating: Any? = null,
        @JsonProperty("actors") val actors: String? = null,
        @JsonProperty("directors") val directors: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("imdb_id") val imdbId: String? = null
    )

    private fun toNumYear(v: Any?): Int? {
        return when (v) {
            is Int -> v
            is Double -> v.toInt()
            is String -> v.toIntOrNull() ?: yearFrom(v)
            else -> null
        }
    }

    private fun toRuntimeMin(v: Any?): Int? {
        return when (v) {
            is Int -> if (v > 0) v else null
            is Double -> v.toInt().takeIf { it > 0 }
            is String -> {
                val n = v.toIntOrNull()
                if (n != null && n > 0) n
                else Regex("""(\d+)""").find(v)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
            else -> null
        }
    }

    private fun toRatingInt(v: Any?): Int? {
        val f = when (v) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        } ?: return null
        if (f <= 0) return null
        return if (f <= 10.0) (f * 1000).toInt() else f.toInt().coerceIn(0, 10000)
    }

    private fun stripHtml(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val t = raw
            .replace(Regex("""(?i)<br\s*/?>"""), "\n")
            .replace(Regex("""<[^>]+>"""), " ")
            .replace("&nbsp;", " ")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return t.ifBlank { null }
    }

    private fun itemToSearch(item: FlixItem): SearchResponse? {
        val id = item.id ?: return null
        val titleRaw = item.title ?: return null
        val title = cleanTitle(titleRaw)
        val url = mainUrl + "/api/movies/" + id
        val poster = item.posterUrl
        val year = toNumYear(item.year) ?: yearFrom(titleRaw) ?: yearFrom(item.releaseDate)
        return if (isSeries(item)) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    private suspend fun fetchMovies(): List<FlixItem> {
        val json = app.get(mainUrl + "/api/movies", headers = hdr()).text
        return parseJson<ArrayList<FlixItem>>(json)
    }

    override val mainPage = mainPageOf(
        "all" to "Latest",
        "movie" to "Movies",
        "hybrid" to "Series Packs",
        "Bangla Movie" to "Bangla Movie",
        "Hindi Dubbed" to "Hindi Dubbed",
        "Dual Audio" to "Dual Audio",
        "Hollywood" to "Hollywood",
        "Action" to "Action"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val all = fetchMovies()
        val key = request.data
        val filtered = when (key) {
            "all" -> all
            "movie" -> all.filter { (it.type ?: "movie") == "movie" }
            "hybrid" -> all.filter { (it.type ?: "") == "hybrid" || (it.type ?: "") == "show" }
            else -> all.filter {
                (it.genre ?: "").contains(key, true) ||
                    (it.category ?: "").contains(key, true) ||
                    (it.title ?: "").contains(key, true)
            }
        }
        val pageSize = 24
        val slice = filtered.drop((page - 1) * pageSize).take(pageSize)
        val list = slice.mapNotNull { itemToSearch(it) }
        return newHomePageResponse(request.name, list, list.size >= pageSize)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val all = fetchMovies()
        val ql = q.lowercase()
        return all.filter {
            (it.title ?: "").lowercase().contains(ql) ||
                (it.actors ?: "").lowercase().contains(ql)
        }.mapNotNull { itemToSearch(it) }.take(40)
    }

    private fun parseEmbedUuids(embedCode: String?): List<Pair<String, String>> {
        if (embedCode.isNullOrBlank() || embedCode.length < 5) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        try {
            val arr = JSONArray(embedCode)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val label = o.optString("label").ifBlank { "Server " + (i + 1) }
                val code = o.optString("code")
                if (code.isBlank()) continue
                val m = Regex(
                    """flixplayer\.top/embed/([0-9a-fA-F-]{16,})""",
                    RegexOption.IGNORE_CASE
                ).find(code)
                val uuid = m?.groupValues?.getOrNull(1) ?: continue
                out.add(label to uuid)
            }
        } catch (_: Exception) {
            val m = Regex(
                """flixplayer\.top/embed/([0-9a-fA-F-]{16,})""",
                RegexOption.IGNORE_CASE
            ).findAll(embedCode)
            m.forEachIndexed { i, match ->
                out.add("Server " + (i + 1) to match.groupValues[1])
            }
        }
        return out
    }

    private fun buildActors(item: FlixItem): List<ActorData> {
        val out = ArrayList<ActorData>()
        item.directors?.split(",")?.map { it.trim() }?.filter { it.length > 1 }?.forEach {
            out += ActorData(Actor(it), roleString = "Director")
        }
        item.actors?.split(",")?.map { it.trim() }?.filter { it.length > 1 }?.take(16)?.forEach {
            out += ActorData(Actor(it), roleString = "Actor")
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse {
        val id = Regex("""/api/movies/(\d+)""").find(url)?.groupValues?.getOrNull(1)
            ?: url.trimEnd('/').substringAfterLast("/")
        val json = app.get(mainUrl + "/api/movies/" + id, headers = hdr()).text
        val item = parseJson<FlixItem>(json)

        val titleRaw = item.title ?: "Unknown"
        val title = cleanTitle(titleRaw)
        val plot = stripHtml(item.description)
        val poster = item.posterUrl
        val background = item.backdropUrl ?: poster
        val year = toNumYear(item.year) ?: yearFrom(titleRaw) ?: yearFrom(item.releaseDate)
        val runtime = toRuntimeMin(item.runtime) ?: toRuntimeMin(item.duration)
        val rating = toRatingInt(item.tmdbRating) ?: toRatingInt(item.rating)
        val tags = (item.genre ?: "").split(",", "|").map { it.trim() }
            .filter { it.length in 2..40 }.distinct().take(12)
        val actors = buildActors(item)
        val dataUrl = mainUrl + "/api/movies/" + (item.id ?: id)

        if (isSeries(item)) {
            val episodes = listOf(
                newEpisode(dataUrl) {
                    this.name = title
                    this.episode = 1
                    this.data = dataUrl
                }
            )
            return newTvSeriesLoadResponse(title, dataUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = runtime
                this.rating = rating
                this.actors = actors.ifEmpty { null }
            }
        }

        return newMovieLoadResponse(title, dataUrl, TvType.Movie, dataUrl) {
            this.posterUrl = poster
            this.backgroundPosterUrl = background
            this.plot = plot
            this.year = year
            this.tags = tags
            this.duration = runtime
            this.rating = rating
            this.actors = actors.ifEmpty { null }
        }
    }

    private suspend fun resolvePlayer(
        uuid: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedJson = app.get(
            playerBase + "/api/videos/embed/" + uuid,
            headers = hdr(playerBase + "/embed/" + uuid)
        ).text
        val root = JSONObject(embedJson)
        val video = root.optJSONObject("video") ?: return false
        val sourceUrl = video.optString("source_url")
        if (sourceUrl.isBlank()) return false

        val workers = root.optJSONArray("workers")
        var workerId = 3
        if (workers != null && workers.length() > 0) {
            workerId = workers.optJSONObject(0)?.optInt("id") ?: workerId
        }

        val signedResp = app.post(
            playerBase + "/api/videos/stream-sign",
            headers = hdr(playerBase + "/embed/" + uuid) + mapOf(
                "Content-Type" to "application/json",
                "Origin" to playerBase
            ),
            json = mapOf(
                "videoUrl" to sourceUrl,
                "workerId" to workerId
            )
        ).text

        var playUrl = sourceUrl
        try {
            val signed = JSONObject(signedResp)
            val u = signed.optString("url")
            if (u.startsWith("http")) playUrl = u
        } catch (_: Exception) {
        }

        if (!playUrl.startsWith("http")) return false

        val q = qualityFrom(label + " " + video.optString("title"))
        val isM3u8 = playUrl.contains(".m3u8")
        callback(
            ExtractorLink(
                name,
                label.ifBlank { "Flixmet" },
                playUrl,
                playerBase + "/",
                q,
                isM3u8
            )
        )
        return true
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex("""/api/movies/(\d+)""").find(data)?.groupValues?.getOrNull(1)
            ?: data.trimEnd('/').substringAfterLast("/")
        val json = app.get(mainUrl + "/api/movies/" + id, headers = hdr()).text
        val item = parseJson<FlixItem>(json)
        val embeds = parseEmbedUuids(item.embedCode)
        if (embeds.isEmpty()) return false

        var found = false
        embeds.take(3).apmap { pair ->
            val label = pair.first
            val uuid = pair.second
            try {
                if (resolvePlayer(uuid, label, callback)) {
                    found = true
                }
            } catch (_: Exception) {
            }
        }
        return found
    }
}
