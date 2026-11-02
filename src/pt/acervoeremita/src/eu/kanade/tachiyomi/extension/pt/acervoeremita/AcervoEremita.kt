package eu.kanade.tachiyomi.extension.pt.acervoeremita

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
import keiyoushi.utils.extractNextJsRsc
import keiyoushi.utils.long
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import kotlin.time.Instant

@Source
abstract class AcervoEremita : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3) { it.host == baseUrl.toHttpUrl().host }
        .addInterceptor(::fallbackImage)

    private fun fallbackImage(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val fallback = request.url.fragment?.takeIf { request.url.host == STORAGE_HOST } ?: return chain.proceed(request)
        val response = chain.proceed(request)
        if (response.isSuccessful) return response

        response.close()
        return chain.proceed(request.newBuilder().url(baseUrl + fallback).build())
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get("$baseUrl/?page=$page").asJsoup()
        val mangas = document.select("section[data-component^=HomeGrid:Destaques] article[data-component^=HomeWorkCard:]")
            .filterNot { it.selectFirst("[data-component$=__genre]")?.text() == NOVEL_GENRE }
            .map(::toSManga)

        return MangasPage(mangas, document.selectFirst("a[data-component$=__desktop__next]") != null)
    }

    private fun toSManga(element: Element) = SManga.create().apply {
        val link = element.selectFirst("a[href^=/work/]")!!
        url = link.attr("href").substringAfter("/work/")
        title = link.attr("aria-label").trim()
        thumbnail_url = element.selectFirst("img")?.absUrl("src")
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(SortFilter(1)))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/explore".toHttpUrl().newBuilder()
            .apply {
                query.trim().takeIf(String::isNotEmpty)?.let { addQueryParameter("q", it) }
                filters.filterIsInstance<UrlFilter>().forEach { it.addToUrl(this) }
            }
            .addQueryParameter("page", page.toString())
            .build()
        val result = fetchRsc(url.toString()).extractNextJsRsc<ExploreDto>() ?: throw IOException("Resposta inesperada do site")
        val mangas = result.works.filterNot { it.genreName == NOVEL_GENRE }.map(WorkDto::toSManga)

        return MangasPage(mangas, result.pagination.hasNext)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.firstOrNull() != WORK_PATH) return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf(String::isNotEmpty) ?: return null

        return client.get("$baseUrl/$WORK_PATH/$slug").asJsoup().toSManga(slug).apply { initialized = true }
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
            chapters = if (fetchChapters) fetchChapters(manga.url, document.workId()) else chapters,
        )
    }

    private fun Document.toSManga(slug: String) = SManga.create().apply {
        url = slug
        title = selectFirst("h1[data-component$=__hero__title]")?.text() ?: throw IOException("Obra não encontrada")
        thumbnail_url = selectFirst("meta[property=og:image]")?.attr("content")
        description = selectFirst("p[data-component$=__hero__description]")?.text()
        genre = (
            listOfNotNull(selectFirst("[data-component$=__hero__stats__genre]")?.text()) +
                select("[data-component$=__hero__tags] span").map(Element::text)
            )
            .distinct()
            .joinToString()
    }

    private fun Document.workId(): Long = selectFirst("[data-component^=WorkScreen_]")
        ?.attr("data-component")
        ?.let(WORK_ID::find)
        ?.groupValues
        ?.get(1)
        ?.toLong()
        ?: throw IOException("Obra não encontrada")

    private suspend fun fetchChapters(slug: String, workId: Long): List<SChapter> {
        val chapters = mutableListOf<ChapterDto>()
        var cursor: String? = null

        do {
            val url = "$baseUrl/api/work/chapters".toHttpUrl().newBuilder()
                .addQueryParameter("workId", workId.toString())
                .addQueryParameter("limit", CHAPTER_PAGE_SIZE.toString())
                .apply { cursor?.let { addQueryParameter("cursor", it) } }
                .build()
            val page = client.get(url).parseAs<ChapterListDto>()
            chapters += page.chapters
            cursor = page.pagination.nextCursor?.takeIf { page.pagination.hasMore && it != cursor }
        } while (cursor != null)

        return chapters
            .filter { it.pageCount > 0 }
            .distinctBy(ChapterDto::chapterId)
            .sortedByDescending { it.chapterNumber.toFloatOrNull() ?: -1f }
            .map { chapter ->
                SChapter.create().apply {
                    url = "$slug/read?chapter=${chapter.chapterNumber}"
                    name = chapter.name
                    chapter_number = chapter.chapterNumber.toFloatOrNull() ?: -1f
                    date_upload = Instant.tryParse(chapter.releasedAt)
                    memo = buildJsonObject { put(MEMO_CHAPTER_ID, chapter.chapterId) }
                }
            }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val reader = fetchRsc("${getChapterUrl(chapter)}&page=1").extractNextJsRsc<ReaderDto>() ?: throw IOException("Capítulo não encontrado")
        val expected = chapter.memo[MEMO_CHAPTER_ID]?.long
        if (expected != null && expected != reader.chapter.chapterId) throw IOException("Capítulo não encontrado")

        return reader.pages
            .sortedBy(PageDto::pageNumber)
            .mapIndexed { index, page -> Page(index, imageUrl = "${page.storageUrl()}#${page.imageUrl}") }
    }

    private fun PageDto.storageUrl(): String = when {
        imageSrc.startsWith("/") || imageSrc.startsWith("manga_") -> "https://$STORAGE_HOST/wp-content/uploads/WP-manga/data/${imageSrc.trimStart('/')}"
        else -> "https://$STORAGE_HOST$imagePathReader"
    }

    private suspend fun fetchRsc(url: String): String = client.get(url, Headers.Builder().addAll(headers).set("RSC", "1").build()).body.string()

    override val supportsFilterFetching: Boolean get() = true

    override suspend fun fetchFilterData(): JsonElement {
        val data = fetchRsc("$baseUrl/explore").extractNextJsRsc<FilterDataDto>() ?: throw IOException("Filtros indisponíveis")

        return data.copy(genres = data.genres.filterNot { it.name == NOVEL_GENRE }).toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.parseAs<FilterDataDto>()

        return FilterList(
            listOfNotNull(
                SortFilter(),
                filterData?.genres?.takeIf(List<GenreDto>::isNotEmpty)?.let(::GenreFilter),
                filterData?.tags?.takeIf(List<TagDto>::isNotEmpty)?.let(::TagFilter),
            ),
        )
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/$WORK_PATH/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/$WORK_PATH/${chapter.url}"

    companion object {
        private const val WORK_PATH = "work"
        private const val NOVEL_GENRE = "Novel"
        private const val CHAPTER_PAGE_SIZE = 80
        private const val MEMO_CHAPTER_ID = "chapterId"
        private const val STORAGE_HOST = "cdn.verdinha.wtf"
        private val WORK_ID = Regex("""WorkScreen_(\d+)""")
    }
}
