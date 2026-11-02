package eu.kanade.tachiyomi.extension.pt.montetaiscanlator

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class ChapterDto(
    val url: String,
    val title: String,
    @SerialName("meta") val date: String? = null,
)

@Serializable
class GenreDto(
    val name: String,
    val slug: String,
)
