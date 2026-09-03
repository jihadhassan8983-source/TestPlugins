@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import java.net.URLEncoder

class AnimeLokProvider : MainAPI() {
    override var mainUrl = "https://animelok.live"
    override var name = "AnimeLok"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val ua =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private fun htmlH() = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to "$mainUrl/"
    )

    private fun jsonH() = mapOf(
        "User-Agent" to ua,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    private fun unescape(s: String) =
        s.replace("\\/", "/").replace("\\\"", "\"").replace("\\n", "\n")

    override val mainPage = mainPageOf(
        "$mainUrl/home" to "Home",
        "$mainUrl/trending" to "Trending",
        "$mainUrl/latest-episode" to "Latest Episodes",
        "$mainUrl/most-watched" to "Most Watched"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
        return try {
            val html = app.get(request.data, headers = htmlH()).text
            newHomePageResponse(request.name, parseCards(html), false)
        } catch (_: Exception) {
            newHomePageResponse(request.name, emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseCards(app.get("$mainUrl/search?q=$q", headers = htmlH()).text)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseCards(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html, mainUrl)
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        for (a in doc.select("a[href*=/anime/]")) {
            var href = a.attr("abs:href")
            if (href.isBlank()) href = a.attr("href")
            val slug = href.substringAfter("/anime/").substringBefore("/").substringBefore("?").trim()
            if (slug.isBlank() || slug == "cover" || !seen.add(slug)) continue

            val img = a.selectFirst("img")
            var title = img?.attr("alt")?.trim().orEmpty()
            if (title.isBlank() || title.equals("Animelok", true)) title = a.attr("title").trim()
            if (title.isBlank()) title = a.text().trim()
            if (title.isBlank()) title = slugToTitle(slug)
            if (title.length < 2) continue

            var poster = img?.attr("abs:src") ?: img?.attr("src") ?: img?.attr("data-src")
            val tail = slug.substringAfterLast("-")
            if (tail.all { it.isDigit() } && tail.length >= 3) {
                if (poster.isNullOrBlank() || poster.contains("logo", true)) {
                    poster = "https://img.anili.st/media/$tail"
                }
            }

            out.add(newAnimeSearchResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
                this.posterUrl = poster
            })
        }
        return out.distinctBy { it.url }
    }

    private fun slugToTitle(slug: String): String {
        var base = slug
        val dash = base.lastIndexOf('-')
        if (dash > 0 && base.substring(dash + 1).all { it.isDigit() }) {
            base = base.substring(0, dash)
        }
        return base.replace("-", " ").split(" ").joinToString(" ") {
            it.replaceFirstChar { c -> c.uppercaseChar() }
        }
    }

    private suspend fun resolveSlug(urlOrSlug: String): String {
        var slug = urlOrSlug
        if (slug.contains("/anime/")) slug = slug.substringAfterLast("/anime/")
        if (slug.contains("/watch/")) slug = slug.substringAfterLast("/watch/")
        slug = slug.substringBefore("/").substringBefore("?").trim()
        if (slug.isBlank()) return urlOrSlug.trim()
        if (slug.contains("-") && slug.any { it.isDigit() }) return slug
        try {
            val html = app.get("$mainUrl/anime/$slug", headers = htmlH()).text
            Regex("\"slug\"\\s*:\\s*\"([a-z0-9-]+-[0-9]+)\"").find(html)?.let {
                return it.groupValues[1]
            }
            Regex("/anime/([a-z0-9-]+-[0-9]+)").find(html)?.let {
                return it.groupValues[1]
            }
        } catch (_: Exception) {
        }
        return slug
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = resolveSlug(url)
        val ep1raw = app.get("$mainUrl/api/anime/$slug/episodes/1", headers = jsonH()).text
        if (!ep1raw.contains("\"anime\"")) {
            throw ErrorLoadingException("API failed for $slug")
        }
        val ep1 = unescape(ep1raw)

        val title = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"").find(ep1)?.groupValues?.get(1)
            ?: slugToTitle(slug)
        val anilistId = Regex("\"anilistId\"\\s*:\\s*([0-9]+)")
            .find(ep1)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        var poster: String? = if (anilistId > 0) "https://img.anili.st/media/$anilistId" else null
        if (poster.isNullOrBlank()) {
            poster = Regex("\"img\"\\s*:\\s*\"(https?://[^\"]+)\"").find(ep1)?.groupValues?.get(1)
        }

        val ep1Langs = Regex("\"languages\"\\s*:\\s*\\[([^\\]]+)\\]")
            .find(ep1)?.groupValues?.get(1)?.replace("\"", "")?.trim()

        val epNums = LinkedHashSet<Int>()
        epNums.add(1)
        try {
            var page = 1
            var totalPages = 1
            while (page <= totalPages && page <= 60) {
                val range = app.get(
                    "$mainUrl/api/anime/$slug/episodes-range?page=$page&lang=ALL&pageSize=50",
                    headers = jsonH()
                ).text
                Regex("\"totalPages\"\\s*:\\s*([0-9]+)").find(range)?.groupValues?.get(1)
                    ?.toIntOrNull()?.let { totalPages = it }
                Regex("\"number\"\\s*:\\s*([0-9]+)").findAll(range).forEach { m ->
                    m.groupValues[1].toIntOrNull()?.let { if (it > 0) epNums.add(it) }
                }
                page++
            }
        } catch (_: Exception) {
        }
        if (epNums.size <= 1) {
            var n = 2
            var miss = 0
            while (n <= 50 && miss < 2) {
                try {
                    val t = app.get("$mainUrl/api/anime/$slug/episodes/$n", headers = jsonH()).text
                    if (t.contains("\"number\"")) {
                        epNums.add(n)
                        miss = 0
                    } else miss++
                } catch (_: Exception) {
                    miss++
                }
                n++
            }
        } else {
            val max = epNums.maxOrNull() ?: 1
            if (max <= 200) for (i in 1..max) epNums.add(i)
        }

        val plot = buildString {
            append("Episodes: ${epNums.size}")
            if (!ep1Langs.isNullOrBlank()) append("\nAudio: $ep1Langs")
        }

        val episodes = epNums.sorted().map { num ->
            newEpisode("$slug|$num|$anilistId") {
                this.name = "Episode $num"
                this.episode = num
                this.posterUrl = poster
            }
        }

        return newAnimeLoadResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = listOfNotNull(ep1Langs?.takeIf { it.isNotBlank() })
            addEpisodes(DubStatus.Subbed, episodes)
            addEpisodes(DubStatus.Dubbed, episodes)
        }
    }

    private fun qualityOf(q: String): Int {
        val t = q.lowercase()
        return when {
            "1080" in t -> Qualities.P1080.value
            "720" in t -> Qualities.P720.value
            "480" in t -> Qualities.P480.value
            "360" in t -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun push(
        callback: (ExtractorLink) -> Unit,
        label: String,
        url: String,
        referer: String,
        quality: Int,
        added: HashSet<String>
    ): Boolean {
        val u = unescape(url).trim()
        if (!u.startsWith("http")) return false
        if (!added.add("$label|$u")) return false
        callback.invoke(
            ExtractorLink(name, label, u, referer, quality, u.contains(".m3u8"))
        )
        return true
    }

    private data class Srv(
        val name: String,
        val tip: String,
        val langs: String,
        val url: String
    )

    /**
     * FIXED: do NOT regex-cut servers array (pahe URL contains "]" and broke parsing).
     * Walk the JSON with bracket depth instead.
     */
    private fun extractServersJson(text: String): String {
        val key = "\"servers\""
        val keyIdx = text.indexOf(key)
        if (keyIdx < 0) return text
        val start = text.indexOf('[', keyIdx)
        if (start < 0) return text
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until text.length) {
            val c = text[i]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
                continue
            }
            when (c) {
                '"' -> inStr = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return text.substring(start)
    }

    private fun parseServers(text: String): List<Srv> {
        val out = ArrayList<Srv>()
        val arr = extractServersJson(text)
        // Each object starts after {"id" or just find url blocks
        var i = 0
        while (i < arr.length) {
            val objStart = arr.indexOf('{', i)
            if (objStart < 0) break
            var depth = 0
            var inStr = false
            var esc = false
            var objEnd = -1
            for (j in objStart until arr.length) {
                val c = arr[j]
                if (inStr) {
                    if (esc) esc = false
                    else if (c == '\\') esc = true
                    else if (c == '"') inStr = false
                    continue
                }
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            objEnd = j
                            break
                        }
                    }
                }
            }
            if (objEnd < 0) break
            val obj = arr.substring(objStart, objEnd + 1)
            i = objEnd + 1

            val url = Regex("\"url\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                .find(obj)?.groupValues?.get(1) ?: continue
            val name = Regex("\"name\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                .find(obj)?.groupValues?.get(1) ?: ""
            val tip = Regex("\"tip\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                .find(obj)?.groupValues?.get(1) ?: ""
            val langs = Regex("\"languages\"\\s*:\\s*(\\[[^\\]]*])")
                .find(obj)?.groupValues?.get(1) ?: "[]"

            out.add(Srv(unescape(name), unescape(tip), langs, unescape(url)))
        }
        return out
    }

    private fun labelOf(s: Srv): String {
        val n = s.name.trim()
        val t = s.tip.trim()
        val l = s.langs.replace("\"", "").replace("[", "").replace("]", "")
        val known = listOf(
            "Hindi", "Tamil", "Telugu", "Malayalam", "Kannada",
            "Bengali", "English", "Japanese"
        )
        for (k in known) {
            if (n.equals(k, true)) {
                return if (t.isNotBlank() && !t.equals(k, true)) "$k · $t" else k
            }
        }
        if (t.contains("Multi", true) || n.contains("Multi", true)) {
            return if (l.isNotBlank()) "Multi · $l" else "Multi Audio"
        }
        if (t.contains("Hard", true) || n.equals("pahe", true)) return "Hard Sub · Pahe"
        if (t.contains("Soft", true)) return "Soft Sub · ${n.ifBlank { "Bato" }}"
        if (t.equals("Dub", true)) return "Dub · ${l.ifBlank { "English" }}"
        return n.ifBlank { t.ifBlank { "Server" } }
    }

    private fun langPriority(label: String): Int {
        val l = label.lowercase()
        return when {
            "zephyr" in l || "multi" in l -> 0
            "hindi" in l -> 1
            "tamil" in l -> 2
            "telugu" in l -> 3
            "soft sub" in l -> 4
            "dub" in l && "anistream" !in l -> 5
            "pahe" in l || "hard" in l -> 6
            "anistream" in l -> 9
            else -> 7
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        val slug = parts.getOrNull(0)?.trim().orEmpty()
        val epNum = parts.getOrNull(1)?.toIntOrNull() ?: 1
        val anilistFromData = parts.getOrNull(2)?.toIntOrNull() ?: 0
        if (slug.isBlank()) return false

        val raw = try {
            app.get("$mainUrl/api/anime/$slug/episodes/$epNum", headers = jsonH()).text
        } catch (_: Exception) {
            return false
        }
        if (raw.isBlank()) return false
        val text = unescape(raw)

        var found = false
        val added = HashSet<String>()
        val servers = parseServers(text).sortedBy { langPriority(labelOf(it)) }

        for (s in servers) {
            val label = labelOf(s)
            val sUrl = s.url

            // Pahe quality array
            if (sUrl.trimStart().startsWith("[")) {
                val inner = sUrl
                Regex(
                    "\"url\"\\s*:\\s*\"(https?://[^\"]+\\.m3u8[^\"]*)\"\\s*,\\s*\"quality\"\\s*:\\s*\"([^\"]+)\""
                ).findAll(inner).forEach { q ->
                    if (push(
                            callback, "$label ${q.groupValues[2]}", q.groupValues[1],
                            mainUrl, qualityOf(q.groupValues[2]), added
                        )
                    ) found = true
                }
                continue
            }

            if (sUrl.contains("as-cdn", true)) continue

            // Zephyrflick Multi
            if (sUrl.contains("zephyrflick", true)) {
                try {
                    if (extractZephyrflick(sUrl, label, callback, added, subtitleCallback)) {
                        found = true
                        continue
                    }
                } catch (_: Exception) {
                }
            }

            // Direct m3u8 (bato Soft Sub / Dub)
            if (sUrl.contains(".m3u8")) {
                if (push(callback, label, sUrl, mainUrl, Qualities.Unknown.value, added)) {
                    found = true
                }
                continue
            }

            // Hindi / Tamil / Telugu — short.icu / Abyss
            if (sUrl.contains("short.icu") || label.contains("Hindi", true) ||
                label.contains("Tamil", true) || label.contains("Telugu", true) ||
                label.contains("Malayalam", true) || label.contains("Kannada", true)
            ) {
                if (extractAbyssStyle(sUrl, label, callback, added, subtitleCallback)) {
                    found = true
                    continue
                }
            }

            // Generic loadExtractor
            try {
                if (loadExtractor(sUrl, mainUrl, subtitleCallback, callback)) {
                    found = true
                    continue
                }
            } catch (_: Exception) {
            }

            try {
                val body = unescape(app.get(sUrl, headers = htmlH()).text)
                Regex("https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*").findAll(body).forEach { mm ->
                    if (push(callback, label, mm.value, sUrl, Qualities.Unknown.value, added)) {
                        found = true
                    }
                }
            } catch (_: Exception) {
            }
        }

        // AniStream backup
        val anilistId = Regex("\"anilistId\"\\s*:\\s*([0-9]+)")
            .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: anilistFromData
        if (anilistId > 0) {
            for (type in listOf("sub", "dub")) {
                try {
                    if (megaPlay(anilistId, epNum, type, callback, added)) found = true
                } catch (_: Exception) {
                }
            }
        }

        return found
    }

    /** Abyss / short.icu language servers */
    private suspend fun extractAbyssStyle(
        url: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
        added: HashSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val code = url.substringAfterLast("/").substringAfter("v=").substringBefore("&").trim()
        if (code.isBlank()) return false

        val candidates = listOf(
            url,
            "https://short.icu/$code",
            "https://short.icu/e/$code",
            "https://abysscdn.com/?v=$code",
            "https://abyss.to/?v=$code",
            "https://abyss.to/e/$code"
        )

        for (u in candidates) {
            try {
                if (loadExtractor(u, mainUrl, subtitleCallback, callback)) return true
            } catch (_: Exception) {
            }
        }

        // Manual page scrape
        for (u in candidates) {
            try {
                val body = unescape(app.get(u, headers = htmlH()).text)
                Regex("https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*").findAll(body).forEach { m ->
                    push(callback, label, m.value, u, Qualities.Unknown.value, added)
                }
                Regex("\"file\"\\s*:\\s*\"(https?://[^\"]+)\"").findAll(body).forEach { m ->
                    push(callback, label, m.groupValues[1], u, Qualities.Unknown.value, added)
                }
                if (added.any { it.startsWith(label) }) return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    /**
     * Zephyrflick: POST /player/index.php?data=ID&do=getVideo → videoSource
     */
    private suspend fun extractZephyrflick(
        playerUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
        added: HashSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val videoId = Regex("/video/([a-f0-9]+)", RegexOption.IGNORE_CASE)
            .find(playerUrl)?.groupValues?.getOrNull(1)
            ?: return false

        val bases = listOf("https://play.zephyrflick.top", "https://zephyrflick.top")
        var ok = false

        for (base in bases) {
            val pageRef = "$base/video/$videoId"
            val apiWithQuery = "$base/player/index.php?data=$videoId&do=getVideo"
            val apiPlain = "$base/player/index.php"

            val headerMap = mapOf(
                "User-Agent" to ua,
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to pageRef,
                "Origin" to base,
                "Accept" to "application/json, text/plain, */*"
            )

            var body = ""
            // Method A: POST with query string (matches official extractor)
            try {
                body = app.post(apiWithQuery, headers = headerMap).text
            } catch (_: Exception) {
            }
            // Method B: POST form fields
            if (body.isBlank() || !body.contains("videoSource")) {
                try {
                    body = app.post(
                        apiPlain,
                        headers = headerMap + mapOf(
                            "Content-Type" to "application/x-www-form-urlencoded"
                        ),
                        data = mapOf("data" to videoId, "do" to "getVideo")
                    ).text
                } catch (_: Exception) {
                }
            }
            // Method C: GET
            if (body.isBlank() || !body.contains("videoSource")) {
                try {
                    body = app.get(apiWithQuery, headers = headerMap).text
                } catch (_: Exception) {
                }
            }

            if (body.isBlank()) continue
            val json = unescape(body)

            var videoSource = Regex("\"videoSource\"\\s*:\\s*\"([^\"]+)\"")
                .find(json)?.groupValues?.getOrNull(1)
            if (videoSource.isNullOrBlank()) {
                videoSource = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"")
                    .find(json)?.groupValues?.getOrNull(1)
            }
            if (videoSource.isNullOrBlank()) {
                videoSource = Regex("https?://[^\\s\"']+\\.m3u8[^\\s\"']*").find(json)?.value
            }
            if (videoSource.isNullOrBlank()) continue
            videoSource = unescape(videoSource)

            if (push(callback, "Zephyrflick · $label", videoSource, base, Qualities.Unknown.value, added)) {
                ok = true
            }

            // Per-language AUDIO from master playlist
            try {
                val master = unescape(
                    app.get(videoSource, headers = mapOf("User-Agent" to ua, "Referer" to base)).text
                )
                Regex(
                    """#EXT-X-MEDIA:TYPE=AUDIO[^\n]*NAME="([^"]+)"[^\n]*URI="([^"]+)""""
                ).findAll(master).forEach { m ->
                    val langName = m.groupValues[1]
                    var uri = m.groupValues[2]
                    if (uri.startsWith("//")) uri = "https:$uri"
                    else if (uri.startsWith("/")) {
                        val origin = Regex("https?://[^/]+").find(videoSource)?.value ?: base
                        uri = origin + uri
                    } else if (!uri.startsWith("http")) {
                        uri = videoSource.substringBeforeLast("/") + "/" + uri
                    }
                    push(callback, "Zephyrflick · $langName", uri, base, Qualities.Unknown.value, added)
                }
            } catch (_: Exception) {
            }

            if (ok) break
        }

        if (!ok) {
            try {
                if (loadExtractor(playerUrl, mainUrl, subtitleCallback, callback)) ok = true
            } catch (_: Exception) {
            }
        }
        return ok
    }

    private suspend fun megaPlay(
        anilistId: Int,
        epNum: Int,
        type: String,
        callback: (ExtractorLink) -> Unit,
        added: HashSet<String>
    ): Boolean {
        val pageUrl = "https://megaplay.buzz/stream/ani/$anilistId/$epNum/$type"
        val html = try {
            app.get(pageUrl, headers = mapOf("User-Agent" to ua, "Referer" to "$mainUrl/")).text
        } catch (_: Exception) {
            return false
        }
        val playerId = Regex("data-id=\"([0-9]+)\"").find(html)?.groupValues?.getOrNull(1)
            ?: return false
        val srcRaw = try {
            app.get(
                "https://megaplay.buzz/stream/getSources?id=$playerId",
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to pageUrl,
                    "Origin" to "https://megaplay.buzz",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json"
                )
            ).text
        } catch (_: Exception) {
            return false
        }
        val file = Regex("\"file\"\\s*:\\s*\"(https?://[^\"]+)\"")
            .find(unescape(srcRaw))?.groupValues?.getOrNull(1) ?: return false
        val label = if (type == "sub") {
            "Japanese · Soft Sub (AniStream)"
        } else {
            "English · Dub (AniStream)"
        }
        return push(callback, label, file, "https://megaplay.buzz/", Qualities.Unknown.value, added)
    }
}
