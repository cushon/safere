// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Arrays;

/**
 * Precomputed 2-gram HashChain scanner for exact multi-byte literal searching (Palmer, SEA 2024).
 *
 * <p>Uses 2-gram hashing into a direct-mapped 256-entry shift table to achieve sublinear scanning
 * on scalar, SWAR, and fallback paths.
 */
final class HashChain {
  private static final int TABLE_SIZE = 256;
  private static final int TABLE_MASK = 0xFF;
  private static final int HASH_SHIFT = 5;

  final byte[] literal;
  final byte[] shifts;
  final int defaultShift;

  private HashChain(byte[] literal, byte[] shifts, int defaultShift) {
    this.literal = literal;
    this.shifts = shifts;
    this.defaultShift = defaultShift;
  }

  static HashChain compile(byte[] literal) {
    if (literal == null || literal.length < 4) {
      return null;
    }
    int m = literal.length;
    byte[] shifts = new byte[TABLE_SIZE];
    int defaultShift = Math.min(127, m - 1);
    Arrays.fill(shifts, (byte) defaultShift);

    for (int i = 0; i < m - 2; i++) {
      int h = hash(literal[i], literal[i + 1]);
      int shift = m - 2 - i;
      shifts[h] = (byte) Math.min(shifts[h] & 0xFF, shift);
    }
    int terminalHash = hash(literal[m - 2], literal[m - 1]);
    shifts[terminalHash] = 0;

    return new HashChain(literal, shifts, defaultShift);
  }

  static int hash(byte b0, byte b1) {
    return ((b0 << HASH_SHIFT) ^ (b1 & 0xFF)) & TABLE_MASK;
  }

  int search(byte[] bytes, int offset, int length, int start, long workLimit) {
    int m = literal.length;
    int last = m - 1;
    int position = start + last;
    long work = 0;

    while (position < length) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      int h = hash(bytes[offset + position - 1], bytes[offset + position]);
      int shift = shifts[h] & 0xFF;

      if (shift == 0) {
        int litPos = last - 2;
        int inPos = position - 2;
        while (litPos >= 0 && bytes[offset + inPos] == literal[litPos]) {
          litPos--;
          inPos--;
        }
        if (litPos < 0) {
          return inPos + 1;
        }
        int matched = (last - 2) - litPos;
        work += matched + 1;
        if (WorkLimit.isExhausted(work, workLimit)) {
          return -2;
        }
        position++;
      } else {
        position += shift;
        work++;
        if (WorkLimit.isExhausted(work, workLimit)) {
          return -2;
        }
      }
    }
    return -1;
  }
}
