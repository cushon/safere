// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/**
 * Empirical character and byte rarity oracle for regex literal and prefix acceleration.
 *
 * <p>Ranks characters based on empirical frequency distributions across text, source code, JSON,
 * and network protocols. Higher rank indicates rarer characters (0 = most common, e.g. space; 127 =
 * rarest, e.g. rare punctuation, control characters, non-ASCII).
 */
final class RarityOracle {
  private static final byte[] ASCII_FREQUENCY_RANK = new byte[128];

  static {
    // Default all unlisted ASCII to high rarity
    for (int i = 0; i < 128; i++) {
      ASCII_FREQUENCY_RANK[i] = 100;
    }
    // Control characters (rare in text)
    for (int i = 0; i < 32; i++) {
      ASCII_FREQUENCY_RANK[i] = 120;
    }
    // High-frequency whitespace
    ASCII_FREQUENCY_RANK[' '] = 0;
    ASCII_FREQUENCY_RANK['\n'] = 10;
    ASCII_FREQUENCY_RANK['\t'] = 30;
    ASCII_FREQUENCY_RANK['\r'] = 35;

    // Common letters (case-folded: 'a'/'A' share rank)
    setLetterRank('e', 6);
    setLetterRank('t', 8);
    setLetterRank('a', 10);
    setLetterRank('o', 11);
    setLetterRank('i', 12);
    setLetterRank('n', 13);
    setLetterRank('s', 14);
    setLetterRank('r', 15);
    setLetterRank('h', 16);
    setLetterRank('l', 18);
    setLetterRank('d', 20);
    setLetterRank('c', 22);
    setLetterRank('u', 24);
    setLetterRank('m', 26);
    setLetterRank('f', 28);
    setLetterRank('p', 30);
    setLetterRank('g', 32);
    setLetterRank('w', 34);
    setLetterRank('y', 36);
    setLetterRank('b', 38);
    setLetterRank('v', 42);
    setLetterRank('k', 46);
    setLetterRank('x', 65);
    setLetterRank('j', 70);
    setLetterRank('q', 80);
    setLetterRank('z', 85);

    // Common code/JSON/protocol punctuation
    ASCII_FREQUENCY_RANK['"'] = 12;
    ASCII_FREQUENCY_RANK[':'] = 14;
    ASCII_FREQUENCY_RANK[','] = 14;
    ASCII_FREQUENCY_RANK['.'] = 15;
    ASCII_FREQUENCY_RANK['/'] = 16;
    ASCII_FREQUENCY_RANK['-'] = 18;
    ASCII_FREQUENCY_RANK['_'] = 18;
    ASCII_FREQUENCY_RANK['='] = 20;
    ASCII_FREQUENCY_RANK[';'] = 20;
    ASCII_FREQUENCY_RANK['('] = 24;
    ASCII_FREQUENCY_RANK[')'] = 24;
    ASCII_FREQUENCY_RANK['{'] = 24;
    ASCII_FREQUENCY_RANK['}'] = 24;
    ASCII_FREQUENCY_RANK['['] = 24;
    ASCII_FREQUENCY_RANK[']'] = 24;

    // Digits
    for (char d = '0'; d <= '9'; d++) {
      ASCII_FREQUENCY_RANK[d] = (byte) (30 + (d - '0'));
    }
  }

  private static void setLetterRank(char c, int rank) {
    ASCII_FREQUENCY_RANK[c] = (byte) rank;
    ASCII_FREQUENCY_RANK[Character.toUpperCase(c)] = (byte) rank;
  }

  /** Returns the byte frequency rank for an ASCII character (higher = rarer). */
  static int byteRarity(int c) {
    return c >= 0 && c < 128 ? (ASCII_FREQUENCY_RANK[c] & 0xFF) : 127;
  }

  /**
   * Returns the offset of the rarest ASCII character in the prefix (up to prefixLen). If all
   * characters have identical rank, returns 0.
   */
  static int rarestAsciiOffset(CharSequence prefix, int prefixLen) {
    int bestOffset = 0;
    int maxRank = -1;
    for (int i = 0; i < prefixLen; i++) {
      char c = prefix.charAt(i);
      int rank = byteRarity(c);
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
    if (s == null || s.isEmpty()) {
      return 0;
    }
    int score = 0;
    int maxCharRarity = 0;
    for (int i = 0; i < s.length(); i++) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      int r = byteRarity(s.charAt(i));
      score += r + 1;
      if (r > maxCharRarity) {
        maxCharRarity = r;
      }
    }
    return score + maxCharRarity;
  }

  private RarityOracle() {}
}
