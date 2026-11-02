package eu.kanade.tachiyomi.extension.pt.pizzariascan

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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class PizzariaScan : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(1, 2.seconds) {
        it.host == baseUrl.toHttpUrl().host && !it.encodedPath.startsWith("/wp-content/")
    }

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(SortFilter("popular")))

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(SortFilter("update")))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/$CATALOG_PATH/page/$page/".toHttpUrl().newBuilder()
            .apply {
                query.trim().takeIf(String::isNotEmpty)?.let { addQueryParameter("title", it) }
                filters.filterIsInstance<UrlFilter>().forEach { it.addToUrl(this) }
            }
            .build()
        val document = client.get(url).asJsoup()
        val mangas = document.select("div.grid a[rel][href*=/manga/]").map(::toSManga).distinctBy(SManga::url)
        val pages = document.selectFirst("div.pagination > span")?.text()?.let(PAGINATION::find)?.groupValues

        return MangasPage(mangas, pages != null && pages[1].toInt() < pages[2].toInt())
    }

    private fun toSManga(element: Element) = SManga.create().apply {
        url = element.absUrl("href").toHttpUrl().pathSegments[1]
        title = element.attr("title").ifEmpty { element.text() }
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
            chapters = if (fetchChapters) fetchChapters(document) else chapters,
        )
    }

    private fun Document.toSManga(slug: String) = SManga.create().apply {
        val info = selectFirst("#manga-info") ?: this@toSManga

        url = slug
        title = info.selectFirst("h1")!!.text()
        thumbnail_url = selectFirst("section[itemtype*=CreativeWorkSeries] img[itemprop=image]")?.imageUrl()
        author = info.select("div.grid > div")
            .firstOrNull { it.selectFirst("strong")?.text()?.startsWith("Autor") == true }
            ?.selectFirst("p")
            ?.text()
            ?.takeIf { it.isNotBlank() && it != "-" }
        genre = info.select("a[itemprop=genre]").joinToString(transform = Element::text)
        status = parseStatus(info.selectFirst("div.flex.flex-wrap.gap-2 > span")?.text())
        description = buildString {
            info.selectFirst("div.text-base.leading-relaxed")?.wholeText()?.trim()?.let(::append)
            info.select("span.text-muted-foreground.text-base > span")
                .map { it.text().substringAfter(":").trim().trim(',').removeSurrounding("(", ")").trim() }
                .filter(String::isNotEmpty)
                .takeIf(List<String>::isNotEmpty)
                ?.let { append("\n\nTítulos alternativos: ", it.joinToString()) }
        }.trim()
    }

    private fun parseStatus(status: String?): Int {
        val value = status?.lowercase() ?: return SManga.UNKNOWN

        return when {
            "andamento" in value -> SManga.ONGOING
            "finalizado" in value || "complet" in value -> SManga.COMPLETED
            "hiato" in value -> SManga.ON_HIATUS
            "cancel" in value -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private suspend fun fetchChapters(document: Document): List<SChapter> {
        val dated = document.select("#chapter_list li a[href]").mapNotNull { element ->
            val path = element.absUrl("href").chapterPath() ?: return@mapNotNull null
            SChapter.create().apply {
                url = path
                name = element.selectFirst("span.text-xs")?.ownText()?.trim().orEmpty()
                chapter_number = parseNumber(name)
                date_upload = DATE_FORMAT.tryParseDate(element.selectFirst("span.text-\\[10px\\]")?.text())
            }
        }
        val newest = dated.firstOrNull() ?: return emptyList()
        val known = dated.associateBy(SChapter::url)
        val all = client.get(getChapterUrl(newest))
            .asJsoup()
            .select("select.select_chapter option[value]")
            .mapNotNull { option ->
                val url = option.attr("value").takeIf(String::isNotBlank)?.chapterPath() ?: return@mapNotNull null
                known[url] ?: SChapter.create().apply {
                    this.url = url
                    name = option.text().substringAfterLast(" - ").trim()
                    chapter_number = parseNumber(name)
                }
            }
            .distinctBy(SChapter::url)

        return all.ifEmpty { dated }
    }

    private fun String.chapterPath(): String? = toHttpUrlOrNull()?.pathSegments?.firstOrNull(String::isNotEmpty)

    private fun parseNumber(name: String): Float = CHAPTER_NUMBER.find(name)?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull() ?: -1f

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get(getChapterUrl(chapter))
        .asJsoup()
        .select("div.reader-area img")
        .mapNotNull(Element::imageUrl)
        .mapIndexed { index, url -> Page(index, imageUrl = url) }

    override val supportsFilterFetching: Boolean get() = true

    override suspend fun fetchFilterData(): JsonElement {
        val document = client.get("$baseUrl/$CATALOG_PATH/").asJsoup()
        val genres = document.select("label.tax_fil:has(input[name='genre[]'])")
            .mapNotNull { label ->
                val name = label.ownText().trim()
                val slug = label.selectFirst("input")!!.attr("value")
                OptionDto(name, slug).takeIf { name.isNotEmpty() && !name.all(Char::isDigit) }
            }
        val years = document.select("input[name='years[]']")
            .map { it.attr("value") }
            .filter(String::isNotEmpty)
            .sortedDescending()

        return FilterDataDto(genres, years).toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.parseAs<FilterDataDto>()

        return FilterList(
            listOfNotNull(
                SortFilter(),
                StatusFilter(),
                TypeFilter(),
                filterData?.years?.takeIf(List<String>::isNotEmpty)?.let(::YearFilter),
                filterData?.genres?.takeIf(List<OptionDto>::isNotEmpty)?.let(::GenreFilter),
            ),
        )
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/$MANGA_PATH/${manga.url}/"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/${chapter.url}/"

    companion object {
        private const val MANGA_PATH = "manga"
        private const val CATALOG_PATH = "todas-as-obras"
        private val PAGINATION = Regex("""(\d+)\D+(\d+)""")
        private val CHAPTER_NUMBER = Regex("""(?i)cap[ií]tulo\s*(\d+(?:[.,]\d+)?)""")
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("pt-BR"))
    }
}

private fun Element.imageUrl(): String? = sequenceOf("data-src", "src")
    .firstOrNull { attr(it).isNotBlank() }
    ?.let(::absUrl)
    ?: attr("srcset").substringBefore(" ").takeIf(String::isNotEmpty)
