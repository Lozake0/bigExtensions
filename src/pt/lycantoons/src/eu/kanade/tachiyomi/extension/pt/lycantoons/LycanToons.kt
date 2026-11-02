package eu.kanade.tachiyomi.extension.pt.lycantoons

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.long
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.IOException

@Source
abstract class LycanToons : KeiSource() {

    private val browser = BrowserFetch()

    private val readerKeyLock = Mutex()

    private var readerKeys: Map<String, ByteArray> = emptyMap()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2)
        .addInterceptor(browser.imageInterceptor)

    // Cloudflare blocks non-browser clients that send the site as Referer, and challenges any
    // request without Accept-Language.
    override fun Headers.Builder.configureHeaders(): Headers.Builder = this
        .removeAll("Referer")
        .removeAll("Origin")
        .set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")

    override suspend fun getPopularManga(page: Int): MangasPage = fetchSeries(SeriesRequestDto(page, PAGE_SIZE, orderBy = "views"))

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchSeries(SeriesRequestDto(page, PAGE_SIZE, orderBy = "updated"))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = fetchSeries(
        SeriesRequestDto(
            page = page,
            limit = PAGE_SIZE,
            search = query.trim().takeIf(String::isNotEmpty),
            seriesType = filters.firstInstanceOrNull<TypeFilter>()?.selectedValue,
            status = filters.firstInstanceOrNull<StatusFilter>()?.selectedValue,
            tags = filters.firstInstanceOrNull<GenreFilter>()?.selectedValues,
            original = filters.firstInstanceOrNull<OriginalFilter>()?.state?.takeIf { it },
            orderBy = filters.firstInstanceOrNull<SortFilter>()?.selectedValue ?: "views",
        ),
    )

    private suspend fun fetchSeries(body: SeriesRequestDto): MangasPage {
        val result = client.post("$baseUrl/api/series", body.toJsonRequestBody()).parseAs<SeriesListDto>()

        return MangasPage(result.series.filterNot(SeriesDto::isNovel).map(SeriesDto::toSManga), result.hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "series") return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf(String::isNotEmpty) ?: return null

        return fetchDetails(slug)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val slug = manga.url.substringAfterLast('/')
        val details = if (fetchDetails) async { fetchDetails(slug, manga.title) } else null
        val chapterList = if (fetchChapters) async { fetchChapters(slug).map { it.toSChapter(slug) } } else null

        SMangaUpdate(details?.await() ?: manga, chapterList?.await() ?: chapters)
    }

    private suspend fun fetchDetails(slug: String, title: String? = null): SManga {
        val words = slug.split('-')
        val queries = listOfNotNull(title) + (words.size downTo 1).map { words.take(it).joinToString(" ") }

        for (query in queries.distinct()) {
            val body = SeriesRequestDto(page = 1, limit = PAGE_SIZE, search = query)
            val match = client.post("$baseUrl/api/series", body.toJsonRequestBody()).parseAs<SeriesListDto>()
                .series
                .firstOrNull { it.slug == slug }

            if (match != null) return match.toSManga()
        }

        throw IOException("Obra não encontrada")
    }

    // Entries saved before 1.6.2 keep the "/series/" prefix.
    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/${manga.url.substringAfterLast('/')}"

    // The site pages this endpoint with skip/take (max 100) and VIP early-access chapters can't be read yet.
    private suspend fun fetchChapters(slug: String): List<ChapterDto> {
        val chapters = LinkedHashMap<Long, ChapterDto>()
        var skip = 0

        while (true) {
            val page = client.get("$baseUrl/api/series/$slug/chapters?skip=$skip&take=$CHAPTER_PAGE_SIZE").parseAs<ChapterListDto>()
            val before = chapters.size
            page.chapters.forEach { chapters.putIfAbsent(it.id, it) }
            skip += page.chapters.size

            val exhausted = page.chapters.size < CHAPTER_PAGE_SIZE || page.total?.let { skip >= it } == true
            if (exhausted || chapters.size == before) break
        }

        val now = System.currentTimeMillis()
        return chapters.values.filterNot { it.isLocked(now) }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val id = chapter.memo["id"]?.long ?: findChapterId(chapter)
        val result = browser.text("$baseUrl/", "/api/chapters/$id/view-pages").parseAs<PagesDto>()
        val pages = when {
            result.enc != null -> decryptPages(result.enc, result.keyId ?: throw IOException("Chave de leitura ausente"))
            else -> result.pages.orEmpty()
        }

        return pages.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    private suspend fun decryptPages(enc: String, kid: String): List<String> = ReaderCipher.decrypt(enc, readerKey(kid)).parseAs()

    // Keys rotate hourly; the reader keeps a small window and refetches (bypassing the cache) when a kid is missing.
    private suspend fun readerKey(kid: String): ByteArray = readerKeyLock.withLock {
        readerKeys[kid]
            ?: refreshReaderKeys(reload = false)[kid]
            ?: refreshReaderKeys(reload = true)[kid]
            ?: throw IOException("Chave de leitura expirada")
    }

    private suspend fun refreshReaderKeys(reload: Boolean): Map<String, ByteArray> {
        val keys = browser.text("$baseUrl/", "/api/reader-key", reload).parseAs<ReaderKeysDto>().keys

        return keys.associate { it.id to ReaderCipher.unmaskKey(it.k) }.also { readerKeys = it }
    }

    private suspend fun findChapterId(chapter: SChapter): Long {
        val (slug, number) = chapter.url.split('/').takeLast(2)

        return fetchChapters(slug).firstOrNull { it.label == number }?.id ?: throw IOException("Capítulo não encontrado")
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
        TypeFilter(),
        StatusFilter(),
        OriginalFilter(),
        GenreFilter(),
    )

    companion object {
        private const val PAGE_SIZE = 24
        private const val CHAPTER_PAGE_SIZE = 100
    }
}
