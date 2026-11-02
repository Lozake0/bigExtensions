package eu.kanade.tachiyomi.extension.pt.akaitomangas

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.extractNextJsRsc
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.toJsonString
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Element
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Instant

@Source
abstract class AkaitoMangas : KeiSource() {

    private val actionIds = ConcurrentHashMap<String, String>()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3) { it.host == baseUrl.toHttpUrl().host }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val result = callAction<RankPageDto>("getRankPageProjects", "/rank", page.toString())

        return MangasPage(result.data.map(ProjectDto::toSManga), result.hasMore)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val mangas = client.get(baseUrl).asJsoup()
            .select("section:has(h2:containsOwn(Últimas Atualizações)) div.grid > div > a.group[href]")
            .map(::toSManga)

        return MangasPage(mangas, false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) {
            val results = callAction<List<ProjectDto>>("searchProjects", "/", query.trim().toJsonString())

            return MangasPage(results.map(ProjectDto::toSManga), false)
        }

        val section = filters.firstInstanceOrNull<SectionFilter>()?.path ?: CATALOG_PATH
        val url = "$baseUrl/$section".toHttpUrl().newBuilder()
            .apply { filters.filterIsInstance<UrlFilter>().forEach { it.addToUrl(this) } }
            .addQueryParameter("page", page.toString())
            .build()
        val document = client.get(url).asJsoup()
        val mangas = document.select("li > a.group.block[href]")
            .filter { UUID.matchesAt(it.attr("href"), 1) }
            .map(::toSManga)
            .distinctBy(SManga::url)

        return MangasPage(mangas, document.selectFirst("nav[aria-label=Paginação] a:containsOwn(Próxima)") != null)
    }

    private fun toSManga(element: Element) = SManga.create().apply {
        val image = element.selectFirst("img")
        url = element.attr("href").trim('/')
        title = (element.selectFirst("h3")?.text() ?: image?.attr("alt").orEmpty()).trim()
        thumbnail_url = image?.absUrl("src")
    }

    private suspend inline fun <reified T> callAction(name: String, page: String, argument: String): T {
        val response = postAction(actionId(name, page), argument)
        val body = if (response.code == 404 && response.header("x-nextjs-action-not-found") != null) {
            response.close()
            actionIds.remove(name)
            postAction(actionId(name, page), argument).body.string()
        } else {
            response.body.string()
        }

        return body.lineSequence()
            .firstOrNull { it.startsWith(ACTION_RESULT_PREFIX) }
            ?.removePrefix(ACTION_RESULT_PREFIX)
            ?.parseAs<T>()
            ?: throw IOException("Resposta inesperada do site")
    }

    private suspend fun postAction(id: String, argument: String) = client.post(
        baseUrl,
        Headers.Builder().addAll(headers).set("Next-Action", id).set("Accept", "text/x-component").build(),
        "[$argument]".toRequestBody(ACTION_MEDIA_TYPE),
        ensureSuccess = false,
    )

    private suspend fun actionId(name: String, page: String): String = actionIds[name] ?: discoverActionId(name, page).also { actionIds[name] = it }

    private suspend fun discoverActionId(name: String, page: String): String {
        val pattern = Regex("""createServerReference\)\("([0-9a-f]+)",[^)]*?"$name"\)""")
        val scripts = client.get("$baseUrl$page").asJsoup()
            .select("script[src*=/_next/static/chunks/]")
            .map { it.absUrl("src") }

        for (script in scripts) {
            pattern.find(client.get(script).body.string())?.let { return it.groupValues[1] }
        }

        throw IOException("Não foi possível carregar a busca do site")
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val id = url.pathSegments.firstOrNull()?.takeIf(UUID::matches) ?: return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf(String::isNotEmpty) ?: return null
        val manga = SManga.create().apply { this.url = "$id/$slug" }

        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga.apply { initialized = true }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val page = fetchRsc(getMangaUrl(manga))
            .extractNextJsRsc<ProjectPageDto> { it is JsonObject && "project" in it && "groups" in it }
            ?: throw IOException("Obra não encontrada")

        return SMangaUpdate(
            manga = if (fetchDetails) page.project.toSManga(manga.url) else manga,
            chapters = if (fetchChapters) page.toSChapters(manga.url) else chapters,
        )
    }

    private fun ProjectPageDto.toSChapters(mangaUrl: String): List<SChapter> = groups
        .flatMap(GroupDto::chapters)
        .filter { it.accessType == FREE_ACCESS && !it.isUpcoming }
        .map { chapter ->
            val number = chapter.title.content
            SChapter.create().apply {
                url = "$mangaUrl/$CHAPTER_PATH/$number"
                name = "Capítulo $number"
                chapter_number = number.toFloatOrNull() ?: -1f
                date_upload = Instant.tryParse(chapter.createdAt)
            }
        }
        .sortedByDescending(SChapter::chapter_number)

    override suspend fun getPageList(chapter: SChapter): List<Page> = fetchRsc(getChapterUrl(chapter))
        .extractNextJsRsc<ReaderDto>()
        ?.pages
        .orEmpty()
        .sortedBy(PageDto::pageNumber)
        .mapIndexed { index, page -> Page(index, imageUrl = page.photo) }

    private suspend fun fetchRsc(url: String): String = client.get(url, Headers.Builder().addAll(headers).set("RSC", "1").build()).body.string()

    override val supportsFilterFetching: Boolean get() = true

    override suspend fun fetchFilterData(): JsonElement = fetchRsc("$baseUrl/$CATALOG_PATH")
        .extractNextJsRsc<GendersDto>()
        ?.availableGenders
        .orEmpty()
        .sortedByDescending { it.count?.mangas ?: 0 }
        .map(GenderDto::name)
        .distinct()
        .toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        listOfNotNull(
            SectionFilter(),
            StatusFilter(),
            data?.parseAs<List<String>>()?.takeIf(List<String>::isNotEmpty)?.let(::GenreFilter),
            TextFilter("Autor", "author"),
            TextFilter("Artista", "artist"),
            NumberFilter("Ano mínimo", "yearMin"),
            NumberFilter("Ano máximo", "yearMax"),
            NumberFilter("Capítulos mínimos", "chaptersMin"),
            NumberFilter("Capítulos máximos", "chaptersMax"),
        ),
    )

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/${chapter.url}"

    companion object {
        private const val CATALOG_PATH = "projetos"
        private const val CHAPTER_PATH = "capitulo"
        private const val FREE_ACCESS = "FREE"
        private const val ACTION_RESULT_PREFIX = "1:"
        private val ACTION_MEDIA_TYPE = "text/plain;charset=UTF-8".toMediaType()
        private val UUID = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")
    }
}
