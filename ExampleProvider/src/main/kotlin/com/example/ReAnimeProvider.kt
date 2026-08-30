@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

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
        "Accept" to "application/json",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to (mainUrl + "/")
    )

    // data = section key in /api/v1/home JSON
    override val mainPage = mainPageOf(
        "latest_aired" to "Latest Episodes",
        "new_on_site" to "New on Site",
        "trending" to "Trending",
        "upcoming" to "Upcoming"
    )

    private fun pickTitle(english: String?, romaji: String?, native: String?, preferred: String?): String {
        return when {
            !preferred.isNullOrBlank() && preferred != "null" -> preferred
            !english.isNullOrBlank() && english != "null" -> english
            !romaji.isNullOrBlank() && romaji != "null" -> romaji
            !native.isNullOrBlank() && native != "null" -> native
            else -> "Unknown"
        }
    }

    private fun parseAnimeList(json: String): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        // each object starts with "anime_id":"slug"
        val matcher = Regex(""""anime_id"\s*:\s*"([^"]+)"""").findAll(json)
        for (m in matcher) {
            val id = m.groupValues[1]
            if (!seen.add(id)) continue
            val start = m.range.first
            val chunk = json.substring(start, minOf(json.length, start + 1200))

            val eng = Regex(""""english"\s*:\s*"([^"]*)"""").find(chunk)?.groupValues?.get(1)
            val rom = Regex(""""romaji"\s*:\s*"([^"]*)"""").find(chunk)?.groupValues?.get(1)
            val nat = Regex(""""native"\s*:\s*"([^"]*)"""").find(chunk)?.groupValues?.get(1)
            val pref = Regex(""""user_preferred"\s*:\s*"([^"]*)"""").find(chunk)?.groupValues?.get(1)
            val title = pickTitle(eng, rom, nat, pref)
            if (title == "Unknown") continue

            val poster = Regex(""""extra_large"\s*:\s*"(https://[^"]+)"""").find(chunk)?.groupValues?.get(1)
                ?: Regex(""""large"\s*:\s*"(https://[^"]+)"""").find(chunk)?.groupValues?.get(1)
                ?: Regex(""""medium"\s*:\s*"(https://[^"]+)"""").find(chunk)?.groupValues?.get(1)

            out.add(
                newAnimeSearchResponse(title, mainUrl + "/anime/" + id, TvType.Anime) {
                    this.posterUrl = poster
                }
            )
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // home API is a single payload (no pagination for sections)
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)

        val json = try {
            app.get(mainUrl + "/api/v1/home", headers = headers).text
        } catch (e: Exception) {
            return newHomePageResponse(request.name, emptyList(), false)
        }

        val key = request.data // latest_aired | new_on_site | trending | upcoming
        // extract that array from JSON: "key":[ ... ]
        val sectionRegex = Regex(""""$key"\s*:\s*(\[[\s\S]*?\])\s*,\s*"""")
        val sectionMatch = sectionRegex.find(json)
        val sectionJson = if (sectionMatch != null) {
            sectionMatch.groupValues[1]
        } else {
            // last key may not have trailing ,"
            val alt = Regex(""""$key"\s*:\s*(\[[\s\S]*?\])\s*[,}]""").find(json)
            alt?.groupValues?.get(1) ?: "[]"
        }

        val list = parseAnimeList(sectionJson)
        return newHomePageResponse(request.name, list, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val json = try {
            app.get(
                mainUrl + "/api/v1/search?limit=20&q=" + q,
                headers = headers
            ).text
        } catch (e: Exception) {
            return emptyList()
        }
        return parseAnimeList(json)
    }

    override suspend fun load(url: String): LoadResponse {
        var slug = url.substringAfterLast("/anime/").substringAfterLast("/watch/")
            .substringBefore("?").substringBefore("/").trim('/')
        if (slug.isBlank()) slug = url.trimEnd('/').substringAfterLast("/")

        val watchHtml = try {
            app.get(
                mainUrl + "/watch/" + slug + "?ep=1",
                headers = mapOf(
                    "User-Agent" to ua,
                    "Accept" to "*/*",
                    "Referer" to (mainUrl + "/")
                )
            ).text
        } catch (e: Exception) {
            ""
        }

        val title = Regex("""user_preferred:"([^"]+)"""")
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

        val epJson = try {
            app.get(
                mainUrl + "/api/v1/anime/" + slug + "/episodes?limit=2000",
                headers = headers
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
                    headers = mapOf("User-Agent" to ua, "Referer" to (mainUrl + "/"))
                ).text
                anilistId = Regex("""anilist_id:(\d+)""").find(html)?.groupValues?.get(1) ?: ""
            } catch (e: Exception) {
            }
        }
        if (anilistId.isBlank()) return false

        val flixJson = try {
            app.get(
                mainUrl + "/api/flix/" + anilistId + "/" + ep,
                headers = headers + mapOf(
                    "Referer" to (mainUrl + "/watch/" + slug + "?ep=" + ep)
                )
            ).text
        } catch (e: Exception) {
            return false
        }
        if (!flixJson.contains("dataLink")) return false

        var found = false
        val links = Regex(""""dataLink"\s*:\s*"(https://[^"]+)"""")
            .findAll(flixJson).map { it.groupValues[1] }.distinct().toList()
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
                val m3u8s = Regex("""https?://[^"'\\s]+\.m3u8[^"'\\s]*""")
                    .findAll(emb).map { it.value }.distinct().toList()
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
            } catch (e: Exception) {
                continue
            }
        }
        return found
    }
                          }
