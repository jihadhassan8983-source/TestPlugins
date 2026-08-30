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
                    "&per_page=20",
                headers = headers
            ).text

            // split roughly by post objects
            val chunks = json.split("""{"id":""")
            for (chunk in chunks) {
                val link = Regex(""""link"\s*:\s*"(https://animahd\.com/[^"]+)"""")
                    .find(chunk)?.groupValues?.get(1)?.replace("\\/", "/") ?: continue
                if (link.contains("/category/")) continue
                val titleRaw = Regex(""""rendered"\s*:\s*"([^"]+)"""")
                    .find(chunk)?.groupValues?.get(1) ?: continue
                val title = decodeHtml(titleRaw)
                val poster = Regex(""""_mal_cached_thumb"\s*:\s*"(https://[^"]+)"""")
                    .find(chunk)?.groupValues?.get(1)?.replace("\\/", "/")
                out.add(
                    newAnimeSearchResponse(title, link, TvType.Anime) {
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
            .replace("&quot;", "\"")
            .replace("\\u0026", "&")
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

    private fun cleanPoster(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var u = url.trim().trim('\'', '"')
        if (u.startsWith("//")) u = "https:$u"
        if (u.contains("sec_route") || u.contains("p=")) {
            u = decodeSecRoute(u) ?: return null
        }
        if (!u.startsWith("http")) return null
        return u
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        doc.select("a.animahd-card").forEach { a ->
            var href = a.attr("abs:href")
            if (href.contains("sec_route") || href.contains("p=")) {
                href = decodeSecRoute(href) ?: return@forEach
            }
            if (!href.contains("animahd.com")) return@forEach
            if (href.contains("/category/") || href.contains("/player/")) return@forEach
            href = href.trimEnd('/') + "/"
            if (!seen.add(href)) return@forEach

            val title = a.selectFirst(".animahd-card-title, .animahd-title")?.text()?.trim()
                ?: a.selectFirst("img")?.attr("alt")?.ifBlank { null }
                ?: href.trimEnd('/').substringAfterLast('/').replace("-", " ")

            // posters are CSS background-image on .animahd-poster
            var poster: String? = null
            val style = a.selectFirst(".animahd-poster")?.attr("style") ?: ""
            val bg = Regex("""url\(['"]?([^'")\s]+)['"]?\)""").find(style)?.groupValues?.get(1)
            poster = cleanPoster(bg)
            if (poster == null) {
                poster = cleanPoster(
                    a.selectFirst("img")?.attr("data-src")
                        ?: a.selectFirst("img")?.attr("src")
                )
            }

            out.add(
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                }
            )
        }

        // fallback: any sec_route link
        if (out.isEmpty()) {
            doc.select("a[href*=sec_route], a[href*=animahd.com]").forEach { a ->
                var href = a.attr("abs:href")
                if (href.contains("sec_route") || href.contains("p=")) {
                    href = decodeSecRoute(href) ?: return@forEach
                }
                if (!href.contains("animahd.com")) return@forEach
                if (href.contains("/category/") || href.contains("/player/") || href.contains("/feed")) return@forEach
                if (href == mainUrl || href == mainUrl + "/") return@forEach
                href = href.trimEnd('/') + "/"
                if (!seen.add(href)) return@forEach
                val title = a.text().trim().ifBlank {
                    href.trimEnd('/').substringAfterLast('/').replace("-", " ")
                }
                out.add(newAnimeSearchResponse(title, href, TvType.Anime))
            }
        }

        return out.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = if (url.endsWith("/")) url else url + "/"
        val doc = app.get(pageUrl, headers = headers).document

        val title = doc.selectFirst("h1.ff-title, h1")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.substringBefore(" Watch")?.trim()
            ?: pageUrl.trimEnd('/').substringAfterLast('/')

        var poster = cleanPoster(doc.selectFirst(".ff-poster-wrap img")?.attr("src"))
        if (poster == null) {
            val style = doc.selectFirst(".ff-hero-bg, .ff-poster-wrap")?.attr("style") ?: ""
            poster = cleanPoster(
                Regex("""url\(['"]?([^'")\s]+)['"]?\)""").find(style)?.groupValues?.get(1)
            )
        }
        if (poster == null) {
            poster = cleanPoster(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        }

        val plot = doc.selectFirst(".ff-synopsis")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val genres = doc.select(".ff-pill")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.length < 40 }

        val episodes = ArrayList<Episode>()
        val seen = HashSet<String>()

        doc.select("a.app-ep-row-item, a[href*=file_id], a[href*=/player/]").forEach { a ->
            var href = a.attr("abs:href").replace("&#038;", "&").replace("&amp;", "&")
            if (href.contains("sec_route") || (href.contains("p=") && !href.contains("file_id"))) {
                href = decodeSecRoute(href) ?: return@forEach
            }
            if (!href.contains("file_id") && !href.contains("/player/")) return@forEach
            if (!seen.add(href)) return@forEach

            val epIdx = Regex("""[?&]ep=(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
            val epNum = epIdx?.plus(1)
            val epName = a.selectFirst(".ff-ep-row-title, .app-ep-title, .ff-ep-row-sub")?.text()?.trim()
                ?: a.ownText().trim().ifBlank { null }
                ?: (if (epNum != null) "Episode $epNum" else "Episode ${episodes.size + 1}")

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
        var fileId: String? = Regex("""[?&]file_id=([A-Za-z0-9_-]+)""")
            .find(data)?.groupValues?.get(1)

        // decode sec_route if needed
        if (fileId == null && (data.contains("sec_route") || data.contains("p="))) {
            val decoded = decodeSecRoute(data)
            if (decoded != null) {
                fileId = Regex("""[?&]file_id=([A-Za-z0-9_-]+)""")
                    .find(decoded)?.groupValues?.get(1)
            }
        }

        // anime page → first episode file_id
        if (fileId == null && !data.contains("/player/")) {
            try {
                val doc = app.get(
                    if (data.endsWith("/")) data else "$data/",
                    headers = headers
                ).document
                for (a in doc.select("a.app-ep-row-item, a[href*=file_id]")) {
                    var href = a.attr("abs:href").replace("&#038;", "&").replace("&amp;", "&")
                    if (href.contains("sec_route")) href = decodeSecRoute(href) ?: continue
                    fileId = Regex("""[?&]file_id=([A-Za-z0-9_-]+)""")
                        .find(href)?.groupValues?.get(1)
                    if (fileId != null) break
                }
            } catch (e: Exception) {
                // ignore
            }
        }

        // player page → JS redirect target
        if (fileId == null) {
            try {
                val html = app.get(
                    data,
                    headers = headers + mapOf("Referer" to mainUrl + "/")
                ).text
                fileId = Regex("""[?&]file_id=([A-Za-z0-9_-]+)""")
                    .find(html)?.groupValues?.get(1)
                    ?: Regex("""animahd\.fun/\?id=([A-Za-z0-9_-]+)""")
                        .find(html)?.groupValues?.get(1)
                    ?: Regex("""targetStreamUrl\s*=\s*["'][^"']*[?&]id=([A-Za-z0-9_-]+)""")
                        .find(html)?.groupValues?.get(1)
            } catch (e: Exception) {
                return false
            }
        }

        if (fileId.isNullOrBlank()) return false

        val embedUrl = "https://youranimewatchingplace.animahd.fun/?id=" + fileId
        val embedHtml = try {
            app.get(
                embedUrl,
                headers = headers + mapOf(
                    "Referer" to (mainUrl + "/"),
                    "Origin" to mainUrl
                )
            ).text
        } catch (e: Exception) {
            return false
        }

        var found = false
        val streamHeaders = mapOf(
            "User-Agent" to ua,
            "Accept" to "*/*",
            "Referer" to "https://youranimewatchingplace.animahd.fun/",
            "Origin" to "https://youranimewatchingplace.animahd.fun"
        )

        // primary: <source src="https://....workers.dev/...">
        val sources = LinkedHashSet<String>()
        Regex("""<source[^>]+src=["']([^"']+)["']""").findAll(embedHtml).forEach {
            sources.add(it.groupValues[1].replace("&amp;", "&"))
        }
        Regex("""https?://[^"'<>\s]*workers\.dev[^"'<>\s]+""").findAll(embedHtml).forEach {
            sources.add(it.value.replace("&amp;", "&"))
        }

        for (src in sources) {
            if (!src.contains("workers.dev") && !src.contains(".mp4") && !src.contains("token=")) continue
            callback.invoke(
                ExtractorLink(
                    name,
                    "AnimeHD",
                    src,
                    "https://youranimewatchingplace.animahd.fun/",
                    Qualities.Unknown.value,
                    false,
                    streamHeaders
                )
            )
            found = true
        }

        return found
    }
}
