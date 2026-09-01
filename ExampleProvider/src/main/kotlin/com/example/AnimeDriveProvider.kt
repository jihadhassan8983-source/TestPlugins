package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeDriveProvider : MainAPI() {

    override var mainUrl = "https://animedrive.me"
    override var name = "AnimeDrive"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Anime",
        "$mainUrl/category/action/" to "Action",
        "$mainUrl/category/adventure/" to "Adventure",
        "$mainUrl/category/comedy/" to "Comedy",
        "$mainUrl/category/drama/" to "Drama",
        "$mainUrl/category/fantasy/" to "Fantasy",
        "$mainUrl/category/horror/" to "Horror",
        "$mainUrl/category/isekai/" to "Isekai",
        "$mainUrl/category/mystery/" to "Mystery",
        "$mainUrl/category/romance/" to "Romance",
        "$mainUrl/category/on-going/" to "On-Going",
        "$mainUrl/category/hindi-dubbed-anime/" to "Hindi Dubbed"
    )

    private fun Element.toAnimeSearchResponse(): SearchResponse? {

        val href = selectFirst("a[href]")?.attr("href")
            ?: return null

        val title = selectFirst(
            "h1, h2, h3, h4, .entry-title, .post-title"
        )?.text()?.trim()
            ?: selectFirst("a[href]")?.text()?.trim()
            ?: return null

        if (title.isBlank()) return null

        val image = selectFirst("img")?.let {
            it.attr("data-src")
                .ifBlank { it.attr("data-lazy-src") }
                .ifBlank { it.attr("src") }
        }

        return newAnimeSearchResponse(
            title,
            fixUrl(href),
            TvType.Anime
        ) {
            posterUrl = image?.let { fixUrl(it) }
        }
    }

    private fun parseResults(
        document: org.jsoup.nodes.Document
    ): List<SearchResponse> {

        val results = LinkedHashMap<String, SearchResponse>()

        document.select(
            "article, .post, .post-item, .entry, .item"
        ).forEach { element ->

            element.toAnimeSearchResponse()?.let {
                results[it.url] = it
            }
        }

        return results.values.toList()
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = if (page <= 1) {
            request.data
        } else {
            "${request.data.trimEnd('/')}/page/$page/"
        }

        val document = app.get(url).document

        val results = parseResults(document)

        val hasNext = document.selectFirst(
            "a.next, .next a, a[rel=next]"
        ) != null

        return newHomePageResponse(
            request.name,
            results,
            hasNext
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encoded = URLEncoder.encode(
            query.trim(),
            "UTF-8"
        )

        val document = app.get(
            "$mainUrl/?s=$encoded"
        ).document

        return parseResults(document)
    }

    override suspend fun load(
        url: String
    ): LoadResponse {

        val document = app.get(url).document

        val title = document.selectFirst(
            "h1.entry-title, h1.post-title, h1"
        )?.text()?.trim()
            ?: "AnimeDrive"

        val poster = document.selectFirst(
            ".entry-content img, .post-content img, article img"
        )?.let {
            it.attr("data-src")
                .ifBlank { it.attr("data-lazy-src") }
                .ifBlank { it.attr("src") }
        }?.let { fixUrl(it) }

        val content = document.selectFirst(
            ".entry-content, .post-content, article"
        )?.text()?.trim()

        val info = content ?: ""

        val year = Regex(
            """(?:Year|Release Year)\s*:\s*(\d{4})"""
        ).find(info)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        val genres = Regex(
            """Genre\s*:\s*([^|]+)"""
        ).find(info)
            ?.groupValues
            ?.getOrNull(1)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val episodes = ArrayList<Episode>()

        document.select("a[href]").forEach { element ->

            val href = element.attr("href")
            val text = element.text().trim()

            val match = Regex(
                """(?:episode|ep)\s*[-:#]?\s*(\d+)""",
                RegexOption.IGNORE_CASE
            ).find(text) ?: return@forEach

            val episodeNumber =
                match.groupValues
                    .getOrNull(1)
                    ?.toIntOrNull()
                    ?: return@forEach

            if (href.isBlank()) return@forEach

            episodes.add(
                newEpisode(fixUrl(href)) {
                    name = text
                    season = 1
                    episode = episodeNumber
                    posterUrl = poster
                }
            )
        }

        val uniqueEpisodes = episodes
            .distinctBy {
                "${it.season}-${it.episode}-${it.data}"
            }
            .sortedWith(
                compareBy<Episode> { it.season }
                    .thenBy { it.episode }
            )

        return newAnimeLoadResponse(
            title,
            url,
            if (uniqueEpisodes.isEmpty()) {
                TvType.AnimeMovie
            } else {
                TvType.Anime
            }
        ) {
            posterUrl = poster
            backgroundPosterUrl = poster
            plot = content
            this.year = year
            this.tags = genres

            if (uniqueEpisodes.isNotEmpty()) {
                addEpisodes(uniqueEpisodes)
            }
        }
    }

    /*
     * Metadata-only provider.
     *
     * No third-party video-host extraction is performed.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }
}
