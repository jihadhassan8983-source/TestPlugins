@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class AnimeDriveProvider : MainAPI() {
    override var mainUrl = "https://animedrive.me"
    override var name = "AnimeDrive"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val ua =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,hi;q=0.8",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/category/hindi-anime-download/" to "Hindi Anime",
        "$mainUrl/category/on-going/" to "On Going",
        "$mainUrl/category/action/" to "Action",
        "$mainUrl/category/school/" to "School",
        "$mainUrl/category/romance/" to "Romance",
        "$mainUrl/category/shounen/" to "Shounen",
        "$mainUrl/category/isekai/" to "Isekai",
        "$mainUrl/category/1080p-anime-download/" to "1080p"
    )

    /** "Mob Psycho ... Hindi, English, Japanese (Multi Audio) WEB-DL Episodes Download" → "Mob Psycho 100 Season 3" */
    private fun cleanTitle(raw: String): String {
        var t = raw.trim()
        t = t.replace(Regex("""(?i)\s*[-|]?\s*Episodes?\s*Download.*$"""), "")
        t = t.replace(Regex("""(?i)\s*Download.*$"""), "")
        t = t.replace(Regex("""(?i)\s*\(Multi Audio\)\s*"""), " ")
        t = t.replace(
            Regex("""(?i)\s*[-,]?\s*(Hindi|English|Japanese|Tamil|Telugu|Kannada|Malayalam|Urdu)(\s*,\s*(Hindi|English|Japanese|Tamil|Telugu|Kannada|Malayalam|Urdu))*"""),
            " "
        )
        t = t.replace(Regex("""(?i)\s*(WEB-?DL|BluRay|HDRip|WEB)\s*"""), " ")
        t = t.replace(Regex("""\s+"""), " ").trim()
        t = t.trim(' ', '-', ',', '|')
        return if (t.length >= 3) t else raw.trim()
    }

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return base.trimEnd('/') + "/page/$page/"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        val doc = app.get(url, headers = headers).document
        val home = doc.select(
            "#content article, main article, .ast-row article, article.ast-article-post, article.post"
        ).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, home, home.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$q", headers = headers).document
        return doc.select("article, .ast-row article, article.post")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("h2 a[href], h3 a[href], .entry-title a[href]")
            ?: this.selectFirst("a[href]")
            ?: return null
        var href = a.attr("abs:href")
        if (href.isBlank()) href = a.attr("href")
        href = href.substringBefore("#").substringBefore("?").trimEnd('/') + "/"
        if (!href.contains("animedrive")) return null
        if (href.contains("/category/") || href.contains("/tag/") || href.contains("/author/")) return null

        var title = a.text().trim()
        if (title.isBlank()) title = a.attr("title").trim()
        if (title.isBlank()) title = a.attr("aria-label").substringAfter("Read:").trim()
        if (title.isBlank()) {
            title = this.selectFirst("img")?.attr("alt")?.trim().orEmpty()
        }
        if (title.length < 3) return null
        title = cleanTitle(title)

        var poster: String? = null
        val img = this.selectFirst("img")
        if (img != null) {
            poster = img.attr("abs:src")
            if (poster.isNullOrBlank()) poster = img.attr("src")
            if (poster.isNullOrBlank()) poster = img.attr("data-src")
            if (poster.isNullOrBlank()) poster = img.attr("data-lazy-src")
        }

        val type = if (title.contains("Movie", true)) TvType.AnimeMovie else TvType.Anime
        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = url.substringBefore("#").substringBefore("?").trimEnd('/') + "/"
        val document = app.get(cleanUrl, headers = headers).document

        var title = document.selectFirst("h1.entry-title, h1")?.text()?.trim().orEmpty()
        if (title.isBlank()) {
            title = document.selectFirst("title")?.text()?.substringBefore("|")?.trim().orEmpty()
        }
        title = cleanTitle(title)

        var poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        if (poster.isNullOrBlank()) {
            poster = document.selectFirst(".entry-content img, article img, img.wp-post-image")
                ?.attr("abs:src")
        }

        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst(".entry-content p")?.text()

        val year = Regex("(20\\d{2}|19\\d{2})").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val linksPageUrl = document.select("a[href*=link.animedrive.me]")
            .map {
                var h = it.attr("abs:href")
                if (h.isBlank()) h = it.attr("href")
                h
            }
            .firstOrNull { it.contains("link.animedrive.me") && !it.contains("/dl/") }
            ?: throw ErrorLoadingException("Download links page not found")

        val linksDoc = app.get(linksPageUrl, headers = headers).document
        val episodes = ArrayList<Episode>()

        val epNames = linksDoc.select("span.adc-epname")
        if (epNames.isNotEmpty()) {
            epNames.forEachIndexed { index, epNameEl ->
                val epTitle = epNameEl.text().trim()
                if (epTitle.isBlank()) return@forEachIndexed
                val epNum = Regex("(\\d+)").find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: (index + 1)

                var container: Element? = epNameEl.parent()
                var buttons = emptyList<Element>()
                var depth = 0
                while (container != null && depth < 8) {
                    val btns = container.select("a[href*=/dl/]")
                    if (btns.isNotEmpty()) {
                        buttons = btns
                        break
                    }
                    container = container.parent()
                    depth++
                }

                val sources = ArrayList<Map<String, String>>()
                for (btn in buttons) {
                    var href = btn.attr("abs:href")
                    if (href.isBlank()) href = btn.attr("href")
                    if (href.isBlank() || !href.contains("/dl/")) continue
                    val qualityText = btn.text().trim().ifBlank { "Link" }
                    val hoster = when {
                        qualityText.contains("Hub", true) || href.contains("hubcloud") -> "HubCloud"
                        qualityText.contains("GD", true) || qualityText.contains("Flix", true) -> "GDFlix"
                        else -> "Server"
                    }
                    sources.add(
                        mapOf(
                            "url" to href,
                            "name" to "$hoster • $qualityText",
                            "quality" to qualityText
                        )
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
        }

        if (episodes.isEmpty()) {
            val sources = ArrayList<Map<String, String>>()
            for (btn in linksDoc.select("a[href*=/dl/]")) {
                var href = btn.attr("abs:href")
                if (href.isBlank()) href = btn.attr("href")
                if (!href.contains("/dl/")) continue
                val qualityText = btn.text().trim().ifBlank { "Link" }
                sources.add(
                    mapOf("url" to href, "name" to qualityText, "quality" to qualityText)
                )
            }
            if (sources.isNotEmpty()) {
                episodes.add(
                    newEpisode(sources.toJson()) {
                        this.name = "Episode 1"
                        this.episode = 1
                    }
                )
            }
        }

        if (episodes.isEmpty()) throw ErrorLoadingException("No episodes found")

        val isMovie =
            title.contains("Movie", true) || cleanUrl.contains("movie", true) || episodes.size <= 1

        return if (isMovie) {
            newMovieLoadResponse(title, cleanUrl, TvType.AnimeMovie, episodes.first().data ?: "") {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            newAnimeLoadResponse(title, cleanUrl, TvType.Anime) {
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

        val sources: List<Map<String, String>> = try {
            parseJson(data)
        } catch (_: Exception) {
            listOf(mapOf("url" to data, "name" to "AnimeDrive", "quality" to ""))
        }

        var found = false
        val added = HashSet<String>()

        for (src in sources) {
            val rawUrl = src["url"] ?: continue
            val qualityText = src["quality"] ?: src["name"] ?: ""
            val q = qualityFromText(qualityText)
            val realUrl = resolveDlLink(rawUrl) ?: continue
            val low = realUrl.lowercase()

            when {
                "hubcloud" in low -> {
                    if (extractHubCloud(realUrl, qualityText, q, callback, added)) found = true
                }
                "gdflix" in low || "gdlink" in low || "gdtot" in low -> {
                    if (extractGdFlix(realUrl, qualityText, q, callback, subtitleCallback, added)) {
                        found = true
                    }
                }
                "pixeldrain" in low -> {
                    val id = Regex("""/(?:u|file)/([A-Za-z0-9]+)""")
                        .find(realUrl)?.groupValues?.getOrNull(1)
                    if (id != null) {
                        val direct = "https://pixeldrain.com/api/file/$id"
                        if (added.add(direct)) {
                            callback.invoke(
                                ExtractorLink(name, "Pixeldrain • $qualityText", direct, realUrl, q, false)
                            )
                            found = true
                        }
                    }
                }
                else -> {
                    try {
                        if (loadExtractor(realUrl, mainUrl, subtitleCallback, callback)) found = true
                    } catch (_: Exception) {
                    }
                }
            }
        }
        return found
    }

    /**
     * HubCloud chain:
     * /drive/ID → #download (hubcloud.php) → [Server 10Gbps]
     * → gpdl → workers → dl.php?link=https://video-downloads.googleusercontent.com/...
     * That googleusercontent URL is the real video/mkv.
     */
    private suspend fun extractHubCloud(
        url: String,
        qualityText: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit,
        added: HashSet<String>
    ): Boolean {
        var ok = false
        try {
            val driveDoc = app.get(url, headers = headers).document
            var gen = driveDoc.select("#download").attr("href")
            if (gen.isBlank()) {
                gen = driveDoc.selectFirst("a#download, a[href*=hubcloud.php]")?.attr("href").orEmpty()
            }
            if (gen.isBlank()) gen = url
            if (!gen.startsWith("http")) {
                val base = Regex("https?://[^/]+").find(url)?.value ?: "https://hubcloud.cx"
                gen = base.trimEnd('/') + "/" + gen.trimStart('/')
            }

            val page = app.get(
                gen,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to url,
                    "Accept" to "text/html,*/*"
                )
            ).document

            val size = page.selectFirst("i#size")?.text().orEmpty()
            val fileType = page.select("li.list-group-item")
                .firstOrNull { it.text().contains("File Type", true) }
                ?.text().orEmpty()

            for (a in page.select("div.card-body a.btn, div.card-body h2 a, a.btn")) {
                var link = a.attr("abs:href")
                if (link.isBlank()) link = a.attr("href")
                val text = a.text()
                if (!link.startsWith("http")) continue
                if (link.contains("t.me") || link.contains("telegram") ||
                    link.contains("tinyurl") || link.contains("google.com/search") ||
                    link.contains("one.one.one")
                ) continue

                val label = buildString {
                    append("HubCloud")
                    if (text.isNotBlank()) append(" • ").append(text.replace("Download", "").trim())
                    if (qualityText.isNotBlank()) append(" • ").append(qualityText)
                    if (size.isNotBlank()) append(" • ").append(size)
                }

                // 10Gbps / gpdl → resolve to googleusercontent mkv
                if (text.contains("10Gbps", true) || text.contains("Server", true) ||
                    link.contains("gpdl")
                ) {
                    val video = resolveHubCloudVideo(link, gen)
                    if (video != null && added.add(video)) {
                        callback.invoke(
                            ExtractorLink(name, label, video, gen, quality, false)
                        )
                        ok = true
                    }
                    continue
                }

                // Pixeldrain
                if (text.contains("Pixel", true) || link.contains("pixeldrain")) {
                    val id = Regex("""/(?:u|file)/([A-Za-z0-9]+)""")
                        .find(link)?.groupValues?.getOrNull(1)
                    val direct = if (id != null) "https://pixeldrain.com/api/file/$id" else link
                    if (added.add(direct)) {
                        callback.invoke(
                            ExtractorLink(name, label, direct, gen, quality, false)
                        )
                        ok = true
                    }
                    continue
                }

                // FSL — only if NOT zip (matroska/mp4 ok; zip cannot play)
                if (text.contains("FSL", true) || link.contains("r2.cloudflarestorage")) {
                    if (fileType.contains("zip", true)) continue
                    if (added.add(link)) {
                        callback.invoke(
                            ExtractorLink(name, label, link, gen, quality, false)
                        )
                        ok = true
                    }
                }
            }
        } catch (_: Exception) {
        }
        return ok
    }

    /** Follow gpdl redirects and pull video-downloads.googleusercontent.com URL */
    private suspend fun resolveHubCloudVideo(gpdlUrl: String, referer: String): String? {
        return try {
            val resp = app.get(
                gpdlUrl,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to referer,
                    "Accept" to "*/*"
                )
            )
            val finalUrl = resp.url
            val body = resp.text

            // dl.php?link=REAL_VIDEO
            val fromQuery = Regex("""[?&]link=([^&]+)""").find(finalUrl)?.groupValues?.get(1)
            if (!fromQuery.isNullOrBlank()) {
                val decoded = URLDecoder.decode(fromQuery, "UTF-8")
                if (decoded.startsWith("http")) return decoded
            }

            // body / meta
            val guc = Regex(
                """https://video-downloads\.googleusercontent\.com[^\"'\s<>]+"""
            ).find(body)?.value
            if (!guc.isNullOrBlank()) return guc

            val anyVideo = Regex(
                """https?://[^\"'\s<>]+\.(mkv|mp4|m3u8)[^\"'\s<>]*"""
            ).find(body)?.value
            if (!anyVideo.isNullOrBlank()) return anyVideo

            // workers final if already media
            if (finalUrl.contains("googleusercontent") ||
                finalUrl.contains(".mkv") || finalUrl.contains(".mp4")
            ) {
                return finalUrl
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun extractGdFlix(
        url: String,
        qualityText: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
        added: HashSet<String>
    ): Boolean {
        try {
            if (loadExtractor(url, mainUrl, subtitleCallback, callback)) return true
        } catch (_: Exception) {
        }

        val id = url.substringAfterLast("/").substringBefore("?")
        val mirrors = listOf(url, "https://new6.gdflix.dad/file/$id", "https://gdflix.dad/file/$id")
        var ok = false
        for (pageUrl in mirrors) {
            try {
                val doc = app.get(pageUrl, headers = headers).document
                for (a in doc.select("div.text-center > a, a.btn")) {
                    val text = a.text()
                    val href = a.attr("abs:href").ifBlank { a.attr("href") }
                    if (!href.startsWith("http")) continue
                    if (text.contains("Cloud Download", true) || text.contains("Direct", true)) {
                        if (href.contains("/file/")) continue
                        if (added.add(href)) {
                            callback.invoke(
                                ExtractorLink(
                                    name, "GDFlix Cloud • $qualityText", href, pageUrl, quality, false
                                )
                            )
                            ok = true
                        }
                    }
                    if (text.contains("Instant", true)) {
                        try {
                            val loc = app.get(href, headers = headers).url
                            val real = Regex("[?&]url=([^&]+)").find(loc)?.groupValues?.get(1)
                                ?.let { URLDecoder.decode(it, "UTF-8") } ?: loc
                            if (real.startsWith("http") && !real.contains("gdflix") && added.add(real)) {
                                callback.invoke(
                                    ExtractorLink(
                                        name, "GDFlix Instant • $qualityText", real, pageUrl, quality, false
                                    )
                                )
                                ok = true
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
                if (ok) break
            } catch (_: Exception) {
            }
        }
        return ok
    }

    private fun qualityFromText(text: String): Int {
        return when {
            text.contains("1080", true) -> Qualities.P1080.value
            text.contains("720", true) -> Qualities.P720.value
            text.contains("480", true) -> Qualities.P480.value
            text.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun resolveDlLink(dlUrl: String): String? {
        return try {
            if (!dlUrl.contains("/dl/")) return dlUrl
            var encoded = dlUrl.substringAfter("/dl/")
            encoded = encoded.substringBefore("?").substringBefore("&").trim()
            if (encoded.isBlank()) return null
            val pad = (4 - encoded.length % 4) % 4
            val decoded = String(Base64.decode(encoded + "=".repeat(pad), Base64.DEFAULT), Charsets.UTF_8)
            val real = decoded.reversed()
            if (real.startsWith("http")) real else null
        } catch (_: Exception) {
            null
        }
    }
}
