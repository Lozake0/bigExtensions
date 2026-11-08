package eu.kanade.tachiyomi.extension.pt.gomangas

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
class BookDto(
    val image: String? = null,
    val description: String? = null,
    private val genre: JsonElement? = null,
    private val author: JsonElement? = null,
) {
    val genres: List<String> get() = genre.texts()

    val authorName: String? get() = author.texts().joinToString().ifEmpty { null }

    private fun JsonElement?.texts(): List<String> = when (this) {
        is JsonArray -> flatMap { it.texts() }
        is JsonObject -> get("name").texts()
        is JsonPrimitive -> listOfNotNull(contentOrNull?.takeIf(String::isNotBlank))
        else -> emptyList()
    }
}

@Serializable
class ChapterListDto(
    val obraSlug: String,
    val chapters: List<List<String?>>,
)

@Serializable
class GenreDto(
    val name: String,
    val slug: String,
)
