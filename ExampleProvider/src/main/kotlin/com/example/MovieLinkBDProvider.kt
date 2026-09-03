@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Free MovieLinkBD — multi-domain, no CNC ads.
 * loadLinks always re-scrapes the detail page (more reliable than pre-stored JSON).
 */
class MovieLinkBDProvider : MainAPI() {
    override var mainUrl = "https://movielinkbd.one"
    override var name = "MovieLinkBD"
    override val hasMainPage = true
    override var lang = "bn"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AnimeMovie,
        TvType.AsianDrama
    )

    private val ua =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val domainPool = listOf(
        "https://movielinkbd.one",
        "https://sd2hbb.movielinkbd.li",
        "https://58usfd.movielinkbd.li",
        "https://pyr5us.movielinkbd.li",
        "https://mlink4f3.movielinkbd.li",
        "https://afz5z7.movielinkbd.li",
        "https://movielinkbd.li"
    )

    private var resolvedBase: String? = null

    private fun hdr(ref: String? = null): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,bn;q=0.8",
        "Referer" to (ref ?: "$mainUrl/"),
        "Origin" to (ref ?: mainUrl)
    )

    private suspend fun getBase(): String {
        resolvedBase?.let { return it }
        for (d in domainPool) {
            try {
                val r = app.get(d, headers = hdr(d), timeout = 20)
                val t = r.text
                if (r.code in 200..399 &&
                    !t.contains("Just a moment", true) &&
                    !t.contains("Verify you are human", true) &&
                    t.length > 3000
                ) {
                    resolvedBase = d.trimEnd('/')
                    mainUrl = resolvedBase!!
                    return resolvedBase!!
                }
            } catch (_: Exception) {
            }
        }
        // last resort — phone network may still open default
        resolvedBase = mainUrl.trimEnd('/')
        return resolvedBase!!
    }

    private suspend fun httpGet(url: String, ref: String? = null): String {
        return app.get(url, headers = hdr(ref ?: mainUrl), timeout = 30).text
    }

    private fun absUrl(href: String?, base: String): String? {
        if (href.isNullOrBlank()) return null
        val h = href.trim()
        if (h.startsWith("//")) return "https:$h"
        if (h.startsWith("http")) return h
        if (h.startsWith("/")) return base.trimEnd('/') + h
        return base.trimEnd('/') + "/" + h
    }

    private fun pickImg(el: Element?, base: String): String? {
        if (el == null) return null
        val attrs = listOf(
            "data-src", "data-lazy-src", "data-original", "data-lazy",
            "data-bg", "data-image", "src", "data-srcset", "srcset"
        )
        for (a in attrs) {
            var v = el.attr(a).trim()
            if (v.isBlank()) continue
            // srcset: take largest / first url
            if (a.contains("srcset")) {
                v = v.split(",").map { it.trim().substringBefore(" ") }.lastOrNull { it.isNotBlank() } ?: v
            }
            if (v.startsWith("data:")) continue
            val full = absUrl(v, base) ?: continue
            if (full.contains("placeholder", true) || full.contains("no-image", true)) continue
            return full
        }
        // style background-image:url(...)
        val style = el.attr("style")
        val m = Regex("""url\(['"]?([^'")]+)['"]?\)""").find(style)
        if (m != null) return absUrl(m.groupValues[1], base)
        return null
    }

    override val mainPage = mainPageOf(
        "/type/movies" to "Movies",
        "/type/series" to "Series",
        "/" to "Latest"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = getBase()
        val path = request.data
        val url = when {
            page <= 1 && path == "/" -> "$base/"
            page <= 1 -> "$base$path"
            path == "/" -> "$base/?page=$page"
            else -> "$base\( path \){if (path.contains("?")) "&" else "?"}page=$page"
        }
        return try {
            val list = parseMovieCards(httpGet(url, base), base)
            newHomePageResponse(request.name, list, list.isNotEmpty())
        } catch (_: Exception) {
            newHomePageResponse(request.name, emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val base = getBase()
        val q = URLEncoder.encode(query, "UTF-8")
        return try {
            parseMovieCards(httpGet("$base/search?q=$q", base), base)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseMovieCards(html: String, base: String): List<SearchResponse> {
        val doc = Jsoup.parse(html, base)
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        // Prefer card containers
        val cards = doc.select(
            "div.movie-item, div.item-box, div.film-item, div.post-item, .movie-card, article, .card, .item"
        )

        fun addFrom(scope: Element) {
            val a = scope.selectFirst(
                "a[href*=/movie/], a[href*=/series/], a[href*=/anime/], a[href*=/download18plus/]"
            ) ?: scope.selectFirst("a[href]") ?: return

            var href = absUrl(a.attr("abs:href").ifBlank { a.attr("href") }, base) ?: return
            href = href.substringBefore("#").substringBefore("?").trimEnd('/')
            if (href in seen) return
            if (!href.contains("/movie/") && !href.contains("/series/") &&
                !href.contains("/anime/") && !href.contains("/download18plus/")
            ) return

            var title = a.attr("title").trim()
            if (title.isBlank()) {
                title = scope.selectFirst(".title, .movie-title, h3, h2, [class*=name]")?.text()?.trim().orEmpty()
            }
            if (title.isBlank()) title = a.text().trim()
            if (title.length < 2) return

            var poster = pickImg(
                scope.selectFirst(
                    "img.poster, img[class*=poster], .poster img, .thumb img, " +
                        "img[src*=poster], img[src*=uploads], img[data-src], img"
                ),
                base
            )
            // sometimes poster is on the anchor's child only
            if (poster == null) poster = pickImg(a.selectFirst("img"), base)

            val type = when {
                href.contains("/series/") ||
                    Regex("""S\d+|Season|Ep\s*\d+""", RegexOption.IGNORE_CASE).containsMatchIn(title) ->
                    TvType.TvSeries
                href.contains("/anime/") -> TvType.AnimeMovie
                else -> TvType.Movie
            }

            seen.add(href)
            out.add(
                newMovieSearchResponse(title, href, type) {
                    this.posterUrl = poster
                }
            )
        }

        if (cards.isNotEmpty()) {
            cards.forEach { addFrom(it) }
        } else {
            doc.select(
                "a[href*=/movie/], a[href*=/series/], a[href*=/anime/], a[href*=/download18plus/]"
            ).forEach { a -> addFrom(a) }
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse {
        val base = getBase()
        var pageUrl = url.trim()
        if (pageUrl.startsWith("/")) pageUrl = base + pageUrl
        // normalize to current working base host if possible
        pageUrl = rewriteHost(pageUrl, base)

        val html = httpGet(pageUrl, base)
        val doc = Jsoup.parse(html, base)

        var title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        if (title.isBlank()) {
            title = doc.selectFirst(".movie-title, .title, .entry-title")?.text()?.trim().orEmpty()
        }
        if (title.isBlank()) {
            title = doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore("|")?.substringBefore("-")?.trim().orEmpty()
        }
        if (title.isBlank()) {
            title = doc.selectFirst("title")?.text()
                ?.substringBefore("|")?.substringBefore("-")?.trim().orEmpty()
        }
        if (title.isBlank()) title = "Unknown"

        // Poster — meta first, then page images
        var poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
        if (poster.isNullOrBlank()) {
            poster = pickImg(
                doc.selectFirst(
                    "img.poster, img[class*=poster], .poster img, .thumb img, " +
                        "img[src*=poster], img[src*=uploads], .movie-poster img, img"
                ),
                base
            )
        } else {
            poster = absUrl(poster, base)
        }

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: doc.selectFirst(".plot, .description, .synopsis, .entry-content p, .movie-desc")?.text()?.trim()

        val rating = Regex("""(\d(?:\.\d)?)\s*/?\s*10""")
            .find(doc.text())?.groupValues?.getOrNull(1)?.toDoubleOrNull()

        // Build episodes from episode sections OR single movie
        val episodes = parseEpisodes(doc, pageUrl, base)
        val isSeries = pageUrl.contains("/series/") ||
            episodes.size > 1 ||
            Regex("""S\d+|Season""", RegexOption.IGNORE_CASE).containsMatchIn(title)

        if (isSeries || episodes.isNotEmpty()) {
            val eps = if (episodes.isEmpty()) {
                // one fake episode pointing to same page (loadLinks will re-scrape)
                listOf(
                    newEpisode(pageUrl) {
                        this.name = "Episode 1"
                        this.episode = 1
                    }
                )
            } else episodes

            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, eps) {
                this.posterUrl = poster
                this.plot = plot
                this.rating = rating
            }
        }

        // Movie — data = page URL so loadLinks can re-scrape servers
        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            this.posterUrl = poster
            this.plot = plot
            this.rating = rating
        }
    }

    /** Rewrite URL host to currently working base */
    private fun rewriteHost(url: String, base: String): String {
        return try {
            val u = java.net.URI(url)
            val b = java.net.URI(base)
            if (u.host != null && u.host.contains("movielinkbd")) {
                "\( {b.scheme}:// \){b.host}${u.path.orEmpty()}"
            } else url
        } catch (_: Exception) {
            url
        }
    }

    private fun parseEpisodes(doc: Document, pageUrl: String, base: String): List<Episode> {
        val out = ArrayList<Episode>()
        val sections = doc.select(
            "div.ep-card, [data-ep], div.episode-section, div.season-section, " +
                "div[class*=episode], div[class*=season]"
        )

        // Map: episode number -> keep first section text for naming
        data class EpInfo(val num: Int, val name: String, val links: MutableList<String>)

        val map = LinkedHashMap<Int, EpInfo>()

        fun addLink(ep: Int, name: String, href: String) {
            val info = map.getOrPut(ep) { EpInfo(ep, name, ArrayList()) }
            if (href !in info.links) info.links.add(href)
        }

        if (sections.isNotEmpty()) {
            sections.forEachIndexed { idx, sec ->
                val text = sec.text()
                val m = Regex("""(?:Ep|Episode|E)\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)
                val epNum = m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (idx + 1)
                val links = sec.select(
                    "a[href*=/getLink/], a[href*=/getWatch/], a[href*=/watch/], a[href*=/file/], a.mlbd-live-server-btn"
                )
                if (links.isEmpty()) {
                    // section has no per-ep links — use pageUrl
                    addLink(epNum, "Episode $epNum", pageUrl)
                } else {
                    for (a in links) {
                        val href = absUrl(a.attr("abs:href").ifBlank { a.attr("href") }, base) ?: continue
                        addLink(epNum, "Episode $epNum", href)
                    }
                }
            }
        }

        // Global quality / server buttons on movie pages
        if (map.isEmpty()) {
            val global = doc.select(
                "a[href*=/getLink/], a[href*=/getWatch/], a[href*=/watch/], a[href*=/file/], a.mlbd-live-server-btn"
            )
            if (global.isNotEmpty()) {
                // Single pack — one episode with pageUrl (loadLinks scrapes full page)
                map[1] = EpInfo(1, "Episode 1", mutableListOf(pageUrl))
            }
        }

        for ((_, info) in map.toSortedMap()) {
            // Prefer pageUrl so loadLinks sees ALL servers; extra links as backup in query
            val data = if (info.links.size == 1) info.links.first() else pageUrl
            out.add(
                newEpisode(data) {
                    this.name = info.name
                    this.episode = info.num
                }
            )
        }
        return out
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        val base = getBase()
        var pageUrl = data.trim()
        if (pageUrl.startsWith("/")) pageUrl = base + pageUrl
        pageUrl = rewriteHost(pageUrl, base)

        var found = false
        val added = HashSet<String>()

        // If data is already a direct media URL
        if (pageUrl.contains(".m3u8") || pageUrl.contains(".mp4") || pageUrl.contains(".mkv")) {
            return pushVideo(pageUrl, "Direct", base, callback, added)
        }

        // If data is already getLink/getWatch
        if (pageUrl.contains("/getLink") || pageUrl.contains("/file/")) {
            return resolveGetLink(pageUrl, "DL", base, callback, subtitleCallback, added)
        }
        if (pageUrl.contains("/getWatch") || pageUrl.contains("/watch/")) {
            return resolveGetWatch(pageUrl, "Watch", base, callback, subtitleCallback, added)
        }

        // Normal detail page — collect all servers
        val html = try {
            httpGet(pageUrl, base)
        } catch (_: Exception) {
            return false
        }
        val doc = Jsoup.parse(html, base)

        val serverLinks = LinkedHashSet<String>()
        for (a in doc.select(
            "a[href*=/getLink/], a[href*=/getWatch/], a[href*=/watch/], " +
                "a[href*=/file/], a.mlbd-live-server-btn[href]"
        )) {
            val href = absUrl(a.attr("abs:href").ifBlank { a.attr("href") }, base) ?: continue
            serverLinks.add(href)
        }

        // Also scan raw HTML for paths
        Regex("""/(?:getLink|getWatch|file)/[A-Za-z0-9_\-./]+""").findAll(html).forEach {
            absUrl(it.value, base)?.let { serverLinks.add(it) }
        }

        for (link in serverLinks) {
            try {
                val label = when {
                    link.contains("getWatch") || link.contains("/watch/") -> "Watch Online"
                    link.contains("getLink") || link.contains("/file/") -> "Download"
                    else -> "Server"
                }
                val ok = when {
                    link.contains("getWatch") || link.contains("/watch/") ->
                        resolveGetWatch(link, label, base, callback, subtitleCallback, added)
                    link.contains("getLink") || link.contains("/file/") ->
                        resolveGetLink(link, label, base, callback, subtitleCallback, added)
                    else ->
                        resolveGeneric(link, label, base, callback, subtitleCallback, added)
                }
                if (ok) found = true
            } catch (_: Exception) {
            }
        }

        // Last resort: extract any media from the detail HTML itself
        if (pushMediaFromHtml(html, "Page", base, callback, added)) found = true

        return found
    }

    private suspend fun resolveGetLink(
        url: String,
        label: String,
        base: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
        added: HashSet<String>
    ): Boolean {
        val html = httpGet(url, base)
        var ok = false

        // file: '/apis/redirect/TOKEN'
        Regex("""file\s*:\s*["'](/apis/redirect/[^"']+)""").findAll(html).forEach { m ->
            if (resolveCdnUrl(base + m.groupValues[1], "MLBD CDN • $label", base, callback, added)) {
                ok = true
            }
        }

        // const SRC = "https://..."
        Regex("""const\s+SRC\s*=\s*["'](https?://[^"']+)["']""").findAll(html).forEach { m ->
            if (pushVideo(m.groupValues[1], "SRC • $label", base, callback, added)) ok = true
        }

        if (pushMediaFromHtml(html, "DL • $label", base, callback, added)) ok = true

        try {
            if (loadExtractor(url, base, subtitleCallback, callback)) ok = true
        } catch (_: Exception) {
        }
        return ok
    }

    private suspend fun resolveGetWatch(
        url: String,
        label: String,
        base: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
        added: HashSet<String>
    ): Boolean {
        val html = httpGet(url, base)
        var ok = false

        // nested /watch/ URL
        Regex("""(https?://[^\s'"]+/watch/[^\s'"]*)""").findAll(html).forEach { m ->
            if (resolveXCloud(m.groupValues[1], label, base, callback, added)) ok = true
        }

        if (resolveXCloud(url, label, base, callback, added)) ok = true
        if (pushMediaFromHtml(html, "XCloud • $label", base, callback, added)) ok = true

        try {
            if (loadExtractor(url, base, subtitleCallback, callback)) ok = true
        } catch (_: Exception) {
        }
        return ok
    }

    private suspend fun resolveGeneric(
        url: String,
        label: String,
        base: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
        added: HashSet<String>
    ): Boolean {
        var ok = false
        if (url.contains("/apis/redirect")) {
            if (resolveCdnUrl(url, label, base, callback, added)) ok = true
        }
        if (url.contains(".m3u8") || url.contains(".mp4") || url.contains(".mkv")) {
            if (pushVideo(url, label, base, callback, added)) ok = true
        }
        try {
            val html = httpGet(url, base)
            if (pushMediaFromHtml(html, label, base, callback, added)) ok = true
        } catch (_: Exception) {
        }
        try {
            if (loadExtractor(url, base, subtitleCallback, callback)) ok = true
        } catch (_: Exception) {
        }
        return ok
    }

    private suspend fun resolveCdnUrl(
        redirectUrl: String,
        label: String,
        base: String,
        callback: (ExtractorLink) -> Unit,
        added: HashSet<String>
    ): Boolean {
        return try {
            val resp = app.get(redirectUrl, headers = hdr(base), timeout = 30)
            var ok = false
            val final = resp.url
            if (final.contains(".m3u8") || final.contains(".mp4") || final.contains(".mkv")) {
                if (pushVideo(final, label, base, callback, added)) ok = true
            }
            if (pushMediaFromHtml(resp.text, label, base, callback, added)) ok = true
            ok
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun resolveXCloud(
        pageUrl: String,
        label: String,
        base: String,
        callback: (ExtractorLink) -> Unit,
        added: HashSet<String>
    ): Boolean {
        return try {
            val html = httpGet(pageUrl, base)
            var ok = false

            val inline = Jsoup.parse(html).selectFirst("script#mlbdInlinePlayerData")?.html()
                ?: Jsoup.parse(html).selectFirst("script#mlbdInlinePlayerData")?.data()
            if (!inline.isNullOrBlank()) {
                if (pushMediaFromHtml(inline, "XCloud • $label", base, callback, added)) ok = true
            }

            for (iframe in Jsoup.parse(html, base).select("iframe[src]")) {
                val src = absUrl(iframe.attr("abs:src").ifBlank { iframe.attr("src") }, base) ?: continue
                try {
                    val inner = httpGet(src, pageUrl)
                    if (pushMediaFromHtml(inner, "XCloud • $label", base, callback, added)) ok = true
                } catch (_: Exception) {
                }
            }

            // const SRC =
            Regex("""const\s+SRC\s*=\s*["'](https?://[^"']+)["']""").findAll(html).forEach {
                if (pushVideo(it.groupValues[1], "XCloud • $label", base, callback, added)) ok = true
            }

            if (pushMediaFromHtml(html, "XCloud • $label", base, callback, added)) ok = true
            ok
        } catch (_: Exception) {
            false
        }
    }

    private fun pushMediaFromHtml(
        html: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        added: HashSet<String>
    ): Boolean {
        var ok = false
        Regex("""(?:file|src)\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4|mkv)[^"']*)""")
            .findAll(html).forEach {
                if (pushVideo(it.groupValues[1], label, referer, callback, added)) ok = true
            }
        Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").findAll(html).forEach {
            if (pushVideo(it.value, label, referer, callback, added)) ok = true
        }
        Regex("""https?://[^\s"'<>\\]+\.(?:mp4|mkv)[^\s"'<>\\]*""").findAll(html).forEach {
            if (pushVideo(it.value, label, referer, callback, added)) ok = true
        }
        try {
            for (v in Jsoup.parse(html).select("video source[src], video[src], source[src]")) {
                val s = v.attr("src")
                if (s.startsWith("http") && pushVideo(s, label, referer, callback, added)) ok = true
            }
        } catch (_: Exception) {
        }
        return ok
    }

    private fun pushVideo(
        url: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        added: HashSet<String>
    ): Boolean {
        val u = url.trim()
            .trimEnd('"', '\'', '\\', ')', ']', '>')
            .replace("\\u0026", "&")
            .replace("\\/", "/")
        if (!u.startsWith("http")) return false
        if (u.contains("youtube", true) || u.contains("favicon", true)) return false
        if (!added.add(u)) return false

        val q = when {
            label.contains("1080", true) || u.contains("1080") -> Qualities.P1080.value
            label.contains("720", true) || u.contains("720") -> Qualities.P720.value
            label.contains("480", true) || u.contains("480") -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }

        callback.invoke(
            ExtractorLink(
                name,
                label,
                u,
                referer,
                q,
                u.contains(".m3u8")
            )
        )
        return true
    }
}
