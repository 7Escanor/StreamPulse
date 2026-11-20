package com.MovieBox

import android.annotation.SuppressLint
import android.net.Uri
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max

class MovieBoxProvider : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }

    // User-facing site URL. moviebox.ph sekarang diarahkan ke movieboxhd.net.
    // Requests API dikirim ke `apiUrl`.
    override var mainUrl = "https://movieboxhd.net"
    override var name = "Moviebox"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val apiUrl = "https://h5-api.aoneroom.com"
    private val apiHostParam = "moviebox.ph"

    /**
     * MovieBox uses numeric `subjectType` codes.
     * - 1: Movie
     * - 2: Series
     * - 7: Episodic "drama"/UGC series (still uses seasons/episodes + subject/download with se/ep)
     *
     * We also fall back to checking for a non-empty seasons array when available, to avoid breaking
     * if the site introduces new episodic subject types.
     */
    private fun inferTvType(subjectType: Int?, seasonsNode: JsonNode? = null): TvType {
        return when (subjectType) {
            2, 7 -> TvType.TvSeries
            1 -> TvType.Movie
            else -> {
                if (seasonsNode != null && seasonsNode.isArray && seasonsNode.size() > 0) TvType.TvSeries else TvType.Movie
            }
        }
    }

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"

    private fun appendQueryParam(url: String, key: String, value: String): String {
        val sep = if (url.contains("?")) "&" else "?"
        return "$url$sep$key=${URLEncoder.encode(value, "UTF-8")}"
    }

    private fun apiHeaders(referer: String = "$mainUrl/") = mapOf(
        "accept" to "application/json",
        "accept-language" to "en-US,en;q=0.5",
        "user-agent" to userAgent,
        "x-client-info" to """{"timezone":"Asia/Jakarta"}""",
        "referer" to referer,
    )

    private fun htmlHeaders(referer: String = "$mainUrl/") = mapOf(
        "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "accept-language" to "en-US,en;q=0.5",
        "user-agent" to userAgent,
        "referer" to referer,
    )

    private val downloadReferer = "https://videodownloader.site/"

    private fun downloadHeaders() = mapOf(
        "accept" to "*/*",
        "accept-language" to "en-US,en;q=0.5",
        "user-agent" to userAgent,
        "origin" to "https://videodownloader.site",
        // Keep Referer both here and via `referer =` parameter for maximum compatibility.
        "referer" to downloadReferer,
    )

    private val secretKeyDefault = base64Decode("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==")
    private val secretKeyAlt = base64Decode("WHFuMm5uTzQxL0w5Mm8xaXVYaFNMSFRiWHZZNFo1Wlo2Mm04bVNMQQ==")

    private fun md5(input: ByteArray): String {
        return MessageDigest.getInstance("MD5").digest(input)
            .joinToString("") { "%02x".format(it) }
    }

    private fun reverseString(input: String): String = input.reversed()

    private fun generateXClientToken(hardcodedTimestamp: Long? = null): String {
        val timestamp = (hardcodedTimestamp ?: System.currentTimeMillis()).toString()
        val reversed = reverseString(timestamp)
        val hash = md5(reversed.toByteArray())
        return "$timestamp,$hash"
    }

    @SuppressLint("UseKtx")
    private fun buildCanonicalString(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String?,
        timestamp: Long
    ): String {
        val parsed = Uri.parse(url)
        val path = parsed.path ?: ""

        // Build query string with sorted parameters (if any)
        val query = if (parsed.queryParameterNames.isNotEmpty()) {
            parsed.queryParameterNames.sorted().joinToString("&") { key ->
                parsed.getQueryParameters(key).joinToString("&") { value ->
                    "$key=$value"  // Don't URL encode here - Python doesn't do it
                }
            }
        } else ""

        val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path

        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val bodyHash = if (bodyBytes != null) {
            val trimmed = if (bodyBytes.size > 102400) bodyBytes.copyOfRange(0, 102400) else bodyBytes
            md5(trimmed)
        } else ""

        val bodyLength = bodyBytes?.size?.toString() ?: ""
        return "${method.uppercase()}\n" +
                "${accept ?: ""}\n" +
                "${contentType ?: ""}\n" +
                "$bodyLength\n" +
                "$timestamp\n" +
                "$bodyHash\n" +
                canonicalUrl
    }

    private fun generateXTrSignature(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String? = null,
        useAltKey: Boolean = false,
        hardcodedTimestamp: Long? = null
    ): String {
        val timestamp = hardcodedTimestamp ?: System.currentTimeMillis()
        val canonical = buildCanonicalString(method, accept, contentType, url, body, timestamp)
        val secret = if (useAltKey) secretKeyAlt else secretKeyDefault
        val secretBytes = base64DecodeArray(secret)

        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(secretBytes, "HmacMD5"))
        val signature = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        val signatureB64 = base64Encode(signature)

        return "$timestamp|2|$signatureB64"
    }

    override val mainPage by lazy {
        val staticFallback = mainPageOf(
            "home_featured" to "Featured",
            "trending"      to "Most Trending",
        )
        runCatching {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                val url = "$apiUrl/wefeed-h5api-bff/home?host=$apiHostParam"
                val response = app.get(url, headers = apiHeaders())
                val mapper = jacksonObjectMapper()
                val operatingList = mapper.readTree(response.text)["data"]
                    ?.get("operatingList")
                    ?: return@runBlocking null

                val sections = mutableListOf<com.lagradost.cloudstream3.MainPageData>()

                operatingList.forEach { op ->
                    when (op["type"]?.asText()?.uppercase()) {
                        "BANNER" -> {
                            val hasItems = op["banner"]?.get("items")?.size() ?: 0
                            if (hasItems > 0) sections += mainPageOf("home_featured" to "Featured")
                        }
                        "SUBJECTS_MOVIE" -> {
                            val title = op["title"]?.asText()?.takeIf { it.isNotBlank() }
                                ?: return@forEach
                            sections += mainPageOf("home_section:$title" to title)
                        }
                    }
                }
                sections += mainPageOf("trending" to "Most Trending")
                sections.takeIf { it.size > 1 }
            }
        }.getOrNull() ?: staticFallback
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val mapper = jacksonObjectMapper()

        fun toSearchResponses(items: JsonNode): List<SearchResponse> {
            return items.mapNotNull { item ->
                val subjectId =
                    item["subjectId"]?.asText()
                        ?: item["subject"]?.get("subjectId")?.asText()
                        ?: return@mapNotNull null
                val detailPath =
                    item["detailPath"]?.asText()
                        ?: item["subject"]?.get("detailPath")?.asText()
                        ?: return@mapNotNull null
                val title = item["title"]?.asText() ?: item["subject"]?.get("title")?.asText() ?: return@mapNotNull null

                val posterUrl =
                    item["cover"]?.get("url")?.asText()
                        ?: item["image"]?.get("url")?.asText()
                        ?: item["subject"]?.get("cover")?.get("url")?.asText()

                val subjectType = item["subjectType"]?.asInt() ?: item["subject"]?.get("subjectType")?.asInt() ?: 1
                val type = inferTvType(subjectType)

                val rating = item["imdbRatingValue"]?.asText() ?: item["subject"]?.get("imdbRatingValue")?.asText()

                newMovieSearchResponse(
                    name = title.substringBefore("["),
                    url = "$mainUrl/detail/$detailPath?id=$subjectId",
                    type = type
                ) {
                    this.posterUrl = posterUrl
                    this.score = Score.from10(rating)
                }
            }
        }

        suspend fun fetchHomeOperatingList(): JsonNode? {
            val url = "$apiUrl/wefeed-h5api-bff/home?host=$apiHostParam"
            val response = app.get(url, headers = apiHeaders())
            val root = mapper.readTree(response.text)
            return root["data"]?.get("operatingList")
        }

        return when {
            request.data == "home_featured" -> {
                if (page != 1) return newHomePageResponse(emptyList())
                val operatingList = fetchHomeOperatingList() ?: return newHomePageResponse(emptyList())
                val items = operatingList.firstOrNull { op ->
                    op["type"]?.asText()?.equals("BANNER", ignoreCase = true) == true
                }?.get("banner")?.get("items")
                    ?.takeIf { it.isArray && it.size() > 0 }
                    ?: return newHomePageResponse(emptyList())
                val results = toSearchResponses(items).take(15)
                newHomePageResponse(HomePageList("Featured", results))
            }

            request.data.startsWith("home_section:") -> {
                if (page != 1) return newHomePageResponse(emptyList())
                val sectionTitle = request.data.removePrefix("home_section:")
                val operatingList = fetchHomeOperatingList() ?: return newHomePageResponse(emptyList())
                val op = operatingList.firstOrNull { node ->
                    node["type"]?.asText()?.equals("SUBJECTS_MOVIE", ignoreCase = true) == true &&
                    node["title"]?.asText() == sectionTitle
                } ?: return newHomePageResponse(emptyList())
                val subjects = op["subjects"]?.takeIf { it.isArray && it.size() > 0 }
                    ?: return newHomePageResponse(emptyList())
                val results = toSearchResponses(subjects)
                newHomePageResponse(HomePageList(sectionTitle, results))
            }

            // Legacy "home" key — kept for backward compatibility
            request.data == "home" -> {
                if (page != 1) return newHomePageResponse(emptyList())
                val operatingList = fetchHomeOperatingList() ?: return newHomePageResponse(emptyList())
                val featured = operatingList.firstOrNull { op ->
                    op["type"]?.asText()?.equals("BANNER", ignoreCase = true) == true
                }?.get("banner")?.get("items")
                    ?.takeIf { it.isArray && it.size() > 0 }
                    ?.let { items ->
                        val results = toSearchResponses(items).take(15)
                        if (results.isEmpty()) null else HomePageList("Featured", results)
                    }
                val sections = operatingList.mapNotNull { op ->
                    if (op["type"]?.asText()?.equals("SUBJECTS_MOVIE", ignoreCase = true) != true) return@mapNotNull null
                    val title = op["title"]?.asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val subjects = op["subjects"] ?: return@mapNotNull null
                    if (!subjects.isArray || subjects.size() == 0) return@mapNotNull null
                    val results = toSearchResponses(subjects)
                    if (results.isEmpty()) return@mapNotNull null
                    HomePageList(title, results)
                }
                newHomePageResponse(buildList { if (featured != null) add(featured); addAll(sections) })
            }

            request.data == "trending" -> {
                val apiPage = (page - 1).coerceAtLeast(0)
                val url = "$apiUrl/wefeed-h5api-bff/subject/trending?page=$apiPage&perPage=20"
                val response = app.get(url, headers = apiHeaders())
                val root = mapper.readTree(response.text)
                val data = root["data"] ?: return newHomePageResponse(emptyList())
                val items = data["subjectList"] ?: return newHomePageResponse(emptyList())
                val pager = data["pager"]
                val hasNext = pager?.get("hasMore")?.asBoolean() == true
                val results = toSearchResponses(items)
                newHomePageResponse(HomePageList(request.name, results), hasNext = hasNext)
            }

            else -> newHomePageResponse(emptyList())
        }
    }

    private fun Element.firstTextOrTitle(vararg selectors: String): String? {
        for (selector in selectors) {
            val selected = selectFirst(selector) ?: continue
            val title = selected.attr("title").takeIf { it.isNotBlank() }
            if (title != null) return title
            val text = selected.text().trim().takeIf { it.isNotBlank() }
            if (text != null) return text
        }
        return null
    }

    private fun Element.firstAttr(vararg selectorsAndAttrs: Pair<String, String>): String? {
        for ((selector, attr) in selectorsAndAttrs) {
            val value = selectFirst(selector)?.attr(attr)?.takeIf { it.isNotBlank() }
            if (value != null) return value
        }
        return null
    }

    private fun slugToTitle(slug: String): String {
        return slug.substringBeforeLast("-")
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { slug }
    }

    private fun nuxtNode(root: JsonNode, node: JsonNode?): JsonNode? {
        val current = node ?: return null
        if (!current.isInt) return current
        val index = current.asInt()
        return if (index >= 0 && index < root.size()) root[index] else current
    }

    private fun nuxtText(root: JsonNode, node: JsonNode?): String? {
        val resolved = nuxtNode(root, node) ?: return null
        return resolved.asText(null)?.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun nuxtInt(root: JsonNode, node: JsonNode?): Int? {
        val resolved = nuxtNode(root, node) ?: return null
        return resolved.takeIf { it.isNumber || it.isTextual }?.asInt()
    }

    private fun parseNuxtSearchResultDocument(document: Document): List<SearchResponse> {
        val script = document.selectFirst("script#__NUXT_DATA__")?.data()?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        val root = runCatching { jacksonObjectMapper().readTree(script) }.getOrNull()
            ?: return emptyList()
        if (!root.isArray) return emptyList()

        val seen = mutableSetOf<String>()
        val results = mutableListOf<SearchResponse>()

        root.forEach { rawItem ->
            if (!rawItem.isObject || !rawItem.has("detailPath") || !rawItem.has("title")) return@forEach

            val detailPath = nuxtText(root, rawItem["detailPath"])?.trim('/') ?: return@forEach
            if (detailPath.isBlank() || !seen.add(detailPath)) return@forEach

            val title = nuxtText(root, rawItem["title"]) ?: slugToTitle(detailPath)
            val coverNode = nuxtNode(root, rawItem["cover"])
            val posterUrl = nuxtText(root, coverNode?.get("url"))
            val subjectType = nuxtInt(root, rawItem["subjectType"]) ?: 1
            val rating = nuxtText(root, rawItem["imdbRatingValue"])
            val subjectId = nuxtText(root, rawItem["subjectId"])
            val url = if (!subjectId.isNullOrBlank()) {
                "$mainUrl/moviedetail/$detailPath?id=$subjectId"
            } else {
                "$mainUrl/moviedetail/$detailPath"
            }

            results.add(
                newMovieSearchResponse(
                    name = title.substringBefore("[").trim().ifBlank { title.trim() },
                    url = url,
                    type = inferTvType(subjectType)
                ) {
                    this.posterUrl = posterUrl
                    this.score = Score.from10(rating)
                }
            )
        }

        return results
    }

    private fun parseSearchResultDocument(document: Document): List<SearchResponse> {
        val nuxtResults = parseNuxtSearchResultDocument(document)
        if (nuxtResults.isNotEmpty()) return nuxtResults

        val seen = mutableSetOf<String>()
        return document.select("a[href*=/moviedetail/]").mapNotNull { card ->
            val href = card.attr("href").takeIf { it.contains("/moviedetail/") } ?: return@mapNotNull null
            val detailPath = href.substringAfter("/moviedetail/")
                .substringBefore('?')
                .substringBefore('#')
                .trim('/')
                .takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            if (!seen.add(detailPath)) return@mapNotNull null

            val title = card.firstTextOrTitle("h2[title]", ".card-title[title]", "h2", ".card-title")
                ?: slugToTitle(detailPath)
            val posterUrl = card.firstAttr(
                "img[src]" to "src",
                "img[data-src]" to "data-src",
                "img[data-original]" to "data-original"
            )
            val rating = card.selectFirst(".rate")?.text()?.trim()?.takeIf { it.isNotBlank() }

            newMovieSearchResponse(
                name = title.substringBefore("[").trim().ifBlank { title.trim() },
                url = "$mainUrl/moviedetail/$detailPath",
                type = TvType.Movie
            ) {
                this.posterUrl = posterUrl
                this.score = Score.from10(rating)
            }
        }
    }

    private fun JsonNode.subjectNode(): JsonNode = this["subject"]?.takeIf { it.isObject } ?: this

    private fun parseApiSearchItems(items: JsonNode): List<SearchResponse> {
        return items.mapNotNull { rawItem ->
            val item = rawItem.subjectNode()
            val subjectId = item["subjectId"]?.asText()
                ?: rawItem["subjectId"]?.asText()
                ?: return@mapNotNull null
            val detailPath = item["detailPath"]?.asText()
                ?: rawItem["detailPath"]?.asText()
                ?: return@mapNotNull null
            val title = item["title"]?.asText()
                ?: rawItem["title"]?.asText()
                ?: return@mapNotNull null
            val posterUrl = item["cover"]?.get("url")?.asText()
                ?: rawItem["cover"]?.get("url")?.asText()
                ?: rawItem["image"]?.get("url")?.asText()
            val subjectType = item["subjectType"]?.asInt()
                ?: rawItem["subjectType"]?.asInt()
                ?: 1
            val rating = item["imdbRatingValue"]?.asText()
                ?: rawItem["imdbRatingValue"]?.asText()

            newMovieSearchResponse(
                name = title.substringBefore("[").trim().ifBlank { title },
                url = "$mainUrl/detail/$detailPath?id=$subjectId",
                type = inferTvType(subjectType)
            ) {
                this.posterUrl = posterUrl
                this.score = Score.from10(rating)
            }
        }
    }

    // Token guest BFF. Endpoint /subject/search menolak request tanpa "Authorization: Bearer <token>".
    // Server memberikan token lewat header respons `x-user` (JSON {"token":"..."}) atau cookie `token`
    // pada request GET /home. Token disimpan dan diperbarui otomatis jika kedaluwarsa.
    @Volatile
    private var guestToken: String? = null

    private fun extractToken(headers: okhttp3.Headers): String? {
        headers["x-user"]?.let { raw ->
            val t = runCatching { jacksonObjectMapper().readTree(raw)["token"]?.asText() }.getOrNull()
            if (!t.isNullOrBlank() && t != "null") return t
        }
        for (cookie in headers.values("set-cookie")) {
            val m = Regex("(?:^|[\\s;,])token=([^;]+)").find(cookie)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    private suspend fun fetchGuestToken(force: Boolean = false): String? {
        if (!force) guestToken?.let { return it }
        return try {
            val resp = app.get("$apiUrl/wefeed-h5api-bff/home?host=$apiHostParam", headers = apiHeaders())
            val token = extractToken(resp.headers)
            if (token == null) {
                android.util.Log.w(
                    "MovieBoxSearch",
                    "Guest token not found. HTTP ${resp.code}, headers=${resp.headers.names()}"
                )
            } else {
                guestToken = token
            }
            token
        } catch (e: Exception) {
            android.util.Log.e("MovieBoxSearch", "Fetch guest token failed: ${e.message}", e)
            null
        }
    }

    private suspend fun searchViaApi(query: String): List<SearchResponse> {
        val url = "$apiUrl/wefeed-h5api-bff/subject/search?host=$apiHostParam"
        val escaped = query.replace("\\", "\\\\").replace("\"", "\\\"")
        var token = fetchGuestToken()

        // Body situs aslinya memakai perPage = 0. Jika kosong, coba perPage lain.
        for (perPage in listOf(0, 20)) {
            var authRetried = false
            while (true) {
                try {
                    val jsonBody = """{"keyword":"$escaped","page":1,"perPage":$perPage,"subjectType":0}"""
                    val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
                    val authHeader = token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()
                    val response = app.post(
                        url,
                        headers = apiHeaders() +
                                mapOf("origin" to mainUrl, "content-type" to "application/json") +
                                authHeader,
                        requestBody = requestBody
                    )

                    // Server bisa memutar token baru lewat header x-user
                    extractToken(response.headers)?.let { guestToken = it; token = it }

                    val text = response.text
                    val authFailed = response.code == 401 ||
                            (response.code == 400 && text.contains("invalid token", ignoreCase = true))
                    if (authFailed && !authRetried) {
                        authRetried = true
                        guestToken = null
                        token = fetchGuestToken(force = true)
                        continue
                    }

                    val root = jacksonObjectMapper().readTree(text)
                    val data = root["data"]
                    val items = data?.get("items")
                        ?: data?.get("subjectList")
                        ?: data?.get("subjects")
                        ?: data?.get("list")
                        ?: data?.takeIf { it.isArray }

                    if (items == null || !items.isArray) {
                        android.util.Log.w(
                            "MovieBoxSearch",
                            "API search (perPage=$perPage): no item list. HTTP ${response.code}, token=${token != null}, body=${text.take(300)}"
                        )
                        break
                    }

                    val parsed = parseApiSearchItems(items)
                    if (parsed.isNotEmpty()) return parsed

                    android.util.Log.w(
                        "MovieBoxSearch",
                        "API search (perPage=$perPage): ${items.size()} items, none parsed. Sample=${items.firstOrNull()?.toString()?.take(300)}"
                    )
                    break
                } catch (e: Exception) {
                    android.util.Log.e("MovieBoxSearch", "API search (perPage=$perPage) failed: ${e.message}", e)
                    break
                }
            }
        }
        return emptyList()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        // 1) API JSON (lebih stabil daripada scraping HTML)
        val apiResults = searchViaApi(cleanQuery)
        if (apiResults.isNotEmpty()) return apiResults

        // 2) Fallback: scraping halaman pencarian
        val searchUrl = "$mainUrl/web/searchResult?keyword=${URLEncoder.encode(cleanQuery, "UTF-8")}"
        return try {
            val res = app.get(searchUrl, headers = htmlHeaders())
            val results = parseSearchResultDocument(res.document)
            if (results.isEmpty()) {
                android.util.Log.w("MovieBoxSearch", "HTML search empty. HTTP ${res.code}, url=$searchUrl")
            }
            results
        } catch (e: Exception) {
            android.util.Log.e("MovieBoxSearch", "HTML search failed: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val parsed = Uri.parse(url)

        val detailPath = parsed.getQueryParameter("detailPath")
            ?: run {
                val segs = parsed.pathSegments
                val detailIndex = segs.indexOf("detail")
                when {
                    detailIndex >= 0 && segs.size > detailIndex + 1 -> segs[detailIndex + 1]
                    segs.isNotEmpty() -> segs.last()
                    else -> null
                }
            }
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Missing detailPath")

        val detailUrl = "$apiUrl/wefeed-h5api-bff/detail?detailPath=${URLEncoder.encode(detailPath, "UTF-8")}"
        val response = app.get(detailUrl, headers = apiHeaders())

        val mapper = jacksonObjectMapper()
        val root = mapper.readTree(response.text)
        val data = root["data"] ?: throw ErrorLoadingException("No data")
        val subject = data["subject"] ?: throw ErrorLoadingException("No subject")

        val subjectId = parsed.getQueryParameter("id")
            ?: subject["subjectId"]?.asText()
            ?: throw ErrorLoadingException("No subjectId")

        val safeDetailPath = subject["detailPath"]?.asText() ?: detailPath
        val pageUrl = "$mainUrl/detail/$safeDetailPath?id=$subjectId"

        val title = subject["title"]?.asText()?.substringBefore("[") ?: throw ErrorLoadingException("No title")
        val description = subject["description"]?.asText()?.takeIf { !it.isNullOrBlank() }
            ?: data["metadata"]?.get("description")?.asText()
        val releaseDate = subject["releaseDate"]?.asText()
        val year = releaseDate?.take(4)?.toIntOrNull()
        val durationMinutes = subject["duration"]?.asInt()?.let { it / 60 }
        val tags = subject["genre"]?.asText()?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

        // Poster bawaan MovieBox (dipakai sebagai fallback jika TMDB tidak tersedia / tidak exact)
        val movieBoxPoster =
            subject["cover"]?.get("url")?.asText()
                ?: data["metadata"]?.get("image")?.asText()

        val actors = data["stars"]
            ?.takeIf { it.isArray }
            ?.mapNotNull { star ->
                val name = star["name"]?.asText() ?: return@mapNotNull null
                val avatarUrl = star["avatarUrl"]?.asText()
                val character = star["character"]?.asText()
                ActorData(Actor(name, avatarUrl), roleString = character)
            }
            ?.distinctBy { it.actor.name }
            ?: emptyList()

        val seasonsNode = data["resource"]?.get("seasons")
        val subjectType = subject["subjectType"]?.asInt() ?: 1
        val type = inferTvType(subjectType, seasonsNode)
        val score = Score.from10(subject["imdbRatingValue"]?.asText())

        // Prioritas pertama: poster, backdrop & logo judul dari TMDB (hanya jika judul + tahun exact).
        // Jika tidak tersedia / tidak exact -> pakai data MovieBox seperti sebelumnya.
        val tmdb = fetchTmdbArtwork(title, year, type, lang)
        val posterUrl = tmdb?.posterUrl ?: movieBoxPoster
        val backgroundUrl = tmdb?.backdropUrl ?: movieBoxPoster
        val logoUrl = tmdb?.logoUrl

        // Data per episode dari TMDB (judul, sinopsis, rating, tanggal rilis, banner).
        // Hanya jika serial sudah cocok exact di TMDB. Field yang tidak tersedia di TMDB
        // tetap memakai data MovieBox seperti sebelumnya.
        val episodeInfo: Map<Pair<Int, Int>, TmdbEpisode> =
            if (type == TvType.TvSeries && tmdb != null) {
                val seasonNumbers =
                    if (seasonsNode != null && seasonsNode.isArray)
                        seasonsNode.map { it["se"]?.asInt() ?: 1 }.distinct()
                    else listOf(1)
                fetchTmdbEpisodes(tmdb.tmdbId, seasonNumbers, lang)
            } else emptyMap()

        if (type == TvType.TvSeries) {
            val episodes = mutableListOf<Episode>()

            if (seasonsNode != null && seasonsNode.isArray) {
                seasonsNode.forEach { seasonNode ->
                    val se = seasonNode["se"]?.asInt() ?: 1
                    val maxEp = seasonNode["maxEp"]?.asInt() ?: 1
                    for (ep in 1..maxEp) {
                        var episodeUrl = pageUrl
                        episodeUrl = appendQueryParam(episodeUrl, "se", se.toString())
                        episodeUrl = appendQueryParam(episodeUrl, "ep", ep.toString())
                        val tm = episodeInfo[Pair(se, ep)]
                        episodes.add(
                            newEpisode(episodeUrl) {
                                this.data = "$subjectId|$safeDetailPath|$se|$ep"
                                this.name = tm?.name ?: "S${se}E$ep"
                                this.season = se
                                this.episode = ep
                                this.posterUrl = tm?.stillUrl ?: posterUrl
                                this.description = tm?.overview
                                tm?.rating?.let { this.score = Score.from10(it.toString()) }
                                tm?.airDate?.let { addDate(it) }
                            }
                        )
                    }
                }
            }

            if (episodes.isEmpty()) {
                var episodeUrl = pageUrl
                episodeUrl = appendQueryParam(episodeUrl, "se", "1")
                episodeUrl = appendQueryParam(episodeUrl, "ep", "1")
                val tm = episodeInfo[Pair(1, 1)]
                episodes.add(
                    newEpisode(episodeUrl) {
                        this.data = "$subjectId|$safeDetailPath|1|1"
                        this.name = tm?.name ?: "Episode 1"
                        this.season = 1
                        this.episode = 1
                        this.posterUrl = tm?.stillUrl ?: posterUrl
                        this.description = tm?.overview
                        tm?.rating?.let { this.score = Score.from10(it.toString()) }
                        tm?.airDate?.let { addDate(it) }
                    }
                )
            }

            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backgroundUrl
                this.logoUrl = logoUrl
                this.plot = description
                this.year = year
                this.tags = tags
                this.actors = actors
                this.score = score
                this.duration = durationMinutes
            }
        }

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, "$subjectId|$safeDetailPath|0|0") {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = backgroundUrl
            this.logoUrl = logoUrl
            this.plot = description
            this.year = year
            this.tags = tags
            this.actors = actors
            this.score = score
            this.duration = durationMinutes
        }
    }

    // ===================== Link / resolusi =====================

    private data class RawLink(
        val url: String,
        val resolution: Int?,
        val referer: String,
        val origin: String,
        val cookie: String?,
        val tag: String,
    )

    private val nonVideoExt = listOf(
        ".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".srt", ".vtt", ".ass", ".ssa"
    )
    private val skipKeys = setOf(
        "captions", "subtitles", "cover", "image", "avatar", "avatarUrl", "stills", "poster", "thumbnail"
    )

    private fun parseResolution(node: JsonNode?): Int? {
        val text = node?.takeIf { !it.isNull && !it.isContainerNode }?.asText() ?: return null
        // "1080", "1080p", "720P", "480,720,1080" -> ambil angka terbesar
        return Regex("(\\d{3,4})").findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
    }

    /** Telusuri JSON secara rekursif dan kumpulkan semua objek yang punya `url` video. */
    private fun collectLinks(
        node: JsonNode?,
        out: MutableList<RawLink>,
        referer: String,
        origin: String,
        tag: String,
    ) {
        if (node == null || node.isNull) return
        if (node.isArray) {
            node.forEach { collectLinks(it, out, referer, origin, tag) }
            return
        }
        if (!node.isObject) return

        val url = node["url"]?.asText()?.trim()
            ?.takeIf { it.startsWith("http", true) || it.startsWith("magnet:", true) }
        if (url != null) {
            val clean = url.substringBefore('?').lowercase()
            if (nonVideoExt.none { clean.endsWith(it) }) {
                val res = parseResolution(node["resolution"])
                    ?: parseResolution(node["resolutions"])
                    ?: parseResolution(node["quality"])
                    ?: parseResolution(node["definition"])
                    ?: parseResolution(node["height"])
                val cookie = node["signCookie"]?.asText()?.takeIf { it.isNotBlank() && it != "null" }
                out += RawLink(url, res, referer, origin, cookie, tag)
            }
        }
        node.fields().forEach { (key, value) ->
            if (key !in skipKeys && value.isContainerNode) {
                collectLinks(value, out, referer, origin, tag)
            }
        }
    }

    private fun detectLinkType(url: String): ExtractorLinkType? {
        val clean = url.substringBefore('?').lowercase()
        return when {
            url.startsWith("magnet:", ignoreCase = true) -> ExtractorLinkType.MAGNET
            url.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
            clean.endsWith(".torrent") -> ExtractorLinkType.TORRENT
            url.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
            clean.endsWith(".mp4") || clean.endsWith(".mkv") -> ExtractorLinkType.VIDEO
            else -> INFER_TYPE
        }
    }

    /**
     * GET ke API BFF dengan token guest (Bearer). Tanpa token, server hanya memberi
     * versi "tamu" yang terbatas (biasanya cuma 480p). Token diperbarui otomatis bila kedaluwarsa.
     * Mengembalikan node `data` bila code == 0, selain itu null.
     */
    private suspend fun fetchApiData(
        label: String,
        url: String,
        headers: Map<String, String>,
        referer: String,
    ): JsonNode? {
        var token = fetchGuestToken()
        for (attempt in 0..1) {
            val auth = token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()
            val resp = runCatching { app.get(url, headers = headers + auth, referer = referer) }
                .onFailure { android.util.Log.w("MovieBoxLinks", "$label request failed: ${it.message}") }
                .getOrNull() ?: return null

            extractToken(resp.headers)?.let { guestToken = it; token = it }
            val text = resp.text

            val authFailed = resp.code == 401 ||
                    (resp.code == 400 && text.contains("invalid token", ignoreCase = true))
            if (authFailed && attempt == 0) {
                guestToken = null
                token = fetchGuestToken(force = true)
                continue
            }

            val root = runCatching { jacksonObjectMapper().readTree(text) }.getOrNull() ?: return null
            val code = root["code"]?.asInt(-1) ?: 0
            if (code != 0) {
                android.util.Log.w("MovieBoxLinks", "$label code=$code HTTP ${resp.code} body=${text.take(200)}")
                return null
            }
            return root["data"]
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val subjectId: String
            val detailPath: String
            val season: Int
            val episode: Int

            val parts = data.split("|")
            if (parts.size >= 4 && parts[0].isNotBlank()) {
                subjectId = parts[0]
                detailPath = parts[1]
                season = parts[2].toIntOrNull() ?: 0
                episode = parts[3].toIntOrNull() ?: 0
            } else {
                val parsed = Uri.parse(data)
                subjectId = parsed.getQueryParameter("id")
                    ?: parsed.getQueryParameter("subjectId")
                    ?: return false

                val segs = parsed.pathSegments
                val detailIndex = segs.indexOf("detail")
                detailPath = when {
                    detailIndex >= 0 && segs.size > detailIndex + 1 -> segs[detailIndex + 1]
                    segs.isNotEmpty() -> segs.last()
                    else -> null
                } ?: return false

                season = parsed.getQueryParameter("se")?.toIntOrNull() ?: 0
                episode = parsed.getQueryParameter("ep")?.toIntOrNull() ?: 0
            }

            if (season < 0 || episode < 0) return false

            val idEnc = URLEncoder.encode(subjectId, "UTF-8")
            val pathEnc = URLEncoder.encode(detailPath, "UTF-8")
            val query = "subjectId=$idEnc&se=$season&ep=$episode&detailPath=$pathEnc"

            val downloadUrl = "$apiUrl/wefeed-h5api-bff/subject/download?$query"
            val playUrl = "$apiUrl/wefeed-h5api-bff/subject/play?$query"
            val playReferer =
                "$mainUrl/spa/videoPlayPage/movies/$pathEnc?id=$idEnc&type=/movie/detail&lang=en"
            val siteHeaders = apiHeaders(playReferer) + mapOf("origin" to mainUrl)

            // Ambil semua sumber secara paralel:
            //  1) download  (header videodownloader.site, seperti sebelumnya, tapi sekarang + token)
            //  2) download  (header situs resmi + token) -> kadang daftar resolusinya berbeda
            //  3) play      (daftar stream HLS/MP4 yang dipakai player situs, resolusi lebih tinggi)
            val (dlA, dlB, play) = coroutineScope {
                val a = async { fetchApiData("download-A", downloadUrl, downloadHeaders(), downloadReferer) }
                val b = async { fetchApiData("download-B", downloadUrl, siteHeaders, playReferer) }
                val c = async { fetchApiData("play", playUrl, siteHeaders, playReferer) }
                Triple(a.await(), b.await(), c.await())
            }

            val raw = mutableListOf<RawLink>()
            collectLinks(dlA, raw, downloadReferer, "https://videodownloader.site", "dl")
            collectLinks(dlB, raw, playReferer, mainUrl, "dl")
            collectLinks(play, raw, playReferer, mainUrl, "stream")

            android.util.Log.d(
                "MovieBoxLinks",
                "se=$season ep=$episode -> ${raw.size} raw links, resolutions=" +
                        raw.map { "${it.tag}:${it.resolution}" }
            )

            // Subtitle dari semua sumber (tanpa duplikat)
            val seenCaptions = mutableSetOf<String>()
            for (src in listOf(dlA, dlB, play)) {
                val captions = src?.get("captions")
                if (captions != null && captions.isArray) {
                    for (caption in captions) {
                        val captionUrl = caption["url"]?.asText()?.takeIf { it.isNotBlank() } ?: continue
                        if (!seenCaptions.add(captionUrl)) continue
                        val capLang = caption["lanName"]?.asText()
                            ?: caption["lan"]?.asText()
                            ?: "Unknown"
                        subtitleCallback.invoke(newSubtitleFile(url = captionUrl, lang = capLang))
                    }
                }
            }

            // Hilangkan duplikat (URL sama tanpa query + resolusi sama)
            val unique = raw
                .distinctBy { it.url.substringBefore('?') + "#" + (it.resolution ?: 0) }
                .sortedByDescending { it.resolution ?: 0 }

            var hasAnyLinks = false
            val emittedKeys = mutableSetOf<String>()

            for (link in unique) {
                val type = detectLinkType(link.url)
                val quality = link.resolution ?: Qualities.Unknown.value
                val hdrs = buildMap {
                    put("Referer", link.referer)
                    put("Origin", link.origin)
                    put("User-Agent", userAgent)
                    link.cookie?.let { put("Cookie", it) }
                }

                // Master playlist HLS: pecah menjadi semua varian resolusi (360/480/720/1080 ...)
                if (type == ExtractorLinkType.M3U8) {
                    val variants = runCatching {
                        M3u8Helper.generateM3u8(
                            source = name,
                            streamUrl = link.url,
                            referer = link.referer,
                            headers = hdrs,
                        )
                    }.getOrNull().orEmpty()

                    if (variants.isNotEmpty()) {
                        for (v in variants) {
                            if (emittedKeys.add("${v.quality}|${v.url.substringBefore('?')}")) {
                                callback.invoke(v)
                                hasAnyLinks = true
                            }
                        }
                        continue
                    }
                }

                if (!emittedKeys.add("$quality|${link.url.substringBefore('?')}")) continue

                val label = if (link.resolution != null) "${link.resolution}p" else ""
                val suffix = if (link.tag == "stream") " (Stream)" else ""
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name $label$suffix".trim(),
                        url = link.url,
                        type = type,
                    ) {
                        this.quality = quality
                        this.referer = link.referer
                        this.headers = hdrs
                    }
                )
                hasAnyLinks = true
            }

            hasAnyLinks
        } catch (e: Exception) {
            android.util.Log.e("MovieBoxLinks", "loadLinks failed: ${e.message}", e)
            false
        }
    }
}

fun getHighestQuality(input: String): Int? {
    val qualities = listOf(
        "2160" to Qualities.P2160.value,
        "1440" to Qualities.P1440.value,
        "1080" to Qualities.P1080.value,
        "720"  to Qualities.P720.value,
        "480"  to Qualities.P480.value,
        "360"  to Qualities.P360.value,
        "240"  to Qualities.P240.value
    )

    for ((label, mappedValue) in qualities) {
        if (input.contains(label, ignoreCase = true)) {
            return mappedValue
        }
    }
    return null
}

// ===================== TMDB artwork (poster / backdrop / logo judul) =====================

private const val TMDB_API = "https://api.themoviedb.org/3"
private const val TMDB_IMG = "https://image.tmdb.org/t/p"
private const val TMDB_TOKEN =
    "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiIwZTc3ZDg5ODliOWNjMmMzYmY1N2JiNzliZGY1MTVhZiIsIm5iZiI6MTc1OTIyNTQ0OC4xNzkwMDAxLCJzdWIiOiI2OGRiYTY2ODcwMmFiNDc0OTBjNzZkMzMiLCJzY29wZXMiOlsiYXBpX3JlYWQiXSwidmVyc2lvbiI6MX0.FosBTfa77PxTS4Y2GZpp4r0Br298AUNH0QmIN5p_Mrc"

private data class TmdbArtwork(
    val tmdbId: Int,
    val imdbId: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val logoUrl: String?,
)

private fun tmdbHeaders() = mapOf(
    "Authorization" to "Bearer $TMDB_TOKEN",
    "accept" to "application/json",
)

private fun JSONObject.strOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

private val tmdbSeasonSuffix = "\\b(season\\s*\\d+|s\\d{1,2})\\b".toRegex(RegexOption.IGNORE_CASE)

/**
 * Cari poster, backdrop & logo judul di TMDB.
 * Hanya mengembalikan hasil jika judul PERSIS sama (setelah dinormalisasi) dan,
 * bila tahun diketahui, tahun rilisnya juga sama. Selain itu return null
 * sehingga pemanggil memakai data MovieBox sebagai fallback.
 */
private suspend fun fetchTmdbArtwork(
    rawTitle: String,
    year: Int?,
    type: TvType,
    langCode: String?,
): TmdbArtwork? {
    return runCatching {
        kotlinx.coroutines.withTimeoutOrNull(10_000L) {
            val isTv = type == TvType.TvSeries
            val kind = if (isTv) "tv" else "movie"

            var queryTitle = rawTitle.substringBefore("[").trim()
            if (isTv) {
                queryTitle = queryTitle.replace(tmdbSeasonSuffix, " ").replace("\\s+".toRegex(), " ").trim()
            }
            val normQuery = normalize(queryTitle)
            if (normQuery.isBlank()) return@withTimeoutOrNull null

            // 1) Search
            val searchUrl = "$TMDB_API/search/$kind" +
                    "?query=${URLEncoder.encode(queryTitle, "UTF-8")}" +
                    "&include_adult=false&language=en-US&page=1"
            val results = JSONObject(app.get(searchUrl, headers = tmdbHeaders()).text)
                .optJSONArray("results") ?: return@withTimeoutOrNull null

            val titleKeys = if (isTv) listOf("name", "original_name") else listOf("title", "original_title")
            val dateKey = if (isTv) "first_air_date" else "release_date"

            var matchedId: Int? = null
            for (i in 0 until results.length()) {
                val o = results.optJSONObject(i) ?: continue
                val id = o.optInt("id", -1)
                if (id == -1) continue

                val titleExact = titleKeys.any { key ->
                    o.strOrNull(key)?.let { normalize(it) == normQuery } == true
                }
                if (!titleExact) continue

                val candYear = o.strOrNull(dateKey)?.take(4)?.toIntOrNull()
                if (year != null && candYear != year) continue

                matchedId = id
                break
            }
            val tmdbId = matchedId ?: return@withTimeoutOrNull null

            // 2) Detail + images + external ids dalam satu request
            val langs = listOfNotNull(
                langCode?.substringBefore("-")?.lowercase()?.takeIf { it.isNotBlank() },
                "en"
            ).distinct()
            val detailUrl = "$TMDB_API/$kind/$tmdbId" +
                    "?language=en-US&append_to_response=images,external_ids" +
                    "&include_image_language=${langs.joinToString(",")},null"
            val detail = JSONObject(app.get(detailUrl, headers = tmdbHeaders()).text)

            val poster = detail.strOrNull("poster_path")?.let { "$TMDB_IMG/w500$it" }
            val backdrop = detail.strOrNull("backdrop_path")?.let { "$TMDB_IMG/w1280$it" }
            val logo = pickTmdbLogo(detail.optJSONObject("images")?.optJSONArray("logos"), langs)
                ?.let { "$TMDB_IMG/w500$it" }
            val imdbId = detail.optJSONObject("external_ids")?.strOrNull("imdb_id")

            TmdbArtwork(tmdbId, imdbId, poster, backdrop, logo)
        }
    }.getOrNull()
}

private data class TmdbEpisode(
    val name: String?,
    val overview: String?,
    val rating: Double?,
    val airDate: String?,
    val stillUrl: String?,
)

// Nama generik seperti "Episode 3" dianggap tidak tersedia, supaya jatuh ke bahasa lain / data MovieBox.
private val tmdbGenericEpisodeName = Regex("^(episode|eps?\\.?)\\s*\\d+$", RegexOption.IGNORE_CASE)

/**
 * Ambil data tiap episode dari TMDB (judul, sinopsis, rating, tanggal rilis, banner), per season
 * secara paralel. Bahasa utama mengikuti bahasa plugin (id), jika kosong dipakai bahasa Inggris.
 * Key = Pair(season, episode). Episode / field yang tidak ada di TMDB tidak diisi,
 * sehingga pemanggil jatuh ke data MovieBox seperti sebelumnya.
 */
private suspend fun fetchTmdbEpisodes(
    tmdbId: Int,
    seasons: List<Int>,
    langCode: String?,
): Map<Pair<Int, Int>, TmdbEpisode> {
    val seasonList = seasons.filter { it > 0 }.distinct()
    if (seasonList.isEmpty()) return emptyMap()

    val preferred = langCode?.trim()?.lowercase()?.takeIf { it.isNotBlank() && !it.startsWith("en") }

    suspend fun fetchSeason(se: Int, language: String): Map<Int, JSONObject> = runCatching {
        val url = "$TMDB_API/tv/$tmdbId/season/$se?language=$language"
        val eps = JSONObject(app.get(url, headers = tmdbHeaders()).text).optJSONArray("episodes")
        val out = mutableMapOf<Int, JSONObject>()
        if (eps != null) {
            for (i in 0 until eps.length()) {
                val o = eps.optJSONObject(i) ?: continue
                val n = o.optInt("episode_number", -1)
                if (n >= 1) out[n] = o
            }
        }
        out.toMap()
    }.getOrDefault(emptyMap())

    return runCatching {
        kotlinx.coroutines.withTimeoutOrNull(12_000L) {
            coroutineScope {
                val jobs = seasonList.map { se ->
                    val en = async { fetchSeason(se, "en-US") }
                    val loc = if (preferred != null) async { fetchSeason(se, preferred) } else null
                    Triple(se, en, loc)
                }

                val result = mutableMapOf<Pair<Int, Int>, TmdbEpisode>()
                for ((se, enJob, locJob) in jobs) {
                    val en = enJob.await()
                    val loc = locJob?.await() ?: emptyMap()

                    for (n in (en.keys + loc.keys)) {
                        val e = en[n]
                        val l = loc[n]
                        val base = e ?: l ?: continue

                        val name = listOfNotNull(l?.strOrNull("name"), e?.strOrNull("name"))
                            .map { it.trim() }
                            .firstOrNull { !tmdbGenericEpisodeName.matches(it) }
                        val overview = l?.strOrNull("overview") ?: e?.strOrNull("overview")

                        val votes = base.optInt("vote_count", 0)
                        val avg = base.optDouble("vote_average", 0.0)
                        val rating = if (votes > 0 && avg > 0.0) avg else null

                        val airDate = base.strOrNull("air_date")
                        val still = base.strOrNull("still_path")?.let { "$TMDB_IMG/w300$it" }

                        if (name != null || overview != null || rating != null || airDate != null || still != null) {
                            result[Pair(se, n)] = TmdbEpisode(name, overview, rating, airDate, still)
                        }
                    }
                }
                result.toMap()
            }
        }
    }.getOrNull() ?: emptyMap()
}

/** Urutan prioritas logo: bahasa app (id) -> en -> tanpa bahasa. File SVG dilewati. */
private fun pickTmdbLogo(logos: org.json.JSONArray?, langs: List<String>): String? {
    if (logos == null || logos.length() == 0) return null

    fun pathAt(i: Int): String? =
        logos.optJSONObject(i)?.strOrNull("file_path")?.takeIf { !it.endsWith(".svg", ignoreCase = true) }
    fun langAt(i: Int): String? = logos.optJSONObject(i)?.strOrNull("iso_639_1")

    val indices = 0 until logos.length()
    for (l in langs) {
        indices.firstNotNullOfOrNull { i -> if (langAt(i) == l) pathAt(i) else null }?.let { return it }
    }
    indices.firstNotNullOfOrNull { i -> if (langAt(i) == null) pathAt(i) else null }?.let { return it }
    return indices.firstNotNullOfOrNull { pathAt(it) }
}

private fun tokenEquals(a: String, b: String): Boolean {
    val sa = a.split("\\s+".toRegex()).filter { it.isNotBlank() }.toSet()
    val sb = b.split("\\s+".toRegex()).filter { it.isNotBlank() }.toSet()
    if (sa.isEmpty() || sb.isEmpty()) return false
    val inter = sa.intersect(sb).size
    return inter >= max(1, minOf(sa.size, sb.size) * 3 / 4)
}

private fun normalize(s: String): String {
    val t = s.replace("\\[.*?]".toRegex(), " ")
        .replace("\\(.*?\\)".toRegex(), " ")
        .replace("(?i)\\b(dub|dubbed|hd|4k|hindi|tamil|telugu|dual audio)\\b".toRegex(), " ")
        .trim()
        .lowercase()
        .replace(":", " ")
        .replace("\\p{Punct}".toRegex(), " ")
        .replace("\\s+".toRegex(), " ")
    return t
}

private suspend fun fetchMetaData(imdbId: String?, type: TvType): JsonNode? {
    if (imdbId.isNullOrBlank()) return null

    val metaType = if (type == TvType.TvSeries) "series" else "movie"
    val url = "https://aiometadata.elfhosted.com/stremio/b7cb164b-074b-41d5-b458-b3a834e197bb/meta/$metaType/$imdbId.json"

    return try {
        val resp = app.get(url).text
        mapper.readTree(resp)["meta"]
    } catch (_: Exception) {
        null
    }
}

suspend fun fetchTmdbLogoUrl(
    tmdbAPI: String,
    apiKey: String,
    type: TvType,
    tmdbId: Int?,
    appLangCode: String?
): String? {
    if (tmdbId == null) return null

    val appLang = appLangCode?.substringBefore("-")?.lowercase()
    val url = if (type == TvType.Movie) {
        "$tmdbAPI/movie/$tmdbId/images?api_key=$apiKey"
    } else {
        "$tmdbAPI/tv/$tmdbId/images?api_key=$apiKey"
    }

    val json = runCatching { JSONObject(app.get(url).text) }.getOrNull() ?: return null
    val logos = json.optJSONArray("logos") ?: return null
    if (logos.length() == 0) return null

    fun logoUrlAt(i: Int): String = "https://image.tmdb.org/t/p/w500${logos.getJSONObject(i).optString("file_path")}"
    fun isSvg(i: Int): Boolean = logos.getJSONObject(i).optString("file_path").endsWith(".svg", ignoreCase = true)

    if (!appLang.isNullOrBlank()) {
        var svgFallback: String? = null
        for (i in 0 until logos.length()) {
            val logo = logos.optJSONObject(i) ?: continue
            if (logo.optString("iso_639_1") == appLang) {
                if (isSvg(i)) {
                    if (svgFallback == null) svgFallback = logoUrlAt(i)
                } else {
                    return logoUrlAt(i)
                }
            }
        }
        if (svgFallback != null) return svgFallback
    }

    var enSvgFallback: String? = null
    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        if (logo.optString("iso_639_1") == "en") {
            if (isSvg(i)) {
                if (enSvgFallback == null) enSvgFallback = logoUrlAt(i)
            } else {
                return logoUrlAt(i)
            }
        }
    }
    if (enSvgFallback != null) return enSvgFallback

    for (i in 0 until logos.length()) {
        if (!isSvg(i)) return logoUrlAt(i)
    }

    return logoUrlAt(0)
}

