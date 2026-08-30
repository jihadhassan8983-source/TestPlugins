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
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to ua,
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to (mainUrl + "/")
    )

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
        if (root == mainUrl) return mainUrl + "/tv/page/" + page + "/"
        return root + "/page/" + page + "/"
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        doc.select("article.anime-card a.card-link, article.anime-card a[href*=/tv/]").forEach { a ->
            var href = a.attr("abs:href")
            if (!href.contains("/tv/")) return@forEach
            href = href.substringBefore("?").trimEnd('/') + "/"
            if (!seen.add(href)) return@forEach
            val title = a.selectFirst("h3")?.text()?.trim()
                ?: a.selectFirst("img")?.attr("alt")?.trim()
                ?: href.trimEnd('/').substringAfterLast('/').replace("-", " ")
            if (title.isBlank()) return@forEach
            val poster = a.selectFirst(".poster-wrap img")?.attr("src")
                ?: a.selectFirst("img")?.attr("src")
            out.add(
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                }
            )
        }

        // search page: entry-title links
        if (out.isEmpty()) {
            doc.select("h2.entry-title a[href*=/tv/], article a[href*=/tv/]").forEach { a ->
                var href = a.attr("abs:href")
                if (!href.contains("/tv/")) return@forEach
                href = href.substringBefore("?").trimEnd('/') + "/"
                if (!seen.add(href)) return@forEach
                val title = a.text().trim().ifBlank { return@forEach }
                out.add(newAnimeSearchResponse(title, href, TvType.Anime))
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
        val doc = app.get(url, headers = headers).document
        val list = parseCards(doc)
        return newHomePageResponse(request.name, list, list.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val doc = app.get(mainUrl + "/?s=" + q, headers = headers).document
        return parseCards(doc)
    }

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = if (url.endsWith("/")) url else url + "/"
        val doc = app.get(pageUrl, headers = headers).document
        val html = doc.html()

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.substringBefore(" Hindi")?.substringBefore(" –")?.trim()
            ?: pageUrl.trimEnd('/').substringAfterLast('/').replace("-", " ")

        val poster = doc.selectFirst(".poster-wrap img")?.attr("src")
            ?: doc.selectFirst("img[src*=anilist]")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst(".entry-content p")?.text()

        // Episodes embedded in triggerEpisode([...], "Episode N", "ep-N"
        val episodes = ArrayList<Episode>()
        val re = Regex(
            """triggerEpisode\(\[(.*?)\],\s*(?:&quot;|")([^"&]+)(?:&quot;|")\s*,\s*(?:&quot;|")(ep-\d+)(?:&quot;|")""",
            RegexOption.DOT_MATCHES_ALL
        )
        var idx = 0
        re.findAll(html).forEach { m ->
            idx++
            var json = m.groupValues[1]
                .replace("&quot;", "\"")
                .replace("\\/", "/")
                .replace("&amp;", "&")
            val epName = m.groupValues[2]
                .replace("&quot;", "\"")
                .replace("&#8217;", "'")
            val epNum = Regex("""(\d+)""").find(m.groupValues[3])?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                ?: idx
            // data = JSON array of servers
            val data = "[" + json + "]"
            episodes.add(
                newEpisode(data) {
                    this.name = epName
                    this.episode = epNum
                }
            )
        }

        if (episodes.isEmpty()) {
            return newMovieLoadResponse(title, pageUrl, TvType.AnimeMovie, pageUrl) {
                this.posterUrl = poster
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data is JSON array: [{"lang":"Hindi","name":"HD-1","url":"https://vidmoly.org/embed-xxx.html"},...]
        val servers = ArrayList<Pair<String, String>>()
        Regex(""""url"\s*:\s*"(https?://[^"]+)"""")
            .findAll(data).forEach { m ->
                val url = m.groupValues[1].replace("\\/", "/")
                val chunkStart = maxOf(0, m.range.first - 120)
                val chunk = data.substring(chunkStart, minOf(data.length, m.range.last + 20))
                val lang = Regex(""""lang"\s*:\s*"([^"]+)"""").find(chunk)?.groupValues?.get(1) ?: ""
                val name = Regex(""""name"\s*:\s*"([^"]+)"""").find(chunk)?.groupValues?.get(1) ?: "Server"
                val label = (if (lang.isNotBlank()) lang + " " else "") + name
                servers.add(label to url)
            }

        // fallback: if load passed anime page url
        if (servers.isEmpty() && data.startsWith("http") && data.contains("animesalt")) {
            try {
                val html = app.get(data, headers = headers).text
                Regex("""triggerEpisode\(\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
                    .find(html)?.groupValues?.get(1)?.let { raw ->
                        val fixed = raw.replace("&quot;", "\"").replace("\\/", "/")
                        Regex(""""url"\s*:\s*"(https?://[^"]+)"""")
                            .findAll(fixed).forEach { m ->
                                servers.add("Server" to m.groupValues[1])
                            }
                    }
            } catch (e: Exception) {
            }
        }

        if (servers.isEmpty()) return false

        var found = false
        for ((label, embed) in servers) {
            try {
                if (embed.contains("vidmoly", true) || embed.contains("vidmoly.to", true)) {
                    if (extractVidmoly(embed, label, callback)) {
                        found = true
                        continue
                    }
                }
                // Cloudstream built-in extractors for other hosts
                if (loadExtractor(embed, mainUrl + "/", subtitleCallback, callback)) {
                    found = true
                    continue
                }
                // generic m3u8 scrape
                val html = app.get(
                    embed,
                    headers = headers + mapOf("Referer" to (mainUrl + "/"))
                ).text
                Regex("""https?://[^"'\\s]+\.m3u8[^"'\\s]*""").findAll(html).forEach { m ->
                    val src = m.value
                    callback.invoke(
                        ExtractorLink(
                            name,
                            label,
                            src,
                            embed,
                            Qualities.Unknown.value,
                            true
                        )
                    )
                    found = true
                }
            } catch (e: Exception) {
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
                "Referer" to (mainUrl + "/"),
                "Accept" to "*/*"
            )
        ).text
        val sources = LinkedHashSet<String>()
        Regex("""file:\s*['"](https?://[^'"]+\.m3u8[^'"]*)['"]""")
            .findAll(html).forEach { sources.add(it.groupValues[1]) }
        Regex("""https?://[^"'\\s]+\.m3u8[^"'\\s]*""")
            .findAll(html).forEach { sources.add(it.value) }
        if (sources.isEmpty()) return false
        for (src in sources) {
            callback.invoke(
                ExtractorLink(
                    name,
                    label,
                    src,
                    "https://vidmoly.org/",
                    Qualities.Unknown.value,
                    true,
                    mapOf(
                        "User-Agent" to ua,
                        "Referer" to "https://vidmoly.org/",
                        "Origin" to "https://vidmoly.org"
                    )
                )
            )
        }
        return true
    }
                              }
