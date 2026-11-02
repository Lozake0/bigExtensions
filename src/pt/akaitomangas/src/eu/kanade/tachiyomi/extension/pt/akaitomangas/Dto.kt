package eu.kanade.tachiyomi.extension.pt.akaitomangas

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

@Serializable
class RankPageDto(
    val data: List<ProjectDto> = emptyList(),
    val hasMore: Boolean = false,
)

@Serializable
class ProjectDto(
    private val id: String,
    private val title: String,
    private val link: String,
    @SerialName("cover_image") private val coverImage: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = "$id/$link"
        title = this@ProjectDto.title.trim()
        thumbnail_url = coverImage
    }
}

@Serializable
class ProjectPageDto(
    val project: ProjectDetailsDto,
    val groups: List<GroupDto> = emptyList(),
)

@Serializable
class ProjectDetailsDto(
    private val title: String,
    @SerialName("alt_titles") private val altTitles: List<String> = emptyList(),
    private val synopsis: String? = null,
    @SerialName("cover_image") private val coverImage: String? = null,
    private val status: String? = null,
    private val authors: List<NameDto> = emptyList(),
    private val artists: List<NameDto> = emptyList(),
    private val genders: List<NameDto> = emptyList(),
) {
    fun toSManga(url: String) = SManga.create().apply {
        this.url = url
        title = this@ProjectDetailsDto.title.trim()
        thumbnail_url = coverImage
        author = authors.joinToString { it.name }.ifEmpty { null }
        artist = artists.joinToString { it.name }.ifEmpty { null }
        genre = genders.map { it.name }.distinct().joinToString()
        status = when (this@ProjectDetailsDto.status) {
            "ACTIVE", "UP_TO_DATE" -> SManga.ONGOING
            "FINISHED" -> SManga.COMPLETED
            "HIATUS" -> SManga.ON_HIATUS
            "DROPPED" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        description = buildString {
            synopsis?.trim()?.let(::append)
            altTitles.filter(String::isNotBlank).takeIf(List<String>::isNotEmpty)?.let {
                append("\n\nTítulos alternativos: ", it.joinToString())
            }
        }.trim()
    }
}

@Serializable
class NameDto(
    val name: String,
)

@Serializable
class GroupDto(
    val chapters: List<ChapterDto> = emptyList(),
)

@Serializable
class ChapterDto(
    val title: JsonPrimitive,
    @SerialName("created_at") val createdAt: String? = null,
    val accessType: String = "FREE",
    val isUpcoming: Boolean = false,
)

@Serializable
class ReaderDto(
    val pages: List<PageDto>,
)

@Serializable
class PageDto(
    val photo: String,
    val pageNumber: Int = 0,
)

@Serializable
class GendersDto(
    val availableGenders: List<GenderDto>,
)

@Serializable
class GenderDto(
    val name: String,
    @SerialName("_count") val count: CountDto? = null,
)

@Serializable
class CountDto(
    val mangas: Int = 0,
)
