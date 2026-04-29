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

    private val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64; rv:122.0) Gecko/20100101 Firefox/122.0"
    private val baseHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ru-RU,ru;q=0.9,uk;q=0.8",
    )

    private fun ajaxHeaders(origin: String, referer: String) = mapOf(
        "User-Agent" to USER_AGENT,
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Accept-Language" to "ru-RU,ru;q=0.9,uk;q=0.8",
        "Origin" to origin,
        "Referer" to referer,
    )

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

    // Movie shape: initCDNMoviesEvents(post_id, translator_id, camrip, ads, director, host, ...)
    private val movieInitRegex =
        "initCDNMoviesEvents\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)"
            .toRegex()

    // Series shape: initCDNSeriesEvents(post_id, translator_id, season, episode, false|true, host, ...)
    private val seriesInitRegex =
        "initCDNSeriesEvents\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(?:false|true)"
            .toRegex()

    // Streams JSON value embedded inside the same initCDN(Movies|Series)Events(...) call.
    private val inlineStreamsRegex =
        "initCDN(?:Movies|Series)Events\\([^)]*?\"streams\":\"([^\"]+)\""
            .toRegex()

    private fun parseTranslators(document: Document, html: String): List<Translator> {
        val list = document.select("#translators-list .b-translator__item, .b-translators__list .b-translator__item")
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

        if (list.isNotEmpty()) return list

        // No translator list rendered (single translator). Try movie-shape first, then series.
        movieInitRegex.find(html)?.let { m ->
            return listOf(
                Translator(
                    id = m.groupValues[2], name = "Default",
                    camrip = m.groupValues[3], ads = m.groupValues[4], director = m.groupValues[5],
                )
            )
        }
        seriesInitRegex.find(html)?.let { m ->
            return listOf(
                Translator(
                    id = m.groupValues[2], name = "Default",
                    camrip = "0", ads = "0", director = "0",
                )
            )
        }
        return emptyList()
    }

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
            // For movies, `data` is the page URL itself.
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                if (!origTitle.isNullOrBlank()) this.name = title
            }
        }

        // Series: each episode's page has its own initCDN call with inline streams.
        // Use the episode URL directly as `data` — loadLinks just fetches and parses.
        // For li-only series (no href), fall back to series URL + s/e marker handled by loadLinks via AJAX.
        val pageOrigin = "https?://[^/]+".toRegex().find(url)?.value ?: mainUrl
        val episodes = document.select(".b-simple_episode__item").map {
            val s = it.attr("data-season_id").toIntOrNull() ?: 1
            val e = it.attr("data-episode_id").toIntOrNull() ?: 1
            val epHrefRaw = it.attr("href")
            // If no per-ep URL, encode s+e+postId+seriesUrl so loadLinks can call AJAX.
            // Force same-origin (page came in on .in but ep hrefs may point to .co).
            val epHref = epHrefRaw.replace("https?://[^/]+".toRegex(), pageOrigin)
            val dataStr = if (epHref.isNotBlank()) epHref
                else "rezka-ajax|$postId|$s|$e|$url"
            newEpisode(dataStr) {
                this.name = it.text()
                this.season = s
                this.episode = e
                this.data = dataStr
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

    /** Captures the most informative failure reason as we walk through code paths. */
    private class ErrorTrack(var msg: String = "no path matched") {
        fun set(m: String) { msg = m }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // `data` is either:
        //   * a page URL (movie page or episode-specific page with inline streams)
        //   * "rezka-ajax|<postId>|<season>|<episode>|<seriesUrl>" — for <li>-only series.
        val err = ErrorTrack()
        var emitted = 0
        try {
            emitted = if (data.startsWith("rezka-ajax|")) {
                loadLinksViaAjax(data, err) { callback(it) }
            } else {
                loadLinksFromPage(data, err) { callback(it) }
            }
        } catch (e: Throwable) {
            err.set("${e::class.simpleName}: ${e.message?.take(140) ?: "(no msg)"}")
        }
        if (emitted == 0) {
            // Single visible error source so user on a TV at least knows what failed.
            callback(
                newExtractorLink(
                    "https://example.invalid/no-streams",
                    "RezkaUA: no streams (${err.msg})",
                    "https://example.invalid/no-streams",
                    ExtractorLinkType.M3U8,
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            // Return true — we did emit (the diagnostic) so CS shows it instead of hiding the source.
            return true
        }
        return true
    }

    /**
     * Fetches the page at [pageUrl], emits the active translator's inline streams, and — if it's
     * a series episode page (initCDNSeriesEvents) — also calls AJAX for every other translator
     * advertised on the page so they appear in the source picker.
     */
    private suspend fun loadLinksFromPage(
        pageUrl: String,
        err: ErrorTrack,
        emit: (ExtractorLink) -> Unit,
    ): Int {
        val origin = "https?://[^/]+".toRegex().find(pageUrl)?.value ?: mainUrl
        val pageHtml = try {
            app.get(pageUrl, headers = baseHeaders).text
        } catch (e: Throwable) {
            err.set("page GET ${e::class.simpleName}: ${e.message?.take(80)}")
            return 0
        }
        if (pageHtml.isBlank()) {
            err.set("page GET returned empty body")
            return 0
        }
        val document = org.jsoup.Jsoup.parse(pageHtml, pageUrl)

        val activeName = document.selectFirst(".b-translator__item.active")?.let {
            it.attr("title").ifBlank { it.text() }.trim()
        } ?: parseTranslators(document, pageHtml).firstOrNull()?.name ?: name

        var emitted = 0
        // 1) Active translator from inline initCDN(...) — fast and reliable.
        val inlineRaw = inlineStreamsRegex.find(pageHtml)?.groupValues?.get(1)
        if (inlineRaw == null) {
            err.set("inline initCDN streams regex did not match (page len=${pageHtml.length})")
        } else {
            val streams = inlineRaw.replace("\\/", "/")
            val parsed = parseStreams(streams)
            if (parsed.isEmpty()) {
                err.set("parseStreams found 0 quality entries in inline streams")
            }
            parsed.forEach { (q, link) ->
                emit(buildLink(link, "$activeName ${q}p".trim(), origin, q))
                emitted++
            }
        }

        // 2) If this is a SERIES episode page, fan out to other translators via AJAX.
        val seriesMatch = seriesInitRegex.find(pageHtml)
        if (seriesMatch != null) {
            val postId = seriesMatch.groupValues[1]
            val activeId = seriesMatch.groupValues[2]
            val season = seriesMatch.groupValues[3]
            val episode = seriesMatch.groupValues[4]
            val favs = document.selectFirst("#ctrl_favs")?.attr("value").orEmpty()
            val translators = parseTranslators(document, pageHtml)
                .filter { it.id != activeId }    // skip active — already emitted from inline

            for (t in translators) {
                emitted += ajaxStreams(
                    origin = origin, referer = pageUrl,
                    postId = postId, translator = t,
                    season = season, episode = episode,
                    favs = favs, err = err, emit = emit,
                )
            }
        }
        return emitted
    }

    /**
     * AJAX-only path used for `<li>`-rendered series episodes (no per-episode URL).
     * `data` shape: `rezka-ajax|<postId>|<season>|<episode>|<seriesUrl>`
     */
    private suspend fun loadLinksViaAjax(
        data: String,
        err: ErrorTrack,
        emit: (ExtractorLink) -> Unit,
    ): Int {
        val parts = data.split("|")
        val postId = parts[1]
        val season = parts[2]
        val episode = parts[3]
        val seriesUrl = parts.last()
        val origin = "https?://[^/]+".toRegex().find(seriesUrl)?.value ?: mainUrl

        val pageHtml = try {
            app.get(seriesUrl, headers = baseHeaders).text
        } catch (e: Throwable) {
            err.set("series GET ${e::class.simpleName}: ${e.message?.take(80)}")
            return 0
        }
        val document = org.jsoup.Jsoup.parse(pageHtml, seriesUrl)
        val favs = document.selectFirst("#ctrl_favs")?.attr("value").orEmpty()
        if (favs.isBlank()) err.set("ctrl_favs missing on series page (size=${pageHtml.length})")
        val translators = parseTranslators(document, pageHtml)
        if (translators.isEmpty()) {
            err.set("translator list empty + initCDN regex didn't match on series page")
            return 0
        }

        var emitted = 0
        for (t in translators) {
            emitted += ajaxStreams(
                origin = origin, referer = seriesUrl,
                postId = postId, translator = t,
                season = season, episode = episode,
                favs = favs, err = err, emit = emit,
            )
        }
        if (emitted == 0 && err.msg == "no path matched") {
            err.set("AJAX returned 0 streams across ${translators.size} translators")
        }
        return emitted
    }

    private suspend fun ajaxStreams(
        origin: String,
        referer: String,
        postId: String,
        translator: Translator,
        season: String?,
        episode: String?,
        favs: String,
        err: ErrorTrack,
        emit: (ExtractorLink) -> Unit,
    ): Int {
        val form = mutableMapOf(
            "id" to postId,
            "translator_id" to translator.id,
            // Always send flags (browser does this); defaults to "0" already in Translator model.
            "is_camrip" to translator.camrip,
            "is_ads" to translator.ads,
            "is_director" to translator.director,
            "favs" to favs,
        )
        if (season != null && episode != null) {
            form["season"] = season
            form["episode"] = episode
            form["action"] = "get_stream"
        } else {
            form["action"] = "get_movie"
        }

        val resp = try {
            app.post(
                "$origin/ajax/get_cdn_series/?t=${System.currentTimeMillis()}",
                data = form,
                headers = ajaxHeaders(origin, referer),
            ).text
        } catch (e: Exception) {
            err.set("AJAX ${e::class.simpleName} for tid=${translator.id}: ${e.message?.take(80)}")
            return 0
        }
        if (!resp.contains("\"success\":true")) {
            // Try to surface the server's own message for the most recent failed translator.
            val msg = "\"message\":\"([^\"]{0,140})\"".toRegex().find(resp)?.groupValues?.get(1)
                ?.replace("\\/", "/")
                ?.let { it.ifBlank { "(empty)" } }
                ?: "no \"success\":true in body (len=${resp.length})"
            err.set("AJAX tid=${translator.id} -> $msg")
            return 0
        }

        val streams = "\"url\":\"([^\"]+)\"".toRegex().find(resp)?.groupValues?.get(1)
            ?.replace("\\/", "/")
        if (streams == null) {
            err.set("AJAX tid=${translator.id} success but no url field")
            return 0
        }

        var n = 0
        parseStreams(streams).forEach { (q, link) ->
            emit(buildLink(link, "${translator.name} ${q}p".trim(), origin, q))
            n++
        }
        if (n == 0) err.set("AJAX tid=${translator.id} url field had 0 quality entries")
        return n
    }

    private suspend fun buildLink(url: String, sourceName: String, origin: String, q: Int): ExtractorLink =
        newExtractorLink(url, sourceName, url, ExtractorLinkType.M3U8) {
            this.referer = origin
            this.quality = qualityValue(q)
        }
}
