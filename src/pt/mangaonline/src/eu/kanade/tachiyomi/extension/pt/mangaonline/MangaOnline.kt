package eu.kanade.tachiyomi.extension.pt.mangaonline

import eu.kanade.tachiyomi.source.model.Filter
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
import keiyoushi.utils.firstInstanceOrNull
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Calendar

@Source
abstract class MangaOnline : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2) { it.host == baseUrl.toHttpUrl().host }

    override suspend fun getPopularManga(page: Int): MangasPage = fetchCatalog(page, "popular")

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val mangas = client.get(baseUrl).asJsoup()
            .select("section.home-latest article.home-manga-card")
            .map(::toSManga)

        return MangasPage(mangas, false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return fetchCatalog(page, filters.firstInstanceOrNull<SortFilter>()?.value ?: "popular")

        val searchUrl = "$baseUrl/page/$page/".toHttpUrl().newBuilder()
            .addQueryParameter("s", query.trim())
            .build()
        val document = client.get(searchUrl).asJsoup()
        val mangas = document.select(".manga-results div.manga-card").map { card ->
            SManga.create().apply {
                url = card.selectFirst("a.manga-card-link")!!.absUrl("href").toHttpUrl().pathSegments[1]
                title = card.selectFirst("h3.manga-card-title")!!.text()
                thumbnail_url = card.selectFirst("img.manga-cover-img")?.coverUrl()
            }
        }

        return MangasPage(mangas, document.selectFirst("a.next.page-numbers") != null)
    }

    private suspend fun fetchCatalog(page: Int, order: String): MangasPage {
        val url = "$baseUrl/$MANGA_PATH/page/$page/".toHttpUrl().newBuilder()
            .addQueryParameter("ordem", order)
            .build()
        val document = client.get(url).asJsoup()
        val mangas = document.select(".manga-archive-grid article.home-manga-card").map(::toSManga)

        return MangasPage(mangas, document.selectFirst("a.next.page-numbers") != null)
    }

    private fun toSManga(element: Element) = SManga.create().apply {
        url = element.selectFirst("a.home-manga-cover")!!.absUrl("href").toHttpUrl().pathSegments[1]
        title = element.selectFirst("h3 > a")!!.text()
        thumbnail_url = element.selectFirst("img")?.coverUrl()
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
        title = selectFirst("h1.manga-title")!!.ownText().trim()
        thumbnail_url = selectFirst(".manga-cover img.manga-cover-image")?.coverUrl()
        description = selectFirst(".manga-synopsis .synopsis-content")?.wholeText()?.trim()
        genre = select(".manga-tags a").joinToString(transform = Element::text)
        status = parseStatus(selectFirst(".manga-meta-item .meta-value")?.text())
    }

    private fun parseStatus(status: String?): Int {
        val value = status?.lowercase() ?: return SManga.UNKNOWN

        return when {
            "andamento" in value -> SManga.ONGOING
            "complet" in value -> SManga.COMPLETED
            "hiato" in value -> SManga.ON_HIATUS
            "cancel" in value -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private fun Document.toSChapters(): List<SChapter> = select("ul.chapters-list li.chapter-item").map { element ->
        SChapter.create().apply {
            url = element.selectFirst("a.chapter-link")!!.absUrl("href").toHttpUrl().pathSegments[1]
            name = element.selectFirst(".chapter-number")!!.text()
            chapter_number = CHAPTER_NUMBER.find(name)?.value?.replace(',', '.')?.toFloatOrNull() ?: -1f
            date_upload = parseRelativeDate(element.selectFirst(".chapter-date")?.text())
        }
    }

    private fun parseRelativeDate(date: String?): Long {
        val match = date?.lowercase()?.let(RELATIVE_DATE::find) ?: return 0L
        val amount = match.groupValues[1].let { if (it.startsWith("u")) 1 else it.toInt() }
        val field = when (match.groupValues[2]) {
            "segundo" -> Calendar.SECOND
            "minuto", "min" -> Calendar.MINUTE
            "hora" -> Calendar.HOUR_OF_DAY
            "dia" -> Calendar.DAY_OF_MONTH
            "semana" -> Calendar.WEEK_OF_YEAR
            "mês", "mes", "meses" -> Calendar.MONTH
            else -> Calendar.YEAR
        }

        return Calendar.getInstance().apply { add(field, -amount) }.timeInMillis
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get(getChapterUrl(chapter))
        .asJsoup()
        .select(".chapter-images img.chapter-image")
        .mapIndexed { index, element -> Page(index, imageUrl = element.absUrl("src")) }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(SortFilter())

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/$MANGA_PATH/${manga.url}/"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/$CHAPTER_PATH/${chapter.url}/"

    private class SortFilter : Filter.Select<String>("Ordenar por", SORT_OPTIONS.map { it.first }.toTypedArray()) {
        val value get() = SORT_OPTIONS[state].second
    }

    companion object {
        private const val MANGA_PATH = "manga"
        private const val CHAPTER_PATH = "capitulo"
        private val SORT_OPTIONS = listOf("Populares" to "popular", "Atualizados" to "recentes")
        private val CHAPTER_NUMBER = Regex("""\d+(?:[.,]\d+)?""")
        private val RELATIVE_DATE = Regex("""(\d+|uma?)\s+(segundo|minuto|min|hora|dia|semana|mês|mes|meses|ano)""")
    }
}

private fun Element.coverUrl(): String? = absUrl("src").takeUnless { it.isEmpty() || "no-cover" in it || "placeholder" in it }
