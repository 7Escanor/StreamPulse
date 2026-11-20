package com.kissasian

import com.lagradost.cloudstream3.*  
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors  
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore  
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer  
import com.lagradost.cloudstream3.MainAPI  
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.base64Decode 
import com.lagradost.cloudstream3.TvType  
import com.lagradost.cloudstream3.mainPageOf  
import com.lagradost.cloudstream3.newMovieSearchResponse  
import com.lagradost.cloudstream3.newTvSeriesLoadResponse  
import com.lagradost.cloudstream3.newMovieLoadResponse  
import com.lagradost.cloudstream3.newEpisode  
import com.lagradost.cloudstream3.utils.*  
import android.util.Base64
import com.lagradost.api.Log
import java.net.URI
import java.net.URLDecoder
import org.jsoup.nodes.Document
import java.util.concurrent.ConcurrentHashMap
import org.jsoup.nodes.Element
import org.jsoup.Jsoup


class Kissasian : MainAPI() {
    override var mainUrl = "https://kissasian.cam"
    override var name = "Kissasian"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        var context: android.content.Context? = null

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }



    // ---------------------------------------------------------------------
    // FIX GAMBAR: sertifikat SSL kissasian.cam sudah kedaluwarsa, jadi Coil
    // (pemuat gambar aplikasi) menolak semua gambar. Client `app` tidak
    // memvalidasi sertifikat, jadi gambar diunduh di sini lalu diberikan
    // ke aplikasi sebagai data URI.
    // ---------------------------------------------------------------------
    private val imageCache = ConcurrentHashMap<String, String>()

    private suspend fun resolveImage(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("data:")) return url
        imageCache[url]?.let { return it }
        return try {
            val res = app.get(url, referer = "$mainUrl/", timeout = 30L)
            val bytes = res.okhttpResponse.body.bytes()
            val mime = res.okhttpResponse.header("Content-Type")
                ?.substringBefore(";")?.trim().orEmpty()
            if (!res.okhttpResponse.isSuccessful || bytes.size < 200 || bytes.size > 2_000_000) {
                null
            } else {
                val type = if (mime.startsWith("image/")) mime else "image/jpeg"
                val data = "data:$type;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                imageCache[url] = data
                data
            }
        } catch (e: Throwable) {
            null
        }
    }

    // Hapus akhiran ukuran WordPress, contoh: foto-200x300.jpg -> foto.jpg
    private fun String.toFullSize(): String =
        replace(Regex("-\\d+x\\d+(?=\\.[A-Za-z]{3,4}(\\?.*)?$)"), "")

    // Ubah poster tiap item menjadi data URI (paralel)
    private suspend fun List<SearchResponse>.withSafePosters(): List<SearchResponse> {
        this.amap { item -> item.posterUrl = resolveImage(item.posterUrl) ?: item.posterUrl }
        return this
    }

    override val mainPage = mainPageOf(
        "series/?order=latest" to "Baru ditambahkan",
        "series/?status=&type=&order=update" to "Update Terbaru",
        "series/?status=&type=Movie&order=latest" to "Movie Terbaru",
        "series/?status=&type=&order=popular" to "Terpopuler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}".plus("&page=$page")
        val document = app.get(url).document
        val items = document.select("div.listupd article.bs")
                            .mapNotNull { it.toSearchResult() }
                            .withSafePosters()
        return newHomePageResponse(HomePageList(request.name, items), hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
    val linkElement = this.selectFirst("a") ?: return null
    val href = fixUrl(linkElement.attr("href"))
    val title = linkElement.attr("title").ifBlank {
        this.selectFirst("div.tt")?.text()
    } ?: return null
    val poster = this.selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }

    val isSeries = href.contains("/series/", true) || href.contains("drama", true)

    return if (isSeries) {
        newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    } else {
        newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }
}

    override suspend fun search(query: String): List<SearchResponse> {
    val document = app.get("$mainUrl/?s=$query", timeout = 50L).document
    val results = document.select("div.listupd article.bs")
        .mapNotNull { it.toSearchResult() }
        .withSafePosters()
    return results
}

    private fun Element.toRecommendResult(): SearchResponse? {
    val title = this.selectFirst("div.tt")?.text()?.trim() ?: return null
    val href = this.selectFirst("a")?.attr("href")?.let { fixUrl(it) } ?: return null
    val posterUrl = this.selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }
    return newMovieSearchResponse(title, href, TvType.Movie) {
        this.posterUrl = posterUrl
    }
}
    override suspend fun load(url: String): LoadResponse {
    val document = app.get(url).document

    // Judul
    val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()

    // Poster
    val posterRaw = (document.selectFirst("div.bigcontent img")?.getImageAttr()
        ?: document.selectFirst("meta[property=og:image]")?.attr("content"))
        ?.let { fixUrlNull(it) }
    val backdropRaw = document.selectFirst("meta[property=og:image]")?.attr("content")
        ?.let { fixUrlNull(it) } ?: posterRaw

    // Coba ukuran penuh dulu, jika gagal pakai thumbnail asli
    val poster = resolveImage(posterRaw?.toFullSize()) ?: resolveImage(posterRaw) ?: posterRaw
    val backdrop = resolveImage(backdropRaw?.toFullSize()) ?: resolveImage(backdropRaw) ?: backdropRaw

    // Sinopsis (gabungkan semua paragraf)
    val description = document.select("div.entry-content p")
        .joinToString("\n") { it.text() }
        .trim()

    // Tahun rilis (ambil angka dari span Dirilis)
    val year = document.selectFirst("span:matchesOwn(Dirilis:)")?.ownText()
        ?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

    // Status, durasi, negara, tipe
    
    val duration = document.selectFirst("div.spe span:contains(Durasi:)")?.ownText()?.let {
    val h = Regex("(\\d+)\\s*hr").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val m = Regex("(\\d+)\\s*min").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    h * 60 + m
    }
    val country = document.selectFirst("span:matchesOwn(Negara:)")?.ownText()?.trim()
    val type = document.selectFirst("span:matchesOwn(Tipe:)")?.ownText()?.trim()

    // Genre / tags
    val tags = document.select("div.genxed a").map { it.text() }

    // Aktor
    val actors = document.select("span:has(b:matchesOwn(Artis:)) a")
    .map { it.text().trim() }

    val rating = document.selectFirst("div.rating strong")
    ?.text()
    ?.replace("Rating", "")
    ?.trim()
    ?.toDoubleOrNull()

    val trailer = document.selectFirst("div.bixbox.trailer iframe")?.attr("src")

    val status = getStatus(
    document.selectFirst("div.info-content div.spe span")
        ?.ownText()
        ?.replace(":", "")
        ?.trim()
        ?: ""
)

    
    val recommendations = document.select("div.listupd article.bs")
        .mapNotNull { it.toRecommendResult() }
        .withSafePosters()

    
val episodeElements = document.select("div.eplister ul li a")

val episodes = episodeElements
    .reversed() // karena biasanya terbaru di atas
    .mapIndexed { index, aTag ->
        val href = fixUrl(aTag.attr("href"))

        newEpisode(href) {
            this.name = "Episode ${index + 1}"
            this.episode = index + 1
        }
    }


    return if (episodes.size > 1) {
    // TV Series
    newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
        this.posterUrl = poster
        this.backgroundPosterUrl = backdrop
        this.year = year
        this.plot = description
        this.tags = tags
        showStatus = status
        this.recommendations = recommendations
        this.duration = duration ?: 0
        if (rating != null) addScore(rating.toString(), 10)
        addActors(actors)
        addTrailer(trailer)
    }
} else {
    // Movie
    newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: url) {
        this.posterUrl = poster
        this.backgroundPosterUrl = backdrop
        this.year = year
        this.plot = description
        this.tags = tags
        this.recommendations = recommendations
        this.duration = duration ?: 0
        if (rating != null) addScore(rating.toString(), 10)
        addActors(actors)
        addTrailer(trailer)
    }
}

}
       
    // ---------------------------------------------------------------------
    // SUBTITLE: cari file subtitle (.vtt/.srt/.ass), tag <track>, parameter
    // iframe (c1_file, sub, dll) dan config player di halaman episode,
    // halaman mirror, serta halaman embed.
    // ---------------------------------------------------------------------
    private val subtitleRegex = Regex(
        """https?:(?:\\?/){2}[^"'\s<>]+?\.(?:vtt|srt|ass|ssa)(?:\?[^"'\s<>]*)?""",
        RegexOption.IGNORE_CASE
    )
    private val labelRegex = Regex(
        """(?:label|lang|language|srclang)["']?\s*[:=]\s*["']([^"']{2,30})["']""",
        RegexOption.IGNORE_CASE
    )

    private fun toAbs(url: String, base: String): String {
        val u = url.trim().replace("\\/", "/")
        return try {
            URI(base).resolve(u).toString()
        } catch (_: Throwable) {
            fixUrl(u)
        }
    }

    private fun guessLang(raw: String?): String {
        val t = raw?.trim()?.lowercase().orEmpty()
        return when {
            t.isBlank() -> "Subtitle"
            t.contains("indo") || t == "id" || t == "ind" -> "Indonesian"
            t.contains("engl") || t == "en" || t == "eng" -> "English"
            t.contains("malay") || t.contains("melayu") || t == "ms" -> "Malay"
            t.contains("thai") || t == "th" -> "Thai"
            t.contains("viet") || t == "vi" -> "Vietnamese"
            t.contains("arab") || t == "ar" -> "Arabic"
            else -> raw!!.trim()
        }
    }

    private fun scanForSubtitles(
        html: String,
        doc: Document?,
        pageUrl: String,
        found: MutableMap<String, String>
    ) {
        // 1) <track kind="subtitles" src="...">
        doc?.select("track[src]")?.forEach { t ->
            val kind = t.attr("kind")
            if (kind.isBlank() || kind.equals("subtitles", true) || kind.equals("captions", true)) {
                found.putIfAbsent(
                    toAbs(t.attr("src"), pageUrl),
                    t.attr("label").ifBlank { t.attr("srclang") }
                )
            }
        }
        // 2) URL berekstensi subtitle di HTML / script (JWPlayer, Plyr, dll)
        subtitleRegex.findAll(html).forEach { m ->
            val start = (m.range.first - 160).coerceAtLeast(0)
            val end = (m.range.last + 160).coerceAtMost(html.length - 1)
            val label = labelRegex.find(html.substring(start, end + 1))?.groupValues?.get(1)
            found.putIfAbsent(toAbs(m.value, pageUrl), label.orEmpty())
        }
    }

    private fun subtitlesFromIframeUrl(src: String, found: MutableMap<String, String>) {
        val query = src.substringAfter("?", "")
        if (query.isBlank()) return
        val params = query.split("&").associate {
            it.substringBefore("=").lowercase() to
                runCatching { URLDecoder.decode(it.substringAfter("=", ""), "UTF-8") }.getOrDefault("")
        }
        val label = params["c1_label"] ?: params["sub_label"] ?: params["label"]
        val keys = setOf("c1_file", "sub", "sub_file", "subtitle", "subtitles", "subs", "caption", "captions")
        params.forEach { (k, v) ->
            if (v.startsWith("http") && (k in keys || subtitleRegex.containsMatchIn(v))) {
                found.putIfAbsent(v, label.orEmpty())
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val found = LinkedHashMap<String, String>()

        suspend fun handlePage(pageUrl: String, res: com.lagradost.nicehttp.NiceResponse) {
            val doc = res.document
            scanForSubtitles(res.text, doc, pageUrl, found)

            val iframe = doc.selectFirst("div.player-embed iframe")
                ?.attr("src")
                ?.takeIf { it.isNotBlank() }
                ?: return
            val embedUrl = httpsify(iframe)
            subtitlesFromIframeUrl(embedUrl, found)
            Log.d("Kissasian", "page=$pageUrl iframe=$embedUrl")

            loadExtractor(embedUrl, pageUrl, subtitleCallback, callback)

            // Pindai halaman embed untuk config subtitle
            try {
                val embed = app.get(embedUrl, referer = pageUrl, timeout = 20L)
                scanForSubtitles(embed.text, embed.document, embedUrl, found)
            } catch (e: Throwable) {
                Log.d("Kissasian", "embed scan gagal: $embedUrl -> ${e.message}")
            }
        }

        val first = app.get(data)
        handlePage(data, first)

        for (opt in first.document.select("select.mirror option[value]")) {
            val mirrorPageUrl = fixUrl(opt.attr("value"))
            if (mirrorPageUrl.isBlank()) continue
            try {
                handlePage(mirrorPageUrl, app.get(mirrorPageUrl))
            } catch (_: Exception) {
            }
        }

        Log.d("Kissasian", "subtitle ditemukan: ${found.size} -> $found")
        found.forEach { (url, label) ->
            subtitleCallback(newSubtitleFile(guessLang(label), url))
        }

        return true
    }


    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src").takeIf { it?.isNotEmpty() == true }
                ?: this?.attr("src")
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
        return this.replace(regex, "")
    }
}
