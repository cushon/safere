// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/** Stateless 1-byte 64-bit SWAR scanning kernels over {@link MemorySegment}. */
public final class SegmentByteSwarScan {

  public static final int UNSUPPORTED = -2;
  private static final long BYTE_ONES = 0x0101_0101_0101_0101L;
  private static final long BYTE_HIGH_BITS = 0x8080_8080_8080_8080L;

  private SegmentByteSwarScan() {}

  public static int indexOfAsciiClass(
      MemorySegment segment, long offset, long length, int[] ranges, int start) {
    if (ranges.length == 0 || ranges.length > 4) {
      return UNSUPPORTED;
    }
    if (length < Long.BYTES) {
      return UNSUPPORTED;
    }

    long pos = Math.max(0, start);
    long wordEnd = length - Long.BYTES;

    int r0 = ranges[0];
    int r1 = ranges[1];
    boolean hasSecondRange = ranges.length >= 4;
    int r2 = hasSecondRange ? ranges[2] : 0;
    int r3 = hasSecondRange ? ranges[3] : 0;

    long low1 = (r0 & 0xFFL) * BYTE_ONES;
    long high1 = (r1 & 0xFFL) * BYTE_ONES;
    long low2 = hasSecondRange ? (r2 & 0xFFL) * BYTE_ONES : 0;
    long high2 = hasSecondRange ? (r3 & 0xFFL) * BYTE_ONES : 0;

    while (pos <= wordEnd) {
      long word = segment.get(ValueLayout.JAVA_LONG_UNALIGNED, offset + pos);

      // SWAR range check: (word >= low) & (word <= high)
      long ge1 = (((word | BYTE_HIGH_BITS) - low1) ^ (~word & BYTE_HIGH_BITS)) & BYTE_HIGH_BITS;
      long le1 = (((high1 | BYTE_HIGH_BITS) - word) ^ (word & BYTE_HIGH_BITS)) & BYTE_HIGH_BITS;
      long inRange1 = ge1 & le1;

      long inRange;
      if (hasSecondRange) {
        long ge2 = (((word | BYTE_HIGH_BITS) - low2) ^ (~word & BYTE_HIGH_BITS)) & BYTE_HIGH_BITS;
        long le2 = (((high2 | BYTE_HIGH_BITS) - word) ^ (word & BYTE_HIGH_BITS)) & BYTE_HIGH_BITS;
        long inRange2 = ge2 & le2;
        inRange = inRange1 | inRange2;
      } else {
        inRange = inRange1;
      }

      if (inRange != 0) {
        long limit = pos + Long.BYTES;
        for (long i = pos; i < limit; i++) {
          int b = segment.get(ValueLayout.JAVA_BYTE, offset + i) & 0xFF;
          if ((b >= r0 && b <= r1) || (hasSecondRange && b >= r2 && b <= r3)) {
            return (int) i;
          }
        }
      }
      pos += Long.BYTES;
    }

    // Scalar tail
    for (long i = pos; i < length; i++) {
      int b = segment.get(ValueLayout.JAVA_BYTE, offset + i) & 0xFF;
      if ((b >= r0 && b <= r1) || (hasSecondRange && b >= r2 && b <= r3)) {
        return (int) i;
      }
    }
    return -1;
  }

  public static int indexOfIgnoreCase(
      MemorySegment segment, long offset, long length, String prefix, int start) {
    int prefixLen = prefix.length();
    for (int i = 0; i < prefixLen; i++) {
      if (prefix.charAt(i) > 127) {
        return UNSUPPORTED;
      }
    }

    long pos = Math.max(0, start);
    long wordEnd = length - Long.BYTES;

    char first = prefix.charAt(0);
    byte low = (byte) asciiLower(first);
    byte high = (byte) asciiUpper(first);
    long repeatedLow = (low & 0xFFL) * BYTE_ONES;
    long repeatedHigh = (high & 0xFFL) * BYTE_ONES;

    while (pos <= wordEnd) {
      long word = segment.get(ValueLayout.JAVA_LONG_UNALIGNED, offset + pos);
      long diffLow = word ^ repeatedLow;
      long diffHigh = word ^ repeatedHigh;
      long matches =
          (((diffLow - BYTE_ONES) & ~diffLow) | ((diffHigh - BYTE_ONES) & ~diffHigh))
              & BYTE_HIGH_BITS;

      if (matches != 0) {
        long limit = pos + Long.BYTES;
        for (long i = pos; i < limit; i++) {
          if (i + prefixLen <= length
              && regionMatchesAsciiIgnoreCase(segment, offset + i, prefix, prefixLen)) {
            return (int) i;
          }
        }
      }
      pos += Long.BYTES;
    }

    for (long i = pos; i <= length - prefixLen; i++) {
      if (regionMatchesAsciiIgnoreCase(segment, offset + i, prefix, prefixLen)) {
        return (int) i;
      }
    }
    return -1;
  }

  private static boolean regionMatchesAsciiIgnoreCase(
      MemorySegment segment, long offset, String prefix, int len) {
    for (int i = 0; i < len; i++) {
      char c1 = (char) (segment.get(ValueLayout.JAVA_BYTE, offset + i) & 0xFF);
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
