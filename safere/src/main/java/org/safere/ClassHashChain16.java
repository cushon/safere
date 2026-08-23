// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Arrays;

/**
 * Precomputed 16-bit 2-gram shift table for multilingual case-insensitive String search.
 *
 * <p>Maps 16-bit character pairs into a 1024-entry shift table to provide sublinear candidate skips
 * for non-ASCII case-insensitive patterns (e.g. Cyrillic, Greek, accented Latin, Emoji).
 */
final class ClassHashChain16 {
  private static final int TABLE_SIZE = 1024;
  private static final int TABLE_MASK = 0x3FF;

  private final String literal;
  private final byte[] shifts;
  private final int length;

  private ClassHashChain16(String literal, byte[] shifts, int length) {
    this.literal = literal;
    this.shifts = shifts;
    this.length = length;
  }

  static int hash(char c0, char c1) {
    return ((c0 * 31 + c1) ^ (c0 >>> 5)) & TABLE_MASK;
  }

  static ClassHashChain16 compileCaseInsensitive(String literal) {
    if (literal == null || literal.length() < 4) {
      return null;
    }
    int m = literal.length();
    byte[] shifts = new byte[TABLE_SIZE];
    int defaultShift = Math.min(127, m - 1);
    Arrays.fill(shifts, (byte) defaultShift);

    for (int i = 0; i < m - 2; i++) {
      int shiftVal = m - 2 - i;
      char[] c0Set = foldChars(literal.charAt(i));
      char[] c1Set = foldChars(literal.charAt(i + 1));
      for (char b0 : c0Set) {
        for (char b1 : c1Set) {
          int h = hash(b0, b1);
          shifts[h] = (byte) Math.min(shifts[h] & 0xFF, shiftVal);
        }
      }
    }

    char[] cLast0 = foldChars(literal.charAt(m - 2));
    char[] cLast1 = foldChars(literal.charAt(m - 1));
    for (char b0 : cLast0) {
      for (char b1 : cLast1) {
        shifts[hash(b0, b1)] = 0;
      }
    }

    return new ClassHashChain16(literal, shifts, m);
  }

  private static char[] foldChars(char c) {
    char lower = Character.toLowerCase(c);
    char upper = Character.toUpperCase(c);
    if (lower == upper) {
      return new char[] {lower};
    }
    return new char[] {lower, upper};
  }

  int shiftAt(CharSequence text, int candidatePos) {
    int inPos = candidatePos + (length - 1);
    if (inPos >= text.length()) {
      return 0;
    }
    char c0 = text.charAt(inPos - 1);
    char c1 = text.charAt(inPos);
    int h = hash(c0, c1);
    return shifts[h] & 0xFF;
  }

  int search(CharSequence text, int start, long workLimit) {
    int textLength = text.length();
    if (textLength - start < length) {
      return -1;
    }
    int last = length - 1;
    int position = start + last;
    long work = 0;

    while (position < textLength) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      char c0 = text.charAt(position - 1);
      char c1 = text.charAt(position);
      int h = hash(c0, c1);
      int shift = shifts[h] & 0xFF;

      if (shift == 0) {
        int candidate = position - last;
        int litPos = last - 2;
        int inPos = position - 2;
        while (litPos >= 0) {
          char tc = text.charAt(inPos);
          char pc = literal.charAt(litPos);
          if (tc != pc
              && Character.toLowerCase(tc) != Character.toLowerCase(pc)
              && Character.toUpperCase(tc) != Character.toUpperCase(pc)) {
            break;
          }
          litPos--;
          inPos--;
        }
        if (litPos < 0) {
          return candidate;
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
