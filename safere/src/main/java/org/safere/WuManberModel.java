// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.io.Serializable;
import java.util.Arrays;

/**
 * Immutable precomputed lookup tables and search engine for Wu-Manber multi-pattern matching over
 * large keyword dictionaries ($32 \le K \le 512$).
 *
 * <p>Wu-Manber extends Boyer-Moore-Horspool to multi-keyword dictionaries using 2-gram hashing. For
 * an alphabet with block size $B = 2$ and minimum pattern length $m = \min_i |P_i| \ge 2$,
 * non-matching text blocks advance by up to $m - B + 1 = m - 1$ positions per lookup, delivering
 * sublinear average search performance across large keyword sets without the memory overhead of
 * Aho-Corasick or DFA state explosion.
 */
// The arrays are immutable, privately owned scanner metadata; array identity is never observed.
@SuppressWarnings("ArrayRecordComponent")
final class WuManberModel implements Serializable {
  private static final long serialVersionUID = 1L;

  private static final int HASH_TABLE_SIZE = 65536;
  private static final int HASH_MASK = HASH_TABLE_SIZE - 1;
  private static final int MAX_PATTERNS = 512;
  private static final int MIN_PATTERNS = 4;
  private static final int MIN_PATTERN_LENGTH = 4;

  private final byte[] shift1Table;
  private final byte[] shift2Table;
  private final int[] hashHead;
  private final int[] nextPattern;
  private final short[] prefixSignatures;
  private final String[] literals;
  private final int minLength;

  private WuManberModel(
      byte[] shift1Table,
      byte[] shift2Table,
      int[] hashHead,
      int[] nextPattern,
      short[] prefixSignatures,
      String[] literals,
      int minLength) {
    this.shift1Table = shift1Table;
    this.shift2Table = shift2Table;
    this.hashHead = hashHead;
    this.nextPattern = nextPattern;
    this.prefixSignatures = prefixSignatures;
    this.literals = literals;
    this.minLength = minLength;
  }

  static WuManberModel compile(String[] patterns) {
    if (patterns == null || patterns.length < MIN_PATTERNS || patterns.length > MAX_PATTERNS) {
      return null;
    }
    int minLen = Integer.MAX_VALUE;
    for (String p : patterns) {
      if (p == null || p.length() < MIN_PATTERN_LENGTH) {
        return null;
      }
      for (int i = 0; i < p.length(); i++) {
        if (p.charAt(i) > 127) {
          return null; // ASCII only
        }
      }
      minLen = Math.min(minLen, p.length());
    }
    if (minLen < MIN_PATTERN_LENGTH) {
      return null;
    }

    int defaultShift1 = minLen - 1;
    byte[] shift1Table = new byte[HASH_TABLE_SIZE];
    Arrays.fill(shift1Table, (byte) Math.min(127, defaultShift1));

    int defaultShift2 = minLen - 3;
    byte[] shift2Table = new byte[HASH_TABLE_SIZE];
    Arrays.fill(shift2Table, (byte) Math.min(127, defaultShift2));

    int k = patterns.length;
    int[] hashHead = new int[HASH_TABLE_SIZE];
    Arrays.fill(hashHead, -1);
    int[] nextPattern = new int[k];
    short[] prefixSignatures = new short[k];

    for (int i = 0; i < k; i++) {
      String p = patterns[i];
      prefixSignatures[i] = (short) (((p.charAt(0) & 0xFF) << 8) | (p.charAt(1) & 0xFF));

      // Populate shift1 table for all 2-grams ending within prefix of length minLen
      for (int j = 2; j <= minLen; j++) {
        int c1 = p.charAt(j - 2) & 0xFF;
        int c2 = p.charAt(j - 1) & 0xFF;
        int hash = ((c1 << 8) | c2) & HASH_MASK;
        int shift = minLen - j;
        int curShift = shift1Table[hash] & 0xFF;
        if (shift < curShift) {
          shift1Table[hash] = (byte) shift;
        }
      }

      // Populate shift2 table for all 2-grams ending within prefix of length (minLen - 2)
      for (int j = 2; j <= minLen - 2; j++) {
        int c1 = p.charAt(j - 2) & 0xFF;
        int c2 = p.charAt(j - 1) & 0xFF;
        int hash = ((c1 << 8) | c2) & HASH_MASK;
        int shift = (minLen - 2) - j;
        int curShift = shift2Table[hash] & 0xFF;
        if (shift < curShift) {
          shift2Table[hash] = (byte) shift;
        }
      }
    }

    // Populate hash table for patterns whose prefix of length minLen ends with the 2-gram
    // Insert in reverse order so iteration traverses patterns in ascending index order
    // (leftmost-first)
    for (int i = k - 1; i >= 0; i--) {
      String p = patterns[i];
      int c1 = p.charAt(minLen - 2) & 0xFF;
      int c2 = p.charAt(minLen - 1) & 0xFF;
      int hash = ((c1 << 8) | c2) & HASH_MASK;
      nextPattern[i] = hashHead[hash];
      hashHead[hash] = i;
    }

    return new WuManberModel(
        shift1Table,
        shift2Table,
        hashHead,
        nextPattern,
        prefixSignatures,
        Arrays.copyOf(patterns, k),
        minLen);
  }

  int minLength() {
    return minLength;
  }

  String[] literals() {
    return literals;
  }

  int findCandidate(String text, int fromIndex) {
    int length = text.length();
    int index = fromIndex + minLength - 1;
    long work = 0;
    long workLimit = WorkLimit.forRemaining(length - fromIndex);

    while (index < length) {
      int c1 = text.charAt(index - 1);
      int c2 = text.charAt(index);
      if (c1 > 127 || c2 > 127) {
        index += minLength - 1;
        continue;
      }
      int hash1 = ((c1 << 8) | c2) & HASH_MASK;
      int shift1 = shift1Table[hash1] & 0xFF;
      if (shift1 > 0) {
        index += shift1;
        continue;
      }

      if (index - 2 >= fromIndex) {
        int c3 = text.charAt(index - 3);
        int c4 = text.charAt(index - 2);
        if (c3 <= 127 && c4 <= 127) {
          int hash2 = ((c3 << 8) | c4) & HASH_MASK;
          int shift2 = shift2Table[hash2] & 0xFF;
          if (shift2 > 0) {
            index += shift2;
            continue;
          }
        }
      }

      int startPos = index - minLength + 1;
      short targetPrefix =
          (short) (((text.charAt(startPos) & 0xFF) << 8) | (text.charAt(startPos + 1) & 0xFF));
      int patIdx = hashHead[hash1];
      int bestPatIdx = -1;
      while (patIdx >= 0) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        work++;
        if (prefixSignatures[patIdx] == targetPrefix) {
          String lit = literals[patIdx];
          if (text.startsWith(lit, startPos)) {
            if (bestPatIdx < 0 || patIdx < bestPatIdx) {
              bestPatIdx = patIdx;
            }
          }
        }
        patIdx = nextPattern[patIdx];
      }
      if (bestPatIdx >= 0) {
        return startPos;
      }
      if (work > workLimit) {
        return fromIndex;
      }
      index++;
    }
    return -1;
  }

  int findCandidate(Utf8InputScanner scanner, int fromIndex) {
    int length = scanner.length();
    int index = fromIndex + minLength - 1;
    long work = 0;
    long workLimit = WorkLimit.forRemaining(length - fromIndex);
    byte[] bytes = scanner.bytes();
    int offset = scanner.offset();

    while (index < length) {
      int c1 = bytes[offset + index - 1] & 0xFF;
      int c2 = bytes[offset + index] & 0xFF;
      if (c1 > 127 || c2 > 127) {
        index += minLength - 1;
        continue;
      }
      int hash1 = ((c1 << 8) | c2) & HASH_MASK;
      int shift1 = shift1Table[hash1] & 0xFF;
      if (shift1 > 0) {
        index += shift1;
        continue;
      }

      if (index - 2 >= fromIndex) {
        int c3 = bytes[offset + index - 3] & 0xFF;
        int c4 = bytes[offset + index - 2] & 0xFF;
        if (c3 <= 127 && c4 <= 127) {
          int hash2 = ((c3 << 8) | c4) & HASH_MASK;
          int shift2 = shift2Table[hash2] & 0xFF;
          if (shift2 > 0) {
            index += shift2;
            continue;
          }
        }
      }

      int startPos = index - minLength + 1;
      short targetPrefix =
          (short)
              (((bytes[offset + startPos] & 0xFF) << 8) | (bytes[offset + startPos + 1] & 0xFF));
      int patIdx = hashHead[hash1];
      int bestPatIdx = -1;
      while (patIdx >= 0) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        work++;
        if (prefixSignatures[patIdx] == targetPrefix) {
          String lit = literals[patIdx];
          if (Ascii.regionMatches(bytes, offset + startPos, lit, lit.length())) {
            if (bestPatIdx < 0 || patIdx < bestPatIdx) {
              bestPatIdx = patIdx;
            }
          }
        }
        patIdx = nextPattern[patIdx];
      }
      if (bestPatIdx >= 0) {
        return startPos;
      }
      if (work > workLimit) {
        return fromIndex;
      }
      index++;
    }
    return -1;
  }
}
