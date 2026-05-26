package eu.kanade.tachiyomi.extension.fr.comicstracker

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.injectLazy

class ComicsTracker : HttpSource() {

    override val name = "Comics Tracker"
    override val baseUrl = "https://comics-tracker.net"
    override val lang = "fr"
    override val supportsLatest = false

    private val apiUrl = "https://api.comics-tracker.net"
    private val imagesUrl = "https://images.comics-tracker.net"

    private val json: Json by injectLazy()

    // ======== PAGE POPULAIRE ========

    override fun popularMangaRequest(page: Int): Request = GET("$apiUrl/api/series?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val ids = json.parseToJsonElement(response.body.string()).jsonArray

        val mangas = ids.map { element ->
            val seriesId = element.jsonPrimitive.content
            SManga.create().apply {
                url = "/api/series/$seriesId/issues"
                title = seriesId
                    .replace(Regex("_\\d{4}$"), "")
                    .replace("_", " ")
                    .split(" ")
                    .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                thumbnail_url = "$apiUrl/api/issues/${seriesId}_1?w=400"
                initialized = false
            }
        }

        return MangasPage(mangas, ids.size >= 20)
    }

    // ======== RECHERCHE ========

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = client.newCall(GET("$apiUrl/api/series?page=$page", headers))
        .asObservableSuccess()
        .map { response ->
            val ids = json.parseToJsonElement(response.body.string()).jsonArray
            val queryLower = query.lowercase()

            val mangas = ids
                .map { it.jsonPrimitive.content }
                .filter { it.lowercase().contains(queryLower) }
                .map { seriesId ->
                    SManga.create().apply {
                        url = "/api/series/$seriesId/issues"
                        title = seriesId
                            .replace(Regex("_\\d{4}$"), "")
                            .replace("_", " ")
                            .replaceFirstChar { it.uppercase() }
                        thumbnail_url = "$apiUrl/api/issues/${seriesId}_1?w=400"
                        initialized = false
                    }
                }

            MangasPage(mangas, ids.size >= 20)
        }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$apiUrl/api/series?page=$page", headers)

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ======== DÉTAILS D'UN MANGA ========

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiUrl${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val jsonObj = json.parseToJsonElement(response.body.string()).jsonObject
        val editions = jsonObj["frenchEditions"]?.jsonArray ?: JsonArray(emptyList())

        return SManga.create().apply {
            title = if (editions.isNotEmpty()) {
                val firstEdition = editions[0].jsonObject
                val frTitle = firstEdition["french_title"]?.jsonPrimitive?.content ?: ""
                frTitle.replace(Regex("\\s+(Tome|Volume|Vol\\.|T\\.)\\s+\\d+.*", RegexOption.IGNORE_CASE), "").trim()
            } else {
                response.request.url.pathSegments
                    .getOrNull(2)
                    ?.replace("_", " ")
                    ?.replaceFirstChar { it.uppercase() }
                    ?: "Comics Tracker"
            }
            thumbnail_url = if (editions.isNotEmpty()) {
                val firstEdition = editions[0].jsonObject
                val imageId = firstEdition["id"]?.jsonPrimitive?.content
                if (imageId != null) "$apiUrl/api/issues/$imageId?w=400" else null
            } else {
                null
            }
            description = "Comics disponible sur Comics Tracker (VF)"
            status = SManga.UNKNOWN
            initialized = true
        }
    }

    // ======== LISTE DES CHAPITRES ========

    override fun chapterListRequest(manga: SManga): Request = GET("$apiUrl${manga.url}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val jsonObj = json.parseToJsonElement(response.body.string()).jsonObject
        val editions = jsonObj["frenchEditions"]?.jsonArray ?: return emptyList()

        return editions.mapIndexed { index, element ->
            val edition = element.jsonObject
            val link = edition["link"]?.jsonPrimitive?.content ?: ""
            val frTitle = edition["french_title"]?.jsonPrimitive?.content ?: "Tome ${index + 1}"
            val createdAt = edition["created_at"]?.jsonPrimitive?.content ?: ""

            SChapter.create().apply {
                url = "/reader/$link"
                name = frTitle
                chapter_number = (index + 1).toFloat()
                date_upload = parseDate(createdAt)
            }
        }.reversed()
    }

    // ======== PAGES D'UN CHAPITRE ========

    override fun pageListRequest(chapter: SChapter): Request {
        val link = chapter.url.removePrefix("/reader/")
        return GET("$imagesUrl/${link}P00001.jpg", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val requestUrl = response.request.url.toString()
        val basePath = requestUrl.substringBeforeLast("P00001.jpg")

        return (1..200).map { i ->
            val pageNum = i.toString().padStart(5, '0')
            Page(i - 1, "", "${basePath}P$pageNum.jpg")
        }
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        val link = chapter.url.removePrefix("/reader/")
        val basePath = "$imagesUrl/$link"

        val pages = (1..200).map { i ->
            val pageNum = i.toString().padStart(5, '0')
            Page(i - 1, "", "${basePath}P$pageNum.jpg")
        }
        return Observable.just(pages)
    }

    // ======== IMAGE URL ========

    override fun imageUrlParse(response: Response): String = response.request.url.toString()

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headers)

    // ======== LATEST (non supporté) ========

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used.")

    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException("Not used.")

    // ======== UTILITAIRES ========

    private fun parseDate(dateStr: String): Long = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.FRENCH)
            .parse(dateStr)?.time
    }.getOrNull() ?: 0L

    override fun getMangaUrl(manga: SManga): String {
        val seriesId = manga.url
            .removePrefix("/api/series/")
            .removeSuffix("/issues")
        return "$baseUrl/issue/${seriesId}_1"
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val link = chapter.url.removePrefix("/reader/")
        return "$baseUrl/issue/$link"
    }
}
