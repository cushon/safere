// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.safere.internal.Ascii;
import org.safere.internal.Swar;

/** Stateless 2-byte UTF-16 64-bit SWAR scanning kernels over {@link MemorySegment}. */
final class SegmentShortSwarScan {

  private static final ValueLayout.OfLong UTF16_LONG =
      ValueLayout.JAVA_LONG_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN);
  private static final ValueLayout.OfShort UTF16_SHORT =
      ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN);

  private SegmentShortSwarScan() {}

  static int indexOfCharClassUtf16(
      MemorySegment segment, long byteOffset, int charLength, int[] ranges, int start) {
    if (!Swar.supportsBmpCodeUnitRanges(ranges, 2)) {
      return Swar.UNSUPPORTED;
    }
    int numRanges = ranges.length / 2;
    if (requiresScalarRangeComparison(ranges)) {
      return scalarIndexOfCharClassUtf16(segment, byteOffset, charLength, ranges, start);
    }

    int pos = Math.max(0, start);
    int wordEnd = charLength - 4;

    long low0 = (ranges[0] & 0xFFFFL) * Swar.SHORT_ONES;
    long high0 = (ranges[1] & 0xFFFFL) * Swar.SHORT_ONES;
    long low1 = numRanges > 1 ? (ranges[2] & 0xFFFFL) * Swar.SHORT_ONES : 0;
    long high1 = numRanges > 1 ? (ranges[3] & 0xFFFFL) * Swar.SHORT_ONES : 0;

    if (numRanges == 1) {
      while (pos <= wordEnd) {
        long word = segment.get(UTF16_LONG, byteOffset + ((long) pos << 1));
        long matches = Swar.exactShortRangeMask(word, low0, high0);
        if (matches != 0) {
          int limit = pos + 4;
          for (int i = pos; i < limit; i++) {
            char ch = (char) (segment.get(UTF16_SHORT, byteOffset + ((long) i << 1)) & 0xFFFF);
            if (ch >= ranges[0] && ch <= ranges[1]) {
              if (Character.isLowSurrogate(ch) && i > 0) {
                char prev =
                    (char) (segment.get(UTF16_SHORT, byteOffset + ((long) (i - 1) << 1)) & 0xFFFF);
                if (Character.isHighSurrogate(prev)) {
                  continue;
                }
              }
              return i;
            }
          }
        }
        pos += 4;
      }
    } else {
      while (pos <= wordEnd) {
        long word = segment.get(UTF16_LONG, byteOffset + ((long) pos << 1));
        long matches =
            Swar.exactShortRangeMask(word, low0, high0)
                | Swar.exactShortRangeMask(word, low1, high1);
        if (matches != 0) {
          int limit = pos + 4;
          for (int i = pos; i < limit; i++) {
            char ch = (char) (segment.get(UTF16_SHORT, byteOffset + ((long) i << 1)) & 0xFFFF);
            if ((ch >= ranges[0] && ch <= ranges[1]) || (ch >= ranges[2] && ch <= ranges[3])) {
              if (Character.isLowSurrogate(ch) && i > 0) {
                char prev =
                    (char) (segment.get(UTF16_SHORT, byteOffset + ((long) (i - 1) << 1)) & 0xFFFF);
                if (Character.isHighSurrogate(prev)) {
                  continue;
                }
              }
              return i;
            }
          }
        }
        pos += 4;
      }
    }

    // Scalar tail
    for (int i = pos; i < charLength; i++) {
      char ch = (char) (segment.get(UTF16_SHORT, byteOffset + ((long) i << 1)) & 0xFFFF);
      for (int r = 0; r < numRanges; r++) {
        if (ch >= ranges[r * 2] && ch <= ranges[r * 2 + 1]) {
          if (Character.isLowSurrogate(ch) && i > 0) {
            char prev =
                (char) (segment.get(UTF16_SHORT, byteOffset + ((long) (i - 1) << 1)) & 0xFFFF);
            if (Character.isHighSurrogate(prev)) {
              continue;
            }
          }
          return i;
        }
      }
    }
    return -1;
  }

  static int indexOfIgnoreCaseUtf16(
      MemorySegment segment, long byteOffset, int charLength, String prefix, int start) {
    int prefixLen = prefix.length();
    if (prefixLen == 0) {
      return Math.min(Math.max(0, start), charLength);
    }
    for (int i = 0; i < prefixLen; i++) {
      if (prefix.charAt(i) > 127) {
        return Swar.UNSUPPORTED;
      }
    }
    if (prefixLen > 1) {
      return SegmentAsciiSearch.indexOfIgnoreCaseUtf16(
          segment, byteOffset, charLength, prefix, start);
    }

    int pos = Math.max(0, start);
    int wordEnd = charLength - 4;

    char first = prefix.charAt(0);
    short low = (short) Ascii.toLowerCase(first);
    short high = (short) Ascii.toUpperCase(first);
    long repeatedLow = (low & 0xFFFFL) * Swar.SHORT_ONES;
    long repeatedHigh = (high & 0xFFFFL) * Swar.SHORT_ONES;

    while (pos <= wordEnd) {
      long word = segment.get(UTF16_LONG, byteOffset + ((long) pos << 1));
      long diffLow = word ^ repeatedLow;
      long diffHigh = word ^ repeatedHigh;
      long matches =
          (((diffLow - Swar.SHORT_ONES) & ~diffLow) | ((diffHigh - Swar.SHORT_ONES) & ~diffHigh))
              & Swar.SHORT_HIGH_BITS;

      if (matches != 0) {
        int limit = pos + 4;
        for (int i = pos; i < limit; i++) {
          if (i + prefixLen <= charLength
              && regionMatchesAsciiIgnoreCaseUtf16(segment, byteOffset, i, prefix, prefixLen)) {
            return i;
          }
        }
      }
      pos += 4;
    }

    for (int i = pos; i <= charLength - prefixLen; i++) {
      if (regionMatchesAsciiIgnoreCaseUtf16(segment, byteOffset, i, prefix, prefixLen)) {
        return i;
      }
    }
    return -1;
  }

  private static boolean regionMatchesAsciiIgnoreCaseUtf16(
      MemorySegment segment, long byteOffset, int charIndex, String prefix, int len) {
    for (int i = 0; i < len; i++) {
      char c1 =
          (char) (segment.get(UTF16_SHORT, byteOffset + ((long) (charIndex + i) << 1)) & 0xFFFF);
      char c2 = prefix.charAt(i);
      if (c1 != c2 && Ascii.toLowerCase(c1) != Ascii.toLowerCase(c2)) {
        return false;
      }
    }
    return true;
  }

  private static boolean requiresScalarRangeComparison(int[] ranges) {
    for (int i = 1; i < ranges.length; i += 2) {
      if (ranges[i] > 0x7FFF) {
        return true;
      }
    }
    return false;
  }

  private static int scalarIndexOfCharClassUtf16(
      MemorySegment segment, long byteOffset, int charLength, int[] ranges, int start) {
    for (int i = Math.max(0, start); i < charLength; i++) {
      int ch = segment.get(UTF16_SHORT, byteOffset + ((long) i << 1)) & 0xFFFF;
      for (int r = 0; r < ranges.length; r += 2) {
        if (ch >= ranges[r] && ch <= ranges[r + 1]) {
          return i;
        }
      }
    }
    return -1;
  }
}
