// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/**
 * Empirical character and byte rarity oracle for regex literal and start acceleration.
 *
 * <p>Assigns empirical frequency ranks to bytes (0..255) to optimize SIMD anchor selection and
 * literal selectivity scoring. Higher rank indicates rarer characters (0 = most common, e.g. space;
 * 255 = rarest, e.g. out-of-range code points or rare control characters).
 *
 * <p>The frequency distribution is adapted from BurntSushi's {@code regex-automata} / {@code
 * aho-corasick} / {@code memchr} background distribution ({@code BYTE_FREQUENCIES}, licensed under
 * MIT / Unlicense), inverted so higher rank indicates rarer bytes.
 *
 * <p>Two distinct frequency distributions are calibrated:
 *
 * <ul>
 *   <li><b>Exact-case rarity ({@link #exactByteRarity(int)}):</b> Calibrated against corpus
 *       distributions across source code, JSON payloads, log streams, and English prose. In exact
 *       matching, lowercase letters (e.g. {@code 'e'}, {@code 't'}, {@code 'a'}) appear at
 *       substantially higher frequencies than uppercase letters (e.g. {@code 'E'}, {@code 'T'},
 *       {@code 'A'}). Anchoring on uppercase letters in camelCase or ALL_CAPS tokens (such as
 *       {@code AbstractBeanFactory} or {@code HTTP_REQUEST}) drastically reduces false-positive
 *       candidate checks.
 *   <li><b>Case-folded rarity ({@link #caseFoldedByteRarity(int)}):</b> Calibrated for
 *       case-insensitive search (such as {@link Matcher#indexOfIgnoreCase}), where a candidate
 *       broadcast lane must match both lowercase and uppercase variations ({@code P(c) +
 *       P(swapCase(c))}). In this mode, {@code 'e'} and {@code 'E'} share the same combined
 *       frequency rank.
 * </ul>
 */
final class RarityOracle {
  private static final byte[] EXACT_BYTE_RARITY =
      new byte[] {
        (byte) 200, (byte) 203, (byte) 204, (byte) 205, (byte) 206, (byte) 207, (byte) 208,
            (byte) 209, (byte) 210, (byte) 152, (byte) 13, (byte) 189, (byte) 188, (byte) 26,
            (byte) 211, (byte) 212,
        (byte) 213, (byte) 214, (byte) 215, (byte) 216, (byte) 217, (byte) 218, (byte) 219,
            (byte) 220, (byte) 221, (byte) 222, (byte) 199, (byte) 223, (byte) 224, (byte) 225,
            (byte) 226, (byte) 227,
        (byte) 0, (byte) 107, (byte) 91, (byte) 106, (byte) 119, (byte) 95, (byte) 100, (byte) 82,
            (byte) 34, (byte) 33, (byte) 121, (byte) 133, (byte) 23, (byte) 53, (byte) 40,
            (byte) 31,
        (byte) 47, (byte) 35, (byte) 51, (byte) 68, (byte) 72, (byte) 76, (byte) 78, (byte) 87,
            (byte) 77, (byte) 55, (byte) 29, (byte) 60, (byte) 101, (byte) 71, (byte) 81,
            (byte) 129,
        (byte) 135, (byte) 64, (byte) 98, (byte) 61, (byte) 85, (byte) 66, (byte) 93, (byte) 94,
            (byte) 105, (byte) 62, (byte) 113, (byte) 118, (byte) 84, (byte) 79, (byte) 70,
            (byte) 88,
        (byte) 69, (byte) 143, (byte) 80, (byte) 63, (byte) 67, (byte) 99, (byte) 115, (byte) 112,
            (byte) 132, (byte) 122, (byte) 127, (byte) 108, (byte) 117, (byte) 109, (byte) 141,
            (byte) 32,
        (byte) 104, (byte) 6, (byte) 39, (byte) 17, (byte) 19, (byte) 2, (byte) 28, (byte) 37,
            (byte) 25, (byte) 8, (byte) 120, (byte) 75, (byte) 14, (byte) 22, (byte) 9, (byte) 11,
        (byte) 24, (byte) 116, (byte) 10, (byte) 12, (byte) 4, (byte) 20, (byte) 54, (byte) 59,
            (byte) 15, (byte) 41, (byte) 103, (byte) 73, (byte) 50, (byte) 74, (byte) 128,
            (byte) 228,
        (byte) 43, (byte) 44, (byte) 45, (byte) 42, (byte) 27, (byte) 58, (byte) 86, (byte) 96,
            (byte) 124, (byte) 83, (byte) 150, (byte) 175, (byte) 157, (byte) 159, (byte) 158,
            (byte) 174,
        (byte) 48, (byte) 110, (byte) 139, (byte) 140, (byte) 111, (byte) 125, (byte) 102,
            (byte) 134, (byte) 148, (byte) 123, (byte) 146, (byte) 145, (byte) 131, (byte) 144,
            (byte) 173, (byte) 147,
        (byte) 137, (byte) 114, (byte) 142, (byte) 126, (byte) 136, (byte) 130, (byte) 90,
            (byte) 138, (byte) 163, (byte) 149, (byte) 172, (byte) 183, (byte) 156, (byte) 162,
            (byte) 190, (byte) 176,
        (byte) 89, (byte) 18, (byte) 92, (byte) 56, (byte) 65, (byte) 30, (byte) 46, (byte) 52,
            (byte) 57, (byte) 38, (byte) 36, (byte) 49, (byte) 21, (byte) 7, (byte) 97, (byte) 16,
        (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0,
            (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0,
        (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0,
            (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0,
        (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0,
            (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0,
        (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0,
            (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0,
      };

  private static final byte[] CASE_FOLDED_BYTE_RARITY =
      new byte[] {
        (byte) 200, (byte) 203, (byte) 204, (byte) 205, (byte) 206, (byte) 207, (byte) 208,
            (byte) 209, (byte) 210, (byte) 152, (byte) 13, (byte) 189, (byte) 188, (byte) 26,
            (byte) 211, (byte) 212,
        (byte) 213, (byte) 214, (byte) 215, (byte) 216, (byte) 217, (byte) 218, (byte) 219,
            (byte) 220, (byte) 221, (byte) 222, (byte) 199, (byte) 223, (byte) 224, (byte) 225,
            (byte) 226, (byte) 227,
        (byte) 0, (byte) 107, (byte) 91, (byte) 106, (byte) 119, (byte) 95, (byte) 100, (byte) 82,
            (byte) 34, (byte) 33, (byte) 121, (byte) 133, (byte) 23, (byte) 53, (byte) 40,
            (byte) 31,
        (byte) 47, (byte) 35, (byte) 51, (byte) 68, (byte) 72, (byte) 76, (byte) 78, (byte) 87,
            (byte) 77, (byte) 55, (byte) 29, (byte) 60, (byte) 101, (byte) 71, (byte) 81,
            (byte) 129,
        (byte) 135, (byte) 6, (byte) 39, (byte) 17, (byte) 19, (byte) 2, (byte) 28, (byte) 37,
            (byte) 25, (byte) 8, (byte) 113, (byte) 75, (byte) 14, (byte) 22, (byte) 9, (byte) 11,
        (byte) 24, (byte) 116, (byte) 10, (byte) 12, (byte) 4, (byte) 20, (byte) 54, (byte) 59,
            (byte) 15, (byte) 41, (byte) 103, (byte) 108, (byte) 117, (byte) 109, (byte) 141,
            (byte) 32,
        (byte) 104, (byte) 6, (byte) 39, (byte) 17, (byte) 19, (byte) 2, (byte) 28, (byte) 37,
            (byte) 25, (byte) 8, (byte) 113, (byte) 75, (byte) 14, (byte) 22, (byte) 9, (byte) 11,
        (byte) 24, (byte) 116, (byte) 10, (byte) 12, (byte) 4, (byte) 20, (byte) 54, (byte) 59,
            (byte) 15, (byte) 41, (byte) 103, (byte) 73, (byte) 50, (byte) 74, (byte) 128,
            (byte) 228,
        (byte) 43, (byte) 44, (byte) 45, (byte) 42, (byte) 27, (byte) 58, (byte) 86, (byte) 96,
            (byte) 124, (byte) 83, (byte) 150, (byte) 175, (byte) 157, (byte) 159, (byte) 158,
            (byte) 174,
        (byte) 48, (byte) 110, (byte) 139, (byte) 140, (byte) 111, (byte) 125, (byte) 102,
            (byte) 134, (byte) 148, (byte) 123, (byte) 146, (byte) 145, (byte) 131, (byte) 144,
            (byte) 173, (byte) 147,
        (byte) 137, (byte) 114, (byte) 142, (byte) 126, (byte) 136, (byte) 130, (byte) 90,
            (byte) 138, (byte) 163, (byte) 149, (byte) 172, (byte) 183, (byte) 156, (byte) 162,
            (byte) 190, (byte) 176,
        (byte) 89, (byte) 18, (byte) 92, (byte) 56, (byte) 65, (byte) 30, (byte) 46, (byte) 52,
            (byte) 57, (byte) 38, (byte) 36, (byte) 49, (byte) 21, (byte) 7, (byte) 97, (byte) 16,
        (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0,
            (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0,
        (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0,
            (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0,
        (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0,
            (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0,
        (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0,
            (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0,
      };

  /**
   * Returns the exact byte frequency rank for an ASCII or UTF-8 byte (higher = rarer).
   *
   * <p>Distinguishes uppercase and lowercase frequencies for exact matching.
   */
  static int exactByteRarity(int c) {
    return c >= 0 && c < 256 ? (EXACT_BYTE_RARITY[c] & 0xFF) : 255;
  }

  /**
   * Returns the case-folded byte frequency rank for an ASCII or UTF-8 byte (higher = rarer).
   *
   * <p>Assigns equal rank to {@code 'a'} and {@code 'A'} based on their combined frequency.
   */
  static int caseFoldedByteRarity(int c) {
    return c >= 0 && c < 256 ? (CASE_FOLDED_BYTE_RARITY[c] & 0xFF) : 255;
  }

  /**
   * Returns the byte frequency rank for an ASCII or UTF-8 byte (higher = rarer).
   *
   * @param c character or byte code point
   * @param caseFolded {@code true} to use case-folded combined letter ranks
   */
  static int byteRarity(int c, boolean caseFolded) {
    return caseFolded ? caseFoldedByteRarity(c) : exactByteRarity(c);
  }

  /** Returns the exact-case byte frequency rank for an ASCII or UTF-8 byte (higher = rarer). */
  static int byteRarity(int c) {
    return exactByteRarity(c);
  }

  /**
   * Returns the offset of the rarest ASCII character in the prefix (up to {@code prefixLen}). If
   * all characters have identical rank or length is 0, returns 0.
   */
  static int rarestAsciiOffset(CharSequence prefix, int prefixLen) {
    return rarestAsciiOffset(prefix, prefixLen, false);
  }

  /**
   * Returns the offset of the rarest ASCII character in the prefix (up to {@code prefixLen}),
   * optionally applying case-folded frequency ratings.
   *
   * @param prefix the character sequence to scan
   * @param prefixLen length of prefix to evaluate
   * @param caseFolded {@code true} for case-insensitive matching
   * @return 0-based offset of rarest character
   */
  static int rarestAsciiOffset(CharSequence prefix, int prefixLen, boolean caseFolded) {
    int bestOffset = 0;
    int maxRank = -1;
    for (int i = 0; i < prefixLen; i++) {
      char c = prefix.charAt(i);
      int rank = byteRarity(c, caseFolded);
      if (rank > maxRank) {
        maxRank = rank;
        bestOffset = i;
      }
    }
    return bestOffset;
  }

  /**
   * Computes a selectivity score for a literal string. Combines string length with individual
   * character rarity.
   */
  static int literalSelectivityScore(CharSequence s) {
    return literalSelectivityScore(s, false);
  }

  /**
   * Computes a selectivity score for a literal string, optionally using case-folded ratings.
   *
   * @param s the literal candidate sequence
   * @param caseFolded {@code true} if matching will be case-insensitive
   * @return higher score indicates a more selective literal
   */
  static int literalSelectivityScore(CharSequence s, boolean caseFolded) {
    if (s == null || s.isEmpty()) {
      return 0;
    }
    int score = 0;
    int maxCharRarity = 0;
    for (int i = 0; i < s.length(); i++) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      int r = byteRarity(s.charAt(i), caseFolded);
      score += r + 1;
      if (r > maxCharRarity) {
        maxCharRarity = r;
      }
    }
    return score + maxCharRarity;
  }

  private RarityOracle() {}
}
