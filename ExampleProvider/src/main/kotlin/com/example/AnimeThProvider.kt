@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import java.net.URLEncoder

class AnimeThProvider : MainAPI() {
    override var mainUrl = "https://anime-th.com"
    override var name = "AnimeTH"
    override val hasMainPage = true
    override var lang = "th"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "th-TH,th;q=0.9,en;q=0.8",
        "Referer" to (mainUrl + "/")
    )

    override val mainPage = mainPageOf(
        (mainUrl + "/") to "Home",
        (mainUrl + "/scoretop/") to "Top Score"
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
        val json = try {
            app.get(
                mainUrl + "/vendor/search-ajax.php?q=" + q,
                headers = headers + mapOf("X-Requested-With" to "XMLHttpRequest")
            ).text
        } catch (e: Exception) {
            ""
        }

        val out = ArrayList<SearchResponse>()
        val re2 = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"slug\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"cover\"\\s*:\\s*\"([^\"]+)\"")
        re2.findAll(json).forEach { m ->
            val title = m.groupValues[1].replace("\\\"", "\"").replace("\\/", "/")
            val slug = m.groupValues[2]
            var cover = m.groupValues[3].replace("\\/", "/")
            if (!cover.startsWith("http")) cover = mainUrl + "/" + cover
            out.add(
                newAnimeSearchResponse(title, mainUrl + "/anime/" + slug + "/", TvType.Anime) {
                    this.posterUrl = cover
                }
            )
        }
        if (out.isNotEmpty()) return out.distinctBy { it.url }

        val doc = app.get(mainUrl + "/search/?s=" + q, headers = headers).document
        return parseCards(doc)
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        doc.select("a[href*=/anime/]").forEach { a ->
            val href = a.attr("abs:href")
            if (!href.contains("/anime/")) return@forEach
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
        val doc = app.get(url, headers = headers + mapOf("Referer" to (mainUrl + "/"))).document
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.substringBefore(" - ")?.trim()
            ?: url.trimEnd('/').substringAfterLast('/')

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("img[src*=uploads]")?.attr("src")

        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
        val genres = doc.select("a[href*=/genre/]").map { it.text().trim() }.filter { it.isNotBlank() }

        val episodes = ArrayList<Episode>()
        doc.select("a[href*=/watch/]").forEach { a ->
            val href = a.attr("abs:href")
            if (!href.contains("/watch/")) return@forEach
            val inList = a.className().contains("ep-item") || a.parents().any { it.id() == "ep-list" }
            if (!inList) return@forEach
            val epName = a.text().trim().ifBlank { "Episode" }
            val epNum = Regex("""(\d+)""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
            episodes.add(
                newEpisode(href) {
                    this.name = epName
                    this.episode = epNum
                }
            )
        }

        if (episodes.isEmpty()) {
            doc.select("a[href*=/watch/]").forEach { a ->
                val href = a.attr("abs:href")
                if (!href.contains("/watch/")) return@forEach
                val epName = a.text().trim().ifBlank { "Episode" }
                episodes.add(newEpisode(href) { this.name = epName })
            }
        }

        if (episodes.isEmpty()) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres.ifEmpty { null }
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = plot
            this.tags = genres.ifEmpty { null }
            addEpisodes(DubStatus.Dubbed, episodes.distinctBy { it.data })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!data.startsWith("http")) return false
        var found = false

        val watchId = Regex("""/watch/([A-Za-z0-9]+)\.html""").find(data)?.groupValues?.get(1)
            ?: data.trimEnd('/').substringAfterLast('/').removeSuffix(".html")

        val baseJs = try {
            app.get(mainUrl + "/base/" + watchId + "/", headers = headers + mapOf("Referer" to data)).text
        } catch (e: Exception) {
            ""
        }

        val streamHost = Regex("""webmainapp\s*=\s*['"]([^'"]+)['"]""")
            .find(baseJs)?.groupValues?.get(1)?.trimEnd('/')
            ?: "https://streaming.tonytonychopper.com"

        // Default server from switch_play(0): playback/v/CODE
        val primaryCodes = LinkedHashSet<String>()
        Regex("""playback/v/([A-Za-z0-9]+)/""").findAll(baseJs).forEach {
            primaryCodes.add(it.groupValues[1])
        }
        Regex("""playback/[a-z]/([A-Za-z0-9]+)/""").findAll(baseJs).forEach {
            primaryCodes.add(it.groupValues[1])
        }
        if (primaryCodes.isEmpty()) primaryCodes.add(watchId)

        // Only try a few servers (was 50+ requests before = endless loading)
        val kinds = listOf("v", "f", "e")
        val targets = ArrayList<String>()
        for (code in primaryCodes.take(2)) {
            for (kind in kinds) {
                targets.add(streamHost + "/playback/" + kind + "/" + code + "/")
            }
        }

        val finalLinks = LinkedHashSet<String>()
        for (playUrl in targets) {
            try {
                collectEmbeds(playUrl, data, finalLinks, 0)
            } catch (e: Exception) {
                // skip
            }
            if (finalLinks.any { it.contains("abysscdn") }) break
        }

        for (link in finalLinks) {
            when {
                link.contains(".m3u8") -> {
                    callback.invoke(
                        ExtractorLink(
                            name, "HLS", link, mainUrl,
                            Qualities.Unknown.value, true
                        )
                    )
                    found = true
                }
                link.contains(".mp4") && !link.contains("ibit.ly") -> {
                    callback.invoke(
                        ExtractorLink(
                            name, "MP4", link, mainUrl,
                            Qualities.Unknown.value, false
                        )
                    )
                    found = true
                }
                else -> {
                    if (loadExtractor(link, data, subtitleCallback, callback)) {
                        found = true
                    }
                }
            }
        }

        if (!found) {
            found = loadExtractor(data, mainUrl, subtitleCallback, callback)
        }
        return found
    }

    private suspend fun collectEmbeds(
        url: String,
        referer: String,
        out: LinkedHashSet<String>,
        depth: Int
    ) {
        if (depth > 3 || url.isBlank()) return
        val doc = try {
            app.get(url, headers = headers + mapOf("Referer" to referer)).document
        } catch (e: Exception) {
            return
        }
        val html = doc.html()

        // Direct media
        Regex("""https?://[^"'<>\s]+\.m3u8[^"'<>\s]*""").findAll(html).forEach { out.add(it.value) }
        Regex("""https?://[^"'<>\s]+\.mp4[^"'<>\s]*""").findAll(html).forEach {
            val u = it.value
            if (!u.contains("ibit.ly") && !u.contains("ad")) out.add(u)
        }

        // Abyss (final public player used by this site)
        Regex("""https?://(?:www\.)?abysscdn\.com/\?v=[A-Za-z0-9_-]+""").findAll(html).forEach {
            out.add(it.value)
        }

        // iframes
        doc.select("iframe[src]").forEach { iframe ->
            var src = iframe.attr("abs:src").ifBlank { iframe.attr("src") }
            if (!src.startsWith("http")) return@forEach
            when {
                src.contains("abysscdn") -> out.add(src)
                src.contains("marimo") || src.contains("tonytonychopper") -> {
                    out.add(src)
                    collectEmbeds(src, url, out, depth + 1)
                }
                else -> out.add(src)
            }
        }

        // marimo / tony links in raw html
        Regex("""https?://(?:player\.marimo\.me|anime\.tonytonychopper\.net)[^"'<>\s]+""")
            .findAll(html)
            .forEach { m ->
                val u = m.value
                out.add(u)
                if (depth < 2) collectEmbeds(u, url, out, depth + 1)
            }
    }
}
