@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import java.net.URLEncoder

class UltraMovieDriveProvider : MainAPI() {
    override var mainUrl = "https://ultramoviedrive.com"
    override var name = "UltraMovieDrive"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val ua =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private fun hdr(ref: String = mainUrl + "/"): Map<String, String> {
        return mapOf(
            "User-Agent" to ua,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to ref
        )
    }

    /**
     * App blocks .text when Content-Length > 5MB (OOM guard).
     * Large API exists only at runtime in the app, not in compile stubs.
     * Try .text first, then reflection, then document.html().
     */
    private suspend fun fetchHtml(url: String): String {
        val res = app.get(url, headers = hdr())
        return try {
            res.text
        } catch (_: Exception) {
            try {
                val method = res.javaClass.methods.firstOrNull { m ->
                    m.name == "getTextLarge" || m.name == "textLarge"
                }
                if (method != null) {
                    (method.invoke(res) as? String).orEmpty()
                } else {
                    res.document.html()
                }
            } catch (_: Exception) {
                try {
                    res.document.html()
                } catch (_: Exception) {
                    ""
                }
            }
        }
    }

    // Only stable URLs (genre/language pages cause infinite redirect hang)
    override val mainPage = mainPageOf(
        (mainUrl + "/movies/") to "All Movies",
        (mainUrl + "/") to "Home"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) {
            return newHomePageResponse(request.name, emptyList(), false)
        }
        return try {
            val html = fetchHtml(request.data)
            val list = parseCards(html).take(40)
            newHomePageResponse(request.name, list, false)
        } catch (_: Exception) {
            newHomePageResponse(request.name, emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            val html = fetchHtml(mainUrl + "/?s=" + q)
            parseCards(html).take(30)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseCards(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html, mainUrl)
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        for (a in doc.select("a[href*=/movies/]")) {
            if (out.size >= 50) break

            var href = a.attr("abs:href")
            if (href.isBlank()) href = a.attr("href")
            if (href.isBlank() || !href.contains("/movies/")) continue

            href = href.substringBefore("?").trimEnd('/') + "/"
            if (!seen.add(href)) continue

            val slug = href.trimEnd('/').substringAfterLast('/')
            if (slug.isBlank() || slug == "movies") continue

            var title = ""
            val img = a.selectFirst("img")
            if (img != null) {
                title = img.attr("alt").trim()
            }
            if (title.isBlank()) title = a.attr("title").trim()
            if (title.isBlank()) title = a.text().trim()
            if (title.isBlank()) title = slug.replace("-", " ")
            if (title.length < 2) continue

            val poster = pickPoster(img)

            out.add(
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            )
        }
        return out.distinctBy { it.url }
    }

    private fun pickPoster(img: org.jsoup.nodes.Element?): String? {
        if (img == null) return null
        val keys = listOf(
            "data-src", "data-lazy-src", "data-original",
            "data-srcset", "srcset", "src"
        )
        for (k in keys) {
            var v = img.attr(k).trim()
            if (v.isBlank()) continue
            if (v.contains(" ")) {
                v = v.split(",")[0].trim().substringBefore(" ")
            }
            if (v.startsWith("//")) v = "https:$v"
            if (v.startsWith("http")) return v
            if (v.startsWith("/")) return mainUrl + v
        }
        val abs = img.attr("abs:src")
        return if (abs.isNotBlank()) abs else null
    }

    override suspend fun load(url: String): LoadResponse {
        val page = if (url.contains("?")) url.substringBefore("?") else url
        val clean = page.trimEnd('/') + "/"

        // Small page first — title + poster
        val infoHtml = fetchHtml(clean)
        val infoDoc = Jsoup.parse(infoHtml, mainUrl)

        var title = infoDoc.selectFirst("h1")?.text()?.trim().orEmpty()
        if (title.isBlank()) {
            title = infoDoc.selectFirst("title")?.text()
                ?.substringBefore("Download")
                ?.substringBefore("–")
                ?.substringBefore("|")
                ?.trim()
                .orEmpty()
        }
        if (title.isBlank()) {
            title = clean.trimEnd('/').substringAfterLast('/').replace("-", " ")
        }

        var poster = infoDoc.selectFirst("meta[property=og:image]")?.attr("content")
        if (poster.isNullOrBlank()) {
            poster = pickPoster(
                infoDoc.selectFirst("img[src*=tmdb], img[src*=image], img[src*=wp-content]")
            )
        }
        val plot = infoDoc.selectFirst("meta[property=og:description]")?.attr("content")

        // Watch page for SERIES / embeds (may be large)
        val watchHtml = fetchHtml(clean + "?watch=1")
        val seriesJson = extractSeriesJson(watchHtml)

        if (seriesJson != null) {
            val episodes = ArrayList<Episode>()
            val epRe = Regex(
                "\\{\"ep\"\\s*:\\s*([0-9]+)\\s*,\\s*\"title\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\""
            )
            val seen = HashSet<Int>()
            for (m in epRe.findAll(seriesJson)) {
                val num = m.groupValues[1].toIntOrNull() ?: continue
                if (!seen.add(num)) continue
                val epTitle = m.groupValues[2]
                    .replace("\\\"", "\"")
                    .replace("\\/", "/")
                episodes.add(
                    newEpisode(clean + "|" + num) {
                        this.name = if (epTitle.isNotBlank()) epTitle else ("Episode " + num)
                        this.episode = num
                    }
                )
            }
            if (episodes.isNotEmpty()) {
                val sorted = episodes.sortedWith(compareBy(nullsLast()) { it.episode })
                return newTvSeriesLoadResponse(title, clean, TvType.TvSeries, sorted) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }
        }

        return newMovieLoadResponse(title, clean, TvType.Movie, clean + "|0") {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    private fun extractSeriesJson(html: String): String? {
        if (html.isBlank()) return null
        var idx = html.indexOf("SERIES =")
        if (idx < 0) idx = html.indexOf("SERIES=")
        if (idx < 0) return null
        val start = html.indexOf('{', idx)
        if (start < 0) return null
        var depth = 0
        var i = start
        while (i < html.length) {
            val c = html[i]
            if (c == '{') depth++
            else if (c == '}') {
                depth--
                if (depth == 0) return html.substring(start, i + 1)
            }
            i++
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        val page = (parts.getOrNull(0) ?: data).trimEnd('/') + "/"
        val epNum = parts.getOrNull(1)?.toIntOrNull() ?: 0

        val html = fetchHtml(page + "?watch=1")
        if (html.isBlank()) return false

        var found = false
        val added = HashSet<String>()
        val embeds = LinkedHashSet<String>()

        val seriesJson = extractSeriesJson(html)
        if (seriesJson != null && epNum > 0) {
            val epPattern = Regex(
                "\"ep\"\\s*:\\s*" + epNum + "\\s*,[\\s\\S]*?\"players\"\\s*:\\s*\\[(.*?)]",
                RegexOption.DOT_MATCHES_ALL
            )
            val block = epPattern.find(seriesJson)?.groupValues?.getOrNull(1) ?: ""
            Regex("src=\"(https?://[^\"]+)\"").findAll(block).forEach { m ->
                embeds.add(m.groupValues[1].replace("\\/", "/"))
            }
            Regex("https?://morencius\\.com/(?:embed|f)/[a-z0-9]+").findAll(block).forEach {
                embeds.add(it.value)
            }
        }

        Regex("https?://morencius\\.com/(?:embed|f)/[a-z0-9]+").findAll(html).forEach {
            embeds.add(it.value)
        }
        Regex("https?://hgcloud\\.to/(?:e/)?[a-z0-9]+").findAll(html).forEach {
            embeds.add(it.value)
        }
        Regex("<iframe[^>]+src=\"(https?://[^\"]+)\"").findAll(html).forEach { m ->
            val u = m.groupValues[1]
            if (!u.contains("googletag") && !u.contains("ultramoviedrive.com/")) {
                embeds.add(u)
            }
        }

        Regex("https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*").findAll(html).forEach { m ->
            val u = m.value
            if (!added.add(u)) return@forEach
            callback.invoke(
                ExtractorLink(name, "Direct HLS", u, mainUrl, Qualities.Unknown.value, true)
            )
            found = true
        }

        for (embed in embeds) {
            try {
                if (embed.contains("morencius.com", true)) {
                    if (extractMorencius(embed, callback, added)) {
                        found = true
                        continue
                    }
                }

                try {
                    if (loadExtractor(embed, mainUrl, subtitleCallback, callback)) {
                        found = true
                        continue
                    }
                } catch (_: Exception) {
                }

                if (!embed.contains("rpmvip", true) && !embed.contains("#")) {
                    val body = fetchHtml(embed)
                    Regex("https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*").findAll(body).forEach { m ->
                        val u = m.value
                        if (!added.add(u)) return@forEach
                        callback.invoke(
                            ExtractorLink(name, "Server", u, embed, Qualities.Unknown.value, true)
                        )
                        found = true
                    }
                }
            } catch (_: Exception) {
            }
        }

        return found
    }

    private suspend fun extractMorencius(
        embed: String,
        callback: (ExtractorLink) -> Unit,
        added: HashSet<String>
    ): Boolean {
        val code = embed.substringAfterLast("/").substringBefore("?").trim()
        if (code.isBlank()) return false

        val pageUrl = "https://morencius.com/f/" + code
        val body = fetchHtml(pageUrl)
        if (body.isBlank()) return false

        val unpacked = unpackEval(body)
        val searchIn = if (unpacked.isNotBlank()) unpacked else body
        var ok = false

        Regex("\"hls2\"\\s*:\\s*\"(https?://[^\"]+)\"").findAll(searchIn).forEach { m ->
            val u = m.groupValues[1].replace("\\/", "/")
            if (!added.add(u)) return@forEach
            callback.invoke(
                ExtractorLink(
                    name,
                    "Morencius",
                    u,
                    "https://morencius.com/",
                    Qualities.Unknown.value,
                    true
                )
            )
            ok = true
        }

        Regex("https?://[^\\s\"'\\\\]+\\.m3u8[^\\s\"'\\\\]*").findAll(searchIn).forEach { m ->
            val u = m.value.replace("\\/", "/")
            if (!added.add(u)) return@forEach
            callback.invoke(
                ExtractorLink(
                    name,
                    "Morencius HLS",
                    u,
                    "https://morencius.com/",
                    Qualities.Unknown.value,
                    true
                )
            )
            ok = true
        }

        if (!ok) {
            try {
                if (loadExtractor("https://morencius.com/embed/" + code, mainUrl, { }, callback)) {
                    ok = true
                }
            } catch (_: Exception) {
            }
        }
        return ok
    }

    private fun unpackEval(html: String): String {
        val re = Regex(
            "eval\\(function\\(p,a,c,k,e,d\\)\\{.*?return p\\}\\('(.*?)'\\s*,\\s*([0-9]+)\\s*,\\s*([0-9]+)\\s*,\\s*'(.*?)'\\.split\\('\\\\|'\\)",
            RegexOption.DOT_MATCHES_ALL
        )
        val m = re.find(html) ?: return ""
        return try {
            var p = m.groupValues[1]
            val radix = m.groupValues[2].toInt()
            var c = m.groupValues[3].toInt()
            val keywords = m.groupValues[4].split("|")
            while (c > 0) {
                c--
                if (c < keywords.size && keywords[c].isNotEmpty()) {
                    val token = encodeBase(c, radix)
                    p = p.replace(Regex("\\b" + Regex.escape(token) + "\\b"), keywords[c])
                }
            }
            p
        } catch (_: Exception) {
            ""
        }
    }

    private fun encodeBase(num: Int, radix: Int): String {
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (num == 0) return "0"
        var n = num
        val sb = StringBuilder()
        while (n > 0) {
            sb.append(alphabet[n % radix])
            n /= radix
        }
        return sb.reverse().toString()
    }
}
