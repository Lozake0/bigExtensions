package eu.kanade.tachiyomi.extension.pt.roxinha

import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
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
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

@Source
abstract class Roxinha :
    KeiSource(),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()

    private val apiUrl get() = "$baseUrl/api"

    @Volatile
    private var token: String? = null

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(4)
        .addInterceptor(::authIntercept)

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
        val access = client.get("$chapterUrl/access", ensureSuccess = false)
        if (!access.isSuccessful) throw IOException(access.errorMessage())
        val ticket = access.parseAs<TicketDto>().ticket
        val headers = headersBuilder().set(ACCESS_HEADER, ticket).build()
        val response = client.get(chapterUrl, headers, ensureSuccess = false)
        if (!response.isSuccessful) throw IOException(response.errorMessage())

        return response.parseAs<ChapterDetailsDto>().pages.mapIndexed { index, path -> Page(index, imageUrl = baseUrl + path) }
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

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = EMAIL_PREF
            title = "E-mail"
            summary = LOGIN_HINT
            dialogTitle = "E-mail"
            setOnPreferenceChangeListener { _, _ ->
                token = null
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PASSWORD_PREF
            title = "Senha"
            summary = LOGIN_HINT
            dialogTitle = "Senha"
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            setOnPreferenceChangeListener { _, _ ->
                token = null
                true
            }
        }.also(screen::addPreference)
    }

    private fun authIntercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.encodedPath.startsWith(API_PATH)) return chain.proceed(request)
        val current = login() ?: return chain.proceed(request)
        val response = chain.proceed(request.withToken(current))
        if (response.code != 401) return response

        response.close()
        token = null
        val renewed = login() ?: return chain.proceed(request)

        return chain.proceed(request.withToken(renewed))
    }

    private fun Request.withToken(value: String): Request = newBuilder().header("Authorization", "Bearer $value").build()

    @Synchronized
    private fun login(): String? {
        token?.let { return it }
        val email = preferences.getString(EMAIL_PREF, "").orEmpty().trim()
        val password = preferences.getString(PASSWORD_PREF, "").orEmpty()
        if (email.isEmpty() || password.isEmpty()) return null

        val body = LoginRequestDto(email, password).toJsonRequestBody()
        val response = network.client.newCall(POST("$apiUrl/auth/login", headers, body)).execute()
        if (!response.isSuccessful) throw IOException(response.errorMessage())

        return response.parseAs<LoginResponseDto>().token.also { token = it }
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
        private const val API_PATH = "/api/"
        private const val ACCESS_HEADER = "x-chapter-access"
        private const val EMAIL_PREF = "email"
        private const val PASSWORD_PREF = "password"
        private const val LOGIN_HINT = "Informe o e-mail e a senha da sua conta da Roxinha nas configurações da extensão para ler os capítulos"
        private val GENRE_MODES = listOf("default", "hentai")
        private val DEFAULT_TYPES = listOf("Manga", "Manhua", "Manhwa", "Webtoon", "Hentai", "Pornhwa", "Yaoi", "Yuri", "Shoujo", "Oneshot")
    }
}
