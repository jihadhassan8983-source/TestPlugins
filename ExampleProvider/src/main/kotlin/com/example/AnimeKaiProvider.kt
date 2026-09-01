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
        "Referer" to (mainUrl + "/")
    )

    private val ajaxHeaders = mapOf(
        "User-Agent" to ua,
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to (mainUrl + "/"),
        "X-Requested-With" to "XMLHttpRequest"
    )

    override val mainPage = mainPageOf(
        "latest-updates" to "Latest",
        "sub-updates" to "Subbed",
        "dub-updates" to "Dubbed",
        (mainUrl + "/genres/action") to "Action",
        (mainUrl + "/genres/romance") to "Romance",
        (mainUrl + "/genres/isekai") to "Isekai",
        (mainUrl + "/genres/school") to "School",
        (mainUrl + "/genres/shounen") to "Shounen",
        (mainUrl + "/browse?status=completed") to "Completed"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val key = request.data
        val list: List<SearchResponse>

        if (!key.startsWith("http")) {
            if (page > 1) {
                return newHomePageResponse(request.name, emptyList(), false)
            }
            val raw = app.get(
                mainUrl + "/ajax/home/items?id=" + key,
                headers = ajaxHeaders
            ).text

            val html: String = try {
                val parsed = parseJson<HomeAjax>(raw)
                parsed.html ?: ""
            } catch (e: Exception) {
                raw
            }

            val doc = Jsoup.parse(html)
            list = doc.select("div.aitem").mapNotNull { el ->
                toSearchResult(el)
            }
        } else {
            val url: String = if (page <= 1) {
                key
            } else if (key.contains("?")) {
                key + "&page=" + page
            } else {
                key + "?page=" + page
            }
            val doc = app.get(url, headers = headers).document
            list = doc.select("div.aitem").mapNotNull { el ->
                toSearchResult(el)
            }
        }

        val unique = list.distinctBy { it.url }
        return newHomePageResponse(request.name, unique, unique.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val doc = app.get(mainUrl + "/browse?keyword=" + q, headers = headers).document
        return doc.select("div.aitem").mapNotNull { el ->
            toSearchResult(el)
        }.distinctBy { it.url }
    }

    private fun toSearchResult(el: Element): SearchResponse? {
        val a = el.selectFirst("a.title") ?: el.selectFirst("a.poster") ?: return null

        var href: String = a.attr("abs:href")
        if (href.isBlank()) {
            href = a.attr("href")
        }
        if (href.isBlank()) return null
        if (!href.contains("/watch/")) return null

        // strip /ep-N
        val epRegex = Regex("/ep-[0-9]+/?$")
        href = epRegex.replace(href, "").trimEnd('/')

        var title: String = a.attr("title")
        if (title.isBlank()) {
            title = a.text().trim()
        }
        val titleEl = el.selectFirst("a.title")
        if (title.isBlank() && titleEl != null) {
            title = titleEl.attr("title")
            if (title.isBlank()) title = titleEl.text().trim()
        }
        if (title.isBlank()) return null

        val img = el.selectFirst("img")
        var poster: String? = null
        if (img != null) {
            poster = img.attr("abs:src")
            if (poster.isNullOrBlank()) poster = img.attr("src")
        }

        val isMovie = title.contains("Movie", true) || title.contains("Film", true)

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.AnimeMovie) {
                this.posterUrl = poster
            }
        } else {
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val epStrip = Regex("/ep-[0-9]+/?$")
        val cleanUrl = epStrip.replace(url, "").trimEnd('/')

        val doc = app.get(cleanUrl, headers = headers).document

        var title = doc.selectFirst("h1")?.text()?.trim()
        if (title.isNullOrBlank()) {
            title = doc.selectFirst(".detail .title, .anime-title")?.text()?.trim()
        }
        if (title.isNullOrBlank()) {
            title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
        }
        if (title.isNullOrBlank()) {
            title = cleanUrl.substringAfterLast("/")
        }

        var poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        if (poster.isNullOrBlank()) {
            poster = doc.selectFirst(".poster img, img.poster")?.attr("abs:src")
        }

        var plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        if (plot.isNullOrBlank()) {
            plot = doc.selectFirst(".desc, .description, .synopsis")?.text()?.trim()
        }

        val slug = cleanUrl.substringAfterLast("/")
        val epLinks = doc.select("a[href*=/watch/]")
            .map { link ->
                var h = link.attr("abs:href")
                if (h.isBlank()) h = link.attr("href")
                h
            }
            .filter { h -> h.contains(slug) && h.contains("/ep-") }
            .distinct()
            .sortedBy { h ->
                val m = Regex("/ep-([0-9]+)").find(h)
                m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            }

        val episodes = ArrayList<Episode>()
        if (epLinks.isNotEmpty()) {
            for (epUrl in epLinks) {
                val m = Regex("/ep-([0-9]+)").find(epUrl)
                val num = m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
                episodes.add(
                    newEpisode(epUrl) {
                        this.name = "Episode " + num
                        this.episode = num
                        this.season = 1
                    }
                )
            }
        } else {
            episodes.add(
                newEpisode(cleanUrl) {
                    this.name = "Episode 1"
                    this.episode = 1
                }
            )
        }

        val isMovie = title.contains("Movie", true) ||
                title.contains("Film", true) ||
                episodes.size <= 1

        return if (isMovie) {
            newMovieLoadResponse(title, cleanUrl, TvType.AnimeMovie, episodes[0].data ?: cleanUrl) {
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
        val servers = doc.select("span.server[data-url], .server[data-url]")
        if (servers.isEmpty()) return false

        var found = false

        for (server in servers) {
            val streamPage = server.attr("data-url").trim()
            if (streamPage.isBlank()) continue
            if (!streamPage.startsWith("http")) continue

            val isDub = streamPage.contains("/dub")
            var label = server.text().trim()
            if (label.isBlank()) {
                label = if (isDub) "Dub" else "Sub"
            }

            try {
                val mpDoc = app.get(
                    streamPage,
                    headers = headers + mapOf("Referer" to mainUrl)
                ).document

                var playerId = mpDoc.selectFirst("#megaplay-player")?.attr("data-id")
                if (playerId.isNullOrBlank()) {
                    playerId = mpDoc.selectFirst("[data-id][data-mediaid]")?.attr("data-id")
                }
                if (playerId.isNullOrBlank()) {
                    val idMatch = Regex("data-id=\"([0-9]+)\"").find(mpDoc.html())
                    playerId = idMatch?.groupValues?.getOrNull(1)
                }
                if (playerId.isNullOrBlank()) continue

                val sourcesJson = app.get(
                    "https://megaplay.buzz/stream/getSources?id=" + playerId,
                    headers = mapOf(
                        "User-Agent" to ua,
                        "Referer" to streamPage,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to "application/json, text/javascript, */*; q=0.01"
                    )
                ).text

                val parsed = try {
                    parseJson<MegaSources>(sourcesJson)
                } catch (e: Exception) {
                    null
                } ?: continue

                val file = parsed.sources?.file
                if (file.isNullOrBlank()) continue

                val isM3u8 = file.contains(".m3u8", true)
                val nameLabel = if (isDub) ("Dub - " + label) else ("Sub - " + label)

                callback.invoke(
                    ExtractorLink(
                        name,
                        nameLabel,
                        file,
                        "https://megaplay.buzz/",
                        Qualities.Unknown.value,
                        isM3u8
                    )
                )
                found = true

                val tracks = parsed.tracks
                if (tracks != null) {
                    for (track in tracks) {
                        val subFile = track.file
                        if (subFile.isNullOrBlank()) continue
                        val subLabel = track.label ?: "English"
                        try {
                            subtitleCallback.invoke(SubtitleFile(subLabel, subFile))
                        } catch (e: Exception) {
                            // ignore
                        }
                    }
                }
            } catch (e: Exception) {
                // next server
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
