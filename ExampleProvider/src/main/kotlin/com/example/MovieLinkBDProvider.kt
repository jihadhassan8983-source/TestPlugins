@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

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

    data class Srv(
        @JsonProperty("u") val u: String,
        @JsonProperty("l") val l: String = "Server"
    )

    private fun hdr(ref: String? = null): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
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
        resolvedBase = mainUrl.trimEnd('/')
        return resolvedBase!!
    }

    private suspend fun httpGet(url: String, ref: String? = null): String {
        return app.get(url, headers = hdr(ref ?: mainUrl), timeout = 35).text
    }

    private fun absUrl(href: String?, base: String): String? {
        if (href.isNullOrBlank()) return null
        val h = href.trim().trim('"', '\'')
        if (h.startsWith("//")) return "https:$h"
        if (h.startsWith("http")) return h
        if (h.startsWith("/")) return base.trimEnd('/') + h
        if (h.startsWith("javascript", true) || h.startsWith("#")) return null
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
            if (a.contains("srcset")) {
                v = v.split(",").map { it.trim().substringBefore(" ") }
                    .lastOrNull { it.isNotBlank() } ?: v
            }
            if (v.startsWith("data:")) continue
            val full = absUrl(v, base) ?: continue
            if (full.contains("placeholder", true) || full.contains("no-image", true)) continue
            return full
        }
        val style = el.attr("style")
        val m = Regex("""url\(['"]?([^'")]+)['"]?\)""").find(style)
        if (m != null) return absUrl(m.groupValues[1], base)
        return null
    }

    /** Is this a playable/download server link? */
    private fun isServerHref(href: String, text: String = ""): Boolean {
        val h = href.lowercase()
        val t = text.lowercase()
        if (h.contains("facebook") || h.contains("telegram") || h.contains("whatsapp")) return false
        if (h.contains("twitter") || h.contains("instagram") || h.contains("youtube.com")) return false
        if (h.contains("/type/") || h.contains("/search") || h.contains("/language/")) return false
        if (h.contains("/getlink") || h.contains("/getwatch") || h.contains("/file/") || h.contains("/watch/")) return true
        if (h.contains("/apis/redirect")) return true
        if (h.contains(".m3u8") || h.contains(".mp4") || h.contains(".mkv")) return true
        // known hosters
        val hosts = listOf(
            "gdflix", "hubcloud", "hubdrive", "drive.google", "mediafire",
            "pixeldrain", "gofile", "streamtape", "dood", "filepress",
            "mixdrop", "mega.nz", "zippyshare", "1fichier", "krakenfiles",
            "fastclick", "racaty", "upstream", "streamsb", "voe.sx"
        )
        if (hosts.any { h.contains(it) }) return true
        // quality button text
        if (Regex("""\b(480p|720p|1080p|2160p|4k|hevc|x265|x264)\b""").containsMatchIn(t)) {
            if (h.startsWith("http") && !h.contains("movielinkbd") || h.contains("/get") || h.contains("/file")) {
                return true
            }
            // quality text on same-site link often is getLink
            if (h.contains("movielinkbd") && (h.contains("/get") || h.contains("/file") || h.contains("/watch"))) return true
        }
        if (t.contains("watch online") || t.contains("download") || t.contains("server")) {
            if (h.contains("/get") || h.contains("/file") || h.contains("/watch")) return true
        }
        return false
    }

    /** Collect every possible server URL from a detail document */
    private fun collectServers(doc: Document, html: String, base: String): List<Srv> {
        val out = LinkedHashMap<String, String>() // url -> label

        fun add(url: String?, label: String) {
            val u = url?.trim() ?: return
            if (!u.startsWith("http")) return
            if (u in out) return
            out[u] = label.ifBlank { "Server" }
        }

        // 1) Anchors
        for (a in doc.select("a[href]")) {
            val href = absUrl(a.attr("abs:href").ifBlank { a.attr("href") }, base) ?: continue
            val text = a.text().trim().ifBlank {
                a.attr("title").ifBlank { a.attr("aria-label") }
            }
            if (isServerHref(href, text)) {
                add(href, text.ifBlank { "Server" }.take(60))
            }
        }

        // 2) data-* attributes
        for (el in doc.select("[data-href], [data-url], [data-link], [data-src], [data-file]")) {
            for (attr in listOf("data-href", "data-url", "data-link", "data-src", "data-file")) {
                val href = absUrl(el.attr(attr), base) ?: continue
                val text = el.text().trim()
                if (isServerHref(href, text) || href.contains("/get") || href.contains("/file")) {
                    add(href, text.ifBlank { "Server" }.take(60))
                }
            }
        }

        // 3) onclick location
        for (el in doc.select("[onclick]")) {
            val oc = el.attr("onclick")
            val m = Regex("""['"](https?://[^'"]+)['"]""").find(oc)
                ?: Regex("""['"](/[^'"]+)['"]""").find(oc)
            if (m != null) {
                val href = absUrl(m.groupValues[1], base)
                if (href != null && isServerHref(href, el.text())) {
                    add(href, el.text().trim().ifBlank { "Server" }.take(60))
                }
            }
        }

        // 4) Raw path scan
        Regex("""(?:https?:)?//[^\s"'<>]+/(?:getLink|getWatch|file|watch)/[A-Za-z0-9_\-./%]+""")
            .findAll(html).forEach { add(it.value.let { v -> if (v.startsWith("//")) "https:$v" else v }, "Server") }
        Regex("""["'](/(?:getLink|getWatch|file|watch)/[^"']+)["']""")
            .findAll(html).forEach { add(absUrl(it.groupValues[1], base), "Server") }
        Regex("""["'](/apis/redirect/[^"']+)["']""")
            .findAll(html).forEach { add(absUrl(it.groupValues[1], base), "MLBD CDN") }

        // 5) Live server buttons
        for (a in doc.select("a.mlbd-live-server-btn[href], .mlbd-live-server-btn[href]")) {
            add(absUrl(a.attr("abs:href").ifBlank { a.attr("href") }, base), a.text().ifBlank { "Live" })
        }

        // 6) Direct media in page
        Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4|mkv)[^\s"'<>\\]*""")
            .findAll(html).forEach { add(it.value, "Direct") }

        return out.map { (u, l) -> Srv(u, l) }
    }

    override val mainPage = mainPageOf(
        "/type/movies" to "Movies",
        "/type/series" to "Series",
        "/" to "Latest"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = getBase()
        val path = request.data
        val url = if (page <= 1) {
            if (path == "/") "$base/" else "$base$path"
        } else {
            if (path == "/") "$base/?page=$page"
            else if (path.contains("?")) "$base$path&page=$page"
            else "$base$path?page=$page"
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
                title = scope.selectFirst(".title, .movie-title, h3, h2, [class*=name]")
                    ?.text()?.trim().orEmpty()
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
            if (poster == null) poster = pickImg(a.selectFirst("img"), base)

            val seriesHint = Regex("""S\d+|Season|Ep\s*\d+""", RegexOption.IGNORE_CASE)
            val type = when {
                href.contains("/series/") || seriesHint.containsMatchIn(title) -> TvType.TvSeries
                href.contains("/anime/") -> TvType.AnimeMovie
                else -> TvType.Movie
            }

            seen.add(href)
            out.add(newMovieSearchResponse(title, href, type) { this.posterUrl = poster })
        }

        if (cards.isNotEmpty()) cards.forEach { addFrom(it) }
        else {
            doc.select(
                "a[href*=/movie/], a[href*=/series/], a[href*=/anime/], a[href*=/download18plus/]"
            ).forEach { addFrom(it) }
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse {
        val base = getBase()
        var pageUrl = url.trim()
        if (pageUrl.startsWith("/")) pageUrl = base + pageUrl
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
            ?: doc.selectFirst(".plot, .description, .synopsis, .entry-content p, .movie-desc")
                ?.text()?.trim()

        // Collect servers NOW so loadLinks has them even if re-fetch fails
        val servers = collectServers(doc, html, base)
        val payload = if (servers.isNotEmpty()) {
            servers.toJson()
        } else {
            // fallback: page url so loadLinks can re-scrape
            listOf(Srv(pageUrl, "Page")).toJson()
        }

        val seasonHint = Regex("""S\d+|Season""", RegexOption.IGNORE_CASE)
        val isSeries = pageUrl.contains("/series/") || seasonHint.containsMatchIn(title)

        if (isSeries) {
            val eps = listOf(
                newEpisode(payload) {
                    this.name = "Episode 1"
                    this.episode = 1
                }
            )
            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, eps) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, payload) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    private fun rewriteHost(url: String, base: String): String {
        return try {
            val u = java.net.URI(url)
            val b = java.net.URI(base)
            if (u.host != null && u.host.contains("movielinkbd")) {
                b.scheme + "://" + b.host + (u.path ?: "")
            } else url
        } catch (_: Exception) {
            url
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        val base = getBase()
        val added = HashSet<String>()
        var found = false

        // Parse JSON list of servers, or treat as single URL
        val servers = ArrayList<Srv>()
        try {
            val list: List<Srv> = parseJson(data)
            servers.addAll(list)
        } catch (_: Exception) {
            try {
                // maybe array of strings
                val list: List<String> = parseJson(data)
                list.forEach { servers.add(Srv(it, "Server")) }
            } catch (_: Exception) {
                servers.add(Srv(data, "Server"))
            }
        }

        // If payload was only the page URL, re-scrape for more links
        val pageLike = servers.filter {
            it.u.contains("/movie/") || it.u.contains("/series/") || it.u.contains("/anime/") ||
                it.l == "Page"
        }
        for (p in pageLike) {
            try {
                var pageUrl = p.u
                if (pageUrl.startsWith("/")) pageUrl = base + pageUrl
                pageUrl = rewriteHost(pageUrl, base)
                val html = httpGet(pageUrl, base)
                val doc = Jsoup.parse(html, base)
                val extra = collectServers(doc, html, base)
                for (e in extra) {
                    if (servers.none { it.u == e.u }) servers.add(e)
                }
            } catch (_: Exception) {
            }
        }

        for (s in servers) {
            var link = s.u.trim()
            if (link.startsWith("/")) link = base + link
            val label = s.l
            try {
                when {
                    link.contains(".m3u8") || link.contains(".mp4") || link.contains(".mkv") -> {
                        if (pushVideo(link, label, base, callback, added)) found = true
                    }
                    link.contains("/getLink") || link.contains("/file/") -> {
                        if (resolveGetLink(link, label, base, callback, subtitleCallback, added)) found = true
                    }
                    link.contains("/getWatch") || (link.contains("/watch/") && link.contains("movielinkbd")) -> {
                        if (resolveGetWatch(link, label, base, callback, subtitleCallback, added)) found = true
                    }
                    link.contains("/apis/redirect") -> {
                        if (resolveCdnUrl(link, label, base, callback, added)) found = true
                    }
                    else -> {
                        // external hoster
                        try {
                            if (loadExtractor(link, base, subtitleCallback, callback)) found = true
                        } catch (_: Exception) {
                        }
                        // still try generic page scrape
                        if (resolveGeneric(link, label, base, callback, subtitleCallback, added)) found = true
                    }
                }
            } catch (_: Exception) {
            }
        }
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
        var ok = false
        val html = try {
            httpGet(url, base)
        } catch (_: Exception) {
            return false
        }

        // file: '/apis/redirect/...'
        Regex("""file\s*:\s*["'](/apis/redirect/[^"']+)""").findAll(html).forEach { m ->
            if (resolveCdnUrl(base + m.groupValues[1], "MLBD CDN - $label", base, callback, added)) {
                ok = true
            }
        }
        Regex("""["'](/apis/redirect/[^"']+)["']""").findAll(html).forEach { m ->
            if (resolveCdnUrl(base + m.groupValues[1], "MLBD CDN - $label", base, callback, added)) {
                ok = true
            }
        }

        // const SRC = "https://..."
        Regex("""const\s+SRC\s*=\s*["'](https?://[^"']+)["']""").findAll(html).forEach { m ->
            if (pushVideo(m.groupValues[1], "SRC - $label", base, callback, added)) ok = true
        }

        // nested external links on getLink page
        val doc = Jsoup.parse(html, base)
        for (a in doc.select("a[href]")) {
            val href = absUrl(a.attr("abs:href").ifBlank { a.attr("href") }, base) ?: continue
            if (href.contains("movielinkbd")) continue
            if (isServerHref(href, a.text())) {
                try {
                    if (loadExtractor(href, url, subtitleCallback, callback)) ok = true
                } catch (_: Exception) {
                }
                if (href.contains(".m3u8") || href.contains(".mp4") || href.contains(".mkv")) {
                    if (pushVideo(href, a.text().ifBlank { label }, base, callback, added)) ok = true
                }
            }
        }

        if (pushMediaFromHtml(html, "DL - $label", base, callback, added)) ok = true

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
        var ok = false
        val html = try {
            httpGet(url, base)
        } catch (_: Exception) {
            return false
        }

        Regex("""(https?://[^\s'"]+/watch/[^\s'"]*)""").findAll(html).forEach { m ->
            if (resolveXCloud(m.groupValues[1], label, base, callback, added)) ok = true
        }

        if (resolveXCloud(url, label, base, callback, added)) ok = true
        if (pushMediaFromHtml(html, "XCloud - $label", base, callback, added)) ok = true

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
            // external links inside
            val doc = Jsoup.parse(html, base)
            for (a in doc.select("a[href]")) {
                val href = absUrl(a.attr("abs:href").ifBlank { a.attr("href") }, base) ?: continue
                if (!href.contains("movielinkbd") && isServerHref(href, a.text())) {
                    try {
                        if (loadExtractor(href, url, subtitleCallback, callback)) ok = true
                    } catch (_: Exception) {
                    }
                }
            }
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
            val resp = app.get(redirectUrl, headers = hdr(base), timeout = 35)
            var ok = false
            val final = resp.url
            if (final.contains(".m3u8") || final.contains(".mp4") || final.contains(".mkv")) {
                if (pushVideo(final, label, base, callback, added)) ok = true
            }
            if (pushMediaFromHtml(resp.text, label, base, callback, added)) ok = true
            // if body is tiny maybe it's still a URL
            val body = resp.text.trim()
            if (body.startsWith("http") && body.length < 500) {
                if (pushVideo(body.lineSequence().first(), label, base, callback, added)) ok = true
            }
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

            val inlineEl = Jsoup.parse(html).selectFirst("script#mlbdInlinePlayerData")
            val inline = inlineEl?.data()?.ifBlank { null } ?: inlineEl?.html()
            if (!inline.isNullOrBlank()) {
                if (pushMediaFromHtml(inline, "XCloud - $label", base, callback, added)) ok = true
            }

            for (iframe in Jsoup.parse(html, base).select("iframe[src]")) {
                val src = absUrl(
                    iframe.attr("abs:src").ifBlank { iframe.attr("src") },
                    base
                ) ?: continue
                try {
                    val inner = httpGet(src, pageUrl)
                    if (pushMediaFromHtml(inner, "XCloud - $label", base, callback, added)) ok = true
                } catch (_: Exception) {
                }
            }

            Regex("""const\s+SRC\s*=\s*["'](https?://[^"']+)["']""").findAll(html).forEach {
                if (pushVideo(it.groupValues[1], "XCloud - $label", base, callback, added)) ok = true
            }

            if (pushMediaFromHtml(html, "XCloud - $label", base, callback, added)) ok = true
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
