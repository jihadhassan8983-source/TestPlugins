@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URLEncoder

class HiAnimeProvider : MainAPI() {
    override var mainUrl = "https://hianime.at"
    override var name = "HiAnime"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val ua =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val zokoKey = "otaku-embed-v1"

    private fun hdr(referer: String = mainUrl + "/"): Map<String, String> {
        return mapOf(
            "User-Agent" to ua,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to referer
        )
    }

    private fun xhrHdr(referer: String): Map<String, String> {
        return mapOf(
            "User-Agent" to ua,
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to referer
        )
    }

    private fun abs(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var u = url.trim().replace("&amp;", "&")
        if (u.startsWith("//")) u = "https:" + u
        if (u.startsWith("http")) return u
        return mainUrl.trimEnd('/') + "/" + u.trimStart('/')
    }

    private fun animeIdFromUrl(url: String): String? {
        return Regex("""-(\d+)(?:\?|$)""").find(url)?.groupValues?.getOrNull(1)
            ?: Regex("""/watch/[^/]*-(\d+)""").find(url)?.groupValues?.getOrNull(1)
    }

    private fun pickImg(el: Element): String? {
        for (img in el.select("img")) {
            for (x in listOf(
                img.attr("data-src"),
                img.attr("data-lazy-src"),
                img.attr("src")
            )) {
                val u = abs(x) ?: continue
                if (u.startsWith("http") && !u.contains("data:image") && !u.endsWith(".svg")) {
                    return u
                }
            }
        }
        return null
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        // Primary: flw-item cards
        for (item in doc.select("div.flw-item, div.film_list-wrap .flw-item")) {
            val a = item.selectFirst("a[href*=/watch/]") ?: continue
            val href = abs(a.attr("abs:href").ifBlank { a.attr("href") }) ?: continue
            if (!seen.add(href)) continue
            val nameEl = item.selectFirst(".film-name a, .film-name, h3 a, h3")
            val title = nameEl?.text()?.trim().orEmpty()
                .ifBlank { a.attr("title").trim() }
            if (title.isBlank()) continue
            val poster = pickImg(item)
            out += newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }

        // Fallback: any watch link with poster nearby
        if (out.isEmpty()) {
            for (a in doc.select("a[href*=/watch/]")) {
                val href = abs(a.attr("abs:href").ifBlank { a.attr("href") }) ?: continue
                if (!seen.add(href)) continue
                val title = a.attr("title").ifBlank { a.text() }.trim()
                if (title.length < 2) continue
                val parent = a.parents().firstOrNull { it.selectFirst("img") != null } ?: a.parent()
                out += newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = parent?.let { pickImg(it) }
                }
            }
        }
        return out
    }

    override val mainPage = mainPageOf(
        mainUrl + "/home" to "Home",
        mainUrl + "/top-airing" to "Top Airing",
        mainUrl + "/most-popular" to "Most Popular",
        mainUrl + "/tv" to "TV Series",
        mainUrl + "/movie" to "Movies",
        mainUrl + "/recently-updated" to "Recently Updated",
        mainUrl + "/latest-completed" to "Completed",
        mainUrl + "/az-list" to "A-Z List"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            page <= 1 -> request.data
            request.data.contains("?") -> request.data + "&page=" + page
            else -> request.data.trimEnd('/') + "?page=" + page
        }
        val doc = app.get(url, headers = hdr()).document
        val list = parseCards(doc)
        return newHomePageResponse(request.name, list, list.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val doc = app.get(
            mainUrl + "/search?keyword=" + URLEncoder.encode(q, "UTF-8"),
            headers = hdr()
        ).document
        return parseCards(doc)
    }

    private suspend fun fetchEpisodeList(animeId: String, referer: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        try {
            val body = app.get(
                mainUrl + "/api/theme/episode/list/" + animeId,
                headers = xhrHdr(referer)
            ).text
            val html = JSONObject(body).optString("html")
            if (html.isBlank()) return out
            val doc = Jsoup.parse(html)
            for (a in doc.select("a.ep-item, a.ssl-item.ep-item")) {
                val num = a.attr("data-number").toIntOrNull()
                    ?: a.selectFirst(".ssli-order")?.text()?.toIntOrNull()
                    ?: continue
                val epId = a.attr("data-id").ifBlank {
                    Regex("""[?&]ep=(\d+)""").find(a.attr("href"))?.groupValues?.getOrNull(1)
                } ?: continue
                out.add(num to epId)
            }
            // regex fallback
            if (out.isEmpty()) {
                for (m in Regex(
                    """data-number=["'](\d+)["'][^>]*data-id=["'](\d+)["']""",
                    RegexOption.IGNORE_CASE
                ).findAll(html)) {
                    out.add(m.groupValues[1].toInt() to m.groupValues[2])
                }
                if (out.isEmpty()) {
                    for (m in Regex(
                        """data-id=["'](\d+)["'][^>]*data-number=["'](\d+)["']""",
                        RegexOption.IGNORE_CASE
                    ).findAll(html)) {
                        out.add(m.groupValues[2].toInt() to m.groupValues[1])
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out.distinctBy { it.first }.sortedBy { it.first }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = hdr()).document
        val animeId = animeIdFromUrl(url)
            ?: doc.selectFirst("#ani_detail, [data-anime-id]")?.attr("data-anime-id")
            ?: doc.selectFirst("meta[name=hi-anime-id]")?.attr("content")
            ?: ""

        val title = doc.selectFirst("h2.film-name, .film-name.dynamic-name, h1, .anisc-detail h2")
            ?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore("|")?.trim()
            ?: "Unknown"

        val poster = abs(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: pickImg(doc.selectFirst(".film-poster, .anisc-poster") ?: doc.body())

        val plot = doc.selectFirst(".film-description .text, .shorting, .anisc-info .item-content")
            ?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

        val tags = ArrayList<String>()
        for (a in doc.select(".anisc-info a[href*=/genre/], .item-list a[href*=genre]")) {
            val t = a.text().trim()
            if (t.length in 2..24) tags.add(t)
        }

        // Actors / characters if present
        val actors = ArrayList<ActorData>()
        for (el in doc.select(".bac-list-wrap .bac-item, .character-item, .block_area-actors .item")) {
            val name = el.selectFirst(".ani-name, .name, a")?.text()?.trim() ?: continue
            val img = el.selectFirst("img")?.let {
                abs(it.attr("data-src").ifBlank { it.attr("src") })
            }
            actors.add(ActorData(Actor(name, img)))
        }

        val year = Regex("""\b(20\d{2}|19\d{2})\b""").find(
            doc.selectFirst(".anisc-info, .film-stats")?.text().orEmpty()
        )?.value?.toIntOrNull()

        val episodes = ArrayList<Episode>()
        if (animeId.isNotBlank()) {
            val list = fetchEpisodeList(animeId, url)
            for ((num, epId) in list) {
                // data = animeId|episodeId|episodeNum
                val data = animeId + "|" + epId + "|" + num
                episodes += newEpisode(data) {
                    this.name = "Episode " + num
                    this.episode = num
                    this.data = data
                }
            }
        }

        // Fallback single ep from URL
        if (episodes.isEmpty()) {
            val epFromUrl = Regex("""[?&]ep=(\d+)""").find(url)?.groupValues?.getOrNull(1)
            val data = (animeId.ifBlank { "0" }) + "|" + (epFromUrl ?: "0") + "|1"
            episodes += newEpisode(data) {
                this.name = "Episode 1"
                this.episode = 1
                this.data = data
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags.distinct()
            this.actors = actors.take(20)
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    /**
     * Decode zokoanime window.__P payload (XOR + base64)
     */
    private fun deobfuscateZoko(blob: String): JSONObject? {
        return try {
            val raw = Base64.decode(blob, Base64.DEFAULT)
            val key = zokoKey.toByteArray(Charsets.UTF_8)
            val out = ByteArray(raw.size)
            for (i in raw.indices) {
                out[i] = (raw[i].toInt() xor key[i % key.size].toInt()).toByte()
            }
            // latin1 bytes interpreted as UTF-8 (matches JS decodeURIComponent(escape(...)))
            val text = String(out, Charsets.ISO_8859_1)
            val utf8 = String(text.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
            JSONObject(utf8)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun resolveZokoStream(streamUrl: String): Pair<String, List<Pair<String, String>>>? {
        return try {
            val html = app.get(
                streamUrl,
                headers = hdr(mainUrl + "/")
            ).text
            val blob = Regex("""window\.__P\s*=\s*"([^"]+)"""").find(html)
                ?.groupValues?.getOrNull(1)
                ?: return null
            val obj = deobfuscateZoko(blob) ?: return null
            val src = obj.optString("src")
            if (src.isBlank()) return null
            val subs = ArrayList<Pair<String, String>>()
            val arr = obj.optJSONArray("subtitles")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    val label = s.optString("label").ifBlank { s.optString("lang") }
                    val subUrl = s.optString("src")
                    if (subUrl.isNotBlank()) subs.add(label to subUrl)
                }
            }
            src to subs
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
        val parts = data.split("|")
        if (parts.size < 2) return false
        val animeId = parts[0]
        val epId = parts[1]
        if (epId.isBlank() || epId == "0") return false

        val referer = mainUrl + "/watch/anime-" + animeId + "?ep=" + epId
        var found = false

        try {
            val body = app.get(
                mainUrl + "/api/theme/episode/servers?episodeId=" + epId,
                headers = xhrHdr(referer)
            ).text
            val html = JSONObject(body).optString("html")
            if (html.isBlank()) return false

            val doc = Jsoup.parse(html)
            val servers = doc.select("div.server-item[data-hash]")
            for (item in servers) {
                val type = item.attr("data-type").ifBlank { "sub" }
                val nameServer = item.attr("data-server-name").ifBlank { "HD" }
                val hash = item.attr("data-hash")
                if (hash.isBlank()) continue
                val streamUrl = try {
                    String(Base64.decode(hash, Base64.DEFAULT))
                } catch (_: Exception) {
                    continue
                }
                if (!streamUrl.startsWith("http")) continue

                // Prefer zokoanime (works); megaplay often 410
                val resolved = resolveZokoStream(streamUrl)
                if (resolved != null) {
                    val (src, subs) = resolved
                    for ((label, subUrl) in subs) {
                        try {
                            subtitleCallback(SubtitleFile(label, subUrl))
                        } catch (_: Exception) {
                        }
                    }
                    callback(
                        ExtractorLink(
                            name,
                            nameServer + " " + type.uppercase(),
                            src,
                            "https://zokoanime.video/",
                            Qualities.P1080.value,
                            true
                        )
                    )
                    found = true
                } else {
                    // Try load as embed page might still work via alternate host
                    try {
                        val page = app.get(streamUrl, headers = hdr(referer)).text
                        val m3u8 = Regex(
                            """(https?://[^"'\s]+\.m3u8[^"'\s]*)""",
                            RegexOption.IGNORE_CASE
                        ).find(page)?.groupValues?.getOrNull(1)
                        if (m3u8 != null) {
                            callback(
                                ExtractorLink(
                                    name,
                                    nameServer + " " + type.uppercase(),
                                    m3u8,
                                    streamUrl,
                                    Qualities.P1080.value,
                                    true
                                )
                            )
                            found = true
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }

        return found
    }
}
