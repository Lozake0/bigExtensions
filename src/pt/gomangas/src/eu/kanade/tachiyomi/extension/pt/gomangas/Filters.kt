package eu.kanade.tachiyomi.extension.pt.gomangas

import eu.kanade.tachiyomi.source.model.Filter
import okhttp3.HttpUrl

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

class SortFilter(default: String = "recentes") :
    SelectFilter(
        "Ordenar por",
        "order",
        SORT_OPTIONS,
        SORT_OPTIONS.indexOfFirst { it.second == default }.coerceAtLeast(0),
    )

private val SORT_OPTIONS = listOf(
    "Recentes" to "recentes",
    "Populares" to "popular",
    "A-Z" to "az",
    "Mais capítulos" to "capitulos",
)

class TypeFilter :
    SelectFilter(
        "Tipo",
        "type",
        listOf(
            "Todos" to "",
            "Mangá" to "MANGA",
            "Manhwa" to "MANHWA",
            "Manhua" to "MANHUA",
            "Novel" to "NOVEL",
        ),
    )

class GenreFilter(genres: List<GenreDto>) :
    SelectFilter(
        "Gênero",
        "genre",
        listOf("Todos" to "") + genres.map { it.name to it.slug },
    )
