package eu.kanade.tachiyomi.extension.pt.acervoeremita

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable

@Serializable
class ExploreDto(
    val works: List<WorkDto>,
    val pagination: PaginationDto,
)

@Serializable
class PaginationDto(
    val hasNext: Boolean = false,
)

@Serializable
class WorkDto(
    private val title: String,
    private val slug: String,
    private val coverUrl: String? = null,
    val genreName: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = slug
        title = this@WorkDto.title.trim()
        thumbnail_url = coverUrl
    }
}

@Serializable
class ChapterListDto(
    val chapters: List<ChapterDto> = emptyList(),
    val pagination: ChapterPaginationDto = ChapterPaginationDto(),
)

@Serializable
class ChapterPaginationDto(
    val hasMore: Boolean = false,
    val nextCursor: String? = null,
)

@Serializable
class ChapterDto(
    val chapterId: Long,
    val chapterNumber: String,
    val name: String,
    val pageCount: Int = 0,
    val releasedAt: String? = null,
)

@Serializable
class ReaderDto(
    val chapter: ReaderChapterDto,
    val pages: List<PageDto>,
)

@Serializable
class ReaderChapterDto(
    val chapterId: Long,
)

@Serializable
class PageDto(
    val pageNumber: Int,
    val imageSrc: String,
    val imagePathReader: String,
    val imageUrl: String,
)

@Serializable
data class FilterDataDto(
    val genres: List<GenreDto>,
    val tags: List<TagDto>,
)

@Serializable
class GenreDto(
    val name: String,
    val slug: String,
)

@Serializable
class TagDto(
    val name: String,
)
