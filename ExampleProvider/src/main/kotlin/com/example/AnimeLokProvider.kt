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

    private fun htmlHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to ua,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to (mainUrl + "/")
        )
    }

    private fun jsonHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to ua,
            "Accept" to "application/json, text/plain, */*",
            "Referer" to (mainUrl + "/"),
            "Origin" to mainUrl
        )
    }

    override val mainPage = mainPageOf(
        (mainUrl + "/home") to "Home",
        (mainUrl + "/latest-episode") to "Latest Episodes",
        (mainUrl + "/trending") to "Trending",
        (mainUrl + "/most-watched") to "Most Watched"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) {
            return newHomePageResponse(request.name, emptyList(), false)
        }
        return try {
            val html = app.get(request.data, headers = htmlHeaders()).text
            newHomePageResponse(request.name, parseCards(html), false)
        } catch (_: Exception) {
            newHomePageResponse(request.name, emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            val html = app.get(mainUrl + "/search?q=" + q, headers = htmlHeaders()).text
            parseCards(html)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseCards(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html, mainUrl)
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        for (a in doc.select("a[href*=/anime/], a[href*=/watch/]")) {
            var href = a.attr("abs:href")
            if (href.isBlank()) href = a.attr("href")
            if (href.isBlank()) continue

            var slug = ""
            if (href.contains("/anime/")) {
                slug = href.substringAfter("/anime/").substringBefore("/").substringBefore("?")
            } else if (href.contains("/watch/")) {
                slug = href.substringAfter("/watch/").substringBefore("/").substringBefore("?")
            }
            if (slug.isBlank() || slug == "cover") continue
            if (!seen.add(slug)) continue

            var title = ""
            val img = a.selectFirst("img")
            if (img != null) title = img.attr("alt").trim()
            if (title.isBlank() || title.equals("Animelok", true) || title.contains("logo", true)) {
                title = a.attr("title").trim()
            }
            if (title.isBlank()) title = a.text().trim()
            if (title.isBlank() || title.equals("Home", true)) title = slugToTitle(slug)
            if (title.length < 2) continue

            var poster: String? = null
            if (img != null) {
                poster = img.attr("abs:src")
                if (poster.isNullOrBlank()) poster = img.attr("src")
                if (poster.isNullOrBlank()) poster = img.attr("data-src")
            }

            out.add(
                newAnimeSearchResponse(title, mainUrl + "/anime/" + slug, TvType.Anime) {
                    this.posterUrl = poster
                }
            )
        }
        return out.distinctBy { it.url }
    }

    private fun slugToTitle(slug: String): String {
        var base = slug
        val dash = base.lastIndexOf('-')
        if (dash > 0) {
            val tail = base.substring(dash + 1)
            if (tail.isNotEmpty() && tail.all { it.isDigit() }) {
                base = base.substring(0, dash)
            }
        }
        base = base.replace("-", " ").trim()
        if (base.isBlank()) return slug
        return base.split(" ").joinToString(" ") { w ->
            if (w.isEmpty()) w else w.replaceFirstChar { it.uppercaseChar() }
        }
    }

    private suspend fun resolveSlug(urlOrSlug: String): String {
        var slug = urlOrSlug
        if (slug.contains("/anime/")) slug = slug.substringAfterLast("/anime/")
        if (slug.contains("/watch/")) slug = slug.substringAfterLast("/watch/")
        slug = slug.substringBefore("/").substringBefore("?").trim()
        if (slug.isBlank()) slug = urlOrSlug.trim()

        if (slug.contains("-") && slug.any { it.isDigit() }) {
            return slug
        }

        try {
            val html = app.get(mainUrl + "/anime/" + slug, headers = htmlHeaders()).text
            val m1 = Regex("\"slug\"\\s*:\\s*\"([a-z0-9-]+-[0-9]+)\"").find(html)
            if (m1 != null) return m1.groupValues[1]
            for (m in Regex("/anime/([a-z0-9]+(?:-[a-z0-9]+)+-[0-9]+)").findAll(html)) {
                val s = m.groupValues[1]
                if (s != slug && s != "cover") return s
            }
        } catch (_: Exception) {
        }
        return slug
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = resolveSlug(url)
        val ep1Text = app.get(
            mainUrl + "/api/anime/" + slug + "/episodes/1",
            headers = jsonHeaders()
        ).text

        if (!ep1Text.contains("\"anime\"")) {
            throw ErrorLoadingException("Anime API failed: " + slug)
        }

        val title = Regex("\"title\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            .find(ep1Text)?.groupValues?.get(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\/", "/")
            ?: slugToTitle(slug)

        val anilistId = Regex("\"anilistId\"\\s*:\\s*([0-9]+)")
            .find(ep1Text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        var poster = Regex("\"img\"\\s*:\\s*\"(https?://[^\"]+)\"")
            .find(ep1Text)?.groupValues?.get(1)
            ?.replace("\\/", "/")

        if (poster.isNullOrBlank()) {
            try {
                val pageHtml = app.get(mainUrl + "/anime/" + slug, headers = htmlHeaders()).text
                poster = Regex("og:image\"\\s+content=\"([^\"]+)\"").find(pageHtml)?.groupValues?.get(1)
            } catch (_: Exception) {
            }
        }

        val epNums = LinkedHashSet<Int>()
        epNums.add(1)

        try {
            var page = 1
            var totalPages = 1
            while (page <= totalPages && page <= 80) {
                val rangeText = app.get(
                    mainUrl + "/api/anime/" + slug +
                        "/episodes-range?page=" + page + "&lang=ALL&pageSize=50",
                    headers = jsonHeaders()
                ).text
                val tp = Regex("\"totalPages\"\\s*:\\s*([0-9]+)")
                    .find(rangeText)?.groupValues?.get(1)?.toIntOrNull()
                if (tp != null) totalPages = tp
                Regex("\"number\"\\s*:\\s*([0-9]+)").findAll(rangeText).forEach { m ->
                    val n = m.groupValues[1].toIntOrNull()
                    if (n != null && n > 0) epNums.add(n)
                }
                page++
            }
        } catch (_: Exception) {
        }

        if (epNums.size <= 1) {
            var n = 2
            var miss = 0
            while (n <= 40 && miss < 2) {
                try {
                    val t = app.get(
                        mainUrl + "/api/anime/" + slug + "/episodes/" + n,
                        headers = jsonHeaders()
                    ).text
                    if (t.contains("\"number\"")) {
                        epNums.add(n)
                        miss = 0
                    } else {
                        miss++
                    }
                } catch (_: Exception) {
                    miss++
                }
                n++
            }
        } else {
            val max = epNums.maxOrNull() ?: 1
            if (max <= 150) {
                for (i in 1..max) epNums.add(i)
            }
        }

        val episodes = epNums.sorted().map { num ->
            newEpisode(slug + "|" + num + "|" + anilistId) {
                this.name = "Episode " + num
                this.episode = num
            }
        }

        return newAnimeLoadResponse(title, mainUrl + "/anime/" + slug, TvType.Anime) {
            this.posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private fun qualityFrom(label: String): Int {
        val t = label.lowercase()
        return when {
            t.contains("1080") -> Qualities.P1080.value
            t.contains("720") -> Qualities.P720.value
            t.contains("480") -> Qualities.P480.value
            t.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun addM3u8(
        callback: (ExtractorLink) -> Unit,
        label: String,
        url: String,
        referer: String,
        quality: Int,
        added: HashSet<String>
    ): Boolean {
        val u = url.replace("\\/", "/").trim()
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
        // data: slug|ep|anilistId
        val parts = data.split("|")
        if (parts.isEmpty()) return false

        val slug = parts[0].trim()
        val epNum = parts.getOrNull(1)?.toIntOrNull() ?: 1
        val anilistId = parts.getOrNull(2)?.toIntOrNull() ?: 0
        if (slug.isBlank()) return false

        val epText = try {
            app.get(
                mainUrl + "/api/anime/" + slug + "/episodes/" + epNum,
                headers = jsonHeaders()
            ).text
        } catch (_: Exception) {
            return false
        }
        if (epText.isBlank() || !epText.contains("\"episode\"")) return false

        var found = false
        val added = HashSet<String>()

        // Normalize escapes so m3u8 is easy to find
        val normalized = epText
            .replace("\\/", "/")
            .replace("\\\"", "\"")

        // ---------- 1) Pahe style: "url":"[{...m3u8...}]" ----------
        // Find quality-tagged entries
        Regex(
            "\"url\"\\s*:\\s*\"(https?://[^\"]+\\.m3u8[^\"]*)\"\\s*,\\s*\"quality\"\\s*:\\s*\"([^\"]+)\""
        ).findAll(normalized).forEach { m ->
            val link = m.groupValues[1]
            val q = m.groupValues[2]
            if (addM3u8(callback, "Pahe " + q, link, mainUrl, qualityFrom(q), added)) {
                found = true
            }
        }
        // quality before url
        Regex(
            "\"quality\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"url\"\\s*:\\s*\"(https?://[^\"]+\\.m3u8[^\"]*)\""
        ).findAll(normalized).forEach { m ->
            val q = m.groupValues[1]
            val link = m.groupValues[2]
            if (addM3u8(callback, "Pahe " + q, link, mainUrl, qualityFrom(q), added)) {
                found = true
            }
        }

        // ---------- 2) Any m3u8 in response ----------
        Regex("https?://[^\\s\"'\\\\<>]+\\.m3u8[^\\s\"'\\\\<>]*").findAll(normalized).forEach { m ->
            val link = m.value
            if (addM3u8(callback, "HLS", link, mainUrl, Qualities.Unknown.value, added)) {
                found = true
            }
        }

        // ---------- 3) Server objects with plain http url (not JSON array) ----------
        Regex(
            "\"name\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]{0,200}?\"url\"\\s*:\\s*\"(https?://[^\"]+)\""
        ).findAll(epText).forEach { m ->
            val sName = m.groupValues[1]
            var sUrl = m.groupValues[2].replace("\\/", "/")
            if (sUrl.startsWith("[")) return@forEach // pahe array already handled
            if (sUrl.contains(".m3u8")) {
                if (addM3u8(callback, sName, sUrl, mainUrl, Qualities.Unknown.value, added)) {
                    found = true
                }
                return@forEach
            }
            // Dead / broken hosts skip quickly
            if (sUrl.contains("as-cdn", true)) return@forEach

            try {
                if (loadExtractor(sUrl, mainUrl, subtitleCallback, callback)) {
                    found = true
                    return@forEach
                }
            } catch (_: Exception) {
            }

            try {
                val body = app.get(sUrl, headers = htmlHeaders()).text
                Regex("https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*").findAll(body).forEach { mm ->
                    if (addM3u8(callback, sName, mm.value, sUrl, Qualities.Unknown.value, added)) {
                        found = true
                    }
                }
            } catch (_: Exception) {
            }
        }

        // ---------- 4) MegaPlay / AniStream backup (works when only ToonStream on site) ----------
        val realAnilist = Regex("\"anilistId\"\\s*:\\s*([0-9]+)")
            .find(epText)?.groupValues?.get(1)?.toIntOrNull() ?: anilistId

        if (realAnilist > 0) {
            for (type in listOf("sub", "dub")) {
                try {
                    if (extractMegaPlay(realAnilist, epNum, type, callback, added)) {
                        found = true
                    }
                } catch (_: Exception) {
                }
            }
        }

        return found
    }

    private suspend fun extractMegaPlay(
        anilistId: Int,
        epNum: Int,
        type: String,
        callback: (ExtractorLink) -> Unit,
        added: HashSet<String>
    ): Boolean {
        val streamPage =
            "https://megaplay.buzz/stream/ani/" + anilistId + "/" + epNum + "/" + type

        val mpHtml = try {
            app.get(
                streamPage,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to (mainUrl + "/"),
                    "Accept" to "text/html,*/*"
                )
            ).text
        } catch (_: Exception) {
            return false
        }

        // data-id="178154"
        val playerId = Regex("data-id=\"([0-9]+)\"")
            .find(mpHtml)?.groupValues?.getOrNull(1)
            ?: Regex("data-id='([0-9]+)'").find(mpHtml)?.groupValues?.getOrNull(1)
            ?: return false

        val sourcesJson = try {
            app.get(
                "https://megaplay.buzz/stream/getSources?id=" + playerId,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to streamPage,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/plain, */*",
                    "Origin" to "https://megaplay.buzz"
                )
            ).text
        } catch (_: Exception) {
            return false
        }

        val file = Regex("\"file\"\\s*:\\s*\"(https?://[^\"]+)\"")
            .find(sourcesJson)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace("\\/", "/")
            ?: return false

        if (!added.add(file)) return true

        callback.invoke(
            ExtractorLink(
                name,
                "AniStream " + type.uppercase(),
                file,
                "https://megaplay.buzz/",
                Qualities.Unknown.value,
                file.contains(".m3u8")
            )
        )

        // Subtitles
        Regex(
            "\"file\"\\s*:\\s*\"(https?://[^\"]+\\.vtt)\"\\s*,\\s*\"label\"\\s*:\\s*\"([^\"]+)\""
        ).findAll(sourcesJson.replace("\\/", "/")).forEach { m ->
            try {
                // subtitleCallback not passed here — skip safe
            } catch (_: Exception) {
            }
        }

        return true
    }
}
