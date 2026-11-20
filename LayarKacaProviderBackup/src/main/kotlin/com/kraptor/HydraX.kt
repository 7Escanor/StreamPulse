package com.kraptor

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

// https://github.com/smy778/EncDecEndpoints
open class AbyssExtractor : ExtractorApi() {
    override val name = "Abyss"
    override val mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    // Mendukung tanda kutip ganda maupun tunggal pada: const datas = "...."
    private val datasRegex = Regex("""(?:const|var|let)\s+datas\s*=\s*["']([^"']+)["']""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Semua domain turunan (short.ink, short.icu, abysscdn, playhydrax, ...) dialihkan ke host player Abyss
        val path = url.substringAfter("://").substringAfter("/")
        val updatedurl = "https://abyssplayer.com/$path"
        val useragent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:150.0) Gecko/20100101 Firefox/150.0"
        val videoreferer = "https://playhydrax.com"
        val apiurl = "https://enc-dec.app/api/dec-abyss"
        val baseheaders = mapOf(
            "User-Agent" to useragent,
            "Referer" to "https://abyssplayer.com"
        )

        val firstdoc = app.get(updatedurl, headers = baseheaders, allowRedirects = false)
        val location = firstdoc.headers["location"]
        val targeturl = when {
            location.isNullOrBlank() -> updatedurl
            location.startsWith("http") -> location
            location.startsWith("//") -> "https:$location"
            else -> "https://abyssplayer.com" + (if (location.startsWith("/")) location else "/$location")
        }

        Log.d("Abyss", targeturl)

        val html = app.get(targeturl, headers = baseheaders).text
        val enc = datasRegex.find(html)?.groups?.get(1)?.value
        if (enc == null) {
            Log.d("Abyss", "datas tidak ditemukan di $targeturl")
            return
        }

        Log.d("Abyss", enc)

        val json = """{"text":"$enc","agent":"$useragent"}"""
        val mediatype = "application/json; charset=utf-8".toMediaTypeOrNull()
        val reqbody = json.toRequestBody(mediatype)

        val apires = app.post(
            apiurl,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Origin" to "https://enc-dec.app",
                "User-Agent" to useragent
            ),
            requestBody = reqbody
        ).parsedSafe<AbyssResponse>()

        Log.d("Abyss", apires?.status.toString())

        apires?.result?.sources?.forEach { source ->
            val videourl = source.url ?: return@forEach
            val qualitytext = source.type
            val linktype = if (videourl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

            Log.d("Abyss", videourl)

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Abyss",
                    url = videourl,
                    type = linktype,
                    initializer = {
                        this.referer = videoreferer
                        this.quality = getQualityFromName(qualitytext)
                        this.headers = mutableMapOf("User-Agent" to useragent, "Referer" to videoreferer)
                    }
                )
            )
        }
    }

    data class AbyssResponse(
        @JsonProperty("status") val status: Int?,
        @JsonProperty("result") val result: AbyssResult?
    )

    data class AbyssResult(
        @JsonProperty("sources") val sources: List<AbyssSource>?
    )

    data class AbyssSource(
        @JsonProperty("url") val url: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("codec") val codec: String?
    )
}

class Shortink : AbyssExtractor() {
    override var mainUrl = "https://short.ink"
}

class Shorticu : AbyssExtractor() {
    override var mainUrl = "https://short.icu"
}

class AbyssCdn : AbyssExtractor() {
    override var mainUrl = "https://abysscdn.com"
}

class AbyssTo : AbyssExtractor() {
    override var mainUrl = "https://abyss.to"
}

class PlayHydrax : AbyssExtractor() {
    override var mainUrl = "https://playhydrax.com"
}
