// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/** Stateless 2-byte UTF-16 64-bit SWAR scanning kernels over {@link MemorySegment}. */
public final class SegmentShortSwarScan {

  public static final int UNSUPPORTED = -2;
  private static final long SHORT_ONES = 0x0001_0001_0001_0001L;
  private static final long SHORT_HIGH_BITS = 0x8000_8000_8000_8000L;

  private SegmentShortSwarScan() {}

  public static int indexOfCharClassUtf16(
      MemorySegment segment, long byteOffset, int charLength, int[] ranges, int start) {
    if (ranges.length == 0 || ranges.length > 4) {
      return UNSUPPORTED;
    }
    if (charLength < 4) {
      return UNSUPPORTED;
    }

    int pos = Math.max(0, start);
    int wordEnd = charLength - 4;

    int r0 = ranges[0];
    int r1 = ranges[1];
    boolean hasSecondRange = ranges.length >= 4;
    int r2 = hasSecondRange ? ranges[2] : 0;
    int r3 = hasSecondRange ? ranges[3] : 0;

    long low1 = (r0 & 0xFFFFL) * SHORT_ONES;
    long high1 = (r1 & 0xFFFFL) * SHORT_ONES;
    long low2 = hasSecondRange ? (r2 & 0xFFFFL) * SHORT_ONES : 0;
    long high2 = hasSecondRange ? (r3 & 0xFFFFL) * SHORT_ONES : 0;

    while (pos <= wordEnd) {
      long word = segment.get(ValueLayout.JAVA_LONG_UNALIGNED, byteOffset + ((long) pos << 1));

      long ge1 = (((word | SHORT_HIGH_BITS) - low1) ^ (~word & SHORT_HIGH_BITS)) & SHORT_HIGH_BITS;
      long le1 = (((high1 | SHORT_HIGH_BITS) - word) ^ (word & SHORT_HIGH_BITS)) & SHORT_HIGH_BITS;
      long inRange1 = ge1 & le1;

      long inRange;
      if (hasSecondRange) {
        long ge2 =
            (((word | SHORT_HIGH_BITS) - low2) ^ (~word & SHORT_HIGH_BITS)) & SHORT_HIGH_BITS;
        long le2 =
            (((high2 | SHORT_HIGH_BITS) - word) ^ (word & SHORT_HIGH_BITS)) & SHORT_HIGH_BITS;
        long inRange2 = ge2 & le2;
        inRange = inRange1 | inRange2;
      } else {
        inRange = inRange1;
      }

      if (inRange != 0) {
        for (int i = pos; i < pos + 4; i++) {
          int c =
              segment.get(ValueLayout.JAVA_SHORT_UNALIGNED, byteOffset + ((long) i << 1)) & 0xFFFF;
          if ((c >= r0 && c <= r1) || (hasSecondRange && c >= r2 && c <= r3)) {
            if (Character.isLowSurrogate((char) c) && i > 0) {
              char prev =
                  (char)
                      (segment.get(
                              ValueLayout.JAVA_SHORT_UNALIGNED, byteOffset + ((long) (i - 1) << 1))
                          & 0xFFFF);
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

    // Scalar tail
    for (int i = pos; i < charLength; i++) {
      int c = segment.get(ValueLayout.JAVA_SHORT_UNALIGNED, byteOffset + ((long) i << 1)) & 0xFFFF;
      if ((c >= r0 && c <= r1) || (hasSecondRange && c >= r2 && c <= r3)) {
        if (Character.isLowSurrogate((char) c) && i > 0) {
          char prev =
              (char)
                  (segment.get(ValueLayout.JAVA_SHORT_UNALIGNED, byteOffset + ((long) (i - 1) << 1))
                      & 0xFFFF);
          if (Character.isHighSurrogate(prev)) {
            continue;
          }
        }
        return i;
      }
    }
    return -1;
  }

  public static int indexOfIgnoreCaseUtf16(
      MemorySegment segment, long byteOffset, int charLength, String prefix, int start) {
    int prefixLen = prefix.length();
    for (int i = 0; i < prefixLen; i++) {
      if (prefix.charAt(i) > 127) {
        return UNSUPPORTED;
      }
    }

    int pos = Math.max(0, start);
    int wordEnd = charLength - 4;

    char first = prefix.charAt(0);
    short low = (short) asciiLower(first);
    short high = (short) asciiUpper(first);
    long repeatedLow = (low & 0xFFFFL) * SHORT_ONES;
    long repeatedHigh = (high & 0xFFFFL) * SHORT_ONES;

    while (pos <= wordEnd) {
      long word = segment.get(ValueLayout.JAVA_LONG_UNALIGNED, byteOffset + ((long) pos << 1));
      long diffLow = word ^ repeatedLow;
      long diffHigh = word ^ repeatedHigh;
      long matches =
          (((diffLow - SHORT_ONES) & ~diffLow) | ((diffHigh - SHORT_ONES) & ~diffHigh))
              & SHORT_HIGH_BITS;

      if (matches != 0) {
        for (int i = pos; i < pos + 4; i++) {
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
          (char)
              (segment.get(
                      ValueLayout.JAVA_SHORT_UNALIGNED, byteOffset + ((long) (charIndex + i) << 1))
                  & 0xFFFF);
      char c2 = prefix.charAt(i);
      if (c1 != c2 && asciiLower(c1) != asciiLower(c2)) {
        return false;
      }
    }
    return true;
  }

  private static char asciiLower(char ch) {
    return ch >= 'A' && ch <= 'Z' ? (char) (ch + 32) : ch;
  }

  private static char asciiUpper(char ch) {
    return ch >= 'a' && ch <= 'z' ? (char) (ch - 32) : ch;
  }
}
