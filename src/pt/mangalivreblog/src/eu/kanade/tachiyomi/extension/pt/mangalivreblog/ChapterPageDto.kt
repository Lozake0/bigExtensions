package eu.kanade.tachiyomi.extension.pt.mangalivreblog

import kotlinx.serialization.Serializable

/** One batch of the chapter list, as the "Carregar mais capítulos" button loads it. */
@Serializable
class ChapterPageDto(
    val list: String,
    val offset: Int,
    val total: Int,
)
