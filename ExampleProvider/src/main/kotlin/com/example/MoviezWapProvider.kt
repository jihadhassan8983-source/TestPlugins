@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Document
import java.net.URLEncoder

class MoviezWapProvider : MainAPI() {
    override var mainUrl = "https://www.moviezwap.golf"
    override var name = "MoviezWap"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val ua = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/category/Telugu-(2026)-Movies.html" to "Telugu 2026",
        "$mainUrl/category/Tamil-(2026)-Movies.html" to "Tamil 2026",
        "$mainUrl/category/Hindi-New-Movies.html" to "Hindi",
        "$mainUrl/category/Telugu-Web-Series.html" to "Web Series"
    )

    private fun headers(referer: String = "$mainUrl/") =
        ua + mapOf("Referer" to referer)

    private fun absUrl(raw: String): String {
        val h = raw.trim()
        if (h.startsWith("http")) return h
        if (h.startsWith("/")) return mainUrl + h
        return "$mainUrl/$h"
    }

    private fun cleanTitle(text: String): String {
        return text.replace('\u00bb', ' ')
            .replace("*", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun posterGuess(movieUrl: String): String {
        val slug = movieUrl.substringAfterLast("/")
            .substringBefore(".html")
            .lowercase()
            .replace("(", "")
            .replace(")", "")
            .replace(" ", "-")
        return "$mainUrl/poster/$slug.jpg"
    }

    private fun encodeMedia(url: String): String {
        val q = url.indexOf('?')
        val path = if (q >= 0) url.substring(0, q) else url
        val query = if (q >= 0) url.substring(q) else ""
        return path.replace(" ", "%20") + query
    }

    private fun pagedCategory(url: String, page: Int): String {
        if (page <= 1) return url
        return url.replace(".html", "/$page.html")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data == "$mainUrl/" || request.data == "$mainUrl") {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            request.data
        } else {
            pagedCategory(request.data, page)
        }
        val doc = app.get(url, headers = headers()).document
        val list = parseList(doc)
        return newHomePageResponse(request.name, list, list.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/search.php?q=$q", headers = headers()).document
        return parseList(doc)
    }

    private fun parseList(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()

        fun addItem(rawHref: String, rawTitle: String) {
            var href = absUrl(rawHref).replace("/movie//movie/", "/movie/")
            if (!href.contains("/movie/") || !href.contains(".html")) return
            val slug = href.substringAfter("/movie/").substringBefore("?")
            if (slug.isBlank() || slug == ".html") return
            val title = cleanTitle(rawTitle)
            if (title.isBlank() || title.equals("Home", true)) return
            val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
            out.add(
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.year = year
                    this.posterUrl = posterGuess(href)
                }
            )
        }

        doc.select("a[href]").forEach { a ->
            val href = a.attr("href")
            if (!href.contains("/movie/")) return@forEach
            addItem(href, a.text())
        }

        if (out.isEmpty()) {
            Regex("""href=['"]([^'"]*/movie/[^'"]+\.html)['"][^>]*>([\s\S]*?)</a>""")
                .findAll(doc.html())
                .forEach { m ->
                    addItem(m.groupValues[1], m.groupValues[2].replace(Regex("<[^>]+>"), ""))
                }
        }
        return out.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers(url)).document
        val title = cleanTitle(
            doc.selectFirst("title")?.text()
                ?.substringBefore("HDRip")
                ?.substringBefore("Full Movie")
                ?.trim()
                ?: url.substringAfterLast("/").removeSuffix(".html").replace("-", " ")
        )

        val posterPath = doc.selectFirst("img[src*=poster]")?.attr("src")
        val poster = if (posterPath.isNullOrBlank()) posterGuess(url) else absUrl(posterPath)

        var year: Int? = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val genres = ArrayList<String>()
        doc.select("div.movie").forEach { row ->
            val t = row.text()
            when {
                t.contains("Genre", true) -> {
                    genres.addAll(
                        t.substringAfter(":").split(",").map { it.trim() }.filter { it.isNotBlank() }
                    )
                }
                t.contains("Release Date", true) -> {
                    year = year ?: Regex("(19|20)\\d{2}").find(t)?.value?.toIntOrNull()
                }
                t.contains("Category", true) -> {
                    val cat = t.substringAfter(":").trim()
                    if (cat.isNotBlank()) genres.add(cat)
                }
            }
        }

        val files = doc.select("a[href*=dwload.php]")
        val plot = files.joinToString(" | ") { it.text().trim() }.ifBlank { null }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres.ifEmpty { null }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!data.startsWith("http")) return false
        val doc = app.get(data, headers = headers(data)).document
        val fileIds = LinkedHashMap<String, String>()
        doc.select("a[href*=dwload.php]").forEach { a ->
            val id = Regex("file=(\\d+)").find(a.attr("href"))?.groupValues?.get(1) ?: return@forEach
            fileIds[id] = a.text().trim()
        }
        if (fileIds.isEmpty()) {
            Regex("dwload\\.php\\?file=(\\d+)").findAll(doc.html()).forEach { m ->
                fileIds.putIfAbsent(m.groupValues[1], "File ${m.groupValues[1]}")
            }
        }

        var found = false
        for ((id, label) in fileIds) {
            val mp4 = resolveMp4(id, data) ?: continue
            val play = encodeMedia(mp4)
            callback.invoke(
                ExtractorLink(
                    name,
                    label.ifBlank { "MP4" },
                    play,
                    mainUrl,
                    qualityFromName(label.ifBlank { play }),
                    false
                )
            )
            found = true
        }
        return found
    }

    private suspend fun resolveMp4(fileId: String, referer: String): String? {
        val dw = app.get("$mainUrl/dwload.php?file=$fileId", headers = headers(referer)).document
        val next = dw.select("a[href*=download.php]").firstOrNull()?.attr("href")
        if (!next.isNullOrBlank()) {
            val dl = app.get(absUrl(next), headers = headers("$mainUrl/dwload.php?file=$fileId")).document
            findMp4(dl)?.let { return it }
        }
        findMp4(dw)?.let { return it }
        val direct = app.get(
            "$mainUrl/download.php?file=$fileId",
            headers = headers("$mainUrl/dwload.php?file=$fileId")
        ).document
        return findMp4(direct)
    }

    private fun findMp4(doc: Document): String? {
        doc.select("a[href]").forEach { a ->
            val href = a.attr("href")
            if (href.contains(".mp4", true) && href.startsWith("http")) return href
        }
        return Regex("""https?://[^"'<>]+\.mp4[^"'<>]*""").find(doc.html())?.value
            ?.replace("&amp;", "&")
    }

    private fun qualityFromName(name: String): Int {
        val p = Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)?.toIntOrNull()
        return when (p) {
            1080 -> Qualities.P1080.value
            720 -> Qualities.P720.value
            480 -> Qualities.P480.value
            360, 320 -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
