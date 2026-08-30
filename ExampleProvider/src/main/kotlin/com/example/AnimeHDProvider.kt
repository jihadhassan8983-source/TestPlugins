@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Document
import java.util.Base64

class AnimeHDProvider : MainAPI() {
    override var mainUrl = "https://animahd.com"
    override var name = "AnimeHD"
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

    // Full category URLs (Cloudstream passes this as request.data)
    override val mainPage = mainPageOf(
        (mainUrl + "/category/ongoing/") to "Ongoing",
        (mainUrl + "/category/hindi-dub/") to "Hindi Dub",
        (mainUrl + "/category/english-dub/") to "English Dub",
        (mainUrl + "/category/anime-movies/") to "Anime Movies"
    )

    private fun categoryId(url: String): String? {
        return when {
            url.contains("ongoing") -> "8"
            url.contains("hindi-dub") -> "7"
            url.contains("english-dub") -> "9"
            url.contains("anime-movies") -> "11"
            else -> null
        }
    }

    private fun decodeHtml(s: String): String {
        return s.replace("&#8211;", "-")
            .replace("&#8217;", "'")
            .replace("&#038;", "&")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
    }

    private fun decodeSecRoute(href: String): String? {
        val raw = href.replace("&amp;", "&").replace("&#038;", "&")
        val p = Regex("""[?&]p=([A-Za-z0-9+/=]+)""").find(raw)?.groupValues?.get(1) ?: return null
        return try {
            val pad = "=".repeat((4 - p.length % 4) % 4)
            String(Base64.getDecoder().decode(p + pad))
        } catch (e: Exception) {
            null
        }
    }

    private fun parseWpPosts(json: String): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        if (!json.trimStart().startsWith("[")) return out
        val chunks = json.split("""{"id":""")
        for (chunk in chunks) {
            val link = Regex(""""link"\s*:\s*"(https://animahd\.com/[^"]+)"""")
                .find(chunk)?.groupValues?.get(1)?.replace("\\/", "/") ?: continue
            if (link.contains("/category/") || link.contains("/filter")) continue
            val titleRaw = Regex(""""rendered"\s*:\s*"([^"]+)"""")
                .find(chunk)?.groupValues?.get(1) ?: continue
            val title = decodeHtml(titleRaw)
            if (title.equals("filter", true) || title.isBlank()) continue
            val poster = Regex(""""_mal_cached_thumb"\s*:\s*"(https://[^"]+)"""")
                .find(chunk)?.groupValues?.get(1)?.replace("\\/", "/")
            out.add(
                newAnimeSearchResponse(title, link, TvType.Anime) {
                    this.posterUrl = poster
                }
            )
        }
        return out.distinctBy { it.url }
    }

    private fun parseCardsHtml(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        doc.select("a.animahd-card").forEach { a ->
            var href = a.attr("abs:href").replace("&#038;", "&").replace("&amp;", "&")
            if (href.contains("sec_route") || href.contains("p=")) {
                href = decodeSecRoute(href) ?: return@forEach
            }
            if (!href.contains("animahd.com")) return@forEach
            if (href.contains("/category/") || href.contains("/filter") || href.contains("/player/")) return@forEach
            href = href.trimEnd('/') + "/"
            if (!seen.add(href)) return@forEach

            val title = a.selectFirst(".animahd-card-title")?.text()?.trim()
                ?: href.trimEnd('/').substringAfterLast('/').replace("-", " ")
            if (title.equals("filter", true) || title.isBlank()) return@forEach

            var poster: String? = null
            val style = a.selectFirst(".animahd-poster")?.attr("style") ?: ""
            val bg = Regex("""url\(['"]?([^'")\s]+)['"]?\)""").find(style)?.groupValues?.get(1)
            if (bg != null && bg.startsWith("http")) poster = bg.trim('\'', '"')

            out.add(
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                }
            )
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val catId = categoryId(request.data)
        var list = emptyList<SearchResponse>()

        // 1) WP API (good posters + pagination)
        if (catId != null) {
            try {
                val apiUrl = mainUrl + "/wp-json/wp/v2/posts?categories=" + catId +
                    "&per_page=20&page=" + page
                val json = app.get(apiUrl, headers = headers).text
                list = parseWpPosts(json)
            } catch (_: Exception) {
            }
        }

        // 2) HTML fallback (page 1 only — page 2+ is 404 on site)
        if (list.isEmpty() && page <= 1) {
            try {
                val doc = app.get(request.data, headers = headers).document
                list = parseCardsHtml(doc)
            } catch (_: Exception) {
            }
        }

        val hasNext = list.size >= 20
        return newHomePageResponse(request.name, list, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        try {
            val json = app.get(
                mainUrl + "/wp-json/wp/v2/posts?search=" + q + "&per_page=20",
                headers = headers
            ).text
            val list = parseWpPosts(json)
            if (list.isNotEmpty()) return list
        } catch (_: Exception) {
        }
        return try {
            parseCardsHtml(app.get(mainUrl + "/?s=" + q, headers = headers).document)
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = if (url.endsWith("/")) url else url + "/"
        val doc = app.get(pageUrl, headers = headers).document

        val title = doc.selectFirst("h1.ff-title, h1")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.substringBefore(" Watch")?.trim()
            ?: pageUrl.trimEnd('/').substringAfterLast('/')

        var poster: String? = null
        val posterImg = doc.selectFirst(".ff-poster-wrap img")?.attr("src")
        if (posterImg != null && posterImg.startsWith("http")) poster = posterImg
        if (poster == null) {
            val style = doc.selectFirst(".ff-hero-bg")?.attr("style") ?: ""
            val bg = Regex("""url\(['"]?([^'")\s]+)['"]?\)""").find(style)?.groupValues?.get(1)
            if (bg != null && bg.startsWith("http")) poster = bg.trim('\'', '"')
        }

        val plot = doc.selectFirst(".ff-synopsis")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

        val genres = doc.select(".ff-pill")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.length < 40 }

        data class EpRow(val href: String, val season: Int, val epIndex: Int, val name: String)
        val rows = ArrayList<EpRow>()
        val seen = HashSet<String>()

        doc.select("a.app-ep-row-item, a[href*=file_id]").forEach { a ->
            var href = a.attr("abs:href").replace("&#038;", "&").replace("&amp;", "&")
            if (href.contains("sec_route") || (href.contains("p=") && !href.contains("file_id"))) {
                href = decodeSecRoute(href) ?: return@forEach
            }
            if (!href.contains("file_id")) return@forEach
            if (!seen.add(href)) return@forEach

            val seasonName = a.attr("data-season").ifBlank { "Season 1" }
            val seasonNum = Regex("""(\d+)""").find(seasonName)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val epIndex = Regex("""[?&]ep=(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull() ?: rows.size
            val name = a.selectFirst(".ff-ep-row-title, .app-ep-title")?.text()?.trim() ?: "Episode"
            rows.add(EpRow(href, seasonNum, epIndex, name))
        }

        if (rows.isEmpty()) {
            return newMovieLoadResponse(title, pageUrl, TvType.AnimeMovie, pageUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres.ifEmpty { null }
            }
        }

        val episodes = ArrayList<Episode>()
        for ((seasonNum, list) in rows.groupBy { it.season }.toSortedMap()) {
            list.sortedBy { it.epIndex }.forEachIndexed { idx, row ->
                episodes.add(
                    newEpisode(row.href) {
                        this.name = if (row.name.isNotBlank() && row.name != "Episode") row.name
                        else "Episode ${idx + 1}"
                        this.season = seasonNum
                        this.episode = idx + 1
                    }
                )
            }
        }

        return newAnimeLoadResponse(title, pageUrl, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = plot
            this.tags = genres.ifEmpty { null }
            addEpisodes(DubStatus.Dubbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var fileId = extractFileId(data)
        if (fileId == null && (data.contains("sec_route") || data.contains("p="))) {
            fileId = extractFileId(decodeSecRoute(data) ?: "")
        }
        if (fileId == null && !data.contains("file_id") && !data.contains("/player/")) {
            try {
                val doc = app.get(if (data.endsWith("/")) data else "$data/", headers = headers).document
                for (a in doc.select("a.app-ep-row-item, a[href*=file_id]")) {
                    var href = a.attr("abs:href").replace("&#038;", "&").replace("&amp;", "&")
                    if (href.contains("sec_route")) href = decodeSecRoute(href) ?: continue
                    fileId = extractFileId(href)
                    if (fileId != null) break
                }
            } catch (_: Exception) {
            }
        }
        if (fileId == null) {
            try {
                val html = app.get(data, headers = headers + mapOf("Referer" to mainUrl + "/")).text
                fileId = extractFileId(html)
                    ?: Regex("""animahd\.fun/\?id=([A-Za-z0-9_-]+)""").find(html)?.groupValues?.get(1)
            } catch (_: Exception) {
            }
        }
        if (fileId.isNullOrBlank()) return false

        val embedUrl = "https://youranimewatchingplace.animahd.fun/?id=" + fileId
        val embedHtml = try {
            app.get(
                embedUrl,
                headers = headers + mapOf("Referer" to (mainUrl + "/"), "Origin" to mainUrl)
            ).text
        } catch (_: Exception) {
            return false
        }

        var found = false
        val streamHeaders = mapOf(
            "User-Agent" to ua,
            "Accept" to "*/*",
            "Referer" to "https://youranimewatchingplace.animahd.fun/",
            "Origin" to "https://youranimewatchingplace.animahd.fun"
        )
        val sources = LinkedHashSet<String>()
        Regex("""<source[^>]+src=["']([^"']+)["']""").findAll(embedHtml).forEach {
            sources.add(it.groupValues[1].replace("&amp;", "&"))
        }
        Regex("""https?://[^"'<>\s]*workers\.dev[^"'<>\s]+""").findAll(embedHtml).forEach {
            sources.add(it.value.replace("&amp;", "&"))
        }
        for (src in sources) {
            if (!src.contains("workers.dev") && !src.contains("token=")) continue
            callback.invoke(
                ExtractorLink(
                    name, "AnimeHD", src,
                    "https://youranimewatchingplace.animahd.fun/",
                    Qualities.Unknown.value, false, streamHeaders
                )
            )
            found = true
        }
        return found
    }

    private fun extractFileId(text: String): String? {
        return Regex("""[?&]file_id=([A-Za-z0-9_-]+)""").find(text)?.groupValues?.get(1)
    }
}
