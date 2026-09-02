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
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie
    )

    private val ua =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private fun hdr(ref: String = mainUrl + "/"): Map<String, String> {
        return mapOf(
            "User-Agent" to ua,
            "Accept" to "*/*",
            "Referer" to ref
        )
    }

    override val mainPage = mainPageOf(
        (mainUrl + "/") to "Home",
        (mainUrl + "/movies/") to "All Movies",
        (mainUrl + "/genres/anime/") to "Anime",
        (mainUrl + "/genres/hollywood/") to "Bollywood",
        (mainUrl + "/genres/hollywood-movie/") to "Hollywood",
        (mainUrl + "/language/hindi/") to "Hindi",
        (mainUrl + "/language/english/") to "English"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            request.data
        } else {
            request.data.trimEnd('/') + "/page/" + page + "/"
        }
        val html = app.get(url, headers = hdr()).text
        val list = parseCards(html)
        return newHomePageResponse(request.name, list, list.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val html = app.get(mainUrl + "/?s=" + q, headers = hdr()).text
        return parseCards(html)
    }

    private fun parseCards(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html, mainUrl)
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        for (a in doc.select("a[href*=/movies/]")) {
            var href = a.attr("abs:href")
            if (href.isBlank()) href = a.attr("href")
            if (href.isBlank() || !href.contains("/movies/")) continue
            href = href.substringBefore("?").trimEnd('/') + "/"
            if (!seen.add(href)) continue

            // skip pure section links
            val slug = href.trimEnd('/').substringAfterLast('/')
            if (slug.isBlank() || slug == "movies") continue

            var title = ""
            val img = a.selectFirst("img")
            if (img != null) {
                title = img.attr("alt").trim()
            }
            if (title.isBlank()) title = a.attr("title").trim()
            if (title.isBlank()) title = a.text().trim()
            if (title.isBlank()) {
                title = slug.replace("-", " ")
            }
            if (title.length < 2) continue

            var poster: String? = null
            if (img != null) {
                poster = img.attr("abs:src")
                if (poster.isNullOrBlank()) poster = img.attr("src")
                if (poster.isNullOrBlank()) poster = img.attr("data-src")
            }

            val isSeries = title.contains("Season", true) ||
                    title.contains("Series", true) ||
                    slug.contains("season")

            if (isSeries) {
                out.add(
                    newAnimeSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                )
            } else {
                out.add(
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = poster
                    }
                )
            }
        }
        return out.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val page = if (url.contains("?")) url.substringBefore("?") else url
        val clean = page.trimEnd('/') + "/"
        val watchUrl = clean + "?watch=1"

        val html = app.get(watchUrl, headers = hdr()).text
        val doc = Jsoup.parse(html, mainUrl)

        var title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        if (title.isBlank()) {
            title = doc.selectFirst("title")?.text()
                ?.substringBefore("Download")
                ?.substringBefore("–")
                ?.substringBefore("-")
                ?.trim()
                .orEmpty()
        }
        if (title.isBlank()) {
            title = clean.trimEnd('/').substringAfterLast('/').replace("-", " ")
        }

        var poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        if (poster.isNullOrBlank()) {
            poster = doc.selectFirst("img[src*=tmdb], img[src*=image]")?.attr("src")
        }

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")

        // SERIES JSON (TV / anime seasons)
        val seriesJson = extractSeriesJson(html)
        if (seriesJson != null) {
            val episodes = ArrayList<Episode>()
            // parse seasons/episodes with regex (no jackson needed)
            // players url inside each episode
            val epBlocks = Regex(
                "\"ep\"\\s*:\\s*([0-9]+)[\\s\\S]*?\"players\"\\s*:\\s*(\\[.*?])",
                RegexOption.DOT_MATCHES_ALL
            )
            // simpler: each ep object
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

        // Movie (single)
        return newMovieLoadResponse(title, clean, TvType.Movie, clean + "|0") {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    private fun extractSeriesJson(html: String): String? {
        val marker = "SERIES="
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
                if (depth == 0) {
                    return html.substring(start, i + 1)
                }
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
        val watchUrl = page + "?watch=1"

        val html = try {
            app.get(watchUrl, headers = hdr()).text
        } catch (_: Exception) {
            return false
        }

        var found = false
        val added = HashSet<String>()
        val embeds = LinkedHashSet<String>()

        // 1) SERIES JSON players for this episode
        val seriesJson = extractSeriesJson(html)
        if (seriesJson != null && epNum > 0) {
            // find player iframe src near this ep number
            val epPattern = Regex(
                "\"ep\"\\s*:\\s*" + epNum + "\\s*,[\\s\\S]*?\"players\"\\s*:\\s*\\[(.*?)]",
                RegexOption.DOT_MATCHES_ALL
            )
            val block = epPattern.find(seriesJson)?.groupValues?.getOrNull(1) ?: ""
            Regex("src\\\\?\"\\s*:\\s*\\\\?\"(https?://[^\\\\\"]+)").findAll(block).forEach { m ->
                embeds.add(m.groupValues[1].replace("\\/", "/"))
            }
            Regex("src=\"(https?://[^\"]+)\"").findAll(block).forEach { m ->
                embeds.add(m.groupValues[1].replace("\\/", "/"))
            }
            Regex("https?://ultramoviedrive\\.rpmvip\\.com/#[a-zA-Z0-9]+").findAll(block).forEach {
                embeds.add(it.value)
            }
            Regex("https?://morencius\\.com/(?:embed|f)/[a-z0-9]+").findAll(block).forEach {
                embeds.add(it.value)
            }
        }

        // 2) Page-level embeds (movies)
        Regex("https?://morencius\\.com/(?:embed|f)/[a-z0-9]+").findAll(html).forEach {
            embeds.add(it.value)
        }
        Regex("https?://hgcloud\\.to/(?:e/)?[a-z0-9]+").findAll(html).forEach {
            embeds.add(it.value)
        }
        Regex("<iframe[^>]+src=\"(https?://[^\"]+)\"").findAll(html).forEach { m ->
            val u = m.groupValues[1]
            if (!u.contains("googletag") && !u.contains("ultramoviedrive.com")) {
                embeds.add(u)
            }
        }

        // 3) Direct m3u8 / mp4 on page
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
                // Morencius / VidHide
                if (embed.contains("morencius.com", true)) {
                    if (extractMorencius(embed, callback, added)) {
                        found = true
                        continue
                    }
                }

                // Built-in extractors
                try {
                    if (loadExtractor(embed, mainUrl, subtitleCallback, callback)) {
                        found = true
                        continue
                    }
                } catch (_: Exception) {
                }

                // Generic page scrape
                val body = app.get(embed, headers = hdr(mainUrl)).text
                Regex("https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*").findAll(body).forEach { m ->
                    val u = m.value
                    if (!added.add(u)) return@forEach
                    callback.invoke(
                        ExtractorLink(name, "Server", u, embed, Qualities.Unknown.value, true)
                    )
                    found = true
                }
            } catch (_: Exception) {
            }
        }

        return found
    }

    /** Morencius (VidHide clone): /f/CODE page has packed JS with hls2 m3u8 */
    private suspend fun extractMorencius(
        embed: String,
        callback: (ExtractorLink) -> Unit,
        added: HashSet<String>
    ): Boolean {
        var code = embed.substringAfterLast("/").substringBefore("?").trim()
        if (code.isBlank()) return false

        val pageUrl = "https://morencius.com/f/" + code
        val body = app.get(
            pageUrl,
            headers = mapOf(
                "User-Agent" to ua,
                "Referer" to mainUrl + "/"
            )
        ).text

        // Try unpack eval packer
        val unpacked = unpackEval(body)
        val searchIn = if (unpacked.isNotBlank()) unpacked else body

        var ok = false
        Regex("https?://[^\\s\"'\\\\]+\\.m3u8[^\\s\"'\\\\]*").findAll(searchIn).forEach { m ->
            val u = m.value.replace("\\/", "/")
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

        // hls2 field explicitly
        Regex("\"hls2\"\\s*:\\s*\"(https?://[^\"]+)\"").findAll(searchIn).forEach { m ->
            val u = m.groupValues[1].replace("\\/", "/")
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

        return ok
    }

    /** Minimal Dean Edwards packer unpacker */
    private fun unpackEval(html: String): String {
        val re = Regex(
            "eval\\(function\\(p,a,c,k,e,d\\)\\{.*?return p\\}\\('(.*?)'\\s*,\\s*([0-9]+)\\s*,\\s*([0-9]+)\\s*,\\s*'(.*?)'\\.split\\('\\\\|'\\)",
            RegexOption.DOT_MATCHES_ALL
        )
        val m = re.find(html) ?: return ""
        return try {
            val payload = m.groupValues[1]
            val radix = m.groupValues[2].toInt()
            val count = m.groupValues[3].toInt()
            val keywords = m.groupValues[4].split("|")
            var p = payload
            var c = count
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
