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

## Fonctions
- **Fiche équipement** en 4 onglets : Fonction · Entretien · Sécurité · Journal.
- **Masquer ▾** cache la fiche ; elle revient d'elle-même quand on revise un marqueur (ou via le bouton ⓘ).
- **Tournée d'entretien** : cocher chaque point fait (le repère 3D passe au vert), puis « Valider la tournée ».
- **Procédure de sécurité guidée** : une étape à la fois, avec le repère concerné mis en évidence.
- **Journal** des tournées et procédures, partageable (mail, messagerie…).
- **Toucher un repère 3D** ouvre sa ligne dans l'onglet Entretien.
- **Flèche de guidage** au bord de l'écran quand le repère sélectionné est hors champ, avec la distance.
- **Mode placement** (⚙) : toucher 📍 puis l'endroit réel pour positionner un repère ; « Exporter les positions » renvoie le fichier `machines.json` à jour.
- **Avis de mise à jour** : un bandeau signale qu'une nouvelle version est disponible.

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
