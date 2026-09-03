@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Free MovieLinkBD provider (no CNC ads / subscription).
 * Domains rotate: *.movielinkbd.li mirrors + movielinkbd.one
 */
class MovieLinkBDProvider : MainAPI() {
    override var mainUrl = "https://sd2hbb.movielinkbd.li"
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
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** Multi-domain fallback (Cloudflare / mirror changes) */
    private val domainPool = listOf(
        "https://sd2hbb.movielinkbd.li",
        "https://movielinkbd.one",
        "https://58usfd.movielinkbd.li",
        "https://pyr5us.movielinkbd.li",
        "https://mlink4f3.movielinkbd.li",
        "https://afz5z7.movielinkbd.li",
        "https://movielinkbd.li"
    )

    private var resolvedBase: String? = null

    private fun hdr(ref: String? = null): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,bn;q=0.8",
        "Referer" to (ref ?: "$mainUrl/")
    )

    private suspend fun getBase(): String {
        resolvedBase?.let { return it }
        for (d in domainPool) {
            try {
                val r = app.get(d, headers = hdr(d), timeout = 15)
                val t = r.text
                if (r.code in 200..399 && !t.contains("Just a moment", true) &&
                    !t.contains("Verify you are human", true) && t.length > 2000
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
        return app.get(url, headers = hdr(ref ?: mainUrl)).text
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
            else -> "$base$path?page=$page"
        }
        return try {
            val html = httpGet(url, base)
            newHomePageResponse(request.name, parseMovieCards(html, base), true)
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
            "div.movie-item, div.item-box, div.film-item, div.post-item, .movie-card, article"
        )
        if (cards.isNotEmpty()) {
            for (card in cards) {
                val a = card.selectFirst(
                    "a[href*=/movie/], a[href*=/series/], a[href*=/anime/], a[href*=/download18plus/], a[href]"
                ) ?: continue
                val item = cardToSearch(a, card, base) ?: continue
                if (seen.add(item.url)) out.add(item)
            }
        } else {
            for (a in doc.select(
                "a[href*=/movie/], a[href*=/series/], a[href*=/anime/], a[href*=/download18plus/]"
            )) {
                val item = cardToSearch(a, a, base) ?: continue
                if (seen.add(item.url)) out.add(item)
            }
        }
        return out
    }

    private fun cardToSearch(a: Element, scope: Element, base: String): SearchResponse? {
        var href = a.attr("abs:href")
        if (href.isBlank()) href = a.attr("href")
        if (href.startsWith("/")) href = base + href
        href = href.substringBefore("#").substringBefore("?").trimEnd('/')
        if (!href.contains("movielinkbd") && !href.startsWith(base)) return null
        if (href.contains("/type/") || href.contains("/search") || href.contains("/getLink") ||
            href.contains("/getWatch")
        ) return null

        var title = a.attr("title").trim()
        if (title.isBlank()) title = a.text().trim()
        if (title.isBlank()) title = scope.selectFirst("h2, h3, .title, .name")?.text()?.trim().orEmpty()
        if (title.length < 2) return null

        var poster: String? = null
        val img = scope.selectFirst(
            "img.poster, img[class*=poster], .poster img, .thumb img, img[src*=poster], img[src*=uploads], img"
        )
        if (img != null) {
            poster = img.attr("abs:src")
            if (poster.isNullOrBlank()) poster = img.attr("src")
            if (poster.isNullOrBlank()) poster = img.attr("data-src")
            if (poster.isNullOrBlank()) poster = img.attr("data-lazy-src")
            if (!poster.isNullOrBlank() && poster!!.startsWith("/")) poster = base + poster
        }

        val type = when {
            href.contains("/series/") || title.contains("S0", true) ||
                title.contains("Season", true) -> TvType.TvSeries
            href.contains("/anime/") -> TvType.AnimeMovie
            else -> TvType.Movie
        }

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val base = getBase()
        var pageUrl = url
        if (pageUrl.startsWith("/")) pageUrl = base + pageUrl
        val html = httpGet(pageUrl, base)
        val doc = Jsoup.parse(html, base)

        var title = doc.selectFirst("h1, .movie-title, .title, .entry-title")?.text()?.trim().orEmpty()
        if (title.isBlank()) {
            title = doc.selectFirst("title")?.text()?.substringBefore("|")?.substringBefore("-")?.trim().orEmpty()
        }

        var poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        if (poster.isNullOrBlank()) {
            val img = doc.selectFirst("img.poster, .poster img, img[src*=poster], img[src*=uploads]")
            poster = img?.attr("abs:src") ?: img?.attr("src")
        }
        if (!poster.isNullOrBlank() && poster!!.startsWith("/")) poster = base + poster

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst(".plot, .description, .synopsis, .entry-content p")?.text()

        // Collect download + watch sources on detail page
        data class Src(val url: String, val label: String, val kind: String)

        val all = ArrayList<Src>()
        for (a in doc.select(
            "a[href*=/getLink/], a[href*=/getWatch/], a.mlbd-live-server-btn[href], " +
                "a[href*=/watch/], a[href*=/file/]"
        )) {
            var href = a.attr("abs:href")
            if (href.isBlank()) href = a.attr("href")
            if (href.startsWith("/")) href = base + href
            if (!href.startsWith("http")) continue
            val label = a.text().trim().ifBlank { "Server" }
            val kind = when {
                href.contains("/getWatch") || href.contains("/watch/") ||
                    a.hasClass("mlbd-live-server-btn") -> "watch"
                else -> "download"
            }
            all.add(Src(href, label, kind))
        }

        // Episode cards (series)
        val episodes = ArrayList<Episode>()
        val epCards = doc.select("div.ep-card, [data-ep]")
        if (epCards.isNotEmpty()) {
            epCards.forEachIndexed { idx, card ->
                val epText = card.text()
                val m = Regex("""(?:Ep|Episode)[^\d]*(\d+)""", RegexOption.IGNORE_CASE).find(epText)
                val epNum = m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (idx + 1)
                val sources = ArrayList<Map<String, String>>()
                for (a in card.select("a[href]")) {
                    var href = a.attr("abs:href")
                    if (href.isBlank()) href = a.attr("href")
                    if (href.startsWith("/")) href = base + href
                    if (!href.startsWith("http")) continue
                    if (!href.contains("/getLink") && !href.contains("/getWatch") &&
                        !href.contains("/watch/") && !href.contains("/file/")
                    ) continue
                    val label = a.text().trim().ifBlank { "Server" }
                    sources.add(
                        mapOf(
                            "url" to href,
                            "label" to label,
                            "kind" to if (href.contains("Watch") || href.contains("/watch")) "watch" else "download"
                        )
                    )
                }
                if (sources.isEmpty()) {
                    // use global all for each ep if buttons are global
                    for (s in all) {
                        sources.add(mapOf("url" to s.url, "label" to s.label, "kind" to s.kind))
                    }
                }
                if (sources.isNotEmpty()) {
                    episodes.add(
                        newEpisode(sources.toJson()) {
                            this.name = "Episode $epNum"
                            this.episode = epNum
                        }
                    )
                }
            }
        }

        if (episodes.isEmpty()) {
            // Movie / single pack
            val sources = all.map {
                mapOf("url" to it.url, "label" to it.label, "kind" to it.kind)
            }
            if (sources.isEmpty()) throw ErrorLoadingException("No servers found")

            val isSeries = pageUrl.contains("/series/") || title.contains("Season", true)
            if (isSeries) {
                episodes.add(
                    newEpisode(sources.toJson()) {
                        this.name = "Episode 1"
                        this.episode = 1
                    }
                )
                return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, sources.toJson()) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        return newTvSeriesLoadResponse(
            title,
            pageUrl,
            TvType.TvSeries,
            episodes.sortedBy { it.episode }
        ) {
            this.posterUrl = poster
            this.plot = plot
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

        val sources: List<Map<String, String>> = try {
            parseJson(data)
        } catch (_: Exception) {
            listOf(mapOf("url" to data, "label" to "Server", "kind" to "watch"))
        }

        var found = false
        val added = HashSet<String>()

        for (src in sources) {
            val url = src["url"] ?: continue
            val label = src["label"] ?: "Server"
            val kind = src["kind"] ?: ""
            try {
                if (url.contains("/getWatch") || url.contains("/watch/") || kind == "watch") {
                    if (resolveGetWatch(url, label, base, callback, subtitleCallback, added)) {
                        found = true
                    }
                } else if (url.contains("/getLink") || url.contains("/file/") || kind == "download") {
                    if (resolveGetLink(url, label, base, callback, subtitleCallback, added)) {
                        found = true
                    }
                } else {
                    if (resolveGeneric(url, label, base, callback, subtitleCallback, added)) {
                        found = true
                    }
                }
            } catch (_: Exception) {
            }
        }
        return found
    }

    /** /getLink/ page → file: '/apis/redirect/...' → CDN stream */
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

        // apis/redirect path
        val redir = Regex("""file\s*:\s*["'](/apis/redirect/[^"']+)""")
            .find(html)?.groupValues?.getOrNull(1)
        if (!redir.isNullOrBlank()) {
            if (resolveCdnUrl(base + redir, "MLBD CDN • $label", base, callback, added)) {
                ok = true
            }
        }

        // direct media in page
        if (pushMediaFromHtml(html, "DL • $label", base, callback, added)) ok = true

        // any absolute media links
        Regex("""https?://[^\s"'<>]+\.(?:m3u8|mp4|mkv)[^\s"'<>]*""").findAll(html).forEach { m ->
            if (pushVideo(m.value, "File • $label", base, callback, added)) ok = true
        }

        try {
            if (loadExtractor(url, base, subtitleCallback, callback)) ok = true
        } catch (_: Exception) {
        }
        return ok
    }

    /** /getWatch/ → player page → XCloud / m3u8 */
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

        // watch URL in page
        val watch = Regex("""(https?://[^\s'"]+/watch/[^\s'"]*)""").find(html)?.groupValues?.getOrNull(1)
        if (!watch.isNullOrBlank()) {
            if (resolveXCloud(watch, label, base, callback, added)) ok = true
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
            if (loadExtractor(url, base, subtitleCallback, callback)) ok = true
        } catch (_: Exception) {
        }
        if (!ok) {
            try {
                val html = httpGet(url, base)
                if (pushMediaFromHtml(html, label, base, callback, added)) ok = true
            } catch (_: Exception) {
            }
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
            val resp = app.get(redirectUrl, headers = hdr(base))
            val final = resp.url
            val body = resp.text
            var ok = false
            if (final.contains(".m3u8") || final.contains(".mp4") || final.contains(".mkv")) {
                if (pushVideo(final, label, base, callback, added)) ok = true
            }
            if (pushMediaFromHtml(body, label, base, callback, added)) ok = true
            // Location style
            Regex("""https?://[^\s"'<>]+\.(?:m3u8|mp4|mkv)[^\s"'<>]*""").findAll(body).forEach {
                if (pushVideo(it.value, label, base, callback, added)) ok = true
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

            // script#mlbdInlinePlayerData
            val inline = Jsoup.parse(html).selectFirst("script#mlbdInlinePlayerData")?.data()
            if (!inline.isNullOrBlank()) {
                if (pushMediaFromHtml(inline, "XCloud • $label", base, callback, added)) ok = true
            }

            // iframe
            for (iframe in Jsoup.parse(html, base).select("iframe[src]")) {
                var src = iframe.attr("abs:src")
                if (src.isBlank()) src = iframe.attr("src")
                if (!src.startsWith("http")) continue
                try {
                    val inner = httpGet(src, pageUrl)
                    if (pushMediaFromHtml(inner, "XCloud • $label", base, callback, added)) ok = true
                } catch (_: Exception) {
                }
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
        // file|src : "http...m3u8|mp4|mkv"
        Regex("""(?:file|src)\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4|mkv)[^"']*)""")
            .findAll(html).forEach {
                if (pushVideo(it.groupValues[1], label, referer, callback, added)) ok = true
            }
        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(html).forEach {
            if (pushVideo(it.value, label, referer, callback, added)) ok = true
        }
        Regex("""https?://[^\s"'<>]+\.(?:mp4|mkv)[^\s"'<>]*""").findAll(html).forEach {
            if (pushVideo(it.value, label, referer, callback, added)) ok = true
        }
        // video tags
        try {
            for (v in Jsoup.parse(html).select("video source[src], video[src]")) {
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
        val u = url.trim().trimEnd('"', '\'', '\\')
        if (!u.startsWith("http")) return false
        if (!added.add(u)) return false
        val q = when {
            label.contains("1080", true) -> Qualities.P1080.value
            label.contains("720", true) -> Qualities.P720.value
            label.contains("480", true) -> Qualities.P480.value
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
