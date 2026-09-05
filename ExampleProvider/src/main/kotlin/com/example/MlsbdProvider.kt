@file:Suppress(
    "PackageName",
    "SpellCheckingInspection",
    "UnusedImport",
    "UNUSED_PARAMETER",
    "MemberVisibilityCanBePrivate",
    "FunctionName"
)

package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import java.net.URLEncoder

class MlsbdProvider : MainAPI() {
    override var mainUrl = "https://mlsbdtv.se"
    override var name = "MLSBD"
    override val hasMainPage = true
    override var lang = "bn"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    private val mirrors = listOf(
        "https://mlsbd.co",
        "https://mlsbdtv.se",
        "https://ww1.mlsbdtv.se"
    )

    private val ua =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private val hdr = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,bn;q=0.8"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/category/bengali-movies/" to "Bengali Movies",
        "$mainUrl/category/indian-web-series/" to "Indian Web Series",
        "$mainUrl/category/moviesda/" to "Moviesda",
        "$mainUrl/category/hdhub4u/" to "HDHub4u",
        "$mainUrl/category/bollyflix/" to "Bollyflix",
        "$mainUrl/genre/action-6/" to "Action",
        "$mainUrl/genre/comedy-6/" to "Comedy",
        "$mainUrl/genre/drama-6/" to "Drama",
        "$mainUrl/genre/horror-2/" to "Horror",
        "$mainUrl/featured/" to "Featured HD"
    )

    private suspend fun fetch(url: String): String {
        var last = ""
        for (base in listOf(mainUrl) + mirrors.filter { it != mainUrl }) {
            val u = try {
                if (url.startsWith("http")) {
                    val uri = URI(url)
                    val pathPart = (uri.path ?: "/") + (uri.query?.let { "?$it" } ?: "")
                    base.trimEnd('/') + pathPart
                } else {
                    base.trimEnd('/') + url
                }
            } catch (_: Throwable) {
                url
            }
            try {
                val doc = app.get(u, headers = hdr, timeout = 30).text
                if (doc.length > 2000 &&
                    !doc.contains("Verify you are human") &&
                    !doc.lowercase().contains("just a moment")
                ) {
                    mainUrl = base.trimEnd('/')
                    return doc
                }
                last = doc
            } catch (_: Throwable) {
            }
        }
        return last
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data
        else request.data.trimEnd('/') + "/page/$page/"
        val html = fetch(url)
        val list = parseCards(html)
        return newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val html = fetch("$mainUrl/?s=$q")
        return parseCards(html)
    }

    private fun parseCards(html: String): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        val articles = Regex(
            """<article[^>]*>[\s\S]*?</article>""",
            RegexOption.IGNORE_CASE
        ).findAll(html)

        for (art in articles) {
            val block = art.value
            val href = Regex(
                """class="[^"]*entry-title[^"]*"[^>]*>\s*<a[^>]+href="([^"]+)"""",
                RegexOption.IGNORE_CASE
            ).find(block)?.groupValues?.getOrNull(1)
                ?: Regex(
                    """<h2[^>]*class="[^"]*entry-title[^"]*"[^>]*>\s*<a[^>]+href="([^"]+)"""",
                    RegexOption.IGNORE_CASE
                ).find(block)?.groupValues?.getOrNull(1)
                ?: Regex(
                    """<a[^>]+href="(https?://[^"]+)"[^>]*rel="bookmark"""",
                    RegexOption.IGNORE_CASE
                ).find(block)?.groupValues?.getOrNull(1)
                ?: continue

            val url = absUrl(href).substringBefore("?").trimEnd('/') + "/"
            if (!seen.add(url)) continue
            if ("/category/" in url || "/genre/" in url || "/tag/" in url || "/page/" in url) continue

            val title = Regex(
                """<a[^>]+href="[^"]*"[^>]*title="([^"]+)"""",
                RegexOption.IGNORE_CASE
            ).find(block)?.groupValues?.getOrNull(1)
                ?: Regex(
                    """entry-title[^>]*>\s*<a[^>]*>([^<]+)</a>""",
                    RegexOption.IGNORE_CASE
                ).find(block)?.groupValues?.getOrNull(1)
                ?: url.substringAfterLast('/').replace('-', ' ')

            val poster = Regex(
                """<img[^>]+(?:data-src|src)="([^"]+)"""",
                RegexOption.IGNORE_CASE
            ).find(block)?.groupValues?.getOrNull(1)

            val isSeries = Regex(
                """season|series|episode|web\s*series""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(title)

            out.add(
                newMovieSearchResponse(
                    title.trim(),
                    url,
                    if (isSeries) TvType.TvSeries else TvType.Movie
                ) {
                    this.posterUrl = poster?.let { absUrl(it) }
                }
            )
        }

        if (out.isEmpty()) {
            Regex(
                """<a[^>]+href="(https?://[^"]+)"[^>]*title="([^"]+)"[^>]*rel="bookmark"""",
                RegexOption.IGNORE_CASE
            ).findAll(html).forEach { m ->
                val url = absUrl(m.groupValues[1]).substringBefore("?").trimEnd('/') + "/"
                if (!seen.add(url)) return@forEach
                if ("/category/" in url || "/genre/" in url) return@forEach
                out.add(newMovieSearchResponse(m.groupValues[2].trim(), url, TvType.Movie))
            }
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = absUrl(url).substringBefore("?").trimEnd('/') + "/"
        val html = fetch(pageUrl)

        val title = Regex(
            """property="og:title"[^>]+content="([^"]+)"""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)
            ?: Regex(
                """<h1[^>]*class="[^"]*entry-title[^"]*"[^>]*>([^<]+)</h1>""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)
            ?: pageUrl.trimEnd('/').substringAfterLast('/').replace('-', ' ')

        val poster = Regex(
            """property="og:image"[^>]+content="([^"]+)"""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)

        val plot = Regex(
            """property="og:description"[^>]+content="([^"]+)"""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)

        val year = Regex("""\((20\d{2}|19\d{2})\)""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val tags = Regex(
            """itemprop="genre"[^>]*>([^<]+)""",
            RegexOption.IGNORE_CASE
        ).findAll(html).map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.distinct().toList()

        val watchLinks = extractWatchLinks(html)
        val dataPayload = if (watchLinks.isEmpty()) {
            pageUrl
        } else {
            pageUrl + "|||" + watchLinks.joinToString("|||") { pair ->
                pair.first + "::" + pair.second
            }
        }

        val isSeries = Regex(
            """season|series|complete|episode""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(title)

        return if (isSeries) {
            val ep = newEpisode(dataPayload) {
                this.name = title.trim()
                this.episode = 1
            }
            newTvSeriesLoadResponse(title.trim(), pageUrl, TvType.TvSeries, listOf(ep)) {
                this.posterUrl = poster?.let { absUrl(it) }
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title.trim(), pageUrl, TvType.Movie, dataPayload) {
                this.posterUrl = poster?.let { absUrl(it) }
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        }
    }

    private fun extractWatchLinks(html: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()

        Regex(
            """<tr class="tritem">([\s\S]*?)</tr>""",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEach { rowMatch ->
            val row = rowMatch.value
            val serverName = Regex(
                """<td[^>]*>\s*([^<]+)\s*</td>""",
                RegexOption.IGNORE_CASE
            ).find(row)?.groupValues?.getOrNull(1)?.trim() ?: "Server"

            val candidates = ArrayList<String>()
            Regex(
                """href="([^"]+)"[^>]*>\s*Watch""",
                RegexOption.IGNORE_CASE
            ).findAll(row).forEach { candidates.add(it.groupValues[1]) }
            Regex(
                """href="([^"]+)"""",
                RegexOption.IGNORE_CASE
            ).findAll(row).forEach { candidates.add(it.groupValues[1]) }

            for (raw in candidates) {
                val link = normalizeLink(raw) ?: continue
                val key = link.substringBefore("?").lowercase()
                if (!seen.add(key)) continue

                val hosts = listOf(
                    "ok.ru", "mixdrop", "miixdrop", "dood", "do7go",
                    "pkembed", "streamtape", "filemoon", "voe", "upstream"
                )
                val isHost = hosts.any { it in key }
                if (!isHost && "mlsbd" in key) continue
                if (listOf("highperformance", "effectivecpm", "facebook", "twitter", "t.me")
                        .any { it in key }
                ) continue

                out.add(serverName to link)
                break
            }
        }

        if (out.isEmpty()) {
            Regex(
                """(?:src|href)="(https?://[^"]*(?:embed|videoembed|/e/)[^"]+)"""",
                RegexOption.IGNORE_CASE
            ).findAll(html).forEach { m ->
                val link = normalizeLink(m.groupValues[1]) ?: return@forEach
                if (seen.add(link)) out.add("Embed" to link)
            }
        }
        return out
    }

    private fun normalizeLink(raw: String): String? {
        var u = raw.trim()
        if (u.isBlank() || u == "#" || u.startsWith("javascript", true)) return null
        if (u.startsWith("ttps://")) u = "h$u"
        if (u.startsWith("//")) u = "https:$u"
        if (!u.startsWith("http")) {
            if (u.startsWith("/")) u = mainUrl.trimEnd('/') + u
            else return null
        }
        return u
    }

    private fun absUrl(u: String): String {
        val t = u.trim()
        return when {
            t.startsWith("http") -> t
            t.startsWith("//") -> "https:$t"
            t.startsWith("/") -> mainUrl.trimEnd('/') + t
            else -> mainUrl.trimEnd('/') + "/" + t
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|||")
        val pageUrl = parts.firstOrNull()?.trim().orEmpty()
        val preParsed = parts.drop(1).mapNotNull { item ->
            val idx = item.indexOf("::")
            if (idx <= 0) null
            else item.substring(0, idx) to item.substring(idx + 2)
        }

        val links = if (preParsed.isNotEmpty()) {
            preParsed
        } else {
            val html = fetch(if (pageUrl.startsWith("http")) pageUrl else data)
            extractWatchLinks(html)
        }

        var found = false
        for ((name, link) in links) {
            if (resolveOne(name, link, subtitleCallback, callback)) found = true
        }

        if (!found && pageUrl.startsWith("http")) {
            val html = fetch(pageUrl)
            for ((name, link) in extractWatchLinks(html)) {
                if (resolveOne(name, link, subtitleCallback, callback)) found = true
            }
        }
        return found
    }

    private suspend fun resolveOne(
        serverName: String,
        link: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var ok = false
        try {
            // loadExtractor already returns ExtractorLink — forward directly (no suspend in lambda)
            loadExtractor(link, mainUrl, subtitleCallback) { ext ->
                ok = true
                callback.invoke(ext)
            }
        } catch (_: Throwable) {
        }

        if (!ok && (link.contains(".m3u8") || link.contains(".mp4"))) {
            ok = true
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = serverName,
                    url = link,
                    type = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.P1080.value
                }
            )
        }
        return ok
    }
}
