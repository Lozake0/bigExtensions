package eu.kanade.tachiyomi.extension.pt.hipertoon

import eu.kanade.tachiyomi.network.GET
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Hipertoon : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor(::apiIntercept)
        .rateLimit(1, READER_INTERVAL) { READER_PROCEDURE in it.encodedPath }
        .rateLimit(3)

    override suspend fun getPopularManga(page: Int): MangasPage = search(page, "", FilterList(SortFilter(SortFilter.POPULAR)))

    override suspend fun getLatestUpdates(page: Int): MangasPage = search(page, "", FilterList(SortFilter(SortFilter.RECENT)))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = search(page, query, filters)

    private suspend fun search(page: Int, query: String, filters: FilterList): MangasPage {
        val input = buildJsonObject {
            put("q", query.trim())
            put("sort", filters.firstInstanceOrNull<SortFilter>()?.selected ?: SortFilter.DEFAULT)
            putJsonObject("filters") { filters.filterIsInstance<SearchFilter>().forEach { it.addTo(this) } }
            put("limit", PAGE_SIZE)
            put("offset", (page - 1) * PAGE_SIZE)
            put("maxRating", MAX_RATING)
        }
        val result = trpc<SearchResultDto>("search.query", input)

        return MangasPage(result.hits.map(MangaDto::toSManga), page * PAGE_SIZE < result.totalHits)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.firstOrNull() != MANGA_PATH) return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null

        return details(slug).toSManga()
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val details = details(manga.url.slug())
        val chapterList = if (fetchChapters) {
            val input = buildJsonObject {
                put("seriesId", details.id)
                put("sort", "best")
                put("page", 1)
                put("limit", CHAPTER_LIMIT)
            }
            trpc<List<ChapterDto>>("series.chapters", input)
                .filter(ChapterDto::isPublished)
                .map { it.toSChapter(details.slug) }
        } else {
            chapters
        }

        return SMangaUpdate(if (fetchDetails) details.toSManga() else manga, chapterList)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val input = buildJsonObject {
            put("seriesSlug", chapter.url.slug())
            put("chapterNumber", chapter.chapter_number)
            put("chapterId", chapter.url.substringAfterLast('/').toLong())
        }
        val pages = trpc<List<PageDto>>("reader.chapterPages", input)
        if (pages.isEmpty()) throw IOException("Nenhuma página encontrada. Abra o capítulo na WebView e tente novamente")

        return pages.sortedBy(PageDto::pageOrder).mapIndexed { index, page -> Page(index, imageUrl = page.imageUrl) }
    }

    override val supportsFilterFetching: Boolean get() = true

    override suspend fun fetchFilterData(): JsonElement = client.get(trpcUrl("search.genres", null))
        .parseAs<List<TrpcResponseDto<List<GenreDto>>>>()
        .first().result.data.json
        .map(GenreDto::name)
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
        .toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        listOfNotNull(
            SortFilter(),
            TypeFilter(),
            StatusFilter(),
            ContentRatingFilter(),
            YearFilter(),
            TextFilter("Autor", "author"),
            TextFilter("Artista", "artist"),
            data?.parseAs<List<String>>()?.takeIf(List<String>::isNotEmpty)?.let(::GenreFilter),
        ),
    )

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/$MANGA_PATH/${chapter.url.slug()}/${chapter.chapter_number.toString().removeSuffix(".0")}"

    private suspend fun details(slug: String): MangaDto = trpc("series.bySlugWithGenres", buildJsonObject { put("slug", slug) })

    private suspend inline fun <reified T> trpc(procedure: String, input: JsonObject): T = client.get(trpcUrl(procedure, input))
        .parseAs<List<TrpcResponseDto<T>>>()
        .first().result.data.json

    private fun trpcUrl(procedure: String, input: JsonObject?): HttpUrl {
        val batch = buildJsonObject {
            putJsonObject("0") {
                if (input == null) {
                    put("json", JsonNull)
                    putJsonObject("meta") { putJsonArray("values") { add("undefined") } }
                } else {
                    put("json", input)
                }
            }
        }

        return "$baseUrl/api/trpc/$procedure".toHttpUrl().newBuilder()
            .addQueryParameter("batch", "1")
            .addQueryParameter("input", batch.toString())
            .build()
    }

    private fun apiIntercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.encodedPath.startsWith(API_PATH)) return chain.proceed(request)
        var response = chain.proceed(request)
        if (response.code == 401) {
            response.close()
            refreshSession()
            response = chain.proceed(request)
        }
        repeat(RATE_LIMIT_RETRIES) {
            if (response.code != 429) return response
            response.close()
            response = chain.proceed(request)
        }

        return response
    }

    private fun refreshSession() {
        val documentHeaders = headersBuilder().set("Accept", DOCUMENT_ACCEPT).build()
        network.client.newCall(GET(baseUrl, documentHeaders)).execute().close()
    }

    private fun String.slug(): String = substringAfter("/$MANGA_PATH/").substringBefore('/')

    companion object {
        private const val PAGE_SIZE = 30
        private const val CHAPTER_LIMIT = 1000
        private const val MAX_RATING = "pornographic"
        private const val MANGA_PATH = "manga"
        private const val API_PATH = "/api/"
        private const val READER_PROCEDURE = "reader.chapterPages"
        private const val RATE_LIMIT_RETRIES = 2
        private val READER_INTERVAL = 21.seconds
        private const val DOCUMENT_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    }
}
