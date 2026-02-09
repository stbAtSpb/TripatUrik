#!/usr/bin/env python3
"""
Convert FastText .vec files to compact .uvec (Urik VECtor) format.

Usage:
    python convert_fasttext.py --input cc.fr.300.vec --output fasttext_fr.uvec --dim 100 --max-words 60000
    python convert_fasttext.py --input cc.en.300.vec --output fasttext_en.uvec --dim 100 --max-words 60000

POS-split mode (generates separate noun/verb .uvec files):
    python convert_fasttext.py --input cc.fr.300.vec --pos-split --lang fr --dim 100 --max-words 60000
    python convert_fasttext.py --input cc.en.300.vec --pos-split --lang en --dim 100 --max-words 60000

MUSE alignment matrix conversion:
    python convert_fasttext.py --convert-muse --input best_mapping_fr.pth --output align_fr.bin --dim 100
    python convert_fasttext.py --convert-muse --input best_mapping_en.pth --output align_en.bin --dim 100

UVEC format (16-byte header):
    magic:      4 bytes = "UVEC"
    dimension:  2 bytes (u16, little-endian)
    word_count: 4 bytes (u32, little-endian)
    reserved:   6 bytes (zeros)

    Vocabulary section:
        For each word: [u16 length][utf8 bytes]
        Sorted alphabetically for binary search

    Vector section:
        Contiguous float16 values, each word = dimension entries
        L2-normalized at export time
"""

import argparse
import gzip
import struct
import sys
import re
import numpy as np
from pathlib import Path


def is_valid_word(word: str) -> bool:
    """Filter: keep alphabetic words with 2+ characters."""
    if len(word) < 2:
        return False
    # Allow letters (including accented), hyphens, apostrophes
    return bool(re.match(r"^[\w'-]+$", word, re.UNICODE)) and any(c.isalpha() for c in word)


def load_fasttext_vec(path: str, max_words: int, target_dim: int) -> tuple:
    """Load FastText .vec file, filter and truncate dimensions."""
    words = []
    vectors = []
    seen = set()

    print(f"Loading {path}...")
    opener = gzip.open if path.endswith('.gz') else open
    with opener(path, 'rt', encoding='utf-8', errors='replace') as f:
        header = f.readline().strip().split()
        total_words = int(header[0])
        orig_dim = int(header[1])
        print(f"  Source: {total_words} words, {orig_dim} dimensions")

        if target_dim > orig_dim:
            print(f"  WARNING: target_dim ({target_dim}) > source dim ({orig_dim}), padding with zeros")

        for line_idx, line in enumerate(f):
            if len(words) >= max_words:
                break

            parts = line.rstrip().split(' ')
            if len(parts) < orig_dim + 1:
                continue

            word = parts[0].lower()
            if not is_valid_word(word):
                continue

            # Skip duplicates (case-folded)
            if word in seen:
                continue
            seen.add(word)

            try:
                vec = np.array([float(x) for x in parts[1:orig_dim + 1]], dtype=np.float32)
            except ValueError:
                continue

            # Truncate or pad to target dimension
            if len(vec) >= target_dim:
                vec = vec[:target_dim]
            else:
                vec = np.pad(vec, (0, target_dim - len(vec)))

            words.append(word)
            vectors.append(vec)

            if (line_idx + 1) % 50000 == 0:
                print(f"  Processed {line_idx + 1} lines, kept {len(words)} words...")

    print(f"  Loaded {len(words)} valid words")
    return words, np.array(vectors, dtype=np.float32)


def l2_normalize(vectors: np.ndarray) -> np.ndarray:
    """L2-normalize each row vector."""
    norms = np.linalg.norm(vectors, axis=1, keepdims=True)
    norms[norms == 0] = 1.0  # avoid division by zero
    return vectors / norms


def write_uvec(path: str, words: list, vectors: np.ndarray, dimension: int):
    """Write .uvec binary format."""
    # Sort alphabetically for binary search
    sorted_indices = sorted(range(len(words)), key=lambda i: words[i])
    sorted_words = [words[i] for i in sorted_indices]
    sorted_vectors = vectors[sorted_indices]

    word_count = len(sorted_words)

    with open(path, 'wb') as f:
        # Header (16 bytes)
        f.write(b'UVEC')                                    # magic
        f.write(struct.pack('<H', dimension))                # dimension (u16)
        f.write(struct.pack('<I', word_count))               # word_count (u32)
        f.write(b'\x00' * 6)                                 # reserved

        # Vocabulary section
        for word in sorted_words:
            encoded = word.encode('utf-8')
            f.write(struct.pack('<H', len(encoded)))         # u16 length
            f.write(encoded)                                 # utf8 bytes

        # Vector section (float16)
        vectors_f16 = sorted_vectors.astype(np.float16)
        f.write(vectors_f16.tobytes())

    file_size = Path(path).stat().st_size
    print(f"  Written {path}: {word_count} words, {dimension}D, {file_size / 1024 / 1024:.1f} MB")


def load_uvec(path: str) -> tuple:
    """Load a .uvec binary file, returning (words, vectors_f32, dimension)."""
    with open(path, 'rb') as f:
        magic = f.read(4)
        if magic != b'UVEC':
            raise ValueError(f"Invalid .uvec magic: {magic}")
        dimension = struct.unpack('<H', f.read(2))[0]
        word_count = struct.unpack('<I', f.read(4))[0]
        f.read(6)  # reserved

        words = []
        for _ in range(word_count):
            wlen = struct.unpack('<H', f.read(2))[0]
            word = f.read(wlen).decode('utf-8')
            words.append(word)

        vec_bytes = f.read(word_count * dimension * 2)  # float16 = 2 bytes
        vectors = np.frombuffer(vec_bytes, dtype=np.float16).reshape(word_count, dimension)
        vectors = vectors.astype(np.float32)

    print(f"  Loaded {path}: {word_count} words, {dimension}D")
    return words, vectors, dimension


# --- French verb morphology (no external dependency) ---

# Common French verb infinitive endings
_FR_INFINITIVE_SUFFIXES = ('er', 'ir', 're', 'oir')

# Conjugated verb endings (indicatif, subjonctif, conditionnel, impératif)
_FR_VERB_SUFFIXES = (
    # Présent -er verbs
    'ons', 'ez',
    # Imparfait
    'ais', 'ait', 'ions', 'iez', 'aient',
    # Passé simple
    'âmes', 'âtes', 'èrent', 'îmes', 'îtes', 'irent', 'ûmes', 'ûtes', 'urent',
    # Futur
    'erai', 'eras', 'erons', 'erez', 'eront',
    'irai', 'iras', 'irons', 'irez', 'iront',
    # Conditionnel
    'erais', 'erait', 'erions', 'eriez', 'eraient',
    'irais', 'irait', 'irions', 'iriez', 'iraient',
    # Subjonctif
    'asses', 'issions', 'issiez', 'issent',
    # Participe présent
    'ant',
    # Participe passé (common patterns)
    'issant',
)

# Endings that are strong verb markers (high precision)
_FR_STRONG_VERB_ENDINGS = (
    'iser', 'aser', 'oser', 'user',  # -iser, -aser, etc.
    'ifier', 'uer', 'uer', 'ller', 'tter', 'pper',
    'enir', 'ertir', 'ormir', 'ouvrir', 'offrir', 'cueillir',
    'endre', 'ondre', 'erdre', 'ordre', 'aître', 'oître',
    'evoir', 'ouvoir', 'avoir', 'ouloir', 'aloir',
)

# Words that look like verbs but aren't (false positives to exclude)
_FR_VERB_EXCEPTIONS = {
    'air', 'chair', 'cuir', 'désir', 'loisir', 'plaisir', 'soir', 'avoir',
    'devoir', 'pouvoir', 'savoir', 'vouloir',  # these ARE verbs but also nouns
    'fer', 'hiver', 'enfer', 'amer', 'cancer', 'danger', 'diner', 'premier',
    'dernier', 'léger', 'étranger', 'particulier', 'régulier', 'entier',
    'hier', 'fier', 'papier', 'métier', 'quartier', 'atelier', 'cahier',
    'escalier', 'grenier', 'panier', 'soulier', 'tablier', 'sentier',
    'palier', 'pilier', 'chantier', 'bijoutier', 'bouclier', 'calendrier',
    'cendrier', 'chevalier', 'clavier', 'collier', 'courrier', 'encrier',
    'fichier', 'foyer', 'gravier', 'laurier', 'levier', 'olivier',
    'pommier', 'rosier', 'sanglier', 'sorcier', 'trésorier', 'vivier',
}

# English verb morphology
_EN_VERB_SUFFIXES = (
    'ing', 'ize', 'ise', 'ify', 'ate', 'ify',
    'ened', 'ened',
)

_EN_STRONG_VERB_ENDINGS = (
    'alize', 'ilize', 'imize', 'anize',
    'icate', 'ulate', 'ivate', 'uate',
    'ifying', 'izing', 'ising', 'ating',
)


def morpho_split_vocabulary(words: list, lang: str) -> tuple:
    """
    Split vocabulary into verbs and non-verbs using morphological heuristics.
    No external dependency needed.
    Returns (noun_indices, verb_indices).
    noun_indices = everything that's NOT classified as a verb.
    """
    verb_indices = []
    noun_indices = []

    if lang == 'fr':
        for i, word in enumerate(words):
            w = word.lower()
            if len(w) < 3:
                noun_indices.append(i)
                continue

            if w in _FR_VERB_EXCEPTIONS:
                noun_indices.append(i)  # known non-verb
                continue

            is_verb = False

            # Strong verb endings (high confidence)
            if any(w.endswith(s) for s in _FR_STRONG_VERB_ENDINGS):
                is_verb = True
            # Infinitive endings with length check
            elif w.endswith('er') and len(w) >= 4 and w[-3] not in 'éèê':
                is_verb = True
            elif w.endswith('ir') and len(w) >= 4:
                is_verb = True
            elif w.endswith('re') and len(w) >= 5:
                is_verb = True
            # Conjugated forms
            elif any(w.endswith(s) for s in _FR_VERB_SUFFIXES) and len(w) >= 4:
                is_verb = True

            if is_verb:
                verb_indices.append(i)
            else:
                noun_indices.append(i)

    elif lang == 'en':
        for i, word in enumerate(words):
            w = word.lower()
            if len(w) < 3:
                noun_indices.append(i)
                continue

            is_verb = False

            if any(w.endswith(s) for s in _EN_STRONG_VERB_ENDINGS):
                is_verb = True
            elif w.endswith('ing') and len(w) >= 5:
                is_verb = True
            elif w.endswith('ize') or w.endswith('ise') and len(w) >= 5:
                is_verb = True
            elif w.endswith('ify') and len(w) >= 5:
                is_verb = True
            elif w.endswith('ate') and len(w) >= 5:
                is_verb = True

            if is_verb:
                verb_indices.append(i)
            else:
                noun_indices.append(i)
    else:
        raise ValueError(f"Unsupported language for morpho split: {lang}")

    print(f"  Morpho split ({lang}): {len(noun_indices)} nouns, {len(verb_indices)} verbs")
    return noun_indices, verb_indices


def pos_split_vocabulary(words: list, lang: str) -> tuple:
    """
    Split vocabulary into nouns and verbs using spaCy POS lexicon.
    Ambiguous words (tagged as both in different contexts) go into both lists.
    Returns (noun_indices, verb_indices) as lists of indices into the words array.
    """
    model_name = {
        'fr': 'fr_core_news_sm',
        'en': 'en_core_web_sm',
    }.get(lang)
    if not model_name:
        raise ValueError(f"Unsupported language for POS tagging: {lang}")

    try:
        import spacy
    except ImportError:
        print("ERROR: spaCy is required for --pos-split mode")
        print("  pip install spacy")
        print(f"  python -m spacy download {model_name}")
        sys.exit(1)

    try:
        nlp = spacy.load(model_name)
    except OSError:
        print(f"ERROR: spaCy model '{model_name}' not found")
        print(f"  python -m spacy download {model_name}")
        sys.exit(1)

    print(f"  POS tagging {len(words)} words with {model_name}...")

    noun_indices = []
    verb_indices = []
    ambiguous_count = 0

    # Use nlp.pipe for batch processing (much faster than word-by-word)
    # Process words in batches to get POS tags
    batch_size = 1000
    for batch_start in range(0, len(words), batch_size):
        batch_end = min(batch_start + batch_size, len(words))
        batch_words = words[batch_start:batch_end]

        # Use nlp.pipe with disable to only get tagger
        docs = list(nlp.pipe(batch_words, disable=['parser', 'ner'], batch_size=batch_size))

        for i, doc in enumerate(docs):
            word_idx = batch_start + i
            if len(doc) == 0:
                continue

            # For single-word input, the POS of the first token
            pos = doc[0].pos_

            is_noun = pos in ('NOUN', 'PROPN')
            is_verb = pos == 'VERB'

            # Also check lexeme-level POS for ambiguity detection
            lexeme = nlp.vocab[words[word_idx]]
            lex_pos = lexeme.pos_ if hasattr(lexeme, 'pos_') and lexeme.pos_ else None

            if lex_pos and lex_pos != pos:
                # Ambiguous: lexeme says different POS than context-free tagger
                lex_is_noun = lex_pos in ('NOUN', 'PROPN')
                lex_is_verb = lex_pos == 'VERB'
                if (is_noun and lex_is_verb) or (is_verb and lex_is_noun):
                    noun_indices.append(word_idx)
                    verb_indices.append(word_idx)
                    ambiguous_count += 1
                    continue

            if is_noun:
                noun_indices.append(word_idx)
            elif is_verb:
                verb_indices.append(word_idx)
            # ADJ, ADV, DET, etc. are dropped

        if (batch_end) % 10000 == 0 or batch_end == len(words):
            print(f"    POS tagged {batch_end}/{len(words)} words...")

    print(f"  POS split: {len(noun_indices)} nouns, {len(verb_indices)} verbs, {ambiguous_count} ambiguous (in both)")
    return noun_indices, verb_indices


def convert_muse_matrix(input_path: str, output_path: str, dim: int):
    """Convert MUSE alignment matrix (PyTorch .pth) to compact float16 binary."""
    try:
        import torch
        data = torch.load(input_path, map_location='cpu')
        if isinstance(data, dict):
            # MUSE saves as {'W': tensor}
            matrix = data.get('W', data.get('best_mapping', next(iter(data.values()))))
        else:
            matrix = data

        matrix = matrix.numpy().astype(np.float32)
    except ImportError:
        print("PyTorch not available, trying numpy load...")
        matrix = np.load(input_path).astype(np.float32)

    # Truncate to target dimension
    matrix = matrix[:dim, :dim]

    # Write as float16
    matrix_f16 = matrix.astype(np.float16)
    with open(output_path, 'wb') as f:
        f.write(matrix_f16.tobytes())

    file_size = Path(output_path).stat().st_size
    print(f"  Written {output_path}: {dim}x{dim} matrix, {file_size / 1024:.1f} KB")


def main():
    parser = argparse.ArgumentParser(description='Convert FastText .vec to .uvec format')
    parser.add_argument('--input', required=True, help='Input file path (.vec or .pth)')
    parser.add_argument('--output', default=None, help='Output file path (.uvec or .bin). Not needed for --pos-split.')
    parser.add_argument('--dim', type=int, default=100, help='Target dimension (default: 100)')
    parser.add_argument('--max-words', type=int, default=60000, help='Max words to keep (default: 60000)')
    parser.add_argument('--convert-muse', action='store_true', help='Convert MUSE alignment matrix')
    parser.add_argument('--pos-split', action='store_true', help='Split vocabulary by POS (noun/verb) using spaCy')
    parser.add_argument('--split-uvec', action='store_true', help='Split existing .uvec into noun/verb via morphological heuristics (no spaCy)')
    parser.add_argument('--lang', default=None, help='Language tag (fr/en) - required for --pos-split or --split-uvec')

    args = parser.parse_args()

    if args.split_uvec:
        if not args.lang:
            parser.error("--lang is required for --split-uvec (fr or en)")
        lang = args.lang.lower()
        print(f"Splitting .uvec by morphology: {args.input} (lang={lang})")

        words, vectors, dimension = load_uvec(args.input)

        noun_indices, verb_indices = morpho_split_vocabulary(words, lang)

        # Write noun .uvec (= everything that's not a verb)
        noun_words = [words[i] for i in noun_indices]
        noun_vectors = vectors[noun_indices]
        noun_path = str(Path(args.input).parent / f"fasttext_{lang}_nouns.uvec")
        print(f"\n  Writing nouns: {len(noun_words)} words")
        write_uvec(noun_path, noun_words, noun_vectors, dimension)

        # Write verb .uvec
        verb_words = [words[i] for i in verb_indices]
        verb_vectors = vectors[verb_indices]
        verb_path = str(Path(args.input).parent / f"fasttext_{lang}_verbs.uvec")
        print(f"\n  Writing verbs: {len(verb_words)} words")
        write_uvec(verb_path, verb_words, verb_vectors, dimension)

        print(f"\n  Summary:")
        print(f"    Nouns: {len(noun_words)} words -> {noun_path}")
        print(f"    Verbs: {len(verb_words)} words -> {verb_path}")

    elif args.convert_muse:
        if not args.output:
            parser.error("--output is required for --convert-muse")
        print(f"Converting MUSE matrix: {args.input} -> {args.output}")
        convert_muse_matrix(args.input, args.output, args.dim)
    elif args.pos_split:
        if not args.lang:
            parser.error("--lang is required for --pos-split (fr or en)")

        lang = args.lang.lower()
        print(f"Converting FastText with POS split: {args.input} (lang={lang})")
        print(f"  Target: {args.max_words} words, {args.dim} dimensions")

        words, vectors = load_fasttext_vec(args.input, args.max_words, args.dim)

        print("  L2-normalizing vectors...")
        vectors = l2_normalize(vectors)

        # POS-tag and split
        noun_indices, verb_indices = pos_split_vocabulary(words, lang)

        # Write noun .uvec
        noun_words = [words[i] for i in noun_indices]
        noun_vectors = vectors[noun_indices]
        noun_path = f"fasttext_{lang}_nouns.uvec"
        print(f"\n  Writing nouns: {len(noun_words)} words")
        write_uvec(noun_path, noun_words, noun_vectors, args.dim)

        # Write verb .uvec
        verb_words = [words[i] for i in verb_indices]
        verb_vectors = vectors[verb_indices]
        verb_path = f"fasttext_{lang}_verbs.uvec"
        print(f"\n  Writing verbs: {len(verb_words)} words")
        write_uvec(verb_path, verb_words, verb_vectors, args.dim)

        print(f"\n  Summary:")
        print(f"    Nouns: {len(noun_words)} words -> {noun_path}")
        print(f"    Verbs: {len(verb_words)} words -> {verb_path}")
    else:
        if not args.output:
            parser.error("--output is required (or use --pos-split)")
        print(f"Converting FastText: {args.input} -> {args.output}")
        print(f"  Target: {args.max_words} words, {args.dim} dimensions")

        words, vectors = load_fasttext_vec(args.input, args.max_words, args.dim)

        print("  L2-normalizing vectors...")
        vectors = l2_normalize(vectors)

        write_uvec(args.output, words, vectors, args.dim)

    print("Done!")


if __name__ == '__main__':
    main()
