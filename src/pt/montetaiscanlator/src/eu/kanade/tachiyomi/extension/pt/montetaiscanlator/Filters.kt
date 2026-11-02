package eu.kanade.tachiyomi.extension.pt.montetaiscanlator

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

class SortFilter(default: String = "") :
    SelectFilter(
        "Ordenar por",
        "m_orderby",
        SORT_OPTIONS,
        SORT_OPTIONS.indexOfFirst { it.second == default }.coerceAtLeast(0),
    )

private val SORT_OPTIONS = listOf(
    "Relevância" to "",
    "Mais vistos" to "views",
    "Atualizados" to "latest",
    "Em alta" to "trending",
    "Melhor avaliados" to "rating",
    "Lançamentos" to "new-manga",
    "A-Z" to "alphabet",
)

class GenreModeFilter :
    SelectFilter(
        "Combinar gêneros",
        "op",
        listOf(
            "Qualquer um" to "",
            "Todos" to "1",
        ),
    )

class TextFilter(name: String, private val parameter: String) :
    Filter.Text(name),
    UrlFilter {
    override fun addToUrl(builder: HttpUrl.Builder) {
        state.trim().takeIf(String::isNotEmpty)?.let { builder.addQueryParameter(parameter, it) }
    }
}

class CheckBoxOption(name: String, val value: String) : Filter.CheckBox(name)

open class CheckBoxGroup(
    name: String,
    private val parameter: String,
    options: List<Pair<String, String>>,
) : Filter.Group<CheckBoxOption>(name, options.map { CheckBoxOption(it.first, it.second) }),
    UrlFilter {
    override fun addToUrl(builder: HttpUrl.Builder) {
        state.filter { it.state }.forEach { builder.addQueryParameter(parameter, it.value) }
    }
}

class StatusFilter :
    CheckBoxGroup(
        "Status",
        "status[]",
        listOf(
            "Em andamento" to "on-going",
            "Completo" to "end",
            "Cancelado" to "canceled",
            "Em hiato" to "on-hold",
            "Em breve" to "upcoming",
        ),
    )

class GenreFilter(genres: List<GenreDto>) : CheckBoxGroup("Gêneros", "genre[]", genres.map { it.name to it.slug })
