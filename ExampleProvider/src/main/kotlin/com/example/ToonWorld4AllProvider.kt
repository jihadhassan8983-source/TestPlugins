@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import java.net.URLEncoder

class ToonWorld4AllProvider : MainAPI() {
    override var mainUrl = "https://toonworld4all.me"
    override var name = "ToonWorld4All"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.TvSeries,
        TvType.Movie
    )

    private val archiveUrl = "https://archive.toonworld4all.me"
    private val ua =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private fun hdr(ref: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,hi;q=0.8",
        "Referer" to ref
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/category/anime/" to "Anime",
        "$mainUrl/category/netflix/" to "Netflix",
        "$mainUrl/category/disney/" to "Disney"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            page <= 1 -> request.data
            request.data.contains("category") -> request.data.trimEnd('/') + "/page/$page/"
            else -> "$mainUrl/page/$page/"
        }
        return try {
            val html = app.get(url, headers = hdr()).text
            newHomePageResponse(request.name, parseCards(html), true)
        } catch (_: Exception) {
            newHomePageResponse(request.name, emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            parseCards(app.get("$mainUrl/?s=$q", headers = hdr()).text)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun pickPoster(img: org.jsoup.nodes.Element?): String? {
        if (img == null) return null
        for (k in listOf("abs:src", "src", "data-src", "data-lazy-src", "data-original")) {
            val v = img.attr(k)
            if (!v.isNullOrBlank() && v.startsWith("http") && !v.contains("data:image")) return v
        }
        val srcset = img.attr("srcset")
        if (srcset.isNotBlank()) {
            val first = srcset.split(",").firstOrNull()?.trim()?.substringBefore(" ")
            if (!first.isNullOrBlank() && first.startsWith("http")) return first
        }
        return null
    }

    private fun parseCards(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html, mainUrl)
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        for (art in doc.select("article")) {
            val a = art.selectFirst("h2 a[href], h3 a[href], .entry-title a[href], a[href]")
                ?: continue
            var href = a.attr("abs:href")
            if (href.isBlank()) href = a.attr("href")
            href = href.substringBefore("#").substringBefore("?").trimEnd('/') + "/"
            if (!href.contains("toonworld4all.me")) continue
            if (shouldSkip(href)) continue
            if (!seen.add(href)) continue

            var title = a.text().trim()
            if (title.isBlank()) title = a.attr("title").trim()
            if (title.length < 3 || title.contains("Comments", true)) continue

            val poster = pickPoster(art.selectFirst("img"))
            val type = when {
                title.contains("Movie", true) -> TvType.Movie
                title.contains("Season", true) -> TvType.TvSeries
                else -> TvType.Anime
            }
            out.add(newAnimeSearchResponse(title, href, type) {
                this.posterUrl = poster
            })
            if (out.size >= 40) break
        }
        return out
    }

    private fun shouldSkip(href: String): Boolean {
        val h = href.lowercase()
        return h.contains("/category/") || h.contains("/tag/") || h.contains("/author/") ||
            h.contains("/page/") || h.contains("/feed") || h.contains("wp-") ||
            h.contains("contact") || h.contains("dmca") || h.contains("how-to") ||
            h.contains("list_25") || h.contains("shows-list") || h.contains("xmlrpc") ||
            h.contains("#comments")
    }

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = url.substringBefore("#").substringBefore("?").trimEnd('/') + "/"
        val html = app.get(pageUrl, headers = hdr()).text
        val doc = Jsoup.parse(html, mainUrl)

        var title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim().orEmpty()
        if (title.isBlank()) {
            title = doc.selectFirst("title")?.text()?.substringBefore("|")?.trim().orEmpty()
        }

        var poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        if (poster.isNullOrBlank()) {
            poster = pickPoster(doc.selectFirst(".entry-content img, article img, img.wp-post-image"))
        }

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst(".entry-content p")?.text()

        val episodes = ArrayList<Episode>()
        val seen = HashSet<String>()
        for (a in doc.select(".entry-content a[href], article a[href]")) {
            var href = a.attr("abs:href")
            if (href.isBlank()) href = a.attr("href")
            if (!href.contains("archive.toonworld4all.me/episode/") && !href.contains("/episode/")) continue
            if (href.startsWith("/")) href = archiveUrl + href
            if (!href.startsWith("http")) continue
            if (!seen.add(href)) continue

            val slugPart = href.substringAfter("/episode/").substringBefore("?").trimEnd('/')
            var epNum: Int? = null
            val nm = Regex("(\\d+)x(\\d+)$", RegexOption.IGNORE_CASE).find(slugPart)
            if (nm != null) epNum = nm.groupValues[2].toIntOrNull()
            val epName = a.text().trim().ifBlank {
                if (epNum != null) "Episode $epNum" else slugPart
            }
            episodes.add(newEpisode(href) {
                this.name = epName
                this.episode = epNum
                this.posterUrl = poster
            })
        }

        if (episodes.isNotEmpty()) {
            val sorted = episodes.sortedBy { it.episode ?: 9999 }
            return newAnimeLoadResponse(title, pageUrl, TvType.Anime) {
                this.posterUrl = poster
                this.plot = plot
                addEpisodes(DubStatus.Subbed, sorted)
                addEpisodes(DubStatus.Dubbed, sorted)
            }
        }
        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    private fun qualityFrom(label: String): Int {
        val t = label.lowercase()
        return when {
            "1080" in t -> Qualities.P1080.value
            "720" in t -> Qualities.P720.value
            "480" in t -> Qualities.P480.value
            "360" in t -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun pushVideo(
        callback: (ExtractorLink) -> Unit,
        label: String,
        url: String,
        referer: String,
        added: HashSet<String>
    ): Boolean {
        val u = url.trim()
        if (!u.startsWith("http")) return false
        // Never push HTML host pages as "video"
        if (u.contains("/file/") && (u.contains("gdflix") || u.contains("filepress"))) return false
        if (u.contains("hubcloud") && u.contains("/video/")) return false
        if (!added.add(u)) return false
        val isM3u8 = u.contains(".m3u8")
        callback.invoke(
            ExtractorLink(
                name,
                label,
                u,
                referer,
                qualityFrom(label),
                isM3u8
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
        var page = data.trim()
        if (page.startsWith("/episode/")) page = archiveUrl + page
        if (!page.startsWith("http")) page = "$mainUrl/" + page.trimStart('/')

        if (page.contains("toonworld4all.me") && !page.contains("archive.")) {
            try {
                val html0 = app.get(page, headers = hdr()).text
                val doc0 = Jsoup.parse(html0, mainUrl)
                for (a in doc0.select(".entry-content a[href]")) {
                    var h = a.attr("abs:href")
                    if (h.isBlank()) h = a.attr("href")
                    if (h.contains("archive.toonworld4all") || h.contains("/episode/")) {
                        if (h.startsWith("/")) h = archiveUrl + h
                        page = h
                        break
                    }
                }
            } catch (_: Exception) {
            }
        }

        val html = try {
            app.get(page, headers = hdr("$archiveUrl/")).text
        } catch (_: Exception) {
            return false
        }

        var found = false
        val added = HashSet<String>()
        val props = extractJsonObject(html, "window.__PROPS__")

        if (props != null && props.contains("encodes")) {
            val pairRe = Regex(
                "\"host\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]{0,250}?\"link\"\\s*:\\s*\"(/redirect/[a-f0-9]+)\""
            )
            for (block in props.split("\"resolution\"")) {
                val codec = Regex(
                    "\"readable\"\\s*:\\s*\\{[^}]*\"codec\"\\s*:\\s*\"([^\"]+)\""
                ).find(block)?.groupValues?.get(1) ?: ""

                for (m in pairRe.findAll(block)) {
                    val host = m.groupValues[1]
                    // Skip MEGA — not playable in CS player
                    if (host.equals("MEGA", true)) continue
                    val redir = archiveUrl + m.groupValues[2]
                    val label = if (codec.isNotBlank()) "$host $codec" else host
                    if (!added.add(redir)) continue
                    try {
                        if (openRedirect(redir, label, callback, subtitleCallback, added)) {
                            found = true
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }

        return found
    }

    /**
     * archive redirect page → domain+hidden → real host URL → extract video
     */
    private suspend fun openRedirect(
        redirectUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
        added: HashSet<String>
    ): Boolean {
        val body = try {
            app.get(redirectUrl, headers = hdr("$archiveUrl/")).text
        } catch (_: Exception) {
            return false
        }
        val props = extractJsonObject(body, "window.__PROPS__") ?: return false
        val domain = Regex("\"domain\"\\s*:\\s*\"([^\"]+)\"").find(props)?.groupValues?.get(1)
        val hidden = Regex("\"hidden\"\\s*:\\s*\"([^\"]+)\"").find(props)?.groupValues?.get(1)
        if (domain.isNullOrBlank() || hidden.isNullOrBlank()) return false

        val finalUrl = domain + hidden
        val low = finalUrl.lowercase()

        return when {
            "hubcloud" in low -> extractHubCloud(finalUrl, label, callback, subtitleCallback, added)
            "gdflix" in low || "gdtot" in low -> extractGdFlix(finalUrl, label, callback, subtitleCallback, added)
            "filepress" in low -> extractFilepress(finalUrl, label, callback, subtitleCallback, added)
            else -> {
                try {
                    loadExtractor(finalUrl, archiveUrl, subtitleCallback, callback)
                } catch (_: Exception) {
                    false
                }
            }
        }
    }

    /** HubCloud: #download → card buttons (FSL / Direct etc.) */
    private suspend fun extractHubCloud(
        url: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
        added: HashSet<String>
    ): Boolean {
        // Built-in extractor first
        try {
            if (loadExtractor(url, archiveUrl, subtitleCallback, callback)) return true
        } catch (_: Exception) {
        }

        var ok = false
        try {
            val doc = app.get(url, headers = hdr(archiveUrl)).document
            var href = doc.select("#download").attr("href")
            if (href.isBlank()) href = doc.selectFirst("a#download, a[href*=hubcloud.php]")?.attr("href").orEmpty()
            if (href.isNotBlank() && !href.startsWith("http")) {
                val base = Regex("https?://[^/]+").find(url)?.value ?: "https://hubcloud.cx"
                href = base.trimEnd('/') + "/" + href.trimStart('/')
            }
            if (href.isBlank()) return false

            val page = app.get(href, headers = hdr(url)).document
            for (a in page.select("div.card-body h2 a.btn, div.card-body a.btn, a.btn")) {
                val link = a.attr("abs:href").ifBlank { a.attr("href") }
                val text = a.text()
                if (!link.startsWith("http")) continue
                val tag = "$label · $text"
                // Prefer direct / FSL style links
                if (text.contains("FSL", true) || text.contains("Direct", true) ||
                    text.contains("Download", true) || text.contains("Server", true)
                ) {
                    if (link.contains(".mp4") || link.contains(".m3u8") ||
                        link.contains("worker") || link.contains("download")
                    ) {
                        if (pushVideo(callback, tag, link, href, added)) ok = true
                    } else {
                        try {
                            if (loadExtractor(link, href, subtitleCallback, callback)) ok = true
                        } catch (_: Exception) {
                        }
                        // follow redirect once
                        try {
                            val loc = app.get(link, headers = hdr(href)).url
                            if (loc != link && (loc.contains(".mp4") || loc.contains(".m3u8"))) {
                                if (pushVideo(callback, tag, loc, href, added)) ok = true
                            }
                        } catch (_: Exception) {
                        }
                    }
                } else {
                    try {
                        if (loadExtractor(link, href, subtitleCallback, callback)) ok = true
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        return ok
    }

    /** GDFlix: Cloud Download / Instant Download buttons */
    private suspend fun extractGdFlix(
        url: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
        added: HashSet<String>
    ): Boolean {
        // Try built-in / plugin extractors
        try {
            if (loadExtractor(url, archiveUrl, subtitleCallback, callback)) return true
        } catch (_: Exception) {
        }

        // Try common mirrors
        val id = url.substringAfterLast("/").substringBefore("?")
        val mirrors = listOf(
            url,
            "https://new6.gdflix.dad/file/$id",
            "https://gdflix.dad/file/$id",
            "https://gdflix.net/file/$id"
        )

        var ok = false
        for (pageUrl in mirrors) {
            try {
                val doc = app.get(pageUrl, headers = hdr(archiveUrl)).document
                // Cloud Download / direct buttons
                for (a in doc.select("div.text-center > a, a.btn, a[href]")) {
                    val text = a.text()
                    val href = a.attr("abs:href").ifBlank { a.attr("href") }
                    if (!href.startsWith("http")) continue

                    if (text.contains("Cloud Download", true) || text.contains("Direct", true)) {
                        if (pushVideo(callback, "$label · Cloud", href, pageUrl, added)) ok = true
                        else {
                            try {
                                if (loadExtractor(href, pageUrl, subtitleCallback, callback)) ok = true
                            } catch (_: Exception) {
                            }
                        }
                    }

                    if (text.contains("Instant Download", true)) {
                        try {
                            // Instant often redirects ?url=
                            val resp = app.get(href, headers = hdr(pageUrl))
                            val loc = resp.url
                            val real = Regex("[?&]url=([^&]+)").find(loc)?.groupValues?.get(1)
                                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                                ?: loc
                            if (real.startsWith("http") && real != pageUrl) {
                                if (pushVideo(callback, "$label · Instant", real, pageUrl, added)) {
                                    ok = true
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }

                    // Google Drive index style
                    if (href.contains("drive.google") || href.contains("workers.dev") ||
                        href.contains("gdflix") && href.contains("download")
                    ) {
                        try {
                            if (loadExtractor(href, pageUrl, subtitleCallback, callback)) ok = true
                        } catch (_: Exception) {
                        }
                    }
                }
                // Any mp4/m3u8 on page
                val html = doc.html()
                Regex("https?://[^\\s\"']+\\.mp4[^\\s\"']*").findAll(html).forEach { m ->
                    if (pushVideo(callback, "$label · MP4", m.value, pageUrl, added)) ok = true
                }
                if (ok) break
            } catch (_: Exception) {
            }
        }
        return ok
    }

    private suspend fun extractFilepress(
        url: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
        added: HashSet<String>
    ): Boolean {
        try {
            if (loadExtractor(url, archiveUrl, subtitleCallback, callback)) return true
        } catch (_: Exception) {
        }
        try {
            val doc = app.get(url, headers = hdr(archiveUrl)).document
            for (a in doc.select("a[href]")) {
                val href = a.attr("abs:href")
                val t = a.text()
                if (!href.startsWith("http")) continue
                if (t.contains("Download", true) || href.contains("download")) {
                    try {
                        if (loadExtractor(href, url, subtitleCallback, callback)) return true
                    } catch (_: Exception) {
                    }
                    if (href.contains(".mp4") || href.contains(".m3u8")) {
                        if (pushVideo(callback, label, href, url, added)) return true
                    }
                }
            }
        } catch (_: Exception) {
        }
        return false
    }

    private fun extractJsonObject(html: String, marker: String): String? {
        val idx = html.indexOf(marker)
        if (idx < 0) return null
        val start = html.indexOf('{', idx)
        if (start < 0) return null
        var depth = 0
        for (i in start until html.length) {
            val c = html[i]
            if (c == '{') depth++
            else if (c == '}') {
                depth--
                if (depth == 0) return html.substring(start, i + 1)
            }
        }
        return null
    }
}
