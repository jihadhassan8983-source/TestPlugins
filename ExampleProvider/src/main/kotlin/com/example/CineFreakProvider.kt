@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class CineFreakProvider : MainAPI() {
    override var mainUrl = "https://cinefreak.net"
    override var name = "CineFreak"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val ua =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/"
    )

    private fun abs(url: String?, base: String = mainUrl): String? {
        if (url.isNullOrBlank()) return null
        var u = url.trim().replace("&amp;", "&")
        if (u.startsWith("//")) u = "https:$u"
        if (u.startsWith("http")) return u
        return base.trimEnd('/') + "/" + u.trimStart('/')
    }

    private fun cleanTitle(raw: String): String {
        var t = raw
            .replace(Regex("""(?i)\s*[-–|].*?(download|watch|gdrive|esub|cinefreak).*"""), "")
            .replace(Regex("""(?i)\s*\[(full movie|all episodes|new episode weekly)\].*"""), "")
            .replace(Regex("""(?i)\s*\((?:WEB-DL|BluRay|HDTC|HDRip)[^)]*\)"""), "")
            .replace(Regex("""(?i)\s*(WEB-DL|BluRay|HEVC|ESub|GDrive|480p|720p|1080p|2160p|4K).*$"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
        if (t.length < 3) {
            t = raw.substringBefore("Download").substringBefore("Watch").trim()
        }
        return t.ifBlank { raw.trim() }
    }

    private fun isSeriesTitle(t: String): Boolean {
        val s = t.lowercase()
        return s.contains("season") || s.contains("series") || s.contains("episode") ||
            s.contains("web series") || Regex("""\bs\d+\b""").containsMatchIn(s)
    }

    private fun qualityFrom(text: String): Int {
        val t = text.lowercase()
        return when {
            "2160" in t || "4k" in t -> Qualities.P1080.value
            "1080" in t -> Qualities.P1080.value
            "720" in t -> Qualities.P720.value
            "480" in t -> Qualities.P480.value
            "360" in t -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun pickImg(el: Element): String? {
        for (img in el.select("img")) {
            val candidates = listOf(
                img.attr("src"),
                img.attr("data-src"),
                img.attr("data-lazy-src"),
                img.attr("data-original"),
                img.attr("data-srcset").substringBefore(" ")
            )
            val url = candidates.mapNotNull { abs(it) }
                .firstOrNull {
                    it.contains("http") &&
                        !it.contains("data:image") &&
                        !it.endsWith(".svg") &&
                        !it.contains("logo")
                }
            if (url != null) return url
        }
        // style background-image
        val style = el.attr("style") + el.select("[style]").joinToString("") { it.attr("style") }
        val bg = Regex("""url\(['"]?([^'")]+)['"]?\)""").find(style)?.groupValues?.getOrNull(1)
        return abs(bg)
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        fun add(href: String, titleRaw: String, poster: String?) {
            if (!href.contains("cinefreak.net")) return
            if (href.contains("/category/") || href.contains("/tag/") || href.contains("/page/")) return
            if (!href.contains("-download")) return
            if (!seen.add(href)) return
            if (titleRaw.isBlank()) return
            val title = cleanTitle(titleRaw)
            val series = isSeriesTitle(titleRaw)
            out += if (series) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }

        // 1) Normal movie cards (have posters)
        for (a in doc.select("a.movie-card[href]")) {
            val href = abs(a.attr("abs:href").ifBlank { a.attr("href") }) ?: continue
            val titleRaw = a.selectFirst("h3.movie-card-title, h2, h3")?.text()
                ?: a.attr("aria-label").ifBlank { a.text() }
            add(href, titleRaw, pickImg(a))
        }

        // 2) Hero slider slides (img is sibling, not inside the title <a>)
        for (slide in doc.select(".cine-slide, .swiper-slide")) {
            val link = slide.selectFirst("h2.cine-slide-title a[href], a[href*='-download/']")
                ?: continue
            val href = abs(link.attr("abs:href").ifBlank { link.attr("href") }) ?: continue
            val titleRaw = link.text().ifBlank {
                slide.selectFirst("img")?.attr("alt") ?: ""
            }
            val poster = pickImg(slide)
            add(href, titleRaw, poster)
        }

        return out
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/web-series/" to "WEB-Series",
        "$mainUrl/hindi-movies/" to "Hindi Movies",
        "$mainUrl/hindi-dubbed-movies/" to "Hindi Dubbed",
        "$mainUrl/english-movies/" to "English Movies",
        "$mainUrl/dual-audio/" to "Dual Audio",
        "$mainUrl/bangla-movies/" to "Bangla Movies",
        "$mainUrl/bangla-dubbed/" to "Bangla Dubbed",
        "$mainUrl/horror/" to "Horror"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            request.data
        } else if (request.data.endsWith("/")) {
            "${request.data}page/$page/"
        } else {
            "$mainUrl/page/$page/"
        }
        val doc = app.get(url, headers = headers).document
        val list = parseCards(doc)
        return newHomePageResponse(request.name, list, list.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().replace(" ", "+")
        // site uses both ?s= and fast-search/?q=
        var doc = app.get("$mainUrl/?s=$q", headers = headers).document
        var list = parseCards(doc)
        if (list.isEmpty()) {
            doc = app.get("$mainUrl/fast-search/?q=$q", headers = headers).document
            list = parseCards(doc)
        }
        return list
    }

    private fun decodeGenerateLinks(doc: Document): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()
        for (a in doc.select("a[href*='generate.php?id=']")) {
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            val id = Regex("""[?&]id=([A-Za-z0-9+/=]+)""").find(href)?.groupValues?.getOrNull(1)
                ?: continue
            val decoded = try {
                String(Base64.decode(id, Base64.DEFAULT))
            } catch (_: Exception) {
                continue
            }
            if (!decoded.startsWith("http")) continue

            // Label from nearby text (quality)
            val nearby = (a.text() + " " + (a.parent()?.text() ?: "")).trim()
            val label = when {
                nearby.contains("1080", true) -> "1080p"
                nearby.contains("720", true) -> "720p"
                nearby.contains("480", true) -> "480p"
                nearby.contains("2160", true) || nearby.contains("4K", true) -> "4K"
                else -> a.text().trim().ifBlank { "Watch" }
            }

            val preferWatch = decoded.contains("/x/")
            if (!out.containsKey(decoded) || preferWatch) {
                out[decoded] = label
            }

            val trunc = Regex("""(https?://[^/]+/(?:x|f)/)([a-fA-F0-9]+)""").find(decoded)
            if (trunc != null) {
                val shortUrl = trunc.groupValues[1] + trunc.groupValues[2]
                if (!out.containsKey(shortUrl)) {
                    out[shortUrl] = label
                }
            }
        }
        return out.map { it.key to it.value }
    }

    private fun extractMediaUrls(html: String): List<String> {
        val found = LinkedHashSet<String>()

        Regex("""player\.yagaverse\.net/embed2/\?id=([^"'&\s]+)""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { m ->
                var u = m.groupValues[1]
                repeat(3) {
                    try {
                        u = java.net.URLDecoder.decode(u, "UTF-8")
                    } catch (_: Exception) {
                    }
                }
                if (u.startsWith("http")) found.add(u)
            }

        Regex(
            """https?://[^\s"'<>\\]+(?:r2\.dev|workers\.dev)[^\s"'<>\\]*""",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEach {
            found.add(it.value.replace("&amp;", "&"))
        }

        Regex(
            """https?://[^\s"'<>\\]+\.(?:mp4|mkv|m3u8)[^\s"'<>\\]*""",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEach {
            found.add(it.value.replace("&amp;", "&"))
        }

        return found.toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val titleRaw = doc.selectFirst("h1.entry-title, h1, title")?.text() ?: "Unknown"
        val title = cleanTitle(titleRaw)
        val poster = abs(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: pickImg(doc.selectFirst(".entry-content, article, body") ?: doc.body())
        val plot = doc.selectFirst(".entry-content p, .post-content p, article p")?.text()
        val year = Regex("""\((19|20)\d{2}\)""").find(titleRaw)?.value
            ?.trim('(', ')')?.toIntOrNull()

        val series = isSeriesTitle(titleRaw)

        if (series) {
            val episodes = ArrayList<Episode>()
            val boxes = doc.select("[id^=single-dl]")
            if (boxes.isNotEmpty()) {
                boxes.forEachIndexed { idx, box ->
                    val epLinks = box.select("a[href*='generate.php?id=']")
                    if (epLinks.isEmpty()) return@forEachIndexed
                    val epName = box.previousElementSibling()?.text()?.take(60)
                        ?: "Episode ${idx + 1}"
                    episodes += newEpisode(url) {
                        this.name = epName
                        this.episode = idx + 1
                        this.data = url
                    }
                }
            }
            if (episodes.isEmpty()) {
                episodes += newEpisode(url) {
                    this.name = "All Episodes"
                    this.episode = 1
                    this.data = url
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data.substringBefore("|||").ifBlank { data }
        val resp = app.get(pageUrl, headers = headers)
        val doc = resp.document
        val html = resp.text
        val servers = decodeGenerateLinks(doc)

        var found = false
        val pushed = HashSet<String>()
        val pending = ArrayList<Triple<String, String, Int>>() // url, label, quality

        fun collect(mediaUrl: String, label: String) {
            val u = mediaUrl.trim().replace("&amp;", "&")
            if (!u.startsWith("http")) return
            if (!pushed.add(u)) return
            val q = qualityFrom(label + " " + u)
            pending.add(Triple(u, label, q))
        }

        // Prefer /x/ watch pages
        val ordered = servers.sortedByDescending { it.first.contains("/x/") }

        for ((cineUrl, label) in ordered) {
            try {
                val page = app.get(
                    cineUrl,
                    headers = headers + mapOf("Referer" to "$mainUrl/")
                ).text
                for (m in extractMediaUrls(page)) {
                    collect(m, label.ifBlank { "CineCloud" })
                }
            } catch (_: Exception) {
            }
        }

        for (m in extractMediaUrls(html)) {
            collect(m, "Direct")
        }

        // Sort: prefer 720p, then 480p, then 1080p (many phones fail on 1080 MKV)
        // Also prefer .mp4 over .mkv when same quality
        val sorted = pending.sortedWith(
            compareBy<Triple<String, String, Int>> {
                when (it.third) {
                    Qualities.P720.value -> 0
                    Qualities.P480.value -> 1
                    Qualities.P360.value -> 2
                    Qualities.P1080.value -> 3
                    else -> 4
                }
            }.thenBy {
                when {
                    it.first.contains(".mp4", true) -> 0
                    it.first.contains(".m3u8", true) -> 1
                    it.first.contains(".mkv", true) -> 2
                    else -> 3
                }
            }
        )

        for ((u, label, q) in sorted) {
            val isM3u8 = u.contains(".m3u8")
            val nameLabel = buildString {
                append(label.take(40).ifBlank { "CineFreak" })
                when (q) {
                    Qualities.P1080.value -> append(" • 1080p")
                    Qualities.P720.value -> append(" • 720p")
                    Qualities.P480.value -> append(" • 480p")
                }
                if (u.contains(".mkv", true)) append(" • MKV")
                if (u.contains(".mp4", true)) append(" • MP4")
            }
            callback(
                ExtractorLink(
                    name,
                    nameLabel,
                    u,
                    "https://new5.cinecloud.site/",
                    q,
                    isM3u8
                )
            )
            found = true
        }

        return found
    }
}
