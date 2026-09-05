package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class AniWaves : MainAPI() {
    override var mainUrl = "https://aniwaves.ru"
    override var name = "AniWaves"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/recently-updated" to "Recent Update",
        "$mainUrl/new-release" to "New Release",
        "$mainUrl/trending" to "Trending"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val doc = app.get(url).document
        
        val items = doc.select("div.item, .flw-item").mapNotNull { item ->
            val a = item.selectFirst("a") ?: return@mapNotNull null
            val title = item.selectFirst(".name, .film-name, .dynamic-name")?.text() 
                ?: a.attr("title").ifEmpty { a.text() }
            val href = fixUrl(a.attr("href"))
            val poster = item.selectFirst("img")?.let { 
                it.attr("data-src").ifEmpty { it.attr("src") } 
            }
            
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }
        
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/filter?keyword=$q").document
        
        return doc.select("div.item, .flw-item").mapNotNull { item ->
            val a = item.selectFirst("a") ?: return@mapNotNull null
            val title = item.selectFirst(".name, .film-name, .dynamic-name")?.text() 
                ?: a.attr("title").ifEmpty { a.text() }
            val href = fixUrl(a.attr("href"))
            val poster = item.selectFirst("img")?.let { 
                it.attr("data-src").ifEmpty { it.attr("src") } 
            }
            
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        val title = doc.selectFirst(".film-name, .title_name, h1")?.text()?.trim() ?: return null
        val poster = doc.selectFirst(".film-poster img")?.let { 
            it.attr("data-src").ifEmpty { it.attr("src") } 
        }
        val plot = doc.selectFirst(".film-description, .description")?.text()?.trim()
        val year = doc.selectFirst(".item.item-title, .fdi-item")?.text()?.toIntOrNull()
        val tags = doc.select(".item.item-list a, .genres a").map { it.text().trim() }
        
        val isMovie = doc.select(".fdi-item").text().contains("Movie", ignoreCase = true)
        val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime
        
        val eps = mutableListOf<Episode>()
        
        // Target specific containers to avoid grabbing unrelated sidebar links
        val epContainer = doc.selectFirst("#episodes, .episodes-list, .episodes, #episodes-content, .ssl-item")
        val epElements = epContainer?.select("a.ep-item, a") ?: doc.select("a.ep-item")
        
        if (epElements.isNotEmpty()) {
            epElements.forEach { ep ->
                val href = ep.attr("href")
                if (href.isNotBlank() && !href.contains("javascript:")) {
                    val epNumText = ep.attr("data-number").ifEmpty { ep.text() }
                    val epNum = Regex("([0-9.]+)").find(epNumText)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.toInt()
                    val epTitle = ep.attr("title").ifEmpty { ep.text() }
                    
                    eps.add(newEpisode(fixUrl(href)) {
                        this.name = epTitle.trim()
                        this.episode = epNum
                    })
                }
            }
        } else {
            // Fallback for single-episode movies where no list exists
            eps.add(newEpisode(url) {
                this.name = title
                this.episode = 1
            })
        }

        val distinctEps = eps.distinctBy { it.data }

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            addEpisodes(DubStatus.Subbed, distinctEps)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        var found = false
        
        // Fallback 1: Extract any active player iframes mounted natively to the DOM
        val iframes = doc.select("iframe")
            .mapNotNull { it.attr("src").ifEmpty { it.attr("data-src") } }
            .filter { it.startsWith("http") }
            
        iframes.forEach { iframeUrl ->
            found = true
            loadExtractor(iframeUrl, data, subtitleCallback, callback)
        }

        // Fallback 2: Extract embedded server buttons that route to supported hosters
        doc.select("[data-link]").mapNotNull { it.attr("data-link") }
            .filter { it.startsWith("http") }
            .forEach { link ->
                found = true
                loadExtractor(link, data, subtitleCallback, callback)
            }
            
        return found
    }
}
