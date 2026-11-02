package eu.kanade.tachiyomi.extension.pt.pizzariascan

import eu.kanade.tachiyomi.source.model.Filter
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl

@Serializable
class OptionDto(
    val name: String,
    val value: String,
)

@Serializable
class FilterDataDto(
    val genres: List<OptionDto> = emptyList(),
    val years: List<String> = emptyList(),
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

class SortFilter(default: String = "title") :
    SelectFilter(
        "Ordenar por",
        "order",
        SORT_OPTIONS,
        SORT_OPTIONS.indexOfFirst { it.second == default }.coerceAtLeast(0),
    )

private val SORT_OPTIONS = listOf(
    "A-Z" to "title",
    "Z-A" to "titlereverse",
    "Atualizados" to "update",
    "Adicionados" to "latest",
    "Populares" to "popular",
)

class StatusFilter :
    SelectFilter(
        "Status",
        "status",
        listOf(
            "Todos" to "",
            "Em andamento" to "Publishing",
            "Finalizado" to "Finished",
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
            "Hentai" to "Hentai",
        ),
    )

class YearFilter(years: List<String>) : SelectFilter("Ano", "years[]", listOf("Todos" to "") + years.map { it to it })

class GenreCheckBox(name: String, val value: String) : Filter.CheckBox(name)

class GenreFilter(genres: List<OptionDto>) :
    Filter.Group<GenreCheckBox>("Gêneros", genres.map { GenreCheckBox(it.name, it.value) }),
    UrlFilter {
    override fun addToUrl(builder: HttpUrl.Builder) {
        state.filter { it.state }.forEach { builder.addQueryParameter("genre[]", it.value) }
    }
}
