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
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
        val html = app.get(request.data, headers = headers).text
        val list = parseCards(html)
        return newHomePageResponse(request.name, list, list.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val html = app.get(mainUrl + "/search?q=" + q, headers = headers).text
        return parseCards(html)
    }

    private fun parseCards(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html, mainUrl)
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        // 1) /anime/slug cards
        for (a in doc.select("a[href*=/anime/]")) {
            var href = a.attr("abs:href")
            if (href.isBlank()) href = a.attr("href")
            if (href.isBlank()) continue

            var slug = href.substringAfter("/anime/").substringBefore("/").substringBefore("?")
            if (slug.isBlank() || slug == "cover") continue
            if (!seen.add(slug)) continue

            var title = ""
            val img = a.selectFirst("img")
            if (img != null) {
                title = img.attr("alt").trim()
            }
            if (title.isBlank() || title.equals("Animelok", true) || title.contains("logo", true)) {
                title = a.attr("title").trim()
            }
            if (title.isBlank()) title = a.text().trim()
            if (title.isBlank() || title.equals("Home", true)) {
                title = slugToTitle(slug)
            }
            if (title.length < 2) continue

            var poster: String? = null
            if (img != null) {
                poster = img.attr("abs:src")
                if (poster.isNullOrBlank()) poster = img.attr("src")
                if (poster.isNullOrBlank()) poster = img.attr("data-src")
            }

            out.add(
                newAnimeSearchResponse(title, mainUrl + "/anime/" + slug, TvType.Anime) {
                    this.posterUrl = poster
                }
            )
        }

        // 2) /watch/slug?ep=  (Latest Episodes page)
        for (a in doc.select("a[href*=/watch/]")) {
            var href = a.attr("abs:href")
            if (href.isBlank()) href = a.attr("href")
            if (href.isBlank()) continue

            var slug = href.substringAfter("/watch/").substringBefore("?").substringBefore("/")
            if (slug.isBlank()) continue
            if (!seen.add(slug)) continue

            var title = ""
            val img = a.selectFirst("img")
            if (img != null) title = img.attr("alt").trim()
            if (title.isBlank()) title = a.attr("title").trim()
            if (title.isBlank()) title = a.text().trim()
            if (title.isBlank()) title = slugToTitle(slug)

            var poster: String? = null
            if (img != null) {
                poster = img.attr("abs:src")
                if (poster.isNullOrBlank()) poster = img.attr("src")
            }

            out.add(
                newAnimeSearchResponse(title, mainUrl + "/anime/" + slug, TvType.Anime) {
                    this.posterUrl = poster
                }
            )
        }

        return out.distinctBy { it.url }
    }

    private fun slugToTitle(slug: String): String {
        val base = slug.replace(Regex("-\\d+$"), "").replace("-", " ").trim()
        if (base.isBlank()) return slug
        return base.split(" ").joinToString(" ") { w ->
            if (w.isEmpty()) w else w.replaceFirstChar { c -> c.uppercaseChar() }
        }
    }

    private suspend fun resolveSlug(urlOrSlug: String): String {
        var slug = urlOrSlug
            .substringAfterLast("/anime/")
            .substringAfterLast("/watch/")
            .substringBefore("/")
            .substringBefore("?")
            .trim()
        if (slug.isBlank()) slug = urlOrSlug.trim()

        // SEO slug: name-123
        if (Regex(".*-\\d+\( ").matches(slug) && !Regex("^[a-f0-9]{12,} \)").matches(slug)) {
            return slug
        }

        // Hash page -> find SEO slug
        try {
            val html = app.get(mainUrl + "/anime/" + slug, headers = headers).text
            val m = Regex("\"slug\"\\s*:\\s*\"([a-z0-9-]+-\\d+)\"").find(html)
            if (m != null) return m.groupValues[1]

            val found = Regex("/anime/([a-z0-9]+(?:-[a-z0-9]+)+-\\d+)").findAll(html)
                .map { it.groupValues[1] }
                .firstOrNull { it != slug && it != "cover" }
            if (found != null) return found

            val watch = Regex("/watch/([a-z0-9]+(?:-[a-z0-9]+)+-\\d+)").find(html)
            if (watch != null) return watch.groupValues[1]
        } catch (_: Exception) {
        }
        return slug
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = resolveSlug(url)

        val ep1Text = app.get(
            mainUrl + "/api/anime/" + slug + "/episodes/1",
            headers = jsonHeaders
        ).text

        val ep1 = try {
            parseJson<EpResponse>(ep1Text)
        } catch (e: Exception) {
            throw ErrorLoadingException("Cannot load anime: " + slug)
        }

        val title = ep1.anime?.title ?: slugToTitle(slug)
        val anilistId = ep1.anime?.anilistId ?: 0
        val poster = ep1.episode?.img

        val epNums = LinkedHashSet<Int>()
        epNums.add(1)

        // episodes-range
        try {
            var page = 1
            var totalPages = 1
            while (page <= totalPages && page <= 80) {
                val rangeText = app.get(
                    mainUrl + "/api/anime/" + slug +
                            "/episodes-range?page=" + page + "&lang=ALL&pageSize=50",
                    headers = jsonHeaders
                ).text
                val range = try {
                    parseJson<RangeResponse>(rangeText)
                } catch (_: Exception) {
                    null
                } ?: break

                totalPages = range.totalPages ?: 1
                val list = range.episodes
                if (list != null) {
                    for (e in list) {
                        val n = e.number
                        if (n != null && n > 0) epNums.add(n)
                    }
                }
                page++
            }
        } catch (_: Exception) {
        }

        // probe if still only ep 1
        if (epNums.size <= 1) {
            var n = 2
            var miss = 0
            while (n <= 40 && miss < 2) {
                try {
                    val t = app.get(
                        mainUrl + "/api/anime/" + slug + "/episodes/" + n,
                        headers = jsonHeaders
                    ).text
                    if (t.contains("\"number\"")) {
                        epNums.add(n)
                        miss = 0
                    } else {
                        miss++
                    }
                } catch (_: Exception) {
                    miss++
                }
                n++
            }
        } else {
            val max = epNums.maxOrNull() ?: 1
            if (max <= 150) {
                for (i in 1..max) epNums.add(i)
            }
        }

        val episodes = ArrayList<Episode>()
        for (num in epNums.sorted()) {
            episodes.add(
                newEpisode(slug + "||" + num + "||" + anilistId) {
                    this.name = "Episode " + num
                    this.episode = num
                    this.season = 1
                }
            )
        }

        return newAnimeLoadResponse(title, mainUrl + "/anime/" + slug, TvType.Anime) {
            this.posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes)
            addEpisodes(DubStatus.Dubbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank() || !data.contains("||")) return false

        val parts = data.split("||")
        val slug = parts[0]
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
            val rawUrl = server.url ?: continue
            val lang = server.languages?.firstOrNull() ?: ""
            val tip = server.tip ?: server.name ?: "Server"
            val baseLabel = if (lang.isNotBlank()) (lang + " - " + tip) else tip

            // Expand URL list (plain string OR JSON array string)
            val urls = expandUrls(rawUrl)

            for ((quality, mediaUrl) in urls) {
                if (!mediaUrl.startsWith("http")) continue
                val label = if (quality.isNotBlank()) (baseLabel + " " + quality) else baseLabel

                try {
                    if (mediaUrl.contains(".m3u8")) {
                        callback.invoke(
                            ExtractorLink(
                                name,
                                label,
                                mediaUrl,
                                mainUrl + "/",
                                qualityToInt(quality),
                                true
                            )
                        )
                        found = true
                        continue
                    }

                    if (mediaUrl.contains(".mp4")) {
                        callback.invoke(
                            ExtractorLink(
                                name,
                                label,
                                mediaUrl,
                                mainUrl + "/",
                                qualityToInt(quality),
                                false
                            )
                        )
                        found = true
                        continue
                    }

                    try {
                        if (loadExtractor(mediaUrl, mainUrl, subtitleCallback, callback)) {
                            found = true
                            continue
                        }
                    } catch (_: Exception) {
                    }

                    // last resort: try open page for m3u8
                    try {
                        val body = app.get(mediaUrl, headers = headers).text
                        val re = Regex("https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*")
                        re.findAll(body).forEach { m ->
                            callback.invoke(
                                ExtractorLink(
                                    name,
                                    label,
                                    m.value,
                                    mediaUrl,
                                    Qualities.Unknown.value,
                                    true
                                )
                            )
                            found = true
                        }
                    } catch (_: Exception) {
                    }
                } catch (_: Exception) {
                }
            }
        }

        // MegaPlay backup
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

    /** url can be normal link OR JSON array string of {url, quality} */
    private fun expandUrls(raw: String): List<Pair<String, String>> {
        val t = raw.trim()
        val out = ArrayList<Pair<String, String>>()

        if (t.startsWith("[")) {
            try {
                val arr = parseJson<List<PaheItem>>(t)
                for (item in arr) {
                    val u = item.url ?: continue
                    out.add((item.quality ?: "") to u)
                }
            } catch (_: Exception) {
                // regex fallback
                val re = Regex("\"url\"\\s*:\\s*\"(https?://[^\"]+)\"")
                val rq = Regex("\"quality\"\\s*:\\s*\"([^\"]+)\"")
                val qualities = rq.findAll(t).map { it.groupValues[1] }.toList()
                var i = 0
                re.findAll(t).forEach { m ->
                    val q = qualities.getOrNull(i) ?: ""
                    out.add(q to m.groupValues[1].replace("\\/", "/"))
                    i++
                }
            }
        } else if (t.startsWith("http")) {
            out.add("" to t)
        }
        return out
    }

    private fun qualityToInt(q: String): Int {
        return when {
            q.contains("1080") -> Qualities.P1080.value
            q.contains("720") -> Qualities.P720.value
            q.contains("480") -> Qualities.P480.value
            q.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private suspend fun extractMegaPlay(
        anilistId: Int,
        epNum: Int,
        type: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val streamPage =
            "https://megaplay.buzz/stream/ani/" + anilistId + "/" + epNum + "/" + type
        val mpHtml = app.get(
            streamPage,
            headers = mapOf("User-Agent" to ua, "Referer" to mainUrl)
        ).text

        val playerId = Regex("data-id=\"([0-9]+)\"").find(mpHtml)?.groupValues?.getOrNull(1)
            ?: return false

        val sourcesJson = app.get(
            "https://megaplay.buzz/stream/getSources?id=" + playerId,
            headers = mapOf(
                "User-Agent" to ua,
                "Referer" to streamPage,
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "application/json"
            )
        ).text

        val file = Regex("\"file\"\\s*:\\s*\"(https?://[^\"]+)\"")
            .find(sourcesJson)
            ?.groupValues
            ?.getOrNull(1)
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

    private data class EpResponse(
        @JsonProperty("anime") val anime: AnimeInfo? = null,
        @JsonProperty("episode") val episode: EpisodeInfo? = null
    )

    private data class AnimeInfo(
        @JsonProperty("anilistId") val anilistId: Int? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("title") val title: String? = null
    )

    private data class EpisodeInfo(
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("img") val img: String? = null,
        @JsonProperty("servers") val servers: List<ServerInfo>? = null
    )

    private data class ServerInfo(
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
        @JsonProperty("number") val number: Int? = null
    )

    private data class PaheItem(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("quality") val quality: String? = null
    )
}
