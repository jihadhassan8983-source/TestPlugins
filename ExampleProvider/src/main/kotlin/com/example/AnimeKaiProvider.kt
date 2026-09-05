@file:Suppress(
    "PackageName",
    "SpellCheckingInspection",
    "UnusedImport",
    "UNUSED_PARAMETER",
    "MemberVisibilityCanBePrivate",
    "FunctionName"
)

package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class AnimeKaiProvider : MainAPI() {
    override var mainUrl = "https://animekai.be"
    override var name = "AnimeKai"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val hdr = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )
    private val megaReferer = "https://megaplay.buzz/"

    override val mainPage = mainPageOf(
        "$mainUrl/home" to "Home",
        "$mainUrl/browse?sort=updated" to "Recently Updated",
        "$mainUrl/browse?sort=trending" to "Trending",
        "$mainUrl/browse?type=tv" to "TV Series",
        "$mainUrl/browse?type=movie" to "Movies",
        "$mainUrl/browse?status=completed" to "Completed"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data
        else if ("?" in request.data) "${request.data}&page=$page"
        else "${request.data}?page=$page"
        val doc = app.get(url, headers = hdr).document
        val list = parseCards(doc)
        return newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/browse?keyword=$q", headers = hdr).document
        return parseCards(doc)
    }

    private fun parseCards(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        doc.select("a[href*=/watch/]").forEach { a ->
            val href = a.attr("href").substringBefore("?").trim()
            if (href.isBlank() || "/ep-" in href) return@forEach
            val url = if (href.startsWith("http")) href else mainUrl + href
            val key = url.substringAfter("/watch/").substringBefore("/").lowercase()
            if (key.isBlank() || !seen.add(key)) return@forEach
            val img = a.selectFirst("img")
            val title = (img?.attr("alt")?.ifBlank { null }
                ?: a.attr("title").ifBlank { null }
                ?: a.text().ifBlank { null }
                ?: key.replace('-', ' ')).trim()
            if (title.isBlank()) return@forEach
            var poster = img?.attr("data-src")?.ifBlank { null }
                ?: img?.attr("src")?.ifBlank { null }
            if (poster != null && poster.startsWith("//")) poster = "https:$poster"
            out.add(
                newAnimeSearchResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster
                }
            )
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse? {
        val clean = url.substringBefore("?").trimEnd('/')
        val watchUrl = if ("/ep-" in clean) clean.replace(Regex("/ep-\\d+$"), "") else clean
        val doc = app.get(watchUrl, headers = hdr).document

        val title = doc.selectFirst("h1, .title, .anime-title, meta[property=og:title]")?.let {
            it.attr("content").ifBlank { it.text() }
        }?.replace(Regex("\\s*\\|\\s*.*$"), "")?.trim()
            ?: watchUrl.substringAfterLast("/").replace('-', ' ')

        var poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".poster img, .anime-poster img, img.poster")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }
        if (poster != null && poster.startsWith("//")) poster = "https:$poster"

        val plot = doc.selectFirst(".synopsis, .description, .anime-synopsis, meta[property=og:description]")
            ?.let { it.attr("content").ifBlank { it.text() } }?.trim()

        val tags = doc.select(".genres a, .genre a, a[href*=genre]").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()

        val year = Regex("""(?:Release|Year)[^0-9]{0,10}(20\d{2}|19\d{2})""", RegexOption.IGNORE_CASE)
            .find(doc.text())?.groupValues?.getOrNull(1)?.toIntOrNull()

        val actors = doc.select(".character, .cast a, .actors a").mapNotNull {
            val n = it.text().trim()
            if (n.isBlank()) null else Actor(n)
        }.distinctBy { it.name }.take(20)

        val slug = watchUrl.substringAfter("/watch/").substringBefore("/").substringBefore("?")
        val epMap = linkedMapOf<Int, String>()
        doc.select("a[href*=/watch/$slug/ep-]").forEach { a ->
            val href = a.attr("href")
            val num = Regex("""/ep-(\d+)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@forEach
            val epUrl = if (href.startsWith("http")) href else mainUrl + href
            epMap[num] = epUrl.substringBefore("?")
        }
        if (epMap.isEmpty()) {
            val maxEp = doc.select("[data-ep]").mapNotNull { it.attr("data-ep").toIntOrNull() }
                .filter { it in 1..5000 }.maxOrNull() ?: 1
            for (i in 1..maxEp) {
                epMap[i] = "$mainUrl/watch/$slug/ep-$i"
            }
        }

        val episodes = epMap.entries.sortedBy { it.key }.map { (num, epUrl) ->
            newEpisode(epUrl) {
                this.name = "Episode $num"
                this.episode = num
            }
        }

        val isMovie = episodes.size <= 1 && (
            doc.text().contains("Movie", true) || watchUrl.contains("movie", true)
            )

        return if (isMovie) {
            newMovieLoadResponse(title, watchUrl, TvType.AnimeMovie, episodes.firstOrNull()?.data ?: watchUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                addActors(actors)
            }
        } else {
            newTvSeriesLoadResponse(title, watchUrl, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epUrl = data.substringBefore("?").trim()
        val doc = app.get(epUrl, headers = hdr).document

        val servers = doc.select(".server[data-url], span.server[data-url], [data-url*=megaplay]")
            .mapNotNull { el ->
                val u = el.attr("data-url").trim()
                if (u.isBlank() || "megaplay" !in u) return@mapNotNull null
                val label = el.text().trim().ifBlank {
                    if ("/dub" in u) "Dub" else "Sub"
                }
                val type = when {
                    "/dub" in u -> "Dub"
                    "/sub" in u -> "Sub"
                    else -> label
                }
                Triple(u, type, label)
            }.distinctBy { it.first }

        if (servers.isEmpty()) return false

        var found = false
        for ((megaUrl, type, _) in servers) {
            try {
                val links = resolveMegaplay(megaUrl)
                for ((name, m3u8, subs) in links) {
                    found = true
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$type - $name",
                            url = m3u8,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = megaReferer
                            this.quality = Qualities.P1080.value
                            this.headers = mapOf(
                                "User-Agent" to ua,
                                "Referer" to megaReferer,
                                "Origin" to "https://megaplay.buzz"
                            )
                        }
                    )
                    for ((subUrl, subLang) in subs) {
                        subtitleCallback(SubtitleFile(subLang, subUrl))
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return found
    }

    private suspend fun resolveMegaplay(megaUrl: String): List<Triple<String, String, List<Pair<String, String>>>> {
        val out = ArrayList<Triple<String, String, List<Pair<String, String>>>>()
        val page = app.get(
            megaUrl,
            headers = hdr + mapOf("Referer" to "$mainUrl/")
        ).text

        val fileId = Regex("""data-id=["'](\d+)["']""").find(page)?.groupValues?.getOrNull(1)
            ?: Regex("""File\s+(\d+)""", RegexOption.IGNORE_CASE).find(page)?.groupValues?.getOrNull(1)
            ?: return out

        val endpoints = listOf(
            "https://megaplay.buzz/stream/getSourcesNew?id=$fileId",
            "https://megaplay.buzz/stream/getSources?id=$fileId"
        )

        for (api in endpoints) {
            try {
                val json = app.get(
                    api,
                    headers = mapOf(
                        "User-Agent" to ua,
                        "Referer" to megaUrl,
                        "Origin" to "https://megaplay.buzz",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to "application/json, text/javascript, */*; q=0.01"
                    )
                ).text
                val file = Regex(""""file"\s*:\s*"([^"]+)"""").find(json)
                    ?.groupValues?.getOrNull(1)
                    ?.replace("\\/", "/")
                    ?: continue
                if (!file.contains(".m3u8") && !file.startsWith("http")) continue

                val subs = ArrayList<Pair<String, String>>()
                Regex(""""file"\s*:\s*"([^"]+)"\s*,\s*"label"\s*:\s*"([^"]+)"""").findAll(json)
                    .forEach { m ->
                        val su = m.groupValues[1].replace("\\/", "/")
                        val lab = m.groupValues[2]
                        if (su.contains(".vtt") || su.contains("subtitle")) {
                            subs.add(su to lab)
                        }
                    }

                out.add(Triple("MegaPlay", file, subs))
                break
            } catch (_: Throwable) {
            }
        }
        return out
    }
                      }
