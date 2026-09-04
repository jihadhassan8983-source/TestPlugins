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
            val year = yearFrom(titleRaw)
            out += newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
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

    /** Find kdramasmaza.com.pk/archives/XXXX buttons */
    private fun findArchiveLinks(html: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        // onclick window.location.href='https://kdramasmaza.com.pk/archives/5068'
        val onclick = Regex(
            """(?:location\.href|window\.location)\s*=\s*['"](https?://kdramasmaza\.com\.pk/archives/\d+)['"]""",
            RegexOption.IGNORE_CASE
        )
        for (m in onclick.findAll(html)) {
            out.add("Episodes" to m.groupValues[1])
        }
        // plain href
        val href = Regex(
            """href\s*=\s*["'](https?://kdramasmaza\.com\.pk/archives/\d+)["']""",
            RegexOption.IGNORE_CASE
        )
        for (m in href.findAll(html)) {
            out.add("Episodes" to m.groupValues[1])
        }
        return out.distinctBy { it.second }
    }

    /**
     * Parse Episode 01 + HubCloud + GDFlix from archives page
     */
    private fun parseEpisodes(html: String): List<Pair<Int, List<Pair<String, String>>>> {
        val out = ArrayList<Pair<Int, List<Pair<String, String>>>>()
        val epRegex = Regex(
            """Episode\s*0*(\d+)([\s\S]*?)(?=Episode\s*0*\d+|\z)""",
            RegexOption.IGNORE_CASE
        )
        for (m in epRegex.findAll(html)) {
            val ep = m.groupValues[1].toIntOrNull() ?: continue
            val block = m.groupValues[2]
            val servers = ArrayList<Pair<String, String>>()
            val linkRegex = Regex(
                """href\s*=\s*["'](https?://[^"']+)["'][^>]*>([\s\S]*?)</a>""",
                RegexOption.IGNORE_CASE
            )
            for (lm in linkRegex.findAll(block)) {
                val url = lm.groupValues[1].trim().replace("&amp;", "&")
                val label = lm.groupValues[2].replace(Regex("""<[^>]+>"""), "").trim()
                val h = url.lowercase()
                if (h.contains("hubcloud") || h.contains("gdflix") || h.contains("gdflix")) {
                    servers.add((label.ifBlank {
                        when {
                            h.contains("hubcloud") -> "HubCloud"
                            h.contains("gdflix") -> "GDFlix"
                            else -> "Server"
                        }
                    }) to url)
                }
            }
            // also bare hubcloud links without good label
            if (servers.isEmpty()) {
                for (u in Regex("""https?://hubcloud\.[a-z]+/drive/[a-zA-Z0-9]+""").findAll(block)) {
                    servers.add("HubCloud" to u.value)
                }
                for (u in Regex("""https?://(?:new\d+\.)?gdflix\.[a-z]+/file/[a-zA-Z0-9]+""").findAll(block)) {
                    servers.add("GDFlix" to u.value)
                }
            }
            if (servers.isNotEmpty()) out.add(ep to servers.distinctBy { it.second })
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

        // plot from first paragraph
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

        // Prefer "All Episodes Wise" style archives (usually first or second)
        for ((_, archUrl) in archives) {
            try {
                val archHtml = app.get(archUrl, headers = hdr(url)).text
                for ((epNum, servers) in parseEpisodes(archHtml)) {
                    if (!seen.add(epNum)) continue
                    // HubCloud first
                    val sorted = servers.sortedBy { s ->
                        if (s.second.contains("hubcloud", true)) 0 else 1
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
            if (episodes.isNotEmpty()) break
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
     * HubCloud page → gamerxyt.com/hubcloud.php?id=&token= → R2 signed MKV
     * Verified: GET + Range 206 works (playable in ExoPlayer)
     */
    private suspend fun resolveHubCloud(driveUrl: String): String? {
        return try {
            val page = app.get(driveUrl, headers = hdr("https://kdramasmaza.com.pk/")).text
            val gen = Regex(
                """(https?://gamerxyt\.com/hubcloud\.php\?[^"'\s]+)""",
                RegexOption.IGNORE_CASE
            ).find(page)?.groupValues?.getOrNull(1)?.replace("&amp;", "&")
                ?: return null

            val genHtml = app.get(gen, headers = hdr(driveUrl)).text

            // Prefer R2 cloudflare storage
            val r2 = Regex(
                """(https://[a-z0-9]+\.r2\.cloudflarestorage\.com/[^"'\s]+)""",
                RegexOption.IGNORE_CASE
            ).find(genHtml)?.groupValues?.getOrNull(1)?.replace("&amp;", "&")
            if (r2 != null) return r2

            // Fallback pixeldrain-style / pixel.hubcloud
            val pixel = Regex(
                """(https://pixel\.hubcloud\.[a-z]+/\?id=[^"'\s]+)""",
                RegexOption.IGNORE_CASE
            ).find(genHtml)?.groupValues?.getOrNull(1)?.replace("&amp;", "&")
            if (pixel != null) return pixel

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

        // Fallback: data is drama page
        if (servers.isEmpty() && data.startsWith("http") && data.contains("kdramasmaza")) {
            try {
                val html = app.get(data, headers = hdr()).text
                for ((_, arch) in findArchiveLinks(html)) {
                    val archHtml = app.get(arch, headers = hdr(data)).text
                    for ((_, list) in parseEpisodes(archHtml)) {
                        servers.addAll(list)
                    }
                    if (servers.isNotEmpty()) break
                }
            } catch (_: Exception) {
            }
        }

        if (servers.isEmpty()) return false

        var found = false

        // 1) HubCloud custom R2 resolve (verified playable)
        for ((label, link) in servers) {
            if (!link.contains("hubcloud", true)) continue
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
            // Also try built-in extractor as extra servers
            try {
                if (loadExtractor(link, "https://hubcloud.cx/", subtitleCallback, callback)) {
                    found = true
                }
            } catch (_: Exception) {
            }
        }

        // 2) GDFlix via built-in extractor (if available in CS)
        for ((_, link) in servers) {
            if (!link.contains("gdflix", true)) continue
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
