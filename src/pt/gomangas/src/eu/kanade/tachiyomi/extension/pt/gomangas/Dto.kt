package eu.kanade.tachiyomi.extension.pt.gomangas

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class BookDto(
    @SerialName("@type") val type: String? = null,
    val image: String? = null,
    val description: String? = null,
    val genre: List<String> = emptyList(),
    val author: AuthorDto? = null,
)

@Serializable
class AuthorDto(
    val name: String? = null,
)

@Serializable
class ChapterDto(
    val slug: String,
    val numberLabel: String,
    val createdAt: String,
    val title: String? = null,
    val sourcePublishedAt: String? = null,
)

@Serializable
class GenreDto(
    val name: String,
    val slug: String,
)
