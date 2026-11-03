package eu.kanade.tachiyomi.extension.pt.toonbr

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Instant

@Serializable
class MangaListDto(
    val data: List<MangaDto> = emptyList(),
    val meta: MetaDto,
)

@Serializable
class MetaDto(
    val page: Int = 1,
    val totalPages: Int = 1,
)

@Serializable
class MangaDto(
    private val title: String,
    private val slug: String,
    private val description: String? = null,
    private val author: String? = null,
    private val artist: String? = null,
    private val status: String? = null,
    private val coverImage: String? = null,
    private val categories: List<JsonElement> = emptyList(),
    val chapters: List<ChapterDto> = emptyList(),
) {
    fun toSManga(cdnUrl: String) = SManga.create().apply {
        url = "/manga/$slug"
        title = this@MangaDto.title.trim()
        thumbnail_url = coverImage?.let { cdnUrl + it }
        description = this@MangaDto.description?.trim()
        author = this@MangaDto.author?.takeIf(String::isNotBlank)
        artist = this@MangaDto.artist?.takeIf(String::isNotBlank)
        genre = categories.flatMap { it.names() }.distinct().joinToString()
        status = when (this@MangaDto.status) {
            "ONGOING" -> SManga.ONGOING
            "COMPLETED" -> SManga.COMPLETED
            "HIATUS" -> SManga.ON_HIATUS
            "CANCELLED", "DROPPED" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private fun JsonElement.names(): List<String> = when (this) {
        is JsonArray -> flatMap { it.names() }
        is JsonObject -> get("name")?.names() ?: get("category")?.names().orEmpty()
        is JsonPrimitive -> listOfNotNull(contentOrNull?.takeIf(String::isNotBlank))
    }
}

@Serializable
class ChapterDto(
    private val id: String,
    private val chapterNumber: Float? = null,
    private val title: String? = null,
    private val publishedAt: String? = null,
    private val createdAt: String? = null,
) {
    fun toSChapter() = SChapter.create().apply {
        url = "/chapter/$id"
        name = chapterNumber?.let { "Capítulo ${it.toString().removeSuffix(".0")}" } ?: title.orEmpty()
        chapter_number = chapterNumber ?: -1f
        date_upload = Instant.tryParse(publishedAt ?: createdAt)
    }
}

@Serializable
class ChapterPagesDto(
    val pages: List<PageDto> = emptyList(),
)

@Serializable
class PageDto(
    val imageUrl: String? = null,
    val pageNumber: Int = 0,
)

@Serializable
class CategoryDto(
    val id: String,
    val name: String,
)
