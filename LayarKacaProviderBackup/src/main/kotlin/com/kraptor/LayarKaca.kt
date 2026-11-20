// ! Bu araç @Kraptor123 tarafından | @kekikanime için yazılmıştır.

package com.kraptor

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.module.kotlin.readValue
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.nicehttp.NiceResponse
import org.json.JSONObject
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

class LayarKaca : MainAPI() {
    override var mainUrl              = "https://tv12.lk21official.cc"
    override var name                 = "LayarKaca"
    override val hasMainPage          = true
    override var lang                 = "id"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)
    //Movie, AnimeMovie, TvSeries, Cartoon, Anime, OVA, Torrent, Documentary, AsianDrama, Live, NSFW, Others, Music, AudioBook, CustomMedia, Audio, Podcast,

    // Domain series (Nontondrama) sering berganti. Nilai awal diambil dari halaman terbaru (canonical),
    // lalu otomatis diperbarui lewat ensureSeriesUrl() bila domain ini sudah mati.
    private var seriesUrl = "https://tv.nontondrama.vip"
    private var seriesChecked = false

    // Awalan khusus: host series diganti saat runtime (lihat getMainPage).
    private val seriesPrefix = "SERIES:"

    override val mainPage = mainPageOf(
        "${mainUrl}/populer"              to "Popular",
        "${seriesPrefix}/latest-series"   to "Series Terbaru",
        "${mainUrl}/quality/bluray"       to "Bluray",
        "${mainUrl}/country/japan"        to "Jepang",
        "${mainUrl}/country/china"        to "Cina",
        "${mainUrl}/genre/action"         to "Action",
        "${mainUrl}/genre/adventure"      to "Adventure",
        "${mainUrl}/genre/animation"      to "Animation",
        "${mainUrl}/genre/biography"      to "Biography",
        "${mainUrl}/genre/comedy"         to "Comedy",
        "${mainUrl}/genre/crime"          to "Crime",
        "${mainUrl}/genre/documentary"    to "Documentary",
        "${mainUrl}/genre/drama"          to "Drama",
        "${mainUrl}/genre/family"         to "Family",
        "${mainUrl}/genre/fantasy"        to "Fantasy",
        "${mainUrl}/genre/history"        to "History",
        "${mainUrl}/genre/horror"         to "Horror",
        "${mainUrl}/genre/musical"        to "Musical",
        "${mainUrl}/genre/mystery"        to "Mystery",
        "${mainUrl}/genre/romance"        to "Romance",
        "${mainUrl}/genre/sci-fi"         to "Sci-Fi",
        "${mainUrl}/genre/sport"          to "Sport",
        "${mainUrl}/genre/thriller"       to "Thriller",
        "${mainUrl}/genre/war"            to "War",
        "${mainUrl}/genre/western"        to "Western",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = if (request.data.startsWith(seriesPrefix))
            ensureSeriesUrl() + request.data.removePrefix(seriesPrefix)
        else request.data

        val res      = app.get("$base/page/$page")
        val host     = runCatching { getBaseUrl(res.url) }.getOrDefault(mainUrl)
        val home     = res.documentLarge.select("div.gallery-grid article a").mapNotNull { it.toMainPageResult(host) }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(host: String): SearchResponse? {
        val title     = this.selectFirst("h3")?.text() ?: return null
        val raw       = this.attr("href").trim()
        if (raw.isEmpty()) return null
        // href relatif harus mengikuti host halaman asalnya (series -> domain nontondrama)
        val href      = if (raw.startsWith("http")) raw else host.trimEnd('/') + "/" + raw.trimStart('/')
        val img       = this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-src").takeUnless { it.isNullOrEmpty() } ?: img?.attr("src"))
        val score     = this.selectFirst("span[itemprop=ratingValue]")?.text()
            ?: this.selectFirst("span.rating")?.text()?.trim()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.score     = Score.from10(score)
        }
    }


    override suspend fun search(query: String, page: Int): SearchResponseList {
        val document = app.get("https://gudangvape.com/search.php?s=$query&page=$page", referer = "${mainUrl}/").text
        val mapper = mapper.readValue<SearchApi>(document)
        val aramaCevap = mapper.data.mapNotNull { it.toSearchResult() }

        return newSearchResponseList(aramaCevap, hasNext = true)
    }

    private fun Data.toSearchResult(): SearchResponse? {
        val title = this.title
        val href = fixUrlNull(this.slug) ?: return null
        val posterUrl = "https://static-jpg.lk21.party/wp-content/uploads/${this.poster}"
        val score = this.rating

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.score = Score.from10(score)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse {
        Log.d("kraptor_$name", "Loading URL: $url")
        val pageRes = fetchProper(url)
        val document = pageRes.documentLarge
        // Host halaman yang BENAR-BENAR dibuka (bisa berbeda dari domain hardcode karena domain rotasi)
        val pageBase = runCatching { getBaseUrl(pageRes.url) }.getOrDefault(mainUrl)
        val title = document.selectFirst("div.movie-info h1")?.text()?.trim().toString()
        val poster = document.select("meta[property=og:image]").attr("content")
        val tags = document.select("div.tag-list span").map { it.text() }
        val posterheaders = mapOf("Referer" to getBaseUrl(poster))

        // Tahun: prioritaskan "(2009)" di judul agar judul seperti "1917" tidak salah terbaca sebagai tahun
        val yearInTitle = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val yil = yearInTitle ?: Regex("\\d{4}").find(title)?.value?.toIntOrNull()
        val tmdbYear = yearInTitle
            ?: Regex("\\b(19|20)\\d{2}\\b").find(document.select("div.info-tag").text())?.value?.toIntOrNull()

        val tvType = if (document.selectFirst("#season-data") != null) TvType.TvSeries else TvType.Movie

        // Prioritas pertama: poster, backdrop & logo judul dari TMDB (hanya jika judul exact).
        // Jika tidak tersedia / tidak exact -> pakai data LK21 seperti sebelumnya.
        val tmdb = TmdbHelper.fetchArtwork(title, tmdbYear, tvType == TvType.TvSeries, lang)
        val finalPoster = tmdb?.posterUrl ?: poster
        val finalPosterHeaders = if (tmdb?.posterUrl != null) null else posterheaders
        val description = document.selectFirst("div.meta-info")?.text()?.trim()
        val trailer = document.selectFirst("ul.action-left a.yt-lightbox")?.attr("href")
            ?: document.selectFirst("ul.action-left > li:nth-child(3) > a")?.attr("href")
        // Rating ada di span.rating-number (div.info-tag strong sudah tidak ada di HTML terbaru)
        val rating = document.selectFirst("span.rating-number")?.text()
            ?: document.selectFirst("div.info-tag strong")?.text()

        val recommendations = document.select("ul.video-list li").map {
            val recName = it.selectFirst("span.video-title")?.text()?.trim().toString()
            val rawHref = it.selectFirst("a")?.attr("href").toString()
            val cleanHref = if (rawHref.startsWith("/")) rawHref else "/$rawHref"
            val recHref = fixUrl("$mainUrl$cleanHref")
            val recPosterUrl = fixUrl(
                it.selectFirst("img")?.attr("data-src")?.ifEmpty { null }
                    ?: it.selectFirst("img")?.attr("src").toString()
            )
            newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                this.posterUrl = recPosterUrl
                this.posterHeaders = posterheaders
            }
        }

        return if (tvType == TvType.TvSeries) {
            if (pageBase != getBaseUrl(mainUrl)) seriesUrl = pageBase

            val json = document.selectFirst("script#season-data")?.data()
            val rawEpisodes = mutableListOf<Triple<String, Int, Int>>() // href, season, episode
            if (json != null) {
                val root = JSONObject(json)
                root.keys().forEach { seasonKey ->
                    val seasonArr = root.getJSONArray(seasonKey)
                    for (i in 0 until seasonArr.length()) {
                        val ep = seasonArr.getJSONObject(i)
                        val href = "$pageBase/" + ep.getString("slug")
                        rawEpisodes.add(Triple(href, ep.optInt("s"), ep.optInt("episode_no")))
                    }
                }
            }

            // Judul, sinopsis, rating, tanggal rilis & banner per episode dari TMDB.
            // Field yang tidak tersedia di TMDB memakai data LK21 seperti sebelumnya.
            val episodeInfo: Map<Pair<Int, Int>, TmdbEpisode> =
                if (tmdb != null)
                    TmdbHelper.fetchEpisodes(tmdb.tmdbId, rawEpisodes.map { it.second }.distinct(), lang)
                else emptyMap()

            val episodes = rawEpisodes.map { (href, seasonNo, episodeNo) ->
                val tm = episodeInfo[Pair(seasonNo, episodeNo)]
                newEpisode(href) {
                    this.name = tm?.name ?: "Episode $episodeNo"
                    this.season = seasonNo
                    this.episode = episodeNo
                    this.posterUrl = tm?.stillUrl
                    this.description = tm?.overview
                    tm?.rating?.let { this.score = Score.from10(it.toString()) }
                    tm?.airDate?.let { addDate(it) }
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = finalPoster
                this.posterHeaders = finalPosterHeaders
                this.backgroundPosterUrl = tmdb?.backdropUrl
                this.logoUrl = tmdb?.logoUrl
                this.year = yil
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = finalPoster
                this.posterHeaders = finalPosterHeaders
                this.backgroundPosterUrl = tmdb?.backdropUrl
                this.logoUrl = tmdb?.logoUrl
                this.year = yil
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        }
    }

    // ===================== loadLinks =====================

    private val playerUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val mediaRegex =
        Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)

    private val anyUrlRegex = Regex("""https?://[^\s"'<>\\)]+""", RegexOption.IGNORE_CASE)

    private val atobRegex = Regex("""atob\(\s*["']([A-Za-z0-9+/=_\-]{16,})["']\s*\)""")

    private val cloudflareHints = listOf("just a moment", "attention required", "cf-chl", "checking your browser")

    private val ignoredExtensions = listOf(
        ".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".css", ".js", ".ico",
        ".woff", ".woff2", ".ttf", ".map", ".json"
    )

    // Host/iklan yang bukan player video
    private val junkHosts = listOf(
        "google", "gstatic", "cloudflare", "histats", "w3.org", "schema.org", "youtube", "facebook",
        "twitter", "instagram", "t.me", "jsdelivr", "cdnjs", "unpkg", "fontawesome", "stopjudi",
        "showcdnx", "s.id/", "donasi", "gmpg.org", "x.com"
    )

    private val playerLike = listOf("embed", "player", "iframe", "video", "stream", "/e/", "/v/", "p2p", "cast", "hydrax")

    private fun isJunk(u: String) = junkHosts.any { u.contains(it, ignoreCase = true) }

    private fun cleanUrl(raw: String?, base: String): String? {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty() || v.startsWith("#") || v.startsWith("javascript", true) || v.startsWith("about:", true)) return null
        val abs = when {
            v.startsWith("//") -> "https:$v"
            v.startsWith("http", true) -> v
            v.startsWith("/") -> base.trimEnd('/') + v
            else -> return null
        }
        val path = abs.substringBefore("?").lowercase()
        return if (ignoredExtensions.any { path.endsWith(it) }) null else abs
    }

    /** Kumpulkan URL player dari daftar server, dropdown, atribut data-*, dan iframe langsung di halaman. */
    private fun collectPlayerUrls(document: org.jsoup.nodes.Document, pageUrl: String): List<String> {
        val base = getBaseUrl(pageUrl)
        val out = LinkedHashMap<String, String>() // kunci tanpa query -> url
        fun add(u: String?) {
            val c = cleanUrl(u, base) ?: return
            if (isJunk(c)) return
            val normalized = c.removeSuffix("?v=1")
            out.putIfAbsent(normalized.substringBefore("?"), normalized)
        }
        // Urutan: daftar server dulu (p2p, cast, hydrax), baru iframe default
        document.select("ul#player-list a, #player-list a").forEach { el ->
            add(el.attr("data-url")); add(el.attr("href"))
        }
        document.select("#player-select option[value]").forEach { add(it.attr("value")) }
        document.select("[data-url], [data-embed], [data-player]").forEach { el ->
            listOf("data-url", "data-embed", "data-player").forEach { add(el.attr(it)) }
        }
        document.select("iframe[src], iframe[data-src]").forEach { el ->
            add(el.attr("src").ifBlank { el.attr("data-src") })
        }
        return out.values.toList()
    }

    private suspend fun emitMedia(
        mediaUrl: String,
        referer: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val isM3u8 = mediaUrl.contains(".m3u8", ignoreCase = true)
        callback.invoke(
            newExtractorLink(
                source = name,
                name = label,
                url = mediaUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to playerUserAgent, "Referer" to referer)
            }
        )
    }

    /** Teks yang dipindai: HTML + isi string base64 dari atob("...") yang sering dipakai menyembunyikan URL. */
    private fun buildScanText(html: String): String {
        val sb = StringBuilder(html.replace("\\/", "/"))
        atobRegex.findAll(html).forEach { m ->
            runCatching {
                val s = m.groupValues[1].replace('-', '+').replace('_', '/')
                sb.append('\n').append(String(Base64.decode(s, Base64.DEFAULT)).replace("\\/", "/"))
            }
        }
        return sb.toString()
    }

    private fun isShortLink(u: String) = u.contains("short.icu") || u.contains("short.ink")

    /**
     * Ikuti rantai player (videonode -> hownetwork/abyss/dll -> host video) sampai 4 tingkat.
     * Di tiap tingkat: coba extractor terdaftar, cari link media langsung di HTML/JS (termasuk atob),
     * lalu coba/ikuti iframe dan URL player bersarang. Dilengkapi daftar `visited` agar tidak berputar.
     * Setiap langkah dicatat di logcat (tag kraptor_LayarKaca).
     */
    private suspend fun resolvePlayer(
        url: String,
        referer: String,
        depth: Int,
        found: AtomicInteger,
        visited: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (depth > 3 || !visited.add(url.substringBefore("#"))) return
        Log.d("kraptor_$name", "resolve[$depth] $url (referer=$referer)")

        // Host yang dikenal (Hownetwork, Furher, Abyss, dll.)
        val before = found.get()
        runCatching { loadExtractor(url, referer, subtitleCallback, callback) }
        if (found.get() > before) return

        val res = try {
            app.get(url, referer = referer, headers = mapOf("User-Agent" to playerUserAgent, "Referer" to referer))
        } catch (e: Exception) {
            Log.e("kraptor_$name", "resolve[$depth] gagal membuka $url: ${e.message}")
            return
        }
        val html = res.text
        val pageUrl = res.url
        if (cloudflareHints.any { html.contains(it, ignoreCase = true) }) {
            Log.w("kraptor_$name", "resolve[$depth] kemungkinan tantangan Cloudflare di $pageUrl (HTTP ${res.code})")
        }
        val scanText = buildScanText(html)

        // 1) Link media langsung di HTML / JS
        val medias = mediaRegex.findAll(scanText).map { it.value }.filter { !isJunk(it) }.distinct().toList()
        if (medias.isNotEmpty()) {
            Log.d("kraptor_$name", "resolve[$depth] media langsung: ${medias.size}")
            medias.forEach {
                if (it.contains("playcdn", ignoreCase = true)) emitHls(it, "LK21 P2P", getBaseUrl(pageUrl) + "/", callback)
                else emitMedia(it, pageUrl, "LK21", callback)
            }
            return
        }

        // 2) Iframe bersarang + semua URL lain yang mirip player
        val base = getBaseUrl(pageUrl)
        val iframes = res.documentLarge.select("iframe[src], iframe[data-src]")
            .mapNotNull { cleanUrl(it.attr("src").ifBlank { it.attr("data-src") }, base) }
            .filter { !isJunk(it) }
            .distinct()
        val others = anyUrlRegex.findAll(scanText)
            .map { it.value.trimEnd('.', ',', ';', ']', '}') }
            .mapNotNull { cleanUrl(it, base) }
            .filter { !isJunk(it) && it !in iframes && it != url }
            .distinct()
            .take(20)
            .toList()
        Log.d("kraptor_$name", "resolve[$depth] iframe=${iframes.size} $iframes | url lain=${others.size}")

        val nested = iframes.map { it to true } + others.map { it to false }
        for ((candidate, isIframe) in nested) {
            val b = found.get()
            runCatching { loadExtractor(candidate, pageUrl, subtitleCallback, callback) }
            if (found.get() > b) continue

            var target = candidate
            if (isShortLink(target)) {
                target = runCatching { app.get(target, allowRedirects = true).url }.getOrDefault(target)
                if (target != candidate) {
                    runCatching { loadExtractor(target, pageUrl, subtitleCallback, callback) }
                    if (found.get() > b) continue
                }
            }
            if (isIframe || playerLike.any { target.contains(it, ignoreCase = true) }) {
                resolvePlayer(target, pageUrl, depth + 1, found, visited, subtitleCallback, callback)
            }
        }

        if (iframes.isEmpty() && others.isEmpty()) {
            Log.w(
                "kraptor_$name",
                "resolve[$depth] tidak ada iframe/media di $pageUrl. Cuplikan HTML: ${html.take(300).replace("\n", " ")}"
            )
        }
    }

    // ===================== P2P (videonode.de/iframe3/p2p/...) =====================

    // Playlist P2P bentuknya: https://stream.playcdn.de/playlist/<hash>/1/0.m3u8?x=1
    // Host playlist memeriksa Referer/Origin dari halaman player (videonode.de).

    private val p2pPlaylistRegex =
        Regex("""https?://[^\s"'<>\\]*playcdn[^\s"'<>\\]*\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)

    // Klik otomatis tombol play agar player P2P mau meminta playlist .m3u8
    private val autoPlayScript = """
        (function(){
          var n=0;
          var sel=['.jw-icon-display','.jw-display-icon-container','.vjs-big-play-button','.plyr__control--overlaid','.play-button','#play','[aria-label*="lay"]','[class*="play"]'];
          var t=setInterval(function(){
            try{
              var v=document.querySelector('video');
              if(v){v.muted=true;var p=v.play();if(p&&p.catch){p.catch(function(){});}}
              for(var i=0;i<sel.length;i++){var e=document.querySelector(sel[i]);if(e&&e.click){e.click();}}
            }catch(e){}
            if(++n>25){clearInterval(t);}
          },1000);
        })();
    """.trimIndent()

    private suspend fun emitHls(mediaUrl: String, label: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val isM3u8 = mediaUrl.contains(".m3u8", ignoreCase = true)
        val origin = referer.trimEnd('/')
        callback.invoke(
            newExtractorLink(
                source = name,
                name = label,
                url = mediaUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to playerUserAgent,
                    "Referer" to referer,
                    "Origin" to origin
                )
            }
        )
    }

    /** Buka player P2P di WebView, klik play otomatis, lalu tangkap request .m3u8 (playcdn). */
    private suspend fun webViewHls(url: String, referer: String): String? {
        return try {
            val captured = withTimeoutOrNull(40_000L) {
                app.get(
                    url,
                    referer = referer,
                    interceptor = WebViewResolver(
                        Regex("""(?i)\.m3u8"""),
                        useOkhttp = false,
                        script = autoPlayScript,
                        timeout = 35_000L
                    )
                ).url
            }
            captured?.takeIf { it.contains(".m3u8", ignoreCase = true) && !isJunk(it) }
        } catch (e: Exception) {
            Log.e("kraptor_$name", "WebView P2P gagal untuk $url: ${e.message}")
            null
        }
    }

    // ===================== videonode.de: embed lewat api.php =====================
    // Halaman videonode.de/iframe3/<host>/<id> HANYA cangkang kosong: <iframe src="">.
    // URL player sebenarnya diisi JavaScript lewat  POST /api.php  (host=<host>&id=<id>)  ->  {"embedUrl": "..."}.
    // Halaman itu juga punya frame-buster (window.self === window.top -> redirect ke lk21.de), jadi
    // membukanya langsung di WebView tidak akan pernah memuat player. Karena itu API dipanggil langsung.

    private val jsHostRegex = Regex("""var\s+host\s*=\s*["']([^"']+)["']""")
    private val jsIdRegex = Regex("""var\s+id\s*=\s*["']([^"']+)["']""")

    private suspend fun fetchEmbedUrl(playerUrl: String, referer: String): String? {
        val origin = getBaseUrl(playerUrl)
        var host: String? = null
        var id: String? = null

        // host & id dari path: /iframe3/<host>/<id>
        val segs = runCatching { URI(playerUrl).path.split("/").filter { it.isNotBlank() } }.getOrDefault(emptyList())
        if (segs.size >= 3) {
            host = segs[segs.size - 2]
            id = segs.last()
        }
        // atau dari JavaScript di halaman (lebih akurat bila format path berubah)
        runCatching {
            val html = app.get(
                playerUrl, referer = referer,
                headers = mapOf("User-Agent" to playerUserAgent, "Referer" to referer)
            ).text
            jsHostRegex.find(html)?.groupValues?.get(1)?.let { host = it }
            jsIdRegex.find(html)?.groupValues?.get(1)?.let { id = it }
        }.onFailure { Log.w("kraptor_$name", "embed: gagal membaca $playerUrl: ${it.message}") }

        if (host.isNullOrBlank() || id.isNullOrBlank()) {
            Log.w("kraptor_$name", "embed: host/id tidak ditemukan untuk $playerUrl")
            return null
        }

        return try {
            val res = app.post(
                "$origin/api.php",
                data = mapOf("host" to host!!, "id" to id!!),
                referer = playerUrl,
                headers = mapOf(
                    "User-Agent" to playerUserAgent,
                    "Referer" to playerUrl,
                    "Origin" to origin,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/javascript, */*; q=0.01"
                )
            )
            val body = res.text
            Log.d("kraptor_$name", "embed api.php host=$host (HTTP ${res.code}): ${body.take(300)}")
            val raw = runCatching { JSONObject(body).optString("embedUrl") }.getOrDefault("").trim()
            when {
                raw.isEmpty() -> null
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("http", true) -> raw
                raw.startsWith("/") -> origin + raw
                else -> null
            }
        } catch (e: Exception) {
            Log.e("kraptor_$name", "embed api.php gagal: ${e.message}")
            null
        }
    }

    /** Tangkap .m3u8 dari embed lewat WebView (hanya cadangan). Embed dimuat sebagai halaman utama. */
    private suspend fun webViewEmbed(embedUrl: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        val captured = webViewHls(embedUrl, referer) ?: return false
        Log.d("kraptor_$name", "WebView menangkap: $captured")
        emitHls(captured, "LK21 (WebView)", getBaseUrl(embedUrl) + "/", callback)
        return true
    }

    /**
     * Buka halaman film/episode dan ambil daftar player. Jika domain di `data` sudah mati / diganti
     * (data lama tersimpan di aplikasi), coba host series & host utama dengan path yang sama.
     */
    private suspend fun locatePlayers(data: String): Pair<String, List<String>>? {
        val headers = mapOf("User-Agent" to playerUserAgent)

        suspend fun attempt(u: String): Pair<String, List<String>>? {
            val r = try {
                app.get(u, referer = "$mainUrl/", headers = headers)
            } catch (e: Exception) {
                Log.e("kraptor_$name", "gagal membuka $u: ${e.message}")
                return null
            }
            if (cloudflareHints.any { r.text.contains(it, ignoreCase = true) }) {
                Log.w("kraptor_$name", "Halaman kemungkinan terkena tantangan Cloudflare (HTTP ${r.code}) di $u")
            }
            val p = collectPlayerUrls(r.documentLarge, r.url)
            return if (p.isNotEmpty()) r.url to p else null
        }

        attempt(data)?.let { return it }

        val path = runCatching {
            val uri = URI(data)
            uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
        }.getOrDefault("")
        if (path.isEmpty()) return null

        val series = ensureSeriesUrl()
        val tried = linkedSetOf(data)
        for (host in listOf(series, mainUrl)) {
            val candidate = host.trimEnd('/') + path
            if (tried.add(candidate)) attempt(candidate)?.let { return it }
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("kraptor_$name", "data = $data")
        val found = AtomicInteger(0)
        val counting: (ExtractorLink) -> Unit = {
            found.incrementAndGet()
            callback(it)
        }

        val located = locatePlayers(data)
        if (located == null) {
            Log.w("kraptor_$name", "Tidak ada player ditemukan untuk $data")
            return false
        }
        val (pageUrl, players) = located
        Log.d("kraptor_$name", "players(${players.size}) = $players")

        // Referer = halaman episode/film itu sendiri (host player biasanya memeriksa ini)
        val visited = HashSet<String>()
        // Urutan: P2P (prioritas utama), lalu CAST, lalu HYDRAX
        val ordered = players.sortedBy {
            when {
                it.contains("/p2p/") -> 0
                it.contains("/cast/") -> 1
                it.contains("/hydrax/") -> 2
                else -> 3
            }
        }
        val embeds = mutableListOf<Pair<String, String>>() // embedUrl, player asal
        for (player in ordered.take(8)) {
            runCatching {
                val isVideonode = player.contains("videonode", ignoreCase = true) || player.contains("/iframe3/")
                val embed = if (isVideonode) fetchEmbedUrl(player, pageUrl) else null
                if (embed != null) {
                    Log.d("kraptor_$name", "embed [$player] -> $embed")
                    embeds.add(embed to player)
                    // Referer ke embed = halaman videonode (sama seperti iframe di browser)
                    resolvePlayer(embed, player, 0, found, visited, subtitleCallback, counting)
                } else {
                    resolvePlayer(player, pageUrl, 0, found, visited, subtitleCallback, counting)
                }
            }.onFailure { Log.e("kraptor_$name", "resolve gagal untuk $player: ${it.message}") }
        }

        if (found.get() == 0) {
            Log.w("kraptor_$name", "Tidak ada link lewat HTTP, mencoba WebView pada embed (${embeds.size})")
            for ((embed, player) in embeds.take(3)) {
                if (webViewEmbed(embed, player, counting)) break
            }
        }

        Log.d("kraptor_$name", "loadLinks selesai: ${found.get()} link")
        return found.get() > 0
    }

    fun getBaseUrl(url: String?): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    /**
     * Pastikan domain series masih hidup. Jika tidak, cari domain terbaru lewat halaman
     * `/nontondrama` di domain utama (halaman pengalih yang berisi a#openNow / div.links a).
     */
    private suspend fun ensureSeriesUrl(): String {
        if (seriesChecked) return seriesUrl
        seriesChecked = true

        val alive = runCatching { app.get(seriesUrl, timeout = 15L).code in 200..399 }.getOrDefault(false)
        if (alive) return seriesUrl

        runCatching {
            val res = app.get("$mainUrl/nontondrama", timeout = 20L)
            val doc = res.documentLarge
            val link = doc.selectFirst("a#openNow")?.attr("href")
                ?: doc.selectFirst("div.links a")?.attr("href")
            val candidate = link?.takeIf { it.startsWith("http") }?.let { getBaseUrl(it) }
                ?: getBaseUrl(res.url)
            if (candidate != getBaseUrl(mainUrl)) {
                Log.d("kraptor_$name", "seriesUrl diperbarui: $seriesUrl -> $candidate")
                seriesUrl = candidate
            }
        }.onFailure { Log.e("kraptor_$name", "gagal memperbarui seriesUrl: ${it.message}") }

        return seriesUrl
    }

    /**
     * Buka URL dan ikuti halaman pengalih (redirect page "Nontondrama") bila ada.
     * Halaman asli dikenali dari adanya div.movie-info h1 atau #season-data, bukan lagi dari kata
     * "Nontondrama" di <title> (judul halaman sekarang berawalan "Lk21 Nonton ...").
     */
    private suspend fun fetchProper(url: String): NiceResponse {
        var res = app.get(url)
        repeat(2) {
            val doc = res.documentLarge
            if (doc.selectFirst("div.movie-info h1") != null || doc.selectFirst("#season-data") != null) return res
            val next = doc.selectFirst("a#openNow")?.attr("href")
                ?: doc.selectFirst("div.links a")?.attr("href")
                ?: return res
            val abs = runCatching { URI(res.url).resolve(next).toString() }.getOrDefault(next)
            res = app.get(abs)
        }
        return res
    }

}

data class SearchApi(
    val data: List<Data>
)

data class Data(
    val slug: String,
    val title: String,
    val poster: String,
    val rating: Double?,
)
