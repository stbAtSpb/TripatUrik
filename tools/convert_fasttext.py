#!/usr/bin/env python3
"""
Convert FastText .vec files to compact .uvec (Urik VECtor) format.

Usage:
    python convert_fasttext.py --input cc.fr.300.vec --output fasttext_fr.uvec --dim 100 --max-words 60000
    python convert_fasttext.py --input cc.en.300.vec --output fasttext_en.uvec --dim 100 --max-words 60000

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
    parser.add_argument('--output', required=True, help='Output file path (.uvec or .bin)')
    parser.add_argument('--dim', type=int, default=100, help='Target dimension (default: 100)')
    parser.add_argument('--max-words', type=int, default=60000, help='Max words to keep (default: 60000)')
    parser.add_argument('--convert-muse', action='store_true', help='Convert MUSE alignment matrix')

    args = parser.parse_args()

    if args.convert_muse:
        print(f"Converting MUSE matrix: {args.input} -> {args.output}")
        convert_muse_matrix(args.input, args.output, args.dim)
    else:
        print(f"Converting FastText: {args.input} -> {args.output}")
        print(f"  Target: {args.max_words} words, {args.dim} dimensions")

        words, vectors = load_fasttext_vec(args.input, args.max_words, args.dim)

        print("  L2-normalizing vectors...")
        vectors = l2_normalize(vectors)

        write_uvec(args.output, words, vectors, args.dim)

    print("Done!")


if __name__ == '__main__':
    main()
