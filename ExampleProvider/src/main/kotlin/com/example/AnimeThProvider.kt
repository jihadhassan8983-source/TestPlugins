@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Document
import java.net.URLEncoder

class AnimeThProvider : MainAPI() {
    override var mainUrl = "https://anime-th.com"
    override var name = "AnimeTH"
    override val hasMainPage = true
    override var lang = "th"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val ua =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to ua,
        "Accept" to "*/*",
        "Accept-Language" to "th-TH,th;q=0.9,en;q=0.8",
        "Referer" to (mainUrl + "/")
    )

    private val streamHeaders = mapOf(
        "User-Agent" to ua,
        "Accept" to "*/*",
        "Origin" to "https://anime.tonytonychopper.net",
        "Referer" to "https://anime.tonytonychopper.net/"
    )

    // percent-encoded Thai paths so mobile paste never breaks
    override val mainPage = mainPageOf(
        (mainUrl + "/") to "Home",
        (mainUrl + "/scoretop/") to "Top Score",
        (mainUrl + "/category/%E0%B8%9E%E0%B8%B2%E0%B8%81%E0%B8%A2%E0%B9%8C%E0%B9%84%E0%B8%97%E0%B8%A2/") to "Thai Dub",
        (mainUrl + "/category/%E0%B8%8B%E0%B8%B1%E0%B8%9A%E0%B9%84%E0%B8%97%E0%B8%A2/") to "Thai Sub"
    )

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        val root = base.trimEnd('/')
        return if (root == mainUrl || root == mainUrl + "/") root else root + "/page/" + page + "/"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data == mainUrl + "/" || request.data == mainUrl) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            request.data
        } else {
            pageUrl(request.data, page)
        }
        val doc = app.get(url, headers = headers).document
        return newHomePageResponse(request.name, parseCards(doc), true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val out = ArrayList<SearchResponse>()

        try {
            val json = app.get(
                mainUrl + "/vendor/search-ajax.php?q=" + q,
                headers = headers + mapOf("X-Requested-With" to "XMLHttpRequest")
            ).text
            val re = Regex(
                "\"title\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"slug\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"cover\"\\s*:\\s*\"([^\"]+)\""
            )
            re.findAll(json).forEach { m ->
                val title = m.groupValues[1].replace("\\\"", "\"").replace("\\/", "/")
                val slug = m.groupValues[2].trim('/')
                var cover = m.groupValues[3].replace("\\/", "/")
                if (!cover.startsWith("http")) cover = mainUrl + "/" + cover
                // same URL shape as home cards
                val page = mainUrl + "/anime/" + slug + "/"
                out.add(
                    newAnimeSearchResponse(title, page, TvType.Anime) {
                        this.posterUrl = cover
                    }
                )
            }
        } catch (e: Exception) {
            // fall through
        }

        if (out.isNotEmpty()) return out.distinctBy { it.url }

        val doc = app.get(mainUrl + "/search/?s=" + q, headers = headers).document
        return parseCards(doc)
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        doc.select("a[href*=/anime/]").forEach { a ->
            var href = a.attr("abs:href")
            if (!href.contains("/anime/")) return@forEach
            href = href.trimEnd('/') + "/"
            val slug = href.trimEnd('/').substringAfterLast("/")
            if (slug.isBlank() || slug == "anime") return@forEach
            val title = a.selectFirst("img")?.attr("alt")?.ifBlank { null }
                ?: a.text().trim().ifBlank { null }
                ?: slug.replace("-", " ")
            if (title.isBlank()) return@forEach
            var poster = a.selectFirst("img")?.attr("data-src")
                ?: a.selectFirst("img")?.attr("src")
            if (poster != null && poster.startsWith("data:")) poster = null
            if (poster != null && !poster.startsWith("http")) {
                poster = mainUrl + "/" + poster.trimStart('/')
            }
            out.add(
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                }
            )
        }
        return out.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = if (url.endsWith("/")) url else url + "/"
        val doc = app.get(pageUrl, headers = headers + mapOf("Referer" to (mainUrl + "/"))).document
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.substringBefore(" - ")?.trim()
            ?: pageUrl.trimEnd('/').substringAfterLast('/')

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("img[src*=uploads]")?.attr("src")

        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
        val genres = doc.select("a[href*=/genre/]").map { it.text().trim() }.filter { it.isNotBlank() }

        val episodes = ArrayList<Episode>()
        val seen = HashSet<String>()

        // Prefer ep-list / ep-item, then any /watch/
        val anchors = doc.select("#ep-list a[href*=/watch/], a.ep-item[href*=/watch/], a[href*=/watch/]")
        anchors.forEach { a ->
            var href = a.attr("abs:href")
            if (!href.contains("/watch/")) return@forEach
            href = href.substringBefore("#")
            if (!seen.add(href)) return@forEach

            val epName = a.text().trim().ifBlank { "Episode" }
            val epNum = a.attr("data-ep").toIntOrNull()?.let { it + 1 }
                ?: Regex("""(\d+)""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""/watch/""").let { episodes.size + 1 }

            episodes.add(
                newEpisode(href) {
                    this.name = epName
                    this.episode = epNum
                }
            )
        }

        if (episodes.isEmpty()) {
            return newMovieLoadResponse(title, pageUrl, TvType.AnimeMovie, pageUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres.ifEmpty { null }
            }
        }

        // sort by episode number when possible
        val sorted = episodes.sortedWith(compareBy(nullsLast()) { it.episode })

        return newAnimeLoadResponse(title, pageUrl, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = plot
            this.tags = genres.ifEmpty { null }
            addEpisodes(DubStatus.Dubbed, sorted)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var watchUrl = data.substringBefore("#")

        // Search/home sometimes pass anime page — resolve first watch URL
        if (!watchUrl.contains("/watch/")) {
            try {
                val doc = app.get(
                    if (watchUrl.endsWith("/")) watchUrl else watchUrl + "/",
                    headers = headers
                ).document
                val first = doc.select("#ep-list a[href*=/watch/], a.ep-item[href*=/watch/], a[href*=/watch/]")
                    .firstOrNull()?.attr("abs:href")
                if (first != null) watchUrl = first
            } catch (e: Exception) {
                return false
            }
        }
        if (!watchUrl.contains("/watch/")) return false

        val watchId = Regex("""/watch/([A-Za-z0-9]+)\.html""").find(watchUrl)?.groupValues?.get(1)
            ?: watchUrl.trimEnd('/').substringAfterLast('/').removeSuffix(".html")

        val baseJs = try {
            app.get(
                mainUrl + "/base/" + watchId + "/",
                headers = headers + mapOf("Referer" to watchUrl)
            ).text
        } catch (e: Exception) {
            ""
        }

        val streamHost = Regex("""webmainapp\s*=\s*['"]([^'"]+)['"]""")
            .find(baseJs)?.groupValues?.get(1)?.trimEnd('/')
            ?: "https://streaming.tonytonychopper.com"

        val playCodes = LinkedHashSet<String>()
        Regex("""playback/v/([A-Za-z0-9]+)/""").findAll(baseJs).forEach {
            playCodes.add(it.groupValues[1])
        }
        if (playCodes.isEmpty()) {
            Regex("""playback/[a-z]/([A-Za-z0-9]+)/""").findAll(baseJs).forEach {
                playCodes.add(it.groupValues[1])
            }
        }
        if (playCodes.isEmpty()) playCodes.add(watchId)

        var found = false
        val tonyRef = "https://anime.tonytonychopper.net/"

        for (code in playCodes) {
            for (kind in listOf("v", "f", "e")) {
                val playUrl = streamHost + "/playback/" + kind + "/" + code + "/"
                val streamIds = extractTonyIds(playUrl, watchUrl)
                for (sid in streamIds) {
                    // Direct 1080 only — avoids empty 360/720 and broken master edge cases
                    val q1080 = "https://anime.tonytonychopper.net/quality2/" + sid + "/1080/"
                    callback.invoke(
                        ExtractorLink(
                            name,
                            "1080p",
                            q1080,
                            tonyRef,
                            Qualities.P1080.value,
                            true,
                            streamHeaders
                        )
                    )
                    found = true
                }
                if (found) break
            }
            if (found) break
        }

        return found
    }

    private suspend fun extractTonyIds(playUrl: String, referer: String): List<String> {
        val ids = LinkedHashSet<String>()
        try {
            val html = app.get(
                playUrl,
                headers = headers + mapOf("Referer" to referer)
            ).text
            Regex("""anime\.tonytonychopper\.net/v2/([A-Za-z0-9]+)""").findAll(html).forEach {
                ids.add(it.groupValues[1])
            }
            Regex("""/v2/([A-Za-z0-9]+)""").findAll(html).forEach {
                ids.add(it.groupValues[1])
            }
        } catch (e: Exception) {
            // ignore
        }
        return ids.toList()
    }
}
