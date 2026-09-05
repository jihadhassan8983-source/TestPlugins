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
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

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
            .replace(Regex("""(?i)\s*Hindi Dubbed.*$"""), "")
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
            val parent = a.parents().firstOrNull { it.selectFirst("img") != null } ?: a.parent()
            val poster = parent?.let { pickImg(it) }
            val title = cleanTitle(titleRaw)
            val isAnime = titleRaw.contains("Anime", true)
            out += newTvSeriesSearchResponse(
                title,
                href,
                if (isAnime) TvType.Anime else TvType.TvSeries
            ) {
                this.posterUrl = poster
                this.year = yearFrom(titleRaw)
            }
        }
        return out
    }

    override val mainPage = mainPageOf(
        mainUrl + "/" to "Latest",
        mainUrl + "/category/korean-dramas/" to "Korean Dramas",
        mainUrl + "/category/turkish-dramas-in-urdu-hindi-dubbed/" to "Turkish",
        mainUrl + "/category/chinese-dramas-in-urdu-hindi-dubbed/" to "Chinese",
        mainUrl + "/category/anime-in-hindi-dubbed/" to "Anime Hindi",
        mainUrl + "/category/korean-dramas-in-english-dubbed/" to "English Dubbed",
        mainUrl + "/category/zzaction/" to "Action",
        mainUrl + "/category/zzromantic/" to "Romance",
        mainUrl + "/category/zzthriller/" to "Thriller"
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

    /** Prefer All Episodes Wise over Zip */
    private fun findArchiveLinks(html: String): List<String> {
        val scored = ArrayList<Pair<Int, String>>()

        for (m in Regex("""<button([^>]*)>([\s\S]*?)</button>""", RegexOption.IGNORE_CASE).findAll(html)) {
            val attrs = m.groupValues[1]
            val label = m.groupValues[2].replace(Regex("""<[^>]+>"""), "").trim().lowercase()
            val href = Regex(
                """(?:location\.href|window\.location)\s*=\s*['"](https?://kdramasmaza\.com\.pk/archives/\d+)['"]""",
                RegexOption.IGNORE_CASE
            ).find(attrs)?.groupValues?.getOrNull(1) ?: continue
            val score = when {
                "wise" in label -> 0
                "zip" in label -> 2
                else -> 1
            }
            scored.add(score to href)
        }

        for (m in Regex(
            """href\s*=\s*["'](https?://kdramasmaza\.com\.pk/archives/\d+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(html)) {
            val href = m.groupValues[1]
            if (scored.none { it.second == href }) scored.add(1 to href)
        }

        for (m in Regex(
            """(?:location\.href|window\.location)\s*=\s*['"](https?://kdramasmaza\.com\.pk/archives/\d+)['"]""",
            RegexOption.IGNORE_CASE
        ).findAll(html)) {
            val href = m.groupValues[1]
            if (scored.none { it.second == href }) scored.add(1 to href)
        }

        return scored.sortedBy { it.first }.map { it.second }.distinct()
    }

    /** External download pages (anime etc.) */
    private fun findExternalDownloadLinks(html: String): List<String> {
        val out = ArrayList<String>()
        for (m in Regex(
            """(?:location\.href|window\.location)\s*=\s*['"](https?://(?!kdramasmaza)[^'"]+)['"]""",
            RegexOption.IGNORE_CASE
        ).findAll(html)) {
            val u = m.groupValues[1]
            if (u.contains("telegram") || u.contains("t.me") || u.contains("whatsapp")) continue
            out.add(u)
        }
        return out.distinct()
    }

    /**
     * Returns list of (episodeNumber, list of server URLs with labels)
     * Works for HubCloud-only, GDFlix-only, mixed, etc.
     */
    private fun parseEpisodes(html: String): List<Pair<Int, List<Pair<String, String>>>> {
        val out = ArrayList<Pair<Int, List<Pair<String, String>>>>()

        val labels = Regex(
            """<span[^>]*ep-no[^>]*>\s*Episode\s*(\d+)\s*</span>""",
            RegexOption.IGNORE_CASE
        ).findAll(html).map { it.groupValues[1].toInt() }.toList()

        if (labels.isNotEmpty()) {
            val parts = Regex(
                """<span[^>]*ep-no[^>]*>\s*Episode\s*\d+\s*</span>""",
                RegexOption.IGNORE_CASE
            ).split(html)
            for (i in labels.indices) {
                val ep = labels[i]
                val block = if (i + 1 < parts.size) parts[i + 1].take(3500) else ""
                val servers = extractServers(block)
                if (servers.isNotEmpty()) out.add(ep to servers)
            }
            if (out.isNotEmpty()) return out
        }

        // Fallback Episode N text
        val epRegex = Regex(
            """Episode\s*0*(\d+)([\s\S]*?)(?=Episode\s*0*\d+|\z)""",
            RegexOption.IGNORE_CASE
        )
        for (m in epRegex.findAll(html)) {
            val ep = m.groupValues[1].toIntOrNull() ?: continue
            val servers = extractServers(m.groupValues[2].take(3500))
            if (servers.isNotEmpty()) out.add(ep to servers)
        }
        return out
    }

    private fun extractServers(block: String): List<Pair<String, String>> {
        val servers = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()

        fun add(label: String, url: String) {
            val u = url.trim().trimEnd('"', '\'', ')', ',')
            if (u.startsWith("http") && seen.add(u)) servers.add(label to u)
        }

        for (m in Regex(
            """https?://(?:www\.)?hubcloud\.[a-z]+/drive/[a-zA-Z0-9]+""",
            RegexOption.IGNORE_CASE
        ).findAll(block)) add("HubCloud", m.value)

        for (m in Regex(
            """https?://(?:new\d+\.)?gdflix\.[a-z]+/file/[a-zA-Z0-9]+""",
            RegexOption.IGNORE_CASE
        ).findAll(block)) add("GDFlix", m.value)

        for (m in Regex(
            """https?://(?:new\d+\.)?drivehub\.[a-z]+/file/\d+""",
            RegexOption.IGNORE_CASE
        ).findAll(block)) add("DriveHub", m.value)

        for (m in Regex(
            """https?://hubdrive\.[a-z]+/[^\s"']+""",
            RegexOption.IGNORE_CASE
        ).findAll(block)) add("HubDrive", m.value)

        for (m in Regex(
            """https?://gofile\.io/d/[a-zA-Z0-9]+""",
            RegexOption.IGNORE_CASE
        ).findAll(block)) add("GoFile", m.value)

        for (m in Regex(
            """https?://(?:www\.)?send\.cm/[a-zA-Z0-9]+""",
            RegexOption.IGNORE_CASE
        ).findAll(block)) add("SendCm", m.value)

        for (m in Regex(
            """https?://fpgo\.xyz/file/[a-zA-Z0-9]+""",
            RegexOption.IGNORE_CASE
        ).findAll(block)) add("FPGO", m.value)

        // Priority: HubCloud first, then GDFlix, then rest
        return servers.sortedBy { s ->
            when {
                s.second.contains("hubcloud", true) -> 0
                s.second.contains("gdflix", true) -> 1
                s.second.contains("drivehub", true) -> 2
                s.second.contains("hubdrive", true) -> 3
                else -> 4
            }
        }
    }

    private fun encodeData(servers: List<Pair<String, String>>): String {
        return servers.joinToString("\n") { it.first + "``" + it.second }
    }

    private fun decodeData(data: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (line in data.split("\n")) {
            val p = line.trim()
            if ("``" in p) {
                val label = p.substringBefore("``").trim()
                val url = p.substringAfter("``").trim()
                if (url.startsWith("http")) out.add(label to url)
            } else if (p.startsWith("http")) {
                out.add("Server" to p)
            }
        }
        return out
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

        // Scan ALL wise archives (multi-season) — do not stop at first empty
        for (archUrl in archives) {
            try {
                val archHtml = app.get(archUrl, headers = hdr(url)).text
                for ((epNum, servers) in parseEpisodes(archHtml)) {
                    if (!seen.add(epNum)) continue
                    if (servers.isEmpty()) continue
                    val data = encodeData(servers)
                    episodes += newEpisode(data) {
                        this.name = "Episode " + epNum
                        this.episode = epNum
                        this.data = data
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Anime / external download site (no archives)
        if (episodes.isEmpty()) {
            val external = findExternalDownloadLinks(html)
            if (external.isNotEmpty()) {
                for ((i, ext) in external.withIndex()) {
                    try {
                        val extHtml = app.get(ext, headers = hdr(url)).text
                        // try same archive / episode patterns on external page
                        val parsed = parseEpisodes(extHtml)
                        if (parsed.isNotEmpty()) {
                            for ((epNum, servers) in parsed) {
                                if (!seen.add(epNum)) continue
                                val data = encodeData(servers)
                                episodes += newEpisode(data) {
                                    this.name = "Episode " + epNum
                                    this.episode = epNum
                                    this.data = data
                                }
                            }
                        } else {
                            // collect any hoster links on page
                            val servers = extractServers(extHtml)
                            if (servers.isNotEmpty()) {
                                val epNum = i + 1
                                if (seen.add(epNum)) {
                                    val data = encodeData(servers)
                                    episodes += newEpisode(data) {
                                        this.name = "Episode " + epNum
                                        this.episode = epNum
                                        this.data = data
                                    }
                                }
                            } else {
                                // store external page as data for loadLinks reparse
                                val epNum = i + 1
                                if (seen.add(epNum)) {
                                    episodes += newEpisode(ext) {
                                        this.name = "Episode " + epNum
                                        this.episode = epNum
                                        this.data = ext
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }

        if (episodes.isEmpty()) {
            val fb = archives.firstOrNull() ?: url
            episodes += newEpisode(fb) {
                this.name = "Episode 1"
                this.episode = 1
                this.data = fb
            }
        }

        episodes.sortBy { it.episode }
        val type = if (titleRaw.contains("Anime", true)) TvType.Anime else TvType.TvSeries
        return newTvSeriesLoadResponse(title, url, type, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags.distinct()
        }
    }

    private suspend fun resolveHubCloud(driveUrl: String): List<String> {
        val results = ArrayList<String>()
        try {
            val page = app.get(driveUrl, headers = hdr("https://kdramasmaza.com.pk/")).text
            val genLinks = Regex(
                """(https?://(?:gamerxyt\.com|[^"'\s>]+)/hubcloud\.php\?[^"'\s>]+)""",
                RegexOption.IGNORE_CASE
            ).findAll(page).map { it.groupValues[1].replace("&amp;", "&") }.distinct().toList()

            for (gen in genLinks.take(2)) {
                try {
                    val genHtml = app.get(gen, headers = hdr(driveUrl)).text
                    for (m in Regex(
                        """(https://[a-z0-9]+\.r2\.cloudflarestorage\.com/[^"'\s>]+)""",
                        RegexOption.IGNORE_CASE
                    ).findAll(genHtml)) {
                        val u = m.groupValues[1].replace("&amp;", "&")
                        if (u !in results) results.add(u)
                    }
                    for (m in Regex(
                        """(https://pixel\.hubcloud\.[a-z]+/\?id=[^"'\s>]+)""",
                        RegexOption.IGNORE_CASE
                    ).findAll(genHtml)) {
                        val u = m.groupValues[1].replace("&amp;", "&")
                        if (u !in results) results.add(u)
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        return results
    }

    /**
     * GDFlix page often lists HubCloud / direct mirrors when accessible from mobile.
     * Also try loadExtractor.
     */
    private suspend fun resolveGdflix(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val html = app.get(url, headers = hdr("https://kdramasmaza.com.pk/")).text
            // HubCloud mirrors on GDFlix page
            for (m in Regex(
                """https?://(?:www\.)?hubcloud\.[a-z]+/drive/[a-zA-Z0-9]+""",
                RegexOption.IGNORE_CASE
            ).findAll(html)) {
                val directs = resolveHubCloud(m.value)
                for (d in directs) {
                    callback(
                        ExtractorLink(
                            name,
                            "GDFlix → HubCloud R2",
                            d,
                            "https://hubcloud.cx/",
                            Qualities.P720.value,
                            false
                        )
                    )
                    found = true
                }
            }
            // Direct file buttons
            for (m in Regex(
                """href\s*=\s*["'](https?://[^"']+\.(?:mkv|mp4)[^"']*)["']""",
                RegexOption.IGNORE_CASE
            ).findAll(html)) {
                callback(
                    ExtractorLink(
                        name,
                        "GDFlix Direct",
                        m.groupValues[1],
                        url,
                        Qualities.P720.value,
                        false
                    )
                )
                found = true
            }
        } catch (_: Exception) {
        }
        try {
            if (loadExtractor(url, "https://gdflix.dev/", subtitleCallback, callback)) {
                found = true
            }
        } catch (_: Exception) {
        }
        // Domain variants
        for (host in listOf("gdflix.dad", "new4.gdflix.dad", "new6.gdflix.dad", "new10.gdflix.dad")) {
            if (found) break
            try {
                val alt = Regex("""https?://[^/]+""").replace(url, "https://" + host)
                if (loadExtractor(alt, "https://" + host + "/", subtitleCallback, callback)) {
                    found = true
                }
            } catch (_: Exception) {
            }
        }
        return found
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var servers = decodeData(data)

        // data is drama/archive/external page URL
        if (servers.isEmpty() && data.startsWith("http")) {
            try {
                val html = app.get(data, headers = hdr()).text
                if (data.contains("/archives/")) {
                    for ((_, list) in parseEpisodes(html)) {
                        servers = servers + list
                    }
                } else {
                    val archives = findArchiveLinks(html)
                    for (arch in archives) {
                        val archHtml = app.get(arch, headers = hdr(data)).text
                        for ((_, list) in parseEpisodes(archHtml)) {
                            servers = servers + list
                        }
                        if (servers.isNotEmpty()) break
                    }
                    if (servers.isEmpty()) {
                        servers = extractServers(html)
                    }
                    if (servers.isEmpty()) {
                        for (ext in findExternalDownloadLinks(html)) {
                            try {
                                val extHtml = app.get(ext, headers = hdr(data)).text
                                servers = extractServers(extHtml)
                                if (servers.isNotEmpty()) break
                                for ((_, list) in parseEpisodes(extHtml)) {
                                    servers = servers + list
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (servers.isEmpty()) return false

        var found = false
        val tried = HashSet<String>()

        // 1) HubCloud first
        for ((label, link) in servers) {
            if (!link.contains("hubcloud", true)) continue
            if (!tried.add(link)) continue
            try {
                val directs = resolveHubCloud(link)
                for ((i, d) in directs.withIndex()) {
                    callback(
                        ExtractorLink(
                            name,
                            (label.ifBlank { "HubCloud" }) + if (i == 0) " R2" else " Alt",
                            d,
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

        // 2) GDFlix (Turkish dramas mainly)
        for ((_, link) in servers) {
            if (!link.contains("gdflix", true)) continue
            if (!tried.add(link)) continue
            if (resolveGdflix(link, subtitleCallback, callback)) found = true
        }

        // 3) DriveHub / HubDrive / GoFile / SendCm / FPGO via extractors
        for ((label, link) in servers) {
            if (link.contains("hubcloud", true) || link.contains("gdflix", true)) continue
            if (!tried.add(link)) continue
            try {
                if (loadExtractor(link, mainUrl, subtitleCallback, callback)) {
                    found = true
                }
            } catch (_: Exception) {
            }
            // DriveHub → sometimes has hubcloud text in page
            if (link.contains("drivehub", true)) {
                try {
                    val html = app.get(link, headers = hdr("https://kdramasmaza.com.pk/")).text
                    for (m in Regex(
                        """https?://(?:www\.)?hubcloud\.[a-z]+/drive/[a-zA-Z0-9]+""",
                        RegexOption.IGNORE_CASE
                    ).findAll(html)) {
                        val directs = resolveHubCloud(m.value)
                        for (d in directs) {
                            callback(
                                ExtractorLink(
                                    name,
                                    "DriveHub → HubCloud",
                                    d,
                                    "https://hubcloud.cx/",
                                    Qualities.P720.value,
                                    false
                                )
                            )
                            found = true
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        return found
    }
}
