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
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/scoretop/" to "Top Score"
    )

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        val root = base.trimEnd('/')
        return if (root == mainUrl || root == "$mainUrl/") root else "$root/page/$page/"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data == "$mainUrl/" || request.data == mainUrl) {
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
                "$mainUrl/vendor/search-ajax.php?q=$q",
                headers = headers + mapOf("X-Requested-With" to "XMLHttpRequest")
            ).text
        } catch (e: Exception) {
            ""
        }

        val out = ArrayList<SearchResponse>()
        val re = Regex(""""slug"\s*:\s*"([^"]+)".*?"title"\s*:\s*"([^"]+)"""")
        // JSON field order on site: title, slug, cover
        val re2 = Regex(""""title"\s*:\s*"([^"]+)"\s*,\s*"slug"\s*:\s*"([^"]+)"\s*,\s*"cover"\s*:\s*"([^"]+)"""")
        re2.findAll(json).forEach { m ->
            val title = decodeJson(m.groupValues[1])
            val slug = m.groupValues[2]
            var cover = m.groupValues[3].replace("\\/", "/")
            if (!cover.startsWith("http")) cover = "$mainUrl/$cover"
            out.add(
                newAnimeSearchResponse(title, "$mainUrl/anime/$slug/", TvType.Anime) {
                    this.posterUrl = cover
                }
            )
        }
        if (out.isNotEmpty()) return out.distinctBy { it.url }

        val doc = app.get("$mainUrl/search/?s=$q", headers = headers).document
        return parseCards(doc)
    }

    private fun decodeJson(s: String): String {
        return try {
            s.replace("\\u", "\\u")
                .replace(Regex("""\\u([0-9a-fA-F]{4})""")) {
                    it.groupValues[1].toInt(16).toChar().toString()
                }
                .replace("\\\"", "\"")
                .replace("\\/", "/")
        } catch (e: Exception) {
            s
        }
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
                poster = "\( mainUrl/ \){poster.trimStart('/')}"
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
        val doc = app.get(url, headers = headers + mapOf("Referer" to "$mainUrl/")).document
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
            if (!a.classNames().contains("ep-item") && a.parent()?.id() != "ep-list") {
                // still allow #ep-list children
                val inList = a.parents().any { it.id() == "ep-list" }
                if (!inList && !a.className().contains("ep-item")) return@forEach
            }
            val epName = a.text().trim().ifBlank { "Episode" }
            val epNum = Regex("""(\d+)""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
            episodes.add(
                newEpisode(href) {
                    this.name = epName
                    this.episode = epNum
                }
            )
        }

        // fallback: all /watch/ links on page
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
            app.get("$mainUrl/base/$watchId/", headers = headers + mapOf("Referer" to data)).text
        } catch (e: Exception) {
            ""
        }

        val streamHost = Regex("""webmainapp\s*=\s*['"]([^'"]+)['"]""")
            .find(baseJs)?.groupValues?.get(1)?.trimEnd('/')
            ?: "https://streaming.tonytonychopper.com"

        val playbackCodes = LinkedHashSet<String>()
        Regex("""playback/[a-z]/([A-Za-z0-9]+)/""").findAll(baseJs).forEach {
            playbackCodes.add(it.groupValues[1])
        }
        if (playbackCodes.isEmpty()) playbackCodes.add(watchId)

        val embedUrls = LinkedHashSet<String>()
        for (code in playbackCodes) {
            for (kind in listOf("v", "f", "e", "x", "y", "z", "b")) {
                val playUrl = "$streamHost/playback/$kind/$code/"
                try {
                    val emb = app.get(playUrl, headers = headers + mapOf("Referer" to data)).document
                    emb.select("iframe[src]").forEach { iframe ->
                        val src = iframe.attr("abs:src").ifBlank { iframe.attr("src") }
                        if (src.startsWith("http")) embedUrls.add(src)
                    }
                    Regex("""https?://[^"'<>\s]+""").findAll(emb.html()).forEach { m ->
                        val u = m.value
                        if (u.contains("abysscdn") || u.contains("marimo") ||
                            u.contains("tonytonychopper") || u.contains(".m3u8") || u.contains(".mp4")
                        ) {
                            embedUrls.add(u)
                        }
                    }
                } catch (e: Exception) {
                    // skip
                }
            }
        }

        for (embed in embedUrls) {
            when {
                embed.contains(".m3u8") -> {
                    callback.invoke(
                        ExtractorLink(
                            name, "HLS", embed, mainUrl,
                            Qualities.Unknown.value, true
                        )
                    )
                    found = true
                }
                embed.contains(".mp4") && !embed.contains("ibit.ly") -> {
                    callback.invoke(
                        ExtractorLink(
                            name, "MP4", embed, mainUrl,
                            Qualities.Unknown.value, false
                        )
                    )
                    found = true
                }
                else -> {
                    if (loadExtractor(embed, data, subtitleCallback, callback)) {
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
                        }
