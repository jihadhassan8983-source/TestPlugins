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
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.net.URLEncoder

class MlsbdProvider : MainAPI() {
    override var mainUrl = "https://mlsbd.co"
    override var name = "MLSBD"
    override val hasMainPage = true
    override var lang = "bn"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    // Cloudflare থাকলে মোবাইলে প্রায়ই চলে; না চললে নিচের mirror ব্যবহার করো
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
        val path = url.removePrefix(mainUrl).ifBlank { "/" }
        for (base in listOf(mainUrl) + mirrors.filter { it != mainUrl }) {
            val u = if (url.startsWith("http")) {
                // swap host to current base when retrying
                try {
                    val pathPart = java.net.URI(url).path + (java.net.URI(url).query?.let { "?$it" } ?: "")
                    base.trimEnd('/') + pathPart
                } catch (_: Throwable) {
                    url
                }
            } else base.trimEnd('/') + path
            try {
                val doc = app.get(u, headers = hdr, timeout = 30).text
                if (doc.length > 2000 && "Verify you are human" !in doc && "just a moment" !in doc.lowercase()) {
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
        // <article> ... entry-title <a href> + img
        val articles = Regex(
            """<article[^>]*>[\s\S]*?</article>""",
            RegexOption.IGNORE_CASE
        ).findAll(html)
        for (art in articles) {
            val block = art.value
            val href = Regex(
                """class="[^"]*entry-title[^"]*"[^>]*>\s*<a[^>]+href=["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ).find(block)?.groupValues?.getOrNull(1)
                ?: Regex(
                    """<h2[^>]*class="[^"]*entry-title[^"]*"[^>]*>\s*<a[^>]+href=["']([^"']+)["']""",
                    RegexOption.IGNORE_CASE
                ).find(block)?.groupValues?.getOrNull(1)
                ?: Regex(
                    """<a[^>]+href=["'](https?://[^"']+)["'][^>]*rel=["']bookmark["']""",
                    RegexOption.IGNORE_CASE
                ).find(block)?.groupValues?.getOrNull(1)
                ?: continue

            val url = absUrl(href).substringBefore("?").trimEnd('/') + "/"
            if (!seen.add(url)) continue
            if ("/category/" in url || "/genre/" in url || "/tag/" in url || "/page/" in url) continue

            val title = Regex(
                """<a[^>]+href=["'][^"']*["'][^>]*title=["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ).find(block)?.groupValues?.getOrNull(1)
                ?: Regex(
                    """entry-title[^>]*>\s*<a[^>]*>([^<]+)</a>""",
                    RegexOption.IGNORE_CASE
                ).find(block)?.groupValues?.getOrNull(1)
                ?: url.substringAfterLast('/').replace('-', ' ')

            val poster = Regex(
                """<img[^>]+(?:data-src|src)=["']([^"']+)["']""",
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

        // fallback: any bookmark title links
        if (out.isEmpty()) {
            Regex(
                """<a[^>]+href=["'](https?://[^"']+)["'][^>]*title=["']([^"']+)["'][^>]*rel=["']bookmark["']""",
                RegexOption.IGNORE_CASE
            ).findAll(html).forEach { m ->
                val url = absUrl(m.groupValues[1]).substringBefore("?").trimEnd('/') + "/"
                if (!seen.add(url)) return@forEach
                if ("/category/" in url || "/genre/" in url) return@forEach
                out.add(
                    newMovieSearchResponse(m.groupValues[2].trim(), url, TvType.Movie)
                )
            }
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = absUrl(url).substringBefore("?").trimEnd('/') + "/"
        val html = fetch(pageUrl)

        val title = Regex(
            """property=["']og:title["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)
            ?: Regex(
                """<h1[^>]*class=["'][^"']*entry-title[^"']*["'][^>]*>([^<]+)</h1>""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)
            ?: pageUrl.trimEnd('/').substringAfterLast('/').replace('-', ' ')

        val poster = Regex(
            """property=["']og:image["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)
            ?: Regex(
                """<img[^>]+(?:data-src|src)=["'](https?://[^"']+)["'][^>]*(?:class|alt)=["'][^"']*""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)

        val plot = Regex(
            """property=["']og:description["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)
            ?: Regex(
                """<p><strong><a[^>]*>[^<]*</a></strong>[^<]*</p>""",
                RegexOption.IGNORE_CASE
            ).find(html)?.value?.let { Regex("""<[^>]+>""").replace(it, "").trim() }

        val year = Regex("""\((20\d{2}|19\d{2})\)""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex(
                """Release:\s*</strong>\s*<a[^>]*>\s*(20\d{2}|19\d{2})""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val tags = Regex(
            """itemprop=["']genre["'][^>]*>([^<]+)""",
            RegexOption.IGNORE_CASE
        ).findAll(html).map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.distinct().toList()

        // Watch links from server table — encode into data for loadLinks
        val watchLinks = extractWatchLinks(html)
        val dataPayload = if (watchLinks.isEmpty()) pageUrl
        else pageUrl + "|||" + watchLinks.joinToString("|||") { "\( {it.first}:: \){it.second}" }

        val isSeries = Regex(
            """season|series|complete|episode""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(title)

        // MLSBD often packs full season as one file → single episode entry
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

    /** Parse <tr class="tritem"> Watch hrefs (skip pure Download file pages when Watch exists). */
    private fun extractWatchLinks(html: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()
        Regex("""<tr class="tritem">([\s\S]*?)</tr>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { rowMatch ->
                val row = rowMatch.value
                val serverName = Regex(
                    """<td[^>]*>\s*([^<]+)\s*</td>""",
                    RegexOption.IGNORE_CASE
                ).find(row)?.groupValues?.getOrNull(1)?.trim() ?: "Server"

                // Prefer links titled Watch
                val watchHref = Regex(
                    """href=["']([^"']+)["'][^>]*>\s*Watch\s*<""",
                    RegexOption.IGNORE_CASE
                ).find(row)?.groupValues?.getOrNull(1)
                    ?: Regex(
                        """href=["']([^"']+)["'][^>]*>\s*Watch""",
                        RegexOption.IGNORE_CASE
                    ).find(row)?.groupValues?.getOrNull(1)

                val candidates = ArrayList<String>()
                if (watchHref != null) candidates.add(watchHref)
                // also embed-style links in row
                Regex("""href=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(row)
                    .forEach { candidates.add(it.groupValues[1]) }

                for (raw in candidates) {
                    val link = normalizeLink(raw) ?: continue
                    // skip pure download /f/ if we already have /e/ embed
                    val key = link.substringBefore("?").lowercase()
                    if (!seen.add(key)) continue
                    // skip ad / self links
                    if ("mlsbd" in key && "/watch" !in key && "embed" !in key) {
                        if (!listOf("ok.ru", "mixdrop", "dood", "do7go", "pkembed", "streamtape", "filemoon")
                                .any { it in key }
                        ) continue
                    }
                    if (listOf("highperformance", "effectivecpm", "facebook", "twitter", "t.me")
                            .any { it in key }
                    ) continue
                    out.add(serverName to link)
                    break // one primary per row
                }
            }

        // fallback embeds in page
        if (out.isEmpty()) {
            Regex(
                """(?:src|href)=["'](https?://[^"']*(?:embed|videoembed|/e/)[^"']+)["']""",
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
        // fix broken ttps://
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
        val preParsed = parts.drop(1).mapNotNull {
            val idx = it.indexOf("::")
            if (idx <= 0) null
            else it.substring(0, idx) to it.substring(idx + 2)
        }

        val links = if (preParsed.isNotEmpty()) preParsed
        else {
            val html = fetch(if (pageUrl.startsWith("http")) pageUrl else data)
            extractWatchLinks(html)
        }

        if (links.isEmpty() && pageUrl.startsWith("http")) {
            val html = fetch(pageUrl)
            extractWatchLinks(html).forEach { (name, link) ->
                resolveOne(name, link, subtitleCallback, callback)
            }
        }

        var found = false
        for ((name, link) in links) {
            if (resolveOne(name, link, subtitleCallback, callback)) found = true
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
            // Built-in extractors: MixDrop, Dood, OkRu, etc.
            loadExtractor(link, mainUrl, subtitleCallback) { ext ->
                ok = true
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$serverName · ${ext.name}",
                        url = ext.url,
                        type = ext.type
                    ) {
                        this.referer = ext.referer
                        this.quality = ext.quality
                        this.headers = ext.headers
                    }
                )
            }
        } catch (_: Throwable) {
        }

        // Direct m3u8/mp4 fallback
        if (!ok && (link.contains(".m3u8") || link.contains(".mp4"))) {
            ok = true
            callback(
                newExtractorLink(
                    source = name,
                    name = serverName,
                    url = link,
                    type = if (".m3u8" in link) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.P1080.value
                }
            )
        }
        return ok
    }
}
