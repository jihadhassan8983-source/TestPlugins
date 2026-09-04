@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

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
        if (h.startsWith("javascript", true) || h == "#" || h.startsWith("mailto:", true)) return null
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

    private fun qualityFrom(text: String): Int {
        val t = text.lowercase()
        return when {
            "2160" in t || "4k" in t -> Qualities.P2160.value
            "1080" in t -> Qualities.P1080.value
            "720" in t -> Qualities.P720.value
            "480" in t -> Qualities.P480.value
            "360" in t -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    // ---------- listing ----------

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

        fun addFrom(scope: Element) {
            val a = scope.selectFirst(
                "a[href*=/movie/], a[href*=/series/], a[href*=/anime/], a[href*=/download18plus/]"
            ) ?: return

            var href = absUrl(a.attr("abs:href").ifBlank { a.attr("href") }, base) ?: return
            href = href.substringBefore("#").substringBefore("?").trimEnd('/')
            if (!seen.add(href)) return

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

            out.add(newMovieSearchResponse(title, href, type) { this.posterUrl = poster })
        }

        val cards = doc.select(
            "div.movie-item, div.item-box, div.film-item, div.post-item, .movie-card, article, .card, .item"
        )
        if (cards.isNotEmpty()) cards.forEach { addFrom(it) }
        else {
            doc.select(
                "a[href*=/movie/], a[href*=/series/], a[href*=/anime/], a[href*=/download18plus/]"
            ).forEach { addFrom(it.parent() ?: it) }
        }
        return out
    }

    // ---------- load ----------

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

        // Collect ALL server links from page (watch + download)
        val pageServers = collectAllServers(doc, html, base)

        // Build episodes
        val episodes = buildEpisodes(doc, html, pageUrl, base, pageServers)

        val seasonHint = Regex("""S\d+|Season""", RegexOption.IGNORE_CASE)
        val isSeries = pageUrl.contains("/series/") ||
            episodes.size > 1 ||
            seasonHint.containsMatchIn(title)

        if (isSeries) {
            val eps = if (episodes.isEmpty()) {
                listOf(
                    newEpisode(encodePayload(pageUrl, pageServers)) {
                        name = "Full Pack"
                        episode = 1
                    }
                )
            } else episodes

            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, eps) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        return newMovieLoadResponse(
            title,
            pageUrl,
            TvType.Movie,
            encodePayload(pageUrl, pageServers)
        ) {
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

    /** Encode pageUrl + server list as JSON map list */
    private fun encodePayload(pageUrl: String, servers: List<Pair<String, String>>): String {
        val list = ArrayList<Map<String, String>>()
        list.add(mapOf("u" to pageUrl, "l" to "PAGE"))
        for ((u, l) in servers) {
            list.add(mapOf("u" to u, "l" to l))
        }
        return list.toJson()
    }

    private fun decodePayload(data: String): List<Pair<String, String>> {
        return try {
            val raw: List<Map<String, String>> = parseJson(data)
            raw.mapNotNull { m ->
                val u = m["u"] ?: return@mapNotNull null
                val l = m["l"] ?: "Server"
                u to l
            }
        } catch (_: Exception) {
            listOf(data to "Server")
        }
    }

    /**
     * Collect getWatch / getLink / file / live / external hoster / quality buttons
     */
    private fun collectAllServers(
        doc: Document,
        html: String,
        base: String
    ): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()

        fun add(url: String?, label: String) {
            var u = url?.trim() ?: return
            u = absUrl(u, base) ?: return
            if (!u.startsWith("http")) return
            if (u.contains("facebook") || u.contains("telegram") || u.contains("whatsapp")) return
            if (u.contains("/type/") || u.contains("/search?")).return
            val prev = out[u]
            if (prev == null || (prev == "Server" && label != "Server")) {
                out[u] = label.ifBlank { "Server" }.take(80)
            }
        }

        // Anchors – broad
        for (a in doc.select("a[href]")) {
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            val text = a.text().trim().ifBlank {
                a.attr("title").ifBlank { a.attr("aria-label") }
            }
            val h = href.lowercase()
            val t = text.lowercase()
            val interesting =
                h.contains("/getlink") || h.contains("/getwatch") || h.contains("/file/") ||
                    h.contains("/watch/") || h.contains("/apis/redirect") ||
                    h.contains("gdflix") || h.contains("hubcloud") || h.contains("hubdrive") ||
                    h.contains("pixeldrain") || h.contains("gofile") || h.contains("drive.google") ||
                    h.contains("streamtape") || h.contains("mediafire") ||
                    a.hasClass("mlbd-live-server-btn") ||
                    Regex("""\b(480p|720p|1080p|2160p|4k|hevc)\b""").containsMatchIn(t) ||
                    t.contains("watch online") || t.contains("download") || t.contains("server")
            if (interesting) add(href, text.ifBlank { "Server" })
        }

        // data attributes (quality player buttons)
        for (el in doc.select("[data-href], [data-url], [data-link], [data-src], [data-file], [data-stream], [data-quality]")) {
            for (attr in listOf("data-href", "data-url", "data-link", "data-src", "data-file", "data-stream")) {
                val v = el.attr(attr)
                if (v.isNotBlank()) {
                    val label = el.text().ifBlank {
                        el.attr("data-quality").ifBlank { el.attr("title") }
                    }
                    add(v, label.ifBlank { "Server" })
                }
            }
        }

        // onclick
        for (el in doc.select("[onclick]")) {
            val oc = el.attr("onclick")
            Regex("""['"](https?://[^'"]+)['"]""").findAll(oc).forEach {
                add(it.groupValues[1], el.text().ifBlank { "Server" })
            }
            Regex("""['"](/(?:getLink|getWatch|file|watch)/[^'"]+)['"]""").findAll(oc).forEach {
                add(it.groupValues[1], el.text().ifBlank { "Server" })
            }
        }

        // live buttons
        doc.select("a.mlbd-live-server-btn[href], .mlbd-live-server-btn[href]").forEach { a ->
            add(a.attr("abs:href").ifBlank { a.attr("href") }, a.text().ifBlank { "Live" })
        }

        // raw paths in HTML
        Regex("""["'](/(?:getLink|getWatch|file|watch)/[^"'\s]+)["']""").findAll(html).forEach {
            add(it.groupValues[1], "Server")
        }
        Regex("""["'](/apis/redirect/[^"']+)["']""").findAll(html).forEach {
            add(it.groupValues[1], "MLBD CDN")
        }
        Regex("""https?://[^\s"'<>]+/(?:getLink|getWatch|file|watch)/[A-Za-z0-9_\-./%]+""")
            .findAll(html).forEach { add(it.value, "Server") }

        // direct media
        Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4|mkv)(?:\?[^\s"'<>\\]*)?""")
            .findAll(html).forEach { add(it.value, "Direct") }

        // script JSON-ish sources
        Regex(
            """["'](?:file|src|url|stream|link)["']\s*:\s*["'](https?://[^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEach {
            add(it.groupValues[1], "Stream")
        }
        Regex("""const\s+SRC\s*=\s*["'](https?://[^"']+)["']""").findAll(html).forEach {
            add(it.groupValues[1], "SRC")
        }

        return out.map { it.key to it.value }
    }

    /**
     * Build episode list:
     * - individual Ep N sections
     * - range Ep 1-50 → expand to 50 episodes (same servers if pack)
     * - fallback one Full Pack
     */
    private fun buildEpisodes(
        doc: Document,
        html: String,
        pageUrl: String,
        base: String,
        pageServers: List<Pair<String, String>>
    ): List<Episode> {
        data class EpBucket(val num: Int, val name: String, val links: MutableList<Pair<String, String>>)
        val buckets = LinkedHashMap<Int, EpBucket>()

        fun bucket(num: Int, name: String): EpBucket {
            return buckets.getOrPut(num) { EpBucket(num, name, ArrayList()) }
        }

        val sections = doc.select(
            "div.ep-card, [data-ep], div.episode-section, div.season-section, " +
                "div[class*=episode], div[class*=season], li[class*=episode], " +
                "tr[class*=episode], .episode-item, .ep-item"
        )

        val epRegex = Regex(
            """(?:Ep|Episode|E)\s*([0-9]{1,4})(?:\s*[-–to]+\s*([0-9]{1,4}))?""",
            RegexOption.IGNORE_CASE
        )

        if (sections.isNotEmpty()) {
            sections.forEachIndexed { idx, sec ->
                val text = sec.text()
                val m = epRegex.find(text)
                val start = m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (idx + 1)
                val end = m?.groupValues?.getOrNull(2)?.toIntOrNull()

                val localLinks = ArrayList<Pair<String, String>>()
                for (a in sec.select(
                    "a[href*=/getLink/], a[href*=/getWatch/], a[href*=/watch/], " +
                        "a[href*=/file/], a.mlbd-live-server-btn, a[href]"
                )) {
                    val href = absUrl(a.attr("abs:href").ifBlank { a.attr("href") }, base) ?: continue
                    val label = a.text().trim().ifBlank { "Server" }
                    val hl = href.lowercase()
                    if (hl.contains("/getlink") || hl.contains("/getwatch") ||
                        hl.contains("/file/") || hl.contains("/watch/") ||
                        hl.contains("gdflix") || hl.contains("hubcloud") ||
                        label.contains("480") || label.contains("720") || label.contains("1080")
                    ) {
                        localLinks.add(href to label)
                    }
                }

                if (end != null && end > start && end - start < 200) {
                    // range pack e.g. Ep 1-24
                    for (n in start..end) {
                        val b = bucket(n, "Episode $n")
                        if (localLinks.isNotEmpty()) b.links.addAll(localLinks)
                        else b.links.addAll(pageServers)
                    }
                } else {
                    val b = bucket(start, "Episode $start")
                    if (localLinks.isNotEmpty()) b.links.addAll(localLinks)
                    else b.links.addAll(pageServers)
                }
            }
        }

        // Title / page text range: "Ep 1-50 Added"
        if (buckets.isEmpty()) {
            val m = epRegex.find(doc.text())
            val start = m?.groupValues?.getOrNull(1)?.toIntOrNull()
            val end = m?.groupValues?.getOrNull(2)?.toIntOrNull()
            if (start != null && end != null && end > start && end - start < 200) {
                for (n in start..end) {
                    bucket(n, "Episode $n").links.addAll(pageServers)
                }
            }
        }

        // Still empty → single pack
        if (buckets.isEmpty() && pageServers.isNotEmpty()) {
            bucket(1, "Full Pack").links.addAll(pageServers)
        }

        return buckets.toSortedMap().map { (_, b) ->
            val links = if (b.links.isEmpty()) pageServers else b.links.distinctBy { it.first }
            newEpisode(encodePayload(pageUrl, links)) {
                name = b.name
                episode = b.num
            }
        }
    }

    // ---------- loadLinks ----------

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

        val items = decodePayload(data).toMutableList()

        // Re-scrape PAGE entries for fresh links
        val pageUrls = items.filter { it.second == "PAGE" }.map { it.first }.toSet()
        for (pu in pageUrls) {
            try {
                var pageUrl = pu
                if (pageUrl.startsWith("/")) pageUrl = base + pageUrl
                pageUrl = rewriteHost(pageUrl, base)
                val html = httpGet(pageUrl, base)
                val doc = Jsoup.parse(html, base)
                for (s in collectAllServers(doc, html, base)) {
                    if (items.none { it.first == s.first }) items.add(s)
                }
                // player media already in HTML
                if (extractAndPushMedia(html, "Player", base, callback, added)) found = true
            } catch (_: Exception) {
            }
        }

        for ((url0, label0) in items) {
            if (label0 == "PAGE") continue
            var link = url0.trim()
            if (link.startsWith("/")) link = base + link
            val label = label0
            try {
                when {
                    link.contains(".m3u8") || link.contains(".mp4") || link.contains(".mkv") -> {
                        if (pushVideo(link, label, base, callback, added)) found = true
                    }
                    link.contains("/getLink") || link.contains("/file/") -> {
                        if (resolveGetLink(link, label, base, callback, subtitleCallback, added)) {
                            found = true
                        }
                    }
                    link.contains("/getWatch") ||
                        (link.contains("/watch/") && link.contains("movielinkbd")) -> {
                        if (resolveGetWatch(link, label, base, callback, subtitleCallback, added)) {
                            found = true
                        }
                    }
                    link.contains("/apis/redirect") -> {
                        if (resolveCdnUrl(link, label, base, callback, added)) found = true
                    }
                    else -> {
                        try {
                            if (loadExtractor(link, base, subtitleCallback, callback)) found = true
                        } catch (_: Exception) {
                        }
                        if (resolveGeneric(link, label, base, callback, subtitleCallback, added)) {
                            found = true
                        }
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
        val html = try {
            httpGet(url, base)
        } catch (_: Exception) {
            return false
        }
        var ok = false

        Regex("""file\s*:\s*["'](/apis/redirect/[^"']+)""").findAll(html).forEach { m ->
            if (resolveCdnUrl(base + m.groupValues[1], "CDN - $label", base, callback, added)) {
                ok = true
            }
        }
        Regex("""["'](/apis/redirect/[^"']+)["']""").findAll(html).forEach { m ->
            if (resolveCdnUrl(base + m.groupValues[1], "CDN - $label", base, callback, added)) {
                ok = true
            }
        }
        Regex("""const\s+SRC\s*=\s*["'](https?://[^"']+)["']""").findAll(html).forEach { m ->
            if (pushVideo(m.groupValues[1], "SRC - $label", base, callback, added)) ok = true
        }

        // external hosters on intermediate page
        val doc = Jsoup.parse(html, base)
        for (a in doc.select("a[href]")) {
            val href = absUrl(a.attr("abs:href").ifBlank { a.attr("href") }, base) ?: continue
            if (href.contains("movielinkbd")) continue
            try {
                if (loadExtractor(href, url, subtitleCallback, callback)) ok = true
            } catch (_: Exception) {
            }
            if (href.contains(".m3u8") || href.contains(".mp4") || href.contains(".mkv")) {
                if (pushVideo(href, a.text().ifBlank { label }, base, callback, added)) ok = true
            }
        }

        if (extractAndPushMedia(html, "DL - $label", base, callback, added)) ok = true
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
        val html = try {
            httpGet(url, base)
        } catch (_: Exception) {
            return false
        }
        var ok = false

        Regex("""(https?://[^\s'"]+/watch/[^\s'"]*)""").findAll(html).forEach { m ->
            if (resolveXCloud(m.groupValues[1], label, base, callback, added)) ok = true
        }
        if (resolveXCloud(url, label, base, callback, added)) ok = true
        if (extractAndPushMedia(html, "Watch - $label", base, callback, added)) ok = true
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
            if (extractAndPushMedia(html, label, base, callback, added)) ok = true
            for (a in Jsoup.parse(html, base).select("a[href]")) {
                val href = absUrl(a.attr("abs:href").ifBlank { a.attr("href") }, base) ?: continue
                if (href.contains("movielinkbd")) continue
                try {
                    if (loadExtractor(href, url, subtitleCallback, callback)) ok = true
                } catch (_: Exception) {
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
            if (extractAndPushMedia(resp.text, label, base, callback, added)) ok = true
            val body = resp.text.trim()
            if (body.startsWith("http") && body.length < 800) {
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
                if (extractAndPushMedia(inline, "XCloud - $label", base, callback, added)) ok = true
            }

            for (iframe in Jsoup.parse(html, base).select("iframe[src]")) {
                val src = absUrl(
                    iframe.attr("abs:src").ifBlank { iframe.attr("src") },
                    base
                ) ?: continue
                try {
                    val inner = httpGet(src, pageUrl)
                    if (extractAndPushMedia(inner, "XCloud - $label", base, callback, added)) {
                        ok = true
                    }
                } catch (_: Exception) {
                }
            }

            Regex("""const\s+SRC\s*=\s*["'](https?://[^"']+)["']""").findAll(html).forEach {
                if (pushVideo(it.groupValues[1], "XCloud - $label", base, callback, added)) ok = true
            }

            if (extractAndPushMedia(html, "XCloud - $label", base, callback, added)) ok = true
            ok
        } catch (_: Exception) {
            false
        }
    }

    private fun extractAndPushMedia(
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
        Regex("""https?://[^\s"'<>\\]+\.(?:mp4|mkv)(?:\?[^\s"'<>\\]*)?""").findAll(html).forEach {
            if (pushVideo(it.value, label, referer, callback, added)) ok = true
        }
        Regex(
            """["'](?:file|src|url|stream|link)["']\s*:\s*["'](https?://[^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEach {
            val u = it.groupValues[1]
            if (u.contains(".m3u8") || u.contains(".mp4") || u.contains(".mkv") ||
                u.contains("/stream") || u.contains("/video")
            ) {
                if (pushVideo(u, label, referer, callback, added)) ok = true
            }
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

        callback.invoke(
            ExtractorLink(
                name,
                label,
                u,
                referer,
                qualityFrom(label + " " + u),
                u.contains(".m3u8")
            )
        )
        return true
    }
}
