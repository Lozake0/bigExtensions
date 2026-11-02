package eu.kanade.tachiyomi.extension.pt.lycantoons

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

@Serializable
class SeriesRequestDto(
    private val page: Int,
    private val limit: Int,
    private val search: String? = null,
    private val seriesType: String? = null,
    private val status: String? = null,
    private val tags: List<String>? = null,
    private val original: Boolean? = null,
    private val orderBy: String? = null,
)

@Serializable
class SeriesListDto(
    val series: List<SeriesDto>,
    private val pagination: PaginationDto,
) {
    val hasNextPage get() = pagination.hasNextPage
}

@Serializable
class PaginationDto(
    val hasNextPage: Boolean,
)

@Serializable
class SeriesDto(
    val slug: String,
    private val title: String,
    @SerialName("original_title") private val originalTitle: String? = null,
    private val description: String? = null,
    private val coverUrl: String? = null,
    private val status: String? = null,
    private val author: String? = null,
    private val artist: String? = null,
    private val genre: List<String> = emptyList(),
    private val seriesType: String? = null,
) {
    val isNovel get() = seriesType == "NOVEL"

    fun toSManga() = SManga.create().apply {
        url = slug
        title = this@SeriesDto.title
        thumbnail_url = coverUrl?.takeIf(String::isNotBlank)
        author = this@SeriesDto.author.known()
        artist = this@SeriesDto.artist.known()
        description = buildString {
            this@SeriesDto.description?.takeIf(String::isNotBlank)?.let(::append)
            originalTitle?.takeIf { it.isNotBlank() && it != title }?.let {
                if (isNotEmpty()) append("\n\n")
                append("Título original: $it")
            }
        }.takeIf(String::isNotEmpty)
        genre = buildList {
            seriesType?.let { add(it.lowercase().replaceFirstChar(Char::uppercase)) }
            this@SeriesDto.genre.mapTo(this) { GENRES[it] ?: it }
        }.distinct().joinToString()
        status = when (this@SeriesDto.status) {
            "ONGOING" -> SManga.ONGOING
            "COMPLETED" -> SManga.COMPLETED
            "HIATUS" -> SManga.ON_HIATUS
            "CANCELLED" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private fun String?.known() = this?.takeIf { it.isNotBlank() && it != "-" }
}

@Serializable
class ChapterListDto(
    val chapters: List<ChapterDto> = emptyList(),
    val total: Int? = null,
)

@Serializable
class ChapterDto(
    val id: Long,
    private val numero: Float,
    private val titulo: String? = null,
    private val createdAt: String? = null,
    private val vipLockHours: Int = 0,
) {
    val label get() = numero.toString().removeSuffix(".0")

    // VIP early access: the chapter opens to everyone vipLockHours after release.
    fun isLocked(now: Long) = vipLockHours > 0 && Instant.tryParse(createdAt) + vipLockHours * 3_600_000L > now

    fun toSChapter(slug: String) = SChapter.create().apply {
        url = "/series/$slug/$label"
        name = buildString {
            append("Capítulo $label")
            titulo?.takeIf(String::isNotBlank)?.let { append(" - $it") }
        }
        chapter_number = numero
        date_upload = Instant.tryParse(createdAt)
        memo = buildJsonObject { put("id", id) }
    }
}

@Serializable
class PagesDto(
    val id: Long? = null,
    val pages: List<String>? = null,
    val enc: String? = null,
    private val kid: JsonPrimitive? = null,
) {
    val keyId get() = kid?.content
}

@Serializable
class ReaderKeysDto(
    val keys: List<ReaderKeyDto> = emptyList(),
)

@Serializable
class ReaderKeyDto(
    private val kid: JsonPrimitive,
    val k: String,
) {
    val id get() = kid.content
}
