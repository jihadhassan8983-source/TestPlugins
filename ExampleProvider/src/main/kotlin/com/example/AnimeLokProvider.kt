@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.Jsoup
import java.net.URLEncoder

class AnimeLokProvider : MainAPI() {
    override var mainUrl = "https://animelok.live"
    override var name = "AnimeLok"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val ua =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,hi;q=0.8",
        "Referer" to (mainUrl + "/")
    )

    private val jsonHeaders = mapOf(
        "User-Agent" to ua,
        "Accept" to "application/json",
        "Referer" to (mainUrl + "/"),
        "Origin" to mainUrl
    )

    override val mainPage = mainPageOf(
        (mainUrl + "/home") to "Home",
        (mainUrl + "/latest-episode") to "Latest Episodes",
        (mainUrl + "/most-watched") to "Most Watched",
        (mainUrl + "/az-list") to "A-Z List"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) {
            return newHomePageResponse(request.name, emptyList(), false)
        }
        val doc = app.get(request.data, headers = headers).document
        val list = parseAnimeCards(doc.html())
        return newHomePageResponse(request.name, list, list.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val html = app.get(mainUrl + "/search?q=" + q, headers = headers).text
        return parseAnimeCards(html)
    }

    private fun parseAnimeCards(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html, mainUrl)
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        // Prefer SEO slugs like /anime/one-piece-21
        val anchors = doc.select("a[href*=/anime/]")
        for (a in anchors) {
            var href = a.attr("abs:href")
            if (href.isBlank()) href = a.attr("href")
            if (href.isBlank()) continue

            val slug = href.substringAfter("/anime/").substringBefore("/").substringBefore("?")
            if (slug.isBlank() || slug == "cover") continue
            // skip pure hash-only if we can; still allow, resolve later in load
            if (!seen.add(slug)) continue

            var title = a.attr("title").trim()
            if (title.isBlank()) title = a.text().trim()
            if (title.isBlank()) {
                title = slug.replace(Regex("-\\d+$"), "").replace("-", " ")
            }
            // skip nav junk
            if (title.equals("Home", true) || title.length < 2) continue

            val img = a.selectFirst("img")
            var poster: String? = null
            if (img != null) {
                poster = img.attr("abs:src")
                if (poster.isNullOrBlank()) poster = img.attr("src")
                if (poster.isNullOrBlank()) poster = img.attr("data-src")
            }

            val url = mainUrl + "/anime/" + slug
            out.add(
                newAnimeSearchResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster
                }
            )
        }
        return out.distinctBy { it.url }
    }

    /** Resolve hash id pages to SEO slug (one-piece-21) for API */
    private suspend fun resolveSlug(urlOrSlug: String): String {
        var slug = urlOrSlug.substringAfterLast("/anime/").substringAfterLast("/watch/")
            .substringBefore("/").substringBefore("?").trim()
        if (slug.isBlank()) slug = urlOrSlug.trim()

        // already SEO style: name-12345
        if (slug.contains("-") && slug.any { it.isDigit() } && !slug.matches(Regex("^[a-f0-9]{10,}$"))) {
            return slug
        }

        // hash page -> find real slug from page
        try {
            val html = app.get(mainUrl + "/anime/" + slug, headers = headers).text
            val m = Regex("\"slug\"\\s*:\\s*\"([a-z0-9-]+-\\d+)\"").find(html)
            if (m != null) return m.groupValues[1]

            val m2 = Regex("/anime/([a-z0-9]+(?:-[a-z0-9]+)+-\\d+)").findAll(html)
                .map { it.groupValues[1] }
                .firstOrNull { it != slug && it != "cover" }
            if (m2 != null) return m2
        } catch (_: Exception) {
        }
        return slug
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = resolveSlug(url)

        // Episode 1 = metadata + confirm API works
        val ep1Text = try {
            app.get(
                mainUrl + "/api/anime/" + slug + "/episodes/1",
                headers = jsonHeaders
            ).text
        } catch (e: Exception) {
            throw ErrorLoadingException("Anime not found: " + slug)
        }

        val ep1 = try {
            parseJson<EpResponse>(ep1Text)
        } catch (e: Exception) {
            throw ErrorLoadingException("Bad episode data")
        }

        val title = ep1.anime?.title ?: slug
        val anilistId = ep1.anime?.anilistId
        val poster = ep1.episode?.img

        // Collect episode numbers
        val epNums = LinkedHashSet<Int>()
        epNums.add(1)

        // episodes-range pages
        try {
            var page = 1
            var totalPages = 1
            while (page <= totalPages && page <= 60) {
                val rangeText = app.get(
                    mainUrl + "/api/anime/" + slug + "/episodes-range?page=" + page +
                            "&lang=ALL&pageSize=50",
                    headers = jsonHeaders
                ).text
                val range = try {
                    parseJson<RangeResponse>(rangeText)
                } catch (_: Exception) {
                    null
                }
                if (range == null) break
                totalPages = range.totalPages ?: 1
                range.episodes?.forEach { e ->
                    val n = e.number
                    if (n != null && n > 0) epNums.add(n)
                }
                page++
            }
        } catch (_: Exception) {
        }

        // If range empty, probe sequentially (short series)
        if (epNums.size <= 1) {
            var n = 2
            var misses = 0
            while (n <= 60 && misses < 3) {
                try {
                    val code = app.get(
                        mainUrl + "/api/anime/" + slug + "/episodes/" + n,
                        headers = jsonHeaders
                    ).code
                    if (code in 200..299) {
                        epNums.add(n)
                        misses = 0
                    } else {
                        misses++
                    }
                } catch (_: Exception) {
                    misses++
                }
                n++
            }
        } else {
            // fill small gaps for series under 200 eps
            val max = epNums.maxOrNull() ?: 1
            if (max <= 200) {
                for (i in 1..max) epNums.add(i)
            }
        }

        val episodes = epNums.sorted().map { num ->
            newEpisode(slug + "||" + num + "||" + (anilistId ?: 0)) {
                this.name = "Episode " + num
                this.episode = num
                this.season = 1
            }
        }

        val isMovie = episodes.size <= 1 && (title.contains("Movie", true) || title.contains("Film", true))

        return if (isMovie) {
            newMovieLoadResponse(title, mainUrl + "/anime/" + slug, TvType.AnimeMovie, episodes.firstOrNull()?.data ?: "") {
                this.posterUrl = poster
            }
        } else {
            newAnimeLoadResponse(title, mainUrl + "/anime/" + slug, TvType.Anime) {
                this.posterUrl = poster
                addEpisodes(DubStatus.Dubbed, episodes)
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
        if (data.isBlank()) return false

        val parts = data.split("||")
        val slug = parts.getOrNull(0) ?: return false
        val epNum = parts.getOrNull(1)?.toIntOrNull() ?: 1
        val anilistId = parts.getOrNull(2)?.toIntOrNull() ?: 0

        val epText = try {
            app.get(
                mainUrl + "/api/anime/" + slug + "/episodes/" + epNum,
                headers = jsonHeaders
            ).text
        } catch (_: Exception) {
            return false
        }

        val epData = try {
            parseJson<EpResponse>(epText)
        } catch (_: Exception) {
            return false
        }

        var found = false
        val servers = epData.episode?.servers ?: emptyList()
        val realAnilist = epData.anime?.anilistId ?: anilistId

        for (server in servers) {
            val url = server.url ?: continue
            if (!url.startsWith("http")) continue

            val lang = server.languages?.firstOrNull() ?: ""
            val tip = server.tip ?: server.name ?: "Server"
            val label = if (lang.isNotBlank()) (lang + " - " + tip) else tip

            try {
                // Direct m3u8 (best)
                if (url.contains(".m3u8")) {
                    callback.invoke(
                        ExtractorLink(
                            name,
                            label,
                            url,
                            mainUrl,
                            Qualities.Unknown.value,
                            true
                        )
                    )
                    found = true
                    continue
                }

                // Direct mp4
                if (url.contains(".mp4")) {
                    callback.invoke(
                        ExtractorLink(
                            name,
                            label,
                            url,
                            mainUrl,
                            Qualities.Unknown.value,
                            false
                        )
                    )
                    found = true
                    continue
                }

                // Built-in extractors (short.icu etc.)
                try {
                    if (loadExtractor(url, mainUrl, subtitleCallback, callback)) {
                        found = true
                        continue
                    }
                } catch (_: Exception) {
                }

                // Generic page scrape
                val body = app.get(url, headers = headers).text
                val reM3u8 = Regex("https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*")
                var got = false
                reM3u8.findAll(body).forEach { m ->
                    callback.invoke(
                        ExtractorLink(name, label, m.value, url, Qualities.Unknown.value, true)
                    )
                    found = true
                    got = true
                }
                if (!got) {
                    // still list as link fallback
                    callback.invoke(
                        ExtractorLink(name, label, url, mainUrl, Qualities.Unknown.value, false)
                    )
                    found = true
                }
            } catch (_: Exception) {
                continue
            }
        }

        // MegaPlay fallback (AniStream) if anilistId known
        if (realAnilist > 0) {
            for (type in listOf("sub", "dub")) {
                try {
                    if (extractMegaPlay(realAnilist, epNum, type, callback)) {
                        found = true
                    }
                } catch (_: Exception) {
                }
            }
        }

        return found
    }

    /** megaplay.buzz/stream/ani/{anilistId}/{ep}/sub|dub -> getSources m3u8 */
    private suspend fun extractMegaPlay(
        anilistId: Int,
        epNum: Int,
        type: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val streamPage = "https://megaplay.buzz/stream/ani/" + anilistId + "/" + epNum + "/" + type
        val mpHtml = app.get(
            streamPage,
            headers = mapOf(
                "User-Agent" to ua,
                "Referer" to mainUrl
            )
        ).text

        var playerId = Regex("data-id=\"([0-9]+)\"").find(mpHtml)?.groupValues?.getOrNull(1)
        if (playerId.isNullOrBlank()) return false

        val sourcesJson = app.get(
            "https://megaplay.buzz/stream/getSources?id=" + playerId,
            headers = mapOf(
                "User-Agent" to ua,
                "Referer" to streamPage,
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "application/json"
            )
        ).text

        val file = Regex("\"file\"\\s*:\\s*\"(https?://[^\"]+)\"").find(sourcesJson)
            ?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")
            ?: return false

        callback.invoke(
            ExtractorLink(
                name,
                "AniStream " + type.uppercase(),
                file,
                "https://megaplay.buzz/",
                Qualities.Unknown.value,
                file.contains(".m3u8")
            )
        )
        return true
    }

    // ---- JSON models ----
    private data class EpResponse(
        @JsonProperty("anime") val anime: AnimeInfo? = null,
        @JsonProperty("episode") val episode: EpisodeInfo? = null
    )

    private data class AnimeInfo(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("anilistId") val anilistId: Int? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("title") val title: String? = null
    )

    private data class EpisodeInfo(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("img") val img: String? = null,
        @JsonProperty("servers") val servers: List<ServerInfo>? = null
    )

    private data class ServerInfo(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("tip") val tip: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("languages") val languages: List<String>? = null
    )

    private data class RangeResponse(
        @JsonProperty("episodes") val episodes: List<RangeEp>? = null,
        @JsonProperty("totalPages") val totalPages: Int? = null
    )

    private data class RangeEp(
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("name") val name: String? = null
    )
}
