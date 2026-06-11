# Comics Tracker — Extension Tachimanga

<p align="center">
  <img src="https://img.shields.io/badge/version-1.4.7-yellow?style=for-the-badge">
  <img src="https://img.shields.io/badge/langue-Fran%C3%A7ais-blue?style=for-the-badge&logo=apple">
  <img src="https://img.shields.io/badge/plateforme-iOS-black?style=for-the-badge&logo=apple">
  <img src="https://img.shields.io/badge/Tachimanga-compatible-orange?style=for-the-badge">
  <img src="https://img.shields.io/badge/contenu-Marvel%20%7C%20DC%20%7C%20Ind%C3%A9pendants-red?style=for-the-badge">
</p>

Extension non officielle pour lire les comics VF depuis [comics-tracker.net](https://comics-tracker.net) dans l'app **Tachimanga** (iOS).

Contenu disponible : Marvel, DC Comics, indépendants (Invincible, The Boys, etc.) — entièrement en **français (VF)**.

---

## Installer l'extension dans Tachimanga

1. Ouvre **Tachimanga** sur ton iPhone
2. Va dans `Browser` → `Extensions` → `Repositories`
3. Ajoute cette URL de dépôt :

```
https://raw.githubusercontent.com/FilloyLuca/Comics-Tracker/repo/index.min.json
```

4. Retourne dans `Extensions`, cherche **Comics Tracker** et installe-la

---

## Fichiers du repo

| Fichier | Lien direct |
|---|---|
| Index du dépôt | [index.min.json](https://raw.githubusercontent.com/FilloyLuca/Comics-Tracker/repo/index.min.json) |
| APK de l'extension | [tachiyomi-fr.comicstracker-v1.4.7-debug.apk](https://raw.githubusercontent.com/FilloyLuca/Comics-Tracker/repo/apk/tachiyomi-fr.comicstracker-v1.4.7-debug.apk) |

---

## Sources disponibles

| Source | Langue | Contenu |
|---|---|---|
| Comics Tracker | 🇫🇷 Français | Marvel, DC, Indépendants (VF) |

---

## Changelog

### v1.4.7 (actuelle)
- Ajout des **Articles & Guides** — accessibles via le filtre "Type de contenu"
- Chaque article s'affiche avec un aperçu et un lien vers comics-tracker.net

### v1.4.6 (non publiée — fusionnée dans v1.4.7)
- Ajout des **filtres dynamiques** par éditeur (Marvel / DC / Indépendants) et par période
- Les périodes sont chargées automatiquement depuis l'API — se mettent à jour sans modifier le code

### v1.4.5
- Ajout des **nouveautés** — affiche les derniers comics ajoutés sur comics-tracker.net
- Ajout d'un **message clair** quand un comic n'est pas disponible en VF au lieu d'une erreur 404

### v1.4.4
- Correction de la recherche — les résultats sont maintenant trouvés sur toutes les pages de l'API, pas uniquement la première

### v1.4.3
- Correction majeure de la lecture des pages — utilisation de l'API `/r2/list` pour récupérer la liste exacte des pages
- Tous les formats de pages sont maintenant supportés (`P00001.jpg`, `001/001.jpg`, pages doubles `003-004/003-004.jpg`...)
- Correction des comics comme **The Boys** qui n'affichaient pas leurs pages correctement
- Plus de limite arbitraire à 200 pages — le nombre exact de pages est récupéré depuis le serveur

### v1.4.2
- Correction du bouton de redirection web — pointe maintenant vers la bonne page du comic
- Amélioration de la navigation entre les séries

### v1.4.1
- Version initiale
- Liste et recherche de séries
- Lecture des comics VF disponibles
- Sauvegarde en bibliothèque

---

## Remarques

- Seuls les comics disposant d'une **édition française** sur comics-tracker.net sont lisibles — les séries sans VF affichent un message explicatif
- La recherche fonctionne par nom de série
- Extension non officielle, non affiliée à comics-tracker.net