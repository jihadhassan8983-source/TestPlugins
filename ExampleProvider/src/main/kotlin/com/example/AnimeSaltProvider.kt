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

    private val ua = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

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
        return if (root == mainUrl) mainUrl + "/tv/page/" + page + "/" else root + "/page/" + page + "/"
    }

    private fun clean(s: String): String {
        return s.replace("&quot;", "\"").replace("&#8211;", "-").replace("&#8217;", "'").replace("&amp;", "&")
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        doc.select("article.anime-card").forEach { card ->
            val a = card.selectFirst("a[href*=/tv/]") ?: return@forEach
            var href = a.attr("abs:href").ifBlank { a.attr("href") }
            if (href.isBlank()) return@forEach
            href = href.substringBefore("?").trimEnd('/') + "/"
            if (!seen.add(href)) return@forEach

            var title = a.selectFirst("h3")?.text()?.trim().orEmpty()
            if (title.isBlank()) title = a.selectFirst("img")?.attr("alt")?.trim().orEmpty()
            if (title.isBlank()) title = href.trimEnd('/').substringAfterLast('/').replace("-", " ")
            title = clean(title)

            var poster = a.selectFirst(".poster-wrap img")?.attr("src")
            if (poster.isNullOrBlank()) poster = a.selectFirst("img")?.attr("src")
            if (poster.isNullOrBlank()) {
                val oc = a.attr("onclick")
                Regex("""saveToWatchHistory\(.+?,.+?,['"]([^'"]+)['"]""").find(oc)?.let { poster = it.groupValues[1] }
            }
            if (!poster.isNullOrBlank() && poster.startsWith("//")) poster = "https:" + poster

            out.add(newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster })
        }

        // search
        if (out.isEmpty()) {
            doc.select("h2.entry-title a[href*=/tv/], article a[href*=/tv/]").forEach { a ->
                var href = a.attr("abs:href").ifBlank { a.attr("href") }
                if (!href.startsWith("http")) href = mainUrl + href
                href = href.substringBefore("?").trimEnd('/') + "/"
                if (!seen.add(href)) return@forEach
                val title = clean(a.text().trim())
                if (title.isNotBlank()) out.add(newAnimeSearchResponse(title, href, TvType.Anime))
            }
        }
        return out.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data == mainUrl + "/" || request.data == mainUrl) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
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
        val pageUrl = if (url.endsWith("/")) url else url + "/"
        val doc = app.get(pageUrl, headers = hdr()).document
        val html = clean(doc.html())

        val title = clean(
            doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("title")?.text()?.substringBefore(" Hindi")?.substringBefore(" –")?.trim()
                ?: pageUrl.trimEnd('/').substringAfterLast('/').replace("-", " ")
        )

        var poster = doc.selectFirst(".poster-wrap img")?.attr("src")
        if (poster.isNullOrBlank()) poster = doc.selectFirst("img[src*=anilist], img[src*=tmdb]")?.attr("src")
        if (poster.isNullOrBlank()) poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        if (!poster.isNullOrBlank() && poster.startsWith("//")) poster = "https:" + poster

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content") ?: doc.selectFirst(".entry-content p")?.text()

        val episodes = ArrayList<Episode>()
        val re = Regex("""triggerEpisode\(\[(.*?)]\s*,\s*"([^"]+)"\s*,\s*"(ep-\d+)""", RegexOption.DOT_MATCHES_ALL)
        var idx = 0
        re.findAll(html).forEach { m ->
            idx++
            val epName = clean(m.groupValues[2])
            val epNum = Regex("""(\d+)""").find(m.groupValues[3])?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: idx
            episodes.add(newEpisode(pageUrl + "||" + epNum) { this.name = epName; this.episode = epNum })
        }

        if (episodes.isEmpty()) {
            return newMovieLoadResponse(title, pageUrl, TvType.AnimeMovie, pageUrl + "||1") {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = plot
            }
        }

        val sorted = episodes.sortedWith(compareBy(nullsLast()) { it.episode })
        return newAnimeLoadResponse(title, pageUrl, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = plot
            addEpisodes(DubStatus.Dubbed, sorted)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        var pageUrl = data
        var epNum = 1
        if (data.contains("||")) {
            val p = data.split("||")
            pageUrl = p[0]
            epNum = p.getOrNull(1)?.toIntOrNull() ?: 1
        }
        if (!pageUrl.endsWith("/")) pageUrl = pageUrl + "/"

        val html = clean(app.get(pageUrl, headers = hdr()).text)

        val servers = ArrayList<Pair<String, String>>()
        val re = Regex("""triggerEpisode\(\[(.*?)]\s*,\s*"([^"]+)"\s*,\s*"(ep-\d+)""", RegexOption.DOT_MATCHES_ALL)
        re.findAll(html).forEach { m ->
            val block = m.groupValues[1]
            val epName = m.groupValues[2]
            val tag = m.groupValues[3]
            val n = Regex("""(\d+)""").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: Regex("""(\d+)""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            if (n != epNum) return@forEach

            Regex(""" "url":\s*"(https?://[^"]+)" """).findAll(block).forEach { um ->
                val u = um.groupValues[1]
                val chunkStart = maxOf(0, um.range.first - 120)
                val chunk = block.substring(chunkStart, minOf(block.length, um.range.last + 10))
                val lang = Regex(""" "lang":\s*"([^"]+)"""").find(chunk)?.groupValues?.get(1) ?: ""
                val name = Regex(""" "name":\s*"([^"]+)"""").find(chunk)?.groupValues?.get(1) ?: "HD"
                val label = (if (lang.isNotBlank()) lang + " " else "") + name
                servers.add(label to u)
            }
        }

        // fallback to first episode
        if (servers.isEmpty()) {
            re.find(html)?.let { m ->
                Regex(""" "url":\s*"(https?://[^"]+)" """).findAll(m.groupValues[1]).forEach { um ->
                    servers.add("Server" to um.groupValues[1])
                }
            }
        }

        if (servers.isEmpty()) return false

        var found = false
        for ((label, embed) in servers.distinctBy { it.second }) {
            try {
                if (embed.contains("vidmoly", ignoreCase = true) || embed.contains("vidmoly.to", ignoreCase = true)) {
                    if (extractVidmoly(embed, label, callback)) {
                        found = true
                        continue
                    }
                }
                if (loadExtractor(embed, mainUrl + "/", subtitleCallback, callback)) {
                    found = true
                    continue
                }

                // generic m3u8 / mp4
                val body = app.get(embed, headers = hdr(mainUrl + "/")).text
                Regex("""https?://[^"'\\s<>]+\.m3u8[^"'\\s<>]*""").findAll(body).forEach { mm ->
                    callback(ExtractorLink(this.name, label, mm.value, embed, Qualities.Unknown.value, true))
                    found = true
                }
                Regex("""https?://[^"'\\s<>]+\.mp4[^"'\\s<>]*""").findAll(body).forEach { mm ->
                    callback(ExtractorLink(this.name, label, mm.value, embed, Qualities.Unknown.value, false))
                    found = true
                }
            } catch (_: Exception) {
                continue
            }
        }
        return found
    }

    private suspend fun extractVidmoly(embed: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(embed, headers = mapOf("User-Agent" to ua, "Referer" to (mainUrl + "/"))).text
        val sources = LinkedHashSet<String>()
        Regex("""file:\s*['"](https?://[^'"]+\.m3u8[^'"]*)['"]""").findAll(html).forEach { sources.add(it.groupValues[1]) }
        Regex("""https?://[^"'\\s<>]+\.m3u8[^"'\\s<>]*""").findAll(html).forEach { sources.add(it.value) }

        if (sources.isEmpty()) return false
        for (src in sources) {
            callback(ExtractorLink(this.name, label, src, "https://vidmoly.org/", Qualities.Unknown.value, true))
        }
        return true
    }
                                 }
