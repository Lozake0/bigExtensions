package eu.kanade.tachiyomi.extension.pt.toonbr

import eu.kanade.tachiyomi.source.model.Filter

class CategoryFilter(private val categories: List<CategoryDto>) : Filter.Select<String>("Categoria", (listOf("Todas") + categories.map { it.name }).toTypedArray()) {
    val selected: String? get() = categories.getOrNull(state - 1)?.id
}
