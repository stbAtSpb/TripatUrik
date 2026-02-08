# Feature Specification: TripatEvoleOne - Clavier Concentrique Contextuel

**Feature Branch**: `tripatevoleOne`
**Created**: 2026-02-08
**Status**: Draft
**Constitution**: Tripat v1.0.0

## Vision

Remplacer le layout AZERTY classique par un **clavier concentrique** organise en anneaux concentriques autour de la lettre saisie. Chaque anneau represente un niveau d'abstraction croissant, des lettres brutes jusqu'aux humeurs et actions contextuelles. Les bulles s'auto-arrangent en temps reel pour eviter la superposition, et le contexte de la phrase guide les suggestions a chaque niveau.

### Architecture des anneaux

```
Anneau 0 (centre)  : Lettre saisie / point de frappe
Anneau 1 (lettres) : Lettres statistiquement les plus probables comme prochain caractere
Anneau 2 (mots)    : Mots les plus frequents / probables (bubbles)
Anneau 3 (abstraits): Mots plus abstraits / semantiquement lies au contexte
Anneau 4 (concepts) : Concepts a niveau d'abstraction superieur
Anneau 5 (humeurs)  : Humeurs, emotions, actions frequentes liees au contexte de la phrase
```

## Non-Negotiable Objectives

1. **Privacy First**: Zero donnee hors du device. Tout le modele de prediction/contexte tourne en local.
2. **No Regression**: Le clavier AZERTY classique reste disponible comme option. L'utilisateur peut basculer entre les deux modes.
3. **API Compatibility**: Android API 26-35.
4. **Performance**: Le calcul des anneaux et l'auto-arrangement des bulles ne doivent pas provoquer de lag perceptible (<16ms par frame pour 60fps).
5. **Test-First**: Tests ecrits et approuves avant implementation.
6. **Anti-Occlusion**: Les bulles ne doivent JAMAIS se superposer. L'algorithme d'arrangement est non-negociable.
7. **Lisibilite**: Les textes dans les bulles doivent rester lisibles quelle que soit la densite de suggestions.
8. **User-Interaction Semantique (Multi-Pinch Rotation)**: Lors de la selection d'un mot dans un anneau, l'utilisateur peut effectuer un geste multi-pinch (rotation a deux doigts) pour faire tourner le referentiel des valeurs propres/vecteurs propres (eigenvalues/eigenvectors) associe a ce mot. Cette rotation pivote la projection 2D de l'espace semantique, revelant de nouvelles proximites selon un axe semantique different. Exemple : si le mot "froid" est affiche, il s'oppose a "tiede/chaud/brulant" selon l'axe semantique de temperature. Un multi-pinch permet de pivoter autour de cet axe pour reveler d'autres dimensions (ex: "froid" vers "distant/glacial/austere" sur un axe emotionnel). L'algorithme utilise des methodes d'analyse multidimensionnelle (ACP, AFD, ou equivalent optimise) pour ordonner les proximites informationnelles selon la projection portant le maximum d'information correlee (variance expliquee maximale = proximite semantique optimale).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Saisie par cercle de lettres (Priority: P1)

En tant qu'utilisateur, je veux taper du texte en touchant le centre de l'ecran clavier, puis voir apparaitre autour de mon doigt un cercle de lettres candidates (les plus probables statistiquement) que je peux selectionner par glissement.

**Why this priority**: C'est le MVP minimal - sans la saisie de base par cercle de lettres, rien d'autre ne fonctionne. Cela remplace le layout AZERTY comme methode d'input primaire.

**Independent Test**: L'utilisateur peut composer le mot "bonjour" en utilisant uniquement le cercle concentrique de lettres, sans aucun autre anneau.

**Acceptance Scenarios**:

1. **Given** le clavier concentrique est actif, **When** l'utilisateur touche la zone de saisie, **Then** un cercle de lettres candidates apparait autour du point de contact
2. **Given** le cercle de lettres est affiche, **When** l'utilisateur glisse vers la lettre 'b', **Then** la lettre 'b' est saisie et le cercle se recalcule pour les lettres probables apres 'b'
3. **Given** l'utilisateur a saisi "bon", **When** le cercle suivant apparait, **Then** les lettres 'j', 's', 'n' sont parmi les plus proches du centre (les plus probables)
4. **Given** le clavier concentrique est actif, **When** l'utilisateur veut revenir au mode AZERTY, **Then** un bouton/geste permet de basculer instantanement

---

### User Story 2 - Suggestions de mots en bulles (Priority: P2)

En tant qu'utilisateur, apres avoir tape quelques lettres, je veux voir apparaitre un 2eme anneau de bulles contenant les mots les plus probables que je peux taper pour auto-completer le mot en cours.

**Why this priority**: L'auto-completion par mots est le premier gain de productivite majeur au-dela de la simple saisie lettre par lettre.

**Independent Test**: Apres avoir tape "bon", l'utilisateur voit des bulles "bonjour", "bonne", "bonheur" dans l'anneau 2 et peut taper une bulle pour inserer le mot complet.

**Acceptance Scenarios**:

1. **Given** l'utilisateur a saisi "bon", **When** l'anneau de mots s'affiche, **Then** les bulles contiennent les mots les plus frequents commencant par "bon"
2. **Given** des bulles de mots sont affichees, **When** l'utilisateur tape sur "bonjour", **Then** le mot "bonjour" est insere dans le champ de saisie et remplace les lettres deja tapees
3. **Given** 5+ bulles de mots sont candidates, **When** elles s'affichent, **Then** aucune bulle ne chevauche une autre bulle (anti-occlusion)

---

### User Story 3 - Auto-arrangement des bulles (Priority: P3)

En tant qu'utilisateur, je veux que toutes les bulles (quel que soit l'anneau) s'arrangent automatiquement pour eviter toute superposition, avec les elements les plus pertinents les plus proches du centre.

**Why this priority**: Sans l'auto-arrangement, l'interface devient inutilisable des que le nombre de suggestions augmente. C'est le fondement de l'UX.

**Independent Test**: Afficher 20+ bulles sur 3 anneaux simultanement - aucune superposition, toutes les bulles lisibles.

**Acceptance Scenarios**:

1. **Given** plusieurs anneaux sont affiches, **When** de nouvelles suggestions arrivent, **Then** les bulles existantes se repositionnent avec une animation fluide
2. **Given** l'espace est contraint (petit ecran), **When** trop de bulles sont candidates, **Then** seules les N plus pertinentes sont affichees, avec indication qu'il y en a d'autres
3. **Given** des bulles de differentes tailles (mots courts/longs), **When** elles sont disposees, **Then** elles ne se chevauchent jamais et restent dans leur anneau

---

### User Story 4 - Suggestions contextuelles abstraites (Priority: P4)

En tant qu'utilisateur, apres avoir commence une phrase, je veux voir dans l'anneau 3 des mots semantiquement lies au contexte de ma phrase, meme s'ils ne commencent pas par les lettres tapees.

**Why this priority**: C'est le premier saut qualitatif par rapport a un auto-complete classique. L'utilisateur peut saisir des idees, pas seulement des caracteres.

**Independent Test**: Apres avoir tape "je suis content de", l'anneau 3 propose des mots comme "retrouver", "voir", "partager" bases sur le contexte semantique.

**Acceptance Scenarios**:

1. **Given** l'utilisateur a tape "je suis content de", **When** l'anneau 3 s'affiche, **Then** il contient des verbes/mots contextuellement pertinents
2. **Given** l'utilisateur selectionne "voir" dans l'anneau 3, **When** le mot est insere, **Then** tous les anneaux se recalculent pour le nouveau contexte "je suis content de voir"

---

### User Story 5 - Concepts et associations (Priority: P5)

En tant qu'utilisateur, je veux voir dans l'anneau 4 des concepts de plus haut niveau lies a ma phrase (themes, categories, associations d'idees).

**Why this priority**: Niveau d'abstraction superieur qui aide a la creativite et a l'expression d'idees complexes.

**Independent Test**: En tapant une phrase sur le voyage, l'anneau 4 propose des concepts comme "aventure", "decouverte", "culture".

**Acceptance Scenarios**:

1. **Given** l'utilisateur ecrit sur un sujet identifiable, **When** l'anneau 4 s'affiche, **Then** il propose des concepts thematiquement lies
2. **Given** le contexte change au fil de la phrase, **When** l'utilisateur continue a taper, **Then** les concepts se mettent a jour en temps reel

---

### User Story 6 - Humeurs et actions contextuelles (Priority: P6)

En tant qu'utilisateur, je veux voir dans l'anneau 5 (le plus externe) des suggestions d'humeurs, d'emotions ou d'actions frequentes liees au contexte de ma conversation.

**Why this priority**: Le dernier niveau d'abstraction - transforme le clavier en assistant de communication contextuel.

**Independent Test**: En tapant un message dans une app de messagerie, l'anneau 5 propose des emojis d'humeur, des actions comme "appeler", "envoyer photo", ou des expressions comme "a bientot".

**Acceptance Scenarios**:

1. **Given** l'utilisateur ecrit un message, **When** l'anneau 5 s'affiche, **Then** il propose des humeurs/actions coherentes avec le ton du message
2. **Given** le ton du message est joyeux, **When** les suggestions d'humeur s'affichent, **Then** elles refletent la joie (emojis, expressions positives)

---

### Edge Cases

- Que se passe-t-il quand l'utilisateur tape dans une langue non supportee ?
- Comment gerer un ecran tres petit (montres ? mode split-screen) ?
- Que se passe-t-il si le modele de prediction n'a pas assez de contexte (debut de phrase) ?
- Comment gerer la saisie de chiffres, symboles et caracteres speciaux dans le mode concentrique ?
- Que se passe-t-il si l'utilisateur tape tres vite - les anneaux se recalculent-ils a chaque lettre ou avec du debounce ?
- Comment gerer le passage entre mode concentrique et mode AZERTY en milieu de mot ?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Le systeme DOIT afficher un cercle de lettres candidates autour du point de touche
- **FR-002**: Le systeme DOIT calculer la probabilite des lettres suivantes basee sur les bigrammes/trigrammes de la langue
- **FR-003**: Le systeme DOIT afficher des bulles de mots candidats dans l'anneau 2
- **FR-004**: L'algorithme d'auto-arrangement DOIT garantir zero superposition de bulles
- **FR-005**: Le systeme DOIT mettre a jour les suggestions en temps reel a chaque caractere saisi
- **FR-006**: Le systeme DOIT permettre la bascule AZERTY <-> Concentrique a tout moment
- **FR-007**: Le systeme DOIT fonctionner avec le dictionnaire local existant (aucun acces reseau)
- **FR-008**: Les animations de repositionnement des bulles DOIVENT etre fluides (60fps)
- **FR-009**: Le systeme utilise une architecture hybride a 2 moteurs :
  - **Moteur 1 (FastText, ~50MB)** : Vecteurs de mots statiques pre-entraines pour les anneaux 1-3 et la rotation multi-pinch ACP/eigenvector. Lookup O(1), calcul ACP temps reel.
  - **Moteur 2 (Gemma 3 Nano via AI Edge SDK, optionnel)** : Pour les anneaux 4-5 (concepts abstraits, humeurs). Necessite comprehension du sens de la phrase entiere. Ajoute en phase ulterieure si performance OK sur Snapdragon 855.
- **FR-010**: Les vecteurs FastText francais DOIVENT etre compresses (quantization) pour tenir dans ~50MB en memoire
- **FR-011**: Le calcul ACP/decomposition en valeurs propres pour la rotation multi-pinch DOIT s'executer en < 16ms

### Key Entities

- **BubbleRing**: Un anneau concentrique contenant des BubbleItems a un rayon donne
- **BubbleItem**: Un element cliquable (lettre, mot, concept, humeur) avec position, taille, priorite
- **ContextEngine**: Moteur de calcul du contexte semantique de la phrase en cours
- **BubbleLayoutManager**: Algorithme d'auto-arrangement qui positionne les bulles sans superposition
- **FrequencyModel**: Modele statistique de frequence des lettres/mots (bigrammes, trigrammes)

## Architecture de Rendu & Threads

### Modele 3 threads

```
Thread UI (Main)          Thread Render (SurfaceView)     Thread Compute
┌──────────────────┐     ┌──────────────────────────┐    ┌──────────────────┐
│ Touch events     │────>│ Canvas.drawCircle/Path   │    │ FastText lookup  │
│ Gesture detection│     │ Anti-alias rendering     │    │ ACP/eigenvectors │
│ Input dispatch   │     │ Animation interpolation  │    │ Bubble positions │
│                  │     │ 60fps render loop        │<───│ Layout solving   │
└──────────────────┘     └──────────────────────────┘    └──────────────────┘
```

- **Thread UI** : Capture des touch events, detection des gestes (tap, swipe, multi-pinch). Ne fait AUCUN calcul lourd.
- **Thread Render** : SurfaceView avec boucle de rendu dediee. Dessine les bulles, anneaux, animations a 60fps. Lit les positions calculees par le thread Compute via un buffer thread-safe (double-buffering ou AtomicReference).
- **Thread Compute** : Calculs FastText (lookup vecteurs, k-NN), ACP/decomposition eigen, algorithme d'auto-arrangement. Publie les resultats dans le buffer partage. Utilise Eigen C++ via JNI pour les operations matricielles critiques.

### Choix techniques

- **SurfaceView** plutot que View/Canvas classique : rendu sur thread dedie, pas de contention avec le UI thread
- **Eigen C++ via JNI** : Bibliotheque C++ header-only pour l'algebre lineaire (ACP, SVD, eigendecomposition). Performance native, ~10x plus rapide que Java pour les matrices denses
- **Double-buffering** : Le thread Compute ecrit dans un buffer "back", le thread Render lit le buffer "front". Swap atomique quand le Compute a fini un cycle

## Approche Progressive de Qualification *(mandatory)*

L'implementation suit une approche en 4 phases incrementales. Chaque phase DOIT atteindre ses criteres de performance AVANT de passer a la suivante. Cela permet d'identifier et resoudre les goulots d'etranglement au plus tot.

### Phase 0 : Rendu Statique (Benchmark de base)

**Objectif** : Valider que le moteur de rendu SurfaceView peut dessiner N bulles a 60fps sans aucun calcul dynamique.

**Implementation** :
- Positions des bulles codees en dur (hardcoded) sur 3 anneaux
- Pas de calcul statistique, pas de FastText, pas d'ACP
- Bulles de tailles variees avec texte, sur fond colore
- Animation simple : rotation lente des anneaux (pour valider le refresh rate)

**Criteres de passage** :
- 30 bulles sur 3 anneaux : rendu constant a 60fps (mesuree via Choreographer)
- Temps de frame < 12ms (marge de 4ms pour le calcul futur)
- Pas de GC pause visible dans les logs (zero allocation dans la boucle de rendu)
- Test sur device cible (Snapdragon 855 / OnePlus 7 Pro)

**Livrables** : `ConcentricBenchmarkView.kt` + rapport de performance

### Phase 1 : Layout Dynamique (Auto-arrangement)

**Objectif** : Valider l'algorithme d'auto-arrangement des bulles en temps reel, toujours sans calcul semantique.

**Implementation** :
- Bulles avec texte aleatoire, ajoutees/retirees dynamiquement
- Algorithme BubbleLayoutManager : placement sans superposition avec contrainte d'anneau
- Animations de repositionnement (spring physics ou interpolation lineaire)
- Test de stress : ajout de 10 bulles/seconde pendant 5 secondes

**Criteres de passage** :
- Zero superposition mesuree automatiquement (test unitaire + visuel)
- Repositionnement de 20 bulles en < 8ms (budget restant pour le rendu)
- Animation fluide (pas de saut, pas de teleportation)
- Stabilite : les bulles convergent vers une position stable en < 300ms

**Livrables** : `BubbleLayoutManager.kt` + tests unitaires anti-occlusion

### Phase 2 : Calcul Asynchrone (FastText + Prediction)

**Objectif** : Integrer le calcul semantique FastText et les bigrammes/trigrammes sur le thread Compute, en validant que le pipeline async ne degrade pas le rendu.

**Implementation** :
- Chargement des vecteurs FastText compresses (~50MB) au demarrage
- Lookup k-NN pour les mots proches (anneau 2-3) sur thread Compute
- Bigrammes/trigrammes pour les lettres probables (anneau 1) sur thread Compute
- Double-buffering : le Render thread ne bloque JAMAIS en attente du Compute

**Criteres de passage** :
- Chargement FastText < 2s au cold start
- Lookup k-NN (10 voisins parmi 200k mots) < 5ms
- Le thread Render maintient 60fps meme si le Compute est en retard (affiche les anciennes positions)
- Latence input-to-display < 50ms (entre le touch et la mise a jour des bulles)

**Livrables** : `FastTextEngine.kt` + `FrequencyModel.kt` + metriques de latence

### Phase 3 : Rotation Multi-Pinch (ACP temps reel)

**Objectif** : Valider le calcul ACP/eigendecomposition en temps reel lors du geste multi-pinch, via Eigen C++ / JNI.

**Implementation** :
- Detection du geste multi-pinch (2 doigts, rotation)
- Extraction de la sous-matrice de covariance pour les K mots voisins
- Decomposition en valeurs/vecteurs propres via Eigen (JNI)
- Projection 2D selon les 2 premiers axes principaux
- Rotation de la projection en fonction de l'angle du geste
- Mise a jour des positions des bulles selon la nouvelle projection

**Criteres de passage** :
- ACP sur matrice 50x300 (50 mots, vecteurs 300D) < 10ms via Eigen/JNI
- Rotation continue (pendant le geste) a 60fps
- Les bulles se repositionnent de facon fluide selon la rotation
- Les axes semantiques reveles sont coherents (validation manuelle)

**Livrables** : `libconcentricnative.so` (Eigen JNI) + `SemanticRotationEngine.kt` + demo interactive

### Phase 4 (Optionnelle) : Gemma 3 Nano (Concepts abstraits)

**Objectif** : Integrer Gemma 3 Nano via AI Edge SDK pour les anneaux 4-5, si les performances le permettent.

**Criteres de passage** :
- Inference Gemma 3 Nano < 200ms par requete (acceptable car anneaux 4-5 sont secondaires)
- Le modele tient en memoire avec FastText (budget total < 500MB RAM)
- Pas de degradation du rendu des anneaux 1-3

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: L'utilisateur peut taper un mot de 7 lettres en moins de 10 secondes avec le mode concentrique (apres apprentissage)
- **SC-002**: Zero superposition de bulles dans 100% des cas testes
- **SC-003**: Temps de calcul des anneaux < 16ms (compatible 60fps)
- **SC-004**: Le mot correct apparait dans l'anneau 2 dans 80% des cas apres 3 lettres tapees
- **SC-005**: Basculement AZERTY <-> Concentrique en < 200ms
- **SC-006**: Phase 0 validee sur Snapdragon 855 avant tout developpement fonctionnel
- **SC-007**: Chaque phase de qualification documentee avec rapport de metriques mesure sur device
