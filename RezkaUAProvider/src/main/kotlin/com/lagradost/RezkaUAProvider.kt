package com.lagradost

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class RezkaUAProvider : MainAPI() {

    override var mainUrl = "https://rezka-ua.in"
    override var name = "HDRezka UA"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Cartoon,
        TvType.Anime,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/films/page/" to "Фільми",
        "$mainUrl/series/page/" to "Серіали",
        "$mainUrl/cartoons/page/" to "Мультфільми",
        "$mainUrl/animation/page/" to "Аніме",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}$page/").document
        val home = document.select(".b-content__inline_item").map { it.toSearchResponse() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse {
        val href = this.attr("data-url").ifBlank { this.selectFirst(".b-content__inline_item-link a")?.attr("href").orEmpty() }
        val title = this.selectFirst(".b-content__inline_item-link a")?.text().orEmpty()
        val posterUrl = this.selectFirst(".b-content__inline_item-cover img")?.attr("src")
        val cat = this.selectFirst(".b-content__inline_item-cover .cat")
        val type = when {
            cat?.hasClass("series") == true -> TvType.TvSeries
            cat?.hasClass("cartoons") == true -> TvType.Cartoon
            cat?.hasClass("animation") == true -> TvType.Anime
            else -> TvType.Movie
        }

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/search/?do=search&subaction=search&q=${query.replace(" ", "+")}"
        ).document
        return document.select(".b-content__inline_item").map { it.toSearchResponse() }
    }

    private data class Translator(
        val id: String,
        val name: String,
        val camrip: String,
        val ads: String,
        val director: String,
    )

    private fun parseTranslators(document: Document): List<Translator> =
        document.select("#translators-list .b-translator__item, .b-translators__list .b-translator__item")
            .map {
                Translator(
                    id = it.attr("data-translator_id"),
                    name = it.attr("title").ifBlank { it.text() }.trim(),
                    camrip = it.attr("data-camrip").ifBlank { "0" },
                    ads = it.attr("data-ads").ifBlank { "0" },
                    director = it.attr("data-director").ifBlank { "0" },
                )
            }
            .filter { it.id.isNotBlank() }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst(".b-post__title h1")?.text().orEmpty()
        val origTitle = document.selectFirst(".b-post__origtitle")?.text()
        val poster = document.selectFirst(".b-sidecover img")?.attr("src")
        val description = document.selectFirst("meta[itemprop=description]")?.attr("content")
            ?: document.selectFirst(".b-post__description_text")?.text()
        val tags = document.select("span[itemprop=genre]").map { it.text() }
        val year = document.selectFirst(".b-post__info a[href*='/year/']")?.text()?.toIntOrNull()
        val rating = document.selectFirst(".b-post__info_rates.imdb .bold")?.text()
            ?: document.selectFirst(".b-post__info_rates.kp .bold")?.text()

        val isSeries = document.selectFirst("#simple-seasons-tabs") != null

        val tvType = when {
            "/animation/" in url -> TvType.Anime
            "/cartoons/" in url -> TvType.Cartoon
            isSeries -> TvType.TvSeries
            else -> TvType.Movie
        }

        val postId = document.selectFirst("[data-post_id]")?.attr("data-post_id")
            ?: document.selectFirst(".b-translator__item")?.attr("data-id").orEmpty()

        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, "$postId|movie|$url") {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                if (!origTitle.isNullOrBlank()) this.name = title
            }
        }

        // Series: parse seasons + episodes from initial active translator block
        val episodes = document.select(".b-simple_episode__item").map {
            val s = it.attr("data-season_id").toIntOrNull() ?: 1
            val e = it.attr("data-episode_id").toIntOrNull() ?: 1
            newEpisode("$postId|series|$s|$e|$url") {
                this.name = it.text()
                this.season = s
                this.episode = e
            }
        }

        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.score = Score.from10(rating)
        }
    }

    private fun parseStreams(streams: String): List<Pair<Int, String>> {
        // Format: [QUALITY]url1 or url2[, [QUALITY2]...]
        // Each url is a HLS-gateway URL ending with .mp4:hls:manifest.m3u8 — use as-is.
        val out = mutableListOf<Pair<Int, String>>()
        val re = "\\[([^\\]]+)\\]([^,\\[]+)".toRegex()
        re.findAll(streams).forEach { m ->
            val q = m.groupValues[1]
            val urls = m.groupValues[2].split(" or ")
            val pick = (urls.firstOrNull { it.endsWith("manifest.m3u8") }
                ?: urls.firstOrNull())?.trim().orEmpty()
            if (pick.isEmpty()) return@forEach
            val qNum = q.filter { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value
            out += qNum to pick
        }
        return out
    }

    private fun qualityValue(p: Int): Int = when (p) {
        2160 -> Qualities.P2160.value
        1440 -> Qualities.P1440.value
        1080 -> Qualities.P1080.value
        720 -> Qualities.P720.value
        480 -> Qualities.P480.value
        360 -> Qualities.P360.value
        240 -> Qualities.P240.value
        144 -> Qualities.P144.value
        else -> Qualities.Unknown.value
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data shape: "<postId>|movie|<url>"  OR  "<postId>|series|<season>|<episode>|<url>"
        val parts = data.split("|")
        val postId = parts[0]
        val kind = parts[1]
        val pageUrl = parts.last()

        val document = app.get(pageUrl).document
        val favs = document.selectFirst("#ctrl_favs")?.attr("value").orEmpty()
        val translators = parseTranslators(document)
            .ifEmpty {
                // single-translator page — try with empty/zero translator id from initCDN args
                listOf(Translator(id = "0", name = "Default", camrip = "0", ads = "0", director = "0"))
            }

        for (t in translators) {
            val form = mutableMapOf(
                "id" to postId,
                "translator_id" to t.id,
                "is_camrip" to t.camrip,
                "is_ads" to t.ads,
                "is_director" to t.director,
                "favs" to favs,
            )
            if (kind == "series") {
                form["season"] = parts[2]
                form["episode"] = parts[3]
                form["action"] = "get_stream"
            } else {
                form["action"] = "get_movie"
            }

            val resp = try {
                app.post(
                    "$mainUrl/ajax/get_cdn_series/?t=${System.currentTimeMillis()}",
                    data = form,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to pageUrl,
                    ),
                ).text
            } catch (_: Exception) {
                continue
            }

            // success may be true or false; only parse url if success
            if (!resp.contains("\"success\":true")) continue

            val streams = "\"url\":\"([^\"]+)\"".toRegex().find(resp)?.groupValues?.get(1)
                ?.replace("\\/", "/") ?: continue

            parseStreams(streams).forEach { (q, link) ->
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name (${t.name})",
                        url = link,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.referer = mainUrl
                        this.quality = qualityValue(q)
                    }
                )
            }
        }
        return true
    }
}
