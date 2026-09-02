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
        (mainUrl + "/most-watched") to "Most Watched",
        (mainUrl + "/az-list") to "A-Z List"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) {
            return newHomePageResponse(request.name, emptyList(), false)
        }
        val html = app.get(request.data, headers = htmlHeaders()).text
        return newHomePageResponse(request.name, parseCards(html), true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val html = app.get(mainUrl + "/search?q=" + q, headers = htmlHeaders()).text
        return parseCards(html)
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

    private fun isHashSlug(slug: String): Boolean {
        if (slug.length < 12) return false
        return slug.all { it in '0'..'9' || it in 'a'..'f' }
    }

    private suspend fun resolveSlug(urlOrSlug: String): String {
        var slug = urlOrSlug
        if (slug.contains("/anime/")) slug = slug.substringAfterLast("/anime/")
        if (slug.contains("/watch/")) slug = slug.substringAfterLast("/watch/")
        slug = slug.substringBefore("/").substringBefore("?").trim()
        if (slug.isBlank()) slug = urlOrSlug.trim()

        if (slug.contains("-") && slug.any { it.isDigit() } && !isHashSlug(slug)) {
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

        val poster = Regex("\"img\"\\s*:\\s*\"(https?://[^\"]+)\"")
            .find(ep1Text)?.groupValues?.get(1)
            ?.replace("\\/", "/")

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
            while (n <= 30 && miss < 2) {
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
            if (max <= 120) {
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data format: slug|ep|anilistId
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

        if (epText.isBlank()) return false

        var found = false
        val added = HashSet<String>()

        // 1) Direct m3u8 anywhere in JSON (anvod Soft Sub / Dub)
        val reM3u8 = Regex("https?://[^\\s\"'\\\\]+\\.m3u8[^\\s\"'\\\\]*")
        reM3u8.findAll(epText).forEach { m ->
            val url = m.value.replace("\\/", "/")
            if (!added.add(url)) return@forEach
            callback.invoke(
                ExtractorLink(
                    name,
                    "M3U8",
                    url,
                    mainUrl,
                    Qualities.Unknown.value,
                    true
                )
            )
            found = true
        }

        // 2) All "url":"https://..." fields (skip if already added)
        val reUrl = Regex("\"url\"\\s*:\\s*\"(https?://[^\"]+)\"")
        var idx = 0
        reUrl.findAll(epText).forEach { m ->
            idx++
            var url = m.groupValues[1].replace("\\/", "/")
            if (url.contains(".m3u8")) {
                // already handled above
                return@forEach
            }
            if (!added.add(url)) return@forEach

            val label = "Server " + idx
            try {
                // loadExtractor for short.icu etc
                try {
                    if (loadExtractor(url, mainUrl, subtitleCallback, callback)) {
                        found = true
                        return@forEach
                    }
                } catch (_: Exception) {
                }

                // open page, find m3u8
                val body = app.get(url, headers = htmlHeaders()).text
                reM3u8.findAll(body).forEach { mm ->
                    val u2 = mm.value.replace("\\/", "/")
                    if (!added.add(u2)) return@forEach
                    callback.invoke(
                        ExtractorLink(
                            name,
                            label,
                            u2,
                            url,
                            Qualities.Unknown.value,
                            true
                        )
                    )
                    found = true
                }
            } catch (_: Exception) {
            }
        }

        // 3) MegaPlay backup
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
        val mpHtml = app.get(
            streamPage,
            headers = mapOf("User-Agent" to ua, "Referer" to mainUrl)
        ).text

        val playerId = Regex("data-id=\"([0-9]+)\"").find(mpHtml)?.groupValues?.getOrNull(1)
        if (playerId.isNullOrBlank()) return false

        val sourcesJson = app.get(
            "https://megaplay.buzz/stream/getSources?id=" + playerId,
            headers = mapOf(
                "User-Agent" to ua,
                "Referer" to streamPage,
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "application/json"
            )
        ).text

        val file = Regex("\"file\"\\s*:\\s*\"(https?://[^\"]+)\"")
            .find(sourcesJson)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace("\\/", "/")
        if (file.isNullOrBlank()) return false
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
        return true
    }
}
