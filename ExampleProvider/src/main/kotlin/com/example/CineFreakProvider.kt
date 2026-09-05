@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class CineFreakProvider : MainAPI() {
    override var mainUrl = "https://cinefreak.net"
    override var name = "CineFreak"
    override val hasMainPage = true
    override var lang = "bn"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val ua =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private fun hdr(referer: String = mainUrl + "/"): Map<String, String> {
        return mapOf(
            "User-Agent" to ua,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9,bn;q=0.8",
            "Referer" to referer
        )
    }

    private fun abs(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var u = url.trim().replace("&amp;", "&").replace("\\/", "/")
        if (u.startsWith("//")) u = "https:" + u
        if (u.startsWith("http")) return u
        return mainUrl.trimEnd('/') + "/" + u.trimStart('/')
    }

    private fun cleanTitle(raw: String): String {
        var t = raw
            .replace(Regex("""(?i)\s*[|\-–].*$"""), "")
            .replace(Regex("""(?i)\s*\((?:WEB-?DL|BluRay|HDTC|HDRip)[^)]*\)"""), "")
            .replace(Regex("""(?i)\s*(480p|720p|1080p|2160p|4K).*$"""), "")
            .replace(Regex("""(?i)\s*Full Series.*$"""), "")
            .replace(Regex("""(?i)\s*Download.*$"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
        return t.ifBlank { raw.trim() }
    }

    private fun qualityFrom(text: String): Int {
        val t = text.lowercase()
        return when {
            "2160" in t || "4k" in t || "uhd" in t -> Qualities.P2160.value
            "1440" in t || "2k" in t -> Qualities.P1440.value
            "1080" in t -> Qualities.P1080.value
            "720" in t -> Qualities.P720.value
            "480" in t -> Qualities.P480.value
            "360" in t -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun yearFrom(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("""\((20\d{2}|19\d{2})\)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""\b(20\d{2})\b""").find(text)?.value?.toIntOrNull()
    }

    private fun isSeriesTitle(t: String): Boolean {
        val s = t.lowercase()
        return s.contains("season") || s.contains("series") || s.contains("episode") ||
            Regex("""\bs0?\d+\b""").containsMatchIn(s) || s.contains("web-series")
    }

    private fun pickImg(el: Element): String? {
        for (img in el.select("img")) {
            for (x in listOf(img.attr("src"), img.attr("data-src"), img.attr("data-lazy-src"))) {
                val u = abs(x) ?: continue
                if (u.startsWith("http") && !u.contains("data:image") && !u.endsWith(".svg")) return u
            }
        }
        return null
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        for (a in doc.select("a[href]")) {
            val href = abs(a.attr("abs:href").ifBlank { a.attr("href") }) ?: continue
            if (!href.contains("cinefreak.net")) continue
            if (href.contains("/category/") || href.contains("/tag/") || href.contains("/page/") ||
                href.contains("generate.php") || href.contains("/?")
            ) continue
            if (href.trimEnd('/') == mainUrl) continue
            if (!seen.add(href)) continue
            val titleRaw = a.text().trim().ifBlank {
                a.selectFirst("img")?.attr("alt")?.trim().orEmpty()
            }
            if (titleRaw.length < 3) continue
            val parent = a.parents().firstOrNull { it.selectFirst("img") != null } ?: a.parent()
            val poster = parent?.let { pickImg(it) }
            val year = yearFrom(titleRaw)
            val title = cleanTitle(titleRaw)
            if (isSeriesTitle(titleRaw)) {
                out += newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                }
            } else {
                out += newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                }
            }
        }
        return out.distinctBy { it.url }.take(40)
    }

    override val mainPage = mainPageOf(
        mainUrl + "/" to "Latest",
        mainUrl + "/category/movies/" to "Movies",
        mainUrl + "/web-series/" to "WEB-Series",
        mainUrl + "/category/dual-audio/" to "Dual Audio",
        mainUrl + "/category/hindi/" to "Hindi",
        mainUrl + "/category/bangla/" to "Bangla"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            page <= 1 -> request.data
            request.data.endsWith("/") -> request.data + "page/" + page + "/"
            else -> request.data + "/page/" + page + "/"
        }
        val doc = app.get(url, headers = hdr()).document
        return newHomePageResponse(request.name, parseCards(doc), true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        // JSON API if available
        try {
            val apiUrl = mainUrl + "/search-api.php?q=" + URLEncoder.encode(q, "UTF-8") + "&pg=1"
            val txt = app.get(apiUrl, headers = hdr()).text
            if (txt.trimStart().startsWith("[")) {
                // minimal parse without forced JSON lib dependency issues
            }
        } catch (_: Exception) {
        }
        val doc = app.get(
            mainUrl + "/?s=" + URLEncoder.encode(q, "UTF-8"),
            headers = hdr()
        ).document
        return parseCards(doc)
    }

    /**
     * One quality link for an episode:
     * label + generate.php URL (watch /x/ preferred)
     */
    data class QualLink(val label: String, val genUrl: String)

    /**
     * Parse ep-card blocks → per-episode quality links (UNIQUE per episode)
     */
    private fun parseEpisodeCards(html: String): List<Pair<Int, List<QualLink>>> {
        val out = ArrayList<Pair<Int, List<QualLink>>>()
        val cards = Regex(
            """<div class="ep-card">([\s\S]*?)(?=<div class="ep-card">|$)""",
            RegexOption.IGNORE_CASE
        ).findAll(html)

        for (card in cards) {
            val block = card.groupValues[1]
            val meta = Regex(
                """class="ep-meta"[^>]*>([\s\S]*?)</div>""",
                RegexOption.IGNORE_CASE
            ).find(block)?.groupValues?.getOrNull(1).orEmpty()
            val metaText = meta.replace(Regex("""<[^>]+>"""), " ")
            val epNum = Regex(
                """Episode\s*0*(\d+)""",
                RegexOption.IGNORE_CASE
            ).find(metaText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex(
                    """\bE0*(\d+)\b""",
                    RegexOption.IGNORE_CASE
                ).find(metaText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: continue

            val links = ArrayList<QualLink>()
            val seen = HashSet<String>()
            val aRegex = Regex(
                """href\s*=\s*["']([^"']*generate\.php\?id=[^"']+)["'][^>]*>([\s\S]*?)</a>""",
                RegexOption.IGNORE_CASE
            )
            for (m in aRegex.findAll(block)) {
                val href = abs(m.groupValues[1]) ?: continue
                val label = m.groupValues[2].replace(Regex("""<[^>]+>"""), "").trim()
                    .ifBlank { "Server" }
                // Prefer watch links (/x/) over file (/f/) when decoding
                val id = href.substringAfter("id=", "")
                val decoded = try {
                    String(Base64.decode(id, Base64.DEFAULT))
                } catch (_: Exception) {
                    ""
                }
                // Skip pure download /f/ if we already have /x/ same quality — keep both for now but mark
                val key = label.lowercase() + "|" + (if ("/x/" in decoded) "x" else "f")
                if (!seen.add(key + href.takeLast(12))) continue
                // Prefer /x/ watch paths: put them first later
                links.add(QualLink(label, href))
            }
            if (links.isNotEmpty()) {
                // Sort: /x/ (watch) first by decoding
                val sorted = links.sortedBy { q ->
                    val id = q.genUrl.substringAfter("id=", "")
                    val dec = try {
                        String(Base64.decode(id, Base64.DEFAULT))
                    } catch (_: Exception) {
                        ""
                    }
                    if ("/x/" in dec) 0 else 1
                }
                out.add(epNum to sorted)
            }
        }
        return out
    }

    /** Movie page: all generate links on page */
    private fun parseMovieLinks(html: String): List<QualLink> {
        val links = ArrayList<QualLink>()
        val seen = HashSet<String>()
        val aRegex = Regex(
            """href\s*=\s*["']([^"']*generate\.php\?id=[^"']+)["'][^>]*>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE
        )
        for (m in aRegex.findAll(html)) {
            val href = abs(m.groupValues[1]) ?: continue
            if (!seen.add(href)) continue
            val label = m.groupValues[2].replace(Regex("""<[^>]+>"""), "").trim()
                .ifBlank { "Server" }
            links.add(QualLink(label, href))
        }
        return links
    }

    /**
     * Encode episode sources into data string (unique per episode).
     * Format: LABEL``GENURL  separated by \n
     */
    private fun encodeData(links: List<QualLink>): String {
        return links.joinToString("\n") { it.label + "``" + it.genUrl }
    }

    private fun decodeData(data: String): List<QualLink> {
        val out = ArrayList<QualLink>()
        for (line in data.split("\n")) {
            val p = line.trim()
            if ("``" in p) {
                val label = p.substringBefore("``").trim()
                val url = p.substringAfter("``").trim()
                if (url.startsWith("http")) out.add(QualLink(label, url))
            } else if (p.contains("generate.php")) {
                out.add(QualLink("Server", p))
            }
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = hdr()).document
        val html = doc.html()
        val titleRaw = doc.selectFirst("h1.page-title, h1.entry-title, h1")?.text() ?: "Unknown"
        val title = cleanTitle(titleRaw)
        val poster = abs(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: pickImg(doc.body())
        val year = yearFrom(titleRaw)

        var plot: String? = null
        val tags = ArrayList<String>()
        val content = doc.selectFirst(".entry-content, .page-content, article")
        if (content != null) {
            val p = content.selectFirst("p")?.text()?.trim()
            if (p != null && p.length > 40) plot = p
        }

        val episodeCards = parseEpisodeCards(html)

        if (episodeCards.isNotEmpty()) {
            val episodes = episodeCards.map { (epNum, links) ->
                val data = encodeData(links)
                newEpisode(data) {
                    this.name = "Episode " + epNum
                    this.episode = epNum
                    this.data = data
                }
            }.sortedBy { it.episode }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }

        // Movie: single load with page generate links
        val movieLinks = parseMovieLinks(html)
        val data = if (movieLinks.isNotEmpty()) encodeData(movieLinks) else url
        return newMovieLoadResponse(title, url, TvType.Movie, data) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    /**
     * generate.php → cinecloud /x/{hex} (truncated) → extract R2 mkv from page/player embed
     */
    private suspend fun resolveGenerate(genUrl: String): String? {
        return try {
            val page = app.get(genUrl, headers = hdr(mainUrl + "/"))
            val html = page.text
            val finalUrl = page.url

            // location or link to cinecloud
            var cloud = Regex(
                """(https?://[^"'\s]*cinecloud[^"'\s]*/x/[a-zA-Z0-9]+)""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)

            if (cloud == null) {
                // decode id from genUrl
                val id = genUrl.substringAfter("id=", "").substringBefore("&")
                val decoded = try {
                    String(Base64.decode(id, Base64.DEFAULT))
                } catch (_: Exception) {
                    ""
                }
                if (decoded.contains("cinecloud") && "/x/" in decoded) {
                    // truncate to hex-only id after /x/
                    val m = Regex(
                        """(https?://[^/]+)/x/([a-f0-9]+)""",
                        RegexOption.IGNORE_CASE
                    ).find(decoded)
                    if (m != null) {
                        cloud = m.groupValues[1] + "/x/" + m.groupValues[2]
                    }
                }
            } else {
                // also truncate cloud URL if has extra suffix
                val m = Regex(
                    """(https?://[^/]+)/x/([a-f0-9]+)""",
                    RegexOption.IGNORE_CASE
                ).find(cloud)
                if (m != null) {
                    cloud = m.groupValues[1] + "/x/" + m.groupValues[2]
                }
            }

            if (cloud == null) return null

            val cHtml = app.get(cloud, headers = hdr(genUrl)).text

            // Direct R2 in page
            val r2 = Regex(
                """(https://pub-[a-z0-9]+\.r2\.dev/[^"'\s]+)""",
                RegexOption.IGNORE_CASE
            ).find(cHtml)?.groupValues?.getOrNull(1)
            if (r2 != null) {
                return try {
                    URLDecoder.decode(r2.replace("&amp;", "&"), "UTF-8")
                } catch (_: Exception) {
                    r2.replace("&amp;", "&")
                }
            }

            // player.yagaverse embed?id=R2URL
            val embed = Regex(
                """player\.yagaverse[^"']*[?&]id=([^&"']+)""",
                RegexOption.IGNORE_CASE
            ).find(cHtml)?.groupValues?.getOrNull(1)
            if (embed != null) {
                val decoded = try {
                    URLDecoder.decode(embed, "UTF-8")
                } catch (_: Exception) {
                    embed
                }
                if (decoded.startsWith("http")) return decoded
            }

            // any mkv/mp4
            val media = Regex(
                """(https://[^"'\s]+\.(?:mkv|mp4)[^"'\s]*)""",
                RegexOption.IGNORE_CASE
            ).find(cHtml)?.groupValues?.getOrNull(1)
            if (media != null) return media.replace("&amp;", "&")

            null
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var links = decodeData(data)

        // Fallback: old bug used page URL for all episodes
        if (links.isEmpty() && data.startsWith("http") && !data.contains("generate.php")) {
            try {
                val html = app.get(data, headers = hdr()).text
                val cards = parseEpisodeCards(html)
                if (cards.isNotEmpty()) {
                    // Cannot know which episode — use first only as last resort
                    links = cards.first().second
                } else {
                    links = parseMovieLinks(html)
                }
            } catch (_: Exception) {
            }
        }

        if (links.isEmpty()) return false

        var found = false
        val seen = HashSet<String>()

        for (q in links) {
            try {
                val direct = resolveGenerate(q.genUrl) ?: continue
                if (!seen.add(direct)) continue
                val quality = qualityFrom(q.label + " " + direct)
                callback(
                    ExtractorLink(
                        name,
                        q.label.ifBlank { "CineFreak" },
                        direct,
                        mainUrl + "/",
                        quality,
                        false
                    )
                )
                found = true
            } catch (_: Exception) {
            }
        }
        return found
    }
}
