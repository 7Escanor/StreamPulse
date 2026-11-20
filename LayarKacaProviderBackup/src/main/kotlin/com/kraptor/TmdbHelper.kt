package com.kraptor

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class TmdbArtwork(
    val tmdbId: Int,
    val imdbId: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val logoUrl: String?,
)

data class TmdbEpisode(
    val name: String?,
    val overview: String?,
    val rating: Double?,
    val airDate: String?,
    val stillUrl: String?,
)

/**
 * Poster, backdrop, logo judul dan data per episode dari TMDB.
 * Hasil hanya dipakai jika judul PERSIS sama (dan tahun sama bila diketahui).
 * Jika tidak tersedia / tidak exact -> mengembalikan null / map kosong,
 * sehingga pemanggil memakai data bawaan situs seperti sebelumnya.
 */
object TmdbHelper {
    private const val API = "https://api.themoviedb.org/3"
    private const val IMG = "https://image.tmdb.org/t/p"
    private const val TOKEN =
        "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiIwZTc3ZDg5ODliOWNjMmMzYmY1N2JiNzliZGY1MTVhZiIsIm5iZiI6MTc1OTIyNTQ0OC4xNzkwMDAxLCJzdWIiOiI2OGRiYTY2ODcwMmFiNDc0OTBjNzZkMzMiLCJzY29wZXMiOlsiYXBpX3JlYWQiXSwidmVyc2lvbiI6MX0.FosBTfa77PxTS4Y2GZpp4r0Br298AUNH0QmIN5p_Mrc"

    private val seasonMarker = Regex("\\b(season|musim|s)\\s*\\d+\\b", RegexOption.IGNORE_CASE)
    private val genericEpisodeName = Regex("^(episode|eps?\\.?)\\s*\\d+$", RegexOption.IGNORE_CASE)

    private fun headers() = mapOf(
        "Authorization" to "Bearer $TOKEN",
        "accept" to "application/json",
    )

    private fun JSONObject.strOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    /** Bersihkan judul situs: "(2009)", "Nonton ...", "Sub Indo", tag [..], dan "Season N" untuk series. */
    private fun cleanTitle(raw: String, isTv: Boolean): String {
        var t = raw
        t = t.replace(Regex("\\[.*?]"), " ")
        t = t.replace(Regex("\\(\\s*\\d{4}\\s*\\)"), " ")
        t = t.replace(Regex("^\\s*nonton\\s+(film|movie|series|serial)?\\s*", RegexOption.IGNORE_CASE), "")
        t = t.replace(
            Regex(
                "\\b(sub(title)?\\s*indo(nesia)?|streaming|download|lk21|layarkaca21|dunia21|nontondrama)\\b.*$",
                RegexOption.IGNORE_CASE
            ), ""
        )
        if (isTv) t = t.replace(seasonMarker, " ")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    private fun normalize(s: String): String =
        s.lowercase()
            .replace("&", " and ")
            .replace(Regex("\\[.*?]"), " ")
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    suspend fun fetchArtwork(
        rawTitle: String,
        year: Int?,
        isTv: Boolean,
        langCode: String?,
    ): TmdbArtwork? {
        return runCatching {
            withTimeoutOrNull(10_000L) {
                val kind = if (isTv) "tv" else "movie"
                val hasSeasonMarker = isTv && seasonMarker.containsMatchIn(rawTitle)
                val queryTitle = cleanTitle(rawTitle, isTv)
                val normQuery = normalize(queryTitle)
                if (normQuery.isBlank()) return@withTimeoutOrNull null

                // 1) Search
                val searchUrl = "$API/search/$kind" +
                        "?query=${URLEncoder.encode(queryTitle, "UTF-8")}" +
                        "&include_adult=false&language=en-US&page=1"
                val results = JSONObject(app.get(searchUrl, headers = headers()).text)
                    .optJSONArray("results") ?: return@withTimeoutOrNull null

                val titleKeys = if (isTv) listOf("name", "original_name") else listOf("title", "original_title")
                val dateKey = if (isTv) "first_air_date" else "release_date"

                val exact = mutableListOf<Pair<Int, Int?>>() // id, tahun rilis
                for (i in 0 until results.length()) {
                    val o = results.optJSONObject(i) ?: continue
                    val id = o.optInt("id", -1)
                    if (id == -1) continue
                    val titleExact = titleKeys.any { key ->
                        o.strOrNull(key)?.let { normalize(it) == normQuery } == true
                    }
                    if (!titleExact) continue
                    exact.add(id to o.strOrNull(dateKey)?.take(4)?.toIntOrNull())
                }

                val tmdbId: Int = when {
                    exact.isEmpty() -> null
                    year == null -> exact.first().first
                    else -> exact.firstOrNull { it.second == year }?.first
                        // Judul series dengan "Season N": tahun di situs = tahun season, bukan tahun tayang perdana.
                        ?: (if (hasSeasonMarker) exact.firstOrNull { val cy = it.second; cy != null && cy <= year }?.first else null)
                } ?: return@withTimeoutOrNull null

                // 2) Detail + images + external ids dalam satu request
                val langs = listOfNotNull(
                    langCode?.substringBefore("-")?.lowercase()?.takeIf { it.isNotBlank() },
                    "en"
                ).distinct()
                val detailUrl = "$API/$kind/$tmdbId" +
                        "?language=en-US&append_to_response=images,external_ids" +
                        "&include_image_language=${langs.joinToString(",")},null"
                val detail = JSONObject(app.get(detailUrl, headers = headers()).text)

                TmdbArtwork(
                    tmdbId = tmdbId,
                    imdbId = detail.optJSONObject("external_ids")?.strOrNull("imdb_id"),
                    posterUrl = detail.strOrNull("poster_path")?.let { "$IMG/w500$it" },
                    backdropUrl = detail.strOrNull("backdrop_path")?.let { "$IMG/w1280$it" },
                    logoUrl = pickLogo(detail.optJSONObject("images")?.optJSONArray("logos"), langs)
                        ?.let { "$IMG/w500$it" },
                )
            }
        }.getOrNull()
    }

    /** Urutan prioritas logo: bahasa plugin (id) -> en -> tanpa bahasa. File SVG dilewati. */
    private fun pickLogo(logos: JSONArray?, langs: List<String>): String? {
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

    /**
     * Data tiap episode (judul, sinopsis, rating, tanggal rilis, banner) per season secara paralel.
     * Bahasa utama = bahasa plugin (id), jika kosong dipakai Inggris.
     * Key = Pair(season, episode).
     */
    suspend fun fetchEpisodes(
        tmdbId: Int,
        seasons: List<Int>,
        langCode: String?,
    ): Map<Pair<Int, Int>, TmdbEpisode> {
        val seasonList = seasons.filter { it > 0 }.distinct()
        if (seasonList.isEmpty()) return emptyMap()

        val preferred = langCode?.trim()?.lowercase()?.takeIf { it.isNotBlank() && !it.startsWith("en") }

        suspend fun fetchSeason(se: Int, language: String): Map<Int, JSONObject> = runCatching {
            val url = "$API/tv/$tmdbId/season/$se?language=$language"
            val eps = JSONObject(app.get(url, headers = headers()).text).optJSONArray("episodes")
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
            withTimeoutOrNull(12_000L) {
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
                                .firstOrNull { !genericEpisodeName.matches(it) }
                            val overview = l?.strOrNull("overview") ?: e?.strOrNull("overview")

                            val votes = base.optInt("vote_count", 0)
                            val avg = base.optDouble("vote_average", 0.0)
                            val rating = if (votes > 0 && avg > 0.0) avg else null

                            val airDate = base.strOrNull("air_date")
                            val still = base.strOrNull("still_path")?.let { "$IMG/w300$it" }

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
}
