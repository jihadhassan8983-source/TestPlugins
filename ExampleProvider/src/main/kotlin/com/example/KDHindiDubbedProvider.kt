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
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

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
        var u = url.trim().replace("&amp;", "&").replace("\\/", "/")
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

    private fun yearFrom(text: String): Int? =
        Regex("""\b(20\d{2})\b""").find(text)?.value?.toIntOrNull()

    private fun isMovieTitle(t: String): Boolean {
        val s = t.lowercase()
        return (s.contains("movie") || s.contains("moive")) && !s.contains("episode")
    }

    private fun pickImg(el: Element): String? {
        for (img in el.select("img")) {
            for (x in listOf(img.attr("src"), img.attr("data-src"), img.attr("data-lazy-src"))) {
                val u = abs(x) ?: continue
                if (u.startsWith("http") && !u.contains("data:image") && !u.endsWith(".svg")) return u
            }
        }
        return null
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        for (a in doc.select("h2.entry-title a[href], h1.entry-title a[href], .entry-title a[href]")) {
            val href = abs(a.attr("abs:href").ifBlank { a.attr("href") }) ?: continue
            if (!href.contains("kdhindidubbed")) continue
            if (href.contains("/category/") || href.contains("/tag/") || href.contains("/page/")) continue
            if (!seen.add(href)) continue
            val titleRaw = a.text().trim()
            if (titleRaw.isBlank()) continue
            val title = cleanTitle(titleRaw)
            val parent = a.parents().firstOrNull { it.selectFirst("img") != null } ?: a.parent()
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
        val url = when {
            page <= 1 -> request.data
            request.data.endsWith("/") -> request.data + "page/" + page + "/"
            else -> request.data + "/page/" + page + "/"
        }
        val doc = app.get(url, headers = hdr()).document
        return newHomePageResponse(request.name, parseCards(doc), true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val doc = app.get(mainUrl + "/?s=" + URLEncoder.encode(q, "UTF-8"), headers = hdr()).document
        return parseCards(doc)
    }

    private fun findDownloadPages(doc: Document): List<String> {
        val out = ArrayList<String>()
        for (a in doc.select("a[href]")) {
            val text = a.text().trim().lowercase()
            val href = abs(a.attr("abs:href").ifBlank { a.attr("href") }) ?: continue
            if (!href.contains("kdhindidubbed")) continue
            if (text.contains("download link")) {
                out.add(href)
            }
        }
        if (out.isEmpty()) {
            for (a in doc.select(".entry-content a[href], article a[href]")) {
                val href = abs(a.attr("abs:href").ifBlank { a.attr("href") }) ?: continue
                if (!href.startsWith(mainUrl)) continue
                val path = href.removePrefix(mainUrl).trim('/')
                if (path.isNotEmpty() && !path.contains("/") && path.length in 8..50) {
                    out.add(href)
                }
            }
        }
        return out.distinct()
    }

    /** Returns episode -> list of (label, url) */
    private fun parseEpisodeServers(htmlRaw: String): List<Pair<Int, List<Pair<String, String>>>> {
        val html = htmlRaw
            .replace("&#8211;", "–")
            .replace("&ndash;", "–")
            .replace("&#8212;", "–")
            .replace("&amp;", "&")

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
                """href\s*=\s*["']\s*(https?://[^"']+)["'][^>]*>([\s\S]*?)</a>""",
                RegexOption.IGNORE_CASE
            )
            for (lm in linkRegex.findAll(block)) {
                val url = lm.groupValues[1].trim()
                val label = lm.groupValues[2].replace(Regex("""<[^>]+>"""), "").trim()
                val h = url.lowercase()
                if (h.contains("gkyfilehost") ||
                    h.contains("momofile") ||
                    h.contains("xcloud") ||
                    h.contains("fpgo") ||
                    h.contains("p2pstream") ||
                    h.contains("rpmplay") ||
                    h.contains("chuckle")
                ) {
                    servers.add((label.ifBlank { "Server" }) to url)
                }
            }
            if (servers.isNotEmpty()) out.add(ep to servers)
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

        var plot: String? = null
        val tags = ArrayList<String>()
        val content = doc.selectFirst(".entry-content, article")
        if (content != null) {
            for (line in content.text().split('\n', '.')) {
                val l = line.trim()
                if (l.startsWith("Genres:", true)) {
                    tags += l.substringAfter(":").split(",", "/")
                        .map { it.trim() }.filter { it.length in 2..30 }
                }
            }
            for (p in content.select("p, div.separator span")) {
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

        val pages = if (downloadPages.isNotEmpty()) downloadPages else listOf(url)
        for (pageUrl in pages) {
            try {
                val html = app.get(pageUrl, headers = hdr(url)).text
                for ((epNum, servers) in parseEpisodeServers(html)) {
                    if (!seenEp.add(epNum)) continue
                    // Put GKY first in payload so loadLinks prioritizes it
                    val sorted = servers.sortedBy { s ->
                        val u = s.second.lowercase()
                        when {
                            u.contains("gkyfilehost") -> 0
                            u.contains("momofile") -> 1
                            else -> 5
                        }
                    }
                    val payload = sorted.joinToString("||") { it.first + "::" + it.second }
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
            for (pageUrl in pages.take(1)) {
                try {
                    val html = app.get(pageUrl, headers = hdr(url)).text
                    val servers = ArrayList<Pair<String, String>>()
                    val linkRegex = Regex(
                        """href\s*=\s*["']\s*(https?://[^"']+)["'][^>]*>([\s\S]*?)</a>""",
                        RegexOption.IGNORE_CASE
                    )
                    for (lm in linkRegex.findAll(html)) {
                        val href = lm.groupValues[1].trim()
                        val label = lm.groupValues[2].replace(Regex("""<[^>]+>"""), "").trim()
                        val h = href.lowercase()
                        if (h.contains("gkyfilehost") || h.contains("momofile")) {
                            servers.add(label.ifBlank { "Server" } to href)
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

    /**
     * GKY flow (verified):
     * file/ID → generate.php?id=ID → /r2cloud/ID/TOKEN page → downloadUrl = "https://pub-....r2.dev/..."
     * R2 supports HEAD + Range → ExoPlayer works
     */
    private suspend fun resolveGky(fileUrl: String): String? {
        try {
            val id = Regex("""/file/(\d+)""").find(fileUrl)?.groupValues?.getOrNull(1) ?: return null
            val base = Regex("""(https?://[^/]+)""").find(fileUrl)?.groupValues?.getOrNull(1)
                ?: "https://new1.gkyfilehost.lol"

            val genResp = app.get(
                base + "/generate.php?id=" + id,
                headers = hdr(fileUrl),
                allowRedirects = true
            )
            val genHtml = genResp.text
            val genFinal = genResp.url

            val pathPatterns = listOf(
                Regex("""delayedDownload\([^,]+,\s*'(/r2cloud/[^']+)'"""),
                Regex("""delayedDownload\([^,]+,\s*"(/r2cloud/[^"]+)""""),
                Regex("""['"](/r2cloud/\d+/[A-Za-z0-9]+)['"]"""),
                Regex("""(/r2cloud/\d+/[A-Za-z0-9]+)""")
            )
            var r2path: String? = null
            for (p in pathPatterns) {
                val m = p.find(genHtml)
                if (m != null) {
                    r2path = m.groupValues[1].trim()
                    break
                }
            }
            if (r2path == null) return null

            val r2Html = app.get(
                base + r2path,
                headers = hdr(genFinal),
                allowRedirects = true
            ).text

            val urlPatterns = listOf(
                Regex("""downloadUrl\s*=\s*"([^"]+)""""),
                Regex("""downloadUrl\s*=\s*'([^']+)'"""),
                Regex("""(https://pub-[a-z0-9]+\.r2\.dev/[a-zA-Z0-9]+)""")
            )
            for (p in urlPatterns) {
                val m = p.find(r2Html)
                if (m != null) {
                    val u = m.groupValues[1].replace("\\/", "/").trim()
                    if (u.startsWith("http")) return u
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private suspend fun resolveMomo(momoUrl: String): String? {
        return try {
            val html = app.get(momoUrl, headers = hdr(mainUrl + "/")).text
            val m = Regex("""download\.php\?t=[^"'\s&]+""").find(html) ?: return null
            val path = m.value
            if (path.startsWith("http")) path else "https://momofile.shop/" + path
        } catch (_: Exception) {
            null
        }
    }

    private fun push(
        callback: (ExtractorLink) -> Unit,
        label: String,
        url: String,
        referer: String
    ) {
        callback(
            ExtractorLink(
                name,
                label,
                url,
                referer,
                Qualities.P720.value,
                false
            )
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val servers = ArrayList<Pair<String, String>>()

        for (p in data.split("||")) {
            val part = p.trim()
            if (part.isEmpty()) continue
            if ("::" in part) {
                val label = part.substringBefore("::").trim()
                val link = part.substringAfter("::").trim()
                if (link.startsWith("http")) servers.add(label to link)
            } else if (part.startsWith("http")) {
                servers.add("Server" to part)
            }
        }

        // Fallback: data is drama page / download page
        if (servers.isEmpty() && data.startsWith("http")) {
            try {
                val html = app.get(data, headers = hdr()).text
                val pages = findDownloadPages(
                    app.get(data, headers = hdr()).document
                )
                val target = pages.firstOrNull() ?: data
                val pageHtml = app.get(target, headers = hdr(data)).text
                for ((_, list) in parseEpisodeServers(pageHtml)) {
                    servers.addAll(list)
                }
            } catch (_: Exception) {
            }
        }

        if (servers.isEmpty()) return false

        var found = false

        // 1) ALL GKY links first (best for ExoPlayer)
        for ((label, link) in servers) {
            if (!link.contains("gkyfilehost", true)) continue
            try {
                val direct = resolveGky(link)
                if (direct != null) {
                    push(callback, (label.ifBlank { "GKY" }) + " • HD", direct, "https://new1.gkyfilehost.lol/")
                    found = true
                }
            } catch (_: Exception) {
            }
        }

        // 2) MomoFile backup (may fail on some devices due to no HEAD/Range)
        for ((label, link) in servers) {
            if (!link.contains("momofile", true)) continue
            try {
                val direct = resolveMomo(link)
                if (direct != null) {
                    push(callback, label.ifBlank { "MomoFile" }, direct, link)
                    found = true
                }
            } catch (_: Exception) {
            }
        }

        return found
    }
                }
