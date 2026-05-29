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
5. **Le site utilise-t-il un endpoint de listing des fichiers ?** → Inspecter l'onglet Network → Fetch/XHR dans les DevTools pendant la lecture

### Pour Comics Tracker
- Liste des séries : `https://api.comics-tracker.net/api/series?page=N` → retourne un tableau JSON d'IDs
- Détails d'une série : `https://api.comics-tracker.net/api/series/{id}/issues` → retourne `frenchEditions`
- Liste des pages d'un tome : `https://api.comics-tracker.net/api/r2/list?prefix={link}` → retourne la liste exacte des fichiers
- Images de lecture : `https://images.comics-tracker.net/{path}` (path retourné par r2/list)
- Couvertures : `https://api.comics-tracker.net/api/issues/{id}?w=400`

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

### Astuce : récupérer les pages via un endpoint de listing
Si le site expose un endpoint listant les fichiers d'un dossier (comme `/api/r2/list`), il vaut mieux l'utiliser plutôt que de générer les URLs à l'aveugle — certains comics ont des formats de pages non standards (sous-dossiers, pages doubles, etc.).

```kotlin
override fun pageListParse(response: Response): List<Page> {
    val files = json.parseToJsonElement(response.body.string()).jsonArray
    return files
        .map { it.jsonPrimitive.content }
        .filter { it.endsWith(".jpg") || it.endsWith(".png") || it.endsWith(".webp") }
        .sorted()
        .mapIndexed { index, path -> Page(index, "", "$imagesUrl/$path") }
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

> 💡 Si **Par URL** échoue, essayer **Par nom** en tapant le nom du repo GitHub directement.

---

## 8. Mettre à jour une extension

1. Modifier le code dans `extensions-source`
2. Incrémenter `extVersionCode` dans `build.gradle` (ex: `2` → `3`)
3. Recompiler avec `.\gradlew.bat`
4. Uploader le nouvel APK dans `apk/` de la branche `repo`
5. Mettre à jour `index.min.json` : incrémenter `"code"` et `"version"`
6. Supprimer l'ancien APK du repo (optionnel mais recommandé)
7. Dans Tachimanga : **désinstaller** l'extension et la **réinstaller** pour forcer la mise à jour

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

### ❌ Certains comics affichent erreur 404 à la lecture
**Cause** : Les pages ne suivent pas toujours le format `P00001.jpg` — certains comics utilisent des sous-dossiers (`001/001.jpg`, `003-004/003-004.jpg`...).
**Solution** : Utiliser l'endpoint `/api/r2/list?prefix=` pour récupérer la liste exacte des pages depuis le serveur au lieu de générer les URLs à l'aveugle.

### ❌ Tachimanga affiche une ancienne version (cache)
**Cause** : Tachimanga met en cache le `index.min.json`.
**Solution** : Supprimer et réajouter le repo dans Tachimanga + vider le cache via Paramètres → Avancé → Vider le cache.

### ❌ "Échec de la récupération des données — le dépôt est inexistant" après réinstallation
**Cause** : Tachimanga garde en cache l'ancien repo même après suppression.
**Solution** : Deux options :
- **Désinstaller** complètement l'extension puis la réinstaller
- Ajouter le repo **Par nom** (taper le nom du repo GitHub) au lieu de **Par URL**

### ❌ L'extension reste sur une ancienne version après mise à jour
**Cause** : Tachimanga met en cache l'APK installé.
**Solution** : Désinstaller l'extension, vider le cache via **Paramètres → Avancé → Vider le cache**, puis réinstaller.

### ❌ L'icône n'apparaît pas
**Cause** : Mauvais nom de fichier ou mauvais emplacement.
**Solution** : Nommer l'icône `eu.kanade.tachiyomi.extension.[lang].[pkg].png` et la placer dans `icon/` de la branche `repo`.

### ❌ `remote: Permission denied` lors du push
**Cause** : Tentative de push vers `keiyoushi/extensions-source` (repo officiel).
**Solution** : Pusher uniquement vers son propre repo GitHub.

### ❌ Comics sans chapitres
**Cause** : La série n'a pas d'édition française sur le site (`frenchEditions: []`).
**Solution** : Comportement normal — ces comics ne sont pas disponibles en VF.

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
| Générer les URLs de pages à l'aveugle (`P00001.jpg`...) | Utiliser l'endpoint `/api/r2/list` si disponible |
| Mettre à jour sans désinstaller/réinstaller l'extension | Toujours désinstaller/réinstaller pour forcer la mise à jour |

---

*Guide rédigé suite à la création de l'extension Comics Tracker — Mai 2026*