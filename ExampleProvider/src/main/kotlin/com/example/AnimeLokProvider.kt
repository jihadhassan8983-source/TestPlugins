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

    private fun htmlH(): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to "$mainUrl/"
    )

    private fun jsonH(): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    /** CRITICAL: API/MegaPlay returns https:\/\/ — must fix before any URL regex */
    private fun unescape(s: String): String {
        return s.replace("\\/", "/").replace("\\\"", "\"").replace("\\n", "\n")
    }

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
            val html = app.get("$mainUrl/search?q=$q", headers = htmlH()).text
            parseCards(html)
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
            if (slug.isBlank() || slug == "cover") continue
            if (!seen.add(slug)) continue

            val img = a.selectFirst("img")
            var title = img?.attr("alt")?.trim().orEmpty()
            if (title.isBlank() || title.equals("Animelok", true)) title = a.attr("title").trim()
            if (title.isBlank()) title = a.text().trim()
            if (title.isBlank()) title = slugToTitle(slug)
            if (title.length < 2) continue

            var poster = img?.attr("abs:src")
            if (poster.isNullOrBlank()) poster = img?.attr("src")
            if (poster.isNullOrBlank()) poster = img?.attr("data-src")

            out.add(
                newAnimeSearchResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
                    this.posterUrl = poster
                }
            )
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

        val ep1 = app.get("$mainUrl/api/anime/$slug/episodes/1", headers = jsonH()).text
        if (!ep1.contains("\"anime\"")) {
            throw ErrorLoadingException("API failed for $slug")
        }
        val ep1u = unescape(ep1)

        val title = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"").find(ep1u)?.groupValues?.get(1)
            ?: slugToTitle(slug)

        val anilistId = Regex("\"anilistId\"\\s*:\\s*([0-9]+)")
            .find(ep1u)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        var poster = Regex("\"img\"\\s*:\\s*\"(https?://[^\"]+)\"").find(ep1u)?.groupValues?.get(1)
        var plot = Regex("\"description\"\\s*:\\s*\"([^\"]*)\"").find(ep1u)?.groupValues?.get(1)

        // Details page for better poster / plot
        try {
            val page = app.get("$mainUrl/anime/$slug", headers = htmlH()).text
            if (poster.isNullOrBlank()) {
                poster = Regex("og:image\"\\s+content=\"([^\"]+)\"").find(page)?.groupValues?.get(1)
            }
            if (plot.isNullOrBlank()) {
                plot = Regex("og:description\"\\s+content=\"([^\"]+)\"").find(page)?.groupValues?.get(1)
            }
            if (plot.isNullOrBlank()) {
                val doc = Jsoup.parse(page)
                plot = doc.selectFirst("meta[name=description]")?.attr("content")
                    ?: doc.selectFirst("p")?.text()
            }
        } catch (_: Exception) {
        }

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

        val episodes = epNums.sorted().map { num ->
            newEpisode("$slug|$num|$anilistId") {
                this.name = "Episode $num"
                this.episode = num
            }
        }

        return newAnimeLoadResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            addEpisodes(DubStatus.Subbed, episodes)
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
        if (!added.add(u)) return false
        callback.invoke(
            ExtractorLink(
                name,
                label,
                u,
                referer,
                quality,
                u.contains(".m3u8")
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

        // ALWAYS unescape first
        val text = unescape(raw)

        var found = false
        val added = HashSet<String>()

        // 1) Pahe: {"url":"https://...m3u8","quality":"720p"}
        Regex(
            "\"url\"\\s*:\\s*\"(https?://[^\"]+\\.m3u8[^\"]*)\"\\s*,\\s*\"quality\"\\s*:\\s*\"([^\"]+)\""
        ).findAll(text).forEach { m ->
            if (push(callback, "Pahe ${m.groupValues[2]}", m.groupValues[1], mainUrl, qualityOf(m.groupValues[2]), added)) {
                found = true
            }
        }
        Regex(
            "\"quality\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"url\"\\s*:\\s*\"(https?://[^\"]+\\.m3u8[^\"]*)\""
        ).findAll(text).forEach { m ->
            if (push(callback, "Pahe ${m.groupValues[1]}", m.groupValues[2], mainUrl, qualityOf(m.groupValues[1]), added)) {
                found = true
            }
        }

        // 2) Any other m3u8 in API body
        Regex("https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*").findAll(text).forEach { m ->
            if (push(callback, "Stream", m.value, mainUrl, Qualities.Unknown.value, added)) {
                found = true
            }
        }

        // 3) Named servers with plain URL (skip JSON arrays & dead as-cdn)
        Regex(
            "\"name\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]{0,220}?\"url\"\\s*:\\s*\"(https?://[^\"]+)\""
        ).findAll(text).forEach { m ->
            val sName = m.groupValues[1]
            val sUrl = m.groupValues[2]
            if (sUrl.startsWith("[")) return@forEach
            if (sUrl.contains("as-cdn", true)) return@forEach
            if (sUrl.contains(".m3u8")) {
                if (push(callback, sName, sUrl, mainUrl, Qualities.Unknown.value, added)) found = true
                return@forEach
            }
            try {
                if (loadExtractor(sUrl, mainUrl, subtitleCallback, callback)) found = true
            } catch (_: Exception) {
            }
        }

        // 4) MegaPlay — works for almost every AniList title (Bleach etc.)
        val anilistId = Regex("\"anilistId\"\\s*:\\s*([0-9]+)")
            .find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: anilistFromData

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

    private suspend fun megaPlay(
        anilistId: Int,
        epNum: Int,
        type: String,
        callback: (ExtractorLink) -> Unit,
        added: HashSet<String>
    ): Boolean {
        val pageUrl = "https://megaplay.buzz/stream/ani/$anilistId/$epNum/$type"
        val html = try {
            app.get(
                pageUrl,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to "$mainUrl/",
                    "Accept" to "text/html,*/*"
                )
            ).text
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
                    "Accept" to "application/json, text/plain, */*"
                )
            ).text
        } catch (_: Exception) {
            return false
        }

        // FIX: unescape before reading file URL
        val src = unescape(srcRaw)
        val file = Regex("\"file\"\\s*:\\s*\"(https?://[^\"]+)\"")
            .find(src)?.groupValues?.getOrNull(1)
            ?: return false

        return push(
            callback,
            "AniStream ${type.uppercase()}",
            file,
            "https://megaplay.buzz/",
            Qualities.Unknown.value,
            added
        )
    }
}
