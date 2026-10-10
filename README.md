# Atelier RA — prototype ARCore

Prototype R&D : on vise un marqueur imprimé collé sur un équipement, et l'appli affiche
sa fiche (fonction, entretien, sécurité) ainsi que des repères 3D numérotés sur les points d'entretien.

Différence avec la version web : le marqueur ne sert qu'à **démarrer**. ARCore pose une ancre
dans l'espace ; on peut ensuite perdre le marqueur de vue, tourner autour du meuble : les repères
restent en place (pastille d'état bleue « Suivi par l'espace »).

## Installer l'appli
1. Sur le téléphone, ouvrir la page **Releases** de ce dépôt → « Atelier RA — dernière version ».
2. Télécharger `AtelierRA.apk` puis l'ouvrir. Autoriser l'installation depuis le navigateur si Android le demande.
3. Au premier lancement, accepter l'installation de « Services Google Play pour la RA » si elle est proposée,
   puis l'accès à la caméra.

Chaque modification poussée sur la branche `main` recompile l'APK automatiquement (onglet **Actions**).
Une nouvelle version s'installe par-dessus l'ancienne.

## Utilisation
- Imprimer les marqueurs (8 cm conseillé, 5 cm expérimental) et régler la taille dans ⚙.
- Approcher le téléphone jusqu'à ce que le marqueur occupe environ un quart de l'écran.
- Cadre **vert** : le marqueur est vu. Cadre **bleu** : suivi par l'espace, marqueur hors champ.
- Onglet Entretien : toucher un point pour faire clignoter le repère correspondant.
- ⚙ → « Réinitialiser le suivi » si les repères ont dérivé.

## Modifier le contenu
Tout est dans `app/src/main/assets/machines.json`. Positions `pos_cm` mesurées depuis le **centre
du marqueur** posé à plat :
- `x` : vers la droite du marqueur (+)
- `y` : au-dessus de la surface (+), en dessous (−)
- `z` : vers le bas du marqueur, côté opposé au bandeau « HAUT » (+)

Pour ajouter un équipement : ajouter son image dans `assets/cibles/` (riche en détails, non répétitive)
et une entrée dans `machines.json`.

## Technique
Kotlin, ARCore 1.56 (Augmented Images + ancres), OpenGL ES 2.0.
Les classes de `fr.atelier.ra.common` proviennent des exemples officiels ARCore de Google (licence Apache 2.0).
