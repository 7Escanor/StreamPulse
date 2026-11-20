package com.sad25kag.drakorkita

import com.lagradost.api.Log
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.Base64
import kotlinx.coroutines.runBlocking

object DrakorKitaResolver {
    private class Svc(
        val key: String,                 // hydrax | sb | p2p
        val endpoint: String,            // video_hydrax.php, ...
        val urlField: String,            // hydrax_url, ...
        val template: (String) -> String // URL embed dari ID (cadangan)
    )

    private const val TAG = "DrakorKita"

    // loadVideoHYDRAX('epId','web','720','f1','hs','ind','1') -> svc, epId, qua, res, server, cat, tag, ep
    private val BUTTON_REGEX = Regex(
        """loadVideo(HYDRAX|SB|P2P)\(\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*'([^']*)'"""
    )

    private data class ResBtn(
        val svc: String,
        val epId: String,
        val qua: String,
        val res: String,
        val server: String,
        val cat: String,
        val tag: String,
        val ep: String
    )
    private const val UA =
        "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"

    data class ApiPayload(
        val detailUrl: String,
        val title: String,
        val movieId: String,
        val episodeId: String,
        val serverXid: String,
        val tag: String,
        val c: String,
        val t: String,
        val ver: String,
        val cApiHost: String,
        val isMob: String,
        val isUc: String,
        val mediaType: String,
        val cat: String = ""
    )

    fun normalizeUrl(url: String, mainUrl: String): String {
        val trimmed = url.trim()
            .removePrefix("\"")
            .removeSuffix("\"")
            .replace("\\/", "/")
        return when {
            trimmed.isBlank() -> ""
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> mainUrl.trimEnd('/') + trimmed
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> trimmed
        }
    }

    fun extractEmbedCandidates(document: Document, mainUrl: String): List<String> {
        val candidates = linkedSetOf<String>()

        document.select("iframe[src], embed[src], video[src], source[src]").forEach { element ->
            val src = element.attr("src").ifBlank { element.attr("data-src") }
            normalizeUrl(src, mainUrl).takeIf { it.isNotBlank() }?.let(candidates::add)
        }

        document.select("a[href], button[data-src], button[data-url], div[data-src], div[data-url], li[data-src], li[data-url]").forEach { element ->
            listOf(
                element.attr("href"),
                element.attr("data-src"),
                element.attr("data-url"),
                element.attr("data-video"),
                element.attr("data-link"),
                element.attr("data-embed")
            ).forEach { raw ->
                val fixed = normalizeUrl(raw, mainUrl)
                if (isPlayableOrEmbed(fixed)) candidates.add(fixed)
            }
        }

        document.select("option[value]").forEach { option ->
            val raw = option.attr("value").trim()
            val direct = normalizeUrl(raw, mainUrl)
            if (isPlayableOrEmbed(direct)) candidates.add(direct)

            decodeBase64(raw)?.let { decoded ->
                val decodedDoc = Jsoup.parse(decoded)
                decodedDoc.select("iframe[src], embed[src], video[src], source[src], a[href]").forEach { element ->
                    val url = normalizeUrl(
                        element.attr("src").ifBlank { element.attr("href") },
                        mainUrl
                    )
                    if (isPlayableOrEmbed(url)) candidates.add(url)
                }
                extractUrlsFromText(decoded, mainUrl).forEach(candidates::add)
            }
        }

        extractUnpackedUrls(document, mainUrl).forEach(candidates::add)
        extractUrlsFromText(document.html(), mainUrl).forEach(candidates::add)

        return candidates.filter { it.isNotBlank() }.distinct()
    }

    suspend fun extractSubtitles(document: Document, mainUrl: String): List<SubtitleFile> {
        return document.select("track[src], a[href$=.srt], a[href$=.vtt]").mapNotNull { element ->
            val url = normalizeUrl(element.attr("src").ifBlank { element.attr("href") }, mainUrl)
            if (url.isBlank()) return@mapNotNull null
            val label = element.attr("srclang")
                .ifBlank { element.attr("label") }
                .ifBlank { element.text() }
                .ifBlank { "Indonesia" }
            newSubtitleFile(label, url)
        }.distinctBy { it.url }
    }

    suspend fun resolveApiPlayback(
        providerName: String,
        mainUrl: String,
        payload: ApiPayload,
        ajaxHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val candidates = linkedSetOf<String>()
        val hints = linkedMapOf<String, Int>() // URL kandidat -> resolusi (untuk label kualitas)
        val cApiHost = payload.cApiHost.trimEnd('/')

        // Client Cloudstream tidak menyimpan cookie antar request. Ambil cookie sesi dari halaman detail
        // (seperti browser) dan kirim ke API; beberapa endpoint (server_mob/video) bisa memerlukannya.
        val pageCookies = runCatching {
            app.get(
                payload.detailUrl,
                headers = mapOf("User-Agent" to UA),
                referer = mainUrl
            ).cookies
        }.getOrDefault(emptyMap())
        Log.d(TAG, "cookie halaman: ${pageCookies.keys}")
        val apiHeaders = if (pageCookies.isEmpty()) ajaxHeaders
        else ajaxHeaders + ("Cookie" to pageCookies.entries.joinToString("; ") { "${it.key}=${it.value}" })

        var episodeIdSeed = payload.episodeId
        var serverXidSeed = payload.serverXid
        var tagSeed = payload.tag

        // Movie payloads and some detail fallbacks only carry movieId/tag from initEpisodeList().
        // HAR proves the site resolves the playable id through episode_mob.php first, then
        // server_mob.php, then video.php/video_sb.php/video_hydrax.php/video_p2p.php.
        if (episodeIdSeed.isBlank() && payload.movieId.isNotBlank()) {
            val episodeJson = apiGetJson(
                url = "$cApiHost/episode_mob.php" +
                    "?is_mob=${payload.isMob}" +
                    "&is_uc=${payload.isUc}" +
                    "&movie_id=${encode(payload.movieId)}" +
                    "&tag=${encode(payload.tag)}" +
                    "&c=${encode(payload.c)}" +
                    "&t=${encode(payload.t)}" +
                    "&ver=${encode(payload.ver)}",
                headers = apiHeaders,
                referer = payload.detailUrl
            )
            episodeIdSeed = episodeJson?.optString("first_ep_id").orEmpty()
            serverXidSeed = episodeJson?.optString("server_xid").orEmpty()
                .ifBlank { serverXidSeed }
            tagSeed = episodeJson?.optString("tag").orEmpty()
                .ifBlank { tagSeed }
            episodeJson?.optString("episode_lists")?.takeIf { it.isNotBlank() }?.let { html ->
                Jsoup.parse(html).selectFirst("a[data-tag]")?.attr("data-tag")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { tagSeed = it }
            }
        }

        // Log menunjukkan server_mob.php/video.php membalas HTTP 500 kalau tag salah: argumen kedua
        // initEpisodeList() adalah "cat" (mis. hs), sedangkan tag sebenarnya ada di data-tag anchor episode (mis. ind).
        val tagCandidates = listOf(tagSeed, payload.tag, "ind", payload.cat)
            .filter { it.isNotBlank() }
            .distinct()
        var usedTag = tagSeed
        var serverJson: JSONObject? = null
        if (episodeIdSeed.isNotBlank()) {
            val catValue = payload.cat.ifBlank { "hs" }
            outer@ for (cand in tagCandidates.take(2)) {
                for (extended in listOf(true, false)) {
                    val json = apiGetJson(
                        url = "$cApiHost/server_mob.php" +
                            "?is_mob=${payload.isMob}" +
                            "&is_uc=${payload.isUc}" +
                            "&episode_id=${encode(episodeIdSeed)}" +
                            "&tag=${encode(cand)}" +
                            "&server_xid=${encode(serverXidSeed)}" +
                            (if (extended)
                                "&movie_id=${encode(payload.movieId)}" +
                                    "&cat=${encode(catValue)}" +
                                    "&ptype=loc&server=server_loc"
                            else "") +
                            "&c=${encode(payload.c)}" +
                            "&t=${encode(payload.t)}" +
                            "&ver=${encode(payload.ver)}",
                        headers = apiHeaders,
                        referer = payload.detailUrl
                    )
                    if (json != null) {
                        serverJson = json
                        usedTag = cand
                        break@outer
                    }
                    Log.w(TAG, "server_mob gagal (tag=$cand, lengkap=$extended)")
                }
            }
        }

        val serverData = serverJson?.optJSONObject("data") ?: JSONObject()
        val episodeId = serverData.optString("id").ifBlank { episodeIdSeed }
        val serverId = serverData.optString("server_id").ifBlank { serverXidSeed }
        val tag = serverData.optString("tag").ifBlank { usedTag }
        val quality = serverData.optString("qua").ifBlank { "web" }
        val res = serverData.optString("res").ifBlank { "1080" }

        if (episodeId.isNotBlank()) {
            val videoJson = apiGetJson(
                url = "$cApiHost/video.php" +
                    "?is_mob=${payload.isMob}" +
                    "&is_uc=${payload.isUc}" +
                    "&id=${encode(episodeId)}" +
                    "&qua=${encode(quality)}" +
                    "&server_id=${encode(serverId)}" +
                    "&tag=${encode(tag)}" +
                    "&c=${encode(payload.c)}" +
                    "&t=${encode(payload.t)}" +
                    "&ver=${encode(payload.ver)}",
                headers = apiHeaders,
                referer = payload.detailUrl
            )

            Log.d(TAG, "video.php id=$episodeId qua=$quality server=$serverId tag=$tag")
            videoJson?.let { json ->
                listOf("file", "source", "video", "hls", "url", "download").forEach { field ->
                    val value = json.optString(field)
                    if (value.isNotBlank()) extractUrlsFromText(value, mainUrl).forEach(candidates::add)
                }
                json.optJSONObject("dl")?.let { dl ->
                    dl.keys().forEach { key ->
                        val value = normalizeUrl(dl.optString(key), mainUrl)
                        if (value.isNotBlank()) candidates.add(value)
                        extractUrlsFromText(dl.optString(key), mainUrl).forEach(candidates::add)
                    }
                }
            }

            // server_mob.php memberi ID tiap layanan (hydrax_id, p2p_id, sb_id). Endpoint video_*.php tanpa ID itu
            // hanya mengembalikan template kosong ("?v=", "/e/.html", "/#"), jadi ID dipakai langsung.
            val listDoc = Jsoup.parse(serverJson?.optString("server_lists").orEmpty())
            val listText = listDoc.text()
            Log.d(TAG, "server_lists: ${listText.take(600)}")
            Log.d(
                TAG,
                "server_lists html: " + listDoc.select("a, button, li, option").take(14)
                    .joinToString(" || ") { it.outerHtml().take(260) }.take(2400)
            )

            val services = listOf(
                Svc("hydrax", "video_hydrax.php", "hydrax_url") { "https://abysscdn.com/?v=$it" },
                Svc("sb", "video_sb.php", "sb_url") { "https://dqt.my.id/e/$it.html" },
                Svc("p2p", "video_p2p.php", "p2p_url") { "https://drakorkita.stream/#$it" }
            )

            // ID tiap layanan berbeda untuk tiap resolusi; data server_mob hanya mewakili satu resolusi.
            suspend fun collect(data: JSONObject, resLabel: String, only: String? = null) {
                services.filter { only == null || it.key == only }.forEach { svc ->
                    val sid = data.optString("${svc.key}_id")
                    val status = data.optString("${svc.key}_status")
                    if (sid.isBlank() || status == "0") {
                        Log.d(TAG, "${svc.key} ${resLabel}p: dilewati (id='$sid', status='$status')")
                        return@forEach
                    }
                    val json = apiGetJson(
                        url = "$cApiHost/${svc.endpoint}" +
                            "?is_mob=${payload.isMob}" +
                            "&is_uc=${payload.isUc}" +
                            "&id=${encode(sid)}" +
                            "&${svc.key}_id=${encode(sid)}" +
                            "&qua=${encode(quality)}" +
                            "&res=${encode(resLabel)}" +
                            "&server_id=${encode(serverId)}" +
                            "&tag=${encode(tag)}" +
                            "&c=${encode(payload.c)}" +
                            "&t=${encode(payload.t)}" +
                            "&ver=${encode(payload.ver)}",
                        headers = apiHeaders,
                        referer = payload.detailUrl
                    )
                    val fromApi = json?.optString(svc.urlField)?.takeIf { it.isNotBlank() }
                        ?.let { normalizeUrl(it, mainUrl) }
                    val chosen = if (fromApi != null && !isEmptyEmbed(fromApi)) fromApi else svc.template(sid)
                    Log.d(TAG, "${svc.key} ${resLabel}p: id=$sid -> $chosen")
                    candidates.add(chosen)
                    hints[chosen] = resLabel.toIntOrNull() ?: 0

                    val sub = chosen.substringAfter("sub=", "").substringBefore("&")
                    if (sub.startsWith("http") && !sub.endsWith("/.srt")) {
                        subtitleCallback(newSubtitleFile("Indonesia", sub))
                    }
                }
            }

            collect(serverData, res)

            // ---- Resolusi tambahan (720p/1080p) ----
            // Bukti dari log: server_mob.php SELALU membalas res=480 walau diminta res=720/1080 dengan ptype=loc,
            // sehingga kode lama tidak pernah mengumpulkan resolusi lain. Server memilih layanan lewat ptype
            // (hydrax/sb/p2p) dan svname ("HYDRAX 480p"). Cara paling andal adalah meniru tombol situs sendiri:
            //   onclick="loadVideoHYDRAX('<episodeId>','web','720','f1','hs','ind','1')"
            // Daftar tombol itu diparse dari server_lists, lalu tiap kombinasi (layanan, resolusi) dicoba
            // lewat beberapa varian parameter sampai ada URL embed yang valid.
            val rawList = serverJson?.optString("server_lists").orEmpty()
                .replace("&#39;", "'").replace("&apos;", "'")
            val buttons = BUTTON_REGEX.findAll(rawList).map {
                val g = it.groupValues
                ResBtn(g[1].lowercase(), g[2], g[3], g[4], g[5], g[6], g[7], g[8])
            }.toList()
            Log.d(TAG, "tombol resolusi (${buttons.size}): " + buttons.joinToString { "${it.svc}:${it.res}" })

            val extra = buttons
                .filter { it.svc != "p2p" && it.res != res } // p2p adalah SPA tanpa endpoint publik -> tidak bisa di-resolve
                .distinctBy { "${it.svc}-${it.res}" }

            for (b in extra) {
                val svc = services.firstOrNull { it.key == b.svc } ?: continue
                val common = "?is_mob=${payload.isMob}&is_uc=${payload.isUc}"
                val tail = "&c=${encode(payload.c)}&t=${encode(payload.t)}&ver=${encode(payload.ver)}"
                var found: String? = null

                // Cara 1: panggil video_<svc>.php persis seperti tombol situs (id = episode id, bukan id layanan).
                val variants = listOf(
                    "&id=${encode(b.epId)}&qua=${encode(b.qua)}&res=${encode(b.res)}" +
                        "&server_id=${encode(b.server)}&cat=${encode(b.cat)}&tag=${encode(b.tag)}&ep=${encode(b.ep)}",
                    "&id=${encode(b.epId)}&movie_id=${encode(payload.movieId)}&qua=${encode(b.qua)}&res=${encode(b.res)}" +
                        "&server_id=${encode(b.server)}&server_xid=${encode(b.server)}&cat=${encode(b.cat)}" +
                        "&tag=${encode(b.tag)}&ep=${encode(b.ep)}&ptype=${svc.key}"
                )
                for ((idx, v) in variants.withIndex()) {
                    val json = apiGetJson(
                        url = "$cApiHost/${svc.endpoint}$common$v$tail",
                        headers = apiHeaders,
                        referer = payload.detailUrl
                    )
                    val u = json?.optString(svc.urlField)?.takeIf { it.isNotBlank() }?.let { normalizeUrl(it, mainUrl) }
                    if (u != null && !isEmptyEmbed(u)) {
                        found = u
                        Log.d(TAG, "${svc.key} ${b.res}p: dapat via varian video_*.php #$idx -> $u")
                        break
                    }
                }

                // Cara 2: minta server_mob.php dengan ptype layanan + res, terima hanya jika res balasan cocok.
                if (found == null && episodeIdSeed.isNotBlank()) {
                    val json = apiGetJson(
                        url = "$cApiHost/server_mob.php$common" +
                            "&episode_id=${encode(episodeIdSeed)}&tag=${encode(usedTag)}" +
                            "&server_xid=${encode(b.server.ifBlank { serverXidSeed })}" +
                            "&movie_id=${encode(payload.movieId)}&cat=${encode(b.cat.ifBlank { payload.cat.ifBlank { "hs" } })}" +
                            "&ptype=${svc.key}&server=server_loc&res=${encode(b.res)}&qua=${encode(b.qua)}$tail",
                        headers = apiHeaders,
                        referer = payload.detailUrl
                    )
                    val d = json?.optJSONObject("data")
                    Log.d(
                        TAG,
                        "server_mob ptype=${svc.key} res=${b.res} -> res=${d?.optString("res")} svname=${d?.optString("svname")} " +
                            "${svc.key}_id=${d?.optString("${svc.key}_id")}"
                    )
                    if (d != null && d.optString("res") == b.res) collect(d, b.res, only = svc.key)
                } else if (found != null) {
                    candidates.add(found)
                    hints[found] = b.res.toIntOrNull() ?: 0
                    val sub = found.substringAfter("sub=", "").substringBefore("&")
                    if (sub.startsWith("http") && !sub.endsWith("/.srt")) {
                        subtitleCallback(newSubtitleFile("Indonesia", sub))
                    }
                } else {
                    Log.d(TAG, "${svc.key} ${b.res}p: tidak tersedia lewat API (tombol ada di situs, ID tidak diberikan server)")
                }
            }
        }

        Log.d(TAG, "kandidat API (${candidates.size}): $candidates")
        if (candidates.isEmpty()) return false

        return resolveCandidates(
            providerName = providerName,
            mainUrl = mainUrl,
            pageUrl = payload.detailUrl,
            candidates = candidates.toList(),
            subtitleCallback = subtitleCallback,
            callback = callback,
            qualityHints = hints
        )
    }

    suspend fun resolveCandidates(
        providerName: String,
        mainUrl: String,
        pageUrl: String,
        candidates: List<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        qualityHints: Map<String, Int> = emptyMap()
    ): Boolean {
        val seen = linkedSetOf<String>()
        var handled = false
        val outerCallback = callback

        suspend fun resolve(link: String, referer: String, depth: Int, hint: Int? = null) {
            val fixed = normalizeUrl(link, mainUrl)
            if (fixed.isBlank() || !seen.add(fixed) || depth > 2) return

            // Beri label resolusi pada link yang kualitasnya tidak diketahui extractor.
            val callback: (ExtractorLink) -> Unit = { l ->
                if (hint != null && hint > 0 && l.quality <= 0) l.quality = hint
                outerCallback.invoke(l)
            }

            when {
                fixed.contains(".m3u8", ignoreCase = true) -> {
                    // Master playlist -> daftar kualitas (480/720/1080) agar bisa dipilih di player.
                    // Log menunjukkan master.m3u8 (premilkyway.com) kadang timeout sekali -> 0 kualitas; coba ulang.
                    var generated = emptyList<ExtractorLink>()
                    for (attempt in 1..3) {
                        generated = runCatching {
                            M3u8Helper.generateM3u8(
                                source = providerName,
                                streamUrl = fixed,
                                referer = referer,
                                headers = mapOf("User-Agent" to UA, "Referer" to referer)
                            )
                        }.getOrNull().orEmpty()
                        if (generated.isNotEmpty()) break
                        Log.w(TAG, "m3u8 percobaan $attempt gagal/0 kualitas: $fixed")
                    }
                    Log.d(TAG, "m3u8 [$fixed] -> ${generated.size} kualitas")
                    if (generated.isNotEmpty()) {
                        generated.forEach { callback.invoke(it) }
                    } else {
                        callback.invoke(
                            newExtractorLink(
                                providerName,
                                providerName,
                                fixed,
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = referer
                                this.quality = parseQuality(fixed)
                                this.headers = mapOf("User-Agent" to UA, "Referer" to referer)
                            }
                        )
                    }
                    handled = true
                }
                fixed.contains(".mp4", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            providerName,
                            providerName,
                            fixed,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.referer = referer
                            this.quality = parseQuality(fixed)
                            this.headers = mapOf("User-Agent" to UA, "Referer" to referer)
                        }
                    )
                    handled = true
                }
                fixed.contains("drakorkita.stream", ignoreCase = true) && fixed.contains("#") -> {
                    // Player P2P (SPA, ID di fragment). Endpoint /api/v1/video terbukti 404, jadi baca skripnya
                    // dan catat endpoint yang dipakai untuk langkah berikutnya.
                    val base = fixed.substringBefore("#").trimEnd('/')
                    val vid = fixed.substringAfter("#")
                    runCatching {
                        val hdr = mapOf("User-Agent" to UA, "Referer" to mainUrl)
                        val html = app.get("$base/", headers = hdr, referer = mainUrl).text
                        Log.d(TAG, "p2p halaman ($vid) ${html.length} byte: ${html.take(400).replace("\n", " ")}")
                        Regex("""src=["']([^"']+\.js[^"']*)["']""").findAll(html)
                            .map { it.groupValues[1] }.toList().take(4).forEach { src ->
                                val jsUrl = normalizeUrl(src, base)
                                val js = app.get(jsUrl, headers = hdr + ("Referer" to "$base/"), referer = "$base/").text
                                val apis = Regex("""["'`](/(?:api|v\d)[A-Za-z0-9_/.\-]*)["'`]""").findAll(js)
                                    .map { it.groupValues[1] }.distinct().take(20).toList()
                                Log.d(TAG, "p2p js $jsUrl (${js.length} byte) endpoint: $apis")
                            }
                    }.onFailure { Log.e(TAG, "p2p diagnosis gagal: ${it.message}") }
                }
                else -> {
                    var extractorCount = 0
                    runCatching {
                        loadExtractor(fixed, referer, subtitleCallback) { l ->
                            callback.invoke(l)
                            extractorCount++
                        }
                    }.onFailure { Log.e(TAG, "extractor error $fixed: ${it.message}") }
                    Log.d(TAG, "extractor [$fixed] -> $extractorCount link")
                    if (extractorCount > 0) handled = true

                    // Tidak ada extractor yang cocok -> baca halamannya sendiri (host apa pun), cari m3u8/mp4/iframe.
                    if (extractorCount == 0 && depth < 2 && !isStaticAsset(fixed)) {
                        runCatching {
                            val response = app.get(
                                url = fixed,
                                headers = mapOf(
                                    "User-Agent" to UA,
                                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                                ),
                                referer = referer
                            )
                            val doc = response.document
                            val nestedList = extractEmbedCandidates(doc, mainUrl)
                            Log.d(TAG, "scan [$fixed] HTTP ${response.code}, ${response.text.length} byte, ${nestedList.size} kandidat")
                            extractSubtitles(doc, mainUrl).forEach(subtitleCallback)
                            var gotMedia = false
                            nestedList
                                .sortedBy { if (it.contains(".m3u8", true) || it.contains(".mp4", true)) 0 else 1 }
                                .forEach { nested ->
                                    val isMedia = nested.contains(".m3u8", true) || nested.contains(".mp4", true)
                                    if (gotMedia && !isMedia) return@forEach
                                    resolve(nested, fixed, depth + 1, hint)
                                    if (isMedia) gotMedia = true
                                }
                        }.onFailure { Log.e(TAG, "scan gagal $fixed: ${it.message}") }
                    }
                }
            }
        }

        candidates.forEach { resolve(it, pageUrl, 0, qualityHints[it]) }
        return handled
    }


    private fun decodeBase64(value: String): String? {
        val normalized = value.trim()
        if (normalized.isBlank()) return null
        val padded = normalized.padEnd(normalized.length + ((4 - normalized.length % 4) % 4), '=')
        return runCatching {
            String(Base64.getDecoder().decode(padded))
        }.getOrElse {
            runCatching { String(Base64.getUrlDecoder().decode(padded)) }.getOrNull()
        }
    }

    private suspend fun apiGetJson(
        url: String,
        headers: Map<String, String>,
        referer: String
    ): JSONObject? {
        val shortUrl = url.substringBefore('?')
        return try {
            val res = app.get(url = url, headers = headers, referer = referer)
            val body = res.text
            Log.d(TAG, "GET $shortUrl -> HTTP ${res.code}: ${body.take(500)}")
            if (res.code != 200) Log.d(TAG, "  header respons: ${res.headers.toString().replace("\n", " | ").take(500)}")
            JSONObject(body)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "API gagal $shortUrl: ${e.message}")
            null
        }
    }

    private fun extractUnpackedUrls(document: Document, mainUrl: String): List<String> {
        val results = linkedSetOf<String>()
        document.select("script").forEach { script ->
            val scriptText = script.data().ifBlank { script.html() }
            if (scriptText.contains("function(p,a,c,k,e,d)")) {
                runCatching { JsUnpacker(scriptText).unpack() }.getOrNull()?.let { unpacked ->
                    extractUrlsFromText(unpacked, mainUrl).forEach(results::add)
                    buildDqtHlsCandidates(unpacked).forEach(results::add)
                }
            }
        }
        return results.toList()
    }

    private fun buildDqtHlsCandidates(unpacked: String): List<String> {
        val direct = Regex("""https?://[^'"\s<>]+\.m3u8[^'"\s<>]*""")
            .find(unpacked)
            ?.value
            ?.replace("\\/", "/")
        if (!direct.isNullOrBlank()) return listOf(direct)

        val tokens = Regex("""['"]([A-Za-z0-9_-]{8,})['"]""")
            .findAll(unpacked)
            .map { it.groupValues[1] }
            .toList()
        val streamId = tokens.firstOrNull { it.length >= 20 }
        val folder = tokens.firstOrNull { it.length >= 12 && it != streamId }
        val expires = Regex("""\b(17\d{8,})\b""")
            .findAll(unpacked)
            .map { it.groupValues[1] }
            .firstOrNull()
        val fileId = Regex("""file_code['"]?\s*[:=]\s*['"]?([A-Za-z0-9_-]+)""")
            .find(unpacked)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""\b([A-Za-z0-9]{10,})_n\b""")
                .find(unpacked)
                ?.groupValues
                ?.getOrNull(1)

        if (streamId.isNullOrBlank() || folder.isNullOrBlank() || expires.isNullOrBlank() || fileId.isNullOrBlank()) {
            return emptyList()
        }

        return listOf("https://dqt.my.id/stream/$streamId/$folder/$expires/$fileId/master.m3u8")
    }

    private fun extractUrlsFromText(text: String, mainUrl: String): List<String> {
        val results = linkedSetOf<String>()
        val normalized = text
            .replace("\\/", "/")
            .replace("&amp;", "&")
        val urlRegex = Regex("""https?://[^'"\\\s<>]+|//[^'"\s<>]+""")
        urlRegex.findAll(normalized).forEach { match ->
            val fixed = normalizeUrl(match.value, mainUrl)
                .trimEnd(',', '.', ';', ')', ']', '}')
            if (isPlayableOrEmbed(fixed)) results.add(fixed)
        }
        return results.toList()
    }

    private fun isPlayableOrEmbed(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        return lower.contains("/embed") ||
            lower.contains("/e/") ||
            lower.contains("/v/") ||
            lower.contains("iframe") ||
            lower.contains("player") ||
            lower.contains("stream") ||
            lower.contains("watch") ||
            lower.contains("abysscdn") ||
            lower.contains("dqt.my.id") ||
            lower.contains("uyeshare") ||
            lower.contains("drakorkita.stream") ||
            lower.contains("handal.bid") ||
            lower.contains("uyeshare.cc") ||
            lower.contains("/download/") ||
            lower.contains(".m3u8") ||
            lower.contains(".mp4")
    }

    /** URL embed yang ID videonya kosong (mis. ?v=?sub=..., /e/.html, https://host/#). */
    private fun isEmptyEmbed(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith("/#") || lower.endsWith("#") ||
            lower.contains("/e/.html") ||
            lower.contains("?v=?") || lower.contains("?v=&") || lower.endsWith("?v=") ||
            lower.contains("/subtitle/.srt")
    }

    private fun isStaticAsset(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return listOf(".js", ".css", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".woff", ".woff2", ".ttf")
            .any { path.endsWith(it) }
    }

    private fun shouldScanNestedPage(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("dqt.my.id") ||
            lower.contains("abysscdn") ||
            lower.contains("drakorkita.stream") ||
            lower.contains("uyeshare")
    }

    private fun parseQuality(url: String): Int {
        return Regex("""(2160|1440|1080|720|480|360|240)p""", RegexOption.IGNORE_CASE)
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""(?:^|[^\d])(2160|1440|1080|720|480|360|240)(?:[^\d]|$)""")
                .find(url)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}