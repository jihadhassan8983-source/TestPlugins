@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class KDHindiDubbedProvider : MainAPI() {
    override var mainUrl = "https://kdhindidubbed.cfd"
    override var name = "KDHindiDubbed"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private fun hdr(referer: String = mainUrl + "/"): Map<String, String> {
        return mapOf(
            "User-Agent" to ua,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9,hi;q=0.8",
            "Referer" to referer
        )
    }

    private fun abs(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var u = url.trim().replace("&amp;", "&")
        if (u.startsWith("//")) u = "https:" + u
        if (u.startsWith("http")) return u
        return mainUrl.trimEnd('/') + "/" + u.trimStart('/')
    }

    private fun cleanTitle(raw: String): String {
        var t = raw
            .replace(Regex("""(?i)\s*\[.*?\]"""), "")
            .replace(Regex("""(?i)\s*\(Korean Drama\).*"""), "")
            .replace(Regex("""(?i)\s*\(Korean Moive\).*"""), "")
            .replace(Regex("""(?i)\s*Hindi.*?Dubbed.*"""), "")
            .replace(Regex("""(?i)\s*Episodes?\s*$"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
        return t.ifBlank { raw.trim() }
    }

    private fun yearFrom(text: String): Int? {
        return Regex("""\b(20\d{2})\b""").find(text)?.value?.toIntOrNull()
    }

    private fun isMovieTitle(t: String): Boolean {
        val s = t.lowercase()
        return (s.contains("movie") || s.contains("moive")) && !s.contains("episode")
    }

    private fun pickImg(el: Element): String? {
        for (img in el.select("img")) {
            val c = listOf(img.attr("src"), img.attr("data-src"), img.attr("data-lazy-src"))
            for (x in c) {
                val u = abs(x) ?: continue
                if (u.contains("http") && !u.contains("data:image") && !u.endsWith(".svg")) {
                    return u
                }
            }
        }
        return null
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        for (a in doc.select("h2.entry-title a[href], h1.entry-title a[href], .entry-title a[href]")) {
            val href = abs(a.attr("abs:href").ifBlank { a.attr("href") }) ?: continue
            if (!href.contains(mainUrl.replace("https://", ""))) continue
            if (href.contains("/category/") || href.contains("/tag/") || href.contains("/page/")) continue
            if (!seen.add(href)) continue
            val titleRaw = a.text().trim()
            if (titleRaw.isBlank()) continue
            val title = cleanTitle(titleRaw)
            val parent = a.parents().firstOrNull {
                it.selectFirst("img") != null
            } ?: a.parent()
            val poster = parent?.let { pickImg(it) }
            val year = yearFrom(titleRaw)
            if (isMovieTitle(titleRaw)) {
                out += newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                }
            } else {
                out += newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                }
            }
        }
        return out
    }

    override val mainPage = mainPageOf(
        mainUrl + "/" to "Latest",
        mainUrl + "/category/korean-drama/" to "Korean Drama",
        mainUrl + "/category/chinese-drama/" to "Chinese Drama",
        mainUrl + "/category/english-dubbed/" to "English Dubbed",
        mainUrl + "/category/action/" to "Action",
        mainUrl + "/category/romance/" to "Romance",
        mainUrl + "/category/thriller/" to "Thriller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            request.data
        } else if (request.data.endsWith("/")) {
            request.data + "page/" + page + "/"
        } else {
            request.data + "/page/" + page + "/"
        }
        val doc = app.get(url, headers = hdr()).document
        val list = parseCards(doc)
        return newHomePageResponse(request.name, list, list.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val doc = app.get(
            mainUrl + "/?s=" + URLEncoder.encode(q, "UTF-8"),
            headers = hdr()
        ).document
        return parseCards(doc)
    }

    /** Find intermediate DOWNLOAD LINKS pages (720p / 1080p etc.) */
    private fun findDownloadPages(doc: Document): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (a in doc.select("a[href]")) {
            val text = a.text().trim().lowercase()
            val href = abs(a.attr("abs:href").ifBlank { a.attr("href") }) ?: continue
            if (!href.contains(mainUrl.replace("https://", ""))) continue
            if (text.contains("download link") || text == "download links" ||
                (text.contains("download") && !text.contains("how to"))
            ) {
                val label = a.text().trim().ifBlank { "Download" }
                out.add(label to href)
            }
        }
        // fallback: short random-path links near download text
        if (out.isEmpty()) {
            for (a in doc.select(".entry-content a[href], article a[href]")) {
                val href = abs(a.attr("abs:href").ifBlank { a.attr("href") }) ?: continue
                if (!href.startsWith(mainUrl)) continue
                val path = href.removePrefix(mainUrl).trim('/')
                if (path.isNotEmpty() && !path.contains("/") && path.length in 8..40) {
                    out.add(a.text().ifBlank { "Links" } to href)
                }
            }
        }
        return out.distinctBy { it.second }
    }

    /**
     * Parse episode blocks: Episode – 01 + MomoFile / XCloud / Stream links
     * Returns list of (episodeNumber, list of (label, url))
     */
    private fun parseEpisodeServers(doc: Document): List<Pair<Int, List<Pair<String, String>>>> {
        val html = doc.html()
            .replace("&#8211;", "–")
            .replace("&ndash;", "–")
            .replace("&#8212;", "–")

        val out = ArrayList<Pair<Int, List<Pair<String, String>>>>()
        val regex = Regex(
            """Episode\s*[–\-]\s*(\d+)([\s\S]*?)(?=Episode\s*[–\-]|\z)""",
            RegexOption.IGNORE_CASE
        )
        for (m in regex.findAll(html)) {
            val ep = m.groupValues[1].toIntOrNull() ?: continue
            val block = m.groupValues[2]
            val servers = ArrayList<Pair<String, String>>()
            val linkRegex = Regex(
                """href=["']\s*(https?://[^"']+)["'][^>]*>([\s\S]*?)</a>""",
                RegexOption.IGNORE_CASE
            )
            for (lm in linkRegex.findAll(block)) {
                val url = lm.groupValues[1].trim()
                val label = lm.groupValues[2].replace(Regex("""<[^>]+>"""), "").trim()
                if (url.contains("momofile", true) ||
                    url.contains("xcloud", true) ||
                    url.contains("hgcloud", true) ||
                    url.contains("chuckle", true) ||
                    url.contains("fpgo", true)
                ) {
                    servers.add((label.ifBlank { "Server" }) to url)
                }
            }
            if (servers.isNotEmpty()) {
                out.add(ep to servers)
            }
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = hdr()).document
        val titleRaw = doc.selectFirst("h1.entry-title, h1")?.text() ?: "Unknown"
        val title = cleanTitle(titleRaw)
        val poster = abs(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: pickImg(doc.body())
        val year = yearFrom(titleRaw)

        // plot / meta from content
        val content = doc.selectFirst(".entry-content, article")
        var plot: String? = null
        val tags = ArrayList<String>()
        if (content != null) {
            val text = content.text()
            for (line in text.split('\n', '.')) {
                val l = line.trim()
                if (l.startsWith("Genres:", true)) {
                    tags += l.substringAfter(":").split(",", "/").map { it.trim() }.filter { it.length in 2..30 }
                }
            }
            val paras = content.select("p, div.separator span")
            for (p in paras) {
                val t = p.text().trim()
                if (t.length > 80 && !t.contains("Download", true) && !t.contains("Episode", true)) {
                    plot = t
                    break
                }
            }
        }

        val downloadPages = findDownloadPages(doc)
        val episodes = ArrayList<Episode>()
        val seenEp = HashSet<Int>()

        // Prefer first download page for episode list (usually 720p pack)
        val pagesToScan = if (downloadPages.isNotEmpty()) downloadPages else listOf("Main" to url)
        for ((pageLabel, pageUrl) in pagesToScan.take(2)) {
            try {
                val pdoc = app.get(pageUrl, headers = hdr(url)).document
                val parsed = parseEpisodeServers(pdoc)
                for ((epNum, servers) in parsed) {
                    if (!seenEp.add(epNum)) continue
                    // Prefer MomoFile link for data
                    val momo = servers.firstOrNull { it.second.contains("momofile", true) }
                    val primary = momo ?: servers.firstOrNull() ?: continue
                    // Store all servers as data payload
                    val payload = servers.joinToString("||") { it.first + "::" + it.second }
                    episodes += newEpisode(payload) {
                        this.name = "Episode " + epNum
                        this.episode = epNum
                        this.data = payload
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (episodes.isEmpty() && isMovieTitle(titleRaw)) {
            // movie: try same download pages as single episode
            for ((_, pageUrl) in downloadPages.take(1)) {
                try {
                    val pdoc = app.get(pageUrl, headers = hdr(url)).document
                    val servers = ArrayList<Pair<String, String>>()
                    for (a in pdoc.select(".entry-content a[href]")) {
                        val href = abs(a.attr("abs:href").ifBlank { a.attr("href") }) ?: continue
                        val label = a.text().trim().ifBlank { "Server" }
                        if (href.contains("momofile") || href.contains("xcloud") || href.contains("hgcloud")) {
                            servers.add(label to href)
                        }
                    }
                    if (servers.isNotEmpty()) {
                        val payload = servers.joinToString("||") { it.first + "::" + it.second }
                        return newMovieLoadResponse(title, url, TvType.Movie, payload) {
                            this.posterUrl = poster
                            this.plot = plot
                            this.year = year
                            this.tags = tags.distinct()
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        if (episodes.isEmpty()) {
            episodes += newEpisode(url) {
                this.name = "Episode 1"
                this.episode = 1
                this.data = url
            }
        }

        episodes.sortBy { it.episode }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags.distinct()
        }
    }

    /** Resolve MomoFile page → direct mkv URL */
    private suspend fun resolveMomo(momoUrl: String): String? {
        return try {
            val doc = app.get(momoUrl, headers = hdr(mainUrl + "/")).document
            val a = doc.selectFirst("a[href*=download.php]")
            val href = a?.attr("abs:href")?.ifBlank { a.attr("href") }
                ?: Regex("""download\.php\?t=[^"'\s]+""").find(doc.html())?.value?.let {
                    "https://momofile.shop/" + it
                }
            val full = abs(href) ?: return null
            if (full.contains("download.php")) full else null
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data = "MomoFile::https://momofile...||XCloud::https://..."
        val parts = data.split("||").map { it.trim() }.filter { it.isNotBlank() }
        val servers = ArrayList<Pair<String, String>>()
        for (p in parts) {
            if ("::" in p) {
                val label = p.substringBefore("::").trim()
                val link = p.substringAfter("::").trim()
                if (link.startsWith("http")) servers.add(label to link)
            } else if (p.startsWith("http")) {
                servers.add("Server" to p)
            }
        }

        if (servers.isEmpty()) {
            // data might be a download page or drama url
            try {
                val doc = app.get(data, headers = hdr()).document
                val pages = findDownloadPages(doc)
                val target = pages.firstOrNull()?.second ?: data
                val pdoc = app.get(target, headers = hdr(data)).document
                val parsed = parseEpisodeServers(pdoc)
                for ((_, list) in parsed) {
                    servers.addAll(list)
                }
                if (servers.isEmpty()) {
                    for (a in pdoc.select("a[href*=momofile]")) {
                        val href = abs(a.attr("abs:href").ifBlank { a.attr("href") }) ?: continue
                        servers.add("MomoFile" to href)
                    }
                }
            } catch (_: Exception) {
            }
        }

        var found = false

        // Priority: MomoFile first (verified direct mkv)
        val ordered = servers.sortedByDescending {
            it.second.contains("momofile", true)
        }

        for ((label, link) in ordered) {
            try {
                if (link.contains("momofile", true)) {
                    val direct = resolveMomo(link) ?: continue
                    callback(
                        ExtractorLink(
                            name,
                            label.ifBlank { "MomoFile" },
                            direct,
                            "https://momofile.shop/",
                            Qualities.P720.value,
                            false
                        )
                    )
                    found = true
                }
                // Other hosts skipped for reliability (xcloud 403, hgcloud JS-only)
            } catch (_: Exception) {
            }
        }

        return found
    }
}
