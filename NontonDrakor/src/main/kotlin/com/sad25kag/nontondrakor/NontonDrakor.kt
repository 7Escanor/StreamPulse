package com.sad25kag.nontondrakor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class NontonDrakor : MainAPI() {
    override var mainUrl = "https://kr.drakor-id.top"
    override var name = "NontonDrakor"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Episode Terbaru",
        "$mainUrl/genre/drama-korea/page/%d/" to "Drama Korea",
        "$mainUrl/tv/page/%d/" to "TV Shows",
        "$mainUrl/genre/c-drama/page/%d/" to "C-Drama",
        "$mainUrl/genre/action/page/%d/" to "Action",
        "$mainUrl/genre/comedy/page/%d/" to "Comedy",
        "$mainUrl/genre/crime/page/%d/" to "Crime",
        "$mainUrl/genre/fantasy/page/%d/" to "Fantasy",
        "$mainUrl/genre/mystery/page/%d/" to "Mystery",
        "$mainUrl/genre/romance/page/%d/" to "Romance",
        "$mainUrl/genre/thriller/page/%d/" to "Thriller",
        "$mainUrl/genre/film-india/page/%d/" to "Film India",
        "$mainUrl/genre/film-semi/page/%d/" to "18+"
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.pageUrl(page)
        val document = app.get(url, headers = headers, referer = "$mainUrl/").document
        val results = document.toSearchResults(url)
        val hasNext = results.isNotEmpty() && document.hasNextPage(page)
        return newHomePageResponse(request.name, results, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/page/1/?s=$encoded",
            "$mainUrl/?s=$encoded&post_type[]=post&post_type[]=tv"
        )

        val results = linkedMapOf<String, SearchResponse>()
        for (url in urls) {
            val text = runCatching { app.get(url, headers = headers, referer = "$mainUrl/").text }.getOrNull().orEmpty()
            if (text.isBlank()) continue
            Jsoup.parse(text, url).toSearchResults(url).forEach { results[it.url] = it }
            if (results.isNotEmpty()) break
        }
        return results.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = url.absoluteUrl(mainUrl) ?: url
        val document = app.get(pageUrl, headers = headers, referer = "$mainUrl/").document

        if (pageUrl.isEpisodeUrl()) {
            val seriesUrl = document.extractSeriesUrl(pageUrl)
            if (!seriesUrl.isNullOrBlank() && seriesUrl != pageUrl) {
                val seriesDocument = runCatching { app.get(seriesUrl, headers = headers, referer = pageUrl).document }.getOrNull()
                if (seriesDocument != null) return seriesDocument.toSeriesOrMovieLoadResponse(seriesUrl)
            }
            return document.toEpisodeMovieLoadResponse(pageUrl)
        }

        return document.toSeriesOrMovieLoadResponse(pageUrl)
    }

    private suspend fun Document.toSeriesOrMovieLoadResponse(pageUrl: String): LoadResponse {
        val title = extractDetailTitle(pageUrl) ?: pageUrl.slugTitle()
        val poster = extractPoster(pageUrl)
        val plot = extractPlot()
        val tags = extractTags()
        val year = extractYear()
        val actors = extractActors()
        val status = extractStatus()
        val episodes = extractEpisodes(pageUrl)
        val recommendations = extractRecommendations(pageUrl)
        val type = when {
            pageUrl.contains("/tv/", true) || episodes.isNotEmpty() -> TvType.AsianDrama
            else -> TvType.Movie
        }

        return if (type == TvType.Movie || episodes.isEmpty()) {
            // Movie detail pages must stay as the playback entrypoint so loadLinks can scan
            // every source-backed server tab (?player=2, ?player=3, etc.) instead of getting
            // trapped on the first active iframe.
            newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl.toPlaybackData(pageUrl)) {
                posterUrl = poster
                backgroundPosterUrl = poster
                plot?.let { this.plot = it }
                this.tags = tags
                this.year = year
                this.actors = actors.map { ActorData(Actor(it)) }
                this.recommendations = recommendations
            }
        } else {
            newTvSeriesLoadResponse(title, pageUrl, TvType.AsianDrama, episodes) {
                posterUrl = poster
                backgroundPosterUrl = poster
                plot?.let { this.plot = it }
                this.tags = tags
                this.year = year
                showStatus = status
                this.actors = actors.map { ActorData(Actor(it)) }
                this.recommendations = recommendations
            }
        }
    }

    private suspend fun Document.toEpisodeMovieLoadResponse(pageUrl: String): LoadResponse {
        val title = extractDetailTitle(pageUrl)?.cleanEpisodeTitle(null) ?: pageUrl.slugTitle().cleanEpisodeTitle(null)
        val poster = extractPoster(pageUrl)
        val plot = extractPlot()
        val tags = extractTags()
        val year = extractYear()
        val actors = extractActors()
        val playData = pageUrl.toPlaybackData(pageUrl)

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, playData) {
            posterUrl = poster
            backgroundPosterUrl = poster
            plot?.let { this.plot = it }
            this.tags = tags
            this.year = year
            this.actors = actors.map { ActorData(Actor(it)) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (rawPage, rawReferer) = data.parsePlaybackData()
        val entryUrl = rawPage.absoluteUrl(mainUrl) ?: rawPage
        val referer = rawReferer.absoluteUrl(mainUrl) ?: "$mainUrl/"
        val session = LinkSession(subtitleCallback, callback)

        val isSitePage = entryUrl.isOwnSiteHost() || entryUrl.isEpisodeUrl()

        when {
            entryUrl.isEvidenceMediaUrl() -> session.emitDirect(entryUrl, name, referer)
            isSitePage -> session.scanPage(entryUrl, referer, 0)
            else -> session.handlePlayer(entryUrl, referer, name, 0, true)
        }

        // Cadangan: situs kadang pindah subdomain (nonton. <-> kr.). Kalau kosong, coba host alternatif.
        if (session.delivered == 0 && isSitePage) {
            for (altHost in ALT_HOSTS) {
                if (altHost == entryUrl.hostOrEmpty()) continue
                val altUrl = entryUrl.replaceHost(altHost) ?: continue
                session.scanPage(altUrl, "https://$altHost/", 0)
                if (session.delivered > 0) break
            }
        }
        return session.delivered > 0
    }

    /**
     * Satu sesi pengambilan link. Menyimpan state "sudah dikunjungi" supaya tidak ada rekursi
     * tak berujung (bug lama: halaman ?player=N saling memanggil tanpa batas).
     */
    private inner class LinkSession(
        private val subtitleCallback: (SubtitleFile) -> Unit,
        private val callback: (ExtractorLink) -> Unit
    ) {
        var delivered = 0
            private set

        private val siteHosts = hashSetOf(mainUrl.hostOrEmpty())
        private val visitedPages = hashSetOf<String>()
        private val visitedPlayers = hashSetOf<String>()
        private val emitted = hashSetOf<String>()
        private val subtitles = hashSetOf<String>()

        suspend fun emitDirect(rawUrl: String?, sourceName: String, refererUrl: String): Boolean {
            val fixed = rawUrl.decodeCandidate()?.absoluteUrl(refererUrl)?.cleanMediaUrl() ?: return false
            if (!fixed.isEvidenceMediaUrl() || fixed.isMediaAssetUrl() || !emitted.add(fixed)) return false
            val type = if (fixed.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(
                newExtractorLink(sourceName, sourceName, fixed, type) {
                    quality = fixed.qualityFromUrl()
                    referer = refererUrl
                    headers = fixed.playbackHeaders(refererUrl)
                }
            )
            delivered++
            return true
        }

        private fun addSubtitle(url: String) {
            if (subtitles.add(url)) subtitleCallback(SubtitleFile(url.subtitleLabel(), url))
        }

        /** Membaca satu halaman situs (halaman film / episode / tab ?player=N). */
        suspend fun scanPage(pageUrl: String, referer: String, depth: Int) {
            if (depth > 3 || !visitedPages.add(pageUrl.substringBefore('#'))) return
            if (depth == 0) pageUrl.hostOrEmpty().takeIf { it.isNotBlank() }?.let { siteHosts.add(it) }

            val html = runCatching { app.get(pageUrl, headers = headers, referer = referer).text }.getOrNull().orEmpty()
            if (html.isBlank()) return
            val document = Jsoup.parse(html, pageUrl)

            // 1. iframe / embed yang sedang aktif di halaman
            document.extractIframeSources(pageUrl).forEach { handlePlayer(it, pageUrl, name, depth + 1, true) }
            html.extractMediaUrls().forEach { emitDirect(it, name, pageUrl) }
            html.extractSubtitleUrls().forEach { addSubtitle(it) }

            // 2. tab server model ?player=N (Muvipro, non-ajax)
            document.extractServerTabs(pageUrl).forEach { scanPage(it, pageUrl, depth + 1) }

            // 3. tab server model ajax (Muvipro ajax / DooPlay)
            scanAjax(document, pageUrl, depth)

            // 4. fallback terakhir: daftar download
            if (depth == 0 && delivered == 0) {
                document.select("ul.gmr-download-list a[href], .gmr-download-list a[href]").forEach {
                    handlePlayer(it.attr("href"), pageUrl, name, depth + 1, false)
                }
            }
        }

        private suspend fun scanAjax(document: Document, pageUrl: String, depth: Int) {
            val ajaxUrl = (pageUrl.origin() ?: mainUrl) + "/wp-admin/admin-ajax.php"
            val ajaxHeaders = headers + mapOf("Accept" to "*/*", "X-Requested-With" to "XMLHttpRequest")

            // Muvipro: action=muvipro_player_content, tab=<id div>, post_id=<id post>
            val postId = document.selectFirst("#muvipro_player_content_id[data-id]")?.attr("data-id").orEmpty()
                .ifBlank { Regex("postid-(\\d+)").find(document.body().className())?.groupValues?.get(1).orEmpty() }
                .ifBlank {
                    Regex("[?&]p=(\\d+)").find(document.selectFirst("link[rel=shortlink]")?.attr("href").orEmpty())
                        ?.groupValues?.get(1).orEmpty()
                }
            val tabs = (
                document.select("div.tab-content-ajax[id]").map { it.id() } +
                    document.select(".muvipro-player-tabs a[href^=#]").map { it.attr("href").removePrefix("#") }
                ).filter { it.isNotBlank() }.distinct()

            if (postId.isNotBlank()) {
                for (tab in tabs) {
                    val text = runCatching {
                        app.post(
                            ajaxUrl,
                            data = mapOf("action" to "muvipro_player_content", "tab" to tab, "post_id" to postId),
                            headers = ajaxHeaders,
                            referer = pageUrl
                        ).text
                    }.getOrNull().orEmpty()
                    handleAjaxResponse(text, pageUrl, depth)
                }
            }

            // DooPlay: action=doo_player_ajax (cadangan kalau tema berubah)
            document.select("[data-post][data-nume]").forEach { element ->
                val post = element.attr("data-post")
                val nume = element.attr("data-nume")
                if (post.isBlank() || nume.isBlank() || nume.equals("trailer", true)) return@forEach
                val text = runCatching {
                    app.post(
                        ajaxUrl,
                        data = mapOf(
                            "action" to "doo_player_ajax",
                            "post" to post,
                            "nume" to nume,
                            "type" to element.attr("data-type").ifBlank { "movie" }
                        ),
                        headers = ajaxHeaders,
                        referer = pageUrl
                    ).text
                }.getOrNull().orEmpty()
                handleAjaxResponse(text, pageUrl, depth)
            }
        }

        private suspend fun handleAjaxResponse(text: String, pageUrl: String, depth: Int) {
            if (text.isBlank()) return
            Jsoup.parse(text, pageUrl).extractIframeSources(pageUrl).forEach { handlePlayer(it, pageUrl, name, depth + 1, true) }
            EMBED_URL_REGEX.findAll(text).forEach { handlePlayer(it.groupValues[1], pageUrl, name, depth + 1, true) }
            text.extractMediaUrls().forEach { emitDirect(it, name, pageUrl) }
        }

        /**
         * Menangani satu URL player.
         * trusted = true  -> URL berasal dari iframe/embed aktif (boleh di-scan manual).
         * trusted = false -> URL dari daftar download (hanya lewat extractor bawaan).
         */
        suspend fun handlePlayer(rawUrl: String?, refererUrl: String, label: String, depth: Int, trusted: Boolean) {
            val playerUrl = rawUrl.decodeCandidate()?.absoluteUrl(refererUrl)?.cleanMediaUrl() ?: return
            if (!playerUrl.startsWith("http", true) || playerUrl.isMediaAssetUrl() || playerUrl.isBlockedHost()) return

            if (playerUrl.isEvidenceMediaUrl()) {
                emitDirect(playerUrl, playerUrl.hostLabel().ifBlank { label }, refererUrl)
                return
            }
            if (depth > 5 || !visitedPlayers.add(playerUrl)) return

            // Player yang dihosting situs sendiri -> baca sebagai halaman biasa
            if (playerUrl.isOwnSiteHost() || playerUrl.hostOrEmpty() in siteHosts) {
                if (trusted) scanPage(playerUrl, refererUrl, depth)
                return
            }

            // Host eksternal: coba extractor bawaan CloudStream dulu
            var extracted = 0
            runCatching {
                loadExtractor(playerUrl, refererUrl, subtitleCallback) { link ->
                    extracted++
                    delivered++
                    callback(link)
                }
            }
            if (extracted > 0 || !trusted) return

            // Tidak ada extractor yang cocok -> bongkar halaman player sendiri
            resolveManually(playerUrl, refererUrl, label, depth)
        }

        private suspend fun resolveManually(playerUrl: String, refererUrl: String, label: String, depth: Int) {
            val response = runCatching { app.get(playerUrl, headers = headers, referer = refererUrl) }.getOrNull() ?: return
            val rawText = response.text
            if (rawText.isBlank()) return
            val finalUrl = response.url.ifBlank { playerUrl }
            val sourceLabel = playerUrl.hostLabel().ifBlank { label }

            // Gabungkan teks asli + hasil unpack JS (eval packer) + hasil atob()
            val combined = StringBuilder(rawText)
            if (rawText.contains("eval(function(p,a,c,k,e", true)) {
                runCatching { getAndUnpack(rawText) }.getOrNull()?.let { combined.append('\n').append(it) }
            }
            ATOB_REGEX.findAll(rawText).forEach { match ->
                runCatching { base64Decode(match.groupValues[1]) }.getOrNull()?.let { combined.append('\n').append(it) }
            }
            val text = combined.toString()

            // Streamtape-style (strcloud.in, tapecontent.net, dll)
            for (candidate in text.extractGetVideoUrls(finalUrl)) {
                if (resolveGetVideo(candidate, finalUrl, sourceLabel)) break
            }

            text.extractMediaUrls().forEach { emitDirect(it, sourceLabel, finalUrl) }
            text.extractSubtitleUrls().forEach { addSubtitle(it) }

            // iframe bersarang di dalam halaman player
            Jsoup.parse(rawText, finalUrl).extractIframeSources(finalUrl)
                .forEach { handlePlayer(it, finalUrl, sourceLabel, depth + 1, true) }
        }

        /** get_video?... mengembalikan 302 ke file mp4 asli; ambil header Location-nya. */
        private suspend fun resolveGetVideo(candidate: String, playerUrl: String, label: String): Boolean {
            val origin = (playerUrl.origin() ?: candidate.origin() ?: mainUrl) + "/"
            val redirected = runCatching {
                app.get(
                    candidate,
                    headers = candidate.playbackHeaders(playerUrl),
                    referer = playerUrl,
                    allowRedirects = false
                ).headers["location"]
            }.getOrNull()?.absoluteUrl(candidate)?.cleanMediaUrl()
            return emitDirect(redirected ?: candidate, label, origin)
        }
    }

    private fun String.pageUrl(page: Int): String {
        if (page <= 1) {
            return replace("/page/%d/", "/")
                .replace("/page/%d", "/")
                .replace("%d", "")
        }
        return format(page)
    }

    private fun Document.hasNextPage(page: Int): Boolean {
        return select(
            "a[href*='/page/${page + 1}/'], a.next, .next a[href], .pagination a[href*='/page/${page + 1}/'], " +
                ".nav-links a[href*='/page/${page + 1}/'], .loadmore, [data-page='${page + 1}']"
        ).isNotEmpty()
    }

    private fun Document.toSearchResults(baseUrl: String): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        select(
            "article, .ml-item, .item, .movie-item, .module, .owl-item, .result-item, .poster, .card, " +
                ".post, .list-item, .film, .tvshows, .movies-list .movie, .grid-item, .box, .swiper-slide"
        ).forEach { card ->
            card.toSearchResult(baseUrl)?.let { results[it.url] = it }
        }

        if (results.isEmpty()) {
            select("h1 a[href], h2 a[href], h3 a[href], h4 a[href], .entry-title a[href], .post-title a[href], .title a[href]").forEach { anchor ->
                (anchor.closest("article, .item, .movie-item, .module, .result-item, .post, .card, .poster") ?: anchor.parent() ?: anchor)
                    .toSearchResult(baseUrl)?.let { results[it.url] = it }
            }
        }
        return results.values.toList()
    }

    private fun Element.toSearchResult(baseUrl: String): SearchResponse? {
        val anchors = select("h1 a[href], h2 a[href], h3 a[href], h4 a[href], .entry-title a[href], .post-title a[href], .title a[href], .name a[href], a[href]")
            .filter { it.attr("href").absoluteUrl(baseUrl)?.isContentUrl() == true }
            .sortedBy { it.anchorPriority() }
        val anchor = anchors.firstOrNull() ?: return null
        val href = anchor.attr("href").absoluteUrl(baseUrl) ?: return null
        if (!href.isContentUrl()) return null

        val title = listOf(
            selectFirst("h1, h2, h3, h4, .entry-title, .post-title, .mli-title, .title, .name")?.text(),
            anchor.attr("title"),
            anchor.text(),
            selectFirst("img[alt]")?.attr("alt")
        ).firstCleanTitle() ?: return null
        val poster = selectFirst("img")?.imageUrl(href)

        return when {
            href.contains("/tv/", true) -> newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { posterUrl = poster }
            else -> newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        }
    }

    private fun Element.anchorPriority(): Int {
        val text = text().lowercase()
        val href = attr("href").lowercase()
        return when {
            tagName().matches(Regex("h[1-4]", RegexOption.IGNORE_CASE)) -> 0
            parent()?.tagName()?.matches(Regex("h[1-4]", RegexOption.IGNORE_CASE)) == true -> 0
            hasClass("title") || hasClass("entry-title") || hasClass("post-title") -> 1
            text.equals("watch", true) || text.equals("watch movie", true) || text.equals("trailer", true) -> 9
            href.contains("/movie/") || href.contains("/tv/") || href.contains("/eps/") -> 2
            else -> 5
        }
    }

    private fun Document.extractDetailTitle(pageUrl: String): String? {
        return listOf(
            selectFirst("h1.entry-title, h1.post-title, .sheader h1, .data h1, .movie-title h1, .entry-header h1, h1")?.text(),
            selectFirst("meta[property=og:title]")?.attr("content"),
            selectFirst("title")?.text()
        ).firstCleanTitle() ?: pageUrl.slugTitle().takeIf { it.isNotBlank() }
    }

    private fun Document.extractPoster(baseUrl: String): String? {
        return listOfNotNull(
            selectFirst("meta[property=og:image]")?.imageUrl(baseUrl),
            selectFirst(".poster img, .thumb img, .image img, .movie-poster img, .sheader img, .entry-content img, img.wp-post-image")?.imageUrl(baseUrl),
            selectFirst("video[poster]")?.imageUrl(baseUrl)
        ).firstOrNull { it.isValidImageUrl() }
    }

    private fun Document.extractPlot(): String? {
        return listOf(
            selectFirst(".synopsis, .desc, .summary, .storyline, .sinopsis, div.entry-content[itemprop='description'], .entry-content p")?.text(),
            selectFirst("meta[property=og:description]")?.attr("content"),
            selectFirst("meta[name=description]")?.attr("content")
        ).mapNotNull { it?.cleanText()?.stripDetailMetadata() }
            .firstOrNull { it.isValidPlot() }
    }

    private fun Document.extractTags(): List<String> {
        return select("a[href*='/genre/'], [itemprop='genre'] a[href]")
            .filterNot { it.isInsideNoiseBlock() }
            .map { it.text().cleanText() }
            .filter { it.isNotBlank() && it.length <= 40 && !it.equals("Genre", true) }
            .distinct()
            .take(12)
    }

    private fun Document.extractActors(): List<String> {
        val peoplePaths = listOf("/cast/", "/actor/", "/actors/", "/star/", "/stars/")
        return select("[itemprop='actors'] a, [itemprop='actor'] a, a[href]")
            .filter { element -> peoplePaths.any { element.attr("href").contains(it, true) } }
            .filterNot { it.isInsideNoiseBlock() }
            .map { it.text().cleanText() }
            .filter { it.isLikelyPersonName() }
            .distinct()
            .take(20)
    }

    private fun Document.extractYear(): Int? {
        val linkYear = select("a[href*='/year/'], a[href*='movieyear'], a[href*='/release/']")
            .mapNotNull { Regex("(?:19|20)\\d{2}").find(it.text())?.value?.toIntOrNull() }
            .firstOrNull()
        return linkYear ?: Regex("(?:19|20)\\d{2}").find(text())?.value?.toIntOrNull()
    }

    private fun Document.extractStatus(): ShowStatus? {
        return listOfNotNull(
            selectFirst(".status, .dtstatus, [class*=status]")?.text(),
            text()
        ).firstNotNullOfOrNull { it.toShowStatus() }
    }

    private fun Document.extractEpisodes(baseUrl: String): List<Episode> {
        val episodeContainers = select(
            ".gmr-listseries, .episodios, .se-c, .episodelist, .episode-list, .les-content, .eplister, .listing, .entry-content"
        ).filterNot { it.isInsideNoiseBlock() }
        val episodeLinks = if (episodeContainers.isNotEmpty()) {
            episodeContainers.flatMap { it.select("a[href*='/eps/'], a[href*='/episode/']") }
        } else {
            select("article a[href*='/eps/'], .entry-content a[href*='/eps/'], .post a[href*='/eps/'], a[href*='/eps/'], a[href*='/episode/']")
                .filterNot { it.isInsideNoiseBlock() }
        }

        return episodeLinks.mapNotNull { it.toEpisode(baseUrl) }
            .distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.episode ?: Int.MAX_VALUE }.thenBy { it.name })
    }

    private fun Element.toEpisode(baseUrl: String): Episode? {
        val href = attr("href").absoluteUrl(baseUrl) ?: return null
        if (!href.isEpisodeUrl()) return null
        val rawText = attr("title").ifBlank { text() }.ifBlank { href.slugTitle() }
        val epNum = Regex("(?i)(?:EP|Episode|Eps?\\.?|Ep\\.?|S\\d+\\s*Eps?)\\s*(\\d+)").find(rawText)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("(?i)(?:episode|eps?)[-_ ]?(\\d+)").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val cleaned = rawText.cleanTitle().cleanEpisodeTitle(epNum)
        return newEpisode(href.toPlaybackData(baseUrl)) {
            name = cleaned.ifBlank { "Episode ${epNum ?: ""}".trim() }
            episode = epNum
            posterUrl = selectFirst("img")?.imageUrl(baseUrl)
        }
    }

    private fun Document.extractRecommendations(pageUrl: String): List<SearchResponse> {
        return select(".related, .recommendations, .owl-carousel, .section, .latest, .archive")
            .flatMap { it.toSearchResultsLike(pageUrl) }
            .filterNot { it.url == pageUrl }
            .distinctBy { it.url }
            .take(20)
    }

    private fun Element.toSearchResultsLike(pageUrl: String): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        select("article, .item, .movie-item, .module, .owl-item, .result-item, .post, .card, .poster").forEach { card ->
            card.toSearchResult(pageUrl)?.let { if (it.url != pageUrl) results[it.url] = it }
        }
        return results.values.toList()
    }

    private fun Document.extractServerTabs(pageUrl: String): List<String> {
        val pageHost = pageUrl.hostOrEmpty()
        return select(".muvipro-player-tabs a[href], a[href*='?player=']")
            .filterNot { it.attr("href").trim().startsWith("#") }
            .mapNotNull { it.attr("href").absoluteUrl(pageUrl) }
            .filter { it.hostOrEmpty() == pageHost }
            .distinct()
    }

    private fun Document.extractIframeSources(pageUrl: String): List<String> {
        val raw = mutableListOf<String>()
        select(
            "iframe, embed, video[src], video source[src], source[src], " +
                "[data-iframe], [data-embed], [data-litespeed-src], [data-player], [data-video]"
        ).filterNot { it.isInsideNoiseBlock() }.forEach { element ->
            listOf(
                "src", "data-src", "data-litespeed-src", "data-lazy-src", "data-lazy", "data-url",
                "data-iframe", "data-embed", "data-player", "data-video", "data-link"
            ).forEach { attr -> raw.add(element.attr(attr)) }
        }
        html().extractIframeSrcsFromText().forEach { raw.add(it) }

        return raw.asSequence()
            .mapNotNull { it.decodeCandidate()?.absoluteUrl(pageUrl) }
            .filter { it.startsWith("http", true) }
            .filterNot { it.isMediaAssetUrl() || it.isBlockedHost() }
            .distinct()
            .toList()
    }

    private fun String.extractIframeSrcsFromText(): List<String> {
        val clean = replace("\\/", "/").replace("&amp;", "&").replace("\\\"", "\"").replace("\\'", "'")
        val results = mutableListOf<String>()
        IFRAME_TAG_REGEX.findAll(clean).forEach { tag ->
            IFRAME_ATTR_REGEX.findAll(tag.value).forEach { results.add(it.groupValues[1]) }
        }
        return results.distinct()
    }

    /** Mengambil URL get_video? (gaya Streamtape) termasuk yang dirakit lewat JS substring(). */
    private fun String.extractGetVideoUrls(baseUrl: String): List<String> {
        val clean = replace("\\/", "/").replace("&amp;", "&")
        val dynamic = mutableListOf<String>()
        INNER_HTML_REGEX.findAll(clean).forEach { match ->
            val expression = match.groupValues[2]
            if (expression.contains("get_video", true)) dynamic.add(evalJsConcat(expression))
        }
        val static = STATIC_GET_VIDEO_REGEX.findAll(clean).map { it.value }.toList()
        return (dynamic.reversed() + static)
            .map { it.normalizeGetVideoUrl(baseUrl) }
            .filter { it.contains("get_video?", true) }
            .distinct()
    }

    private fun evalJsConcat(expression: String): String {
        val output = StringBuilder()
        JS_STRING_PART_REGEX.findAll(expression).forEach { match ->
            var value = match.groupValues[1].ifEmpty { match.groupValues[2] }
            SUBSTRING_REGEX.findAll(match.groupValues[3]).forEach { sub ->
                val start = sub.groupValues[1].toIntOrNull() ?: 0
                value = if (start <= value.length) value.substring(start) else ""
            }
            output.append(value)
        }
        return output.toString()
    }

    private fun String.normalizeGetVideoUrl(baseUrl: String): String {
        val raw = trim().trim(' ', '\'', '"').replace("\\/", "/").replace("&amp;", "&")
        return when {
            raw.startsWith("http", true) -> raw
            raw.startsWith("//") -> "https:$raw"
            Regex("""^/[A-Za-z0-9.-]+\.[A-Za-z]{2,}/""").containsMatchIn(raw) -> "https:/$raw"
            else -> raw.absoluteUrl(baseUrl) ?: raw
        }
    }

    private fun String.hostOrEmpty(): String {
        return HOST_REGEX.find(trim())?.groupValues?.get(1)?.removePrefix("www.")?.lowercase().orEmpty()
    }

    /** Semua subdomain drakor-id.top (kr., nonton., www.) dianggap situs sendiri. */
    private fun String.isOwnSiteHost(): Boolean {
        val h = hostOrEmpty()
        if (h.isBlank()) return false
        return h == SITE_DOMAIN || h.endsWith(".$SITE_DOMAIN") || h == mainUrl.hostOrEmpty()
    }

    private fun String.replaceHost(newHost: String): String? {
        return runCatching {
            val uri = URI(this)
            URI(uri.scheme ?: "https", newHost, uri.path, uri.query, uri.fragment).toString()
        }.getOrNull()
    }

    private fun String.isBlockedHost(): Boolean {
        val host = hostOrEmpty()
        if (host == "drive.google.com") return false
        return BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private fun String.extractMediaUrls(): List<String> {
        return MEDIA_URL_REGEX.findAll(replace("\\/", "/").replace("&amp;", "&"))
            .map { it.value.cleanMediaUrl() }
            .distinct()
            .toList()
    }

    private fun String.extractSubtitleUrls(): List<String> {
        return Regex("""https?://[^'"<>\s]+?\.(?:vtt|srt)(?:\?[^'"<>\s]*)?""", RegexOption.IGNORE_CASE)
            .findAll(replace("\\/", "/").replace("&amp;", "&"))
            .map { it.value.cleanMediaUrl() }
            .distinct()
            .toList()
    }

    private fun Document.extractSeriesUrl(baseUrl: String): String? {
        // Jangan ambil link menu "TV Shows" (/tv/) di header - hanya halaman detail /tv/<slug>/
        selectFirst("a.gmr-all-serie[href]")?.attr("href")?.absoluteUrl(baseUrl)
            ?.takeIf { it.isSeriesDetailUrl() }?.let { return it }
        return select("a[href*='/tv/']")
            .filterNot { it.isInsideNoiseBlock() }
            .mapNotNull { it.attr("href").absoluteUrl(baseUrl) }
            .firstOrNull { it.isSeriesDetailUrl() }
    }

    private fun String.isSeriesDetailUrl(): Boolean {
        val path = runCatching { URI(this).path.lowercase().trim('/') }.getOrNull() ?: return false
        if (!path.startsWith("tv/")) return false
        val slug = path.removePrefix("tv/")
        return slug.isNotBlank() && !slug.startsWith("page")
    }

    private fun Element.imageUrl(baseUrl: String): String? {
        val raw = attr("content")
            .ifBlank { attr("data-src") }
            .ifBlank { attr("data-lazy-src") }
            .ifBlank { attr("data-original") }
            .ifBlank { attr("data-poster") }
            .ifBlank { attr("poster") }
            .ifBlank { attr("srcset").srcSetFirst() }
            .ifBlank { attr("src") }
        return raw.absoluteUrl(baseUrl)?.takeIf { it.isValidImageUrl() }
    }

    private fun String?.absoluteUrl(baseUrl: String): String? {
        val raw = this?.trim()?.replace("&amp;", "&")?.replace("\\/", "/") ?: return null
        if (raw.isBlank() || raw == "#" || raw.startsWith("javascript", true) || raw.startsWith("mailto:", true)) return null
        val normalized = if (raw.startsWith("//")) "https:$raw" else raw
        return runCatching { URI(baseUrl).resolve(normalized).toString() }.getOrNull()
    }

    private fun String?.decodeCandidate(): String? {
        val raw = this?.trim()?.trim(' ', '\'', '"') ?: return null
        if (raw.isBlank()) return null
        val clean = raw.replace("&quot;", "\"").replace("&#039;", "'").replace("&#038;", "&")
            .replace("&amp;", "&").replace("\\/", "/").trim(' ', '\'', '"')

        // Nilai atribut berupa potongan HTML: <iframe src="...">
        if (clean.contains("<iframe", true) || clean.contains("<embed", true)) {
            return clean.extractIframeSrcsFromText().firstOrNull()
        }
        if (clean.startsWith("http", true) || clean.startsWith("//") || clean.startsWith("/") ||
            clean.startsWith("./") || clean.startsWith("../")) return clean

        if (clean.length > 16 && clean.matches(Regex("^[A-Za-z0-9+/=_-]+$"))) {
            val decoded = runCatching { base64Decode(clean) }.getOrNull()
            if (decoded != null && (decoded.contains("http", true) || decoded.contains("iframe", true))) {
                return decoded.decodeCandidate()
            }
        }
        return Regex("https?://[^'\"<>\\s]+", RegexOption.IGNORE_CASE).find(clean)?.value
    }

    private fun String.isContentUrl(): Boolean {
        val path = runCatching { URI(this).path.lowercase() }.getOrNull() ?: return false
        return path.startsWith("/eps/") || path.startsWith("/episode/") || path.startsWith("/movie/") || path.startsWith("/tv/") || isRootMovieUrl()
    }

    private fun String.isRootMovieUrl(): Boolean {
        val path = runCatching { URI(this).path.lowercase().trim('/') }.getOrNull() ?: return false
        if (path.isBlank() || path.contains('/')) return false
        if (path.length < 4 || path.matches(Regex("\\d{4}"))) return false
        val blockedExact = setOf(
            "index-movie", "cara-download", "menampilkan-subtitle-setelah-download", "order-by-title",
            "best-rating", "hd", "home", "tv", "tv-shows", "c-drama", "drakor", "film-india",
            "contact", "privacy-policy", "privacy", "dmca", "disclaimer", "terms", "term-of-service", "sitemap"
        )
        val blockedPrefixes = listOf(
            "genre", "country", "blog-category", "tag", "cast", "actor", "actors", "creator",
            "director", "star", "stars", "quality", "year", "page", "search", "category",
            "wp-admin", "wp-content", "wp-json", "author", "feed"
        )
        if (path in blockedExact) return false
        if (blockedPrefixes.any { path == it || path.startsWith("$it-") }) return false
        return true
    }

    private fun String.isEpisodeUrl(): Boolean {
        val path = runCatching { URI(this).path.lowercase() }.getOrNull() ?: lowercase()
        return path.startsWith("/eps/") || path.startsWith("/episode/")
    }

    private fun String.isEvidenceMediaUrl(): Boolean {
        val lower = lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".mkv") ||
            lower.contains("videoplayback") || lower.contains("/get_video?")
    }

    private fun String.isMediaAssetUrl(): Boolean {
        val path = runCatching { URI(this).path.lowercase() }.getOrDefault(lowercase())
        return path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") || path.endsWith(".webp") ||
            path.endsWith(".gif") || path.endsWith(".svg") || path.endsWith(".ico") ||
            path.contains("/wp-content/uploads/") && !path.contains(".m3u8") && !path.contains(".mp4")
    }

    private fun String.playbackHeaders(refererUrl: String): Map<String, String> {
        val map = mutableMapOf("Referer" to refererUrl, "User-Agent" to USER_AGENT)
        refererUrl.origin()?.let { map["Origin"] = it }
        return map
    }

    private fun String.toPlaybackData(refererUrl: String): String {
        return if (this == refererUrl) this else JSONObject().put("url", this).put("referer", refererUrl).toString()
    }

    private fun String.parsePlaybackData(): Pair<String, String> {
        return runCatching {
            val json = JSONObject(this)
            val url = json.optString("url").ifBlank { this }
            val referer = json.optString("referer").ifBlank { "$mainUrl/" }
            url to referer
        }.getOrDefault(this to (this.origin()?.let { "$it/" } ?: "$mainUrl/"))
    }

    private fun String.cleanTitle(): String {
        return replace(Regex("(?i)^\\s*Permalink\\s+(?:to|ke)\\s*:?\\s*"), "")
            .replace(Regex("(?i)^\\s*Watch\\s+Movie\\s*"), "")
            .replace(Regex("(?i)^\\s*Trailer\\s*"), "")
            .replace("Drakor ID", "", ignoreCase = true)
            .replace("Nonton", "", ignoreCase = true)
            .replace("Streaming", "", ignoreCase = true)
            .replace("Subtitle Indonesia", "", ignoreCase = true)
            .replace("Sub Indo", "", ignoreCase = true)
            .replace("Watch Movie", "", ignoreCase = true)
            .replace(Regex("(?i)\\|.*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '|', '⋆', ':')
    }

    private fun String.cleanEpisodeTitle(epNum: Int?): String {
        if (epNum != null) return "Episode $epNum"
        val found = Regex("(?i)(?:episode|eps?)\\s*(\\d+)").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return if (found != null) "Episode $found" else this
    }

    private fun String.cleanText(): String = Jsoup.parse(this).text().replace(Regex("\\s+"), " ").trim()

    private fun String.stripDetailMetadata(): String {
        var output = cleanText()
        val markers = listOf(
            " By:", " Posted on:", " Views:", " Tagline:", " Genre:", " Quality:", " Year:", " Duration:",
            " Country:", " Release:", " Last Air Date:", " Number Of Episode:", " Network:", " Cast:", " Director:", " Stars:"
        )
        markers.forEach { marker ->
            val index = output.indexOf(marker, ignoreCase = true)
            if (index > 0) output = output.substring(0, index).trim()
        }
        return output.replace(Regex("\\s+"), " ").trim()
    }

    private fun String.cleanMediaUrl(): String {
        val cleaned = replace("\\/", "/").replace("&amp;", "&").trim(' ', '\'', '"')
        // JANGAN URL-decode sembarangan: token/signature (%2B, %3D, '+') akan rusak.
        // Decode hanya jika seluruh URL memang ter-encode (http%3A%2F%2F...).
        return if (cleaned.startsWith("http%3A", true) || cleaned.startsWith("https%3A", true)) {
            runCatching { URLDecoder.decode(cleaned, "UTF-8") }.getOrDefault(cleaned)
        } else cleaned
    }

    private fun String.srcSetFirst(): String {
        return split(",").map { it.trim().split(Regex("\\s+")).firstOrNull().orEmpty() }.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun String.slugTitle(): String {
        return substringBefore('?').trimEnd('/').substringAfterLast('/').replace('-', ' ').cleanTitle()
    }

    private fun List<String?>.firstCleanTitle(): String? {
        return mapNotNull { it?.cleanTitle()?.takeIf { cleaned -> cleaned.isNotBlank() && !cleaned.isNoisyTitle() } }.firstOrNull()
    }

    private fun String.toShowStatus(): ShowStatus? {
        val lower = lowercase()
        return when {
            lower.contains("completed") || lower.contains("tamat") || lower.contains("end") || lower.contains("complete") -> ShowStatus.Completed
            lower.contains("ongoing") || lower.contains("on-going") || lower.contains("berjalan") -> ShowStatus.Ongoing
            else -> null
        }
    }

    private fun String.qualityFromUrl(): Int {
        val lower = lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k") -> Qualities.P2160.value
            lower.contains("1080") -> Qualities.P1080.value
            lower.contains("720") -> Qualities.P720.value
            lower.contains("480") -> Qualities.P480.value
            lower.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun String.origin(): String? {
        return runCatching {
            val uri = URI(this)
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            "$scheme://$host"
        }.getOrNull()
    }

    private fun String.hostLabel(): String {
        return runCatching { URI(this).host?.removePrefix("www.")?.substringBefore('.')?.replaceFirstChar { it.uppercase() } }
            .getOrNull() ?: name
    }

    private fun String.subtitleLabel(): String {
        val file = substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
        return file.substringAfterLast('_').substringAfterLast('-').ifBlank { "Subtitle" }
    }

    private fun String.isValidImageUrl(): Boolean {
        val lower = lowercase()
        return startsWith("http", true) && !lower.startsWith("data:") && !lower.contains("blank") && !lower.contains("placeholder") && !lower.endsWith(".svg")
    }

    private fun String.isNoisyTitle(): Boolean {
        val lower = lowercase()
        return lower.isBlank() || lower == "watch movie" || lower == "watch" || lower == "trailer" || lower == "hd" || lower == "play" ||
            lower.startsWith("permalink to:") || lower.startsWith("permalink ke:")
    }

    private fun String.isValidPlot(): Boolean {
        if (isBlank() || length < 40) return false
        val lower = lowercase()
        val seo = listOf("subtitle indonesia", "streaming drakor", "download video", "drakor-id", "full episode", "gambar lebih jernih")
        return seo.none { lower.contains(it) }
    }

    private fun String.isLikelyPersonName(): Boolean {
        if (isBlank() || length < 2 || length > 60) return false
        val lower = lowercase()
        if (lower.contains("watch") || lower.contains("trailer") || lower.contains("episode") || lower.contains("season")) return false
        return true
    }

    private fun Element.isInsideNoiseBlock(): Boolean {
        return parents().any { it.`is`("header, footer, nav, .menu, .navbar, .sidebar") }
    }

    companion object {
        private const val SITE_DOMAIN = "drakor-id.top"
        private val ALT_HOSTS = listOf("kr.drakor-id.top", "nonton.drakor-id.top")
        private val MEDIA_URL_REGEX = Regex("""https?://[^'"<>()\s]+?(?:\.mp4|\.m3u8|\.webm|\.mkv|videoplayback|get_video\?)[^'"<>()\s]*""", RegexOption.IGNORE_CASE)
        private val IFRAME_TAG_REGEX = Regex("""<(?:iframe|embed)\b[^>]*>""", RegexOption.IGNORE_CASE)
        private val IFRAME_ATTR_REGEX = Regex("""(?:data-litespeed-src|data-lazy-src|data-src|src)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val EMBED_URL_REGEX = Regex("""["']embed_url["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val ATOB_REGEX = Regex("""atob\(\s*['"]([A-Za-z0-9+/=_-]{16,})['"]\s*\)""")
        private val HOST_REGEX = Regex("""^(?:https?:)?//([^/:?#]+)""", RegexOption.IGNORE_CASE)
        private val INNER_HTML_REGEX = Regex("""getElementById\(\s*['"]([^'"]+)['"]\s*\)\.innerHTML\s*=\s*([^;]+);""")
        private val JS_STRING_PART_REGEX = Regex("""(?:'([^']*)'|"([^"]*)")\s*\)?((?:\s*\.\s*substring\(\s*\d+\s*\))*)""")
        private val SUBSTRING_REGEX = Regex("""substring\(\s*(\d+)\s*\)""")
        private val STATIC_GET_VIDEO_REGEX = Regex(
            """(?:https?:)?//[A-Za-z0-9.-]+/get_video\?[^'"<>\s]+|/[A-Za-z0-9.-]+\.[A-Za-z]{2,}/get_video\?[^'"<>\s]+""",
            RegexOption.IGNORE_CASE
        )
        private val BLOCKED_HOSTS = listOf(
            "youtube.com", "youtu.be", "facebook.com", "twitter.com", "x.com", "instagram.com", "t.me",
            "telegram.me", "whatsapp.com", "tiktok.com", "google.com", "googleapis.com", "gstatic.com",
            "googletagmanager.com", "google-analytics.com", "doubleclick.net", "googlesyndication.com",
            "histats.com", "disqus.com", "gravatar.com", "wp.com", "w.org", "schema.org",
            "fontawesome.com", "jquery.com", "bootstrapcdn.com", "cdnjs.cloudflare.com", "jsdelivr.net"
        )
    }
}