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

    // series/movie category সাইটে প্রায় খালি — কাজ করা ক্যাটাগরি দিয়েছি
    override val mainPage = mainPageOf(
        (mainUrl + "/") to "Latest",
        (mainUrl + "/category/hindi-anime-download/") to "Hindi Anime",
        (mainUrl + "/category/on-going/") to "On Going",
        (mainUrl + "/category/action/") to "Action",
        (mainUrl + "/category/school/") to "School",
        (mainUrl + "/category/romance/") to "Romance",
        (mainUrl + "/category/shounen/") to "Shounen",
        (mainUrl + "/category/isekai/") to "Isekai",
        (mainUrl + "/category/1080p-anime-download/") to "1080p"
    )

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return base.trimEnd('/') + "/page/" + page + "/"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        val doc = app.get(url, headers = headers).document

        // শুধু main content — sidebar বাদ
        val home = doc.select("#content article, main article, .ast-row article, article.ast-article-post, article.post")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home, home.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val doc = app.get(mainUrl + "/?s=" + q, headers = headers).document
        return doc.select("#content article, main article, article.ast-article-post, article.post")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("h2.entry-title a, .entry-title a, h2 a") ?: return null
        var href = a.attr("abs:href")
        if (href.isBlank()) href = a.attr("href")
        if (href.isBlank()) return null
        // sidebar / tag লিংক বাদ
        if (href.contains("/category/") || href.contains("/tag/") || href.contains("/page/")) return null

        var title = a.text().trim()
            .replace(Regex("(?i)\\s*(Hindi|English|Japanese|Tamil|Telugu|Multi Audio|WEB-DL|Episodes?\\s*Download).*$"), "")
            .trim()
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

        if (episodes.isEmpty()) {
            val allDl = linksDoc.select("a[href*=/dl/]")
            var i = 0
            while (i < allDl.size) {
                val end = minOf(i + 3, allDl.size)
                val group = allDl.subList(i, end)
                val sources = ArrayList<Map<String, String>>()
                for (a in group) {
                    var href = a.attr("abs:href")
                    if (href.isBlank()) href = a.attr("href")
                    if (href.isBlank()) continue
                    val txt = a.text().trim().ifBlank { "Link" }
                    sources.add(mapOf("url" to href, "name" to txt, "quality" to txt))
                }
                if (sources.isNotEmpty()) {
                    val ep = (i / 3) + 1
                    episodes.add(
                        newEpisode(sources.toJson()) {
                            this.name = "Episode $ep"
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
            listOf(mapOf("url" to data, "name" to "AnimeDrive", "quality" to ""))
        }

        var found = false

        for (src in sources) {
            val rawUrl = src["url"] ?: continue
            val qualityText = src["quality"] ?: src["name"] ?: ""
            val q = qualityFromText(qualityText)

            val realUrl = resolveDlLink(rawUrl) ?: continue

            // ----- HubCloud -----
            if (realUrl.contains("hubcloud", true)) {
                if (extractHubCloud(realUrl, qualityText, q, callback)) {
                    found = true
                    continue
                }
            }

            // ----- GDFlix -----
            if (realUrl.contains("gdflix", true) || realUrl.contains("gdlink", true)) {
                try {
                    if (loadExtractor(realUrl, mainUrl, subtitleCallback, callback)) {
                        found = true
                        continue
                    }
                } catch (_: Exception) {
                }
                // fallback: still list the page url
                callback.invoke(
                    ExtractorLink(name, "GDFlix • $qualityText", realUrl, mainUrl, q, false)
                )
                found = true
                continue
            }

            // other hosters via built-in extractors
            try {
                if (loadExtractor(realUrl, mainUrl, subtitleCallback, callback)) {
                    found = true
                    continue
                }
            } catch (_: Exception) {
            }

            callback.invoke(
                ExtractorLink(name, src["name"] ?: "Server", realUrl, mainUrl, q, false)
            )
            found = true
        }
        return found
    }

    /** HubCloud: drive page → hubcloud.php → FSL / 10Gbps / Pixeldrain */
    private suspend fun extractHubCloud(
        driveUrl: String,
        qualityText: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val driveDoc = app.get(driveUrl, headers = headers + mapOf("Referer" to mainUrl)).document
            val phpUrl = driveDoc.select("a[href*=hubcloud.php], a[href*=gamerxyt]")
                .map { it.attr("abs:href").ifBlank { it.attr("href") } }
                .firstOrNull { it.contains("hubcloud.php") }
                ?: Regex("""https?://[^"'\s]+hubcloud\.php[^"'\s]*""")
                    .find(driveDoc.html())?.value

            if (phpUrl.isNullOrBlank()) return false

            val phpDoc = app.get(
                phpUrl,
                headers = headers + mapOf("Referer" to driveUrl)
            ).document

            val anchors = phpDoc.select("a[href]")
            for (a in anchors) {
                val href = a.attr("abs:href").ifBlank { a.attr("href") }
                if (href.isBlank()) continue
                val label = a.text().trim()

                // skip junk
                if (href.contains("t.me") || href.contains("telegram", true)) continue
                if (href.contains("winexch") || href.contains("google.com/search")) continue
                if (href.contains("hubcloud.cx/drive/admin") || href.contains("tinyurl")) continue

                when {
                    // Direct FSL / R2 CDN file (best)
                    href.contains("r2.cloudflarestorage.com") ||
                            href.contains("Download [FSL", true) ||
                            label.contains("FSL", true) -> {
                        if (href.startsWith("http") && !href.contains("hubcloud.php")) {
                            callback.invoke(
                                ExtractorLink(
                                    name,
                                    "HubCloud FSL • $qualityText",
                                    href,
                                    "https://hubcloud.cx/",
                                    quality,
                                    false
                                )
                            )
                            found = true
                        }
                    }
                    // 10Gbps server
                    label.contains("10Gbps", true) || href.contains("gpdl.hubcloud") -> {
                        if (href.startsWith("http")) {
                            callback.invoke(
                                ExtractorLink(
                                    name,
                                    "HubCloud 10Gbps • $qualityText",
                                    href,
                                    "https://hubcloud.cx/",
                                    quality,
                                    false
                                )
                            )
                            found = true
                        }
                    }
                    // Pixeldrain
                    href.contains("pixeldrain", true) -> {
                        val fileId = Regex("""/(?:u|file)/([A-Za-z0-9]+)""").find(href)?.groupValues?.getOrNull(1)
                        val direct = if (fileId != null) {
                            "https://pixeldrain.com/api/file/$fileId?download"
                        } else href
                        callback.invoke(
                            ExtractorLink(
                                name,
                                "Pixeldrain • $qualityText",
                                direct,
                                href,
                                quality,
                                false
                            )
                        )
                        found = true
                    }
                }
            }
        } catch (_: Exception) {
        }
        return found
    }

    private fun qualityFromText(text: String): Int {
        return when {
            text.contains("1080", true) -> Qualities.P1080.value
            text.contains("720", true) -> Qualities.P720.value
            text.contains("480", true) -> Qualities.P480.value
            text.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    /** /dl/BASE64 → real hoster URL (base64 then reverse) */
    private fun resolveDlLink(dlUrl: String): String? {
        return try {
            if (!dlUrl.contains("/dl/")) return dlUrl
            var encoded = dlUrl.substringAfter("/dl/")
            encoded = encoded.substringBefore("?").substringBefore("&").trim()
            if (encoded.isBlank()) return null
            val pad = (4 - encoded.length % 4) % 4
            val padded = encoded + "=".repeat(pad)
            val decoded = String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
            val real = decoded.reversed()
            if (real.startsWith("http")) real else null
        } catch (_: Exception) {
            null
        }
    }
}
