package eu.kanade.tachiyomi.extension.pt.akaitomangas

import eu.kanade.tachiyomi.source.model.Filter
import okhttp3.HttpUrl

interface UrlFilter {
    fun addToUrl(builder: HttpUrl.Builder)
}

class SectionFilter :
    Filter.Select<String>(
        "Seção",
        SECTIONS.map { it.first }.toTypedArray(),
    ) {
    val path get() = SECTIONS[state].second
}

private val SECTIONS = listOf(
    "Catálogo" to "projetos",
    "Hentai" to "hentai",
)

class OptionCheckBox(name: String, val value: String) : Filter.CheckBox(name)

open class CheckBoxGroup(
    name: String,
    private val parameter: String,
    options: List<Pair<String, String>>,
) : Filter.Group<OptionCheckBox>(name, options.map { OptionCheckBox(it.first, it.second) }),
    UrlFilter {
    override fun addToUrl(builder: HttpUrl.Builder) {
        state.filter { it.state }
            .joinToString(",") { it.value }
            .takeIf(String::isNotEmpty)
            ?.let { builder.addQueryParameter(parameter, it) }
    }
}

class StatusFilter :
    CheckBoxGroup(
        "Status",
        "status",
        listOf(
            "Em andamento" to "ACTIVE",
            "Em dia" to "UP_TO_DATE",
            "Completo" to "FINISHED",
            "Hiato" to "HIATUS",
            "Dropado" to "DROPPED",
            "Em breve" to "COMING_SOON",
        ),
    )

class GenreFilter(genres: List<String>) : CheckBoxGroup("Gêneros", "genders", genres.map { it to it })

class TextFilter(name: String, private val parameter: String) :
    Filter.Text(name),
    UrlFilter {
    override fun addToUrl(builder: HttpUrl.Builder) {
        state.trim().takeIf(String::isNotEmpty)?.let { builder.addQueryParameter(parameter, it) }
    }
}

class NumberFilter(name: String, private val parameter: String) :
    Filter.Text(name),
    UrlFilter {
    override fun addToUrl(builder: HttpUrl.Builder) {
        state.trim().toIntOrNull()?.let { builder.addQueryParameter(parameter, it.toString()) }
    }
}
