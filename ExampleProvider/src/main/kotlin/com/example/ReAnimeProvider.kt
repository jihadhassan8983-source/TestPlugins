@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Document

class ReAnimeProvider : MainAPI() {
    override var mainUrl = "https://reanime.to"
    override var name = "ReAnime"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val ua =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to ua,
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to (mainUrl + "/")
    )

    override val mainPage = mainPageOf(
        (mainUrl + "/home") to "Home"
    )

    private fun pickTitle(english: String?, romaji: String?, native: String?): String {
        return when {
            !english.isNullOrBlank() && english != "null" -> english
            !romaji.isNullOrBlank() && romaji != "null" -> romaji
            !native.isNullOrBlank() && native != "null" -> native
            else -> "Unknown"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
        val doc = app.get(request.data, headers = headers).document
        val list = parseHomeCards(doc)
        return newHomePageResponse(request.name, list, false)
    }

    private fun parseHomeCards(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        doc.select("a[href*=/anime/], a[href*=/watch/]").forEach { a ->
            var href = a.attr("abs:href")
            if (href.contains("/watch/")) {
                val slug = href.substringAfter("/watch/").substringBefore("?").substringBefore("/")
                if (slug.isBlank()) return@forEach
                href = mainUrl + "/anime/" + slug
            }
            if (!href.contains("/anime/")) return@forEach
            val slug = href.substringAfter("/anime/").substringBefore("?").substringBefore("/")
            if (slug.isBlank() || !seen.add(slug)) return@forEach
            val title = a.selectFirst("img")?.attr("alt")?.ifBlank { null }
                ?: a.text().trim().ifBlank { null }
                ?: slug.replace("-", " ")
            if (title.isBlank()) return@forEach
            var poster = a.selectFirst("img")?.attr("src")
                ?: a.selectFirst("img")?.attr("data-src")
            if (poster != null && !poster.startsWith("http")) poster = null
            out.add(
                newAnimeSearchResponse(title, mainUrl + "/anime/" + slug, TvType.Anime) {
                    this.posterUrl = poster
                }
            )
        }
        return out.distinctBy { it.url }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val json = try {
            app.get(
                mainUrl + "/api/v1/search?limit=20&q=" + q,
                headers = headers + mapOf("Accept" to "application/json")
            ).text
        } catch (e: Exception) {
            return emptyList()
        }
        val out = ArrayList<SearchResponse>()
        // results objects
        val blocks = Regex("""\{"anime_id":"([^"]+)"""").findAll(json)
        for (m in blocks) {
            val id = m.groupValues[1]
            val chunkStart = m.range.first
            val chunk = json.substring(chunkStart, minOf(json.length, chunkStart + 800))
            val eng = Regex(""""english"\s*:\s*"([^"]*)"""").find(chunk)?.groupValues?.get(1)
            val rom = Regex(""""romaji"\s*:\s*"([^"]*)"""").find(chunk)?.groupValues?.get(1)
            val nat = Regex(""""native"\s*:\s*"([^"]*)"""").find(chunk)?.groupValues?.get(1)
            val title = pickTitle(eng, rom, nat)
            val poster = Regex(""""large"\s*:\s*"(https://[^"]+)"""").find(chunk)?.groupValues?.get(1)
                ?: Regex(""""medium"\s*:\s*"(https://[^"]+)"""").find(chunk)?.groupValues?.get(1)
            out.add(
                newAnimeSearchResponse(title, mainUrl + "/anime/" + id, TvType.Anime) {
                    this.posterUrl = poster
                }
            )
        }
        return out.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        var slug = url.substringAfterLast("/anime/").substringAfterLast("/watch/")
            .substringBefore("?").substringBefore("/").trim('/')
        if (slug.isBlank()) slug = url.trimEnd('/').substringAfterLast("/")

        // Detail + anilist from watch SSR page
        val watchHtml = try {
            app.get(mainUrl + "/watch/" + slug + "?ep=1", headers = headers).text
        } catch (e: Exception) {
            ""
        }
        val title = Regex("""anilist_id:\d+,anime_id:"[^"]+",[\s\S]{0,400}?user_preferred:"([^"]+)"""")
            .find(watchHtml)?.groupValues?.get(1)
            ?: Regex("""<title[^>]*>([^|<]+)""").find(watchHtml)?.groupValues?.get(1)?.trim()
            ?: slug.replace("-", " ")
        val poster = Regex("""og:image["'][^>]+content=["']([^"']+)""")
            .find(watchHtml)?.groupValues?.get(1)
            ?: Regex("""extra_large:"(https://s4\.anilist\.co[^"]+)"""")
                .find(watchHtml)?.groupValues?.get(1)
        val plot = Regex("""description:"((?:\\.|[^"\\])*)"""")
            .find(watchHtml)?.groupValues?.get(1)
            ?.replace("\\u003C", "<")
            ?.replace("\\n", " ")
            ?.replace(Regex("<[^>]+>"), "")
            ?.take(500)
        val anilistId = Regex("""anilist_id:(\d+)""").find(watchHtml)?.groupValues?.get(1)

        // Episodes API
        val epJson = try {
            app.get(
                mainUrl + "/api/v1/anime/" + slug + "/episodes?limit=2000",
                headers = headers + mapOf("Accept" to "application/json")
            ).text
        } catch (e: Exception) {
            "[]"
        }

        val episodes = ArrayList<Episode>()
        Regex(""""episode_number"\s*:\s*(\d+)""").findAll(epJson).forEach { m ->
            val num = m.groupValues[1].toIntOrNull() ?: return@forEach
            val data = slug + "|" + num + "|" + (anilistId ?: "")
            episodes.add(
                newEpisode(data) {
                    this.name = "Episode " + num
                    this.episode = num
                }
            )
        }

        if (episodes.isEmpty()) {
            // fallback: ep links on page
            Regex("""[?&]ep=(\d+)""").findAll(watchHtml).map { it.groupValues[1].toInt() }
                .distinct().sorted().forEach { num ->
                    episodes.add(
                        newEpisode(slug + "|" + num + "|" + (anilistId ?: "")) {
                            this.name = "Episode " + num
                            this.episode = num
                        }
                    )
                }
        }

        if (episodes.isEmpty()) {
            return newMovieLoadResponse(title, mainUrl + "/anime/" + slug, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        val sorted = episodes.sortedWith(compareBy(nullsLast()) { it.episode })
        return newAnimeLoadResponse(title, mainUrl + "/anime/" + slug, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = plot
            addEpisodes(DubStatus.Subbed, sorted)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data = slug|ep|anilistId  OR full watch URL
        var slug = ""
        var ep = 1
        var anilistId = ""

        if (data.contains("|")) {
            val p = data.split("|")
            slug = p.getOrNull(0) ?: ""
            ep = p.getOrNull(1)?.toIntOrNull() ?: 1
            anilistId = p.getOrNull(2) ?: ""
        } else if (data.contains("/watch/")) {
            slug = data.substringAfter("/watch/").substringBefore("?").substringBefore("/")
            ep = Regex("""[?&]ep=(\d+)""").find(data)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        }

        if (anilistId.isBlank() && slug.isNotBlank()) {
            try {
                val html = app.get(
                    mainUrl + "/watch/" + slug + "?ep=" + ep,
                    headers = headers
                ).text
                anilistId = Regex("""anilist_id:(\d+)""").find(html)?.groupValues?.get(1) ?: ""
            } catch (e: Exception) {
            }
        }
        if (anilistId.isBlank()) return false

        // Public servers list
        val flixJson = try {
            app.get(
                mainUrl + "/api/flix/" + anilistId + "/" + ep,
                headers = headers + mapOf(
                    "Accept" to "application/json",
                    "Referer" to (mainUrl + "/watch/" + slug + "?ep=" + ep)
                )
            ).text
        } catch (e: Exception) {
            return false
        }
        if (!flixJson.contains("dataLink")) return false

        var found = false
        val links = Regex(""""dataLink"\s*:\s*"(https://[^"]+)"""")
            .findAll(flixJson).map { it.groupValues[1].replace("\\u0026", "&") }.distinct().toList()
        val types = Regex(""""dataType"\s*:\s*"([^"]+)"""")
            .findAll(flixJson).map { it.groupValues[1] }.toList()

        for ((i, link) in links.withIndex()) {
            val label = (types.getOrNull(i) ?: "sub").uppercase()
            try {
                val emb = app.get(
                    link,
                    headers = mapOf(
                        "User-Agent" to ua,
                        "Referer" to (mainUrl + "/"),
                        "Accept" to "*/*"
                    )
                ).text

                // Prefer any clear m3u8 (rare without decrypt)
                val m3u8s = Regex("""https?://[^"'\\s]+\.m3u8[^"'\\s]*""")
                    .findAll(emb).map { it.value.replace("\\u0026", "&") }.distinct().toList()
                for (src in m3u8s) {
                    callback.invoke(
                        ExtractorLink(
                            name,
                            "ReAnime " + label,
                            src,
                            "https://flixcloud.cc/",
                            Qualities.Unknown.value,
                            true
                        )
                    )
                    found = true
                }

                // video_id present — stream needs flixcloud client decrypt (anti-bot)
                // TODO: flixcloud.cc uses AES-CBC + WASM obfuscation for m3u8 token.
                // Cannot extract playable HLS without implementing that client crypto.
                val videoId = Regex("""video_id:"([0-9a-f-]{36})"""")
                    .find(emb)?.groupValues?.get(1)
                if (!found && videoId != null) {
                    // No public direct URL without token — skip inventing links
                }
            } catch (e: Exception) {
                continue
            }
        }

        return found
    }
                                    }
