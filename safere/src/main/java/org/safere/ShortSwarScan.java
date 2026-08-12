// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.lang.invoke.MethodHandles.byteArrayViewVarHandle;
import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static java.nio.ByteOrder.nativeOrder;
import static org.safere.internal.Ascii.toLowerCase;
import static org.safere.internal.Ascii.toUpperCase;
import static org.safere.internal.Swar.SHORT_HIGH_BITS;
import static org.safere.internal.Swar.SHORT_ONES;

import java.lang.invoke.VarHandle;
import org.safere.internal.Ascii;
import org.safere.internal.Swar;

/** Shared 64-bit SWAR kernels for scanning bounded 2-byte sequences (UTF-16 and char[]). */
final class ShortSwarScan {

  private static final VarHandle LONG_VIEW = byteArrayViewVarHandle(long[].class, LITTLE_ENDIAN);

  public static int indexOfCharClass(
      char[] chars, int offset, int length, int[] ranges, int start) {
    if (!Swar.supportsBmpCodeUnitRanges(ranges, 2)) {
      return VectorScanProvider.UNSUPPORTED;
    }
    int numRanges = ranges.length / 2;
    if (requiresScalarRangeComparison(ranges)) {
      return scalarIndexOfCharClass(chars, offset, length, ranges, start);
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
    if (!Swar.supportsBmpCodeUnitRanges(ranges, 2) || nativeOrder() == BIG_ENDIAN) {
      return VectorScanProvider.UNSUPPORTED;
    }
    int numRanges = ranges.length / 2;
    if (requiresScalarRangeComparison(ranges)) {
      return scalarIndexOfCharClassUtf16(bytes, offset, length, ranges, start);
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
    if (prefixLen == 0) {
      return Math.min(Math.max(0, start), length);
    }
    for (int i = 0; i < prefixLen; i++) {
      if (prefix.charAt(i) > 127) {
        return VectorScanProvider.UNSUPPORTED;
      }
    }
    if (prefixLen > 1) {
      return Ascii.indexOfIgnoreCase(chars, offset, length, prefix, start);
    }

    int pos = Math.max(0, start);
    int wordEnd = length - 4;

    char first = prefix.charAt(0);
    short low = (short) toLowerCase(first);
    short high = (short) toUpperCase(first);
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
    if (prefixLen == 0) {
      return Math.min(Math.max(0, start), length);
    }
    for (int i = 0; i < prefixLen; i++) {
      if (prefix.charAt(i) > 127) {
        return VectorScanProvider.UNSUPPORTED;
      }
    }
    if (prefixLen > 1) {
      return Ascii.indexOfIgnoreCaseUtf16(bytes, offset, length, prefix, start);
    }
    if (nativeOrder() == BIG_ENDIAN) {
      return VectorScanProvider.UNSUPPORTED;
    }

    int pos = Math.max(0, start);
    int wordEnd = length - 4;

    char first = prefix.charAt(0);
    short low = (short) toLowerCase(first);
    short high = (short) toUpperCase(first);
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
    return Swar.exactShortRangeMask(word, repeatedLow, repeatedHigh);
  }

  private static boolean requiresScalarRangeComparison(int[] ranges) {
    for (int i = 1; i < ranges.length; i += 2) {
      if (ranges[i] > 0x7FFF) {
        return true;
      }
    }
    return false;
  }

  private static int scalarIndexOfCharClass(
      char[] chars, int offset, int length, int[] ranges, int start) {
    for (int i = Math.max(0, start); i < length; i++) {
      char ch = chars[offset + i];
      for (int r = 0; r < ranges.length; r += 2) {
        if (ch >= ranges[r] && ch <= ranges[r + 1]) {
          return i;
        }
      }
    }
    return -1;
  }

  private static int scalarIndexOfCharClassUtf16(
      byte[] bytes, int offset, int length, int[] ranges, int start) {
    for (int i = Math.max(0, start); i < length; i++) {
      int byteIndex = offset + (i << 1);
      char ch = (char) ((bytes[byteIndex] & 0xFF) | ((bytes[byteIndex + 1] & 0xFF) << 8));
      for (int r = 0; r < ranges.length; r += 2) {
        if (ch >= ranges[r] && ch <= ranges[r + 1]) {
          return i;
        }
      }
    }
    return -1;
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
      if (c != p && toLowerCase(c) != toLowerCase(p)) {
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
      if (c != p && toLowerCase(c) != toLowerCase(p)) {
        return false;
      }
    }
    return true;
  }

  private ShortSwarScan() {}
}
