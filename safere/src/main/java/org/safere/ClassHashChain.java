// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Arrays;

/** Precomputed 2-gram Class-HashChain scanner for character class sequences and case-folding. */
final class ClassHashChain {
  private static final int TABLE_SIZE = 256;

  final AsciiBitmap[] classes;
  final byte[] shifts;
  final int length;

  private ClassHashChain(AsciiBitmap[] classes, byte[] shifts, int length) {
    this.classes = classes;
    this.shifts = shifts;
    this.length = length;
  }

  static ClassHashChain compileCaseInsensitive(String literal) {
    if (literal == null || literal.length() < 4) {
      return null;
    }
    int m = literal.length();
    AsciiBitmap[] classes = new AsciiBitmap[m];
    for (int i = 0; i < m; i++) {
      char c = literal.charAt(i);
      if (c > 127) {
        return null;
      }
      char lower = Ascii.toLowerCase(c);
      char upper = Ascii.toUpperCase(c);
      classes[i] = AsciiBitmap.of(lower).union(AsciiBitmap.of(upper));
    }
    return compile(classes);
  }

  static ClassHashChain compile(AsciiBitmap[] classes) {
    if (classes == null || classes.length < 4) {
      return null;
    }
    int m = classes.length;
    byte[] shifts = new byte[TABLE_SIZE];
    int defaultShift = Math.min(127, m - 1);
    Arrays.fill(shifts, (byte) defaultShift);

    for (int i = 0; i < m - 2; i++) {
      int shiftVal = m - 2 - i;
      AsciiBitmap c0 = classes[i];
      AsciiBitmap c1 = classes[i + 1];
      for (int b0 = 0; b0 < 128; b0++) {
        if (c0.containsAscii(b0)) {
          for (int b1 = 0; b1 < 128; b1++) {
            if (c1.containsAscii(b1)) {
              int h = HashChain.hash((byte) b0, (byte) b1);
              shifts[h] = (byte) Math.min(shifts[h] & 0xFF, shiftVal);
            }
          }
        }
      }
    }

    // Terminal 2-gram marker
    AsciiBitmap cLast0 = classes[m - 2];
    AsciiBitmap cLast1 = classes[m - 1];
    for (int b0 = 0; b0 < 128; b0++) {
      if (cLast0.containsAscii(b0)) {
        for (int b1 = 0; b1 < 128; b1++) {
          if (cLast1.containsAscii(b1)) {
            int h = HashChain.hash((byte) b0, (byte) b1);
            shifts[h] = 0;
          }
        }
      }
    }

    return new ClassHashChain(classes, shifts, m);
  }

  int shiftAt(CharSequence text, int candidatePos) {
    int inPos = candidatePos + (length - 1);
    if (inPos >= text.length()) {
      return 0;
    }
    char c0 = text.charAt(inPos - 1);
    char c1 = text.charAt(inPos);
    int h = HashChain.hash((byte) c0, (byte) c1);
    return shifts[h] & 0xFF;
  }

  int search(byte[] bytes, int offset, int textLength, int start, long workLimit) {
    int last = length - 1;
    int position = start + last;
    long work = 0;

    while (position < textLength) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      int h = HashChain.hash(bytes[offset + position - 1], bytes[offset + position]);
      int shift = shifts[h] & 0xFF;

      if (shift == 0) {
        int clsPos = last - 2;
        int inPos = position - 2;
        while (clsPos >= 0 && classes[clsPos].contains(bytes[offset + inPos] & 0xFF)) {
          clsPos--;
          inPos--;
        }
        if (clsPos < 0) {
          return inPos + 1;
        }
        int matched = (last - 2) - clsPos;
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
      int h = HashChain.hash((byte) c0, (byte) c1);
      int shift = shifts[h] & 0xFF;

      if (shift == 0) {
        int clsPos = last - 2;
        int inPos = position - 2;
        while (clsPos >= 0 && classes[clsPos].contains(text.charAt(inPos))) {
          clsPos--;
          inPos--;
        }
        if (clsPos < 0) {
          return inPos + 1;
        }
        int matched = (last - 2) - clsPos;
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
