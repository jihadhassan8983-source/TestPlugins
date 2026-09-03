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
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

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
        val keys = listOf(
            "abs:src", "src", "data-src", "data-lazy-src",
            "data-original", "data-lazy", "data-bg"
        )
        for (k in keys) {
            val v = if (k.startsWith("abs:")) img.attr(k) else img.attr(k)
            if (!v.isNullOrBlank() && v.startsWith("http") && !v.contains("data:image")) {
                return v
            }
        }
        // srcset first url
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

        // Prefer article blocks so poster is from same card
        val articles = doc.select("article")
        if (articles.isNotEmpty()) {
            for (art in articles) {
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
                if (title.length < 3) continue
                if (title.contains("Comments", true)) continue

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
        }

        if (out.isEmpty()) {
            for (a in doc.select("h2 a[href], h3 a[href], .entry-title a[href]")) {
                var href = a.attr("abs:href")
                if (href.isBlank()) href = a.attr("href")
                href = href.substringBefore("#").substringBefore("?").trimEnd('/') + "/"
                if (!href.contains("toonworld4all.me")) continue
                if (shouldSkip(href)) continue
                if (!seen.add(href)) continue
                var title = a.text().trim()
                if (title.isBlank() || title.contains("Comments", true)) continue
                val poster = pickPoster(a.selectFirst("img"))
                out.add(newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                })
            }
        }
        return out
    }

    private fun shouldSkip(href: String): Boolean {
        val h = href.lowercase()
        return h.contains("/category/") ||
            h.contains("/tag/") ||
            h.contains("/author/") ||
            h.contains("/page/") ||
            h.contains("/feed") ||
            h.contains("wp-") ||
            h.contains("contact") ||
            h.contains("dmca") ||
            h.contains("how-to") ||
            h.contains("list_25") ||
            h.contains("shows-list") ||
            h.contains("xmlrpc") ||
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

            if (!href.contains("archive.toonworld4all.me/episode/") &&
                !href.contains("/episode/")
            ) continue

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

            episodes.add(
                newEpisode(href) {
                    this.name = epName
                    this.episode = epNum
                    this.posterUrl = poster
                }
            )
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var page = data.trim()
        if (page.startsWith("/episode/")) page = archiveUrl + page
        if (!page.startsWith("http")) page = "$mainUrl/" + page.trimStart('/')

        // Movie post -> first archive episode
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

        // Episode page: list of encodes -> /redirect/TOKEN
        if (props != null && props.contains("encodes")) {
            val pairRe = Regex(
                "\"host\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]{0,250}?\"link\"\\s*:\\s*\"(/redirect/[a-f0-9]+)\""
            )
            val blocks = props.split("\"resolution\"")
            for (block in blocks) {
                val codec = Regex(
                    "\"readable\"\\s*:\\s*\\{[^}]*\"codec\"\\s*:\\s*\"([^\"]+)\""
                ).find(block)?.groupValues?.get(1) ?: ""

                for (m in pairRe.findAll(block)) {
                    val host = m.groupValues[1]
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

        // Fallback any /redirect/
        val redirRe = Regex("href=\"(/redirect/[a-f0-9]+)\"")
        for (m in redirRe.findAll(html)) {
            val redir = archiveUrl + m.groupValues[1]
            if (!added.add(redir)) continue
            try {
                if (openRedirect(redir, "Server", callback, subtitleCallback, added)) {
                    found = true
                }
            } catch (_: Exception) {
            }
        }

        return found
    }

    /**
     * Redirect page has:
     * window.__PROPS__ = {"link":{"domain":"https://gdflix.dev/file/","hidden":"abc123"},...}
     * Real URL = domain + hidden
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
        if (!added.add(finalUrl)) return false

        // Prefer extractor (HubCloud / GDFlix / Filepress)
        try {
            if (loadExtractor(finalUrl, archiveUrl, subtitleCallback, callback)) {
                return true
            }
        } catch (_: Exception) {
        }

        // Also try without path quirks
        val alt = finalUrl
            .replace("hubcloud.cx", "hubcloud.one")
            .replace("gdflix.dev", "new6.gdflix.dad")
        if (alt != finalUrl) {
            try {
                if (loadExtractor(alt, archiveUrl, subtitleCallback, callback)) {
                    return true
                }
            } catch (_: Exception) {
            }
        }

        // Last resort: expose as direct link so user can see source exists
        callback.invoke(
            ExtractorLink(
                name,
                label,
                finalUrl,
                archiveUrl,
                Qualities.Unknown.value,
                false
            )
        )
        return true
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
