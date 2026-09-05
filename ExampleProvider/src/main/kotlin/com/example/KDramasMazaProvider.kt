@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class KDramasMazaProvider : MainAPI() {
    override var mainUrl = "https://kdramasmaza.net"
    override var name = "KDramasMaza"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val ua =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private fun hdr(referer: String = mainUrl + "/"): Map<String, String> {
        return mapOf(
            "User-Agent" to ua,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9,hi;q=0.8",
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
            .replace(Regex("""(?i)\s*[–\-]\s*Complete.*$"""), "")
            .replace(Regex("""(?i)\s*[–\-]\s*KDramas Maza.*$"""), "")
            .replace(Regex("""(?i)\s*\[.*?\]"""), "")
            .replace(Regex("""(?i)\s*in Urdu.*$"""), "")
            .replace(Regex("""(?i)\s*in Hindi.*$"""), "")
            .replace(Regex("""(?i)\s*Episode.*$"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
        return t.ifBlank { raw.trim() }
    }

    private fun yearFrom(text: String): Int? =
        Regex("""\b(20\d{2})\b""").find(text)?.value?.toIntOrNull()

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
        for (a in doc.select("h2.entry-title a[href], h1.entry-title a[href], .entry-title a[href]")) {
            val href = abs(a.attr("abs:href").ifBlank { a.attr("href") }) ?: continue
            if (!href.contains("kdramasmaza.net")) continue
            if (href.contains("/category/") || href.contains("/tag/") || href.contains("/page/")) continue
            if (!seen.add(href)) continue
            val titleRaw = a.text().trim()
            if (titleRaw.isBlank()) continue
            val parent = a.parents().firstOrNull { it.selectFirst("img") != null } ?: a.parent()
            out += newTvSeriesSearchResponse(cleanTitle(titleRaw), href, TvType.TvSeries) {
                this.posterUrl = parent?.let { pickImg(it) }
                this.year = yearFrom(titleRaw)
            }
        }
        return out
    }

    override val mainPage = mainPageOf(
        mainUrl + "/" to "Latest",
        mainUrl + "/category/korean-dramas/" to "Korean Dramas",
        mainUrl + "/category/korean-dramas-in-english-dubbed/" to "English Dubbed",
        mainUrl + "/category/turkish-dramas-in-urdu-hindi-dubbed/" to "Turkish",
        mainUrl + "/category/zzaction/" to "Action",
        mainUrl + "/category/zzromantic/" to "Romance",
        mainUrl + "/category/zzthriller/" to "Thriller",
        mainUrl + "/category/anime-in-hindi-dubbed/" to "Anime Hindi"
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
        val doc = app.get(
            mainUrl + "/?s=" + URLEncoder.encode(q, "UTF-8"),
            headers = hdr()
        ).document
        return parseCards(doc)
    }

    /** Prefer All Episodes Wise over Zip */
    private fun findArchiveLinks(html: String): List<String> {
        val scored = ArrayList<Pair<Int, String>>()

        for (m in Regex("""<button([^>]*)>([\s\S]*?)</button>""", RegexOption.IGNORE_CASE).findAll(html)) {
            val attrs = m.groupValues[1]
            val label = m.groupValues[2].replace(Regex("""<[^>]+>"""), "").trim().lowercase()
            val href = Regex(
                """(?:location\.href|window\.location)\s*=\s*['"](https?://kdramasmaza\.com\.pk/archives/\d+)['"]""",
                RegexOption.IGNORE_CASE
            ).find(attrs)?.groupValues?.getOrNull(1) ?: continue
            val score = when {
                "wise" in label -> 0
                "zip" in label -> 2
                else -> 1
            }
            scored.add(score to href)
        }

        for (m in Regex(
            """href\s*=\s*["'](https?://kdramasmaza\.com\.pk/archives/\d+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(html)) {
            val href = m.groupValues[1]
            if (scored.none { it.second == href }) scored.add(1 to href)
        }

        for (m in Regex(
            """(?:location\.href|window\.location)\s*=\s*['"](https?://kdramasmaza\.com\.pk/archives/\d+)['"]""",
            RegexOption.IGNORE_CASE
        ).findAll(html)) {
            val href = m.groupValues[1]
            if (scored.none { it.second == href }) scored.add(1 to href)
        }

        return scored.sortedBy { it.first }.map { it.second }.distinct()
    }

    /**
     * Returns list of (episodeNumber, hubcloudUrl, extraUrls)
     * data for loadLinks = hubcloudUrl only (simple, no encoding issues)
     */
    private fun parseEpisodes(html: String): List<Triple<Int, String, List<String>>> {
        val out = ArrayList<Triple<Int, String, List<String>>>()

        // Split by ep-no labels
        val labelRegex = Regex(
            """<span[^>]*ep-no[^>]*>\s*Episode\s*(\d+)\s*</span>""",
            RegexOption.IGNORE_CASE
        )
        val labels = labelRegex.findAll(html).map { it.groupValues[1].toInt() }.toList()
        if (labels.isEmpty()) {
            // fallback text split
            return parseEpisodesFallback(html)
        }

        val parts = Regex(
            """<span[^>]*ep-no[^>]*>\s*Episode\s*\d+\s*</span>""",
            RegexOption.IGNORE_CASE
        ).split(html)

        for (i in labels.indices) {
            val ep = labels[i]
            val block = if (i + 1 < parts.size) parts[i + 1].take(3000) else ""
            val hubs = Regex(
                """https?://(?:www\.)?hubcloud\.[a-z]+/drive/[a-zA-Z0-9]+""",
                RegexOption.IGNORE_CASE
            ).findAll(block).map { it.value }.distinct().toList()

            val extras = ArrayList<String>()
            for (m in Regex(
                """https?://(?:new\d+\.)?gdflix\.[a-z]+/file/[a-zA-Z0-9]+""",
                RegexOption.IGNORE_CASE
            ).findAll(block)) {
                extras.add(m.value)
            }

            if (hubs.isNotEmpty()) {
                out.add(Triple(ep, hubs.first(), hubs.drop(1) + extras))
            } else if (extras.isNotEmpty()) {
                out.add(Triple(ep, extras.first(), extras.drop(1)))
            }
        }
        return out
    }

    private fun parseEpisodesFallback(html: String): List<Triple<Int, String, List<String>>> {
        val out = ArrayList<Triple<Int, String, List<String>>>()
        val epRegex = Regex(
            """Episode\s*0*(\d+)([\s\S]*?)(?=Episode\s*0*\d+|\z)""",
            RegexOption.IGNORE_CASE
        )
        for (m in epRegex.findAll(html)) {
            val ep = m.groupValues[1].toIntOrNull() ?: continue
            val block = m.groupValues[2].take(3000)
            val hubs = Regex(
                """https?://(?:www\.)?hubcloud\.[a-z]+/drive/[a-zA-Z0-9]+""",
                RegexOption.IGNORE_CASE
            ).findAll(block).map { it.value }.distinct().toList()
            val gds = Regex(
                """https?://(?:new\d+\.)?gdflix\.[a-z]+/file/[a-zA-Z0-9]+""",
                RegexOption.IGNORE_CASE
            ).findAll(block).map { it.value }.distinct().toList()
            when {
                hubs.isNotEmpty() -> out.add(Triple(ep, hubs.first(), hubs.drop(1) + gds))
                gds.isNotEmpty() -> out.add(Triple(ep, gds.first(), gds.drop(1)))
            }
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = hdr()).document
        val html = doc.html()
        val titleRaw = doc.selectFirst("h1.entry-title, h1")?.text() ?: "Unknown"
        val title = cleanTitle(titleRaw)
        val poster = abs(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: pickImg(doc.body())
        val year = yearFrom(titleRaw)

        var plot: String? = null
        val tags = ArrayList<String>()
        val content = doc.selectFirst(".entry-content, article")
        if (content != null) {
            val text = content.text()
            if (text.contains("Genres:", true)) {
                val g = text.substringAfter("Genres:", "").substringBefore("Download").trim()
                tags += g.split(",", " ").map { it.trim() }.filter { it.length in 3..24 }.take(10)
            }
            val p = content.selectFirst("p")?.text()?.trim()
            if (p != null && p.length > 40) plot = p
        }

        val archives = findArchiveLinks(html)
        val episodes = ArrayList<Episode>()
        val seen = HashSet<Int>()

        for (archUrl in archives) {
            try {
                val archHtml = app.get(archUrl, headers = hdr(url)).text
                for ((epNum, primary, extras) in parseEpisodes(archHtml)) {
                    if (!seen.add(epNum)) continue
                    // data = primary URL only (hubcloud or gdflix) — clean, no || encoding issues
                    // extras stored after \n for optional secondary resolve
                    val dataStr = if (extras.isEmpty()) {
                        primary
                    } else {
                        primary + "\n" + extras.joinToString("\n")
                    }
                    episodes += newEpisode(primary) {
                        this.name = "Episode " + epNum
                        this.episode = epNum
                        this.data = dataStr
                    }
                }
            } catch (_: Exception) {
            }
            // Prefer first archive that actually has episodes (wise sorted first)
            if (episodes.isNotEmpty()) break
        }

        if (episodes.isEmpty()) {
            val fb = archives.firstOrNull() ?: url
            episodes += newEpisode(fb) {
                this.name = "Episode 1"
                this.episode = 1
                this.data = fb
            }
        }

        episodes.sortBy { it.episode }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags.distinct()
        }
    }

    private suspend fun resolveHubCloud(driveUrl: String): List<String> {
        val results = ArrayList<String>()
        try {
            val page = app.get(driveUrl, headers = hdr("https://kdramasmaza.com.pk/")).text

            val genLinks = Regex(
                """(https?://(?:gamerxyt\.com|[^"'\s>]+)/hubcloud\.php\?[^"'\s>]+)""",
                RegexOption.IGNORE_CASE
            ).findAll(page).map { it.groupValues[1].replace("&amp;", "&") }.distinct().toList()

            for (gen in genLinks.take(2)) {
                try {
                    val genHtml = app.get(gen, headers = hdr(driveUrl)).text

                    // R2 signed URL (works with Range GET)
                    for (m in Regex(
                        """(https://[a-z0-9]+\.r2\.cloudflarestorage\.com/[^"'\s>]+)""",
                        RegexOption.IGNORE_CASE
                    ).findAll(genHtml)) {
                        val u = m.groupValues[1].replace("&amp;", "&")
                        if (u !in results) results.add(u)
                    }

                    // pixel.hubcloud
                    for (m in Regex(
                        """(https://pixel\.hubcloud\.[a-z]+/\?id=[^"'\s>]+)""",
                        RegexOption.IGNORE_CASE
                    ).findAll(genHtml)) {
                        val u = m.groupValues[1].replace("&amp;", "&")
                        if (u !in results) results.add(u)
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        return results
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = data.split("\n").map { it.trim() }.filter { it.startsWith("http") }
        val toResolve = ArrayList<String>()

        if (links.isNotEmpty()) {
            toResolve.addAll(links)
        } else if (data.startsWith("http")) {
            toResolve.add(data)
        }

        // If data is drama/archive page, parse servers
        if (toResolve.size == 1) {
            val only = toResolve[0]
            if (only.contains("kdramasmaza")) {
                toResolve.clear()
                try {
                    val html = app.get(only, headers = hdr()).text
                    if (only.contains("/archives/")) {
                        for ((_, primary, extras) in parseEpisodes(html)) {
                            toResolve.add(primary)
                            toResolve.addAll(extras)
                        }
                    } else {
                        for (arch in findArchiveLinks(html)) {
                            val archHtml = app.get(arch, headers = hdr(only)).text
                            for ((_, primary, extras) in parseEpisodes(archHtml)) {
                                toResolve.add(primary)
                                toResolve.addAll(extras)
                            }
                            if (toResolve.isNotEmpty()) break
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        if (toResolve.isEmpty()) return false

        var found = false
        val tried = HashSet<String>()

        for (link in toResolve) {
            if (!tried.add(link)) continue

            if (link.contains("hubcloud", true)) {
                // 1) Custom R2 resolve
                try {
                    val directs = resolveHubCloud(link)
                    for ((i, direct) in directs.withIndex()) {
                        callback(
                            ExtractorLink(
                                name,
                                if (i == 0) "HubCloud R2" else "HubCloud Alt " + (i + 1),
                                direct,
                                "https://hubcloud.cx/",
                                Qualities.P720.value,
                                false
                            )
                        )
                        found = true
                    }
                } catch (_: Exception) {
                }

                // 2) Built-in HubCloud extractor
                try {
                    if (loadExtractor(link, "https://hubcloud.cx/", subtitleCallback, callback)) {
                        found = true
                    }
                } catch (_: Exception) {
                }
            } else {
                // GDFlix / others
                try {
                    if (loadExtractor(link, mainUrl, subtitleCallback, callback)) {
                        found = true
                    }
                } catch (_: Exception) {
                }
            }
        }

        return found
    }
}
