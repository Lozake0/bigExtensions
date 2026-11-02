package eu.kanade.tachiyomi.extension.pt.gomangas

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
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Instant

@Source
abstract class GoMangas : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3) { it.host == baseUrl.toHttpUrl().host }

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(SortFilter("popular")))

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(SortFilter("recentes")))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder()
            .apply {
                if (query.isNotBlank()) {
                    addPathSegment("busca")
                    addQueryParameter("q", query.trim())
                } else {
                    addPathSegment("mangas")
                    filters.filterIsInstance<UrlFilter>().forEach { it.addToUrl(this) }
                }
            }
            .addQueryParameter("page", page.toString())
            .build()
        val document = client.get(url).asJsoup()
        val mangas = document.select("a.group.block[href^=/manga/]").map(::toSManga)

        return MangasPage(mangas, document.selectFirst("a[aria-label=Próxima página]") != null)
    }

    private fun toSManga(element: Element) = SManga.create().apply {
        url = element.absUrl("href").toHttpUrl().pathSegments[1]
        title = element.selectFirst("h3")!!.text()
        thumbnail_url = element.selectFirst("img")?.absUrl("src")?.let(::unwrapImage)
    }

    private fun unwrapImage(url: String): String = url.toHttpUrl().queryParameter("url") ?: url

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.firstOrNull() != MANGA_PATH) return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf(String::isNotEmpty) ?: return null

        return client.get("$baseUrl/$MANGA_PATH/$slug").asJsoup().toSManga(slug).apply { initialized = true }
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
        val book = select("script[type=application/ld+json]")
            .map { it.data().parseAs<BookDto>() }
            .firstOrNull { it.type == "Book" }
        val badges = select("div.mb-3 > span").map(Element::text)

        url = slug
        title = selectFirst("h1")!!.text()
        thumbnail_url = book?.image ?: selectFirst("meta[property=og:image]")?.attr("content")
        author = book?.author?.name ?: selectFirst("h1 + p span")?.text()
        description = book?.description
        genre = (badges.take(1) + book?.genre.orEmpty()).distinct().joinToString()
        status = badges.firstNotNullOfOrNull { STATUS[it] } ?: SManga.UNKNOWN
    }

    private fun Document.toSChapters(): List<SChapter> = extractNextJs<List<ChapterDto>>()
        .orEmpty()
        .groupBy(ChapterDto::numberLabel)
        .values
        .map { group -> group.firstOrNull { it.title != null } ?: group.first() }
        .map { chapter ->
            SChapter.create().apply {
                url = chapter.slug
                name = chapter.title ?: "Capítulo ${chapter.numberLabel}"
                chapter_number = chapter.numberLabel.toFloatOrNull() ?: -1f
                date_upload = Instant.tryParse(chapter.sourcePublishedAt ?: chapter.createdAt)
            }
        }

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get(getChapterUrl(chapter))
        .asJsoup()
        .select("div.gm-paywalled img")
        .mapIndexed { index, element -> Page(index, imageUrl = element.absUrl("src")) }

    override val supportsFilterFetching: Boolean get() = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$baseUrl/mangas")
        .asJsoup()
        .select("select:has(option:containsOwn(gêneros)) option")
        .mapNotNull { option ->
            val slug = option.attr("value").takeIf(String::isNotEmpty) ?: return@mapNotNull null
            GenreDto(option.text(), slug)
        }
        .toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        listOfNotNull(
            SortFilter(),
            TypeFilter(),
            data?.parseAs<List<GenreDto>>()?.let(::GenreFilter),
        ),
    )

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/$MANGA_PATH/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/ler/${chapter.url}"

    companion object {
        private const val MANGA_PATH = "manga"
        private val STATUS = mapOf(
            "Em andamento" to SManga.ONGOING,
            "Completo" to SManga.COMPLETED,
            "Hiato" to SManga.ON_HIATUS,
            "Cancelado" to SManga.CANCELLED,
        )
    }
}
