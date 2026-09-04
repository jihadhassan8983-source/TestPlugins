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
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return base.trimEnd('/') + "/" + url.trimStart('/')
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
        val img = el.selectFirst("img") ?: return null
        val candidates = listOf(
            img.attr("src"),
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("data-original")
        )
        return candidates.mapNotNull { abs(it) }
            .firstOrNull { it.contains("http") && !it.contains("data:image") }
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        for (a in doc.select("a.movie-card[href], a[href*='-download/']")) {
            val href = abs(a.attr("abs:href").ifBlank { a.attr("href") }) ?: continue
            if (!href.contains("cinefreak.net")) continue
            if (href.contains("/category/") || href.contains("/tag/") || href.contains("/page/")) continue
            if (!href.contains("-download") && !href.matches(Regex("""https?://[^/]+/[^/]+/$"""))) continue
            if (!seen.add(href)) continue

            val titleRaw = a.selectFirst("h3.movie-card-title, h2, h3")?.text()
                ?: a.attr("aria-label").ifBlank { a.text() }
            if (titleRaw.isBlank()) continue
            val title = cleanTitle(titleRaw)
            val poster = pickImg(a)
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
        val doc = app.get("$mainUrl/?s=$q", headers = headers).document
        return parseCards(doc)
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

            val label = a.text().trim().ifBlank {
                a.parent()?.text()?.trim()?.take(40) ?: "Server"
            }
            val preferWatch = decoded.contains("/x/")
            if (!out.containsKey(decoded) || preferWatch) {
                out[decoded] = label.ifBlank { if (preferWatch) "Watch" else "Download" }
            }

            val trunc = Regex("""(https?://[^/]+/(?:x|f)/)([a-fA-F0-9]+)""").find(decoded)
            if (trunc != null) {
                val shortUrl = trunc.groupValues[1] + trunc.groupValues[2]
                if (!out.containsKey(shortUrl)) {
                    out[shortUrl] = label.ifBlank { "Watch" }
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
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: pickImg(doc.body())
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

        fun push(mediaUrl: String, label: String) {
            val u = mediaUrl.trim().replace("&amp;", "&")
            if (!u.startsWith("http")) return
            if (!pushed.add(u)) return
            val q = qualityFrom(label + " " + u)
            val isM3u8 = u.contains(".m3u8")
            callback(
                ExtractorLink(
                    name,
                    label.take(60).ifBlank { name },
                    u,
                    "https://new5.cinecloud.site/",
                    q,
                    isM3u8
                )
            )
            found = true
        }

        val ordered = servers.sortedByDescending { it.first.contains("/x/") }

        for ((cineUrl, label) in ordered) {
            try {
                val page = app.get(
                    cineUrl,
                    headers = headers + mapOf("Referer" to "$mainUrl/")
                ).text
                for (m in extractMediaUrls(page)) {
                    push(m, label.ifBlank { "CineCloud" })
                }
            } catch (_: Exception) {
            }
        }

        for (m in extractMediaUrls(html)) {
            push(m, "Direct")
        }

        return found
    }
}
