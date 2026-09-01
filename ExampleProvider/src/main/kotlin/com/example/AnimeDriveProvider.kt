@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Document
import java.net.URLEncoder

class AnimeDriveProvider : MainAPI() {

    override var mainUrl = "https://animedrive.me"
    override var name = "AnimeDrive"
    override var lang = "hi"

    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/category/action/" to "Action",
        "$mainUrl/category/adventure/" to "Adventure",
        "$mainUrl/category/comedy/" to "Comedy",
        "$mainUrl/category/drama/" to "Drama",
        "$mainUrl/category/fantasy/" to "Fantasy",
        "$mainUrl/category/horror/" to "Horror",
        "$mainUrl/category/isekai/" to "Isekai",
        "$mainUrl/category/magic/" to "Magic",
        "$mainUrl/category/mystery/" to "Mystery",
        "$mainUrl/category/romance/" to "Romance",
        "$mainUrl/category/sci-fi/" to "Sci-Fi",
        "$mainUrl/category/sports/" to "Sports",
        "$mainUrl/category/on-going/" to "On-Going"
    )

    private val headers = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/"
    )

    private fun cleanUrl(url: String): String {
        return url
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .trim()
    }

    private fun getPoster(element: org.jsoup.nodes.Element): String? {
        val image = element.selectFirst("img") ?: return null

        val url = image.attr("data-src")
            .ifBlank { image.attr("data-lazy-src") }
            .ifBlank { image.attr("src") }

        return url
            .takeIf { it.startsWith("http") }
            ?.let(::cleanUrl)
    }

    private fun parsePosts(document: Document): List<SearchResponse> {

        val result = LinkedHashMap<String, SearchResponse>()

        /*
         * Current site exposes posts as links/headings.
         * We intentionally avoid inventing a single fragile CSS class.
         */
        document.select("article").forEach { article ->

            val link = article.selectFirst("a[href]")
                ?: return@forEach

            val url = cleanUrl(
                link.attr("abs:href")
            )

            if (!url.startsWith(mainUrl)) return@forEach
            if (url == "$mainUrl/") return@forEach
            if (url.contains("/category/")) return@forEach

            val title = article.selectFirst(
                "h1, h2, h3, h4, .entry-title"
            )?.text()?.trim()
                ?: link.text().trim()

            if (title.isBlank()) return@forEach

            result[url] = newAnimeSearchResponse(
                title,
                url,
                TvType.Anime
            ) {
                posterUrl = getPoster(article)
            }
        }

        /*
         * Fallback for the current homepage/theme.
         */
        if (result.isEmpty()) {
            document.select("a[href]").forEach { link ->

                val url = cleanUrl(
                    link.attr("abs:href")
                )

                val title = link.text().trim()

                if (
                    !url.startsWith(mainUrl) ||
                    url == "$mainUrl/" ||
                    url.contains("/category/") ||
                    title.length < 4
                ) {
                    return@forEach
                }

                /*
                 * Ignore navigation/footer links.
                 */
                if (
                    title.equals("Home", true) ||
                    title.equals("Genres", true) ||
                    title.equals("Search", true) ||
                    title.equals("Contact Us", true) ||
                    title.equals("About Us", true)
                ) {
                    return@forEach
                }

                result[url] = newAnimeSearchResponse(
                    title,
                    url,
                    TvType.Anime
                )
            }
        }

        return result.values.toList()
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val baseUrl = request.data.trimEnd('/')

        val url = if (page <= 1) {
            baseUrl
        } else {
            "$baseUrl/page/$page/"
        }

        val document = try {
            app.get(
                url,
                headers = headers
            ).document
        } catch (e: Exception) {
            return newHomePageResponse(
                request.name,
                emptyList(),
                false
            )
        }

        val results = parsePosts(document)

        val nextPage = document.selectFirst(
            "a[rel=next], .next a, a.next"
        ) != null

        return newHomePageResponse(
            request.name,
            results,
            nextPage
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        if (query.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(
            query.trim(),
            "UTF-8"
        )

        val url = "$mainUrl/?s=$encoded"

        return try {
            val document = app.get(
                url,
                headers = headers
            ).document

            parsePosts(document)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(
        url: String
    ): LoadResponse {

        val pageUrl = cleanUrl(url)

        val document = try {
            app.get(
                pageUrl,
                headers = headers
            ).document
        } catch (e: Exception) {

            return newMovieLoadResponse(
                "AnimeDrive",
                pageUrl,
                TvType.AnimeMovie,
                pageUrl
            )
        }

        val title = document.selectFirst(
            "h1.entry-title, h1.post-title, h1"
        )?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")
                ?.attr("content")
                ?.trim()
            ?: document.title().substringBefore("|").trim()

        val poster = document.selectFirst(
            "meta[property=og:image]"
        )?.attr("content")
            ?.takeIf { it.startsWith("http") }

            ?: document.selectFirst(
                "article img, .entry-content img"
            )?.let {
                it.attr("data-src")
                    .ifBlank { it.attr("data-lazy-src") }
                    .ifBlank { it.attr("src") }
            }?.takeIf { it.startsWith("http") }

        val description =
            document.selectFirst(
                "meta[property=og:description]"
            )?.attr("content")?.trim()

                ?: document.selectFirst(
                    ".entry-content, .post-content, article"
                )?.text()?.trim()

        val contentText =
            document.selectFirst(
                ".entry-content, .post-content, article"
            )?.text()
                ?: ""

        val year = Regex(
            """(?:Year|Release Year)\s*[:\-]\s*(\d{4})"""
        )
            .find(contentText)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        val genres = document.select(
            "a[href*='/category/'], a[rel='tag']"
        )
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(20)

        /*
         * Metadata-only episode detection.
         *
         * We only create an Episode when the public page
         * actually exposes an episode-like link.
         */
        val episodes = ArrayList<Episode>()

        val seen = HashSet<String>()

        document.select("a[href]").forEach { link ->

            val href = cleanUrl(
                link.attr("abs:href")
            )

            val text = link.text().trim()

            if (
                href.isBlank() ||
                text.isBlank() ||
                !href.startsWith(mainUrl)
            ) {
                return@forEach
            }

            val match = Regex(
                """(?:episode|ep)\s*[-.#:]?\s*(\d+)""",
                RegexOption.IGNORE_CASE
            ).find(text)

            val episodeNumber =
                match?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: return@forEach

            if (!seen.add(href)) return@forEach

            episodes.add(
                newEpisode(href) {
                    name = text
                    season = 1
                    episode = episodeNumber
                    posterUrl = poster
                }
            )
        }

        val sortedEpisodes = episodes
            .distinctBy { it.data }
            .sortedBy { it.episode }

        if (sortedEpisodes.isEmpty()) {

            return newMovieLoadResponse(
                title,
                pageUrl,
                TvType.AnimeMovie,
                pageUrl
            ) {
                posterUrl = poster
                backgroundPosterUrl = poster
                plot = description
                this.year = year
                this.tags = genres
            }
        }

        return newAnimeLoadResponse(
            title,
            pageUrl,
            TvType.Anime
        ) {
            posterUrl = poster
            backgroundPosterUrl = poster
            plot = description
            this.year = year
            this.tags = genres

            addEpisodes(
                DubStatus.NotFound,
                sortedEpisodes
            )
        }
    }

    /*
     * Metadata-only provider.
     *
     * AnimeDrive states that files are hosted by
     * third-party websites. No third-party media
     * extraction is performed here.
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
