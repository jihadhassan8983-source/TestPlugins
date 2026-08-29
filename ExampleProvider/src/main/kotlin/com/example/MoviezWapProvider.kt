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
    override var lang = "te"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val ua = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
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

    private fun pagedCategory(url: String, page: Int): String {
        if (page <= 1) return url
        return url.replace(".html", "/$page.html")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data == "$mainUrl/" || request.data == "$mainUrl") {
            if (page > 1) return newHomePageResponse(request.name, emptyList())
            request.data
        } else {
            pagedCategory(request.data, page)
        }
        val doc = app.get(url, headers = headers()).document
        return newHomePageResponse(request.name, parseList(doc))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/search.php?q=$q", headers = headers()).document
        return parseList(doc)
    }

    private fun parseList(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        doc.select("a[href*=/movie/]").forEach { a ->
            var href = a.attr("abs:href")
            if (href.contains("/movie//movie/")) {
                href = href.replace("/movie//movie/", "/movie/")
            }
            if (!href.contains("/movie/") || !href.endsWith(".html")) return@forEach
            if (href.substringAfter("/movie/").isBlank()) return@forEach
            val title = a.text().replace(Regex("<[^>]+>"), "").trim()
            if (title.isBlank() || title.equals("Home", true)) return@forEach
            val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
            out.add(
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.year = year
                }
            )
        }
        return out.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers(url)).document
        val title = doc.selectFirst("title")?.text()
            ?.substringBefore("HDRip")
            ?.substringBefore("Full Movie")
            ?.trim()
            ?: url.substringAfterLast("/").removeSuffix(".html").replace("-", " ")

        val posterPath = doc.selectFirst("img[src*=/poster/]")?.attr("src")
        val poster = when {
            posterPath.isNullOrBlank() -> null
            posterPath.startsWith("http") -> posterPath
            else -> mainUrl + posterPath
        }

        var year: Int? = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        var plot: String? = null
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

        val files = doc.select("a[href*=dwload.php?file=]")
        plot = files.joinToString(" | ") { it.text().trim() }.ifBlank { null }

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
        val fileIds = doc.select("a[href*=dwload.php?file=]").mapNotNull { a ->
            val href = a.attr("href")
            val id = Regex("file=(\\d+)").find(href)?.groupValues?.get(1)
            val label = a.text().trim()
            if (id != null) id to label else null
        }.distinctBy { it.first }

        var found = false
        for ((id, label) in fileIds) {
            val mp4 = resolveMp4(id, data) ?: continue
            val q = qualityFromName(label.ifBlank { mp4 })
            callback.invoke(
                ExtractorLink(
                    name,
                    label.ifBlank { "MP4" },
                    mp4,
                    mainUrl,
                    q,
                    false
                )
            )
            found = true
        }
        return found
    }

    private suspend fun resolveMp4(fileId: String, referer: String): String? {
        val dw = app.get(
            "$mainUrl/dwload.php?file=$fileId",
            headers = headers(referer)
        ).document
        dw.select("a[href*=download.php?file=]").firstOrNull()?.attr("abs:href")?.let { next ->
            val dl = app.get(next, headers = headers("$mainUrl/dwload.php?file=$fileId")).document
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
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            if (href.contains(".mp4", true) && href.startsWith("http")) return href
        }
        val html = doc.html()
        return Regex("""https?://[^"'<>\s]+\.mp4[^"'<>\s]*""").find(html)?.value
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
