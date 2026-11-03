package eu.kanade.tachiyomi.extension.pt.hipertoon

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Instant

@Serializable
class TrpcResponseDto<T>(
    val result: TrpcResultDto<T>,
)

@Serializable
class TrpcResultDto<T>(
    val data: TrpcDataDto<T>,
)

@Serializable
class TrpcDataDto<T>(
    val json: T,
)

@Serializable
class SearchResultDto(
    val hits: List<MangaDto> = emptyList(),
    val totalHits: Int = 0,
)

@Serializable
class MangaDto(
    val id: Long,
    val slug: String,
    private val title: String,
    private val synopsis: String? = null,
    private val coverUrl: String? = null,
    private val status: String? = null,
    private val type: String? = null,
    private val contentRating: String? = null,
    private val year: Int? = null,
    private val alternativeTitles: JsonElement? = null,
    private val genres: List<String> = emptyList(),
    private val authors: List<String> = emptyList(),
    private val artists: List<String> = emptyList(),
) {
    fun toSManga() = SManga.create().apply {
        url = "/manga/$slug"
        title = this@MangaDto.title.trim()
        thumbnail_url = coverUrl
        author = authors.joinToString().ifEmpty { null }
        artist = artists.joinToString().ifEmpty { null }
        genre = (genres + listOfNotNull(type?.let(TYPES::get), contentRating?.let(RATINGS::get))).distinct().joinToString()
        description = listOfNotNull(
            synopsis?.trim()?.takeIf(String::isNotEmpty),
            alternativeNames().takeIf(List<String>::isNotEmpty)?.joinToString(prefix = "Títulos alternativos: "),
            year?.let { "Ano: $it" },
        ).joinToString("\n\n")
        status = when (this@MangaDto.status) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private fun alternativeNames(): List<String> = when (val value = alternativeTitles) {
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> value.contentOrNull?.let { raw -> if (raw.startsWith("[")) raw.parseAs<List<String>>() else listOf(raw) }.orEmpty()
        else -> emptyList()
    }.map(String::trim).filter { it.isNotEmpty() && it != title }.distinct()

    companion object {
        private val TYPES = mapOf("manga" to "Mangá", "manhwa" to "Manhwa", "manhua" to "Manhua", "one_shot" to "One-shot", "novel" to "Novel", "webtoon" to "Webtoon")
        private val RATINGS = mapOf("suggestive" to "Sugestivo", "erotica" to "Erótico", "pornographic" to "Pornográfico")
    }
}

@Serializable
class ChapterDto(
    private val id: Long,
    private val number: Float,
    private val title: String? = null,
    private val status: String? = null,
    private val isDeleted: Boolean = false,
    private val createdAt: String? = null,
) {
    val isPublished: Boolean get() = !isDeleted && (status == null || status == "published")

    fun toSChapter(slug: String) = SChapter.create().apply {
        val label = number.toString().removeSuffix(".0")
        url = "/manga/$slug/$id"
        name = title?.replace(TITLE_PREFIX, "")?.trim()?.takeIf { it.isNotEmpty() && it != label }?.let { "Capítulo $label - $it" } ?: "Capítulo $label"
        chapter_number = number
        date_upload = Instant.tryParse(createdAt)
    }

    companion object {
        private val TITLE_PREFIX = Regex("""^\s*cap[ií]tulo\s*[\d.,]*\s*[:\-–—.]?""", RegexOption.IGNORE_CASE)
    }
}

@Serializable
class PageDto(
    val pageOrder: Int,
    private val webpUrl: String? = null,
    private val avifUrl: String? = null,
) {
    val imageUrl: String get() = webpUrl ?: avifUrl.orEmpty()
}

@Serializable
class GenreDto(
    val name: String,
)
