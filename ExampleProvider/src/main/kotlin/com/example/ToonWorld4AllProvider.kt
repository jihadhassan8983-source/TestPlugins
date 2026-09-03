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
        "$mainUrl/category/disney/" to "Disney",
        "$mainUrl/cartoon-shows-list_25/" to "Cartoons"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            request.data
        } else if (request.data.contains("category")) {
            request.data.trimEnd('/') + "/page/$page/"
        } else {
            "$mainUrl/page/$page/"
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
            val html = app.get("$mainUrl/?s=$q", headers = hdr()).text
            parseCards(html)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseCards(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html, mainUrl)
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        val anchors = doc.select(
            "article a[href], .herald-post a[href], .entry-title a[href], h2 a[href], h3 a[href]"
        )
        for (a in anchors) {
            var href = a.attr("abs:href")
            if (href.isBlank()) href = a.attr("href")
            href = href.substringBefore("?").trimEnd('/') + "/"
            if (!href.contains("toonworld4all.me")) continue
            if (anySkip(href)) continue
            if (!seen.add(href)) continue

            var title = a.text().trim()
            if (title.isBlank()) title = a.attr("title").trim()
            if (title.length < 3) continue

            var poster: String? = null
            val img = a.selectFirst("img")
            if (img != null) {
                poster = img.attr("abs:src")
                if (poster.isNullOrBlank()) poster = img.attr("src")
                if (poster.isNullOrBlank()) poster = img.attr("data-src")
            }
            if (poster.isNullOrBlank()) {
                val art = a.closest("article")
                val img2 = art?.selectFirst("img")
                if (img2 != null) {
                    poster = img2.attr("abs:src")
                    if (poster.isNullOrBlank()) poster = img2.attr("src")
                    if (poster.isNullOrBlank()) poster = img2.attr("data-src")
                }
            }

            val type = when {
                title.contains("Movie", true) -> TvType.Movie
                title.contains("Season", true) || title.contains("Episode", true) -> TvType.TvSeries
                else -> TvType.Anime
            }

            out.add(
                newAnimeSearchResponse(title, href, type) {
                    this.posterUrl = poster
                }
            )
            if (out.size >= 40) break
        }
        return out
    }

    private fun anySkip(href: String): Boolean {
        val h = href.lowercase()
        val bad = listOf(
            "/category/", "/tag/", "/author/", "/page/", "/feed",
            "wp-", "contact", "dmca", "how-to", "list_25", "shows-list",
            "exclusive", "first-on", "xmlrpc"
        )
        for (b in bad) {
            if (h.contains(b)) return true
        }
        return false
    }

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = url.substringBefore("?").trimEnd('/') + "/"
        val html = app.get(pageUrl, headers = hdr()).text
        val doc = Jsoup.parse(html, mainUrl)

        var title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim().orEmpty()
        if (title.isBlank()) {
            title = doc.selectFirst("title")?.text()
                ?.substringBefore("–")
                ?.substringBefore("|")
                ?.trim()
                .orEmpty()
        }

        var poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        if (poster.isNullOrBlank()) {
            poster = doc.selectFirst(".entry-content img, article img")?.attr("abs:src")
        }

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst(".entry-content p")?.text()

        val episodes = ArrayList<Episode>()
        val seen = HashSet<String>()

        for (a in doc.select(".entry-content a[href], article a[href]")) {
            var href = a.attr("abs:href")
            if (href.isBlank()) href = a.attr("href")

            val isEp = href.contains("archive.toonworld4all.me/episode/") ||
                href.contains("/episode/")
            if (!isEp) continue

            if (href.startsWith("/")) href = archiveUrl + href
            if (!href.startsWith("http")) continue
            if (!seen.add(href)) continue

            val slugPart = href.substringAfter("/episode/").substringBefore("?").trimEnd('/')
            var epNum: Int? = null
            val nm = Regex("(\\d+)x(\\d+)$", RegexOption.IGNORE_CASE).find(slugPart)
            if (nm != null) {
                epNum = nm.groupValues[2].toIntOrNull()
            }

            val name = a.text().trim().ifBlank {
                if (epNum != null) "Episode $epNum" else slugPart
            }

            episodes.add(
                newEpisode(href) {
                    this.name = name
                    this.episode = epNum
                    this.posterUrl = poster
                }
            )
        }

        if (episodes.isNotEmpty()) {
            episodes.sortBy { it.episode ?: 9999 }
            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
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
        if (!page.startsWith("http")) page = "\( mainUrl/ \){page.trimStart('/')}"

        if (page.contains("toonworld4all.me") && !page.contains("archive.")) {
            try {
                val html0 = app.get(page, headers = hdr()).text
                val doc0 = Jsoup.parse(html0, mainUrl)
                val ep = doc0.select(
                    ".entry-content a[href*=archive.toonworld4all], .entry-content a[href*=/episode/]"
                ).firstOrNull()?.attr("abs:href")
                if (!ep.isNullOrBlank()) page = ep
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

        val props = extractPropsJson(html)
        if (props != null) {
            val pairRe = Regex(
                "\"host\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]{0,200}?\"link\"\\s*:\\s*\"(/redirect/[a-f0-9]+)\""
            )
            val blocks = props.split("\"resolution\"")
            for (block in blocks) {
                val codec = Regex(
                    "\"readable\"\\s*:\\s*\\{[^}]*\"codec\"\\s*:\\s*\"([^\"]+)\""
                ).find(block)?.groupValues?.get(1)
                    ?: Regex("\"codec\"\\s*:\\s*\"([^\"]+)\"").find(block)?.groupValues?.get(1)
                    ?: ""

                for (m in pairRe.findAll(block)) {
                    val host = m.groupValues[1]
                    val link = m.groupValues[2]
                    val full = if (link.startsWith("http")) link else archiveUrl + link
                    val label = if (codec.isNotBlank()) "$host · $codec" else host
                    if (!added.add(full)) continue

                    try {
                        if (resolveAndExtract(full, label, callback, subtitleCallback, added)) {
                            found = true
                        }
                    } catch (_: Exception) {
                    }
                    try {
                        if (loadExtractor(full, archiveUrl, subtitleCallback, callback)) {
                            found = true
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }

        Regex("""href="(/redirect/[a-f0-9]+)"""").findAll(html).forEach { m ->
            val full = archiveUrl + m.groupValues[1]
            if (!added.add(full)) return@forEach
            try {
                if (resolveAndExtract(full, "Download", callback, subtitleCallback, added)) {
                    found = true
                }
            } catch (_: Exception) {
            }
        }

        return found
    }

    private fun extractPropsJson(html: String): String? {
        val marker = "window.__PROPS__"
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

    private suspend fun resolveAndExtract(
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

        var ok = false
        val hosts = listOf(
            "filepress", "gdflix", "gdtot", "drive.google", "mega.nz",
            "hubcloud", "pixeldrain", "workdrive", "video-seeds", "nexdrive"
        )
        val foundUrls = LinkedHashSet<String>()

        Regex("""https?://[^\"'\s<>]+""").findAll(body).forEach { m ->
            val u = m.value
            val low = u.lowercase()
            if (hosts.any { low.contains(it) }) {
                if (u.count { it == '/' } >= 3) {
                    foundUrls.add(u.trimEnd('"', '\'', ')', ','))
                }
            }
        }
        Regex("""href="(https?://[^"]+)"""").findAll(body).forEach { m ->
            val u = m.groupValues[1]
            val low = u.lowercase()
            if (hosts.any { low.contains(it) }) foundUrls.add(u)
        }

        for (u in foundUrls) {
            if (!added.add(u)) continue
            try {
                if (loadExtractor(u, archiveUrl, subtitleCallback, callback)) {
                    ok = true
                    continue
                }
            } catch (_: Exception) {
            }
            if (u.contains(".m3u8") || u.contains(".mp4")) {
                callback.invoke(
                    ExtractorLink(
                        name,
                        label,
                        u,
                        archiveUrl,
                        Qualities.Unknown.value,
                        u.contains(".m3u8")
                    )
                )
                ok = true
            }
        }
        return ok
    }
}
