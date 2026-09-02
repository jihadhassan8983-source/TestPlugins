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
            "Accept" to "*/*",
            "Referer" to ref
        )
    }

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

    // Genre URLs on this site infinite-redirect. Only these work.
    override val mainPage = mainPageOf(
        (mainUrl + "/movies/") to "All Movies",
        (mainUrl + "/movies/page/2/") to "More Movies",
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
            parseCards(html).take(40)
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
            if (slug.isBlank() || slug == "movies" || slug.startsWith("page")) continue

            var title = ""
            val img = a.selectFirst("img")
            if (img != null) title = img.attr("alt").trim()
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
        for (k in listOf("data-src", "data-lazy-src", "data-original", "src")) {
            var v = img.attr(k).trim()
            if (v.isBlank()) continue
            if (v.startsWith("//")) v = "https:$v"
            if (v.startsWith("http")) return v
            if (v.startsWith("/")) return mainUrl + v
        }
        val abs = img.attr("abs:src")
        return abs.ifBlank { null }
    }

    override suspend fun load(url: String): LoadResponse {
        val page = if (url.contains("?")) url.substringBefore("?") else url
        val clean = page.trimEnd('/') + "/"

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
            poster = pickPoster(infoDoc.selectFirst("img[src*=tmdb], img[src*=wp-content], img"))
        }
        val plot = infoDoc.selectFirst("meta[property=og:description]")?.attr("content")

        val watchHtml = fetchHtml(clean + "?watch=1")
        val seriesJson = extractSeriesJson(watchHtml)

        if (!seriesJson.isNullOrBlank()) {
            val episodes = ArrayList<Episode>()
            val epRe = Regex(
                "\\{\"ep\"\\s*:\\s*([0-9]+)\\s*,\\s*\"title\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\""
            )
            val seen = HashSet<Int>()
            for (m in epRe.findAll(seriesJson)) {
                val num = m.groupValues[1].toIntOrNull() ?: continue
                if (!seen.add(num)) continue
                val epTitle = m.groupValues[2].replace("\\\"", "\"").replace("\\/", "/")
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
            if (html[i] == '{') depth++
            else if (html[i] == '}') {
                depth--
                if (depth == 0) return html.substring(start, i + 1)
            }
            i++
        }
        return null
    }

    private fun addLink(
        callback: (ExtractorLink) -> Unit,
        label: String,
        url: String,
        referer: String
    ) {
        callback.invoke(
            ExtractorLink(
                name,
                label,
                url,
                referer,
                Qualities.Unknown.value,
                url.contains(".m3u8")
            )
        )
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

        Regex("https?://morencius\\.com/(?:embed|f|v|d|e)/[a-zA-Z0-9]+").findAll(html).forEach {
            embeds.add(it.value)
        }
        Regex("src=[\"'](https?://morencius\\.com/[^\"']+)[\"']").findAll(html).forEach {
            embeds.add(it.groupValues[1])
        }
        Regex("https?://hgcloud\\.to/(?:e/)?[a-z0-9]+").findAll(html).forEach {
            embeds.add(it.value)
        }

        if (epNum > 0) {
            val seriesJson = extractSeriesJson(html)
            if (seriesJson != null) {
                val epPattern = Regex(
                    "\"ep\"\\s*:\\s*" + epNum + "\\s*,[\\s\\S]*?\"players\"\\s*:\\s*\\[(.*?)]",
                    RegexOption.DOT_MATCHES_ALL
                )
                val block = epPattern.find(seriesJson)?.groupValues?.getOrNull(1) ?: ""
                Regex("https?://morencius\\.com/[a-zA-Z0-9/]+").findAll(block).forEach {
                    embeds.add(it.value.replace("\\/", "/"))
                }
                Regex("src=\"(https?://[^\"]+)\"").findAll(block).forEach {
                    embeds.add(it.groupValues[1].replace("\\/", "/"))
                }
            }
        }

        Regex("https?://[^\\s\"'<>\\\\]+\\.m3u8[^\\s\"'<>\\\\]*").findAll(html).forEach { m ->
            val u = m.value.replace("\\/", "/")
            if (!added.add(u)) return@forEach
            addLink(callback, "Direct", u, mainUrl)
            found = true
        }

        // Collect morencius codes — try reversed (full movie often last)
        val morenciusCodes = LinkedHashSet<String>()
        for (embed in embeds) {
            if (embed.contains("morencius", true)) {
                val code = embed.substringAfterLast("/").substringBefore("?").trim()
                if (code.isNotBlank()) morenciusCodes.add(code)
            }
        }

        for (code in morenciusCodes.reversed()) {
            try {
                if (extractMorencius("https://morencius.com/f/$code", callback, added)) {
                    found = true
                }
            } catch (_: Exception) {
            }
        }

        for (embed in embeds) {
            if (embed.contains("morencius", true)) continue
            try {
                if (loadExtractor(embed, mainUrl, subtitleCallback, callback)) {
                    found = true
                }
            } catch (_: Exception) {
            }
        }

        return found
    }

    /**
     * Skip ad/preview clips (duration < 2 minutes).
     * Full movie embeds are usually 1+ hours.
     */
    private suspend fun extractMorencius(
        embed: String,
        callback: (ExtractorLink) -> Unit,
        added: HashSet<String>
    ): Boolean {
        val code = embed.substringAfterLast("/").substringBefore("?").trim()
        if (code.isBlank()) return false

        val pages = listOf(
            "https://morencius.com/f/$code",
            "https://morencius.com/embed/$code"
        )

        var ok = false
        for (pageUrl in pages) {
            val body = fetchHtml(pageUrl)
            if (body.isBlank()) continue

            val unpacked = unpackEval(body)
            val searchIn = if (unpacked.isNotBlank()) unpacked else body

            var durationSec = 0.0
            Regex("""duration\s*:\s*["']?([0-9.]+)""").find(searchIn)?.groupValues?.getOrNull(1)
                ?.toDoubleOrNull()?.let { durationSec = it }

            // Skip ads / trailers / samples under 2 minutes
            if (durationSec > 0.0 && durationSec < 120.0) {
                continue
            }

            Regex("\"hls2\"\\s*:\\s*\"(https?://[^\"]+)\"").findAll(searchIn).forEach { m ->
                val u = m.groupValues[1].replace("\\/", "/")
                if (!added.add(u)) return@forEach
                val label = if (durationSec >= 120.0) {
                    "Morencius " + (durationSec / 60).toInt() + "min"
                } else {
                    "Morencius"
                }
                addLink(callback, label, u, "https://morencius.com/")
                ok = true
            }

            Regex("https?://[^\\s\"'\\\\]+\\.m3u8[^\\s\"'\\\\]*").findAll(searchIn).forEach { m ->
                val u = m.value.replace("\\/", "/")
                if (!added.add(u)) return@forEach
                if (durationSec > 0.0 && durationSec < 120.0) return@forEach
                val label = if (durationSec >= 120.0) {
                    "Morencius " + (durationSec / 60).toInt() + "min"
                } else {
                    "Morencius HLS"
                }
                addLink(callback, label, u, "https://morencius.com/")
                ok = true
            }

            if (ok) break
        }
        return ok
    }

    private fun unpackEval(html: String): String {
        val re = Regex(
            """eval\(function\(p,a,c,k,e,d\)\{.*?return p\}\('((?:\\'|[^'])*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:\\'|[^'])*)'\.split\('\|'\)""",
            RegexOption.DOT_MATCHES_ALL
        )
        val m = re.find(html) ?: return ""
        return try {
            var p = m.groupValues[1]
                .replace("\\'", "'")
                .replace("\\n", "\n")
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
