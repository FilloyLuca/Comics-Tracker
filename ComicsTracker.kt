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

    // ======== CACHE DES PÉRIODES ET COLLECTIONS ========

    data class Period(val id: String, val name: String, val publisher: String)

    // Item d'une collection hors-périodes (dc_black_label, must_have...) — données du __NEXT_DATA__ de la home
    data class CollectionItem(
        val id: String,
        val frTitle: String,
        val link: String,
        val collectionSlug: String,
        val thumbnailUrl: String,
    )

    private var periodsCache: List<Period>? = null
    private var collectionItemsCache: List<CollectionItem>? = null

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

    // Scrape la home pour les items des collections (dc_black_label, must_have...)
    // Ces period_id n'existent pas dans /api/periods mais leurs données sont dans le __NEXT_DATA__ de la home
    // IDs de périodes valides dans /api/periods — les period_id absents de cette liste sont des collections hors-API
    private val knownApiPeriodIds = setOf(
        "all_new_all_different", "dc_absolute", "dawn_of_dc", "dc_rebirth", "infinite_frontier",
        "invincible", "marvel_classics", "dc_classics", "marvel_now", "ultimate_marvel",
        "ultimate_universe", "fresh_start", "timeless", "the_boys", "dc_all_in", "the_authority",
        "vertigo", "spawn", "energon_universe", "new_52", "the_walking_dead",
        "league_of_extraordinary_gentlemen", "tmnt", "star_wars_dark_horse",
    )

    private fun fetchCollectionItems(): List<CollectionItem> {
        collectionItemsCache?.let { return it }
        return runCatching {
            // On utilise les IDs connus en dur + ceux du cache si disponible, pour ne pas bloquer sur fetchPeriods()
            val validPeriodIds = if (periodsCache != null) {
                periodsCache!!.map { it.id }.toSet()
            } else {
                knownApiPeriodIds
            }
            val response = client.newCall(GET(baseUrl, headers)).execute()
            val document = Jsoup.parse(response.body.string())
            val nextDataJson = document.getElementById("__NEXT_DATA__")?.data() ?: return@runCatching emptyList()
            val root = json.parseToJsonElement(nextDataJson).jsonObject
            val allItems = mutableListOf<CollectionItem>()

            fun extractEditions(element: kotlinx.serialization.json.JsonElement) {
                when (element) {
                    is kotlinx.serialization.json.JsonArray -> element.forEach { extractEditions(it) }
                    is kotlinx.serialization.json.JsonObject -> {
                        val periodId = element["period_id"]?.jsonPrimitive?.content
                        val id = element["id"]?.jsonPrimitive?.content
                        val frTitle = element["french_title"]?.jsonPrimitive?.content
                        val link = element["link"]?.jsonPrimitive?.content
                        if (periodId != null && id != null && frTitle != null && link != null &&
                            !validPeriodIds.contains(periodId)
                        ) {
                            allItems.add(
                                CollectionItem(
                                    id = id,
                                    frTitle = frTitle,
                                    link = link,
                                    collectionSlug = periodId,
                                    thumbnailUrl = "$baseUrl/api/image-proxy/collection-items/$id?w=400",
                                ),
                            )
                        }
                        element.values.forEach { extractEditions(it) }
                    }
                    else -> {}
                }
            }
            extractEditions(root)

            val items = allItems.distinctBy { it.id }
            collectionItemsCache = items
            items
        }.getOrElse { emptyList() }
    }

    // ======== FILTRES ========

    private class PublisherFilter : Filter.Select<String>("Éditeur", arrayOf("Tous", "Marvel", "DC Comics", "Indépendants"))

    private class PeriodFilter(periods: List<Period>) : Filter.Select<String>("Période (optionnel)", arrayOf("Toutes", *periods.map { it.name }.toTypedArray())) {
        val periodList = periods
    }

    private class ContentTypeFilter : Filter.Select<String>("Type de contenu", arrayOf("Comics", "Articles & Guides"))

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
        if (collectionItemsCache == null) {
            Thread { runCatching { fetchCollectionItems() } }.start()
        }
        val ids = json.parseToJsonElement(response.body.string()).jsonArray
        val mangas = ids.map { element ->
            val seriesId = element.jsonPrimitive.content
            SManga.create().apply {
                url = "/series/$seriesId"
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
            .jsonObject["props"]?.jsonObject?.get("pageProps")?.jsonObject
            ?: return MangasPage(emptyList(), false)
        val editions = pageProps["editions"]?.jsonArray ?: return MangasPage(emptyList(), false)

        val seen = mutableSetOf<String>()
        val mangas = editions.mapNotNull { element ->
            val edition = element.jsonObject
            val editionId = edition["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val frTitle = edition["french_title"]?.jsonPrimitive?.content ?: editionId
            val groupId = editionId.replace(Regex("_t\\d+$|_v\\d+$|_n\\d+$|_vol\\d+$"), "")
            if (!seen.add(groupId)) return@mapNotNull null
            SManga.create().apply {
                url = "/series/$groupId"
                title = frTitle
                    .replace(Regex("\\s+(n°|Tome|Volume|Vol\\.|T\\.)\\s*\\d+.*", RegexOption.IGNORE_CASE), "")
                    .trim()
                thumbnail_url = "$apiUrl/api/issues/$editionId?w=400"
                initialized = false
            }
        }
        return MangasPage(mangas, false)
    }

    // ======== RECHERCHE ET FILTRES ========

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        // S'assurer que les périodes sont chargées (bloquant si nécessaire)
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

        if (contentTypeIndex == 1) return fetchArticles(query)

        if (periodIndex > 0) {
            val selectedPeriod = periodFilter?.periodList?.getOrNull(periodIndex - 1)
                ?: return Observable.just(MangasPage(emptyList(), false))
            return fetchByPeriod(selectedPeriod.id, query)
        }

        if (publisherIndex > 0) {
            val publisherKey = when (publisherIndex) {
                1 -> "marvel"
                2 -> "dc"
                else -> "independant"
            }
            return fetchByPublisher(periods.filter { it.publisher == publisherKey }, query)
        }

        if (query.isNotBlank()) {
            val queryNorm = normalizeAccents(query)

            // Recherche dans /api/series (séries classiques)
            val seriesSearch = client.newCall(GET("$apiUrl/api/series?page=1", headers))
                .asObservableSuccess()
                .flatMap { response ->
                    val ids = json.parseToJsonElement(response.body.string()).jsonArray
                    val matched = ids
                        .map { it.jsonPrimitive.content }
                        .filter { !it.contains(".") && normalizeAccents(it.replace("_", " ")).contains(queryNorm) }
                        .map { seriesId ->
                            SManga.create().apply {
                                url = "/series/$seriesId"
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

            // Recherche dans toutes les périodes (couvre collections, black label, runs, etc.)
            val editionsSearch = if (periods.isEmpty()) {
                Observable.just(MangasPage(emptyList(), false))
            } else {
                val periodedRequests = periods.map { period ->
                    client.newCall(GET("$apiUrl/api/french-editions?periodName=${period.id}", headers))
                        .asObservableSuccess()
                        .map { response -> Pair(period.id, response) }
                }
                Observable.merge(periodedRequests)
                    .toList()
                    .map { pairs ->
                        val seen = mutableSetOf<String>()
                        val mangas = mutableListOf<SManga>()
                        pairs.forEach { (periodId, response) ->
                            val editions = json.parseToJsonElement(response.body.string()).jsonArray
                            editions.forEach { element ->
                                val edition = element.jsonObject
                                val editionId = edition["id"]?.jsonPrimitive?.content ?: return@forEach
                                val frTitle = edition["french_title"]?.jsonPrimitive?.content ?: editionId
                                if (!normalizeAccents(frTitle).contains(queryNorm)) return@forEach

                                val link = edition["link"]?.jsonPrimitive?.content ?: ""
                                val sourceType = edition["source_type"]?.jsonPrimitive?.content ?: ""
                                val runId = if (sourceType == "run") {
                                    link.split("/runs/").getOrNull(1)?.split("/")?.firstOrNull()
                                        ?.lowercase()?.replace(" ", "_") ?: ""
                                } else {
                                    ""
                                }

                                // Déduplication par titre normalisé (regroupe tous les tomes d'une même série)
                                val dedupeKey = if (sourceType == "run") "run:$runId" else "title:${normalizeAccents(frTitle.replace(Regex("\\s+(Tome|Volume|Vol\\.|T\\.|n°)\\s*\\d+.*", RegexOption.IGNORE_CASE), "").trim())}"
                                if (!seen.add(dedupeKey)) return@forEach

                                mangas.add(
                                    SManga.create().apply {
                                        url = if (sourceType == "run") "/run/$runId" else "/period/$periodId/edition/$editionId"
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

            // Recherche dans les collections hors-périodes (dc_black_label, must_have, etc.)
            val collectionItems = runCatching { fetchCollectionItems() }.getOrElse { emptyList() }
            val collectionMangas = collectionItems
                .filter { normalizeAccents(it.frTitle).contains(queryNorm) }
                .distinctBy { normalizeAccents(it.frTitle) }
                .map { item ->
                    SManga.create().apply {
                        url = "/collection/${item.collectionSlug}/${item.id}"
                        title = item.frTitle
                        thumbnail_url = item.thumbnailUrl
                        initialized = false
                    }
                }

            return Observable.zip(seriesSearch, editionsSearch) { fromSeries, fromEditions ->
                val seen = mutableSetOf<String>()
                val combined = (fromSeries.mangas + fromEditions.mangas + collectionMangas).filter { seen.add(it.url) }
                MangasPage(combined.sortedBy { it.title }, false)
            }
        }

        // Pas de filtre, pas de query → liste populaire
        return client.newCall(popularMangaRequest(page))
            .asObservableSuccess()
            .map { popularMangaParse(it) }
    }

    // ======== ARTICLES & GUIDES ========

    private fun fetchArticles(query: String): Observable<MangasPage> {
        return client.newCall(GET("$baseUrl/articles", headers))
            .asObservableSuccess()
            .map { response ->
                val document = Jsoup.parse(response.body.string())
                val nextDataJson = document.getElementById("__NEXT_DATA__")?.data()
                    ?: return@map MangasPage(emptyList(), false)
                val pageProps = json.parseToJsonElement(nextDataJson)
                    .jsonObject["props"]?.jsonObject?.get("pageProps")?.jsonObject
                    ?: return@map MangasPage(emptyList(), false)
                val articles = pageProps["articles"]?.jsonArray ?: return@map MangasPage(emptyList(), false)
                val queryNorm = normalizeAccents(query)

                val mangas = articles.mapNotNull { element ->
                    val article = element.jsonObject
                    val slug = article["slug"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val title = article["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val content = article["content"]?.jsonPrimitive?.content ?: ""
                    if (queryNorm.isNotBlank() && !normalizeAccents(title).contains(queryNorm)) return@mapNotNull null
                    val preview = content
                        .replace(Regex("\\[.*?\\]\\(.*?\\)"), "")
                        .replace(Regex("#{1,6}\\s"), "")
                        .replace(Regex("\\*{1,2}(.*?)\\*{1,2}"), "$1")
                        .trim().take(200)
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

    private fun editionToManga(edition: kotlinx.serialization.json.JsonObject, periodId: String): SManga? {
        val editionId = edition["id"]?.jsonPrimitive?.content ?: return null
        val frTitle = edition["french_title"]?.jsonPrimitive?.content ?: editionId
        val link = edition["link"]?.jsonPrimitive?.content ?: ""
        val sourceType = edition["source_type"]?.jsonPrimitive?.content ?: ""
        val runId = if (sourceType == "run") {
            link.split("/runs/").getOrNull(1)?.split("/")?.firstOrNull()
                ?.lowercase()?.replace(" ", "_") ?: ""
        } else {
            ""
        }
        return SManga.create().apply {
            url = if (sourceType == "run") "/run/$runId" else "/period/$periodId/edition/$editionId"
            title = frTitle
                .replace(Regex("\\s+(Tome|Volume|Vol\\.|T\\.|n°)\\s*\\d+.*", RegexOption.IGNORE_CASE), "")
                .trim()
            thumbnail_url = "$apiUrl/api/issues/$editionId?w=400"
            initialized = false
        }
    }

    private fun fetchByPeriod(periodId: String, query: String): Observable<MangasPage> {
        return client.newCall(GET("$apiUrl/api/french-editions?periodName=$periodId", headers))
            .asObservableSuccess()
            .map { response ->
                val editions = json.parseToJsonElement(response.body.string()).jsonArray
                val queryNorm = normalizeAccents(query)
                val seen = mutableSetOf<String>()
                val mangas = editions.mapNotNull { element ->
                    val edition = element.jsonObject
                    val editionId = edition["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val frTitle = edition["french_title"]?.jsonPrimitive?.content ?: editionId
                    val sourceType = edition["source_type"]?.jsonPrimitive?.content ?: ""
                    val link = edition["link"]?.jsonPrimitive?.content ?: ""
                    val runId = if (sourceType == "run") {
                        link.split("/runs/").getOrNull(1)?.split("/")?.firstOrNull()
                            ?.lowercase()?.replace(" ", "_") ?: ""
                    } else {
                        ""
                    }
                    val dedupeKey = if (sourceType == "run") "run:$runId" else "title:${normalizeAccents(frTitle.replace(Regex("\\s+(Tome|Volume|Vol\\.|T\\.|n°)\\s*\\d+.*", RegexOption.IGNORE_CASE), "").trim())}"
                    if (!seen.add(dedupeKey)) return@mapNotNull null
                    if (queryNorm.isNotBlank() && !normalizeAccents(frTitle).contains(queryNorm)) return@mapNotNull null
                    editionToManga(edition, periodId)
                }
                MangasPage(mangas, false)
            }
    }

    private fun fetchByPublisher(periods: List<Period>, query: String): Observable<MangasPage> {
        if (periods.isEmpty()) return Observable.just(MangasPage(emptyList(), false))
        val periodedRequests = periods.map { period ->
            client.newCall(GET("$apiUrl/api/french-editions?periodName=${period.id}", headers))
                .asObservableSuccess()
                .map { response -> Pair(period.id, response) }
        }
        return Observable.merge(periodedRequests)
            .toList()
            .map { pairs ->
                val queryNorm = normalizeAccents(query)
                val seen = mutableSetOf<String>()
                val mangas = mutableListOf<SManga>()
                pairs.forEach { (periodId, response) ->
                    val editions = json.parseToJsonElement(response.body.string()).jsonArray
                    editions.forEach { element ->
                        val edition = element.jsonObject
                        val editionId = edition["id"]?.jsonPrimitive?.content ?: return@forEach
                        val frTitle = edition["french_title"]?.jsonPrimitive?.content ?: editionId
                        val sourceType = edition["source_type"]?.jsonPrimitive?.content ?: ""
                        val link = edition["link"]?.jsonPrimitive?.content ?: ""
                        val runId = if (sourceType == "run") {
                            link.split("/runs/").getOrNull(1)?.split("/")?.firstOrNull()
                                ?.lowercase()?.replace(" ", "_") ?: ""
                        } else {
                            ""
                        }
                        val dedupeKey = if (sourceType == "run") "run:$runId" else "title:${normalizeAccents(frTitle.replace(Regex("\\s+(Tome|Volume|Vol\\.|T\\.|n°)\\s*\\d+.*", RegexOption.IGNORE_CASE), "").trim())}"
                        if (!seen.add(dedupeKey)) return@forEach
                        if (queryNorm.isNotBlank() && !normalizeAccents(frTitle).contains(queryNorm)) return@forEach
                        editionToManga(edition, periodId)?.let { mangas.add(it) }
                    }
                }
                MangasPage(mangas.sortedBy { it.title }, false)
            }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$apiUrl/api/series?page=$page", headers)

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ======== DÉTAILS D'UN MANGA ========

    override fun mangaDetailsRequest(manga: SManga): Request = when {
        manga.url.startsWith("/article/") ->
            GET("$baseUrl/articles/${manga.url.removePrefix("/article/")}", headers)
        manga.url.startsWith("/run/") ->
            GET("$apiUrl/api/runs/${manga.url.removePrefix("/run/")}", headers)
        manga.url.startsWith("/period/") -> {
            // /period/{periodId}/edition/{editionId}
            val periodId = manga.url.removePrefix("/period/").substringBefore("/edition/")
            val editionId = manga.url.substringAfterLast("/edition/")
            val newHeaders = headers.newBuilder().add("X-Edition-Id", editionId).build()
            GET("$apiUrl/api/french-editions?periodName=$periodId", newHeaders)
        }
        manga.url.startsWith("/collection/") -> {
            // /collection/{collectionSlug}/{editionId} — comic hors-périodes, pas d'API, données dans la home
            // On retourne la page de la collection pour parser le __NEXT_DATA__
            val collectionSlug = manga.url.removePrefix("/collection/").substringBefore("/")
            val editionId = manga.url.substringAfterLast("/")
            val newHeaders = headers.newBuilder().add("X-Edition-Id", editionId).build()
            GET("$baseUrl/collection/$collectionSlug", newHeaders)
        }
        manga.url.startsWith("/series/") ->
            GET("$apiUrl/api/series/${manga.url.removePrefix("/series/")}/issues", headers)
        else ->
            GET("$apiUrl${manga.url}", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        if (response.request.url.toString().contains("/articles/")) {
            return SManga.create().apply {
                description = "📖 Cliquez sur 'Ouvrir dans le navigateur' pour lire cet article sur comics-tracker.net"
                status = SManga.COMPLETED
                initialized = true
            }
        }

        val responseStr = response.body.string()
        val targetEditionId = response.request.header("X-Edition-Id")

        // Si la réponse est une page HTML (collection hors-périodes), parser le __NEXT_DATA__
        val isHtmlResponse = responseStr.trimStart().startsWith("<")
        if (isHtmlResponse && targetEditionId != null) {
            val document = Jsoup.parse(responseStr)
            val nextDataJson = document.getElementById("__NEXT_DATA__")?.data()
            if (nextDataJson != null) {
                val collectionItems = mutableListOf<kotlinx.serialization.json.JsonObject>()
                fun findItems(el: kotlinx.serialization.json.JsonElement) {
                    when (el) {
                        is kotlinx.serialization.json.JsonArray -> el.forEach { findItems(it) }
                        is kotlinx.serialization.json.JsonObject -> {
                            if (el["id"]?.jsonPrimitive?.content == targetEditionId) collectionItems.add(el)
                            el.values.forEach { findItems(it) }
                        }
                        else -> {}
                    }
                }
                findItems(json.parseToJsonElement(nextDataJson))
                val item = collectionItems.firstOrNull()
                if (item != null) {
                    return SManga.create().apply {
                        title = item["french_title"]?.jsonPrimitive?.content ?: targetEditionId
                        thumbnail_url = "$baseUrl/api/image-proxy/collection-items/$targetEditionId?w=400"
                        description = "Comics disponible sur Comics Tracker (VF)"
                        status = SManga.UNKNOWN
                        initialized = true
                    }
                }
            }
        }

        val editions: List<kotlinx.serialization.json.JsonElement> = runCatching {
            val element = json.parseToJsonElement(responseStr)
            if (element is kotlinx.serialization.json.JsonArray) {
                val all = element.jsonArray.toList()
                if (targetEditionId != null) {
                    // Garder tous les tomes dont l'ID partage la même racine
                    // Ex: "batman_annee_un_t1" → racine "batman_annee_un" → garde _t1, _t2, etc.
                    val root = targetEditionId.replace(Regex("_t\\d+$|_v\\d+$|_n\\d+$|_vol\\d+$"), "")
                    all.filter {
                        val id = it.jsonObject["id"]?.jsonPrimitive?.content ?: ""
                        id.startsWith(root)
                    }
                } else {
                    all
                }
            } else {
                val jsonObj = element.jsonObject
                if (jsonObj.containsKey("sections")) {
                    jsonObj["sections"]!!.jsonArray.flatMap {
                        it.jsonObject["frenchEditions"]?.jsonArray?.toList() ?: emptyList()
                    }
                } else {
                    jsonObj["frenchEditions"]?.jsonArray?.toList() ?: emptyList()
                }
            }
        }.getOrElse { emptyList() }

        val hasVF = editions.isNotEmpty()
        val firstEdition = if (hasVF) editions[0].jsonObject else null

        return SManga.create().apply {
            if (hasVF && firstEdition != null) {
                val frTitle = firstEdition["french_title"]?.jsonPrimitive?.content ?: ""
                title = frTitle.replace(Regex("\\s+(Tome|Volume|Vol\\.|T\\.)\\s+\\d+.*", RegexOption.IGNORE_CASE), "").trim()
                thumbnail_url = firstEdition["id"]?.jsonPrimitive?.content?.let { "$apiUrl/api/issues/$it?w=400" }
                description = "Comics disponible sur Comics Tracker (VF)"
                status = SManga.UNKNOWN
            } else {
                // Pas de VF : on garde la cover déjà chargée (ne pas mettre null !)
                // On renseigne seulement description et status
                description = "❌ Ce comic n'est pas disponible en version française sur Comics Tracker.\n\nSeules les éditions VF sont lisibles via cette extension."
                status = SManga.LICENSED
            }
            initialized = true
        }
    }

    // ======== LISTE DES CHAPITRES ========

    override fun chapterListRequest(manga: SManga): Request = when {
        manga.url.startsWith("/article/") ->
            GET("$baseUrl/articles/${manga.url.removePrefix("/article/")}", headers)
        manga.url.startsWith("/run/") ->
            GET("$apiUrl/api/runs/${manga.url.removePrefix("/run/")}", headers)
        manga.url.startsWith("/period/") -> {
            val periodId = manga.url.removePrefix("/period/").substringBefore("/edition/")
            val editionId = manga.url.substringAfterLast("/edition/")
            val newHeaders = headers.newBuilder().add("X-Edition-Id", editionId).build()
            GET("$apiUrl/api/french-editions?periodName=$periodId", newHeaders)
        }
        manga.url.startsWith("/collection/") -> {
            val collectionSlug = manga.url.removePrefix("/collection/").substringBefore("/")
            val editionId = manga.url.substringAfterLast("/")
            val newHeaders = headers.newBuilder().add("X-Edition-Id", editionId).build()
            GET("$baseUrl/collection/$collectionSlug", newHeaders)
        }
        manga.url.startsWith("/series/") ->
            GET("$apiUrl/api/series/${manga.url.removePrefix("/series/")}/issues", headers)
        else ->
            GET("$apiUrl${manga.url}", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
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

        val responseStr = response.body.string()
        val targetEditionId = response.request.header("X-Edition-Id")

        // Si la réponse est une page HTML (collection hors-périodes), parser le __NEXT_DATA__
        val isHtml = responseStr.trimStart().startsWith("<")
        if (isHtml && targetEditionId != null) {
            val document = Jsoup.parse(responseStr)
            val nextDataJson = document.getElementById("__NEXT_DATA__")?.data() ?: return emptyList()
            val collectionItems = mutableListOf<kotlinx.serialization.json.JsonObject>()
            fun findItems(el: kotlinx.serialization.json.JsonElement) {
                when (el) {
                    is kotlinx.serialization.json.JsonArray -> el.forEach { findItems(it) }
                    is kotlinx.serialization.json.JsonObject -> {
                        if (el["id"]?.jsonPrimitive?.content == targetEditionId) collectionItems.add(el)
                        el.values.forEach { findItems(it) }
                    }
                    else -> {}
                }
            }
            findItems(json.parseToJsonElement(nextDataJson))
            val item = collectionItems.firstOrNull() ?: return emptyList()
            val link = item["link"]?.jsonPrimitive?.content ?: return emptyList()
            val frTitle = item["french_title"]?.jsonPrimitive?.content ?: targetEditionId
            val createdAt = item["created_at"]?.jsonPrimitive?.content ?: ""
            // Une collection = un seul "chapitre" qui correspond au dossier complet
            return listOf(
                SChapter.create().apply {
                    url = "/reader/$link"
                    name = frTitle
                    chapter_number = 1f
                    date_upload = parseDate(createdAt)
                },
            )
        }

        val allEditions: List<kotlinx.serialization.json.JsonElement> = runCatching {
            val element = json.parseToJsonElement(responseStr)
            if (element is kotlinx.serialization.json.JsonArray) {
                val all = element.jsonArray.toList()
                if (targetEditionId != null) {
                    val root = targetEditionId.replace(Regex("_t\\d+$|_v\\d+$|_n\\d+$|_vol\\d+$"), "")
                    all.filter {
                        val id = it.jsonObject["id"]?.jsonPrimitive?.content ?: ""
                        id.startsWith(root)
                    }
                } else {
                    all
                }
            } else {
                val jsonObj = element.jsonObject
                if (jsonObj.containsKey("sections")) {
                    jsonObj["sections"]!!.jsonArray.flatMap {
                        it.jsonObject["frenchEditions"]?.jsonArray?.toList() ?: emptyList()
                    }
                } else {
                    jsonObj["frenchEditions"]?.jsonArray?.toList() ?: emptyList()
                }
            }
        }.getOrElse { emptyList() }

        if (allEditions.isEmpty()) return emptyList()

        return allEditions.mapIndexed { index, element ->
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
        if (chapter.url.startsWith("/article/")) {
            return GET("$baseUrl/articles/${chapter.url.removePrefix("/article/")}", headers)
        }
        val link = chapter.url.removePrefix("/reader/")
        val encodedPrefix = link.replace(" ", "%20").replace("[", "%5B").replace("]", "%5D").replace(":", "%3A")
        return GET("$apiUrl/api/r2/list?prefix=$encodedPrefix", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
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
            return Observable.just(listOf(Page(0, "$baseUrl/articles/${chapter.url.removePrefix("/article/")}", null)))
        }
        val link = chapter.url.removePrefix("/reader/")
        val encodedPrefix = link.replace(" ", "%20").replace("[", "%5B").replace("]", "%5D").replace(":", "%3A")
        return client.newCall(GET("$apiUrl/api/r2/list?prefix=$encodedPrefix", headers))
            .asObservableSuccess()
            .map { response -> pageListParse(response) }
    }

    // ======== IMAGE URL ========

    override fun imageUrlParse(response: Response): String = response.request.url.toString()

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headers)

    // ======== UTILITAIRES ========

    private fun parseDate(dateStr: String): Long = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.FRENCH).parse(dateStr)?.time
    }.getOrNull() ?: 0L

    override fun getMangaUrl(manga: SManga): String = when {
        manga.url.startsWith("/article/") ->
            "$baseUrl/articles/${manga.url.removePrefix("/article/")}"
        manga.url.startsWith("/run/") ->
            "$baseUrl/run/${manga.url.removePrefix("/run/")}"
        manga.url.startsWith("/collection/") -> {
            val collectionSlug = manga.url.removePrefix("/collection/").substringBefore("/")
            "$baseUrl/collection/$collectionSlug"
        }
        manga.url.startsWith("/period/") ->
            "$baseUrl/issue/${manga.url.substringAfterLast("/edition/")}"
        manga.url.startsWith("/series/") ->
            "$baseUrl/issue/${manga.url.removePrefix("/series/")}_1"
        else -> baseUrl
    }

    override fun getChapterUrl(chapter: SChapter): String = if (chapter.url.startsWith("/article/")) {
        "$baseUrl/articles/${chapter.url.removePrefix("/article/")}"
    } else {
        val link = chapter.url.removePrefix("/reader/")
        val encodedLink = link.replace(" ", "%20").replace("[", "%5B").replace("]", "%5D").replace(":", "%3A")
        "$baseUrl/read?mode=server&driveLink=$encodedLink"
    }

    private fun normalizeAccents(input: String): String = java.text.Normalizer
        .normalize(input, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .replace("[^a-zA-Z0-9\\s]".toRegex(), " ")
        .replace("\\s+".toRegex(), " ")
        .lowercase()
        .trim()
}
