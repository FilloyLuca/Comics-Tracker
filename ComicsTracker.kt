package eu.kanade.tachiyomi.extension.fr.comicstracker

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import rx.Observable
import uy.kohesive.injekt.injectLazy

class ComicsTracker : HttpSource() {

    override val name = "Comics Tracker"
    override val baseUrl = "https://comics-tracker.net"
    override val lang = "fr"
    override val supportsLatest = true

    private val apiUrl = "https://api.comics-tracker.net"
    private val imagesUrl = "https://images.comics-tracker.net"

    private val json: Json by injectLazy()

    // ======== CACHE DES PÉRIODES ========

    data class Period(val id: String, val name: String, val publisher: String)

    private var periodsCache: List<Period>? = null

    private fun fetchPeriods(): List<Period> {
        periodsCache?.let { return it }

        return runCatching {
            val response = client.newCall(GET("$apiUrl/api/periods", headers)).execute()
            val periodsJson = json.parseToJsonElement(response.body.string()).jsonArray

            val periods = periodsJson.map { element ->
                val obj = element.jsonObject
                Period(
                    id = obj["id"]?.jsonPrimitive?.content ?: "",
                    name = obj["name"]?.jsonPrimitive?.content ?: "",
                    publisher = obj["publisher"]?.jsonPrimitive?.content ?: "independant",
                )
            }.filter { it.id.isNotBlank() }

            periodsCache = periods
            periods
        }.getOrElse { emptyList() }
    }

    // ======== FILTRES ========

    private class PublisherFilter :
        Filter.Select<String>(
            "Éditeur",
            arrayOf("Tous", "Marvel", "DC Comics", "Indépendants"),
        )

    private class PeriodFilter(periods: List<Period>) :
        Filter.Select<String>(
            "Période (optionnel)",
            arrayOf("Toutes", *periods.map { it.name }.toTypedArray()),
        ) {
        val periodList = periods
    }

    private class ContentTypeFilter :
        Filter.Select<String>(
            "Type de contenu",
            arrayOf("Comics", "Articles & Guides"),
        )

    override fun getFilterList(): FilterList {
        val periods = periodsCache

        return if (periods == null) {
            FilterList(
                Filter.Header("⚠️ Ouvrez la source pour charger les filtres"),
                Filter.Separator(),
                ContentTypeFilter(),
                Filter.Separator(),
                PublisherFilter(),
            )
        } else {
            FilterList(
                Filter.Header("Filtrer par type, éditeur ou période"),
                Filter.Separator(),
                ContentTypeFilter(),
                Filter.Separator(),
                PublisherFilter(),
                PeriodFilter(periods),
            )
        }
    }

    // ======== PAGE POPULAIRE ========

    override fun popularMangaRequest(page: Int): Request = GET("$apiUrl/api/series?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        if (periodsCache == null) {
            Thread { fetchPeriods() }.start()
        }

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

    // ======== NOUVEAUTÉS ========

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/recent", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val html = response.body.string()
        val document = Jsoup.parse(html)

        val nextDataJson = document.getElementById("__NEXT_DATA__")?.data()
            ?: return MangasPage(emptyList(), false)

        val pageProps = json.parseToJsonElement(nextDataJson)
            .jsonObject["props"]
            ?.jsonObject?.get("pageProps")
            ?.jsonObject ?: return MangasPage(emptyList(), false)

        val editions = pageProps["editions"]?.jsonArray ?: return MangasPage(emptyList(), false)

        val seen = mutableSetOf<String>()
        val mangas = editions.mapNotNull { element ->
            val edition = element.jsonObject
            val parentId = edition["parent_id"]?.jsonPrimitive?.content
            val editionId = edition["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val seriesId = parentId ?: editionId

            if (!seen.add(seriesId)) return@mapNotNull null

            val frTitle = edition["french_title"]?.jsonPrimitive?.content ?: editionId
            val imageId = edition["id"]?.jsonPrimitive?.content

            SManga.create().apply {
                url = "/api/series/$seriesId/issues"
                title = frTitle
                    .replace(Regex("\\s+(n°|Tome|Volume|Vol\\.|T\\.)\\s*\\d+.*", RegexOption.IGNORE_CASE), "")
                    .trim()
                thumbnail_url = if (imageId != null) "$apiUrl/api/issues/$imageId?w=400" else null
                initialized = false
            }
        }

        return MangasPage(mangas, false)
    }

    // ======== RECHERCHE ET FILTRES ========

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        val periods = fetchPeriods()

        var publisherIndex = 0
        var periodIndex = 0
        var contentTypeIndex = 0
        var periodFilter: PeriodFilter? = null

        filters.forEach { filter ->
            when (filter) {
                is PublisherFilter -> publisherIndex = filter.state
                is PeriodFilter -> {
                    periodIndex = filter.state
                    periodFilter = filter
                }
                is ContentTypeFilter -> contentTypeIndex = filter.state
                else -> {}
            }
        }

        // Filtre Articles & Guides
        if (contentTypeIndex == 1) {
            return fetchArticles(query)
        }

        // Filtre par période spécifique
        if (periodIndex > 0) {
            val selectedPeriod = periodFilter?.periodList?.getOrNull(periodIndex - 1)
                ?: return Observable.just(MangasPage(emptyList(), false))
            return fetchByPeriod(selectedPeriod.id, query)
        }

        // Filtre par éditeur
        if (publisherIndex > 0) {
            val publisherKey = when (publisherIndex) {
                1 -> "marvel"
                2 -> "dc"
                else -> "independant"
            }
            val filteredPeriods = periods.filter { it.publisher == publisherKey }
            return fetchByPublisher(filteredPeriods, query)
        }

        // Recherche textuelle récursive
        if (query.isNotBlank()) {
            val queryLower = query.lowercase().trim()

            // Recherche dans /api/series ET /api/french-editions en parallèle
            val seriesSearch = client.newCall(GET("$apiUrl/api/series?page=1", headers))
                .asObservableSuccess()
                .flatMap { response ->
                    val ids = json.parseToJsonElement(response.body.string()).jsonArray
                    val matched = ids
                        .map { it.jsonPrimitive.content }
                        .filter {
                            it.lowercase().replace("_", " ").contains(queryLower) &&
                                !it.contains(".") // ← exclure les numéros spéciaux (.deaths, .mu, etc.)
                        }
                        .map { seriesId ->
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
                    Observable.just(MangasPage(matched, false))
                }

            val editionsSearch = run {
                val requests = periods.map { period ->
                    client.newCall(GET("$apiUrl/api/french-editions?periodName=${period.id}", headers))
                        .asObservableSuccess()
                }
                Observable.merge(requests)
                    .toList()
                    .map { responses ->
                        val seen = mutableSetOf<String>()
                        val mangas = mutableListOf<SManga>()
                        responses.forEach { response ->
                            val editions = json.parseToJsonElement(response.body.string()).jsonArray
                            editions.forEach { element ->
                                val edition = element.jsonObject
                                val editionId = edition["id"]?.jsonPrimitive?.content ?: return@forEach
                                val frTitle = edition["french_title"]?.jsonPrimitive?.content ?: editionId
                                if (!frTitle.lowercase().contains(queryLower)) return@forEach
                                val link = edition["link"]?.jsonPrimitive?.content ?: ""
                                val sourceType = edition["source_type"]?.jsonPrimitive?.content ?: ""
                                val runId = if (sourceType == "run") {
                                    link.split("/runs/").getOrNull(1)?.split("/")?.firstOrNull()
                                        ?.lowercase()?.replace(" ", "_") ?: ""
                                } else {
                                    ""
                                }
                                val groupId = if (sourceType == "run") runId else editionId.replace(Regex("_t\\d+$|_v\\d+$|_n\\d+$|_vol\\d+$"), "")
                                if (!seen.add(groupId)) return@forEach
                                mangas.add(
                                    SManga.create().apply {
                                        url = if (sourceType == "run") "/run/$runId" else "/reader/$link"
                                        title = frTitle
                                            .replace(Regex("\\s+(Tome|Volume|Vol\\.|T\\.|n°)\\s*\\d+.*", RegexOption.IGNORE_CASE), "")
                                            .trim()
                                        thumbnail_url = "$apiUrl/api/issues/$editionId?w=400"
                                        initialized = false
                                    },
                                )
                            }
                        }
                        MangasPage(mangas.sortedBy { it.title }, false)
                    }
            }

            return Observable.zip(seriesSearch, editionsSearch) { fromSeries, fromEditions ->
                val seen = mutableSetOf<String>()
                val combined = (fromSeries.mangas + fromEditions.mangas).filter { seen.add(it.url) }
                MangasPage(combined.sortedBy { it.title }, false)
            }
        }

        // Aucun filtre → liste populaire
        return client.newCall(popularMangaRequest(page))
            .asObservableSuccess()
            .map { popularMangaParse(it) }
    }

    // ======== ARTICLES & GUIDES ========

    private fun fetchArticles(query: String): Observable<MangasPage> {
        return client.newCall(GET("$baseUrl/articles", headers))
            .asObservableSuccess()
            .map { response ->
                val html = response.body.string()
                val document = Jsoup.parse(html)

                val nextDataJson = document.getElementById("__NEXT_DATA__")?.data()
                    ?: return@map MangasPage(emptyList(), false)

                val pageProps = json.parseToJsonElement(nextDataJson)
                    .jsonObject["props"]
                    ?.jsonObject?.get("pageProps")
                    ?.jsonObject ?: return@map MangasPage(emptyList(), false)

                val articles = pageProps["articles"]?.jsonArray ?: return@map MangasPage(emptyList(), false)
                val queryLower = query.lowercase().trim()

                val mangas = articles.mapNotNull { element ->
                    val article = element.jsonObject
                    val slug = article["slug"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val title = article["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val content = article["content"]?.jsonPrimitive?.content ?: ""

                    if (queryLower.isNotBlank() && !title.lowercase().contains(queryLower)) return@mapNotNull null

                    // Extrait les 200 premiers caractères du contenu comme description
                    val preview = content
                        .replace(Regex("\\[.*?\\]\\(.*?\\)"), "") // Enlever les liens Markdown
                        .replace(Regex("#{1,6}\\s"), "") // Enlever les titres Markdown
                        .replace(Regex("\\*{1,2}(.*?)\\*{1,2}"), "$1") // Enlever le gras/italique
                        .trim()
                        .take(200)
                        .let { if (it.length == 200) "$it..." else it }

                    SManga.create().apply {
                        url = "/article/$slug"
                        this.title = "📖 $title"
                        thumbnail_url = "https://raw.githubusercontent.com/FilloyLuca/Comics-Tracker/repo/icon/eu.kanade.tachiyomi.extension.fr.comicstracker.png"
                        description = preview
                        status = SManga.COMPLETED
                        initialized = true
                    }
                }

                MangasPage(mangas, false)
            }
    }

    private fun fetchByPeriod(periodId: String, query: String): Observable<MangasPage> {
        return client.newCall(GET("$apiUrl/api/french-editions?periodName=$periodId", headers))
            .asObservableSuccess()
            .map { response ->
                val editions = json.parseToJsonElement(response.body.string()).jsonArray
                val queryLower = query.lowercase().trim()

                val seen = mutableSetOf<String>()
                val mangas = editions.mapNotNull { element ->
                    val edition = element.jsonObject
                    val editionId = edition["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val frTitle = edition["french_title"]?.jsonPrimitive?.content ?: editionId
                    val link = edition["link"]?.jsonPrimitive?.content ?: ""
                    val sourceType = edition["source_type"]?.jsonPrimitive?.content ?: ""
                    val runId = if (sourceType == "run") {
                        link.split("/runs/").getOrNull(1)?.split("/")?.firstOrNull()
                            ?.lowercase()?.replace(" ", "_") ?: ""
                    } else {
                        ""
                    }
                    val groupId = if (sourceType == "run") runId else editionId.replace(Regex("_t\\d+$|_v\\d+$|_n\\d+$|_vol\\d+$"), "")

                    if (!seen.add(groupId)) return@mapNotNull null
                    if (queryLower.isNotBlank() && !frTitle.lowercase().contains(queryLower)) return@mapNotNull null

                    SManga.create().apply {
                        url = if (sourceType == "run") "/run/$runId" else "/reader/$link"
                        title = frTitle
                            .replace(Regex("\\s+(Tome|Volume|Vol\\.|T\\.|n°)\\s*\\d+.*", RegexOption.IGNORE_CASE), "")
                            .trim()
                        thumbnail_url = "$apiUrl/api/issues/$editionId?w=400"
                        initialized = false
                    }
                }

                MangasPage(mangas, false)
            }
    }

    private fun fetchByPublisher(periods: List<Period>, query: String): Observable<MangasPage> {
        if (periods.isEmpty()) return Observable.just(MangasPage(emptyList(), false))

        val requests = periods.map { period ->
            client.newCall(GET("$apiUrl/api/french-editions?periodName=${period.id}", headers))
                .asObservableSuccess()
        }

        return Observable.merge(requests)
            .toList()
            .map { responses ->
                val queryLower = query.lowercase().trim()
                val seen = mutableSetOf<String>()
                val mangas = mutableListOf<SManga>()

                responses.forEach { response ->
                    val editions = json.parseToJsonElement(response.body.string()).jsonArray
                    editions.forEach { element ->
                        val edition = element.jsonObject
                        val editionId = edition["id"]?.jsonPrimitive?.content ?: return@forEach
                        val frTitle = edition["french_title"]?.jsonPrimitive?.content ?: editionId
                        val link = edition["link"]?.jsonPrimitive?.content ?: ""
                        val sourceType = edition["source_type"]?.jsonPrimitive?.content ?: ""
                        val runId = if (sourceType == "run") {
                            link.split("/runs/").getOrNull(1)?.split("/")?.firstOrNull()
                                ?.lowercase()?.replace(" ", "_") ?: ""
                        } else {
                            ""
                        }
                        val groupId = if (sourceType == "run") runId else editionId.replace(Regex("_t\\d+$|_v\\d+$|_n\\d+$|_vol\\d+$"), "")

                        if (!seen.add(groupId)) return@forEach
                        if (queryLower.isNotBlank() && !frTitle.lowercase().contains(queryLower)) return@forEach

                        mangas.add(
                            SManga.create().apply {
                                url = if (sourceType == "run") "/run/$runId" else "/reader/$link"
                                title = frTitle
                                    .replace(Regex("\\s+(Tome|Volume|Vol\\.|T\\.|n°)\\s*\\d+.*", RegexOption.IGNORE_CASE), "")
                                    .trim()
                                thumbnail_url = "$apiUrl/api/issues/$editionId?w=400"
                                initialized = false
                            },
                        )
                    }
                }

                MangasPage(mangas.sortedBy { it.title }, false)
            }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$apiUrl/api/series?page=$page", headers)

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ======== DÉTAILS D'UN MANGA ========

    override fun mangaDetailsRequest(manga: SManga): Request = when {
        manga.url.startsWith("/article/") -> GET("$baseUrl/articles/${manga.url.removePrefix("/article/")}", headers)
        manga.url.startsWith("/run/") -> GET("$apiUrl/api/runs/${manga.url.removePrefix("/run/")}", headers)
        manga.url.startsWith("/reader/") -> {
            val parts = manga.url.removePrefix("/reader/").trimEnd('/').split("/")
            val seriesId = parts.lastOrNull()?.lowercase() ?: ""
            GET("$apiUrl/api/series/$seriesId/issues", headers)
        }
        else -> GET("$apiUrl${manga.url}", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        // Si c'est un article → retourner directement sans parser
        if (response.request.url.toString().contains("/articles/")) {
            return SManga.create().apply {
                description = "📖 Cliquez sur 'Ouvrir dans le navigateur' pour lire cet article sur comics-tracker.net"
                status = SManga.COMPLETED
                initialized = true
            }
        }

        val jsonObj = runCatching {
            json.parseToJsonElement(response.body.string()).jsonObject
        }.getOrNull()

        val editions = if (jsonObj?.containsKey("sections") == true) {
            jsonObj["sections"]!!.jsonArray
                .flatMap { it.jsonObject["frenchEditions"]?.jsonArray?.toList() ?: emptyList() }
        } else {
            jsonObj?.get("frenchEditions")?.jsonArray?.toList() ?: emptyList()
        }
        val hasVF = editions.isNotEmpty()

        return SManga.create().apply {
            title = if (hasVF) {
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
            thumbnail_url = if (hasVF) {
                val firstEdition = editions[0].jsonObject
                val imageId = firstEdition["id"]?.jsonPrimitive?.content
                if (imageId != null) "$apiUrl/api/issues/$imageId?w=400" else null
            } else {
                null
            }
            description = if (hasVF) {
                "Comics disponible sur Comics Tracker (VF)"
            } else {
                "❌ Ce comic n'est pas disponible en version française sur Comics Tracker.\n\nSeules les éditions VF sont lisibles via cette extension."
            }
            status = if (hasVF) SManga.UNKNOWN else SManga.LICENSED
            initialized = true
        }
    }

    // ======== LISTE DES CHAPITRES ========

    override fun chapterListRequest(manga: SManga): Request = when {
        manga.url.startsWith("/article/") -> GET("$baseUrl/articles/${manga.url.removePrefix("/article/")}", headers)
        manga.url.startsWith("/run/") -> GET("$apiUrl/api/runs/${manga.url.removePrefix("/run/")}", headers)
        manga.url.startsWith("/reader/") -> {
            val parts = manga.url.removePrefix("/reader/").trimEnd('/').split("/")
            val seriesId = parts.lastOrNull()?.lowercase() ?: ""
            GET("$apiUrl/api/series/$seriesId/issues", headers)
        }
        else -> GET("$apiUrl${manga.url}", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        // Articles → pas de chapitres, juste un lien
        if (response.request.url.toString().contains("/articles/")) {
            val slug = response.request.url.pathSegments.last()
            return listOf(
                SChapter.create().apply {
                    url = "/article/$slug"
                    name = "Lire l'article"
                    chapter_number = 1f
                },
            )
        }

        val jsonObj = runCatching {
            json.parseToJsonElement(response.body.string()).jsonObject
        }.getOrNull()

        val allEditions = if (jsonObj?.containsKey("sections") == true) {
            jsonObj["sections"]!!.jsonArray
                .flatMap { it.jsonObject["frenchEditions"]?.jsonArray?.toList() ?: emptyList() }
        } else {
            jsonObj?.get("frenchEditions")?.jsonArray?.toList() ?: emptyList()
        }

        if (allEditions.isEmpty()) return emptyList()

        // Pour les séries classiques, filtrer les recueils multi-séries
        // en comparant le dossier parent du link avec l'ID de la série
        val requestSeriesId = response.request.url.pathSegments
            .dropLastWhile { it == "issues" }
            .lastOrNull() ?: ""

        val editionsList = if (requestSeriesId.isNotBlank() && !response.request.url.toString().contains("/api/runs/")) {
            val seriesWord = requestSeriesId
                .replace(Regex("_\\d{4}$"), "")
                .replace("_", " ")
                .replace("-", " ")
                .lowercase()

            allEditions.filter { element ->
                val link = element.jsonObject["link"]?.jsonPrimitive?.content ?: ""
                val folderName = link.split("/").getOrNull(link.split("/").size - 2)
                    ?.lowercase()
                    ?.replace("_", " ")
                    ?.replace("-", " ") ?: ""
                folderName.contains(seriesWord)
            }.ifEmpty { allEditions }
        } else {
            allEditions
        }

        return editionsList.mapIndexed { index, element ->
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
        // Article → renvoie vers la page web
        if (chapter.url.startsWith("/article/")) {
            val slug = chapter.url.removePrefix("/article/")
            return GET("$baseUrl/articles/$slug", headers)
        }

        val link = chapter.url.removePrefix("/reader/")
        val encodedPrefix = link
            .replace(" ", "%20")
            .replace("[", "%5B")
            .replace("]", "%5D")
            .replace(":", "%3A")
        return GET("$apiUrl/api/r2/list?prefix=$encodedPrefix", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        // Article → une seule "page" avec l'URL de l'article
        if (response.request.url.toString().contains("/articles/")) {
            return listOf(Page(0, response.request.url.toString(), null))
        }

        val files = json.parseToJsonElement(response.body.string()).jsonArray

        return files
            .map { it.jsonPrimitive.content }
            .filter { path ->
                path.endsWith(".jpg", ignoreCase = true) ||
                    path.endsWith(".jpeg", ignoreCase = true) ||
                    path.endsWith(".png", ignoreCase = true) ||
                    path.endsWith(".webp", ignoreCase = true) ||
                    path.endsWith(".gif", ignoreCase = true)
            }
            .sorted()
            .mapIndexed { index, path ->
                Page(index, "", "$imagesUrl/${path.replace(" ", "%20").replace("[", "%5B").replace("]", "%5D")}")
            }
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        if (chapter.url.startsWith("/article/")) {
            val slug = chapter.url.removePrefix("/article/")
            return Observable.just(
                listOf(Page(0, "$baseUrl/articles/$slug", null)),
            )
        }

        val link = chapter.url.removePrefix("/reader/")
        val encodedPrefix = link
            .replace(" ", "%20")
            .replace("[", "%5B")
            .replace("]", "%5D")
            .replace(":", "%3A")
        return client.newCall(GET("$apiUrl/api/r2/list?prefix=$encodedPrefix", headers))
            .asObservableSuccess()
            .map { response -> pageListParse(response) }
    }

    // ======== IMAGE URL ========

    override fun imageUrlParse(response: Response): String = response.request.url.toString()

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headers)

    // ======== UTILITAIRES ========

    private fun parseDate(dateStr: String): Long = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.FRENCH)
            .parse(dateStr)?.time
    }.getOrNull() ?: 0L

    override fun getMangaUrl(manga: SManga): String = when {
        manga.url.startsWith("/article/") -> "$baseUrl/articles/${manga.url.removePrefix("/article/")}"
        manga.url.startsWith("/run/") -> "$baseUrl/run/${manga.url.removePrefix("/run/")}"
        manga.url.startsWith("/reader/") -> "$baseUrl${manga.url}"
        else -> {
            val seriesId = manga.url
                .removePrefix("/api/series/")
                .removeSuffix("/issues")
            "$baseUrl/issue/${seriesId}_1"
        }
    }

    override fun getChapterUrl(chapter: SChapter): String = if (chapter.url.startsWith("/article/")) {
        "$baseUrl/articles/${chapter.url.removePrefix("/article/")}"
    } else {
        val link = chapter.url.removePrefix("/reader/")
        val encodedLink = link.replace(" ", "%20").replace("[", "%5B").replace("]", "%5D").replace(":", "%3A")
        "$baseUrl/read?mode=server&driveLink=$encodedLink"
    }
}
