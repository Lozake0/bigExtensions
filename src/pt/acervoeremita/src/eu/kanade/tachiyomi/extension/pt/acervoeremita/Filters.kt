package eu.kanade.tachiyomi.extension.pt.acervoeremita

import eu.kanade.tachiyomi.source.model.Filter
import okhttp3.HttpUrl

interface UrlFilter {
    fun addToUrl(builder: HttpUrl.Builder)
}

class SortFilter(state: Int = 0) :
    Filter.Select<String>("Ordenar por", SORT_OPTIONS.map { it.name }.toTypedArray(), state),
    UrlFilter {
    override fun addToUrl(builder: HttpUrl.Builder) {
        val option = SORT_OPTIONS[state]
        builder.addQueryParameter("sort", option.sort)
        builder.addQueryParameter("dir", option.dir)
    }
}

private class SortOption(val name: String, val sort: String, val dir: String)

private val SORT_OPTIONS = listOf(
    SortOption("Relevância", "relevance", "desc"),
    SortOption("Lançamentos recentes", "release", "desc"),
    SortOption("Lançamentos antigos", "release", "asc"),
)

class GenreFilter(private val genres: List<GenreDto>) :
    Filter.Select<String>("Gênero", (listOf("Todos") + genres.map { it.name }).toTypedArray()),
    UrlFilter {
    override fun addToUrl(builder: HttpUrl.Builder) {
        genres.getOrNull(state - 1)?.let { builder.addQueryParameter("genre", it.slug) }
    }
}

class TagCheckBox(name: String) : Filter.CheckBox(name)

class TagFilter(tags: List<TagDto>) :
    Filter.Group<TagCheckBox>("Tags", tags.map { TagCheckBox(it.name) }),
    UrlFilter {
    override fun addToUrl(builder: HttpUrl.Builder) {
        state.filter { it.state }.forEach { builder.addQueryParameter("tag", it.name) }
    }
}
