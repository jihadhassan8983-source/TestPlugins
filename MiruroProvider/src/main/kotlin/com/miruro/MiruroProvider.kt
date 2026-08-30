package com.miruro

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class MiruroProvider : MainAPI() {
    override var mainUrl = "https://miruro.ro"
    override var name = "Miruro"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        private const val ANILIST_API = "https://graphql.anilist.co"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "TRENDING_DESC" to "Trending Now",
        "POPULARITY_DESC" to "All Time Popular",
        "SCORE_DESC" to "Top Rated",
        "UPDATED_AT_DESC" to "Recently Updated"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val sort = request.data
        val query = """
            query (${'$'}page: Int, ${'$'}sort: [MediaSort]) {
                Page(page: ${'$'}page, perPage: 20) {
                    media(sort: ${'$'}sort, type: ANIME) {
                        id
                        title {
                            romaji
                            english
                            userPreferred
                        }
                        coverImage {
                            extraLarge
                            large
                        }
                        format
                        seasonYear
                        averageScore
                    }
                }
            }
        """.trimIndent()

        val payload = mapOf(
            "query" to query,
            "variables" to mapOf(
                "page" to page,
                "sort" to listOf(sort)
            )
        )

        val response = app.post(
            ANILIST_API,
            json = payload,
            headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json")
        ).parsedSafe<AniListResponse>()

        val animeList = response?.data?.page?.media?.mapNotNull { media ->
            val id = media.id ?: return@mapNotNull null
            val title = media.title?.english ?: media.title?.romaji ?: media.title?.userPreferred ?: "Anime"
            val poster = media.coverImage?.extraLarge ?: media.coverImage?.large
            val type = when (media.format) {
                "MOVIE" -> TvType.AnimeMovie
                "OVA" -> TvType.OVA
                else -> TvType.Anime
            }

            newAnimeSearchResponse(title, "$mainUrl/watch?id=$id", type) {
                this.posterUrl = poster
                this.year = media.seasonYear
                addDubStatus(dubExist = true, subExist = true)
            }
        } ?: emptyList()

        return newHomePageResponse(request.name, animeList, hasNext = animeList.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val gqlQuery = """
            query (${'$'}search: String) {
                Page(page: 1, perPage: 20) {
                    media(search: ${'$'}search, type: ANIME, sort: SEARCH_MATCH) {
                        id
                        title {
                            romaji
                            english
                            userPreferred
                        }
                        coverImage {
                            extraLarge
                            large
                        }
                        format
                        seasonYear
                        averageScore
                    }
                }
            }
        """.trimIndent()

        val payload = mapOf(
            "query" to gqlQuery,
            "variables" to mapOf("search" to query)
        )

        val response = app.post(
            ANILIST_API,
            json = payload,
            headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json")
        ).parsedSafe<AniListResponse>()

        return response?.data?.page?.media?.mapNotNull { media ->
            val id = media.id ?: return@mapNotNull null
            val title = media.title?.english ?: media.title?.romaji ?: media.title?.userPreferred ?: "Anime"
            val poster = media.coverImage?.extraLarge ?: media.coverImage?.large
            val type = when (media.format) {
                "MOVIE" -> TvType.AnimeMovie
                "OVA" -> TvType.OVA
                else -> TvType.Anime
            }

            newAnimeSearchResponse(title, "$mainUrl/watch?id=$id", type) {
                this.posterUrl = poster
                this.year = media.seasonYear
                addDubStatus(dubExist = true, subExist = true)
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = Regex("""id=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null

        val gqlQuery = """
            query (${'$'}id: Int) {
                Media(id: ${'$'}id, type: ANIME) {
                    id
                    idMal
                    title {
                        romaji
                        english
                        native
                        userPreferred
                    }
                    coverImage {
                        extraLarge
                        large
                    }
                    bannerImage
                    description(asHtml: false)
                    format
                    status
                    episodes
                    seasonYear
                    averageScore
                    genres
                    tags {
                        name
                    }
                    nextAiringEpisode {
                        episode
                    }
                }
            }
        """.trimIndent()

        val payload = mapOf(
            "query" to gqlQuery,
            "variables" to mapOf("id" to id)
        )

        val response = app.post(
            ANILIST_API,
            json = payload,
            headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json")
        ).parsedSafe<AniListResponse>()

        val media = response?.data?.media ?: return null
        val title = media.title?.english ?: media.title?.romaji ?: media.title?.userPreferred ?: "Anime"
        val poster = media.coverImage?.extraLarge ?: media.coverImage?.large
        val totalEpisodes = media.episodes ?: media.nextAiringEpisode?.episode?.minus(1) ?: 1
        val type = when (media.format) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA" -> TvType.OVA
            else -> TvType.Anime
        }

        val subEpisodes = (1..totalEpisodes).map { epNum ->
            newEpisode(data = "$id|$epNum|sub") {
                this.name = "Episode $epNum"
                this.episode = epNum
                this.posterUrl = poster
            }
        }

        val dubEpisodes = (1..totalEpisodes).map { epNum ->
            newEpisode(data = "$id|$epNum|dub") {
                this.name = "Episode $epNum (Dub)"
                this.episode = epNum
                this.posterUrl = poster
            }
        }

        val showStatus = when (media.status) {
            "FINISHED" -> ShowStatus.Completed
            "RELEASING" -> ShowStatus.Ongoing
            else -> ShowStatus.Other
        }

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = poster
            this.backgroundPosterUrl = media.bannerImage
            this.year = media.seasonYear
            this.plot = media.description
            this.tags = media.genres ?: emptyList()
            this.rating = media.averageScore
            this.showStatus = showStatus
            media.idMal?.let { addMalId(it) }
            addAniListId(id)
            addEpisodes(DubStatus.Subbed, subEpisodes)
            addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        val id = parts.getOrNull(0) ?: return false
        val ep = parts.getOrNull(1) ?: "1"
        val type = parts.getOrNull(2) ?: "sub"

        val streamEndpoints = listOf(
            "https://api.aniheist.com/api/stream?anime_id=$id&episode=$ep",
            "https://anivexa-api-nine.vercel.app/watch/reanime/$id/$type/reanime-$ep",
            "https://anivexa-api-nine.vercel.app/watch/anizone/$id/$type/anizone-$ep",
            "https://anivexa-api-nine.vercel.app/watch/anikoto/$id/$type/anikoto-$ep"
        )

        var linkFound = false

        for (endpoint in streamEndpoints) {
            try {
                val response = app.get(
                    endpoint,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "$mainUrl/"
                    ),
                    timeout = 10
                )

                if (response.isSuccessful) {
                    val body = response.text
                    val json = parseJson<Map<String, Any>>(body)

                    val videoUrl = (json["video_url"] as? String)
                        ?: (json["url"] as? String)
                        ?: ((json["data"] as? Map<*, *>)?.get("video_url") as? String)
                        ?: ((json["results"] as? Map<*, *>)?.get("url") as? String)

                    val referer = (json["headers"] as? Map<*, *>)?.get("Referer") as? String
                        ?: ((json["data"] as? Map<*, *>)?.get("headers") as? Map<*, *>)?.get("Referer") as? String
                        ?: "$mainUrl/"

                    if (!videoUrl.isNullOrEmpty()) {
                        val isM3u8 = videoUrl.contains(".m3u8") || (json["format"] as? String) == "hls"

                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = "$name Server",
                                url = videoUrl,
                                referer = referer,
                                quality = Qualities.P1080.value,
                                isM3u8 = isM3u8,
                                headers = mapOf(
                                    "Referer" to referer,
                                    "User-Agent" to USER_AGENT
                                )
                            )
                        )
                        linkFound = true
                    }

                    val subtitles = (json["subtitles"] as? List<*>)
                        ?: ((json["data"] as? Map<*, *>)?.get("subtitles") as? List<*>)

                    subtitles?.forEach { subItem ->
                        if (subItem is Map<*, *>) {
                            val subUrl = subItem["url"] as? String ?: subItem["file"] as? String
                            val subLang = subItem["lang"] as? String ?: subItem["label"] as? String ?: "English"
                            if (!subUrl.isNullOrEmpty()) {
                                subtitleCallback.invoke(
                                    SubtitleFile(subLang, subUrl)
                                )
                            }
                        }
                    }

                    if (linkFound) break
                }
            } catch (_: Exception) {
            }
        }

        return linkFound
    }

    data class AniListResponse(
        @JsonProperty("data") val data: AniListData? = null
    )

    data class AniListData(
        @JsonProperty("Page") val page: AniListPage? = null,
        @JsonProperty("Media") val media: AniListMedia? = null
    )

    data class AniListPage(
        @JsonProperty("media") val media: List<AniListMedia>? = null
    )

    data class AniListMedia(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("idMal") val idMal: Int? = null,
        @JsonProperty("title") val title: AniListTitle? = null,
        @JsonProperty("coverImage") val coverImage: AniListCoverImage? = null,
        @JsonProperty("bannerImage") val bannerImage: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("episodes") val episodes: Int? = null,
        @JsonProperty("seasonYear") val seasonYear: Int? = null,
        @JsonProperty("averageScore") val averageScore: Int? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("tags") val tags: List<AniListTag>? = null,
        @JsonProperty("nextAiringEpisode") val nextAiringEpisode: AniListNextAiring? = null
    )

    data class AniListTitle(
        @JsonProperty("romaji") val romaji: String? = null,
        @JsonProperty("english") val english: String? = null,
        @JsonProperty("native") val native: String? = null,
        @JsonProperty("userPreferred") val userPreferred: String? = null
    )

    data class AniListCoverImage(
        @JsonProperty("extraLarge") val extraLarge: String? = null,
        @JsonProperty("large") val large: String? = null,
        @JsonProperty("medium") val medium: String? = null
    )

    data class AniListTag(
        @JsonProperty("name") val name: String? = null
    )

    data class AniListNextAiring(
        @JsonProperty("episode") val episode: Int? = null
    )
}
