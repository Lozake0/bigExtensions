package eu.kanade.tachiyomi.extension.pt.montetaiscanlator

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
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.format.DateTimeFormatter

@Source
abstract class MonteTaiScanlator : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3) { it.host == baseUrl.toHttpUrl().host }

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(SortFilter("views")))

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(SortFilter("latest")))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("page/$page/")
            .addQueryParameter("s", query.trim())
            .addQueryParameter("post_type", "wp-manga")
            .apply { filters.filterIsInstance<UrlFilter>().forEach { it.addToUrl(this) } }
            .build()
        val document = client.get(url).asJsoup()
        val mangas = document.select("div.c-tabs-item__content").map(::toSManga)

        return MangasPage(mangas, document.selectFirst("a.next.page-numbers") != null)
    }

    private fun toSManga(element: Element) = SManga.create().apply {
        val link = element.selectFirst(".post-title a")!!
        url = link.absUrl("href").toHttpUrl().pathSegments[1]
        title = link.text()
        thumbnail_url = element.selectFirst("img")?.imageUrl()
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
        title = selectFirst(".mtx-hero-title")!!.text()
        thumbnail_url = selectFirst(".mtx-cover img")?.imageUrl()
        author = sideValue("Autor")
        artist = sideValue("Artista")
        genre = (listOfNotNull(selectFirst(".mtx-pill-type")?.text()) + select(".mtx-chip").map(Element::text))
            .distinct()
            .joinToString()
        status = parseStatus(selectFirst(".mtx-pill-status")?.text())
        description = buildString {
            append(select(".mtx-synopsis .entry-content p").joinToString("\n\n", transform = Element::text))
            selectFirst(".mtx-hero-subtitle")?.text()?.takeIf(String::isNotEmpty)?.let {
                append("\n\nTítulos alternativos: ", it)
            }
        }.trim()
    }

    private fun Document.sideValue(label: String): String? = select(".mtx-side-item")
        .firstOrNull { it.selectFirst("small")?.text() == label }
        ?.selectFirst("strong")
        ?.text()
        ?.takeIf { it.isNotBlank() && it != "-" }

    private fun parseStatus(status: String?): Int {
        val value = status?.lowercase() ?: return SManga.UNKNOWN

        return when {
            "progresso" in value || "andamento" in value || "lançamento" in value -> SManga.ONGOING
            "complet" in value || "finaliz" in value || "conclu" in value -> SManga.COMPLETED
            "hiato" in value || "pausa" in value -> SManga.ON_HIATUS
            "cancel" in value -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private fun Document.toSChapters(): List<SChapter> = selectFirst("script.mtx-chapter-data")
        ?.data()
        ?.parseAs<List<ChapterDto>>()
        .orEmpty()
        .map { chapter ->
            SChapter.create().apply {
                url = chapter.url.toHttpUrl().pathSegments.filter(String::isNotEmpty).drop(1).joinToString("/")
                name = chapter.title
                chapter_number = CHAPTER_NUMBER.find(chapter.title)?.value?.replace(',', '.')?.toFloatOrNull() ?: -1f
                date_upload = DATE_FORMAT.tryParseDate(chapter.date)
            }
        }

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get(getChapterUrl(chapter))
        .asJsoup()
        .select(".reading-content img.wp-manga-chapter-img")
        .mapNotNull(Element::imageUrl)
        .mapIndexed { index, url -> Page(index, imageUrl = url) }

    override val supportsFilterFetching: Boolean get() = true

    override suspend fun fetchFilterData(): JsonElement {
        val document = client.get("$baseUrl/?s=&post_type=wp-manga").asJsoup()
        val labels = document.select("label[for]").associate { it.attr("for") to it.text() }
        val usedGenres = fetchUsedGenres()

        return document.select("input[name='genre[]']")
            .map { GenreDto(labels[it.id()] ?: it.attr("value"), it.attr("value")) }
            .filter { usedGenres.isEmpty() || it.slug in usedGenres }
            .toJsonElement()
    }

    private suspend fun fetchUsedGenres(): Set<String> = client.get("$baseUrl/wp-sitemap-taxonomies-wp-manga-genre-1.xml", ensureSuccess = false).use { response ->
        if (!response.isSuccessful) return emptySet()

        GENRE_SLUG.findAll(response.body.string()).map { it.groupValues[1] }.toSet()
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        listOfNotNull(
            SortFilter(),
            StatusFilter(),
            data?.parseAs<List<GenreDto>>()?.let(::GenreFilter),
            GenreModeFilter(),
            TextFilter("Autor", "author"),
            TextFilter("Artista", "artist"),
            NumberFilter("Ano de lançamento", "release"),
        ),
    )

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/$MANGA_PATH/${manga.url}/"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/$MANGA_PATH/${chapter.url}/"

    companion object {
        private const val MANGA_PATH = "manga"
        private val CHAPTER_NUMBER = Regex("""\d+(?:[.,]\d+)?""")
        private val GENRE_SLUG = Regex("""<loc>[^<]*/genero/([^/<]+)/?</loc>""")
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    }
}

private fun Element.imageUrl(): String? = sequenceOf("data-src", "data-lazy-src", "src")
    .firstOrNull { attr(it).isNotBlank() }
    ?.let(::absUrl)
