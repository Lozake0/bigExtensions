package eu.kanade.tachiyomi.extension.pt.kitsuneyako

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.util.Calendar

@Source
abstract class KitsuneYako : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2) { it.host == baseUrl.toHttpUrl().host }

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(SortFilter("popular")))

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(SortFilter("updated")))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/$MANGA_PATH/page/$page/".toHttpUrl().newBuilder()
            .apply {
                query.trim().takeIf(String::isNotEmpty)?.let { addQueryParameter("s", it) }
                filters.filterIsInstance<UrlFilter>().forEach { it.addToUrl(this) }
            }
            .build()
        val document = client.get(url).asJsoup()
        val mangas = document.select("article.inkra-catalog-card")
            .map(::toSManga)
            .filterNot { NOVEL_TITLE.containsMatchIn(it.title) }

        return MangasPage(mangas, document.selectFirst(".inkra-pagination a.next") != null)
    }

    private fun toSManga(element: Element) = SManga.create().apply {
        val link = element.selectFirst(".inkra-catalog-card__body h3 a")!!
        url = link.absUrl("href").toHttpUrl().pathSegments[1]
        title = link.text()
        thumbnail_url = element.selectFirst(".inkra-catalog-card__media img")?.absUrl("src")
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.firstOrNull() != MANGA_PATH) return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf(String::isNotEmpty) ?: return null

        return client.get("$baseUrl/$MANGA_PATH/$slug/").asJsoup().toSManga(slug).apply { initialized = true }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()

        return SMangaUpdate(
            manga = if (fetchDetails) document.toSManga(manga.url) else manga,
            chapters = if (fetchChapters) document.toSChapters() else chapters,
        )
    }

    private fun Document.toSManga(slug: String) = SManga.create().apply {
        url = slug
        title = selectFirst("h1.inkra-page-title")!!.text()
        thumbnail_url = selectFirst(".inkra-series-cover img")?.absUrl("src")
        author = techValue("Autor")
        artist = techValue("Artista")
        genre = (listOfNotNull(seriesType()) + select(".inkra-genre-tags a.inkra-genre-tag").map(Element::text))
            .distinctBy(String::lowercase)
            .joinToString()
        status = parseStatus(selectFirst(".inkra-series-pill--status")?.className())
        description = selectFirst(".inkra-series-synopsis")?.wholeText()?.trim()
    }

    private fun Document.seriesType(): String? = selectFirst(".inkra-series-pill--type")
        ?.text()
        ?.replaceFirstChar(Char::uppercase)
        ?.takeIf(String::isNotEmpty)

    private fun Document.techValue(label: String): String? = select(".inkra-series-tech__card")
        .firstOrNull { it.selectFirst("span")?.text()?.startsWith(label) == true }
        ?.selectFirst("strong")
        ?.text()
        ?.takeIf { it.isNotBlank() && it != "-" }

    private fun parseStatus(classes: String?): Int {
        val value = classes ?: return SManga.UNKNOWN

        return when {
            "status--ongoing" in value -> SManga.ONGOING
            "status--completed" in value -> SManga.COMPLETED
            "status--hiatus" in value -> SManga.ON_HIATUS
            "status--dropped" in value -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private fun Document.toSChapters(): List<SChapter> {
        if (seriesType().equals(NOVEL, ignoreCase = true)) return emptyList()

        return select("[data-chapter-list] article.inkra-chapter-item").map { element ->
            SChapter.create().apply {
                url = element.selectFirst("a.inkra-chapter-item__link")!!.absUrl("href").toHttpUrl().pathSegments.first(String::isNotEmpty)
                name = element.selectFirst(".inkra-chapter-item__label")!!.text()
                chapter_number = element.attr("data-number").toFloatOrNull() ?: -1f
                date_upload = parseRelativeDate(element.selectFirst(".inkra-chapter-item__date")?.text())
            }
        }
    }

    private fun parseRelativeDate(date: String?): Long {
        val match = date?.lowercase()?.let(RELATIVE_DATE::find) ?: return 0L
        val field = when (match.groupValues[2]) {
            "segundo" -> Calendar.SECOND
            "minuto" -> Calendar.MINUTE
            "hora" -> Calendar.HOUR_OF_DAY
            "dia" -> Calendar.DAY_OF_MONTH
            "semana" -> Calendar.WEEK_OF_YEAR
            "mês", "mes" -> Calendar.MONTH
            else -> Calendar.YEAR
        }

        return Calendar.getInstance().apply { add(field, -match.groupValues[1].toInt()) }.timeInMillis
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        if (document.selectFirst("[data-reader-type=$NOVEL]") != null) throw IOException("Capítulos em texto não são suportados")

        return document.select(".inkra-reader-pages figure.inkra-reader-page img")
            .mapIndexed { index, element -> Page(index, imageUrl = element.absUrl("src")) }
    }

    override val supportsFilterFetching: Boolean get() = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$baseUrl/$MANGA_PATH/")
        .asJsoup()
        .select("form.inkra-filter-form label.inkra-pill--genre")
        .mapNotNull { label ->
            val slug = label.selectFirst("input[name=genre]")?.attr("value")?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            GenreDto(label.selectFirst("span")?.text() ?: slug, slug)
        }
        .distinctBy(GenreDto::slug)
        .toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        listOfNotNull(
            SortFilter(),
            StatusFilter(),
            TypeFilter(),
            data?.parseAs<List<GenreDto>>()?.let(::GenreFilter),
        ),
    )

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/$MANGA_PATH/${manga.url}/"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/${chapter.url}/"

    companion object {
        private const val MANGA_PATH = "manga"
        private const val NOVEL = "novel"
        private val NOVEL_TITLE = Regex("""\(novel\)""", RegexOption.IGNORE_CASE)
        private val RELATIVE_DATE = Regex("""(\d+)\s+(segundo|minuto|hora|dia|semana|mês|mes|ano)""")
    }
}
