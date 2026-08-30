@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Document
import java.net.URLDecoder
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

    override val mainPage = mainPageOf(
        (mainUrl + "/category/ongoing/") to "Ongoing",
        (mainUrl + "/category/hindi-dub/") to "Hindi Dub",
        (mainUrl + "/category/english-dub/") to "English Dub",
        (mainUrl + "/category/anime-movies/") to "Anime Movies"
    )

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return base.trimEnd('/') + "/page/" + page + "/"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(pageUrl(request.data, page), headers = headers).document
        return newHomePageResponse(request.name, parseCards(doc), true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        try {
            val json = app.get(
                mainUrl + "/wp-json/wp/v2/posts?search=" +
                    java.net.URLEncoder.encode(query, "UTF-8") +
                    "&_embed&per_page=20",
                headers = headers
            ).text
            val blocks = Regex("""\{"id":\d+.*?\}(?=,\{"id":|\])""", RegexOption.DOT_MATCHES_ALL)
                .findAll(json)
            // simpler parse
            val titles = Regex(""""title"\s*:\s*\{\s*"rendered"\s*:\s*"([^"]+)"""").findAll(json).map { it.groupValues[1] }.toList()
            val links = Regex(""""link"\s*:\s*"(https://animahd\.com/[^"]+)"""").findAll(json).map {
                it.groupValues[1].replace("\\/", "/")
            }.toList()
            val thumbs = Regex(""""_mal_cached_thumb"\s*:\s*"([^"]*)"""").findAll(json).map {
                it.groupValues[1].replace("\\/", "/")
            }.toList()
            for (i in links.indices) {
                val title = decodeHtml(if (i < titles.size) titles[i] else links[i])
                val poster = if (i < thumbs.size && thumbs[i].startsWith("http")) thumbs[i] else null
                out.add(
                    newAnimeSearchResponse(title, links[i], TvType.Anime) {
                        this.posterUrl = poster
                    }
                )
            }
        } catch (e: Exception) {
            // fall through
        }
        if (out.isNotEmpty()) return out.distinctBy { it.url }

        val doc = app.get(
            mainUrl + "/?s=" + java.net.URLEncoder.encode(query, "UTF-8"),
            headers = headers
        ).document
        return parseCards(doc)
    }

    private fun decodeHtml(s: String): String {
        return s.replace("&#8211;", "-")
            .replace("&#038;", "&")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .replace(Regex("""\\u([0-9a-fA-F]{4})""")) {
                it.groupValues[1].toInt(16).toChar().toString()
            }
    }

    private fun decodeSecRoute(href: String): String? {
        val raw = href.replace("&amp;", "&").replace("&#038;", "&")
        val p = Regex("""[?&]p=([A-Za-z0-9+/=]+)""").find(raw)?.groupValues?.get(1) ?: return null
        return try {
            val pad = when (p.length % 4) {
                2 -> "=="
                3 -> "="
                else -> ""
            }
            String(Base64.getDecoder().decode(p + pad))
        } catch (e: Exception) {
            null
        }
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        doc.select("a.animahd-card, a[href*=sec_route], a[href*=animahd.com]").forEach { a ->
            val href = a.attr("abs:href")
            var url = href
            if (href.contains("sec_route") || href.contains("p=")) {
                url = decodeSecRoute(href) ?: return@forEach
            }
            if (!url.contains("animahd.com")) return@forEach
            if (url.contains("/category/") || url.contains("/player/") || url.contains("/feed")) return@forEach
            if (url == mainUrl || url == mainUrl + "/") return@forEach
            url = url.trimEnd('/') + "/"
            if (!seen.add(url)) return@forEach

            val title = a.selectFirst("img")?.attr("alt")?.ifBlank { null }
                ?: a.text().trim().ifBlank { null }
                ?: url.trimEnd('/').substringAfterLast('/').replace("-", " ")
            if (title.isBlank()) return@forEach

            var poster = a.selectFirst("img")?.attr("data-src")
                ?: a.selectFirst("img")?.attr("src")
            if (poster != null && poster.startsWith("data:")) poster = null
            if (poster != null && poster.contains("sec_route")) {
                poster = decodeSecRoute(poster)
            }
            if (poster != null && !poster.startsWith("http")) {
                poster = mainUrl + "/" + poster.trimStart('/')
            }

            out.add(
                newAnimeSearchResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster
                }
            )
        }
        return out.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = if (url.endsWith("/")) url else url + "/"
        val doc = app.get(pageUrl, headers = headers).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.substringBefore(" Watch")?.trim()
            ?: pageUrl.trimEnd('/').substringAfterLast('/')

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".ff-poster-wrap img")?.attr("src")
            ?: doc.selectFirst("img[src*=uploads]")?.attr("src")

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst(".ff-synopsis")?.text()

        val genres = doc.select(".ff-pill, a[href*=/genre/], a[rel=tag]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.length < 30 }

        val episodes = ArrayList<Episode>()
        val seen = HashSet<String>()

        doc.select("a.app-ep-row-item, a[href*=/player/], a[href*=file_id]").forEach { a ->
            var href = a.attr("abs:href")
            if (href.contains("sec_route") || (href.contains("p=") && !href.contains("file_id"))) {
                href = decodeSecRoute(href) ?: return@forEach
            }
            if (!href.contains("/player/") && !href.contains("file_id")) return@forEach
            if (!seen.add(href)) return@forEach

            val epName = a.selectFirst(".ff-ep-row-title, .app-ep-title")?.text()?.trim()
                ?: a.text().trim().ifBlank { null }
                ?: Regex("""[?&]ep=(\d+)""").find(href)?.let { "Episode " + (it.groupValues[1].toInt() + 1) }
                ?: "Episode ${episodes.size + 1}"

            val epNum = Regex("""[?&]ep=(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()?.let { it + 1 }
                ?: Regex("""(\d+)""").find(epName)?.groupValues?.get(1)?.toIntOrNull()

            episodes.add(
                newEpisode(href) {
                    this.name = epName
                    this.episode = epNum
                }
            )
        }

        if (episodes.isEmpty()) {
            return newMovieLoadResponse(title, pageUrl, TvType.AnimeMovie, pageUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres.ifEmpty { null }
            }
        }

        val sorted = episodes.sortedWith(compareBy(nullsLast()) { it.episode })
        return newAnimeLoadResponse(title, pageUrl, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = plot
            this.tags = genres.ifEmpty { null }
            addEpisodes(DubStatus.Dubbed, sorted)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var playerUrl = data
        if (data.contains("sec_route") || (data.contains("p=") && !data.contains("file_id"))) {
            playerUrl = decodeSecRoute(data) ?: data
        }

        // Resolve file_id from player URL or anime page
        var fileId = Regex("""[?&]file_id=([A-Za-z0-9_-]+)""").find(playerUrl)?.groupValues?.get(1)

        if (fileId == null && !playerUrl.contains("/player/")) {
            // might be anime page — take first episode
            try {
                val doc = app.get(playerUrl, headers = headers).document
                val first = doc.select("a.app-ep-row-item, a[href*=file_id]").firstOrNull()
                var href = first?.attr("abs:href") ?: return false
                if (href.contains("sec_route")) href = decodeSecRoute(href) ?: return false
                fileId = Regex("""[?&]file_id=([A-Za-z0-9_-]+)""").find(href)?.groupValues?.get(1)
                playerUrl = href
            } catch (e: Exception) {
                return false
            }
        }

        if (fileId == null) {
            // open player page and extract redirect target
            try {
                val html = app.get(playerUrl, headers = headers + mapOf("Referer" to mainUrl + "/")).text
                fileId = Regex("""[?&]file_id=([A-Za-z0-9_-]+)""").find(html)?.groupValues?.get(1)
                    ?: Regex("""animahd\.fun/\?id=([A-Za-z0-9_-]+)""").find(html)?.groupValues?.get(1)
                    ?: Regex("""targetStreamUrl\s*=\s*["'][^"']*[?&]id=([A-Za-z0-9_-]+)""").find(html)?.groupValues?.get(1)
            } catch (e: Exception) {
                return false
            }
        }

        if (fileId == null) return false

        val embedUrl = "https://youranimewatchingplace.animahd.fun/?id=" + fileId
        val embedHtml = try {
            app.get(
                embedUrl,
                headers = headers + mapOf("Referer" to (mainUrl + "/"))
            ).text
        } catch (e: Exception) {
            return false
        }

        var found = false
        // <source src="https://....workers.dev/?eid=...&token=...">
        val sources = Regex(
            """https?://[^"'<>\s]*workers\.dev[^"'<>\s]+"""
        ).findAll(embedHtml).map { it.value }.distinct().toList()

        for (src in sources) {
            val url = src.replace("&amp;", "&")
            callback.invoke(
                ExtractorLink(
                    name,
                    "AnimeHD",
                    url,
                    "https://youranimewatchingplace.animahd.fun/",
                    Qualities.Unknown.value,
                    false,
                    mapOf(
                        "User-Agent" to ua,
                        "Referer" to "https://youranimewatchingplace.animahd.fun/"
                    )
                )
            )
            found = true
        }

        // fallback: any mp4/mkv direct
        if (!found) {
            Regex("""https?://[^"'<>\s]+\.(?:mp4|mkv)[^"'<>\s]*""").findAll(embedHtml).forEach {
                callback.invoke(
                    ExtractorLink(
                        name,
                        "AnimeHD",
                        it.value.replace("&amp;", "&"),
                        embedUrl,
                        Qualities.Unknown.value,
                        false
                    )
                )
                found = true
            }
        }

        return found
    }
}
