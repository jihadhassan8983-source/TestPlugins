@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import java.net.URLEncoder

class AnimeLokProvider : MainAPI() {
    override var mainUrl = "https://animelok.live"
    override var name = "AnimeLok"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val ua =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private fun htmlH(): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to "$mainUrl/"
    )

    private fun jsonH(): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    private fun unescape(s: String): String {
        return s.replace("\\/", "/").replace("\\\"", "\"").replace("\\n", "\n")
    }

    override val mainPage = mainPageOf(
        "$mainUrl/home" to "Home",
        "$mainUrl/trending" to "Trending",
        "$mainUrl/latest-episode" to "Latest Episodes",
        "$mainUrl/most-watched" to "Most Watched"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
        return try {
            val html = app.get(request.data, headers = htmlH()).text
            newHomePageResponse(request.name, parseCards(html), false)
        } catch (_: Exception) {
            newHomePageResponse(request.name, emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            val html = app.get("$mainUrl/search?q=$q", headers = htmlH()).text
            parseCards(html)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseCards(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html, mainUrl)
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        for (a in doc.select("a[href*=/anime/]")) {
            var href = a.attr("abs:href")
            if (href.isBlank()) href = a.attr("href")
            val slug = href.substringAfter("/anime/").substringBefore("/").substringBefore("?").trim()
            if (slug.isBlank() || slug == "cover") continue
            if (!seen.add(slug)) continue

            val img = a.selectFirst("img")
            var title = img?.attr("alt")?.trim().orEmpty()
            if (title.isBlank() || title.equals("Animelok", true)) title = a.attr("title").trim()
            if (title.isBlank()) title = a.text().trim()
            if (title.isBlank()) title = slugToTitle(slug)
            if (title.length < 2) continue

            var poster = img?.attr("abs:src")
            if (poster.isNullOrBlank()) poster = img?.attr("src")
            if (poster.isNullOrBlank()) poster = img?.attr("data-src")

            // Prefer anilist poster if slug ends with anilist id
            val tail = slug.substringAfterLast("-")
            if (tail.all { it.isDigit() } && tail.length >= 3) {
                if (poster.isNullOrBlank() || poster.contains("logo", true)) {
                    poster = "https://img.anili.st/media/$tail"
                }
            }

            out.add(
                newAnimeSearchResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
                    this.posterUrl = poster
                }
            )
        }
        return out.distinctBy { it.url }
    }

    private fun slugToTitle(slug: String): String {
        var base = slug
        val dash = base.lastIndexOf('-')
        if (dash > 0 && base.substring(dash + 1).all { it.isDigit() }) {
            base = base.substring(0, dash)
        }
        return base.replace("-", " ").split(" ").joinToString(" ") {
            it.replaceFirstChar { c -> c.uppercaseChar() }
        }
    }

    private suspend fun resolveSlug(urlOrSlug: String): String {
        var slug = urlOrSlug
        if (slug.contains("/anime/")) slug = slug.substringAfterLast("/anime/")
        if (slug.contains("/watch/")) slug = slug.substringAfterLast("/watch/")
        slug = slug.substringBefore("/").substringBefore("?").trim()
        if (slug.isBlank()) return urlOrSlug.trim()
        if (slug.contains("-") && slug.any { it.isDigit() }) return slug
        try {
            val html = app.get("$mainUrl/anime/$slug", headers = htmlH()).text
            Regex("\"slug\"\\s*:\\s*\"([a-z0-9-]+-[0-9]+)\"").find(html)?.let {
                return it.groupValues[1]
            }
            Regex("/anime/([a-z0-9-]+-[0-9]+)").find(html)?.let {
                return it.groupValues[1]
            }
        } catch (_: Exception) {
        }
        return slug
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = resolveSlug(url)

        val ep1raw = app.get("$mainUrl/api/anime/$slug/episodes/1", headers = jsonH()).text
        if (!ep1raw.contains("\"anime\"")) {
            throw ErrorLoadingException("API failed for $slug")
        }
        val ep1 = unescape(ep1raw)

        val title = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"").find(ep1)?.groupValues?.get(1)
            ?: slugToTitle(slug)

        val anilistId = Regex("\"anilistId\"\\s*:\\s*([0-9]+)")
            .find(ep1)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        // Poster: AniList CDN proxy (site detail page has no og:image)
        var poster: String? = null
        if (anilistId > 0) {
            poster = "https://img.anili.st/media/$anilistId"
        }
        if (poster.isNullOrBlank()) {
            poster = Regex("\"img\"\\s*:\\s*\"(https?://[^\"]+)\"").find(ep1)?.groupValues?.get(1)
        }

        var plot = Regex("\"description\"\\s*:\\s*\"([^\"]*)\"").find(ep1)?.groupValues?.get(1)
        val ep1Langs = Regex("\"languages\"\\s*:\\s*\\[([^\\]]+)\\]").find(ep1)?.groupValues?.get(1)
            ?.replace("\"", "")?.trim()

        // Episode numbers
        val epNums = LinkedHashSet<Int>()
        epNums.add(1)
        try {
            var page = 1
            var totalPages = 1
            while (page <= totalPages && page <= 60) {
                val range = app.get(
                    "$mainUrl/api/anime/$slug/episodes-range?page=$page&lang=ALL&pageSize=50",
                    headers = jsonH()
                ).text
                Regex("\"totalPages\"\\s*:\\s*([0-9]+)").find(range)?.groupValues?.get(1)
                    ?.toIntOrNull()?.let { totalPages = it }
                Regex("\"number\"\\s*:\\s*([0-9]+)").findAll(range).forEach { m ->
                    m.groupValues[1].toIntOrNull()?.let { if (it > 0) epNums.add(it) }
                }
                page++
            }
        } catch (_: Exception) {
        }

        if (epNums.size <= 1) {
            var n = 2
            var miss = 0
            while (n <= 50 && miss < 2) {
                try {
                    val t = app.get("$mainUrl/api/anime/$slug/episodes/$n", headers = jsonH()).text
                    if (t.contains("\"number\"")) {
                        epNums.add(n)
                        miss = 0
                    } else miss++
                } catch (_: Exception) {
                    miss++
                }
                n++
            }
        } else {
            val max = epNums.maxOrNull() ?: 1
            if (max <= 200) for (i in 1..max) epNums.add(i)
        }

        val totalEps = epNums.size
        val infoBits = ArrayList<String>()
        infoBits.add("Episodes: $totalEps")
        if (!ep1Langs.isNullOrBlank()) infoBits.add("Audio: $ep1Langs")
        if (anilistId > 0) infoBits.add("AniList: $anilistId")

        val fullPlot = buildString {
            if (!plot.isNullOrBlank()) append(plot).append("\n\n")
            append(infoBits.joinToString(" · "))
        }

        val episodes = epNums.sorted().map { num ->
            newEpisode("$slug|$num|$anilistId") {
                this.name = "Episode $num"
                this.episode = num
                this.posterUrl = poster
            }
        }

        return newAnimeLoadResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
            this.posterUrl = poster
            this.plot = fullPlot
            this.tags = listOfNotNull(
                ep1Langs?.takeIf { it.isNotBlank() }
            )
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private fun qualityOf(q: String): Int {
        val t = q.lowercase()
        return when {
            "1080" in t -> Qualities.P1080.value
            "720" in t -> Qualities.P720.value
            "480" in t -> Qualities.P480.value
            "360" in t -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    /** Build nice source name: Hindi / Tamil / Telugu / Multi / Hard Sub ... */
    private fun serverLabel(name: String, tip: String, langs: String): String {
        val n = name.trim()
        val t = tip.trim()
        val l = langs.replace("\"", "").replace("[", "").replace("]", "").trim()

        // Explicit language servers (One Piece style)
        val langNames = listOf(
            "Hindi", "Tamil", "Telugu", "Malayalam", "Kannada",
            "English", "Japanese", "Bengali", "Chinese", "Korean"
        )
        for (ln in langNames) {
            if (n.equals(ln, true) || n.contains(ln, true)) {
                return if (t.isNotBlank() && !t.equals(ln, true)) "$ln ($t)" else ln
            }
        }

        if (t.contains("Multi", true) || n.contains("Multi", true)) {
            return if (l.isNotBlank()) "Multi [$l]" else "Multi Audio"
        }
        if (t.contains("Hard", true)) return "Hard Sub"
        if (t.contains("Soft", true)) return "Soft Sub"
        if (t.equals("Dub", true) || n.contains("Dub", true)) {
            return if (l.isNotBlank()) "Dub [$l]" else "Dub"
        }
        if (n.equals("pahe", true)) return "Pahe"
        if (n.isNotBlank()) {
            return if (l.isNotBlank()) "$n [$l]" else n
        }
        return t.ifBlank { "Server" }
    }

    private fun push(
        callback: (ExtractorLink) -> Unit,
        label: String,
        url: String,
        referer: String,
        quality: Int,
        added: HashSet<String>
    ): Boolean {
        val u = unescape(url).trim()
        if (!u.startsWith("http")) return false
        if (!added.add(u)) return false
        callback.invoke(
            ExtractorLink(
                name,
                label,
                u,
                referer,
                quality,
                u.contains(".m3u8")
            )
        )
        return true
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        val slug = parts.getOrNull(0)?.trim().orEmpty()
        val epNum = parts.getOrNull(1)?.toIntOrNull() ?: 1
        val anilistFromData = parts.getOrNull(2)?.toIntOrNull() ?: 0
        if (slug.isBlank()) return false

        val raw = try {
            app.get("$mainUrl/api/anime/$slug/episodes/$epNum", headers = jsonH()).text
        } catch (_: Exception) {
            return false
        }
        if (raw.isBlank()) return false
        val text = unescape(raw)

        var found = false
        val added = HashSet<String>()

        // ---- Parse each server object fully ----
        // {"id":...,"name":"Hindi","tip":"Abyess","languages":["HINDI"],"url":"https://..."}
        val serverRe = Regex(
            "\\{\"id\"\\s*:\\s*[0-9]+\\s*,\\s*\"name\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"tip\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"languages\"\\s*:\\s*(\\[[^\\]]*])\\s*,\\s*\"url\"\\s*:\\s*\"([^\"]+)\""
        )

        for (m in serverRe.findAll(text)) {
            val sName = m.groupValues[1]
            val sTip = m.groupValues[2]
            val sLangs = m.groupValues[3]
            var sUrl = m.groupValues[4]
            val label = serverLabel(sName, sTip, sLangs)

            // Pahe embeds JSON array as string
            if (sUrl.startsWith("[")) {
                val inner = unescape(sUrl)
                Regex(
                    "\"url\"\\s*:\\s*\"(https?://[^\"]+\\.m3u8[^\"]*)\"\\s*,\\s*\"quality\"\\s*:\\s*\"([^\"]+)\""
                ).findAll(inner).forEach { q ->
                    if (push(
                            callback,
                            "$label ${q.groupValues[2]}",
                            q.groupValues[1],
                            mainUrl,
                            qualityOf(q.groupValues[2]),
                            added
                        )
                    ) found = true
                }
                Regex(
                    "\"quality\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"url\"\\s*:\\s*\"(https?://[^\"]+\\.m3u8[^\"]*)\""
                ).findAll(inner).forEach { q ->
                    if (push(
                            callback,
                            "$label ${q.groupValues[1]}",
                            q.groupValues[2],
                            mainUrl,
                            qualityOf(q.groupValues[1]),
                            added
                        )
                    ) found = true
                }
                continue
            }

            if (sUrl.contains(".m3u8")) {
                if (push(callback, label, sUrl, mainUrl, Qualities.Unknown.value, added)) {
                    found = true
                }
                continue
            }

            // Dead ToonStream CDN — skip
            if (sUrl.contains("as-cdn", true)) continue

            // Hindi/Tamil/Telugu etc. via short.icu / other hosts
            try {
                if (loadExtractor(sUrl, mainUrl, subtitleCallback, callback)) {
                    found = true
                    continue
                }
            } catch (_: Exception) {
            }

            // Manual page scrape for m3u8
            try {
                val body = unescape(app.get(sUrl, headers = htmlH()).text)
                Regex("https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*").findAll(body).forEach { mm ->
                    if (push(callback, label, mm.value, sUrl, Qualities.Unknown.value, added)) {
                        found = true
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Fallback: any m3u8 left in body
        Regex("https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*").findAll(text).forEach { m ->
            if (push(callback, "Stream", m.value, mainUrl, Qualities.Unknown.value, added)) {
                found = true
            }
        }

        // MegaPlay backup (Sub / Dub only — host limitation)
        val anilistId = Regex("\"anilistId\"\\s*:\\s*([0-9]+)")
            .find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: anilistFromData

        if (anilistId > 0) {
            for (type in listOf("sub", "dub")) {
                try {
                    if (megaPlay(anilistId, epNum, type, callback, added)) found = true
                } catch (_: Exception) {
                }
            }
        }

        return found
    }

    private suspend fun megaPlay(
        anilistId: Int,
        epNum: Int,
        type: String,
        callback: (ExtractorLink) -> Unit,
        added: HashSet<String>
    ): Boolean {
        val pageUrl = "https://megaplay.buzz/stream/ani/$anilistId/$epNum/$type"
        val html = try {
            app.get(
                pageUrl,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to "$mainUrl/",
                    "Accept" to "text/html,*/*"
                )
            ).text
        } catch (_: Exception) {
            return false
        }

        val playerId = Regex("data-id=\"([0-9]+)\"").find(html)?.groupValues?.getOrNull(1)
            ?: return false

        val srcRaw = try {
            app.get(
                "https://megaplay.buzz/stream/getSources?id=$playerId",
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to pageUrl,
                    "Origin" to "https://megaplay.buzz",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/plain, */*"
                )
            ).text
        } catch (_: Exception) {
            return false
        }

        val src = unescape(srcRaw)
        val file = Regex("\"file\"\\s*:\\s*\"(https?://[^\"]+)\"")
            .find(src)?.groupValues?.getOrNull(1)
            ?: return false

        return push(
            callback,
            "AniStream ${type.uppercase()}",
            file,
            "https://megaplay.buzz/",
            Qualities.Unknown.value,
            added
        )
    }
}
