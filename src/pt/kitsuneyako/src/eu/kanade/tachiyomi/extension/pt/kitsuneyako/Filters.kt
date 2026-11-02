package eu.kanade.tachiyomi.extension.pt.kitsuneyako

import eu.kanade.tachiyomi.source.model.Filter
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl

@Serializable
class GenreDto(
    val name: String,
    val slug: String,
)

interface UrlFilter {
    fun addToUrl(builder: HttpUrl.Builder)
}

open class SelectFilter(
    name: String,
    private val parameter: String,
    private val options: List<Pair<String, String>>,
    state: Int = 0,
) : Filter.Select<String>(name, options.map { it.first }.toTypedArray(), state),
    UrlFilter {
    override fun addToUrl(builder: HttpUrl.Builder) {
        options[state].second.takeIf(String::isNotEmpty)?.let { builder.addQueryParameter(parameter, it) }
    }
}

class SortFilter(default: String = "updated") :
    SelectFilter(
        "Ordenar por",
        "order",
        SORT_OPTIONS,
        SORT_OPTIONS.indexOfFirst { it.second == default }.coerceAtLeast(0),
    )

private val SORT_OPTIONS = listOf(
    "Atualizados" to "updated",
    "Populares" to "popular",
    "A-Z" to "title",
)

class StatusFilter :
    SelectFilter(
        "Status",
        "status",
        listOf(
            "Todos" to "",
            "Em andamento" to "Ongoing",
            "Completo" to "Completed",
            "Hiato" to "Hiatus",
            "Cancelado" to "Canceled",
        ),
    )

class TypeFilter :
    SelectFilter(
        "Tipo",
        "type",
        listOf(
            "Todos" to "",
            "Mangá" to "Manga",
            "Manhwa" to "Manhwa",
            "Manhua" to "Manhua",
            "Comic" to "Comic",
        ),
    )

class GenreFilter(genres: List<GenreDto>) :
    SelectFilter(
        "Gênero",
        "genre",
        listOf("Todos" to "") + genres.map { it.name to it.slug },
    )
