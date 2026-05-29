# Comics Tracker — Extension Tachimanga
https://filloyluca.github.io/Comics-Tracker/

<p align="center">
  <img src="https://img.shields.io/badge/version-1.4.4-yellow?style=for-the-badge">
  <img src="https://img.shields.io/badge/langue-Fran%C3%A7ais-blue?style=for-the-badge&logo=data:image/png;base64,">
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
https://raw.githubusercontent.com/FilloyLuca/Comics-Tracker/main/index.min.json
```

4. Retourne dans `Extensions`, cherche **Comics Tracker** et installe-la

---

## Fichiers du repo

| Fichier | Lien direct |
|---|---|
| Index du dépôt | [index.min.json](https://raw.githubusercontent.com/FilloyLuca/Comics-Tracker/main/index.min.json) |
| APK de l'extension | [tachiyomi-fr.comicstracker-v1.4.4-debug.apk](https://raw.githubusercontent.com/FilloyLuca/Comics-Tracker/main/tachiyomi-fr.comicstracker-v1.4.4-debug.apk) |

---

## Sources disponibles

| Source | Langue | Contenu |
|---|---|---|
| Comics Tracker | 🇫🇷 Français | Marvel, DC, Indépendants (VF) |

---

## Changelog

v1.4.1

Version initiale
Liste et recherche de séries
Lecture des comics VF disponibles
Sauvegarde en bibliothèque

v1.4.2

Correction du bouton de redirection web — pointe maintenant vers la bonne page du comic

Amélioration de la navigation entre les séries

v1.4.3

Correction majeure de la lecture des pages — utilisation de l'API /r2/list pour récupérer la liste exacte des pages

Tous les formats de pages sont maintenant supportés (P00001.jpg, 001/001.jpg, pages doubles 003-004/003-004.jpg...)

Correction des comics comme The Boys qui n'affichaient pas leurs pages correctement

Plus de limite arbitraire à 200 pages — le nombre exact de pages est récupéré depuis le serveur

v1.4.4 (actuelle)

Correction de la recherche — les résultats sont maintenant trouvés sur toutes les pages de l'API, pas uniquement la première

---

## Remarques

- Extension en cours de développement
- Les pages sont générées jusqu'à 200 par tome (les 404 sont ignorées automatiquement)
- La recherche fonctionne par nom de série
