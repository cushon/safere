// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Precomputed 2-gram shift table for multilingual case-insensitive UTF-8 byte stream search.
 *
 * <p>Maps UTF-8 byte pairs into a 1024-entry shift table to provide sublinear candidate skips for
 * non-ASCII case-insensitive patterns (e.g. Cyrillic, Greek, accented Latin) on {@code byte[]}.
 */
final class ClassHashChainUtf8 {
  private static final int TABLE_SIZE = 1024;
  private static final int TABLE_MASK = 0x3FF;
  private static final int REPLACEMENT_CHARACTER = 0xFFFD;

  private final String prefix;
  private final byte[] shifts;
  private final int byteLength;

  private ClassHashChainUtf8(String prefix, byte[] shifts, int byteLength) {
    this.prefix = prefix;
    this.shifts = shifts;
    this.byteLength = byteLength;
  }

  static int hash(byte b0, byte b1) {
    int u0 = b0 & 0xFF;
    int u1 = b1 & 0xFF;
    return ((u0 * 31 + u1) ^ (u0 >>> 3)) & TABLE_MASK;
  }

  static ClassHashChainUtf8 compileCaseInsensitive(String prefix) {
    if (prefix == null) {
      return null;
    }
    byte[] utf8 = prefix.getBytes(StandardCharsets.UTF_8);
    int m = utf8.length;
    if (m < 4) {
      return null;
    }

    // Build byte-sequence variants for each character in the prefix.
    List<byte[][]> charVariants = new ArrayList<>(prefix.length());

    for (int i = 0; i < prefix.length(); ) {
      int cp = prefix.codePointAt(i);
      int charCount = Character.charCount(cp);

      byte[] origBytes = new String(new int[] {cp}, 0, 1).getBytes(StandardCharsets.UTF_8);
      List<byte[]> variants = new ArrayList<>();
      int folded = cp;
      do {
        byte[] foldedBytes = new String(new int[] {folded}, 0, 1).getBytes(StandardCharsets.UTF_8);
        if (foldedBytes.length != origBytes.length) {
          return null;
        }
        if (variants.stream().noneMatch(existing -> Arrays.equals(existing, foldedBytes))) {
          variants.add(foldedBytes);
        }
        folded = Inst.simpleFold(folded);
      } while (folded != cp);
      charVariants.add(variants.toArray(byte[][]::new));

      i += charCount;
    }

    // Map each byte index to its character index and intra-character byte offset.
    int numChars = charVariants.size();
    int[] charIndexForByte = new int[m];
    int[] intraByteOffset = new int[m];
    int byteIdx = 0;
    for (int c = 0; c < numChars; c++) {
      byte[][] variants = charVariants.get(c);
      int charLen = variants[0].length;
      for (int b = 0; b < charLen; b++) {
        charIndexForByte[byteIdx] = c;
        intraByteOffset[byteIdx] = b;
        byteIdx++;
      }
    }

    byte[] shifts = new byte[TABLE_SIZE];
    int defaultShift = Math.min(127, m - 1);
    Arrays.fill(shifts, (byte) defaultShift);

    // Populate shifts for 2-grams at offsets 0 .. m - 3
    for (int p = 0; p < m - 2; p++) {
      int shiftVal = m - 2 - p;
      int c0 = charIndexForByte[p];
      int off0 = intraByteOffset[p];
      int c1 = charIndexForByte[p + 1];
      int off1 = intraByteOffset[p + 1];

      byte[][] vars0 = charVariants.get(c0);
      byte[][] vars1 = charVariants.get(c1);

      if (c0 == c1) {
        // Both bytes belong to the same multi-byte character
        for (byte[] var : vars0) {
          int h = hash(var[off0], var[off1]);
          shifts[h] = (byte) Math.min(shifts[h] & 0xFF, shiftVal);
        }
      } else {
        // Bytes span adjacent characters
        for (byte[] var0 : vars0) {
          for (byte[] var1 : vars1) {
            int h = hash(var0[off0], var1[off1]);
            shifts[h] = (byte) Math.min(shifts[h] & 0xFF, shiftVal);
          }
        }
      }
    }

    // Terminal 2-gram at offset m - 2 has shift 0
    int cLast0 = charIndexForByte[m - 2];
    int offLast0 = intraByteOffset[m - 2];
    int cLast1 = charIndexForByte[m - 1];
    int offLast1 = intraByteOffset[m - 1];

    byte[][] varsLast0 = charVariants.get(cLast0);
    byte[][] varsLast1 = charVariants.get(cLast1);

    if (cLast0 == cLast1) {
      for (byte[] var : varsLast0) {
        shifts[hash(var[offLast0], var[offLast1])] = 0;
      }
    } else {
      for (byte[] var0 : varsLast0) {
        for (byte[] var1 : varsLast1) {
          shifts[hash(var0[offLast0], var1[offLast1])] = 0;
        }
      }
    }

    return new ClassHashChainUtf8(prefix, shifts, m);
  }

  int byteLength() {
    return byteLength;
  }

  int shiftAt(byte[] bytes, int offset, int length, int candidatePos) {
    int inPos = candidatePos + (byteLength - 1);
    if (inPos >= length) {
      return 0;
    }
    byte b0 = bytes[offset + inPos - 1];
    byte b1 = bytes[offset + inPos];
    int h = hash(b0, b1);
    return shifts[h] & 0xFF;
  }

  int search(byte[] bytes, int offset, int length, int start, long workLimit) {
    if (length - start < byteLength) {
      return -1;
    }
    int last = byteLength - 1;
    int position = start + last;
    long work = 0;

    while (position < length) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      byte b0 = bytes[offset + position - 1];
      byte b1 = bytes[offset + position];
      int h = hash(b0, b1);
      int shift = shifts[h] & 0xFF;

      if (shift == 0) {
        int candidate = position - last;
        if (regionMatchesIgnoreCase(bytes, offset + candidate, length - candidate)) {
          return candidate;
        }
        work += byteLength;
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

  private boolean regionMatchesIgnoreCase(byte[] bytes, int startOffset, int remaining) {
    if (remaining < byteLength) {
      return false;
    }
    int pos = startOffset;
    int end = startOffset + byteLength;
    int prefixLen = prefix.length();
    int prefixCharIdx = 0;

    while (prefixCharIdx < prefixLen) {
      if (pos >= end) {
        return false;
      }
      int expectedCp = prefix.codePointAt(prefixCharIdx);
      int expectedCount = Character.charCount(expectedCp);
      prefixCharIdx += expectedCount;

      long decoded = decodeForward(bytes, pos, end);
      int actualCp = (int) decoded;
      pos = (int) (decoded >>> Integer.SIZE);
      if (!unicodeEqualsIgnoreCase(actualCp, expectedCp)) {
        return false;
      }
    }
    return pos == end;
  }

  private static long decodeForward(byte[] bytes, int pos, int end) {
    int b0 = bytes[pos] & 0xFF;
    if (b0 < 0x80) {
      return decoded(b0, pos + 1);
    }
    if (b0 >= 0xC2 && b0 <= 0xDF && continuation(bytes, pos + 1, end)) {
      int codePoint = ((b0 & 0x1F) << 6) | (bytes[pos + 1] & 0x3F);
      return decoded(codePoint, pos + 2);
    }
    if (b0 >= 0xE0
        && b0 <= 0xEF
        && validThreeByteSecond(bytes, b0, pos + 1, end)
        && continuation(bytes, pos + 2, end)) {
      int codePoint =
          ((b0 & 0x0F) << 12) | ((bytes[pos + 1] & 0x3F) << 6) | (bytes[pos + 2] & 0x3F);
      return decoded(codePoint, pos + 3);
    }
    if (b0 >= 0xF0
        && b0 <= 0xF4
        && validFourByteSecond(bytes, b0, pos + 1, end)
        && continuation(bytes, pos + 2, end)
        && continuation(bytes, pos + 3, end)) {
      int codePoint =
          ((b0 & 0x07) << 18)
              | ((bytes[pos + 1] & 0x3F) << 12)
              | ((bytes[pos + 2] & 0x3F) << 6)
              | (bytes[pos + 3] & 0x3F);
      return decoded(codePoint, pos + 4);
    }
    return decoded(REPLACEMENT_CHARACTER, pos + 1);
  }

  private static boolean continuation(byte[] bytes, int pos, int end) {
    return pos < end && (bytes[pos] & 0xC0) == 0x80;
  }

  private static boolean validThreeByteSecond(byte[] bytes, int first, int pos, int end) {
    if (!continuation(bytes, pos, end)) {
      return false;
    }
    int second = bytes[pos] & 0xFF;
    return (first != 0xE0 || second >= 0xA0) && (first != 0xED || second <= 0x9F);
  }

  private static boolean validFourByteSecond(byte[] bytes, int first, int pos, int end) {
    if (!continuation(bytes, pos, end)) {
      return false;
    }
    int second = bytes[pos] & 0xFF;
    return (first != 0xF0 || second >= 0x90) && (first != 0xF4 || second <= 0x8F);
  }

  private static long decoded(int codePoint, int next) {
    return ((long) next << Integer.SIZE) | (codePoint & 0xFFFF_FFFFL);
  }

  private static boolean unicodeEqualsIgnoreCase(int left, int right) {
    if (left == right) {
      return true;
    }
    int folded = Inst.simpleFold(left);
    while (folded != left) {
      if (folded == right) {
        return true;
      }
      folded = Inst.simpleFold(folded);
    }
    return false;
  }
}
