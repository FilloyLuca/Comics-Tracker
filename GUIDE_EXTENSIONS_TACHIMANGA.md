# Guide Création d'Extensions Tachimanga (iOS)

Guide complet basé sur la création de l'extension **Comics Tracker**, documentant chaque étape, problème rencontré et solution trouvée.

---

## Sommaire

1. [Prérequis](#1-prérequis)
2. [Structure d'une extension](#2-structure-dune-extension)
3. [Analyser le site cible](#3-analyser-le-site-cible)
4. [Coder l'extension](#4-coder-lextension)
5. [Compiler l'extension](#5-compiler-lextension)
6. [Héberger sur GitHub](#6-héberger-sur-github)
7. [Installer dans Tachimanga](#7-installer-dans-tachimanga)
8. [Mettre à jour une extension](#8-mettre-à-jour-une-extension)
9. [Problèmes rencontrés et solutions](#9-problèmes-rencontrés-et-solutions)
10. [Erreurs à ne jamais faire](#10-erreurs-à-ne-jamais-faire)

---

## 1. Prérequis

### Logiciels nécessaires
- **Android Studio** (gratuit) — pour compiler le code Kotlin en APK
- **Git** — pour cloner les repos
- **JDK** — inclus dans Android Studio, pas besoin d'installer séparément
- Un compte **GitHub** — pour héberger l'extension

### Repos à cloner
```bash
# Repo du code source des extensions (contient gradlew.bat)
git clone https://github.com/keiyoushi/extensions-source
```

> ⚠️ Ne pas cloner `keiyoushi/extensions` (branche `repo`) — c'est le repo des APK compilés, pas le code source.

---

## 2. Structure d'une extension

### Structure des fichiers sur GitHub
Le repo GitHub doit avoir **deux branches distinctes** :

```
branche main/                          ← Code source
├── README.md
└── src/fr/comicstracker/
    ├── build.gradle
    └── src/eu/kanade/tachiyomi/extension/fr/comicstracker/
        └── NomExtension.kt

branche repo/                          ← Distribution
├── index.min.json
├── icon/
│   └── eu.kanade.tachiyomi.extension.fr.nomextension.png
└── apk/
    └── tachiyomi-fr.nomextension-v1.4.X-debug.apk
```

> ⚠️ **Ne jamais merger `repo` dans `main`** — ce sont deux branches avec des rôles totalement différents.

### Format du `index.min.json`
```json
[{
  "name": "Tachiyomi: Nom Extension",
  "pkg": "eu.kanade.tachiyomi.extension.fr.nomextension",
  "apk": "tachiyomi-fr.nomextension-v1.4.X-debug.apk",
  "lang": "fr",
  "code": 1,
  "version": "1.4.1",
  "nsfw": 0,
  "sources": [{
    "name": "Nom Extension",
    "lang": "fr",
    "id": "IDENTIFIANT_UNIQUE",
    "baseUrl": "https://le-site.com",
    "versionId": 1
  }]
}]
```

> ⚠️ Règles critiques du `index.min.json` :
> - Le champ `"apk"` = **nom du fichier uniquement**, pas une URL complète
> - L'`id` dans `sources` doit être un nombre unique (peut être n'importe quel grand nombre)
> - Pas de champ `"icon"` dans le JSON — l'icône est trouvée automatiquement via son nom de fichier
> - Le `"code"` doit être incrémenté à chaque mise à jour

### Format du `build.gradle`
```gradle
ext {
    extName = 'Nom Extension'
    extClass = '.NomExtension'
    extVersionCode = 1
}
apply plugin: "kei.plugins.extension.legacy"
```

> ⚠️ Ne pas utiliser `apply from: "$rootDir/common.gradle"` — ce format ne fonctionne pas dans `extensions-source`.

### Nommage de l'icône
L'icône doit s'appeler exactement :
```
eu.kanade.tachiyomi.extension.[lang].[nomextension].png
```
Exemple : `eu.kanade.tachiyomi.extension.fr.comicstracker.png`

Et se trouver dans le dossier `icon/` de la branche `repo`.

---

## 3. Analyser le site cible

Avant de coder, il faut comprendre la structure du site. Ouvrir les DevTools (F12) et identifier :

### Questions à se poser
1. **Le site a-t-il une API ?** → Tester `api.le-site.com` ou `le-site.com/api/`
2. **Comment sont listées les séries ?** → URL de la page catalogue
3. **Comment sont listés les chapitres ?** → Structure de la page d'une série
4. **Comment sont chargées les images ?** → Inspecter les balises `<img>` sur la page de lecture
5. **Le site utilise-t-il Next.js ?** → Chercher `__NEXT_DATA__` dans le source HTML — toutes les données SSR y sont, même celles sans appel API visible

### Pour Comics Tracker
- Liste des séries : `https://api.comics-tracker.net/api/series?page=N` → retourne un tableau JSON d'IDs
- Détails d'une série : `https://api.comics-tracker.net/api/series/{id}/issues` → retourne `frenchEditions`
- Liste des périodes valides : `https://api.comics-tracker.net/api/periods` → retourne tous les `period_id` acceptés par l'API
- Liste des éditions par période : `https://api.comics-tracker.net/api/french-editions?periodName={id}` → retourne toutes les éditions (séries ET runs) avec `source_type`
- Détails d'un run (saga d'auteur) : `https://api.comics-tracker.net/api/runs/{runId}` → retourne `sections[].frenchEditions`
- Liste des pages d'un chapitre : `https://api.comics-tracker.net/api/r2/list?prefix={link}` → retourne un tableau JSON de chemins complets
- Images de lecture : `https://images.comics-tracker.net/{chemin_complet}` (chemin issu de `/api/r2/list`)
- Couvertures : `https://api.comics-tracker.net/api/issues/{id}?w=400`
- **Collections hors-périodes** (DC Black Label, Marvel Must-Have...) : données dans le `__NEXT_DATA__` de `https://comics-tracker.net` — pas d'endpoint API dédié

> ⚠️ `/api/issues/{id}` sans paramètre retourne directement l'image de couverture, pas du JSON. Ne pas appeler cet endpoint pour récupérer des métadonnées.

> ⚠️ Le champ `link` du JSON (ex: `comics/marvel/.../Secret_Wars_1/`) est seulement le **préfixe** du dossier. Les chemins complets avec sous-dossiers (ex: `[Comics Fr]Secret Wars - 001/`) sont uniquement disponibles via `/api/r2/list`.

> ⚠️ Il existe **trois types de contenu** distincts dans Comics Tracker :
> - `source_type: "edition"` dans une période API → série classique, via `/api/series/{id}/issues` → structure `frenchEditions[]`
> - `source_type: "run"` → saga d'auteur, via `/api/runs/{runId}` → structure `sections[].frenchEditions[]`
> - `source_type: "edition"` dans une **collection hors-périodes** (ex: `period_id: "dc_black_label"`) → absent de `/api/periods`, données uniquement dans le `__NEXT_DATA__` de la home

> ⚠️ Les `period_id` retournés dans le champ `period_id` des éditions ne correspondent pas tous aux IDs valides de `/api/periods`. Vérifier toujours avec `/api/periods` avant d'appeler `/api/french-editions?periodName=...` — un `period_id` invalide retourne une erreur 400.

---

## 4. Coder l'extension

### Imports obligatoires
```kotlin
import eu.kanade.tachiyomi.network.GET          // ← Obligatoire pour les requêtes HTTP
import eu.kanade.tachiyomi.network.asObservableSuccess  // ← Pour la recherche
import uy.kohesive.injekt.injectLazy            // ← Pour le Json
```

> ⚠️ Sans `import eu.kanade.tachiyomi.network.GET`, toutes les requêtes échouent avec "Unresolved reference".

### Fonctions obligatoires à implémenter
```kotlin
class MonExtension : HttpSource() {
    override val name: String           // Nom affiché dans Tachimanga
    override val baseUrl: String        // URL du site
    override val lang: String           // Code langue (ex: "fr")
    override val supportsLatest: Boolean

    // Liste populaire (page d'accueil)
    override fun popularMangaRequest(page: Int): Request
    override fun popularMangaParse(response: Response): MangasPage

    // Recherche
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request
    override fun searchMangaParse(response: Response): MangasPage

    // Détails d'un manga
    override fun mangaDetailsRequest(manga: SManga): Request
    override fun mangaDetailsParse(response: Response): SManga

    // Liste des chapitres
    override fun chapterListRequest(manga: SManga): Request
    override fun chapterListParse(response: Response): List<SChapter>

    // Pages d'un chapitre
    override fun pageListRequest(chapter: SChapter): Request
    override fun pageListParse(response: Response): List<Page>

    // URL d'une image
    override fun imageUrlParse(response: Response): String

    // Latest (même si non supporté, doit exister)
    override fun latestUpdatesRequest(page: Int): Request
    override fun latestUpdatesParse(response: Response): MangasPage
}
```

---

## 5. Compiler l'extension

### Étapes

**1 — Placer les fichiers dans extensions-source**
```
extensions-source/src/fr/nomextension/
├── build.gradle
├── res/
│   ├── mipmap-hdpi/ic_launcher.png      (72x72)
│   ├── mipmap-mdpi/ic_launcher.png      (48x48)
│   ├── mipmap-xhdpi/ic_launcher.png     (96x96)
│   ├── mipmap-xxhdpi/ic_launcher.png    (144x144)
│   └── mipmap-xxxhdpi/ic_launcher.png   (192x192)
└── src/eu/kanade/tachiyomi/extension/fr/nomextension/
    └── NomExtension.kt
```

**2 — Configurer Java (à faire à chaque nouvelle session PowerShell)**
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
```

Pour rendre permanent :
```powershell
[System.Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Android\Android Studio\jbr", "User")
```

**3 — Configurer le SDK Android (une seule fois)**
```powershell
Set-Content "extensions-source\local.properties" "sdk.dir=C\:\\Users\\[username]\\AppData\\Local\\Android\\Sdk"
```

**4 — Compiler**
```powershell
cd extensions-source
.\gradlew.bat :src:fr:nomextension:assembleDebug
```

**5 — Trouver l'APK généré**
```
extensions-source\src\fr\nomextension\build\outputs\apk\debug\
```

> ⚠️ Le dossier `build/` est ignoré par `.gitignore` — ne pas essayer de le pusher directement.

---

## 6. Héberger sur GitHub

### Structure du repo GitHub

**Branche `main`** (code source) :
- Uploader `build.gradle` et `NomExtension.kt`
- `README.md`

**Branche `repo`** (distribution) :
- Créer via GitHub : cliquer sur `main` → taper `repo` → "Create branch: repo"
- Créer dossier `apk/` → uploader l'APK dedans
- Créer dossier `icon/` → uploader l'icône nommée `eu.kanade.tachiyomi.extension.fr.nomextension.png`
- Uploader `index.min.json` à la racine

### URL de distribution
```
https://raw.githubusercontent.com/[username]/[repo]/repo/index.min.json
```

---

## 7. Installer dans Tachimanga

1. Ouvrir Tachimanga → `Browser` → `Extensions` → `Repositories`
2. Appuyer sur **+** → sélectionner **Par URL**
3. Coller l'URL du `index.min.json` (branche `repo`)
4. Aller dans `Extensions` → trouver l'extension → **Installer**

---

## 8. Mettre à jour une extension

1. Modifier le code dans `extensions-source`
2. Incrémenter `extVersionCode` dans `build.gradle` (ex: `2` → `3`)
3. Recompiler avec `.\gradlew.bat`
4. Uploader le nouvel APK dans `apk/` de la branche `repo`
5. Mettre à jour `index.min.json` : incrémenter `"code"` et `"version"`
6. Supprimer l'ancien APK du repo (optionnel mais recommandé)

---

## 9. Problèmes rencontrés et solutions

### ❌ `JAVA_HOME is not set`
**Cause** : Java non configuré dans la session PowerShell.
**Solution** :
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
```

### ❌ `Could not read script 'common.gradle' as it does not exist`
**Cause** : Mauvais format de `build.gradle` (format ancien Tachiyomi).
**Solution** : Utiliser le format `apply plugin: "kei.plugins.extension.legacy"`.

### ❌ `SDK location not found`
**Cause** : Le fichier `local.properties` est manquant.
**Solution** : Créer le fichier avec le chemin du SDK Android.

### ❌ `ic_launcher.png.png` — nom de fichier invalide
**Cause** : Fichier renommé avec double extension `.png.png`.
**Solution** :
```powershell
Get-ChildItem -Recurse -Filter "*.png.png" | Rename-Item -NewName { $_.Name -replace '\.png\.png$', '.png' }
```

### ❌ `Unresolved reference 'GET'`
**Cause** : Import manquant dans le fichier Kotlin.
**Solution** : Ajouter `import eu.kanade.tachiyomi.network.GET`.

### ❌ HTTP error 404 lors de l'installation
**Cause** : Tachimanga cherche l'APK dans la branche `repo` dans un dossier `apk/`, pas dans `main` à la racine.
**Solution** : Créer une branche `repo`, mettre l'APK dans `apk/` et pointer le `index.min.json` vers cette branche.

### ❌ Tachimanga affiche une ancienne version (cache)
**Cause** : Tachimanga met en cache le `index.min.json`.
**Solution** : Supprimer et réajouter le repo dans Tachimanga + vider le cache via Paramètres → Avancé → Vider le cache.

### ❌ L'icône n'apparaît pas
**Cause** : Mauvais nom de fichier ou mauvais emplacement.
**Solution** : Nommer l'icône `eu.kanade.tachiyomi.extension.[lang].[pkg].png` et la placer dans `icon/` de la branche `repo`.

### ❌ `remote: Permission denied` lors du push
**Cause** : Tentative de push vers `keiyoushi/extensions-source` (repo officiel).
**Solution** : Pusher uniquement vers son propre repo GitHub.

### ❌ Comics sans chapitres
**Cause** : La série n'a pas d'édition française sur le site (`frenchEditions: []`).
**Solution** : Comportement normal — ces comics ne sont pas disponibles en VF.

### ❌ Images ne se chargent pas (erreur 404 sur les pages)
**Cause** : `java.net.URLEncoder.encode()` encode les espaces en `+` au lieu de `%20` et encode aussi les `/`, ce qui corrompt le chemin envoyé à `/api/r2/list`.
**Solution** : Encoder manuellement uniquement les caractères problématiques :
```kotlin
val encodedPrefix = link
    .replace(" ", "%20")
    .replace("[", "%5B")
    .replace("]", "%5D")
    .replace(":", "%3A")
```
Appliquer ce fix partout où le `link` est utilisé dans une URL : `pageListRequest`, `fetchPageList`, et les URLs d'images dans `pageListParse`.

### ❌ `getChapterUrl` génère une URL cassée (ex: `…/issue/comics/marvel/…`)
**Cause** : Le champ `chapter.url` contient le chemin complet du dossier (ex: `comics/marvel/…`), pas un ID de série. L'ancienne implémentation faisait `$baseUrl/issue/$link` ce qui produisait une URL invalide.
**Solution** : Pointer vers la vraie page de lecture du site :
```kotlin
override fun getChapterUrl(chapter: SChapter): String = if (chapter.url.startsWith("/article/")) {
    "$baseUrl/articles/${chapter.url.removePrefix("/article/")}"
} else {
    val link = chapter.url.removePrefix("/reader/")
    val encodedLink = link.replace(" ", "%20").replace("[", "%5B").replace("]", "%5D").replace(":", "%3A")
    "$baseUrl/read?mode=server&driveLink=$encodedLink"
}
```

### ❌ Tachimanga refuse de mettre à jour (garde une ancienne version)
**Cause** : Tachimanga compare le `"code"` du `index.min.json` avec celui de l'extension installée. Si le nouveau `"code"` est inférieur ou égal, il ignore la mise à jour — même après désinstallation et vider le cache.
**Solution** : Toujours aller **vers l'avant** — incrémenter `"code"`, `"version"` et `extVersionCode`. Ne jamais réutiliser un numéro de version déjà publié.

> 🔥 **Versions brûlées — Comics Tracker** :
> - **1.4.8** : bug d'encodage URL (images ne se chargeaient pas). Version suivante valide : **1.4.9**.
> - **1.4.11** : régression — moins fonctionnelle que la 1.4.10. Version suivante valide : **1.4.12**.
> - **1.4.13** : bug `&&` manquant dans le filtre de recherche — toute la recherche textuelle était cassée. Version suivante valide : **1.4.14**.
> - **1.4.23 → 1.4.26** : tentatives de correction des collections hors-périodes — toutes non fonctionnelles. Version suivante valide : **1.4.27**.

### ❌ Comics de type "run" (saga d'auteur) non trouvés ou erreur 404
**Cause** : Les runs (ex: Spider-Man par Dan Slott) n'utilisent pas `/api/series/{id}/issues` mais `/api/runs/{runId}` avec une structure différente (`sections[].frenchEditions` au lieu de `frenchEditions`). L'extension traitait tous les comics comme des séries classiques.
**Solution** : Détecter le `source_type` dans les réponses de `/api/french-editions` et router différemment :
```kotlin
val sourceType = edition["source_type"]?.jsonPrimitive?.content ?: ""
val runId = if (sourceType == "run") {
    link.split("/runs/").getOrNull(1)?.split("/")?.firstOrNull()
        ?.lowercase()?.replace(" ", "_") ?: ""
} else ""
url = if (sourceType == "run") "/run/$runId" else "/period/$periodId/edition/$editionId"
```
Stocker `/run/{runId}` dans `manga.url` pour les runs, et adapter `mangaDetailsRequest`, `chapterListRequest`, `mangaDetailsParse` et `chapterListParse` pour gérer les deux structures.

### ❌ `mangaDetailsRequest` / `chapterListRequest` retournent 404 pour les comics via filtres
**Cause** : Quand un manga vient de `fetchByPeriod` ou `fetchByPublisher`, son `url` était `/reader/comics/marvel/...`. Le code appelait `$baseUrl/reader/...` (page web) au lieu de l'API.
**Solution** : Stocker le `periodId` et l'`editionId` dans l'URL interne au format `/period/{periodId}/edition/{editionId}`, puis appeler `/api/french-editions?periodName={periodId}` avec un header `X-Edition-Id` pour filtrer.

### ❌ Recherche textuelle timeout pour les comics de type "run"
**Cause** : La recherche parcourait `/api/series?page=N` en boucle, mais les runs n'y sont pas. Elle tournait jusqu'au timeout.
**Solution** : Lancer la recherche en parallèle dans `/api/series` ET `/api/french-editions` de toutes les périodes, puis fusionner les résultats :
```kotlin
return Observable.zip(seriesSearch, editionsSearch) { fromSeries, fromEditions ->
    val seen = mutableSetOf<String>()
    val combined = (fromSeries.mangas + fromEditions.mangas + collectionMangas).filter { seen.add(it.url) }
    MangasPage(combined.sortedBy { it.title }, false)
}
```

### ❌ Numéros spéciaux (`.DEATHS`, `.MU`, etc.) apparaissent comme résultats de recherche
**Cause** : La recherche dans `/api/series` retourne tous les IDs bruts, y compris les numéros spéciaux comme `amazing_spider-man_2022_65.deaths`. Ces IDs passent le filtre de recherche car ils contiennent le nom de la série, mais ils n'ont pas d'édition française propre.
**Solution** : Exclure les IDs contenant un `.` dans le filtre de recherche :
```kotlin
.filter {
    it.contains(queryLower) &&
        !it.contains(".")
}
```

### ❌ Toute la recherche textuelle retourne la liste populaire (plus de résultats)
**Cause** : Un `&&` manquant dans un bloc `.filter {}` — en Kotlin, deux expressions sur des lignes séparées sans opérateur ne sont pas combinées : seule la dernière est évaluée. Exemple du bug :
```kotlin
.filter {
    it.contains(queryLower)  // ← ignoré !
    !it.contains(".")        // ← seule condition évaluée
}
```
**Solution** : Toujours utiliser `&&` explicitement :
```kotlin
.filter {
    it.contains(queryLower) &&
        !it.contains(".")
}
```

### ❌ Comics des collections hors-périodes introuvables (DC Black Label, Marvel Must-Have...)
**Cause** : Ces collections ont un `period_id` (ex: `dc_black_label`) qui **n'existe pas** dans `/api/periods` et n'est donc jamais interrogé via `/api/french-editions`. Appeler `/api/french-editions?periodName=dc_black_label` retourne une erreur 400.
**Solution** : Scraper le `__NEXT_DATA__` de la page d'accueil (`https://comics-tracker.net`) pour extraire les éditions dont le `period_id` est absent de la liste `/api/periods`. Ces items sont mis en cache au démarrage de la source et inclus dans les résultats de recherche.
```kotlin
// Identifier les collections hors-API : period_id absent de la liste /api/periods
if (periodId != null && !validApiPeriodIds.contains(periodId)) {
    // Item issu d'une collection hors-périodes → stocker avec url = "/collection/{periodId}/{editionId}"
}
```
> ⚠️ Le cache des collections est préchauffé en arrière-plan dans `popularMangaParse`. Si la recherche est lancée immédiatement au premier démarrage, le cache peut ne pas encore être prêt — attendre quelques secondes après avoir ouvert la source.

### ❌ Recherche insensible aux accents échoue ("annee un" ne trouve pas "Année Un")
**Cause** : La comparaison `frTitle.lowercase().contains(queryLower)` est sensible aux accents — "année" ≠ "annee".
**Solution** : Normaliser les deux chaînes avant comparaison :
```kotlin
private fun normalizeAccents(input: String): String = java.text.Normalizer
    .normalize(input, java.text.Normalizer.Form.NFD)
    .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    .replace("[^a-zA-Z0-9\\s]".toRegex(), " ")
    .lowercase()
    .trim()

// Utilisation
if (!normalizeAccents(frTitle).contains(normalizeAccents(query))) return@forEach
```

### ❌ Covers disparues après `mangaDetailsParse` sur les comics sans VF
**Cause** : Retourner `thumbnail_url = null` dans `mangaDetailsParse` écrase la cover déjà chargée par Tachimanga depuis la liste.
**Solution** : Ne jamais assigner `thumbnail_url` dans le cas "pas de VF" — laisser Tachimanga conserver la valeur existante :
```kotlin
return SManga.create().apply {
    if (hasVF) {
        thumbnail_url = "..."  // ← seulement si on a une meilleure cover
    }
    // pas de VF → ne pas toucher à thumbnail_url
    description = "❌ Non disponible en VF"
    status = SManga.LICENSED
    initialized = true
}
```

---

## 10. Erreurs à ne jamais faire

| ❌ À éviter | ✅ À faire à la place |
|---|---|
| Mettre une URL complète dans le champ `"apk"` du JSON | Mettre uniquement le nom du fichier |
| Merger la branche `repo` dans `main` | Garder les deux branches séparées |
| Uploader l'APK dans `main` | Uploader l'APK dans `repo/apk/` |
| Utiliser `apply from: "$rootDir/common.gradle"` | Utiliser `apply plugin: "kei.plugins.extension.legacy"` |
| Oublier d'incrémenter `extVersionCode` et `code` | Toujours incrémenter les deux à chaque mise à jour |
| Pusher vers `keiyoushi/extensions-source` | Pusher uniquement vers son propre repo |
| Nommer l'icône `ic_launcher.png` dans le repo | Nommer l'icône avec le nom du package complet |
| Utiliser `java.net.URLEncoder.encode()` pour encoder les chemins | Encoder manuellement : `.replace(" ", "%20")` etc. |
| Construire l'URL d'image avec un pattern fixe (`P00001.jpg`) | Récupérer les chemins complets via `/api/r2/list` |
| Réutiliser un numéro de version déjà publié | Toujours incrémenter — un numéro brûlé est inutilisable |
| Revenir à une version antérieure dans `index.min.json` | Toujours aller vers l'avant (ex: 1.4.23→1.4.26 brûlées → passer à 1.4.27) |
| Appeler `/api/series/{id}/issues` pour un comic de type `run` | Détecter `source_type` et appeler `/api/runs/{runId}` |
| Parser `frenchEditions` directement pour tous les comics | Vérifier si la réponse contient `sections` (runs) ou `frenchEditions` (séries) |
| Chercher les runs uniquement dans `/api/series` | Chercher en parallèle dans `/api/series`, `/api/french-editions` ET les collections hors-périodes |
| Supprimer le bloc `// Aucun filtre` en bas de `fetchSearchManga` | Toujours conserver le fallback vers `popularMangaRequest` |
| Mettre deux conditions sur deux lignes dans un `.filter {}` sans `&&` | Toujours utiliser `&&` explicitement entre les conditions |
| Afficher les numéros spéciaux (`.DEATHS`, `.MU`) comme résultats | Filtrer les IDs contenant un `.` dans `seriesSearch` |
| Appeler `/api/french-editions?periodName=dc_black_label` | Vérifier d'abord via `/api/periods` — les collections hors-API se scrappent depuis le `__NEXT_DATA__` |
| Assigner `thumbnail_url = null` dans `mangaDetailsParse` | Ne pas toucher à `thumbnail_url` si on n'a pas de meilleure valeur à mettre |
| Comparer des titres accentués avec `.lowercase().contains()` | Utiliser `normalizeAccents()` des deux côtés de la comparaison |

---

*Guide rédigé suite à la création de l'extension Comics Tracker — Mai 2026*
*Mis à jour suite au débogage de l'encodage URL et des chemins d'images — Juin 2026*
*Mis à jour suite à l'ajout du support des runs, correction de la recherche et filtrage des numéros spéciaux (v1.4.14) — Juin 2026*
*Mis à jour suite au support des collections hors-périodes (DC Black Label, Must-Have), normalisation des accents et correction des covers (v1.4.27) — Juin 2026*