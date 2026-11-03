package eu.kanade.tachiyomi.extension.pt.roxinha

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Instant

@Serializable
class LoginRequestDto(
    private val email: String,
    private val password: String,
    private val rememberMe: Boolean = true,
)

@Serializable
class LoginResponseDto(
    val token: String,
)

@Serializable
class ErrorDto(
    val error: String? = null,
)

@Serializable
class SearchResponseDto(
    val mangas: List<MangaDto> = emptyList(),
    val hasMore: Boolean = false,
)

@Serializable
class MangaDto(
    private val id: Int,
    private val title: String,
    private val cover: String? = null,
    private val author: String? = null,
    private val description: String? = null,
    private val status: String? = null,
    private val genres: String? = null,
    private val type: String? = null,
    private val year: JsonPrimitive? = null,
    @SerialName("alt_titles") private val altTitles: JsonElement? = null,
    private val chapters: List<ChapterDto> = emptyList(),
) {
    fun toSManga(baseUrl: String) = SManga.create().apply {
        url = "/manga/$id"
        title = this@MangaDto.title.trim()
        thumbnail_url = cover?.takeIf(String::isNotBlank)?.let { if (it.startsWith("http")) it else baseUrl + it }
        author = this@MangaDto.author?.trim()?.takeUnless { it.isEmpty() || it.lowercase() in UNKNOWN_AUTHORS }
        genre = (listOfNotNull(type) + genres.orEmpty().split(',').map(String::trim)).filter(String::isNotEmpty).distinct().joinToString()
        description = listOfNotNull(
            this@MangaDto.description?.trim()?.takeIf(String::isNotEmpty),
            alternativeTitles().takeIf(List<String>::isNotEmpty)?.joinToString(prefix = "Títulos alternativos: "),
            year?.contentOrNull?.takeIf(String::isNotBlank)?.let { "Ano: $it" },
        ).joinToString("\n\n")
        status = when (this@MangaDto.status) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    fun toSChapters(): List<SChapter> = chapters.map(ChapterDto::toSChapter).sortedByDescending(SChapter::chapter_number)

    private fun alternativeTitles(): List<String> = when (val value = altTitles) {
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> value.contentOrNull.orEmpty().split(',', ';', '\n')
        else -> emptyList()
    }.map(String::trim).filter { it.isNotEmpty() && it != title }

    companion object {
        private val UNKNOWN_AUTHORS = setOf("desconhecido", "n/a", "na", "-", "unknown")
    }
}

@Serializable
class ChapterDto(
    private val id: Int,
    private val chapterNumber: Float? = null,
    private val title: String? = null,
    private val createdAt: String? = null,
    private val chapterIsVipOnly: Boolean = false,
) {
    fun toSChapter() = SChapter.create().apply {
        val number = chapterNumber?.let { it.toString().removeSuffix(".0") }
        val label = title?.trim()?.takeIf { it.isNotEmpty() && it.toFloatOrNull() == null && it != number }
        url = "/reader/$id"
        name = listOfNotNull(number?.let { "Capítulo $it" }, label).joinToString(" - ").ifEmpty { "Capítulo" }
        chapter_number = chapterNumber ?: -1f
        date_upload = Instant.tryParse(createdAt)
        scanlator = if (chapterIsVipOnly) "VIP" else null
    }
}

@Serializable
class TicketDto(
    val ticket: String,
)

@Serializable
class ChapterDetailsDto(
    val pages: List<String> = emptyList(),
)

@Serializable
class GenresDto(
    val genres: List<String> = emptyList(),
)

@Serializable
class TypesDto(
    val types: List<String> = emptyList(),
)

@Serializable
class YearsDto(
    val years: List<Int> = emptyList(),
)

@Serializable
class FilterDataDto(
    val genres: List<String> = emptyList(),
    val types: List<String> = emptyList(),
    val years: List<Int> = emptyList(),
)
