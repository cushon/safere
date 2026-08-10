// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.lang.invoke.MethodHandles.byteArrayViewVarHandle;
import static java.nio.ByteOrder.nativeOrder;

import java.lang.invoke.VarHandle;

/** Shared 64-bit SWAR kernels for scanning bounded 2-byte sequences (UTF-16 and char[]). */
public final class ShortSwarScan {
  static final long SHORT_ONES = 0x0001_0001_0001_0001L;
  static final long SHORT_HIGH_BITS = 0x8000_8000_8000_8000L;

  private static final VarHandle LONG_VIEW = byteArrayViewVarHandle(long[].class, nativeOrder());

  public static int indexOfCharClass(
      char[] chars, int offset, int length, int[] ranges, int start) {
    int numRanges = ranges.length / 2;
    if (numRanges < 1 || numRanges > 2 || (ranges.length & 1) != 0) {
      return VectorScanProvider.UNSUPPORTED;
    }

    int pos = Math.max(0, start);
    int wordEnd = length - 4; // 4 chars = 64 bits

    long low0 = (ranges[0] & 0xFFFFL) * SHORT_ONES;
    long high0 = (ranges[1] & 0xFFFFL) * SHORT_ONES;
    long low1 = numRanges > 1 ? (ranges[2] & 0xFFFFL) * SHORT_ONES : 0;
    long high1 = numRanges > 1 ? (ranges[3] & 0xFFFFL) * SHORT_ONES : 0;

    if (numRanges == 1) {
      while (pos <= wordEnd) {
        long word = loadLongFromChars(chars, offset + pos);
        long matches = exactShortRangeMask(word, low0, high0);
        if (matches != 0) {
          int limit = pos + 4;
          for (int i = pos; i < limit; i++) {
            char ch = chars[offset + i];
            if (ch >= ranges[0] && ch <= ranges[1]) {
              return i;
            }
          }
        }
        pos += 4;
      }
    } else {
      while (pos <= wordEnd) {
        long word = loadLongFromChars(chars, offset + pos);
        long matches =
            exactShortRangeMask(word, low0, high0) | exactShortRangeMask(word, low1, high1);
        if (matches != 0) {
          int limit = pos + 4;
          for (int i = pos; i < limit; i++) {
            char ch = chars[offset + i];
            if ((ch >= ranges[0] && ch <= ranges[1]) || (ch >= ranges[2] && ch <= ranges[3])) {
              return i;
            }
          }
        }
        pos += 4;
      }
    }

    for (; pos < length; pos++) {
      char ch = chars[offset + pos];
      for (int r = 0; r < numRanges; r++) {
        if (ch >= ranges[r * 2] && ch <= ranges[r * 2 + 1]) {
          return pos;
        }
      }
    }
    return -1;
  }

  public static int indexOfCharClassUtf16(
      byte[] bytes, int offset, int length, int[] ranges, int start) {
    int numRanges = ranges.length / 2;
    if (numRanges < 1 || numRanges > 2 || (ranges.length & 1) != 0) {
      return VectorScanProvider.UNSUPPORTED;
    }

    int pos = Math.max(0, start);
    int wordEnd = length - 4; // 4 shorts = 8 bytes

    long low0 = (ranges[0] & 0xFFFFL) * SHORT_ONES;
    long high0 = (ranges[1] & 0xFFFFL) * SHORT_ONES;
    long low1 = numRanges > 1 ? (ranges[2] & 0xFFFFL) * SHORT_ONES : 0;
    long high1 = numRanges > 1 ? (ranges[3] & 0xFFFFL) * SHORT_ONES : 0;

    if (numRanges == 1) {
      while (pos <= wordEnd) {
        long word = (long) LONG_VIEW.get(bytes, offset + (pos << 1));
        long matches = exactShortRangeMask(word, low0, high0);
        if (matches != 0) {
          int limit = pos + 4;
          for (int i = pos; i < limit; i++) {
            char ch =
                (char)
                    ((bytes[offset + (i << 1)] & 0xFF)
                        | ((bytes[offset + (i << 1) + 1] & 0xFF) << 8));
            if (ch >= ranges[0] && ch <= ranges[1]) {
              return i;
            }
          }
        }
        pos += 4;
      }
    } else {
      while (pos <= wordEnd) {
        long word = (long) LONG_VIEW.get(bytes, offset + (pos << 1));
        long matches =
            exactShortRangeMask(word, low0, high0) | exactShortRangeMask(word, low1, high1);
        if (matches != 0) {
          int limit = pos + 4;
          for (int i = pos; i < limit; i++) {
            char ch =
                (char)
                    ((bytes[offset + (i << 1)] & 0xFF)
                        | ((bytes[offset + (i << 1) + 1] & 0xFF) << 8));
            if ((ch >= ranges[0] && ch <= ranges[1]) || (ch >= ranges[2] && ch <= ranges[3])) {
              return i;
            }
          }
        }
        pos += 4;
      }
    }

    for (; pos < length; pos++) {
      char ch =
          (char)
              ((bytes[offset + (pos << 1)] & 0xFF)
                  | ((bytes[offset + (pos << 1) + 1] & 0xFF) << 8));
      for (int r = 0; r < numRanges; r++) {
        if (ch >= ranges[r * 2] && ch <= ranges[r * 2 + 1]) {
          return pos;
        }
      }
    }
    return -1;
  }

  public static int indexOfIgnoreCase(
      char[] chars, int offset, int length, String prefix, int start) {
    int prefixLen = prefix.length();
    for (int i = 0; i < prefixLen; i++) {
      if (prefix.charAt(i) > 127) {
        return VectorScanProvider.UNSUPPORTED;
      }
    }

    int pos = Math.max(0, start);
    int wordEnd = length - 4;

    char first = prefix.charAt(0);
    short low = (short) VectorScanProvider.asciiLower(first);
    short high = (short) VectorScanProvider.asciiUpper(first);
    long repeatedLow = (low & 0xFFFFL) * SHORT_ONES;
    long repeatedHigh = (high & 0xFFFFL) * SHORT_ONES;

    while (pos <= wordEnd) {
      long word = loadLongFromChars(chars, offset + pos);
      long diffLow = word ^ repeatedLow;
      long diffHigh = word ^ repeatedHigh;
      long matches =
          (((diffLow - SHORT_ONES) & ~diffLow) | ((diffHigh - SHORT_ONES) & ~diffHigh))
              & SHORT_HIGH_BITS;

      if (matches != 0) {
        int limit = pos + 4;
        for (int i = pos; i < limit; i++) {
          if (i + prefixLen <= length
              && regionMatchesAsciiIgnoreCase(chars, offset + i, prefix, prefixLen)) {
            return i;
          }
        }
      }
      pos += 4;
    }

    int scalarLimit = length - prefixLen;
    for (; pos <= scalarLimit; pos++) {
      if (regionMatchesAsciiIgnoreCase(chars, offset + pos, prefix, prefixLen)) {
        return pos;
      }
    }
    return -1;
  }

  public static int indexOfIgnoreCaseUtf16(
      byte[] bytes, int offset, int length, String prefix, int start) {
    int prefixLen = prefix.length();
    for (int i = 0; i < prefixLen; i++) {
      if (prefix.charAt(i) > 127) {
        return VectorScanProvider.UNSUPPORTED;
      }
    }

    int pos = Math.max(0, start);
    int wordEnd = length - 4;

    char first = prefix.charAt(0);
    short low = (short) VectorScanProvider.asciiLower(first);
    short high = (short) VectorScanProvider.asciiUpper(first);
    long repeatedLow = (low & 0xFFFFL) * SHORT_ONES;
    long repeatedHigh = (high & 0xFFFFL) * SHORT_ONES;

    while (pos <= wordEnd) {
      long word = (long) LONG_VIEW.get(bytes, offset + (pos << 1));
      long diffLow = word ^ repeatedLow;
      long diffHigh = word ^ repeatedHigh;
      long matches =
          (((diffLow - SHORT_ONES) & ~diffLow) | ((diffHigh - SHORT_ONES) & ~diffHigh))
              & SHORT_HIGH_BITS;

      if (matches != 0) {
        int limit = pos + 4;
        for (int i = pos; i < limit; i++) {
          if (i + prefixLen <= length
              && regionMatchesAsciiIgnoreCaseUtf16(bytes, offset + (i << 1), prefix, prefixLen)) {
            return i;
          }
        }
      }
      pos += 4;
    }

    int scalarLimit = length - prefixLen;
    for (; pos <= scalarLimit; pos++) {
      if (regionMatchesAsciiIgnoreCaseUtf16(bytes, offset + (pos << 1), prefix, prefixLen)) {
        return pos;
      }
    }
    return -1;
  }

  static long exactShortRangeMask(long word, long repeatedLow, long repeatedHigh) {
    long atLeastLow = ((word | SHORT_HIGH_BITS) - repeatedLow) & SHORT_HIGH_BITS;
    long atMostHigh = ((repeatedHigh | SHORT_HIGH_BITS) - word) & SHORT_HIGH_BITS;
    return atLeastLow & atMostHigh;
  }

  private static long loadLongFromChars(char[] chars, int offset) {
    return (chars[offset] & 0xFFFFL)
        | ((chars[offset + 1] & 0xFFFFL) << 16)
        | ((chars[offset + 2] & 0xFFFFL) << 32)
        | ((chars[offset + 3] & 0xFFFFL) << 48);
  }

  private static boolean regionMatchesAsciiIgnoreCase(
      char[] chars, int offset, String prefix, int prefixLen) {
    for (int i = 0; i < prefixLen; i++) {
      char c = chars[offset + i];
      char p = prefix.charAt(i);
      if (c != p && VectorScanProvider.asciiLower(c) != VectorScanProvider.asciiLower(p)) {
        return false;
      }
    }
    return true;
  }

  private static boolean regionMatchesAsciiIgnoreCaseUtf16(
      byte[] bytes, int byteOffset, String prefix, int prefixLen) {
    for (int i = 0; i < prefixLen; i++) {
      char c =
          (char)
              ((bytes[byteOffset + (i << 1)] & 0xFF)
                  | ((bytes[byteOffset + (i << 1) + 1] & 0xFF) << 8));
      char p = prefix.charAt(i);
      if (c != p && VectorScanProvider.asciiLower(c) != VectorScanProvider.asciiLower(p)) {
        return false;
      }
    }
    return true;
  }

  private ShortSwarScan() {}
}
