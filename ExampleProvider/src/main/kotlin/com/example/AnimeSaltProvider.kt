@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document

class AnimeSaltProvider : MainAPI() {
    override var mainUrl = "https://animesalt.me"
    override var name = "AnimeSalt"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val ua =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private fun hdr(ref: String = mainUrl + "/"): Map<String, String> {
        return mapOf(
            "User-Agent" to ua,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to ref,
            "Origin" to mainUrl
        )
    }

    override val mainPage = mainPageOf(
        (mainUrl + "/") to "Home",
        (mainUrl + "/audio/hindi/") to "Hindi Dub",
        (mainUrl + "/audio/tamil/") to "Tamil Dub",
        (mainUrl + "/audio/telugu/") to "Telugu Dub",
        (mainUrl + "/tv/") to "All Anime"
    )

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        val root = base.trimEnd('/')
        return if (root == mainUrl) {
            mainUrl + "/tv/page/" + page + "/"
        } else {
            root + "/page/" + page + "/"
        }
    }

    private fun clean(s: String): String {
        return s
            .replace("&quot;", "\"")
            .replace("&#8211;", "-")
            .replace("&#8217;", "'")
            .replace("&amp;", "&")
            .replace("\\/", "/")
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        doc.select("article.anime-card").forEach { card ->
            val a = card.selectFirst("a[href*=/tv/]") ?: return@forEach
            var href = a.attr("abs:href")
            if (href.isBlank()) href = a.attr("href")
            if (href.isBlank()) return@forEach
            href = href.substringBefore("?").trimEnd('/') + "/"
            if (!seen.add(href)) return@forEach

            var title = a.selectFirst("h3")?.text()?.trim().orEmpty()
            if (title.isBlank()) {
                title = a.selectFirst("img")?.attr("alt")?.trim().orEmpty()
            }
            if (title.isBlank()) {
                title = href.trimEnd('/').substringAfterLast('/').replace("-", " ")
            }
            title = clean(title)
            if (title.isBlank()) return@forEach

            val img = a.selectFirst("img")
            var poster: String? = null
            if (img != null) {
                poster = img.attr("abs:src")
                if (poster.isNullOrBlank()) poster = img.attr("src")
                if (poster.isNullOrBlank()) poster = img.attr("data-src")
            }

            out.add(
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                }
            )
        }

        if (out.isEmpty()) {
            doc.select("a[href*=/tv/]").forEach { a ->
                var href = a.attr("abs:href")
                if (href.isBlank()) href = a.attr("href")
                if (href.isBlank()) return@forEach
                if (!href.contains("/tv/")) return@forEach
                href = href.substringBefore("?").trimEnd('/') + "/"
                if (!seen.add(href)) return@forEach
                val title = clean(a.text().trim())
                if (title.isNotBlank()) {
                    out.add(newAnimeSearchResponse(title, href, TvType.Anime))
                }
            }
        }
        return out.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data == mainUrl + "/" || request.data == mainUrl) {
            if (page > 1) {
                return newHomePageResponse(request.name, emptyList(), false)
            }
            request.data
        } else {
            pageUrl(request.data, page)
        }
        val doc = app.get(url, headers = hdr()).document
        val list = parseCards(doc)
        return newHomePageResponse(request.name, list, list.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val doc = app.get(mainUrl + "/?s=" + q, headers = hdr()).document
        return parseCards(doc)
    }

    override suspend fun load(url: String): LoadResponse {
        val page = if (url.endsWith("/")) url else url + "/"
        val doc = app.get(page, headers = hdr()).document
        val html = clean(doc.html())

        var title = doc.selectFirst("h1")?.text()?.trim()
        if (title.isNullOrBlank()) {
            title = doc.selectFirst("title")?.text()
                ?.substringBefore(" Hindi")
                ?.substringBefore(" –")
                ?.trim()
        }
        if (title.isNullOrBlank()) {
            title = page.trimEnd('/').substringAfterLast('/').replace("-", " ")
        }
        title = clean(title)

        var poster = doc.selectFirst(".poster-wrap img")?.attr("src")
        if (poster.isNullOrBlank()) {
            poster = doc.selectFirst("img[src*=anilist], img[src*=tmdb]")?.attr("src")
        }
        if (poster.isNullOrBlank()) {
            poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        }
        if (!poster.isNullOrBlank() && poster.startsWith("//")) {
            poster = "https:" + poster
        }

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst(".entry-content p")?.text()

        val episodes = ArrayList<Episode>()
        val re = Regex(
            "triggerEpisode\\(\\[(.*?)]\\s*,\\s*\"([^\"]+)\"\\s*,\\s*\"(ep-\\d+)\"",
            RegexOption.DOT_MATCHES_ALL
        )
        var idx = 0
        re.findAll(html).forEach { m ->
            idx++
            val epName = clean(m.groupValues[2])
            var epNum = Regex("(\\d+)").find(m.groupValues[3])?.groupValues?.get(1)?.toIntOrNull()
            if (epNum == null) {
                epNum = Regex("(\\d+)").find(epName)?.groupValues?.get(1)?.toIntOrNull()
            }
            if (epNum == null) epNum = idx

            episodes.add(
                newEpisode(page + "||" + epNum) {
                    this.name = epName
                    this.episode = epNum
                }
            )
        }

        if (episodes.isEmpty()) {
            return newMovieLoadResponse(title, page, TvType.AnimeMovie, page + "||1") {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = plot
            }
        }

        val sorted = episodes.sortedWith(compareBy(nullsLast()) { it.episode })
        return newAnimeLoadResponse(title, page, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = plot
            addEpisodes(DubStatus.Dubbed, sorted)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var pageUrl = data
        var epNum = 1
        if (data.contains("||")) {
            val p = data.split("||")
            pageUrl = p[0]
            epNum = p.getOrNull(1)?.toIntOrNull() ?: 1
        }
        if (!pageUrl.endsWith("/")) {
            pageUrl = pageUrl + "/"
        }

        val html = clean(app.get(pageUrl, headers = hdr()).text)
        val servers = ArrayList<Pair<String, String>>()

        val re = Regex(
            "triggerEpisode\\(\\[(.*?)]\\s*,\\s*\"([^\"]+)\"\\s*,\\s*\"(ep-\\d+)\"",
            RegexOption.DOT_MATCHES_ALL
        )
        val reUrl = Regex("\"url\"\\s*:\\s*\"(https?:\\\\?/\\\\?/[^\"]+)\"")

        re.findAll(html).forEach { m ->
            val block = m.groupValues[1]
            val thisEp = Regex("(\\d+)").find(m.groupValues[3])?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (thisEp != epNum) return@forEach

            reUrl.findAll(block).forEach { um ->
                val u = um.groupValues[1].replace("\\/", "/")
                val chunkStart = maxOf(0, um.range.first - 150)
                val chunkEnd = minOf(block.length, um.range.last + 20)
                val chunk = block.substring(chunkStart, chunkEnd)
                val lang = Regex("\"lang\"\\s*:\\s*\"([^\"]+)\"").find(chunk)?.groupValues?.get(1) ?: ""
                val sname = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(chunk)?.groupValues?.get(1) ?: "HD"
                val label = if (lang.isNotBlank()) (lang + " " + sname) else sname
                servers.add(label to u)
            }
        }

        if (servers.isEmpty()) {
            re.findAll(html).forEach { m ->
                val thisEp = Regex("(\\d+)").find(m.groupValues[3])?.groupValues?.get(1)?.toIntOrNull()
                if (thisEp != null && thisEp != epNum) return@forEach
                reUrl.findAll(m.groupValues[1]).forEach { um ->
                    val u = um.groupValues[1].replace("\\/", "/")
                    servers.add("Server" to u)
                }
            }
        }

        if (servers.isEmpty()) return false

        var found = false
        for ((label, embedRaw) in servers.distinctBy { it.second }) {
            val embed = embedRaw.replace("\\/", "/").trim()
            if (embed.isBlank()) continue

            try {
                if (embed.contains("vidmoly", true)) {
                    if (extractVidmoly(embed, label, callback)) {
                        found = true
                        continue
                    }
                }

                try {
                    if (loadExtractor(embed, mainUrl + "/", subtitleCallback, callback)) {
                        found = true
                        continue
                    }
                } catch (_: Exception) {
                }

                val body = app.get(embed, headers = hdr(mainUrl + "/")).text
                val m3u8s = LinkedHashSet<String>()
                val reM3u8 = Regex("https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*")
                reM3u8.findAll(body).forEach { m3u8s.add(it.value) }

                val reFile = Regex("file:\\s*[\"']([^\"']+)[\"']")
                reFile.findAll(body).forEach { m ->
                    val u = m.groupValues[1]
                    if (u.contains(".m3u8")) m3u8s.add(u)
                }

                for (src in m3u8s) {
                    callback.invoke(
                        ExtractorLink(name, label, src, embed, Qualities.Unknown.value, true)
                    )
                    found = true
                }

                if (m3u8s.isEmpty()) {
                    val reMp4 = Regex("https?://[^\\s\"'<>]+\\.mp4[^\\s\"'<>]*")
                    reMp4.findAll(body).forEach { mm ->
                        callback.invoke(
                            ExtractorLink(name, label, mm.value, embed, Qualities.Unknown.value, false)
                        )
                        found = true
                    }
                }
            } catch (_: Exception) {
                continue
            }
        }
        return found
    }

    private suspend fun extractVidmoly(
        embed: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(
            embed,
            headers = mapOf(
                "User-Agent" to ua,
                "Referer" to (mainUrl + "/")
            )
        ).text

        val sources = LinkedHashSet<String>()

        val re1 = Regex("https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*")
        re1.findAll(html).forEach { sources.add(it.value) }

        val re2 = Regex("file:\\s*[\"']([^\"']+)[\"']")
        re2.findAll(html).forEach { m ->
            val u = m.groupValues[1]
            if (u.contains(".m3u8") || u.startsWith("http")) {
                sources.add(u)
            }
        }

        if (sources.isEmpty()) return false

        for (src in sources) {
            if (!src.startsWith("http")) continue
            callback.invoke(
                ExtractorLink(
                    name,
                    label,
                    src,
                    "https://vidmoly.org/",
                    Qualities.Unknown.value,
                    true
                )
            )
        }
        return true
    }
}
