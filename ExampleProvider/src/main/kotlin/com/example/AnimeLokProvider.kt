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

    private fun htmlH() = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to "$mainUrl/"
    )

    private fun jsonH() = mapOf(
        "User-Agent" to ua,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    private fun unescape(s: String) =
        s.replace("\\/", "/").replace("\\\"", "\"").replace("\\n", "\n")

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
            parseCards(app.get("$mainUrl/search?q=$q", headers = htmlH()).text)
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
            if (slug.isBlank() || slug == "cover" || !seen.add(slug)) continue

            val img = a.selectFirst("img")
            var title = img?.attr("alt")?.trim().orEmpty()
            if (title.isBlank() || title.equals("Animelok", true)) title = a.attr("title").trim()
            if (title.isBlank()) title = a.text().trim()
            if (title.isBlank()) title = slugToTitle(slug)
            if (title.length < 2) continue

            var poster = img?.attr("abs:src") ?: img?.attr("src") ?: img?.attr("data-src")
            val tail = slug.substringAfterLast("-")
            if (tail.all { it.isDigit() } && tail.length >= 3) {
                if (poster.isNullOrBlank() || poster.contains("logo", true)) {
                    poster = "https://img.anili.st/media/$tail"
                }
            }

            out.add(newAnimeSearchResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
                this.posterUrl = poster
            })
        }
        return out.distinctBy { it.url }
    }

    private fun slugToTitle(slug: String): String {
        var base = slug
        val dash = base.lastIndexOf('-')
        if (dash > 0 && base.substring(dash + 1).all { it.isDigit() }) base = base.substring(0, dash)
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
            Regex("\"slug\"\\s*:\\s*\"([a-z0-9-]+-[0-9]+)\"").find(html)?.let { return it.groupValues[1] }
            Regex("/anime/([a-z0-9-]+-[0-9]+)").find(html)?.let { return it.groupValues[1] }
        } catch (_: Exception) {
        }
        return slug
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = resolveSlug(url)
        val ep1raw = app.get("$mainUrl/api/anime/$slug/episodes/1", headers = jsonH()).text
        if (!ep1raw.contains("\"anime\"")) throw ErrorLoadingException("API failed for $slug")
        val ep1 = unescape(ep1raw)

        val title = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"").find(ep1)?.groupValues?.get(1)
            ?: slugToTitle(slug)
        val anilistId = Regex("\"anilistId\"\\s*:\\s*([0-9]+)")
            .find(ep1)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        var poster: String? = if (anilistId > 0) "https://img.anili.st/media/$anilistId" else null
        if (poster.isNullOrBlank()) {
            poster = Regex("\"img\"\\s*:\\s*\"(https?://[^\"]+)\"").find(ep1)?.groupValues?.get(1)
        }

        val ep1Langs = Regex("\"languages\"\\s*:\\s*\\[([^\\]]+)\\]")
            .find(ep1)?.groupValues?.get(1)?.replace("\"", "")?.trim()

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
                        epNums.add(n); miss = 0
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

        val plot = buildString {
            append("Episodes: ${epNums.size}")
            if (!ep1Langs.isNullOrBlank()) append("\nAudio on site: $ep1Langs")
            append("\nOpen episode → Source list for Hindi / Tamil / Telugu / Sub / Dub")
        }

        // Same episodes under BOTH Sub & Dub so CloudStream shows language tabs
        val episodes = epNums.sorted().map { num ->
            newEpisode("$slug|$num|$anilistId") {
                this.name = "Episode $num"
                this.episode = num
                this.posterUrl = poster
            }
        }

        return newAnimeLoadResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = listOfNotNull(ep1Langs?.takeIf { it.isNotBlank() })
            addEpisodes(DubStatus.Subbed, episodes)
            addEpisodes(DubStatus.Dubbed, episodes)
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
        if (!added.add("$label|$u")) return false
        callback.invoke(
            ExtractorLink(name, label, u, referer, quality, u.contains(".m3u8"))
        )
        return true
    }

    /** Priority: Hindi/Tamil/Telugu first, then others */
    private fun langPriority(label: String): Int {
        val l = label.lowercase()
        return when {
            "hindi" in l -> 0
            "tamil" in l -> 1
            "telugu" in l -> 2
            "malayalam" in l -> 3
            "kannada" in l -> 4
            "english" in l && "dub" in l -> 5
            "multi" in l -> 6
            "pahe" in l || "hard" in l -> 7
            "anistream" in l && "sub" in l -> 8
            "anistream" in l && "dub" in l -> 9
            else -> 10
        }
    }

    private data class Srv(
        val name: String,
        val tip: String,
        val langs: String,
        val url: String
    )

    private fun parseServers(text: String): List<Srv> {
        val out = ArrayList<Srv>()
        // Flexible: find each {"id":...} block inside servers array
        val arr = Regex("\"servers\"\\s*:\\s*\\[(.*?)]\\s*,\\s*\"subtitles\"", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1) ?: text

        // Split roughly by {"id"
        val parts = arr.split(Regex("\\{\\s*\"id\""))
        for (p in parts) {
            if (!p.contains("\"url\"")) continue
            val name = Regex("\"name\"\\s*:\\s*\"([^\"]*)\"").find(p)?.groupValues?.get(1) ?: ""
            val tip = Regex("\"tip\"\\s*:\\s*\"([^\"]*)\"").find(p)?.groupValues?.get(1) ?: ""
            val langs = Regex("\"languages\"\\s*:\\s*(\\[[^\\]]*])").find(p)?.groupValues?.get(1) ?: "[]"
            val url = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(p)?.groupValues?.get(1) ?: continue
            out.add(Srv(name, tip, langs, url))
        }
        return out
    }

    private fun labelOf(s: Srv): String {
        val n = s.name.trim()
        val t = s.tip.trim()
        val l = s.langs.replace("\"", "").replace("[", "").replace("]", "")

        val known = listOf(
            "Hindi", "Tamil", "Telugu", "Malayalam", "Kannada",
            "Bengali", "English", "Japanese"
        )
        for (k in known) {
            if (n.equals(k, true)) {
                return if (t.isNotBlank() && !t.equals(k, true)) "$k · $t" else k
            }
        }
        if (t.contains("Multi", true) || n.contains("Multi", true)) {
            return if (l.isNotBlank()) "Multi · $l" else "Multi Audio"
        }
        if (t.contains("Hard", true) || n.equals("pahe", true)) return "Hard Sub · Pahe"
        if (t.contains("Soft", true)) return "Soft Sub"
        if (t.equals("Dub", true)) return if (l.isNotBlank()) "Dub · $l" else "Dub"
        return n.ifBlank { t.ifBlank { "Server" } }
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
        val servers = parseServers(text).sortedBy { langPriority(labelOf(it)) }

        for (s in servers) {
            val label = labelOf(s)
            var sUrl = s.url

            // Pahe JSON array
            if (sUrl.startsWith("[")) {
                val inner = unescape(sUrl)
                Regex(
                    "\"url\"\\s*:\\s*\"(https?://[^\"]+\\.m3u8[^\"]*)\"\\s*,\\s*\"quality\"\\s*:\\s*\"([^\"]+)\""
                ).findAll(inner).forEach { q ->
                    if (push(callback, "$label ${q.groupValues[2]}", q.groupValues[1], mainUrl, qualityOf(q.groupValues[2]), added))
                        found = true
                }
                continue
            }

            if (sUrl.contains("as-cdn", true)) continue

            if (sUrl.contains(".m3u8")) {
                if (push(callback, label, sUrl, mainUrl, Qualities.Unknown.value, added)) found = true
                continue
            }

            // Hindi / Tamil / Telugu hosts (short.icu / multi player) — try extractors
            val tryUrls = LinkedHashSet<String>()
            tryUrls.add(sUrl)
            if (sUrl.contains("short.icu")) {
                val code = sUrl.substringAfterLast("/")
                tryUrls.add("https://short.icu/$code")
                tryUrls.add("https://short.icu/e/$code")
                tryUrls.add("https://abyss.to/e/$code")
                tryUrls.add("https://player.abyss.to/e/$code")
            }

            var extracted = false
            for (tu in tryUrls) {
                try {
                    if (loadExtractor(tu, mainUrl, subtitleCallback, callback)) {
                        found = true
                        extracted = true
                        break
                    }
                } catch (_: Exception) {
                }
            }
            if (extracted) continue

            try {
                val body = unescape(app.get(sUrl, headers = htmlH()).text)
                Regex("https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*").findAll(body).forEach { mm ->
                    if (push(callback, label, mm.value, sUrl, Qualities.Unknown.value, added)) found = true
                }
            } catch (_: Exception) {
            }
        }

        // Any leftover m3u8
        Regex("https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*").findAll(text).forEach { m ->
            if (push(callback, "Stream", m.value, mainUrl, Qualities.Unknown.value, added)) found = true
        }

        // AniStream Sub / Dub (always useful backup)
        val anilistId = Regex("\"anilistId\"\\s*:\\s*([0-9]+)")
            .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: anilistFromData
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
            app.get(pageUrl, headers = mapOf("User-Agent" to ua, "Referer" to "$mainUrl/")).text
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
                    "Accept" to "application/json"
                )
            ).text
        } catch (_: Exception) {
            return false
        }
        val file = Regex("\"file\"\\s*:\\s*\"(https?://[^\"]+)\"")
            .find(unescape(srcRaw))?.groupValues?.getOrNull(1) ?: return false
        val label = if (type == "sub") "Japanese · Soft Sub (AniStream)" else "English · Dub (AniStream)"
        return push(callback, label, file, "https://megaplay.buzz/", Qualities.Unknown.value, added)
    }
}
