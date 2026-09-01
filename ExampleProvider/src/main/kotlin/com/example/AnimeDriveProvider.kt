package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element
import java.util.Base64

class AnimeDriveProvider : MainAPI() {
    override var mainUrl = "https://animedrive.me"
    override var name = "AnimeDrive"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/category/hindi-anime-download/" to "Hindi Anime",
        "$mainUrl/category/action/" to "Action",
        "$mainUrl/category/school/" to "School",
        "$mainUrl/category/romance/" to "Romance",
        "$mainUrl/category/1080p-anime-download/" to "1080p",
        "$mainUrl/category/series/" to "Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val document = app.get(url).document
        val home = document.select("article.ast-article-post, article.post").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("\( mainUrl/?s= \){query.replace(" ", "+")}").document
        return document.select("article.ast-article-post, article.post").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h2.entry-title a, .entry-title a") ?: return null
        val href = titleElement.attr("abs:href").ifBlank { titleElement.attr("href") }
        if (href.isBlank()) return null

        val title = titleElement.text().trim()
            .replace(Regex("(?i)\\s*(Hindi|English|Japanese|Tamil|Telugu|Multi Audio|WEB-DL|Episodes?\\s*Download).*$"), "")
            .trim()
        if (title.isBlank()) return null

        val poster = this.selectFirst("img.wp-post-image, img[src]")?.let {
            it.attr("abs:src").ifBlank { it.attr("src") }.ifBlank { it.attr("data-src") }
        }

        val isMovie = title.contains("Movie", true) || href.contains("movie", true)

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
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?.replace(Regex("(?i)\\s*(Hindi|English|Japanese|Tamil|Telugu|Multi Audio|WEB-DL|Episodes?\\s*Download).*$"), "")
            ?.trim() ?: "Unknown"

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("img.wp-post-image")?.attr("abs:src")

        val plot = document.select(".entry-content p")
            .map { it.text().trim() }
            .firstOrNull { it.length > 80 }

        val year = Regex("""(20\d{2})""").find(
            document.selectFirst(".entry-content")?.text() ?: ""
        )?.groupValues?.getOrNull(1)?.toIntOrNull()

        // link.animedrive.me পেজ খুঁজে বের করো
        val linksPageUrl = document.select("a[href*=link.animedrive.me]")
            .map { it.attr("abs:href").ifBlank { it.attr("href") } }
            .firstOrNull { it.contains("link.animedrive.me") && !it.contains("/dl/") }
            ?: throw ErrorLoadingException("Download links page not found")

        val linksDoc = app.get(linksPageUrl).document
        val episodes = mutableListOf<Episode>()

        // প্রতিটি Episode ব্লক
        linksDoc.select("span.adc-epname").forEachIndexed { index, epNameEl ->
            val epTitle = epNameEl.text().trim()
            val epNum = Regex("""(\d+)""").find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: (index + 1)

            // একই ব্লকের সব /dl/ বাটন
            val container = epNameEl.parents().firstOrNull { p ->
                p.select("a.adc-btn[href*=/dl/], a[href*=/dl/]").isNotEmpty()
            } ?: epNameEl.parent()

            val buttons = container?.select("a.adc-btn[href*=/dl/], a[href*=/dl/]") ?: emptyList()

            val sources = buttons.mapNotNull { btn ->
                val href = btn.attr("abs:href").ifBlank { btn.attr("href") }
                if (href.isBlank() || !href.contains("/dl/")) return@mapNotNull null

                val qualityText = btn.text().trim().ifBlank { "Link" }
                val hoster = when {
                    btn.hasClass("hc") || qualityText.contains("Hub", true) -> "HubCloud"
                    btn.hasClass("fp") || qualityText.contains("GD", true) || qualityText.contains("Flix", true) -> "GDFlix"
                    else -> "Server"
                }

                mapOf(
                    "url" to href,
                    "name" to "$hoster • $qualityText",
                    "quality" to qualityText
                )
            }

            if (sources.isNotEmpty()) {
                episodes.add(
                    newEpisode(sources.toJson()) {
                        this.name = epTitle
                        this.episode = epNum
                        this.season = 1
                    }
                )
            }
        }

        // Fallback – যদি epname না পাওয়া যায়
        if (episodes.isEmpty()) {
            val allDl = linksDoc.select("a[href*=/dl/]")
            allDl.chunked(3).forEachIndexed { idx, group ->
                val sources = group.mapNotNull { a ->
                    val href = a.attr("abs:href").ifBlank { a.attr("href") }
                    if (href.isBlank()) return@mapNotNull null
                    mapOf(
                        "url" to href,
                        "name" to a.text().trim().ifBlank { "Link ${idx + 1}" },
                        "quality" to a.text().trim()
                    )
                }
                if (sources.isNotEmpty()) {
                    episodes.add(
                        newEpisode(sources.toJson()) {
                            this.name = "Episode ${idx + 1}"
                            this.episode = idx + 1
                        }
                    )
                }
            }
        }

        val isMovie = title.contains("Movie", true) || url.contains("movie", true) || episodes.size <= 1

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, episodes.firstOrNull()?.data ?: "") {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                addEpisodes(DubStatus.Dubbed, episodes.sortedBy { it.episode })
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

        val sources = try {
            parseJson<List<Map<String, String>>>(data)
        } catch (e: Exception) {
            listOf(mapOf("url" to data, "name" to "AnimeDrive"))
        }

        var found = false

        for (src in sources) {
            val rawUrl = src["url"] ?: continue
            val displayName = src["name"] ?: "AnimeDrive"

            val realUrl = resolveDlLink(rawUrl) ?: continue

            // Built-in extractors (HubCloud / GDFlix)
            if (loadExtractor(realUrl, mainUrl, subtitleCallback, callback)) {
                found = true
            } else {
                // Fallback direct link
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = displayName,
                        url = realUrl
                    ) {
                        this.referer = mainUrl
                        this.quality = getQualityFromName(src["quality"] ?: displayName)
                    }
                )
                found = true
            }
        }
        return found
    }

    /** /dl/BASE64 → real HubCloud / GDFlix URL */
    private fun resolveDlLink(dlUrl: String): String? {
        return try {
            if (!dlUrl.contains("/dl/")) return dlUrl

            val encoded = dlUrl.substringAfter("/dl/").substringBefore("?").substringBefore("&").trim()
            if (encoded.isBlank()) return null

            val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
            val decoded = String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
            val real = decoded.reversed()

            if (real.startsWith("http")) real else null
        } catch (e: Exception) {
            null
        }
    }
}
