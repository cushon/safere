// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * 64-bit SWAR (SIMD Within A Register) scanner for String backing byte arrays. Provides broadword
 * scanning (8 chars/step for Latin-1, 4 chars/step for UTF-16) when the Vector API is unavailable
 * or disabled.
 */
final class SegmentSwarScanner {
  private static final long BYTE_LOW_BITS = 0x7F7F_7F7F_7F7F_7F7FL;
  private static final long BYTE_HIGH_BITS = 0x8080_8080_8080_8080L;
  private static final long BYTE_ONES = 0x0101_0101_0101_0101L;

  private static final long SHORT_HIGH_BITS = 0x8000_8000_8000_8000L;
  private static final long SHORT_ONES = 0x0001_0001_0001_0001L;

  private static final VarHandle LONG_VIEW =
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());

  static int indexOfCharClass(
      SegmentAndCharset sac, String text, int[] ranges, int start, int numRanges) {
    if (numRanges > 2) {
      return -2; // Route classes with >= 3 ranges to the O(1) 64-bit scalar bitmap table
    }
    if (sac.isLatin1()) {
      return indexOfLatin1(sac.value(), text, ranges, start, numRanges);
    } else {
      return indexOfUtf16(sac.value(), text, ranges, start, numRanges);
    }
  }

  private static int indexOfLatin1(
      byte[] value, String text, int[] ranges, int start, int numRanges) {
    int length = text.length();
    int pos = start;
    int wordEnd = length - Long.BYTES;

    long low0 = ranges[0] * BYTE_ONES;
    long high0 = ranges[1] * BYTE_ONES;
    long low1 = numRanges > 1 ? ranges[2] * BYTE_ONES : 0;
    long high1 = numRanges > 1 ? ranges[3] * BYTE_ONES : 0;

    if (numRanges == 1) {
      while (pos <= wordEnd) {
        long word = (long) LONG_VIEW.get(value, pos);
        long values = word & BYTE_LOW_BITS;
        long ascii = ~word & BYTE_HIGH_BITS;

        long matches = exactAsciiRangeMask(values, ascii, low0, high0);
        if (matches != 0) {
          int wordLimit = pos + Long.BYTES;
          for (int i = pos; i < wordLimit; i++) {
            char ch = text.charAt(i);
            if (ch >= ranges[0] && ch <= ranges[1]) {
              return i;
            }
          }
        }
        pos += Long.BYTES;
      }
    } else {
      while (pos <= wordEnd) {
        long word = (long) LONG_VIEW.get(value, pos);
        long values = word & BYTE_LOW_BITS;
        long ascii = ~word & BYTE_HIGH_BITS;

        long matches =
            exactAsciiRangeMask(values, ascii, low0, high0)
                | exactAsciiRangeMask(values, ascii, low1, high1);

        if (matches != 0) {
          int wordLimit = pos + Long.BYTES;
          for (int i = pos; i < wordLimit; i++) {
            char ch = text.charAt(i);
            if ((ch >= ranges[0] && ch <= ranges[1]) || (ch >= ranges[2] && ch <= ranges[3])) {
              return i;
            }
          }
        }
        pos += Long.BYTES;
      }
    }

    for (; pos < length; pos++) {
      char ch = text.charAt(pos);
      for (int r = 0; r < numRanges; r++) {
        if (ch >= ranges[r * 2] && ch <= ranges[r * 2 + 1]) {
          return pos;
        }
      }
    }
    return -1;
  }

  private static int indexOfUtf16(
      byte[] value, String text, int[] ranges, int start, int numRanges) {
    int length = text.length();
    int pos = start;
    int wordEnd = length - 4; // 4 shorts = 8 bytes

    long low0 = ranges[0] * SHORT_ONES;
    long high0 = ranges[1] * SHORT_ONES;
    long low1 = numRanges > 1 ? ranges[2] * SHORT_ONES : 0;
    long high1 = numRanges > 1 ? ranges[3] * SHORT_ONES : 0;

    if (numRanges == 1) {
      while (pos <= wordEnd) {
        long word = (long) LONG_VIEW.get(value, pos << 1);
        long matches = exactShortRangeMask(word, low0, high0);

        if (matches != 0) {
          int wordLimit = pos + 4;
          for (int i = pos; i < wordLimit; i++) {
            char ch = text.charAt(i);
            if (ch >= ranges[0] && ch <= ranges[1]) {
              return i;
            }
          }
        }
        pos += 4;
      }
    } else {
      while (pos <= wordEnd) {
        long word = (long) LONG_VIEW.get(value, pos << 1);
        long matches =
            exactShortRangeMask(word, low0, high0) | exactShortRangeMask(word, low1, high1);

        if (matches != 0) {
          int wordLimit = pos + 4;
          for (int i = pos; i < wordLimit; i++) {
            char ch = text.charAt(i);
            if ((ch >= ranges[0] && ch <= ranges[1]) || (ch >= ranges[2] && ch <= ranges[3])) {
              return i;
            }
          }
        }
        pos += 4;
      }
    }

    for (; pos < length; pos++) {
      char ch = text.charAt(pos);
      for (int r = 0; r < numRanges; r++) {
        if (ch >= ranges[r * 2] && ch <= ranges[r * 2 + 1]) {
          return pos;
        }
      }
    }
    return -1;
  }

  static int indexOfIgnoreCase(SegmentAndCharset sac, String text, String prefix, int start) {
    int prefixLen = prefix.length();
    if (prefixLen == 0) {
      return start;
    }
    for (int i = 0; i < prefixLen; i++) {
      if (prefix.charAt(i) > 127) {
        return -2;
      }
    }
    if (sac.isLatin1()) {
      return indexOfIgnoreCaseLatin1(sac.value(), text, prefix, start);
    } else {
      return indexOfIgnoreCaseUtf16(sac.value(), text, prefix, start);
    }
  }

  private static int indexOfIgnoreCaseLatin1(byte[] value, String text, String prefix, int start) {
    int textLen = text.length();
    int prefixLen = prefix.length();
    int pos = start;
    int wordEnd = textLen - Long.BYTES;

    char first = prefix.charAt(0);
    byte low = (byte) VectorScanProvider.asciiLower(first);
    byte high = (byte) VectorScanProvider.asciiUpper(first);
    long repeatedLow = (low & 0xFFL) * BYTE_ONES;
    long repeatedHigh = (high & 0xFFL) * BYTE_ONES;

    while (pos <= wordEnd) {
      long word = (long) LONG_VIEW.get(value, pos);
      long diffLow = word ^ repeatedLow;
      long diffHigh = word ^ repeatedHigh;
      long matches =
          (((diffLow - BYTE_ONES) & ~diffLow) | ((diffHigh - BYTE_ONES) & ~diffHigh))
              & BYTE_HIGH_BITS;

      if (matches != 0) {
        int wordLimit = pos + Long.BYTES;
        for (int i = pos; i < wordLimit; i++) {
          if (i + prefixLen <= textLen
              && Matcher.regionMatchesAsciiIgnoreCase(text, i, prefix, 0, prefixLen)) {
            return i;
          }
        }
      }
      pos += Long.BYTES;
    }

    int scalarLimit = textLen - prefixLen;
    for (; pos <= scalarLimit; pos++) {
      if (Matcher.regionMatchesAsciiIgnoreCase(text, pos, prefix, 0, prefixLen)) {
        return pos;
      }
    }
    return -1;
  }

  private static int indexOfIgnoreCaseUtf16(byte[] value, String text, String prefix, int start) {
    int textLen = text.length();
    int prefixLen = prefix.length();
    int pos = start;
    int wordEnd = textLen - 4; // 4 shorts = 8 bytes

    char first = prefix.charAt(0);
    short low = (short) VectorScanProvider.asciiLower(first);
    short high = (short) VectorScanProvider.asciiUpper(first);
    long repeatedLow = (low & 0xFFFFL) * SHORT_ONES;
    long repeatedHigh = (high & 0xFFFFL) * SHORT_ONES;

    while (pos <= wordEnd) {
      long word = (long) LONG_VIEW.get(value, pos << 1);
      long diffLow = word ^ repeatedLow;
      long diffHigh = word ^ repeatedHigh;
      long matches =
          (((diffLow - SHORT_ONES) & ~diffLow) | ((diffHigh - SHORT_ONES) & ~diffHigh))
              & SHORT_HIGH_BITS;

      if (matches != 0) {
        int wordLimit = pos + 4;
        for (int i = pos; i < wordLimit; i++) {
          if (i + prefixLen <= textLen
              && Matcher.regionMatchesAsciiIgnoreCase(text, i, prefix, 0, prefixLen)) {
            return i;
          }
        }
      }
      pos += 4;
    }

    int scalarLimit = textLen - prefixLen;
    for (; pos <= scalarLimit; pos++) {
      if (Matcher.regionMatchesAsciiIgnoreCase(text, pos, prefix, 0, prefixLen)) {
        return pos;
      }
    }
    return -1;
  }

  private static long exactAsciiRangeMask(
      long values, long ascii, long repeatedLow, long repeatedHigh) {
    long atLeastLow = ((values | BYTE_HIGH_BITS) - repeatedLow) & BYTE_HIGH_BITS;
    long atMostHigh = ((repeatedHigh | BYTE_HIGH_BITS) - values) & BYTE_HIGH_BITS;
    return ascii & atLeastLow & atMostHigh;
  }

  private static long exactShortRangeMask(long values, long repeatedLow, long repeatedHigh) {
    long atLeastLow = ((values | SHORT_HIGH_BITS) - repeatedLow) & SHORT_HIGH_BITS;
    long atMostHigh = ((repeatedHigh | SHORT_HIGH_BITS) - values) & SHORT_HIGH_BITS;
    return atLeastLow & atMostHigh;
  }

  private SegmentSwarScanner() {}
}
