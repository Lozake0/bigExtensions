package eu.kanade.tachiyomi.extension.pt.roxinha

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
import keiyoushi.utils.getLocalStorage
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException

@Source
abstract class Roxinha : KeiSource() {

    private val apiUrl get() = "$baseUrl/api"

    private val tokenMutex = Mutex()

    @Volatile
    private var token: String? = null

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(4)

    // The API only answers requests that look like they come from the site itself.
    override fun Headers.Builder.configureHeaders(): Headers.Builder {
        val userAgent = get("User-Agent").orEmpty()
        val version = CHROME_REGEX.find(userAgent)?.groupValues?.get(1) ?: DEFAULT_CHROME_VERSION

        return set("Sec-Fetch-Site", "same-origin")
            .set("Sec-Fetch-Mode", "cors")
            .set("Sec-Fetch-Dest", "empty")
            .set("sec-ch-ua", "\"Chromium\";v=\"$version\", \"Google Chrome\";v=\"$version\", \"Not_A Brand\";v=\"24\"")
            .set("sec-ch-ua-mobile", if ("Mobile" in userAgent) "?1" else "?0")
    }

    override fun getHomeUrl(): String = "$baseUrl/login"

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(SortFilter(SortFilter.POPULAR)))

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(SortFilter(SortFilter.LATEST)))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$apiUrl/manga/search/advanced".toHttpUrl().newBuilder()
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())
            .apply {
                query.trim().takeIf(String::isNotEmpty)?.let { addQueryParameter("q", it) }
                filters.filterIsInstance<UrlFilter>().forEach { it.addTo(this) }
            }
            .build()
        val result = client.get(url).parseAs<SearchResponseDto>()

        return MangasPage(result.mangas.map { it.toSManga(baseUrl) }, result.hasMore)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.firstOrNull() != MANGA_PATH) return null
        val id = url.pathSegments.getOrNull(1)?.toIntOrNull() ?: return null

        return client.get("$apiUrl/manga/$id").parseAs<MangaDto>().toSManga(baseUrl).apply { initialized = true }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val details = client.get("$apiUrl/manga/${manga.url.substringAfterLast('/')}").parseAs<MangaDto>()

        return SMangaUpdate(
            manga = if (fetchDetails) details.toSManga(baseUrl) else manga,
            chapters = if (fetchChapters) details.toSChapters() else chapters,
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = "$apiUrl/manga/chapter/${chapter.url.substringAfterLast('/')}"
        val details = authorized { auth ->
            val access = client.get("$chapterUrl/access", auth, ensureSuccess = false)
            if (!access.isSuccessful) return@authorized access
            val ticket = access.parseAs<TicketDto>().ticket

            client.get(chapterUrl, auth.newBuilder().set(ACCESS_HEADER, ticket).build(), ensureSuccess = false)
        }.parseAs<ChapterDetailsDto>()

        return details.pages.mapIndexed { index, path -> Page(index, imageUrl = absoluteUrl(path)) }
    }

    override val supportsFilterFetching: Boolean get() = true

    override suspend fun fetchFilterData(): JsonElement = coroutineScope {
        val genres = GENRE_MODES.map { mode -> async { client.get("$apiUrl/manga/filters/genres?mode=$mode").parseAs<GenresDto>().genres } }
        val types = async { client.get("$apiUrl/manga/filters/types").parseAs<TypesDto>().types }
        val years = async { client.get("$apiUrl/manga/filters/years").parseAs<YearsDto>().years }

        FilterDataDto(
            genres = genres.awaitAll().flatten().cleanGenres(),
            types = types.await().filterNot { it.equals(NOVEL_TYPE, ignoreCase = true) },
            years = years.await().sortedDescending(),
        ).toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.parseAs<FilterDataDto>()

        return FilterList(
            listOfNotNull(
                CatalogFilter(),
                SortFilter(),
                StatusFilter(),
                TypeFilter(filterData?.types?.takeIf(List<String>::isNotEmpty) ?: DEFAULT_TYPES),
                filterData?.years?.takeIf(List<Int>::isNotEmpty)?.let(::YearFilter),
                RatingFilter(),
                FlagFilter("Somente Yaoi", "yaoi"),
                FlagFilter("Somente Yuri", "yuri"),
                FlagFilter("Oficial Roxinha", "roxinhaOfficial"),
                filterData?.genres?.takeIf(List<String>::isNotEmpty)?.let(::GenreFilter),
            ),
        )
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    // Login is protected by Turnstile, so the session comes from the site opened in the WebView.
    private suspend fun authorized(request: suspend (Headers) -> Response): Response {
        val cached = token
        var response = request(authHeaders())
        if (response.code == 401 && cached != null) {
            response.close()
            response = request(authHeaders(refresh = true))
        }
        if (response.code == 401) token = null
        if (!response.isSuccessful) throw IOException(response.errorMessage())

        return response
    }

    private suspend fun authHeaders(refresh: Boolean = false): Headers {
        val current = tokenMutex.withLock {
            if (refresh || token == null) {
                token = getLocalStorage(baseUrl, TOKEN_KEY)?.trim()?.takeIf(String::isNotEmpty)
            }
            token
        }

        return headers.newBuilder()
            .apply { current?.let { set("Authorization", "Bearer $it") } }
            .build()
    }

    private fun absoluteUrl(path: String): String = when {
        path.startsWith("http") -> path
        path.startsWith("/") -> baseUrl + path
        else -> "$baseUrl/$path"
    }

    private fun Response.errorMessage(): String = use {
        val message = runCatching { it.parseAs<ErrorDto>().error }.getOrNull() ?: "Erro HTTP ${it.code}"
        if (it.code == 401) "$message. $LOGIN_HINT" else message
    }

    private fun List<String>.cleanGenres(): List<String> = map(String::trim)
        .filter { it.length > 1 && '?' !in it && it.any(Char::isLetter) }
        .distinctBy(String::lowercase)
        .sortedWith(String.CASE_INSENSITIVE_ORDER)

    companion object {
        private const val PAGE_SIZE = 24
        private const val NOVEL_TYPE = "Novel"
        private const val MANGA_PATH = "manga"
        private const val ACCESS_HEADER = "X-Chapter-Access"
        private const val TOKEN_KEY = "token"
        private const val DEFAULT_CHROME_VERSION = "131"
        private const val LOGIN_HINT = "Abra a Roxinha na WebView, faça login na sua conta e tente de novo"
        private val CHROME_REGEX = Regex("""Chrome/(\d+)""")
        private val GENRE_MODES = listOf("default", "hentai")
        private val DEFAULT_TYPES = listOf("Manga", "Manhua", "Manhwa", "Webtoon", "Hentai", "Pornhwa", "Yaoi", "Yuri", "Shoujo", "Oneshot")
    }
}
