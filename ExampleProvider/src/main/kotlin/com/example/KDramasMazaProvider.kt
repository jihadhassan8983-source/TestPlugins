@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class KDramasMazaProvider : MainAPI() {
    override var mainUrl = "https://kdramasmaza.net"
    override var name = "KDramasMaza"
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
            .replace(Regex("""(?i)\s*[–\-]\s*Complete.*$"""), "")
            .replace(Regex("""(?i)\s*[–\-]\s*KDramas Maza.*$"""), "")
            .replace(Regex("""(?i)\s*\[.*?\]"""), "")
            .replace(Regex("""(?i)\s*in Urdu.*$"""), "")
            .replace(Regex("""(?i)\s*in Hindi.*$"""), "")
            .replace(Regex("""(?i)\s*Episode.*$"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
        return t.ifBlank { raw.trim() }
    }

    private fun yearFrom(text: String): Int? =
        Regex("""\b(20\d{2})\b""").find(text)?.value?.toIntOrNull()

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
            if (!href.contains("kdramasmaza.net")) continue
            if (href.contains("/category/") || href.contains("/tag/") || href.contains("/page/")) continue
            if (!seen.add(href)) continue
            val titleRaw = a.text().trim()
            if (titleRaw.isBlank()) continue
            val title = cleanTitle(titleRaw)
            val parent = a.parents().firstOrNull { it.selectFirst("img") != null } ?: a.parent()
            val poster = parent?.let { pickImg(it) }
            out += newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = yearFrom(titleRaw)
            }
        }
        return out
    }

    override val mainPage = mainPageOf(
        mainUrl + "/" to "Latest",
        mainUrl + "/category/korean-dramas/" to "Korean Dramas",
        mainUrl + "/category/korean-dramas-in-english-dubbed/" to "English Dubbed",
        mainUrl + "/category/turkish-dramas-in-urdu-hindi-dubbed/" to "Turkish",
        mainUrl + "/category/zzaction/" to "Action",
        mainUrl + "/category/zzromantic/" to "Romance",
        mainUrl + "/category/zzthriller/" to "Thriller",
        mainUrl + "/category/anime-in-hindi-dubbed/" to "Anime Hindi"
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
        val doc = app.get(
            mainUrl + "/?s=" + URLEncoder.encode(q, "UTF-8"),
            headers = hdr()
        ).document
        return parseCards(doc)
    }

    /**
     * Collect archive links. Prefer "All Episodes Wise" over "Zip Download".
     * Returns sorted list: wise first, then others.
     */
    private fun findArchiveLinks(html: String): List<String> {
        val scored = ArrayList<Pair<Int, String>>()

        // <button onclick="window.location.href='URL'">Label</button>
        val btnRegex = Regex(
            """<button([^>]*)>([\s\S]*?)</button>""",
            RegexOption.IGNORE_CASE
        )
        for (m in btnRegex.findAll(html)) {
            val attrs = m.groupValues[1]
            val label = m.groupValues[2].replace(Regex("""<[^>]+>"""), "").trim().lowercase()
            val href = Regex(
                """(?:location\.href|window\.location)\s*=\s*['"](https?://kdramasmaza\.com\.pk/archives/\d+)['"]""",
                RegexOption.IGNORE_CASE
            ).find(attrs)?.groupValues?.getOrNull(1) ?: continue

            val score = when {
                label.contains("episode") && label.contains("wise") -> 0
                label.contains("wise") -> 0
                label.contains("zip") -> 2
                else -> 1
            }
            scored.add(score to href)
        }

        // plain href fallback
        for (m in Regex(
            """href\s*=\s*["'](https?://kdramasmaza\.com\.pk/archives/\d+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(html)) {
            val href = m.groupValues[1]
            if (scored.none { it.second == href }) {
                scored.add(1 to href)
            }
        }

        // onclick without button wrapper
        for (m in Regex(
            """(?:location\.href|window\.location)\s*=\s*['"](https?://kdramasmaza\.com\.pk/archives/\d+)['"]""",
            RegexOption.IGNORE_CASE
        ).findAll(html)) {
            val href = m.groupValues[1]
            if (scored.none { it.second == href }) {
                scored.add(1 to href)
            }
        }

        return scored.sortedBy { it.first }.map { it.second }.distinct()
    }

    /**
     * Parse by <span class="ep-no">Episode 01</span> blocks (most reliable).
     * Fallback: Episode N text split.
     */
    private fun parseEpisodes(html: String): List<Pair<Int, List<Pair<String, String>>>> {
        val out = ArrayList<Pair<Int, List<Pair<String, String>>>>()

        // Method 1: ep-no spans
        val parts = Regex(
            """<span[^>]*class=["'][^"']*ep-no[^"']*["'][^>]*>\s*(Episode\s*\d+)\s*</span>""",
            RegexOption.IGNORE_CASE
        ).split(html)

        val labels = Regex(
            """<span[^>]*class=["'][^"']*ep-no[^"']*["'][^>]*>\s*Episode\s*(\d+)\s*</span>""",
            RegexOption.IGNORE_CASE
        ).findAll(html).map { it.groupValues[1].toIntOrNull() }.filterNotNull().toList()

        if (labels.isNotEmpty() && parts.size >= 2) {
            // split() result: [before, after1, after2, ...] corresponding to each match
            for (i in labels.indices) {
                val ep = labels[i]
                val block = if (i + 1 < parts.size) parts[i + 1] else ""
                // only until next episode-row roughly - take limited chunk
                val chunk = block.take(2500)
                val servers = extractServers(chunk)
                if (servers.isNotEmpty()) {
                    out.add(ep to servers)
                }
            }
            if (out.isNotEmpty()) return out
        }

        // Method 2: Episode N text
        val epRegex = Regex(
            """Episode\s*0*(\d+)([\s\S]*?)(?=Episode\s*0*\d+|\z)""",
            RegexOption.IGNORE_CASE
        )
        for (m in epRegex.findAll(html)) {
            val ep = m.groupValues[1].toIntOrNull() ?: continue
            val servers = extractServers(m.groupValues[2].take(2500))
            if (servers.isNotEmpty()) out.add(ep to servers)
        }
        return out
    }

    private fun extractServers(block: String): List<Pair<String, String>> {
        val servers = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()

        // HubCloud
        for (m in Regex(
            """https?://(?:www\.)?hubcloud\.[a-z]+/drive/[a-zA-Z0-9]+""",
            RegexOption.IGNORE_CASE
        ).findAll(block)) {
            val u = m.value
            if (seen.add(u)) servers.add("HubCloud" to u)
        }

        // GDFlix
        for (m in Regex(
            """https?://(?:new\d+\.)?gdflix\.[a-z]+/file/[a-zA-Z0-9]+""",
            RegexOption.IGNORE_CASE
        ).findAll(block)) {
            val u = m.value
            if (seen.add(u)) servers.add("GDFlix" to u)
        }

        // DriveHub
        for (m in Regex(
            """https?://(?:new\d+\.)?drivehub\.[a-z]+/[^\s"']+""",
            RegexOption.IGNORE_CASE
        ).findAll(block)) {
            val u = m.value.trimEnd('/', '"', '\'')
            if (seen.add(u)) servers.add("DriveHub" to u)
        }

        // HubDrive
        for (m in Regex(
            """https?://hubdrive\.[a-z]+/[^\s"']+""",
            RegexOption.IGNORE_CASE
        ).findAll(block)) {
            val u = m.value.trimEnd('/', '"', '\'')
            if (seen.add(u)) servers.add("HubDrive" to u)
        }

        return servers
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = hdr()).document
        val html = doc.html()
        val titleRaw = doc.selectFirst("h1.entry-title, h1")?.text() ?: "Unknown"
        val title = cleanTitle(titleRaw)
        val poster = abs(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: pickImg(doc.body())
        val year = yearFrom(titleRaw)

        var plot: String? = null
        val tags = ArrayList<String>()
        val content = doc.selectFirst(".entry-content, article")
        if (content != null) {
            val text = content.text()
            if (text.contains("Genres:", true)) {
                val g = text.substringAfter("Genres:", "").substringBefore("Download").trim()
                tags += g.split(",", " ").map { it.trim() }.filter { it.length in 3..24 }.take(10)
            }
            val p = content.selectFirst("p")?.text()?.trim()
            if (p != null && p.length > 40) plot = p
        }

        val archives = findArchiveLinks(html)
        val episodes = ArrayList<Episode>()
        val seen = HashSet<Int>()

        // Try ALL archives (wise first). Merge episodes, don't stop early on weak pages.
        for (archUrl in archives) {
            try {
                val archHtml = app.get(archUrl, headers = hdr(url)).text
                val parsed = parseEpisodes(archHtml)
                for ((epNum, servers) in parsed) {
                    if (!seen.add(epNum)) continue
                    val sorted = servers.sortedBy { s ->
                        when {
                            s.second.contains("hubcloud", true) -> 0
                            s.second.contains("gdflix", true) -> 1
                            else -> 2
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

        if (episodes.isEmpty()) {
            // last resort: store first archive URL so loadLinks can re-parse
            val fallback = archives.firstOrNull() ?: url
            episodes += newEpisode(fallback) {
                this.name = "Episode 1"
                this.episode = 1
                this.data = fallback
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

    /** HubCloud → gamerxyt → R2 direct (playable) */
    private suspend fun resolveHubCloud(driveUrl: String): String? {
        return try {
            val page = app.get(driveUrl, headers = hdr("https://kdramasmaza.com.pk/")).text

            val gen = Regex(
                """(https?://gamerxyt\.com/hubcloud\.php\?[^"'\s>]+)""",
                RegexOption.IGNORE_CASE
            ).find(page)?.groupValues?.getOrNull(1)?.replace("&amp;", "&")
                ?: Regex(
                    """(https?://[^"'\s>]*hubcloud\.php\?[^"'\s>]+)""",
                    RegexOption.IGNORE_CASE
                ).find(page)?.groupValues?.getOrNull(1)?.replace("&amp;", "&")
                ?: return null

            val genHtml = app.get(gen, headers = hdr(driveUrl)).text

            val r2 = Regex(
                """(https://[a-z0-9]+\.r2\.cloudflarestorage\.com/[^"'\s>]+)""",
                RegexOption.IGNORE_CASE
            ).find(genHtml)?.groupValues?.getOrNull(1)?.replace("&amp;", "&")
            if (r2 != null) return r2

            val pixel = Regex(
                """(https://pixel\.hubcloud\.[a-z]+/\?id=[^"'\s>]+)""",
                RegexOption.IGNORE_CASE
            ).find(genHtml)?.groupValues?.getOrNull(1)?.replace("&amp;", "&")
            if (pixel != null) return pixel

            // pixeldrain
            val pd = Regex(
                """(https://pixeldrain\.[a-z]+/u/[a-zA-Z0-9]+)""",
                RegexOption.IGNORE_CASE
            ).find(genHtml)?.groupValues?.getOrNull(1)
            if (pd != null) return pd

            null
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

        // data is drama page or archive page
        if (servers.isEmpty() && data.startsWith("http")) {
            try {
                val html = app.get(data, headers = hdr()).text
                if (data.contains("/archives/")) {
                    for ((_, list) in parseEpisodes(html)) {
                        servers.addAll(list)
                    }
                } else {
                    for (arch in findArchiveLinks(html)) {
                        val archHtml = app.get(arch, headers = hdr(data)).text
                        for ((_, list) in parseEpisodes(archHtml)) {
                            servers.addAll(list)
                        }
                        if (servers.isNotEmpty()) break
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (servers.isEmpty()) return false

        var found = false
        val tried = HashSet<String>()

        // HubCloud first
        for ((label, link) in servers) {
            if (!link.contains("hubcloud", true)) continue
            if (!tried.add(link)) continue
            try {
                val direct = resolveHubCloud(link)
                if (direct != null) {
                    callback(
                        ExtractorLink(
                            name,
                            (label.ifBlank { "HubCloud" }) + " • R2",
                            direct,
                            "https://hubcloud.cx/",
                            Qualities.P720.value,
                            false
                        )
                    )
                    found = true
                }
            } catch (_: Exception) {
            }
            try {
                if (loadExtractor(link, "https://hubcloud.cx/", subtitleCallback, callback)) {
                    found = true
                }
            } catch (_: Exception) {
            }
        }

        // GDFlix / others via extractor
        for ((_, link) in servers) {
            if (link.contains("hubcloud", true)) continue
            if (!tried.add(link)) continue
            try {
                if (loadExtractor(link, mainUrl, subtitleCallback, callback)) {
                    found = true
                }
            } catch (_: Exception) {
            }
        }

        return found
    }
}
