package eu.kanade.tachiyomi.extension.pt.hipertoon

import eu.kanade.tachiyomi.source.model.Filter
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

interface SearchFilter {
    fun addTo(builder: JsonObjectBuilder)
}

open class SelectFilter(
    name: String,
    private val key: String,
    private val options: List<Pair<String, String>>,
) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()),
    SearchFilter {
    override fun addTo(builder: JsonObjectBuilder) {
        options[state].second.takeIf(String::isNotEmpty)?.let { builder.put(key, it) }
    }
}

class SortFilter(state: Int = 0) : Filter.Select<String>("Ordenar por", OPTIONS.map { it.first }.toTypedArray(), state) {
    val selected: String get() = OPTIONS[state].second

    companion object {
        private val OPTIONS = listOf(
            "Relevância" to "relevance",
            "Popularidade" to "popular",
            "Nota" to "score",
            "Atualizados recentemente" to "recent",
            "Mais recentes" to "newest",
            "Mais antigos" to "oldest",
            "A-Z" to "alphabetical",
        )
        const val DEFAULT = "relevance"
        const val POPULAR = 1
        const val RECENT = 3
    }
}

class TypeFilter :
    SelectFilter(
        "Tipo",
        "type",
        listOf(
            "Todos" to "",
            "Mangá" to "manga",
            "Manhwa" to "manhwa",
            "Manhua" to "manhua",
            "One-shot" to "one_shot",
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

class ContentRatingFilter :
    SelectFilter(
        "Classificação",
        "contentRating",
        listOf(
            "Todas" to "",
            "Livre" to "safe",
            "Sugestivo" to "suggestive",
            "Erótico" to "erotica",
            "Pornográfico" to "pornographic",
        ),
    )

class TextFilter(name: String, private val key: String) :
    Filter.Text(name),
    SearchFilter {
    override fun addTo(builder: JsonObjectBuilder) {
        state.trim().takeIf(String::isNotEmpty)?.let { builder.put(key, it) }
    }
}

class YearFilter :
    Filter.Text("Ano"),
    SearchFilter {
    override fun addTo(builder: JsonObjectBuilder) {
        state.trim().toIntOrNull()?.let { builder.put("year", it) }
    }
}

class Genre(name: String) : Filter.CheckBox(name)

class GenreFilter(genres: List<String>) :
    Filter.Group<Genre>("Gêneros", genres.map(::Genre)),
    SearchFilter {
    override fun addTo(builder: JsonObjectBuilder) {
        val selected = state.filter { it.state }.map { it.name }
        if (selected.isNotEmpty()) builder.putJsonArray("genres") { selected.forEach { add(it) } }
    }
}
