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
        "Accept" to "text/html,*/*",
        "Referer" to "$mainUrl/"
    )

    private fun jsonH() = mapOf(
        "User-Agent" to ua,
        "Accept" to "application/json,*/*",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    private fun unesc(s: String) = s.replace("\\/", "/").replace("\\\"", "\"")

    override val mainPage = mainPageOf(
        "$mainUrl/home" to "Home",
        "$mainUrl/trending" to "Trending",
        "$mainUrl/latest-episode" to "Latest Episodes",
        "$mainUrl/most-watched" to "Most Watched"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
        return try {
            newHomePageResponse(request.name, parseCards(app.get(request.data, headers = htmlH()).text), false)
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
            if (title.isBlank()) title = slug.replace("-", " ")
            if (title.length < 2) continue
            var poster = img?.attr("abs:src") ?: img?.attr("src")
            val id = slug.substringAfterLast("-")
            if (id.all { it.isDigit() } && id.length >= 2) {
                if (poster.isNullOrBlank() || poster.contains("logo", true))
                    poster = "https://img.anili.st/media/$id"
            }
            out.add(newAnimeSearchResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
                this.posterUrl = poster
            })
        }
        return out.distinctBy { it.url }
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
            Regex("/anime/([a-z0-9-]+-[0-9]+)").find(html)?.let { return it.groupValues[1] }
        } catch (_: Exception) {
        }
        return slug
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = resolveSlug(url)
        val ep1 = unesc(app.get("$mainUrl/api/anime/$slug/episodes/1", headers = jsonH()).text)
        if (!ep1.contains("\"anime\"")) throw ErrorLoadingException("API failed: $slug")

        val title = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"").find(ep1)?.groupValues?.get(1)
            ?: slug.replace("-", " ")
        val anilistId = Regex("\"anilistId\"\\s*:\\s*([0-9]+)")
            .find(ep1)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val poster = if (anilistId > 0) "https://img.anili.st/media/$anilistId" else null

        val epNums = LinkedHashSet<Int>()
        epNums.add(1)
        try {
            var page = 1
            var totalPages = 1
            while (page <= totalPages && page <= 40) {
                val range = app.get(
                    "$mainUrl/api/anime/$slug/episodes-range?page=$page&lang=ALL&pageSize=50",
                    headers = jsonH()
                ).text
                Regex("\"totalPages\"\\s*:\\s*([0-9]+)").find(range)?.groupValues?.get(1)
                    ?.toIntOrNull()?.let { totalPages = it }
                Regex("\"number\"\\s*:\\s*([0-9]+)").findAll(range).forEach {
                    it.groupValues[1].toIntOrNull()?.let { n -> if (n > 0) epNums.add(n) }
                }
                page++
            }
        } catch (_: Exception) {
        }
        if (epNums.size <= 1) {
            for (n in 2..30) {
                try {
                    val t = app.get("$mainUrl/api/anime/$slug/episodes/$n", headers = jsonH()).text
                    if (t.contains("\"number\"")) epNums.add(n) else break
                } catch (_: Exception) {
                    break
                }
            }
        } else {
            val max = epNums.maxOrNull() ?: 1
            if (max <= 200) for (i in 1..max) epNums.add(i)
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
            this.plot = "Episodes: ${epNums.size}"
            addEpisodes(DubStatus.Subbed, episodes)
            addEpisodes(DubStatus.Dubbed, episodes)
        }
    }

    private fun add(
        callback: (ExtractorLink) -> Unit,
        label: String,
        url: String,
        referer: String,
        added: HashSet<String>,
        quality: Int = Qualities.Unknown.value
    ): Boolean {
        val u = unesc(url).trim()
        if (!u.startsWith("http")) return false
        if (!added.add(u)) return false
        callback.invoke(
            ExtractorLink(name, label, u, referer, quality, u.contains(".m3u8"))
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
            ""
        }
        val text = unesc(raw)
        var found = false
        val added = HashSet<String>()

        // ========== 1) ALL direct m3u8 in API (bato + pahe) ==========
        Regex("https?://[^\\s\"'\\\\<>]+\\.m3u8[^\\s\"'\\\\<>]*").findAll(text).forEach { m ->
            val u = m.value
            val label = when {
                "vault-" in u || "uwucdn" in u || "owocdn" in u -> "Pahe HLS"
                "anvod" in u || "anivid" in u || "anixl" in u -> "Soft/Dub HLS"
                else -> "HLS"
            }
            if (add(callback, label, u, mainUrl, added)) found = true
        }

        // ========== 2) Zephyrflick — EVERY video id in response ==========
        Regex("https?://play\\.zephyrflick\\.top/video/([a-f0-9]+)").findAll(text).forEach { m ->
            val id = m.groupValues[1]
            if (zf(id, callback, added, subtitleCallback)) found = true
        }
        Regex("zephyrflick\\.top/video/([a-f0-9]+)").findAll(text).forEach { m ->
            val id = m.groupValues[1]
            if (zf(id, callback, added, subtitleCallback)) found = true
        }

        // ========== 3) Hindi / Tamil / Telugu / etc (short.icu) ==========
        // Map name near url when possible
        val langServers = listOf(
            "Hindi", "Tamil", "Telugu", "Malayalam", "Kannada", "English", "Japanese", "Bengali"
        )
        for (lang in langServers) {
            // "name":"Hindi" ... "url":"https://short.icu/xxx"
            val re = Regex(
                "\"name\"\\s*:\\s*\"$lang\"[\\s\\S]{0,400}?\"url\"\\s*:\\s*\"(https?://[^\"]+)\""
            )
            re.find(text)?.groupValues?.getOrNull(1)?.let { u ->
                if (langLink(lang, u, callback, added, subtitleCallback)) found = true
            }
        }
        // Any remaining short.icu
        Regex("https?://short\\.icu/([A-Za-z0-9_-]+)").findAll(text).forEach { m ->
            if (langLink("Abyess", m.value, callback, added, subtitleCallback)) found = true
        }

        // ========== 4) AniStream backup ==========
        val anilistId = Regex("\"anilistId\"\\s*:\\s*([0-9]+)")
            .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: anilistFromData
        if (anilistId > 0) {
            for (type in listOf("sub", "dub")) {
                if (mega(anilistId, epNum, type, callback, added)) found = true
            }
        }

        return found
    }

    private suspend fun zf(
        videoId: String,
        callback: (ExtractorLink) -> Unit,
        added: HashSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val bases = listOf("https://play.zephyrflick.top", "https://zephyrflick.top")
        for (base in bases) {
            val ref = "$base/video/$videoId"
            val api = "$base/player/index.php?data=$videoId&do=getVideo"
            val headers = mapOf(
                "User-Agent" to ua,
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to ref,
                "Origin" to base,
                "Accept" to "*/*"
            )
            var body = ""
            try {
                body = app.post(api, headers = headers).text
            } catch (_: Exception) {
            }
            if (body.isBlank() || !body.contains("video")) {
                try {
                    body = app.get(api, headers = headers).text
                } catch (_: Exception) {
                }
            }
            if (body.isBlank() || !body.contains("video")) {
                try {
                    body = app.post(
                        "$base/player/index.php",
                        headers = headers + mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                        data = mapOf("data" to videoId, "do" to "getVideo")
                    ).text
                } catch (_: Exception) {
                }
            }
            val json = unesc(body)
            var src = Regex("\"videoSource\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.getOrNull(1)
            if (src.isNullOrBlank()) {
                src = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.getOrNull(1)
            }
            if (src.isNullOrBlank()) {
                src = Regex("https?://[^\\s\"']+\\.m3u8[^\\s\"']*").find(json)?.value
            }
            if (src.isNullOrBlank()) continue
            src = unesc(src)

            if (add(callback, "Zephyrflick Multi", src, base, added)) {
                // Audio tracks
                try {
                    val master = unesc(app.get(src, headers = mapOf("User-Agent" to ua, "Referer" to base)).text)
                    Regex("""#EXT-X-MEDIA:TYPE=AUDIO[^\n]*NAME="([^"]+)"[^\n]*URI="([^"]+)"""").findAll(master).forEach { am ->
                        var uri = am.groupValues[2]
                        if (uri.startsWith("/")) {
                            uri = (Regex("https?://[^/]+").find(src)?.value ?: base) + uri
                        } else if (!uri.startsWith("http")) {
                            uri = src.substringBeforeLast("/") + "/" + uri
                        }
                        add(callback, "Zephyrflick ${am.groupValues[1]}", uri, base, added)
                    }
                } catch (_: Exception) {
                }
                return true
            }
        }
        try {
            if (loadExtractor("https://play.zephyrflick.top/video/$videoId", mainUrl, subtitleCallback, callback))
                return true
        } catch (_: Exception) {
        }
        return false
    }

    private suspend fun langLink(
        label: String,
        url: String,
        callback: (ExtractorLink) -> Unit,
        added: HashSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val code = url.substringAfterLast("/").substringAfter("v=").substringBefore("&").trim()
        val tries = listOf(
            url,
            "https://short.icu/$code",
            "https://abysscdn.com/?v=$code",
            "https://abyss.to/?v=$code",
            "https://abyss.to/e/$code"
        )
        for (u in tries) {
            try {
                if (loadExtractor(u, mainUrl, subtitleCallback, callback)) return true
            } catch (_: Exception) {
            }
        }
        for (u in tries) {
            try {
                val body = unesc(app.get(u, headers = htmlH()).text)
                Regex("https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*").findAll(body).forEach { m ->
                    add(callback, label, m.value, u, added)
                }
                Regex("\"file\"\\s*:\\s*\"(https?://[^\"]+)\"").findAll(body).forEach { m ->
                    add(callback, label, m.groupValues[1], u, added)
                }
                if (added.any { it.contains("m3u8") }) return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    private suspend fun mega(
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
        val playerId = Regex("data-id=\"([0-9]+)\"").find(html)?.groupValues?.getOrNull(1) ?: return false
        val srcRaw = try {
            app.get(
                "https://megaplay.buzz/stream/getSources?id=$playerId",
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to pageUrl,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json"
                )
            ).text
        } catch (_: Exception) {
            return false
        }
        val file = Regex("\"file\"\\s*:\\s*\"(https?://[^\"]+)\"")
            .find(unesc(srcRaw))?.groupValues?.getOrNull(1) ?: return false
        val label = if (type == "sub") "Japanese Soft Sub (AniStream)" else "English Dub (AniStream)"
        return add(callback, label, file, "https://megaplay.buzz/", added)
    }
}
