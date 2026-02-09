# Feature Specification: TripatEvoleOne — Exploration Semantique sans Friction

**Feature Branch**: `tripatevoleOne`
**Created**: 2026-02-08
**Updated**: 2026-02-09
**Status**: In Progress — Pivot UX en cours
**Constitution**: Tripat v2.0.0

---

## Vision

### Le dilemme fondamental

Le clavier swipe (type SwiftKey) est extremement performant pour la **saisie rapide d'idees connues**. L'utilisateur sait ce qu'il veut dire, le swipe accelere la frappe. Mais il manque une capacite cruciale : **visualiser et choisir des alternatives proches ou nuancees** qui permettraient d'etre plus inspire, plus precis, ou plus creatif.

### Deux modes cognitifs, un seul outil

L'utilisateur alterne en permanence entre deux etats mentaux :

```
MODE FLUIDE                          MODE REFLEXIF
"Je sais ce que je veux dire"        "Je cherche le mot juste"
                                     "Je cherche l'idee suivante"
     │                                    │
     │  Swipe rapide                      │  Exploration semantique
     │  Suggestion frequentielle          │  Associations, nuances
     │  Optimisation du debit             │  Divergence creative
     │                                    │
     └── Ne PAS interrompre ──────────────└── Aider SANS casser le flow
```

### Principe directeur : extension continue, pas basculement

**L'exploration semantique doit etre une extension naturelle de la suggestion, pas un mode alternatif.** L'utilisateur ne devrait jamais avoir a "quitter" le clavier pour explorer — l'exploration doit s'integrer progressivement dans l'interface de saisie.

---

## Non-Negotiable Objectives

1. **Zero interruption du flow** : L'aide semantique ne doit JAMAIS interrompre un utilisateur en train de taper vite. Elle n'apparait que quand l'utilisateur la cherche ou fait une pause.
2. **Privacy First** : Zero donnee hors du device. Tout tourne en local (FastText, ACP, modeles).
3. **No Regression** : Le clavier swipe/AZERTY reste la methode d'input primaire. L'exploration est un supplement, pas un remplacement.
4. **API Compatibility** : Android API 26-35.
5. **Performance** : Toute computation semantique doit etre async (off main thread). Le clavier ne lag JAMAIS.
6. **Lisibilite** : Toute information semantique affichee doit etre immediatement comprehensible.

---

## Architecture UX : 3 niveaux de profondeur semantique

L'exploration semantique s'organise en 3 niveaux de profondeur croissante, accessibles par des gestes de plus en plus deliberes. Chaque niveau preserve le contexte du niveau precedent.

### Niveau 1 : Barre de suggestions enrichie (ambient)

**Declencheur** : Toujours visible pendant la frappe (remplace la barre de suggestions classique).

```
┌──────────────────────────────────────────────────┐
│  [exact]  │  [nuance proche]  │  [distant inspirant] │
│  bonjour  │  salut            │  bienvenue           │
│           │                   │                      │
│  ← frequence               semantique →              │
└──────────────────────────────────────────────────┘
```

**Principe** : La barre de 3 suggestions n'affiche plus 3 synonymes frequentiels mais un **gradient semantique** :
- **Slot 1** (gauche) : suggestion la plus probable (frequence + contexte), comme aujourd'hui
- **Slot 2** (centre) : variante proche mais nuancee (proximite semantique moyenne)
- **Slot 3** (droite) : alternative plus distante, potentiellement inspirante (proximite semantique faible mais coherente)

**Calcul** : Les 3 suggestions sont extraites du voisinage FastText du dernier mot, filtrees par coherence avec le contexte de la phrase. Le slot 1 reste frequentiel, les slots 2-3 sont semantiques.

**Impact flow** : Nul. La barre est deja la, seul son contenu change.

### Niveau 2 : Eventail semantique (pull-up)

**Declencheur** : Pull-up (glissement vers le haut) sur la barre de suggestions, OU pause de frappe >1.5 secondes.

```
                    ┌─────────────────────┐
                   ╱  arc de 8-12 mots     ╲
                  ╱   disposes en eventail   ╲
                 ╱    selon proximite ACP      ╲
                ╱                                ╲
┌──────────────────────────────────────────────────┐
│  [exact]  │  [nuance proche]  │  [distant]       │
└──────────────────────────────────────────────────┘
│                   CLAVIER                         │
```

**Principe** : Un arc de mots (8-12) s'ouvre au-dessus de la barre, projetes par ACP dans un demi-cercle. La position angulaire encode l'axe semantique principal :
- **Gauche de l'arc** : un pole semantique (ex: concret, quotidien, froid)
- **Droite de l'arc** : le pole oppose (ex: abstrait, solennel, chaud)
- **Distance au centre** : force de la correlation semantique

**Les axes comme questions** : L'axe principal (PC1) est interprete et affiche comme une **question implicite** au-dessus de l'eventail :

```
        ← cuisine domestique ... gastronomie →
                    ┌──────────┐
                   ╱  casserole  repas  diner  ╲
                  ╱   soupe    MANGER   festin   ╲
                 ╱    grignoter   deguster  banquet ╲
```

L'utilisateur ne navigue pas dans un nuage abstrait — il **repond a une question semantique** en choisissant un mot le long de l'axe.

**Interaction** :
- Tap sur un mot → insertion + retour au clavier
- Multi-pinch rotation → pivoter vers un autre axe semantique (PC3/PC4), revelant une nouvelle "question" (ex: "besoin vital ↔ plaisir social")
- Swipe down → fermer l'eventail

**Impact flow** : Minimal. L'eventail apparait au-dessus du clavier, le clavier reste visible et utilisable. Taper une lettre ferme automatiquement l'eventail.

### Niveau 3 : Graphe S-V-O (exploration profonde)

**Declencheur** : Pull-up prolonge (>300ms) ou double-tap sur la barre de suggestions. Geste delibere = l'utilisateur VEUT explorer.

```
┌──────────────────────────────────────────────────┐
│  SUJET          │  ACTION          │  OBJET       │
│                 │                  │              │
│   ○ animal      │   ○ devorer      │   ○ proie    │
│  ○ predateur    │  ● MANGER       │  ○ repas     │
│   ○ chat        │   ○ savourer    │   ○ plat     │
│  ○ convive      │   ○ cuisiner    │  ○ dessert   │
│                 │                  │              │
│  ← PC1: sauvage ... domestique →  │              │
└──────────────────────────────────────────────────┘
```

**Principe** : Le graphe tri-zone Sujet-Verbe-Objet (implemente en Phase 2) en plein ecran. L'utilisateur compose visuellement des combinaisons S-V-O : c'est de la **combinatoire creative assistee**. Chaque zone est une ACP independante dans sa categorie grammaticale.

**Les axes comme questions (tri-zone)** : Chaque zone affiche son axe semantique principal :
- Zone SUJET : "← individu ... collectif →"
- Zone ACTION : "← subir ... agir →"
- Zone OBJET : "← concret ... abstrait →"

**Mode bilingue** : Les noeuds FR et EN sont affiches simultanement avec des couleurs distinctes (FR bleu, EN rouge). Permet la fonctionalite de **neologisme multilingual** : l'utilisateur selectionne 2 mots de langues differentes pour generer un mot-valise bilingue.

**Interaction** :
- Tap sur un noeud dans une zone → cette zone se recentre sur le mot choisi (les 2 autres zones conservent leur etat)
- Multi-pinch → rotation ACP par zone
- Tap sur un noeud + swipe down → insertion du mot et retour au clavier
- Toute frappe clavier → fermeture immediate du graphe

---

## Declenchement intelligent : respecter le flow

### Quand NE PAS afficher l'aide semantique

- L'utilisateur tape a >40 mots/minute (mode fluide detecte)
- L'utilisateur est en train de swiper (geste en cours)
- Le champ de saisie est un mot de passe ou un champ sensible
- Moins de 2 mots ont ete tapes dans la phrase (pas assez de contexte)

### Quand afficher l'aide semantique

| Signal                          | Niveau declenche | Justification                              |
|---------------------------------|------------------|--------------------------------------------|
| Frappe normale                  | Niveau 1         | Barre enrichie toujours visible            |
| Pause >1.5s apres validation    | Niveau 2         | L'utilisateur cherche le mot suivant       |
| Pull-up sur barre suggestions  | Niveau 2         | Geste intentionnel d'exploration           |
| Pull-up prolonge (>300ms)      | Niveau 3         | Geste delibere = exploration profonde      |
| Double-tap barre suggestions   | Niveau 3         | Raccourci explicite                        |

### Auto-dismiss

- Niveau 2 : se ferme apres 3s sans interaction, ou si l'utilisateur tape une lettre
- Niveau 3 : se ferme apres 5s sans interaction, ou si l'utilisateur tape une lettre
- Tout niveau : swipe down pour fermer explicitement

---

## Fondements mathematiques

### Pourquoi l'ACP produit des axes semantiques

Les vecteurs FastText (100D, pre-entraines par skip-gram) encodent la semantique distributionnelle : des mots apparaissant dans des contextes similaires ont des vecteurs proches. L'arithmetique vectorielle a du sens (`roi - homme + femme ≈ reine`).

Quand on fait l'ACP sur un voisinage de k mots :
1. On centre les vecteurs (soustrait la moyenne)
2. La matrice de covariance capture comment les mots varient ensemble dans les 100 dimensions
3. Les eigenvectors de plus grande eigenvalue = directions de **variance maximale**
4. Ces directions **sont** les axes semantiques dominants du voisinage

**Les eigenvectors sont des questions** : PC1 pour "manger" pourrait etre l'axe `cuisine domestique ↔ gastronomie`. PC2 pourrait etre `besoin vital ↔ plaisir social`. Chaque paire d'eigenvectors revele une dimension de sens.

### Format `.uvec` (Urik VECtor)

Format binaire compact pour embarquer les vecteurs FastText dans les assets (~13 MB/langue) :

```
Header (16 octets) :
  - magic: 4 bytes = "UVEC"
  - dimension: u16 (100)
  - word_count: u32
  - reserved: 6 bytes

Vocabulary section :
  - Pour chaque mot: [u16 length][utf8 bytes]
  - Trie alphabetiquement pour recherche binaire

Vector section :
  - float16 contigus, chaque mot = dimension entrees
  - Vecteurs L2-normalises a l'export → cosinus = dot product
```

### Stores categoriels (POS-split)

```
fasttext_{lang}.uvec          → store complet (60k mots)
fasttext_{lang}_nouns.uvec    → noms + noms propres (~52k)
fasttext_{lang}_verbs.uvec    → verbes (~8k)
```

Split par heuristique morphologique (`--split-uvec`) ou par spaCy (`--pos-split`).
Vecteurs pre-alignes MUSE au chargement pour k-NN bilingue en dot product direct.

---

## Architecture technique

### Moteur FastText (`ml/FastTextEngine.kt`)

- Chargement paresseux et parallele des `.uvec` (4 stores bilingues en ~800ms)
- Pre-alignement MUSE au chargement (dot product direct sans multiplication matricielle runtime)
- Recherche k-NN bilingue : cherche l'ancre dans les 2 langues automatiquement
- Vecteur ancre : fallback 6 niveaux (2 langues × 3 categories)
- Gestion pression memoire : decharge verbes, puis noms secondaires

### Projecteur ACP (`ml/PcaProjector.kt`)

- Power iteration (25 iters) pour les 2 eigenvectors principaux (~1-5ms)
- `project()` : projection single-graph [0.1, 0.9]
- `projectToRegion()` : projection dans une bande d'ecran arbitraire
- `projectTrigram()` : 3 ACP independantes pour S-V-O
- Rotation multi-pinch : re-projection sur paires d'eigenvectors secondaires

### Pipeline de conversion (`tools/convert_fasttext.py`)

| Mode | Commande | Description |
|------|----------|-------------|
| Standard | `--input .vec --output .uvec` | Conversion .vec → .uvec avec PCA truncation |
| MUSE | `--convert-muse --input .pth` | Matrice alignement → float16 binary |
| POS spaCy | `--pos-split --lang fr` | Split par POS via spaCy (requiert spaCy) |
| POS morpho | `--split-uvec --lang fr` | Split par heuristique morphologique (zero dep) |

### Architecture threads

```
Thread UI (Main)              Thread Compute (Dispatchers.Default)
┌──────────────────────┐     ┌──────────────────────────────────┐
│ Touch events         │     │ FastText k-NN (bilingue)         │
│ Gesture detection    │     │ ACP projection (power iteration) │
│ Overlay rendering    │────>│ Morpho split / POS classification│
│ Cross-fade animation │<────│ Trigram S-V-O orchestration      │
│ 60fps Canvas draw    │     │ Axe semantique interpretation    │
└──────────────────────┘     └──────────────────────────────────┘
                                        │
                              Thread IO (Dispatchers.IO)
                             ┌──────────────────────────┐
                             │ Chargement .uvec assets   │
                             │ Pre-alignement MUSE       │
                             │ Room/SQLite (historique)   │
                             └──────────────────────────┘
```

Regle absolue : **le Thread UI ne fait AUCUN calcul vectoriel**. Tout est sur Default/IO.

---

## Phases d'implementation

### Phase 0 : Overlay + Cross-Fade (VALIDEE)

Superposition technique overlay transparent + cross-fade fluide entre clavier et graphe.
Donnees mock, validation du pipeline d'affichage.

### Phase 1 : Moteur FastText + ACP + Bilingue (VALIDEE)

Vrais embeddings FastText projetes en 2D via ACP. Support bilingue FR/EN via MUSE.
Neologismes par multi-touch. Format `.uvec`. Graphe single-anchor.

### Phase 2 : Trigram S-V-O (VALIDEE — debug en cours)

Graphe tri-zone Sujet-Verbe-Objet. POS-split morphologique. Pre-alignement MUSE.
Recherche k-NN bilingue avec fallback multi-langue. Calcul off-thread (Dispatchers.Default).

**Bugs resolus pendant le debug 2026-02-09 :**
- FileNotFoundException sur stores categoriels manquants → fallback gracieux vers single-graph
- ANR cause par k-NN sur main thread → migration vers Dispatchers.Default
- Zone VERB vide → fix recherche ancre bilingue (cherche dans les 2 langues)
- Performance 20s → <100ms par pre-alignement MUSE au chargement

### Phase 3 : Barre de suggestions enrichie (A FAIRE)

**Objectif** : Remplacer la barre de 3 suggestions frequentielles par un gradient semantique.

**Implementation** :
- Slot 1 : suggestion frequentielle existante (pas de changement)
- Slot 2 : voisin FastText de distance semantique moyenne, filtre par coherence contextuelle
- Slot 3 : voisin FastText distant mais thematiquement coherent
- Calcul async sur Dispatchers.Default, affichage non bloquant

**Criteres de passage** :
- Les 3 suggestions sont visuellement distinctes (opacite decroissante gauche→droite)
- Le slot 1 reste aussi rapide qu'avant (pas de regression de latence)
- Les slots 2-3 se remplissent en <50ms apres le slot 1

### Phase 4 : Eventail semantique + Axes comme questions (A FAIRE)

**Objectif** : Pull-up sur la barre pour reveler un arc de 8-12 mots avec interpretation de l'axe ACP.

**Implementation** :
- Detection pull-up sur la barre de suggestions (seuil 40dp)
- Projection ACP en demi-cercle (180 degres) au-dessus de la barre
- Interpretation automatique de PC1 : extraction des 2 mots extremes sur l'axe comme labels
- Affichage de l'axe : `← mot_pole_A ... mot_pole_B →`
- Multi-pinch rotation → changement d'axe avec animation de transition
- Auto-dismiss sur frappe ou timeout 3s

**Criteres de passage** :
- L'eventail s'ouvre en <200ms apres le pull-up
- L'axe semantique affiche est pertinent (validation manuelle sur 20 mots ancre)
- Le clavier reste utilisable sous l'eventail (tap lettre → ferme eventail + saisit lettre)
- La rotation multi-pinch revele un axe sementiquement different de PC1

### Phase 5 : Declenchement intelligent (A FAIRE)

**Objectif** : Detecter automatiquement le mode cognitif de l'utilisateur.

**Implementation** :
- Mesure du debit de frappe (mots/minute) sur fenetre glissante de 10s
- Seuil mode fluide : >40 mots/min → desactive eventail automatique
- Seuil mode reflexif : pause >1.5s apres validation → ouvre eventail automatiquement
- Exclusion champs sensibles (TYPE_TEXT_VARIATION_PASSWORD)

### Phase 6 : Neologisme bilingue en mode trigram (A FAIRE)

**Objectif** : Permettre la creation de neologismes multilingues dans le graphe S-V-O.

**Implementation** :
- Affichage bilingue dans chaque zone (noeuds FR bleus, EN rouges)
- Selection de 2 noeuds de langues differentes (multi-touch)
- Disambiguation : angle rotation < 5° = neologisme, >= 5° = rotation ACP
- 3 strategies de fusion : Portmanteau, Syllable Blend, Morpheme Mix
- Score de naturalite : penalise clusters consonnes >3

---

## Architecture Dictionnaire & Apprentissage Contextuel

### Stockage actuel (Room/SQLite)

```
Table: learned_words
├── word, word_normalized, language_tag
├── frequency, source, character_count
├── created_at, last_used
Index: idx_exact_lookup (language_tag, word_normalized) UNIQUE
```

### Historique conversationnel local (Phase future)

```
Table: conversation_context (nouvelle)
├── session_id, word, preceding_word
├── language_tag, app_package, timestamp
├── input_method (typed, swiped, selected, semantic_graph, eventail)
```

**Exploitation** :
- Bigrammes utilisateur pour ponderer les aretes du graphe
- Frequence par app (messaging vs email) pour adapter les suggestions
- `input_method = eventail` / `semantic_graph` trace les mots choisis via exploration
- Modele de co-occurrence utilisateur superpose aux vecteurs FastText statiques

**Privacy** : Aucune permission supplementaire. Option parametrable. Retention configurable. Exclusion champs sensibles. Pas de stockage de phrases entieres.

---

## Extensibilite

### AR/XR (future-proofing)

- 2D actuel → 3D via projection sur 3 eigenvectors (Android XR SDK / ARCore)
- L'eventail devient une sphere explorable en 3D
- Le multi-pinch devient rotation 3D libre
- Changement de parametre, pas d'architecture

### Limites de FastText et pistes d'evolution

FastText capture la **proximite distributionnelle** (mots dans les memes contextes). C'est puissant pour les synonymes et champs lexicaux mais limite pour :
- **Associations metaphoriques** : "glacial" → solitude (connexion poetique inter-domaine)
- **Pragmatique** : intention de l'utilisateur, registre de langue

**Pistes** :
- Gemma 3 Nano (AI Edge SDK) pour enrichir les axes avec des interpretations contextuelles
- Fine-tuning sur des corpus specifiques (poetique, technique, etc.)
- Modele de co-occurrence utilisateur pour personnaliser les axes

---

## Success Criteria

- **SC-001** : Le clavier ne lag JAMAIS — zero frame drop pendant la frappe swipe, quel que soit le niveau d'exploration actif
- **SC-002** : La barre enrichie (Niveau 1) affiche 3 suggestions de qualite croissante en <50ms
- **SC-003** : L'eventail (Niveau 2) s'ouvre en <200ms et affiche un axe semantique pertinent
- **SC-004** : Le graphe trigram (Niveau 3) affiche 3 zones peuplees en <100ms (hors chargement initial)
- **SC-005** : L'utilisateur peut passer du Niveau 0 (frappe pure) au Niveau 3 (exploration profonde) et retour en <1s total
- **SC-006** : Le declenchement intelligent ne montre JAMAIS l'eventail quand l'utilisateur tape a >40 mots/min
- **SC-007** : L'axe semantique (PC1) affiche est jugé pertinent dans >80% des cas (validation manuelle)
- **SC-008** : Le mode bilingue affiche FR et EN avec couleurs distinctes dans chaque zone du trigram
- **SC-009** : Zero donnee transmise hors du device
- **SC-010** : Chaque phase de qualification documentee avec metriques mesurees sur device cible
