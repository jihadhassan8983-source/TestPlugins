@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeDriveProvider : MainAPI() {
    override var mainUrl = "https://animedrive.me"
    override var name = "AnimeDrive"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val ua =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,hi;q=0.8",
        "Referer" to (mainUrl + "/")
    )

    override val mainPage = mainPageOf(
        (mainUrl + "/") to "Latest",
        (mainUrl + "/category/hindi-anime-download/") to "Hindi Anime",
        (mainUrl + "/category/action/") to "Action",
        (mainUrl + "/category/school/") to "School",
        (mainUrl + "/category/romance/") to "Romance",
        (mainUrl + "/category/1080p-anime-download/") to "1080p",
        (mainUrl + "/category/series/") to "Series"
    )

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        val root = base.trimEnd('/')
        return root + "/page/" + page + "/"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        val doc = app.get(url, headers = headers).document
        val home = doc.select("article.ast-article-post, article.post").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home, home.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val doc = app.get(mainUrl + "/?s=" + q, headers = headers).document
        return doc.select("article.ast-article-post, article.post").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("h2.entry-title a, .entry-title a") ?: return null
        var href = a.attr("abs:href")
        if (href.isBlank()) href = a.attr("href")
        if (href.isBlank()) return null

        var title = a.text().trim()
        title = title.replace(
            Regex("(?i)\\s*(Hindi|English|Japanese|Tamil|Telugu|Multi Audio|WEB-DL|Episodes?\\s*Download).*$"),
            ""
        ).trim()
        if (title.isBlank()) return null

        val img = this.selectFirst("img.wp-post-image, img[src]")
        var poster = img?.attr("abs:src")
        if (poster.isNullOrBlank()) poster = img?.attr("src")
        if (poster.isNullOrBlank()) poster = img?.attr("data-src")

        val isMovie = title.contains("Movie", true) || href.contains("movie", true)

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.AnimeMovie) {
                this.posterUrl = poster
            }
        } else {
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        var title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "Unknown"
        title = title.replace(
            Regex("(?i)\\s*(Hindi|English|Japanese|Tamil|Telugu|Multi Audio|WEB-DL|Episodes?\\s*Download).*$"),
            ""
        ).trim()

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("img.wp-post-image")?.attr("abs:src")

        val plot = document.select(".entry-content p")
            .map { it.text().trim() }
            .firstOrNull { it.length > 60 }

        val year = Regex("(20\\d{2})").find(
            document.selectFirst(".entry-content")?.text() ?: ""
        )?.groupValues?.getOrNull(1)?.toIntOrNull()

        // link.animedrive.me page
        val linksPageUrl = document.select("a[href*=link.animedrive.me]")
            .map {
                var h = it.attr("abs:href")
                if (h.isBlank()) h = it.attr("href")
                h
            }
            .firstOrNull { it.contains("link.animedrive.me") && !it.contains("/dl/") }
            ?: throw ErrorLoadingException("Download links page not found")

        val linksDoc = app.get(linksPageUrl, headers = headers).document
        val episodes = ArrayList<Episode>()

        val epNames = linksDoc.select("span.adc-epname")
        if (epNames.isNotEmpty()) {
            epNames.forEachIndexed { index, epNameEl ->
                val epTitle = epNameEl.text().trim()
                val epNum = Regex("(\\d+)").find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: (index + 1)

                val container = epNameEl.parents().firstOrNull { p ->
                    p.select("a[href*=/dl/]").isNotEmpty()
                } ?: epNameEl.parent()

                val buttons = container?.select("a[href*=/dl/]") ?: emptyList()
                val sources = ArrayList<Map<String, String>>()

                for (btn in buttons) {
                    var href = btn.attr("abs:href")
                    if (href.isBlank()) href = btn.attr("href")
                    if (href.isBlank() || !href.contains("/dl/")) continue

                    val qualityText = btn.text().trim().ifBlank { "Link" }
                    val hoster = when {
                        btn.hasClass("hc") || qualityText.contains("Hub", true) -> "HubCloud"
                        btn.hasClass("fp") || qualityText.contains("GD", true) ||
                                qualityText.contains("Flix", true) -> "GDFlix"
                        else -> "Server"
                    }

                    sources.add(
                        mapOf(
                            "url" to href,
                            "name" to (hoster + " • " + qualityText),
                            "quality" to qualityText
                        )
                    )
                }

                if (sources.isNotEmpty()) {
                    episodes.add(
                        newEpisode(sources.toJson()) {
                            this.name = epTitle
                            this.episode = epNum
                            this.season = 1
                        }
                    )
                }
            }
        }

        // Fallback
        if (episodes.isEmpty()) {
            val allDl = linksDoc.select("a[href*=/dl/]")
            var i = 0
            while (i < allDl.size) {
                val group = allDl.subList(i, minOf(i + 3, allDl.size))
                val sources = ArrayList<Map<String, String>>()
                for (a in group) {
                    var href = a.attr("abs:href")
                    if (href.isBlank()) href = a.attr("href")
                    if (href.isBlank()) continue
                    val txt = a.text().trim().ifBlank { "Link" }
                    sources.add(
                        mapOf(
                            "url" to href,
                            "name" to txt,
                            "quality" to txt
                        )
                    )
                }
                if (sources.isNotEmpty()) {
                    val ep = (i / 3) + 1
                    episodes.add(
                        newEpisode(sources.toJson()) {
                            this.name = "Episode " + ep
                            this.episode = ep
                        }
                    )
                }
                i += 3
            }
        }

        val isMovie = title.contains("Movie", true) || url.contains("movie", true) || episodes.size <= 1

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, episodes.firstOrNull()?.data ?: "") {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                addEpisodes(DubStatus.Dubbed, episodes.sortedBy { it.episode })
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val sources: List<Map<String, String>> = try {
            parseJson(data)
        } catch (e: Exception) {
            listOf(mapOf("url" to data, "name" to "AnimeDrive"))
        }

        var found = false

        for (src in sources) {
            val rawUrl = src["url"] ?: continue
            val displayName = src["name"] ?: "AnimeDrive"
            val qualityText = src["quality"] ?: displayName

            val realUrl = resolveDlLink(rawUrl) ?: continue

            // Try built-in extractors first (HubCloud / GDFlix if registered)
            try {
                if (loadExtractor(realUrl, mainUrl, subtitleCallback, callback)) {
                    found = true
                    continue
                }
            } catch (_: Exception) {
            }

            // Fallback: pass resolved URL (player / external extractor may handle)
            val q = when {
                qualityText.contains("1080", true) -> Qualities.P1080.value
                qualityText.contains("720", true) -> Qualities.P720.value
                qualityText.contains("480", true) -> Qualities.P480.value
                else -> Qualities.Unknown.value
            }

            val isM3u8 = realUrl.contains(".m3u8", true)

            callback.invoke(
                ExtractorLink(
                    name,
                    displayName,
                    realUrl,
                    mainUrl,
                    q,
                    isM3u8
                )
            )
            found = true
        }
        return found
    }

    /** Decode /dl/BASE64 → real https://hubcloud... or https://gdflix... URL */
    private fun resolveDlLink(dlUrl: String): String? {
        return try {
            if (!dlUrl.contains("/dl/")) return dlUrl

            var encoded = dlUrl.substringAfter("/dl/")
            encoded = encoded.substringBefore("?")
            encoded = encoded.substringBefore("&")
            encoded = encoded.trim()
            if (encoded.isBlank()) return null

            val pad = (4 - encoded.length % 4) % 4
            val padded = encoded + "=".repeat(pad)

            val decodedBytes = Base64.decode(padded, Base64.DEFAULT)
            val decoded = String(decodedBytes, Charsets.UTF_8)
            val real = decoded.reversed()

            if (real.startsWith("http")) real else null
        } catch (e: Exception) {
            null
        }
    }
}
