// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Precomputed 16-bit 2-gram shift table for multilingual case-insensitive UTF-16 String search.
 *
 * <p>Maps 16-bit character pairs into a 1024-entry shift table to provide sublinear candidate skips
 * for non-ASCII case-insensitive patterns (e.g. Cyrillic, Greek, accented Latin, Emoji) on {@link
 * CharSequence}.
 */
final class ClassHashChainUtf16 {
  private static final int TABLE_SIZE = 1024;
  private static final int TABLE_MASK = 0x3FF;

  private final String literal;
  private final byte[] shifts;
  private final int length;

  private ClassHashChainUtf16(String literal, byte[] shifts, int length) {
    this.literal = literal;
    this.shifts = shifts;
    this.length = length;
  }

  static int hash(char c0, char c1) {
    return ((c0 * 31 + c1) ^ (c0 >>> 5)) & TABLE_MASK;
  }

  static ClassHashChainUtf16 compileCaseInsensitive(String literal) {
    if (literal == null || literal.length() < 4) {
      return null;
    }
    int m = literal.length();
    List<char[][]> codePointVariants = new ArrayList<>();
    for (int i = 0; i < literal.length(); ) {
      int codePoint = literal.codePointAt(i);
      char[] original = Character.toChars(codePoint);
      List<char[]> variants = new ArrayList<>();
      int folded = codePoint;
      do {
        char[] foldedChars = Character.toChars(folded);
        if (foldedChars.length != original.length) {
          return null;
        }
        if (variants.stream().noneMatch(existing -> Arrays.equals(existing, foldedChars))) {
          variants.add(foldedChars);
        }
        folded = Inst.simpleFold(folded);
      } while (folded != codePoint);
      codePointVariants.add(variants.toArray(char[][]::new));
      i += original.length;
    }

    int[] codePointIndexForChar = new int[m];
    int[] intraCharOffset = new int[m];
    int charIndex = 0;
    for (int codePointIndex = 0; codePointIndex < codePointVariants.size(); codePointIndex++) {
      int charCount = codePointVariants.get(codePointIndex)[0].length;
      for (int charOffset = 0; charOffset < charCount; charOffset++) {
        codePointIndexForChar[charIndex] = codePointIndex;
        intraCharOffset[charIndex] = charOffset;
        charIndex++;
      }
    }

    byte[] shifts = new byte[TABLE_SIZE];
    int defaultShift = Math.min(127, m - 1);
    Arrays.fill(shifts, (byte) defaultShift);

    for (int i = 0; i < m - 2; i++) {
      int shiftVal = m - 2 - i;
      int cp0 = codePointIndexForChar[i];
      int off0 = intraCharOffset[i];
      int cp1 = codePointIndexForChar[i + 1];
      int off1 = intraCharOffset[i + 1];
      if (cp0 == cp1) {
        for (char[] variant : codePointVariants.get(cp0)) {
          int h = hash(variant[off0], variant[off1]);
          shifts[h] = (byte) Math.min(shifts[h] & 0xFF, shiftVal);
        }
      } else {
        for (char[] variant0 : codePointVariants.get(cp0)) {
          for (char[] variant1 : codePointVariants.get(cp1)) {
            int h = hash(variant0[off0], variant1[off1]);
            shifts[h] = (byte) Math.min(shifts[h] & 0xFF, shiftVal);
          }
        }
      }
    }

    int cpLast0 = codePointIndexForChar[m - 2];
    int offLast0 = intraCharOffset[m - 2];
    int cpLast1 = codePointIndexForChar[m - 1];
    int offLast1 = intraCharOffset[m - 1];
    if (cpLast0 == cpLast1) {
      for (char[] variant : codePointVariants.get(cpLast0)) {
        shifts[hash(variant[offLast0], variant[offLast1])] = 0;
      }
    } else {
      for (char[] variant0 : codePointVariants.get(cpLast0)) {
        for (char[] variant1 : codePointVariants.get(cpLast1)) {
          shifts[hash(variant0[offLast0], variant1[offLast1])] = 0;
        }
      }
    }

    return new ClassHashChainUtf16(literal, shifts, m);
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
        if (Utf16.regionMatchesUnicodeIgnoreCase(text, candidate, literal)) {
          return candidate;
        }
        work += length;
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
