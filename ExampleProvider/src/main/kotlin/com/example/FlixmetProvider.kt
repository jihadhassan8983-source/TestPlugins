@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Flixmet (flixmet.net) — SPA with public JSON APIs.
 *
 * Catalog:  GET /api/section-movies?sectionId=&page=&limit=
 * Search:   GET /api/movies/search?q=
 * Movie:    GET /api/movies/{id}/consolidated
 * Show:     GET /api/tv-show/{id}/consolidated
 * Season:   GET /api/season/{id}/consolidated
 * Play:     embed iframe → flixplayer.top/embed/{uuid}
 *           GET  https://flixplayer.top/api/videos/embed/{uuid}
 *           POST https://flixplayer.top/api/videos/stream-sign
 *                { videoUrl, workerId } → signed download.aspx (real MKV/MP4)
 */
class FlixmetProvider : MainAPI() {
    override var mainUrl = "https://flixmet.net"
    override var name = "Flixmet"
    override val hasMainPage = true
    override var lang = "bn"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val playerUrl = "https://flixplayer.top"

    private val ua =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to ua,
        "Accept" to "application/json,text/html;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,bn;q=0.8",
        "Referer" to "$mainUrl/"
    )

    private val playerHeaders = mapOf(
        "User-Agent" to ua,
        "Accept" to "application/json,text/html;q=0.9,*/*;q=0.8",
        "Origin" to playerUrl,
        "Referer" to "$playerUrl/"
    )

    override val mainPage = mainPageOf(
        "hero" to "Featured",
        "2" to "Trending",
        "1" to "Latest Upload",
        "3" to "Bangla Movie",
        "15" to "Hindi Dubbed",
        "6" to "Hollywood English",
        "16" to "Bangla Dubbed",
        "5" to "Bollywood Hindi",
        "17" to "TV / Web Series",
        "18" to "Bengali Series",
        "19" to "Hollywood Series",
        "21" to "Animation",
        "20" to "Bangla Natok"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val list = if (request.data == "hero") {
            if (page > 1) emptyList() else loadHero()
        } else {
            loadSection(request.data, page)
        }
        val hasNext = request.data != "hero" && list.size >= 20
        return newHomePageResponse(request.name, list, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8")
        val text = app.get("$mainUrl/api/movies/search?q=$encoded", headers = headers).text
        return parseItemList(text).mapNotNull { it.toSearch() }
    }

    override suspend fun load(url: String): LoadResponse {
        val idType = parseLoadUrl(url)
        val id = idType.first
        val hint = idType.second

        return when (hint) {
            "show" -> loadShow(id, url)
            "season" -> loadSeasonAsSeries(id, url)
            else -> {
                val root = getObject("$mainUrl/api/movies/$id/consolidated")
                val movie = root?.optJSONObject("movie") ?: getObject("$mainUrl/api/movies/$id")
                ?: throw ErrorLoadingException("Title not found")
                when (movie.optString("type").lowercase()) {
                    "show" -> loadShow(movie.optInt("id").toString(), url)
                    "season" -> loadSeasonAsSeries(movie.optInt("id").toString(), url)
                    "episode" -> loadEpisodeAsMovie(movie, url)
                    else -> loadMovie(root, movie, url)
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val item = getObject(data) ?: getObject("$mainUrl/api/movies/${data.trim('/')}") 
            ?: return false

        var found = false
        val seen = HashSet<String>()
        val embeds = asArray(item, "embed_code")

        for (i in 0 until embeds.length()) {
            val server = embeds.optJSONObject(i) ?: continue
            val label = server.optString("label").ifBlank { "Play Server ${i + 1}" }
            val code = server.optString("code")
            if (code.isBlank()) continue
            val uuid = extractPlayerUuid(code) ?: continue
            try {
                if (resolvePlayer(uuid, label, seen, callback)) found = true
            } catch (_: Exception) {
            }
        }

        // Fallback: some items only have download hosters. Do not emit HTML pages as video.
        if (!found) {
            val dls = asArray(item, "download_link")
            for (i in 0 until dls.length()) {
                val row = dls.optJSONObject(i) ?: continue
                val link = row.optString("link")
                if (!looksLikeDirectMedia(link)) continue
                if (!seen.add(link)) continue
                val format = row.optString("format")
                emitLink(callback, "${format.ifBlank { "Download" }}", link, qualityFrom(format))
                found = true
            }
        }
        return found
    }

    // ── catalog ──────────────────────────────────────────────────────────

    private suspend fun loadHero(): List<SearchResponse> {
        val root = getObject("$mainUrl/api/home-consolidated") ?: return emptyList()
        val movies = root.optJSONArray("heroMovies") ?: return emptyList()
        return jsonArrayToList(movies).mapNotNull { it.toSearch() }
    }

    private suspend fun loadSection(sectionId: String, page: Int): List<SearchResponse> {
        val url = "$mainUrl/api/section-movies?sectionId=$sectionId&page=$page&limit=24"
        val root = getObject(url) ?: return emptyList()
        val movies = root.optJSONArray("movies") ?: return emptyList()
        return jsonArrayToList(movies).mapNotNull { it.toSearch() }
    }

    private fun JSONObject.toSearch(): SearchResponse? {
        val id = intVal("id") ?: return null
        val type = optString("type").lowercase()
        if (type == "banner") return null
        val title = displayTitle()
        if (title.length < 2) return null
        val poster = absUrl(optString("poster_url"))
        val href = when (type) {
            "show" -> "$mainUrl/show/$id"
            "season" -> "$mainUrl/season/$id"
            else -> "$mainUrl/movie/$id"
        }
        return if (type == "show" || type == "season" || isSeriesTitle(title, optString("genre"))) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    // ── load: movie / show / season ──────────────────────────────────────

    private suspend fun loadMovie(root: JSONObject?, movie: JSONObject, url: String): LoadResponse {
        val id = movie.intVal("id") ?: throw ErrorLoadingException("Missing id")
        val title = movie.displayTitle()
        val poster = absUrl(movie.optString("poster_url"))
        val backdrop = absUrl(movie.optString("backdrop_url")) ?: poster
        val data = "$mainUrl/api/movies/$id"
        return newMovieLoadResponse(title, url, TvType.Movie, data) {
            applyMeta(movie, root, poster, backdrop)
        }
    }

    private suspend fun loadEpisodeAsMovie(movie: JSONObject, url: String): LoadResponse {
        val id = movie.intVal("id") ?: throw ErrorLoadingException("Missing id")
        return newMovieLoadResponse(movie.displayTitle(), url, TvType.Movie, "$mainUrl/api/movies/$id") {
            applyMeta(movie, null, absUrl(movie.optString("poster_url")), absUrl(movie.optString("backdrop_url")))
        }
    }

    private suspend fun loadShow(id: String, url: String): LoadResponse {
        val root = getObject("$mainUrl/api/tv-show/$id/consolidated")
            ?: throw ErrorLoadingException("Show not found")
        val show = root.optJSONObject("show") ?: throw ErrorLoadingException("Show payload missing")
        val title = show.displayTitle()
        val poster = absUrl(show.optString("poster_url"))
        val backdrop = absUrl(show.optString("backdrop_url")) ?: poster
        val episodes = ArrayList<Episode>()
        val seasons = root.optJSONArray("seasons") ?: JSONArray()
        for (s in 0 until seasons.length()) {
            val season = seasons.optJSONObject(s) ?: continue
            val seasonId = season.intVal("id") ?: continue
            val seasonNum = season.intVal("season_number") ?: (s + 1)
            val seasonRoot = getObject("$mainUrl/api/season/$seasonId/consolidated")
            val epArr = seasonRoot?.optJSONArray("episodes")
                ?: getArray("$mainUrl/api/episodes-by-season/$seasonId")
                ?: JSONArray()
            for (e in 0 until epArr.length()) {
                val ep = epArr.optJSONObject(e) ?: continue
                val epId = ep.intVal("id") ?: continue
                val epNum = ep.intVal("episode_number") ?: (e + 1)
                val epName = ep.optString("title").ifBlank { "Episode $epNum" }
                episodes += newEpisode("$mainUrl/api/movies/$epId") {
                    this.name = epName
                    this.episode = epNum
                    this.season = seasonNum
                    this.posterUrl = absUrl(ep.optString("poster_url")) ?: poster
                    this.description = stripHtml(ep.optString("description"))
                }
            }
        }
        if (episodes.isEmpty()) {
            // Single-item fallback so the title still opens instead of "no episodes"
            val showId = show.intVal("id") ?: id.toIntOrNull()
            if (showId != null) {
                episodes += newEpisode("$mainUrl/api/movies/$showId") {
                    this.name = title
                    this.episode = 1
                    this.season = 1
                }
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            applyMeta(show, root, poster, backdrop)
        }
    }

    private suspend fun loadSeasonAsSeries(id: String, url: String): LoadResponse {
        val root = getObject("$mainUrl/api/season/$id/consolidated")
            ?: throw ErrorLoadingException("Season not found")
        val season = root.optJSONObject("season") ?: throw ErrorLoadingException("Season payload missing")
        val title = season.displayTitle()
        val poster = absUrl(season.optString("poster_url"))
        val backdrop = absUrl(season.optString("backdrop_url")) ?: poster
        val seasonNum = season.intVal("season_number") ?: 1
        val epArr = root.optJSONArray("episodes") ?: JSONArray()
        val episodes = ArrayList<Episode>()
        for (e in 0 until epArr.length()) {
            val ep = epArr.optJSONObject(e) ?: continue
            val epId = ep.intVal("id") ?: continue
            val epNum = ep.intVal("episode_number") ?: (e + 1)
            episodes += newEpisode("$mainUrl/api/movies/$epId") {
                this.name = ep.optString("title").ifBlank { "Episode $epNum" }
                this.episode = epNum
                this.season = seasonNum
                this.posterUrl = absUrl(ep.optString("poster_url")) ?: poster
                this.description = stripHtml(ep.optString("description"))
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            applyMeta(season, root, poster, backdrop)
        }
    }

    private fun LoadResponse.applyMeta(
        item: JSONObject,
        root: JSONObject?,
        poster: String?,
        backdrop: String?
    ) {
        this.posterUrl = poster
        this.backgroundPosterUrl = backdrop ?: poster
        this.plot = stripHtml(item.optString("description"))
            ?: stripHtml(item.optString("meta_description"))
        this.year = item.intVal("year")
            ?: yearFrom(item.optString("release_date"))
            ?: yearFrom(item.optString("title"))
        this.tags = splitTags(item.optString("genre"))
        item.intVal("runtime")?.let { if (it > 0) this.duration = it }
        this.actors = buildActors(item, root)
        val recs = recommendationsFrom(root)
        if (recs.isNotEmpty()) this.recommendations = recs
    }

    // ── player / links ───────────────────────────────────────────────────

    private suspend fun resolvePlayer(
        uuid: String,
        label: String,
        seen: HashSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embed = getObject("$playerUrl/api/videos/embed/$uuid", playerHeaders) ?: return false
        val video = embed.optJSONObject("video") ?: return false
        val sourceUrl = video.optString("source_url")
        val sourceType = video.optString("source_type").lowercase()
        if (sourceUrl.isBlank()) return false

        val workers = embed.optJSONArray("workers") ?: JSONArray()
        val publicWorkers = ArrayList<JSONObject>()
        for (i in 0 until workers.length()) {
            val w = workers.optJSONObject(i) ?: continue
            val flag = w.opt("is_public")
            val isPublic = flag == true || flag == 1 || flag?.toString() == "1" || flag?.toString() == "true"
            if (isPublic && w.optString("url").isNotBlank()) publicWorkers.add(w)
        }

        val signed = signStream(sourceUrl, publicWorkers)
            ?: signExternal(sourceUrl, publicWorkers)
            ?: sourceUrl.takeIf { sourceType == "r2" || sourceType == "cdn" || looksLikeDirectMedia(it) }

        if (signed.isNullOrBlank() || !isPlayableMedia(signed)) return false
        if (!seen.add(signed)) return false

        val quality = qualityFrom("$label ${video.optString("title")} $sourceUrl")
        emitLink(callback, label.ifBlank { video.optString("title").ifBlank { "FlixPlayer" } }, signed, quality)
        return true
    }

    private suspend fun signStream(sourceUrl: String, workers: List<JSONObject>): String? {
        if (workers.isEmpty()) return null
        val worker = pickWorker(sourceUrl, workers)
        val workerId = worker.intVal("id") ?: return null
        return try {
            val res = app.post(
                "$playerUrl/api/videos/stream-sign",
                headers = playerHeaders + mapOf("Content-Type" to "application/json"),
                json = mapOf("videoUrl" to sourceUrl, "workerId" to workerId)
            ).text
            val url = JSONObject(res).optString("url")
            url.takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun signExternal(sourceUrl: String, workers: List<JSONObject>): String? {
        val fileId = driveFileId(sourceUrl) ?: return null
        for (w in workers) {
            val base = w.optString("url").trimEnd('/')
            val key = w.optString("api_key")
            if (base.isBlank() || key.isBlank()) continue
            try {
                val res = app.post(
                    "$base/api/external-sign",
                    headers = mapOf(
                        "User-Agent" to ua,
                        "Content-Type" to "application/json",
                        "X-API-Key" to key,
                        "Origin" to playerUrl,
                        "Referer" to "$playerUrl/"
                    ),
                    json = mapOf("id" to fileId)
                ).text
                val obj = JSONObject(res)
                val url = obj.optString("url")
                if (obj.optBoolean("ok", url.startsWith("http")) && url.startsWith("http")) return url
            } catch (_: Exception) {
            }
        }
        return null
    }

    private suspend fun emitLink(
        callback: (ExtractorLink) -> Unit,
        label: String,
        url: String,
        quality: Int
    ) {
        val isM3u8 = url.contains(".m3u8", true)
        val isDash = url.contains(".mpd", true)
        val type = when {
            isM3u8 -> ExtractorLinkType.M3U8
            isDash -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
        try {
            callback(
                newExtractorLink(
                    source = name,
                    name = label.take(48),
                    url = url,
                    type = type
                ) {
                    this.referer = playerUrl
                    this.quality = quality
                    this.headers = mapOf(
                        "User-Agent" to ua,
                        "Referer" to "$playerUrl/"
                    )
                }
            )
        } catch (_: Exception) {
            callback(
                ExtractorLink(
                    name,
                    label.take(48),
                    url,
                    playerUrl,
                    quality,
                    isM3u8
                )
            )
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun parseLoadUrl(url: String): Pair<String, String> {
        val clean = url.substringBefore("#").substringBefore("?").trimEnd('/')
        val path = clean.removePrefix(mainUrl).trim('/')
        val parts = path.split('/')
        return when {
            parts.size >= 2 && parts[0] in listOf("movie", "show", "season") ->
                parts[1] to parts[0]
            else -> path to "auto"
        }
    }

    private suspend fun getObject(url: String, hdr: Map<String, String> = headers): JSONObject? {
        return try {
            val text = app.get(url, headers = hdr).text.trim()
            if (text.startsWith("{")) JSONObject(text) else null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun getArray(url: String): JSONArray? {
        return try {
            val text = app.get(url, headers = headers).text.trim()
            when {
                text.startsWith("[") -> JSONArray(text)
                text.startsWith("{") -> {
                    val obj = JSONObject(text)
                    obj.optJSONArray("episodes") ?: obj.optJSONArray("movies") ?: obj.optJSONArray("list")
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseItemList(text: String): List<JSONObject> {
        val trimmed = text.trim()
        return try {
            when {
                trimmed.startsWith("[") -> jsonArrayToList(JSONArray(trimmed))
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    val arr = obj.optJSONArray("movies") ?: obj.optJSONArray("results") ?: obj.optJSONArray("list")
                    if (arr != null) jsonArrayToList(arr) else listOf(obj)
                }
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun jsonArrayToList(arr: JSONArray): List<JSONObject> {
        val out = ArrayList<JSONObject>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { out.add(it) }
        }
        return out
    }

    private fun asArray(obj: JSONObject, key: String): JSONArray {
        val v = obj.opt(key) ?: return JSONArray()
        return when (v) {
            is JSONArray -> v
            is String -> try {
                if (v.isBlank()) JSONArray() else JSONArray(v)
            } catch (_: Exception) {
                JSONArray()
            }
            else -> JSONArray()
        }
    }

    private fun JSONObject.intVal(key: String): Int? {
        val v = opt(key) ?: return null
        return when (v) {
            is Int -> v
            is Number -> v.toInt()
            is String -> v.trim().takeWhile { it.isDigit() }.toIntOrNull()
            else -> null
        }
    }

    private fun JSONObject.displayTitle(): String {
        val original = optString("original_title").trim()
        if (original.length >= 2 && !original.contains("Download", true)) return original
        val custom = optString("custom_name").trim()
        if (custom.length >= 2 && !custom.contains("Download", true)) return custom
        return cleanTitle(optString("title"))
    }

    private fun cleanTitle(raw: String): String {
        var t = raw
        t = t.replace(Regex("""(?i)\s*Movie Download.*$"""), "")
        t = t.replace(Regex("""(?i)\s*Download.*$"""), "")
        t = t.replace(Regex("""(?i)\s*[|\-–]\s*(Season|S\d+|Epi).*$"""), "")
        t = t.replace(Regex("""(?i)\s*\((?:WEB-?DL|BluRay|HDTC|HDRip|HD-?CAM)[^)]*\)"""), "")
        t = t.replace(Regex("""(?i)\s*(480p|720p|1080p|2160p|4K|GDrive|&?\s*Watch).*$"""), "")
        t = t.replace(Regex("""\s{2,}"""), " ").trim(' ', '-', '|', '–')
        return t.ifBlank { raw.trim() }
    }

    private fun isSeriesTitle(title: String, genre: String): Boolean {
        val s = "$title $genre".lowercase()
        return s.contains("season") || s.contains("web series") || s.contains("tv-show") ||
            s.contains("tv show") || s.contains("tv / web") || Regex("""\bs\d+\b""").containsMatchIn(s)
    }

    private fun splitTags(genre: String): List<String> {
        val skip = setOf(
            "latest movie", "movies", "slider latest", "trending", "trending movie",
            "tv movie", "tv-shows", "tv show"
        )
        return genre.split(',', '|')
            .map { it.trim() }
            .filter { it.length in 2..32 && it.lowercase() !in skip }
            .distinct()
            .take(8)
    }

    private fun stripHtml(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val t = raw
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&#39;", "'")
            .replace("&", "&")
            .replace(""", "\"")
            .replace(Regex("\\s+"), " ")
            .trim()
        return t.ifBlank { null }
    }

    private fun yearFrom(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("""(19|20)\d{2}""").find(text)?.value?.toIntOrNull()
    }

    private fun absUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var u = url.trim()
        if (u.startsWith("//")) u = "https:$u"
        if (u.startsWith("/")) u = mainUrl + u
        return u.takeIf { it.startsWith("http") }
    }

    private fun extractPlayerUuid(html: String): String? {
        return Regex(
            """flixplayer\.top/embed/([0-9a-fA-F-]{16,})""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""src=["']([^"']*flixplayer[^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)
                ?.substringAfterLast('/')
                ?.substringBefore('?')
                ?.takeIf { it.length >= 16 }
    }

    private fun driveFileId(url: String): String? {
        return Regex("""/file/d/([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.getOrNull(1)
            ?: Regex("""[?&]id=([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.getOrNull(1)
    }

    private fun pickWorker(sourceUrl: String, workers: List<JSONObject>): JSONObject {
        if (workers.size == 1) return workers[0]
        var hash = 0
        for (ch in sourceUrl) {
            hash = (hash shl 5) - hash + ch.code
        }
        val idx = (hash.toLong() and 0x7fffffffL).toInt() % workers.size
        return workers[idx]
    }

    private fun looksLikeDirectMedia(url: String): Boolean {
        val u = url.lowercase()
        if (!u.startsWith("http")) return false
        return u.contains(".mp4") || u.contains(".mkv") || u.contains(".m3u8") ||
            u.contains(".mpd") || u.contains("download.aspx")
    }

    private fun isPlayableMedia(url: String): Boolean {
        val u = url.lowercase()
        if (!u.startsWith("http")) return false
        if (u.contains("t.me") || u.contains("telegram")) return false
        if (u.contains("/login") || u.contains("accounts.google")) return false
        if (u.contains("megalinked.top") && !u.contains("download.aspx")) return false
        if (u.contains("drive.google.com") && !u.contains("download.aspx")) return false
        return true
    }

    private fun qualityFrom(text: String): Int {
        val t = text.lowercase()
        return when {
            "2160" in t || "4k" in t -> Qualities.P2160.value
            "1080" in t -> Qualities.P1080.value
            "720" in t -> Qualities.P720.value
            "480" in t -> Qualities.P480.value
            "360" in t -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun buildActors(item: JSONObject, root: JSONObject?): List<ActorData> {
        val out = ArrayList<ActorData>()
        val credits = root?.optJSONObject("credits")
        val cast = credits?.optJSONArray("cast")
        if (cast != null) {
            for (i in 0 until minOf(cast.length(), 16)) {
                val a = cast.optJSONObject(i) ?: continue
                val n = a.optString("name").trim()
                if (n.isEmpty()) continue
                var img = a.optString("profile_path")
                if (img.startsWith("/")) img = "https://image.tmdb.org/t/p/w185$img"
                out += ActorData(Actor(n, img.takeIf { it.startsWith("http") }), role = a.optString("character"))
            }
        }
        if (out.isEmpty()) {
            item.optString("actors").split(',').map { it.trim() }.filter { it.length > 1 }.take(12).forEach {
                out += ActorData(Actor(it, null))
            }
        }
        return out
    }

    private fun recommendationsFrom(root: JSONObject?): List<SearchResponse> {
        val related = root?.optJSONObject("related") ?: return emptyList()
        val keys = listOf("cast", "director", "category", "movies")
        val seen = HashSet<Int>()
        val out = ArrayList<SearchResponse>()
        for (k in keys) {
            val arr = related.optJSONArray(k) ?: continue
            for (item in jsonArrayToList(arr)) {
                val id = item.intVal("id") ?: continue
                if (!seen.add(id)) continue
                item.toSearch()?.let { out.add(it) }
                if (out.size >= 12) return out
            }
        }
        return out
    }
}
