package eu.kanade.tachiyomi.extension.pt.roxinha

import eu.kanade.tachiyomi.source.model.Filter
import okhttp3.HttpUrl

interface UrlFilter {
    fun addTo(builder: HttpUrl.Builder)
}

open class SelectFilter(
    name: String,
    private val param: String,
    private val options: List<Pair<String, String>>,
) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()),
    UrlFilter {
    private val selected: String get() = options[state].second

    override fun addTo(builder: HttpUrl.Builder) {
        selected.takeIf(String::isNotEmpty)?.let { builder.addQueryParameter(param, it) }
    }
}

class CatalogFilter :
    SelectFilter(
        "Catálogo",
        "mode",
        listOf(
            "Todos" to "default",
            "Manhwa" to "manhwa",
            "Manhua" to "manhua",
            "Mangá" to "so-mangas",
            "Comics" to "comic",
            "Shoujo e Romance" to "shoujo",
            "Hentai" to "hentai",
        ),
    )

class StatusFilter :
    SelectFilter(
        "Status",
        "status",
        listOf(
            "Todos" to "",
            "Em andamento" to "ongoing",
            "Completo" to "completed",
            "Hiato" to "hiatus",
            "Cancelado" to "cancelled",
        ),
    )

class TypeFilter(types: List<String>) : SelectFilter("Tipo", "type", listOf("Todos" to "") + types.map { it to it })

class YearFilter(years: List<Int>) : SelectFilter("Ano", "year", listOf("Todos" to "") + years.map { "$it" to "$it" })

class RatingFilter : SelectFilter("Avaliação mínima", "rating_min", listOf("Qualquer" to "") + (5 downTo 1).map { "$it+ ★" to "$it" })

class FlagFilter(name: String, private val param: String) :
    Filter.CheckBox(name),
    UrlFilter {
    override fun addTo(builder: HttpUrl.Builder) {
        if (state) builder.addQueryParameter(param, "1")
    }
}

class Genre(name: String) : Filter.CheckBox(name)

class GenreFilter(genres: List<String>) :
    Filter.Group<Genre>("Gêneros", genres.map(::Genre)),
    UrlFilter {
    override fun addTo(builder: HttpUrl.Builder) {
        state.filter { it.state }.joinToString(",") { it.name }.takeIf(String::isNotEmpty)?.let { builder.addQueryParameter("genres", it) }
    }
}

class SortFilter(selection: Selection = POPULAR) :
    Filter.Sort("Ordenar por", OPTIONS.map { it.first }.toTypedArray(), selection),
    UrlFilter {
    override fun addTo(builder: HttpUrl.Builder) {
        val current = state ?: return
        builder.addQueryParameter("sort", OPTIONS[current.index].second)
        builder.addQueryParameter("order", if (current.ascending) "ASC" else "DESC")
    }

    companion object {
        private val OPTIONS = listOf(
            "Título" to "title",
            "Atualização" to "updatedAt",
            "Data de adição" to "createdAt",
            "Visualizações" to "views",
            "Avaliação" to "avgRating",
            "Favoritos" to "favoriteCount",
        )
        val POPULAR = Selection(3, false)
        val LATEST = Selection(1, false)
    }
}
