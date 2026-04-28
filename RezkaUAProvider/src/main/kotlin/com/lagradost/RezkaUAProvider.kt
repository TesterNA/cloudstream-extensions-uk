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

    private val streamsRegex =
        "initCDN(?:Movies|Series)Events\\([^,]+,[^,]+,[^,]+,[^,]+,[^,]+,[^,]+,[^,]+,[^,]+,\\s*\\{[^}]*\"streams\":\"([^\"]+)\""
            .toRegex()
    private val translatorRegex =
        "<li[^>]*class=\"b-translator__item[^\"]*\"[^>]*data-translator_id=\"(\\d+)\"[^>]*>([^<]+)</li>"
            .toRegex()
    private val seasonRegex =
        "<li[^>]*class=\"b-simple_season__item[^\"]*\"[^>]*data-tab_id=\"(\\d+)\"[^>]*>([^<]+)</li>"
            .toRegex()
    private val episodeRegex =
        "<li[^>]*class=\"b-simple_episode__item[^\"]*\"[^>]*data-season_id=\"(\\d+)\"[^>]*data-episode_id=\"(\\d+)\"[^>]*>([^<]+)</li>"
            .toRegex()

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val html = document.html()

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

        // Translators: id -> name. If page has no translator list, use empty -> default.
        val translators = translatorRegex.findAll(html).map { it.groupValues[1] to it.groupValues[2] }.toList()

        if (!isSeries) {
            // Movie: streams already in initCDNMoviesEvents script
            val activeTranslator = document.selectFirst(".b-translator__item.active")?.attr("data-translator_id")
                ?: translators.firstOrNull()?.first.orEmpty()
            val data = "$postId|$activeTranslator|movie|${url}"
            return newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                if (!origTitle.isNullOrBlank()) this.name = title
            }
        }

        // Series: parse seasons + episodes from initial active translator block
        val seasons = seasonRegex.findAll(html).associate { it.groupValues[1].toInt() to it.groupValues[2] }
        val episodes = episodeRegex.findAll(html).map {
            val s = it.groupValues[1].toInt()
            val e = it.groupValues[2].toInt()
            val name = it.groupValues[3]
            val activeTranslator = document.selectFirst(".b-translator__item.active")?.attr("data-translator_id")
                ?: translators.firstOrNull()?.first.orEmpty()
            newEpisode("$postId|$activeTranslator|series|$s|$e|$url") {
                this.name = name
                this.season = s
                this.episode = e
            }
        }.toList()

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
        val out = mutableListOf<Pair<Int, String>>()
        val re = "\\[([^\\]]+)\\]([^,\\[]+)".toRegex()
        re.findAll(streams).forEach { m ->
            val q = m.groupValues[1]
            val urls = m.groupValues[2].split(" or ")
            val pick = urls.firstOrNull { ".mp4" in it }?.trim() ?: return@forEach
            // Strip the :hls:manifest.m3u8 suffix to get direct mp4
            val mp4 = pick.substringBefore(".mp4") + ".mp4"
            val qNum = q.filter { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value
            out += qNum to mp4
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
        val parts = data.split("|")
        val postId = parts[0]
        val pageUrl = parts.last()

        // Discover all translators from the page
        val pageHtml = app.get(pageUrl).document.html()
        val translators = translatorRegex.findAll(pageHtml)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
            .ifEmpty { listOf(parts[1] to "Default") }

        for ((translatorId, translatorName) in translators) {
            val form = mutableMapOf(
                "id" to postId,
                "translator_id" to translatorId,
            )
            if (parts[2] == "series") {
                form["season"] = parts[3]
                form["episode"] = parts[4]
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

            val streams = "\"url\":\"([^\"]+)\"".toRegex().find(resp)?.groupValues?.get(1)
                ?.replace("\\/", "/") ?: continue

            parseStreams(streams).forEach { (q, link) ->
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name ($translatorName)",
                        url = link,
                        type = ExtractorLinkType.VIDEO,
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
