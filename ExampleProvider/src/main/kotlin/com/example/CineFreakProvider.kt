@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Document
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
            .replace(Regex("""(?i),\s*(480p|720p|1080p|2160p|4K).*$"""), "")
            .replace(Regex("""(?i)\s*(480p|720p|1080p|2160p|4K).*$"""), "")
            .replace(Regex("""(?i)\s*Full (Movie|Series).*$"""), "")
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
        return s.contains("season") || s.contains("series") ||
            s.contains("episode") || Regex("""\bs0?\d+\b""").containsMatchIn(s)
    }

    /** Homepage / category: <a class="movie-card" aria-label="TITLE"> <img ...> */
    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        for (a in doc.select("a.movie-card[href]")) {
            val href = abs(a.attr("abs:href").ifBlank { a.attr("href") }) ?: continue
            if (!href.contains("cinefreak.net")) continue
            if (href.contains("/category/") || href.contains("/tag/") || href.contains("/page/")) continue
            if (!seen.add(href)) continue

            var titleRaw = a.attr("aria-label").trim()
            if (titleRaw.isBlank()) titleRaw = a.attr("title").trim()
            if (titleRaw.isBlank()) {
                titleRaw = a.selectFirst("img")?.attr("alt")?.trim().orEmpty()
            }
            if (titleRaw.length < 2) continue

            val title = cleanTitle(titleRaw)
            val img = a.selectFirst("img")
            var poster: String? = null
            if (img != null) {
                poster = abs(
                    img.attr("data-src").ifBlank {
                        img.attr("data-lazy-src").ifBlank { img.attr("src") }
                    }
                )
            }
            val year = yearFrom(titleRaw)

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

        // Fallback if theme changes
        if (out.isEmpty()) {
            for (a in doc.select("article a[href], .post a[href]")) {
                val href = abs(a.attr("abs:href").ifBlank { a.attr("href") }) ?: continue
                if (!href.contains("cinefreak.net") || href.contains("/category/")) continue
                if (!seen.add(href)) continue
                val titleRaw = a.text().trim()
                if (titleRaw.length < 3) continue
                val poster = a.selectFirst("img")?.let {
                    abs(it.attr("data-src").ifBlank { it.attr("src") })
                }
                out += newMovieSearchResponse(cleanTitle(titleRaw), href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = yearFrom(titleRaw)
                }
            }
        }
        return out
    }

    override val mainPage = mainPageOf(
        mainUrl + "/" to "Latest",
        mainUrl + "/dual-audio/" to "Dual Audio",
        mainUrl + "/category/movies/" to "Movies",
        mainUrl + "/web-series/" to "WEB-Series",
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
        val list = parseCards(doc)
        return newHomePageResponse(request.name, list, list.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val doc = app.get(
            mainUrl + "/?s=" + URLEncoder.encode(q, "UTF-8"),
            headers = hdr()
        ).document
        return parseCards(doc)
    }

    data class QualLink(val label: String, val genUrl: String)

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

    /** Series: each ep-card has unique generate.php links */
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
            val epNum = Regex("""Episode\s*0*(\d+)""", RegexOption.IGNORE_CASE)
                .find(metaText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""\bE0*(\d+)\b""", RegexOption.IGNORE_CASE)
                    .find(metaText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: continue

            val links = collectGenerateLinks(block)
            if (links.isNotEmpty()) out.add(epNum to links)
        }
        return out
    }

    private fun collectGenerateLinks(html: String): List<QualLink> {
        val links = ArrayList<QualLink>()
        val seen = HashSet<String>()
        val aRegex = Regex(
            """href\s*=\s*["']([^"']*generate\.php\?id=[^"']+)["'][^>]*>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE
        )
        for (m in aRegex.findAll(html)) {
            val href = abs(m.groupValues[1]) ?: continue
            if (!seen.add(href)) continue
            var label = m.groupValues[2].replace(Regex("""<[^>]+>"""), "").trim()
            if (label.isBlank() || label.equals("Download Links", true) ||
                label.equals("Watch Online", true)
            ) {
                // Infer quality from decoded URL path or nearby text
                val id = href.substringAfter("id=", "")
                val dec = try {
                    String(Base64.decode(id, Base64.DEFAULT))
                } catch (_: Exception) {
                    ""
                }
                label = when {
                    label.contains("Watch", true) -> "Watch"
                    label.contains("Download", true) -> "Download"
                    else -> label.ifBlank { "Server" }
                }
                // quality from parent context - try match in surrounding via href only
                if ("/x/" in dec) label = "Watch " + label
            }
            links.add(QualLink(label, href))
        }

        // Prefer /x/ watch links first
        return links.sortedBy { q ->
            val id = q.genUrl.substringAfter("id=", "")
            val dec = try {
                String(Base64.decode(id, Base64.DEFAULT))
            } catch (_: Exception) {
                ""
            }
            when {
                "/x/" in dec -> 0
                "/f/" in dec -> 1
                else -> 2
            }
        }
    }

    /** Better labels: look at quality-grid buttons */
    private fun collectGenerateLinksWithQuality(html: String): List<QualLink> {
        val links = ArrayList<QualLink>()
        val seen = HashSet<String>()
        val aRegex = Regex(
            """href\s*=\s*["']([^"']*generate\.php\?id=[^"']+)["'][^>]*>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE
        )
        for (m in aRegex.findAll(html)) {
            val href = abs(m.groupValues[1]) ?: continue
            if (!seen.add(href)) continue
            var label = m.groupValues[2].replace(Regex("""<[^>]+>"""), "").trim()
            val id = href.substringAfter("id=", "")
            val dec = try {
                String(Base64.decode(id, Base64.DEFAULT))
            } catch (_: Exception) {
                ""
            }
            // Use label if it has quality info
            if (!Regex("""(?i)480|720|1080|2160|4k""").containsMatchIn(label)) {
                val kind = if ("/x/" in dec) "Watch" else "Download"
                label = kind
            }
            links.add(QualLink(label, href))
        }
        return links.sortedBy { q ->
            val id = q.genUrl.substringAfter("id=", "")
            val dec = try {
                String(Base64.decode(id, Base64.DEFAULT))
            } catch (_: Exception) {
                ""
            }
            if ("/x/" in dec) 0 else 1
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = hdr()).document
        val html = doc.html()

        val titleRaw = doc.selectFirst("h1.page-title, h1.entry-title, h1")?.text() ?: "Unknown"
        val title = cleanTitle(titleRaw)
        val year = yearFrom(titleRaw)

        // Poster: prefer TMDB on page, then og:image if not wp-admin placeholder
        var poster: String? = null
        for (img in doc.select("img[src*=tmdb], img[data-src*=tmdb], img[src*=image.tmdb]")) {
            poster = abs(img.attr("data-src").ifBlank { img.attr("src") })
            if (poster != null) break
        }
        if (poster == null) {
            val og = abs(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            if (og != null && !og.contains("wp-admin") && !og.contains("default")) poster = og
        }
        if (poster == null) {
            poster = doc.selectFirst(".poster img, .movie-poster img, article img")?.let {
                abs(it.attr("data-src").ifBlank { it.attr("src") })
            }
        }

        var plot: String? = null
        for (p in doc.select(".entry-content p, .page-content p, article p")) {
            val t = p.text().trim()
            if (t.length > 50 && !t.contains("Download", true) && !t.contains("480p", true)) {
                plot = t
                break
            }
        }

        val tags = ArrayList<String>()
        for (a in doc.select("a[rel=category tag], .post-categories a, .genre a")) {
            val t = a.text().trim()
            if (t.length in 2..30) tags.add(t)
        }

        // Series with ep-cards
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
                this.tags = tags.distinct()
            }
        }

        // Movie
        val movieLinks = collectGenerateLinksWithQuality(html)
        val data = if (movieLinks.isNotEmpty()) encodeData(movieLinks) else url
        return newMovieLoadResponse(title, url, TvType.Movie, data) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags.distinct()
        }
    }

    /**
     * generate.php → cinecloud /x/{hex} → R2 mkv URL
     */
    private suspend fun resolveGenerate(genUrl: String): Pair<String, String>? {
        return try {
            val page = app.get(genUrl, headers = hdr(mainUrl + "/"))
            val html = page.text

            var cloud = Regex(
                """(https?://[^"'\s]*cinecloud[^"'\s]*/x/[a-zA-Z0-9]+)""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)

            if (cloud == null) {
                val id = genUrl.substringAfter("id=", "").substringBefore("&")
                val decoded = try {
                    String(Base64.decode(id, Base64.DEFAULT))
                } catch (_: Exception) {
                    ""
                }
                if ("cinecloud" in decoded && "/x/" in decoded) {
                    cloud = decoded
                }
            }

            if (cloud == null) {
                // try /f/ path as fallback (sometimes works similarly)
                val id = genUrl.substringAfter("id=", "").substringBefore("&")
                val decoded = try {
                    String(Base64.decode(id, Base64.DEFAULT))
                } catch (_: Exception) {
                    ""
                }
                if ("cinecloud" in decoded) cloud = decoded
            }

            if (cloud == null) return null

            // Truncate /x/ID to hex-only (site requirement)
            val trunc = Regex(
                """(https?://[^/]+)/x/([a-f0-9]+)""",
                RegexOption.IGNORE_CASE
            ).find(cloud)
            if (trunc != null) {
                cloud = trunc.groupValues[1] + "/x/" + trunc.groupValues[2]
            }

            val cHtml = app.get(cloud, headers = hdr(genUrl)).text

            // embed id= encoded R2
            val emb = Regex(
                """[?&]id=(https?%3A%2F%2Fpub[^&"'\s]+)""",
                RegexOption.IGNORE_CASE
            ).find(cHtml)?.groupValues?.getOrNull(1)
            if (emb != null) {
                val direct = URLDecoder.decode(emb, "UTF-8")
                if (direct.startsWith("http")) return direct to cloud
            }

            val r2 = Regex(
                """(https://pub-[a-z0-9]+\.r2\.dev/[^"'\s]+)""",
                RegexOption.IGNORE_CASE
            ).find(cHtml)?.groupValues?.getOrNull(1)
            if (r2 != null) {
                val direct = try {
                    URLDecoder.decode(r2.replace("&amp;", "&"), "UTF-8")
                } catch (_: Exception) {
                    r2.replace("&amp;", "&")
                }
                return direct to cloud
            }

            val media = Regex(
                """(https://[^"'\s]+\.(?:mkv|mp4)[^"'\s]*)""",
                RegexOption.IGNORE_CASE
            ).find(cHtml)?.groupValues?.getOrNull(1)
            if (media != null) return media.replace("&amp;", "&") to cloud

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

        if (links.isEmpty() && data.startsWith("http") && data.contains("cinefreak")) {
            try {
                val html = app.get(data, headers = hdr()).text
                val cards = parseEpisodeCards(html)
                links = if (cards.isNotEmpty()) cards.first().second
                else collectGenerateLinksWithQuality(html)
            } catch (_: Exception) {
            }
        }

        if (links.isEmpty()) return false

        var found = false
        val seen = HashSet<String>()

        // Prefer watch (/x/) links already sorted
        for (q in links) {
            try {
                val resolved = resolveGenerate(q.genUrl) ?: continue
                val direct = resolved.first
                if (!seen.add(direct)) continue
                val quality = qualityFrom(q.label + " " + direct)
                val nameLabel = q.label.ifBlank { "CineFreak" }
                callback(
                    ExtractorLink(
                        name,
                        nameLabel,
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
