@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeKaiProvider : MainAPI() {
    override var mainUrl = "https://animekai.be"
    override var name = "AnimeKai"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val ua =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/"
    )

    private val ajaxHeaders = headers + mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/json, text/javascript, */*; q=0.01"
    )

    override val mainPage = mainPageOf(
        "latest-updates" to "Latest",
        "sub-updates" to "Subbed",
        "dub-updates" to "Dubbed",
        "$mainUrl/genres/action" to "Action",
        "$mainUrl/genres/romance" to "Romance",
        "$mainUrl/genres/isekai" to "Isekai",
        "$mainUrl/genres/school" to "School",
        "$mainUrl/genres/shounen" to "Shounen",
        "$mainUrl/browse?status=completed" to "Completed"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val key = request.data
        val list: List<SearchResponse>

        if (!key.startsWith("http")) {
            // ajax home tabs
            if (page > 1) {
                return newHomePageResponse(request.name, emptyList(), false)
            }
            val json = app.get(
                "$mainUrl/ajax/home/items?id=$key",
                headers = ajaxHeaders
            ).text
            val html = try {
                parseJson<HomeAjax>(json).html ?: ""
            } catch (_: Exception) {
                // sometimes raw html
                json
            }
            val doc = Jsoup.parse(html)
            list = doc.select("div.aitem").mapNotNull { it.toSearchResult() }
        } else {
            val url = if (page <= 1) key else {
                val sep = if (key.contains("?")) "&" else "?"
                "\( key \){sep}page=$page"
            }
            val doc = app.get(url, headers = headers).document
            list = doc.select("div.aitem").mapNotNull { it.toSearchResult() }
        }

        return newHomePageResponse(request.name, list.distinctBy { it.url }, list.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/browse?keyword=$q", headers = headers).document
        return doc.select("div.aitem").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a.title, a.poster") ?: return null
        var href = a.attr("abs:href")
        if (href.isBlank()) href = a.attr("href")
        if (href.isBlank() || !href.contains("/watch/")) return null
        // normalize to series page (strip /ep-N)
        href = href.replace(Regex("/ep-\\d+/?$"), "").trimEnd('/')

        val title = this.selectFirst("a.title")?.attr("title")
            ?: this.selectFirst("a.title")?.text()
            ?: a.attr("title")
            ?: return null
        if (title.isBlank()) return null

        val poster = this.selectFirst("img")?.let {
            it.attr("abs:src").ifBlank { it.attr("src") }
        }

        val isMovie = title.contains("Movie", true) || title.contains("Film", true)

        return if (isMovie) {
            newMovieSearchResponse(title.trim(), href, TvType.AnimeMovie) {
                this.posterUrl = poster
            }
        } else {
            newAnimeSearchResponse(title.trim(), href, TvType.Anime) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = url.replace(Regex("/ep-\\d+/?$"), "").trimEnd('/')
        val doc = app.get(cleanUrl, headers = headers).document

        val title = doc.selectFirst("h1, .title, .anime-title, .detail .title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: cleanUrl.substringAfterLast("/")

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".poster img, .detail img, img.poster")?.attr("abs:src")

        val plot = doc.selectFirst(".desc, .description, .synopsis, meta[name=description]")
            ?.let { if (it.tagName() == "meta") it.attr("content") else it.text() }
            ?.trim()

        // Episodes: /watch/slug/ep-1, ep-2, ...
        val epLinks = doc.select("a[href*=/watch/]")
            .map { it.attr("abs:href").ifBlank { it.attr("href") } }
            .filter { it.contains(cleanUrl.substringAfterLast("/")) && it.contains("/ep-") }
            .distinct()
            .sortedBy {
                Regex("/ep-(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            }

        val episodes = ArrayList<Episode>()
        if (epLinks.isNotEmpty()) {
            for (epUrl in epLinks) {
                val num = Regex("/ep-(\\d+)").find(epUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: continue
                episodes.add(
                    newEpisode(epUrl) {
                        this.name = "Episode $num"
                        this.episode = num
                        this.season = 1
                    }
                )
            }
        } else {
            // single page with servers only (movie / ep1)
            episodes.add(
                newEpisode(cleanUrl) {
                    this.name = "Episode 1"
                    this.episode = 1
                }
            )
        }

        val isMovie = title.contains("Movie", true) || title.contains("Film", true) || episodes.size <= 1

        return if (isMovie) {
            newMovieLoadResponse(title, cleanUrl, TvType.AnimeMovie, episodes.first().data ?: cleanUrl) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newAnimeLoadResponse(title, cleanUrl, TvType.Anime) {
                this.posterUrl = poster
                this.plot = plot
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = headers).document

        // server buttons: data-url = megaplay.buzz/stream/...
        val servers = doc.select(".server[data-url], span.server[data-url]")
        if (servers.isEmpty()) return false

        var found = false

        for (server in servers) {
            val streamPage = server.attr("data-url").trim()
            if (streamPage.isBlank() || !streamPage.startsWith("http")) continue

            val label = server.text().trim().ifBlank {
                when {
                    streamPage.endsWith("/dub") -> "Dub"
                    streamPage.endsWith("/sub") -> "Sub"
                    else -> "Server"
                }
            }
            val isDub = streamPage.contains("/dub")

            try {
                // Open MegaPlay page → get player data-id
                val mpDoc = app.get(
                    streamPage,
                    headers = headers + mapOf("Referer" to mainUrl)
                ).document

                val playerId = mpDoc.selectFirst("#megaplay-player[data-id], [data-id][data-mediaid]")
                    ?.attr("data-id")
                    ?: Regex("""data-id=["'](\d+)["']""").find(mpDoc.html())?.groupValues?.getOrNull(1)

                if (playerId.isNullOrBlank()) continue

                // getSources → m3u8
                val sourcesJson = app.get(
                    "https://megaplay.buzz/stream/getSources?id=$playerId",
                    headers = mapOf(
                        "User-Agent" to ua,
                        "Referer" to streamPage,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to "application/json, text/javascript, */*; q=0.01"
                    )
                ).text

                val parsed = try {
                    parseJson<MegaSources>(sourcesJson)
                } catch (_: Exception) {
                    null
                } ?: continue

                val file = parsed.sources?.file
                if (file.isNullOrBlank()) continue

                val isM3u8 = file.contains(".m3u8", true)

                callback.invoke(
                    ExtractorLink(
                        name,
                        if (isDub) "Dub • $label" else "Sub • $label",
                        file,
                        "https://megaplay.buzz/",
                        Qualities.Unknown.value,
                        isM3u8
                    )
                )
                found = true

                // subtitles
                parsed.tracks?.forEach { track ->
                    val subFile = track.file ?: return@forEach
                    val subLabel = track.label ?: "Subtitle"
                    try {
                        subtitleCallback.invoke(
                            SubtitleFile(subLabel, subFile)
                        )
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
                // try next server
            }
        }
        return found
    }

    private data class HomeAjax(
        @JsonProperty("html") val html: String? = null
    )

    private data class MegaSources(
        @JsonProperty("sources") val sources: MegaFile? = null,
        @JsonProperty("tracks") val tracks: List<MegaTrack>? = null
    )

    private data class MegaFile(
        @JsonProperty("file") val file: String? = null
    )

    private data class MegaTrack(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )
}
