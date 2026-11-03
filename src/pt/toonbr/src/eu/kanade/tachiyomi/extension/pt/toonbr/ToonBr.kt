package eu.kanade.tachiyomi.extension.pt.toonbr

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

@Source
abstract class ToonBr : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3) { it.host == API_HOST }

    override suspend fun getPopularManga(page: Int): MangasPage = fetchRanking("popular")

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchRanking("latest")

    private suspend fun fetchRanking(kind: String): MangasPage {
        val mangas = client.get("$API_URL/manga/$kind?limit=$RANKING_LIMIT").parseAs<List<MangaDto>>()

        return MangasPage(mangas.map { it.toSManga(CDN_URL) }, false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$API_URL/manga".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .apply {
                query.trim().takeIf(String::isNotEmpty)?.let { addQueryParameter("search", it) }
                filters.firstInstanceOrNull<CategoryFilter>()?.selected?.let { addQueryParameter("categoryId", it) }
            }
            .build()
        val result = client.get(url).parseAs<MangaListDto>()

        return MangasPage(result.data.map { it.toSManga(CDN_URL) }, result.meta.page < result.meta.totalPages)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.firstOrNull() != MANGA_PATH) return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf(String::isNotEmpty) ?: return null

        return client.get("$API_URL/manga/$slug").parseAs<MangaDto>().toSManga(CDN_URL).apply { initialized = true }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val details = client.get("$API_URL/manga/${manga.url.substringAfterLast('/')}").parseAs<MangaDto>()

        return SMangaUpdate(
            manga = if (fetchDetails) details.toSManga(CDN_URL) else manga,
            chapters = if (fetchChapters) details.chapters.map(ChapterDto::toSChapter).sortedByDescending(SChapter::chapter_number) else chapters,
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get("$API_URL/chapter/${chapter.url.substringAfterLast('/')}")
        .parseAs<ChapterPagesDto>()
        .pages
        .sortedBy(PageDto::pageNumber)
        .mapNotNull { it.imageUrl }
        .mapIndexed { index, path -> Page(index, imageUrl = CDN_URL + path) }

    override val supportsFilterFetching: Boolean get() = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$API_URL/category")
        .parseAs<List<CategoryDto>>()
        .sortedBy(CategoryDto::name)
        .toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        listOfNotNull(
            data?.parseAs<List<CategoryDto>>()?.takeIf(List<CategoryDto>::isNotEmpty)?.let(::CategoryFilter),
        ),
    )

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/read/${chapter.url.substringAfterLast('/')}"

    companion object {
        private const val MANGA_PATH = "manga"
        private const val API_HOST = "api.toonbr.com"
        private const val API_URL = "https://$API_HOST/api"
        private const val CDN_URL = "https://cdn2.toonbr.com"
        private const val PAGE_SIZE = 24
        private const val RANKING_LIMIT = 150
    }
}
