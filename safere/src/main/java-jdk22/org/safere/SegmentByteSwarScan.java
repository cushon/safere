// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.safere.internal.Ascii;
import org.safere.internal.Swar;

/** Stateless 1-byte 64-bit SWAR scanning kernels over {@link MemorySegment}. */
final class SegmentByteSwarScan {

  private SegmentByteSwarScan() {}

  public static int indexOfAsciiClass(
      MemorySegment segment, long offset, long length, int[] ranges, int start) {
    if (length > Integer.MAX_VALUE) {
      return Swar.UNSUPPORTED;
    }
    if (!Swar.supportsAsciiRanges(ranges, 2)) {
      return Swar.UNSUPPORTED;
    }
    int numRanges = ranges.length / 2;

    long pos = Math.max(0, start);
    long wordEnd = length - Long.BYTES;

    long low0 = (ranges[0] & 0xFFL) * Swar.BYTE_ONES;
    long high0 = (ranges[1] & 0xFFL) * Swar.BYTE_ONES;
    long low1 = numRanges > 1 ? (ranges[2] & 0xFFL) * Swar.BYTE_ONES : 0;
    long high1 = numRanges > 1 ? (ranges[3] & 0xFFL) * Swar.BYTE_ONES : 0;

    if (numRanges == 1) {
      while (pos <= wordEnd) {
        long word = segment.get(ValueLayout.JAVA_LONG_UNALIGNED, offset + pos);
        long values = word & ~Swar.BYTE_HIGH_BITS;
        long ascii = ~word & Swar.BYTE_HIGH_BITS;
        long matches = Swar.exactAsciiRangeMask(values, ascii, low0, high0);

        if (matches != 0) {
          long limit = pos + Long.BYTES;
          for (long i = pos; i < limit; i++) {
            int b = segment.get(ValueLayout.JAVA_BYTE, offset + i) & 0xFF;
            if (b >= ranges[0] && b <= ranges[1]) {
              return (int) i;
            }
          }
        }
        pos += Long.BYTES;
      }
    } else {
      while (pos <= wordEnd) {
        long word = segment.get(ValueLayout.JAVA_LONG_UNALIGNED, offset + pos);
        long values = word & ~Swar.BYTE_HIGH_BITS;
        long ascii = ~word & Swar.BYTE_HIGH_BITS;
        long matches =
            Swar.exactAsciiRangeMask(values, ascii, low0, high0)
                | Swar.exactAsciiRangeMask(values, ascii, low1, high1);

        if (matches != 0) {
          long limit = pos + Long.BYTES;
          for (long i = pos; i < limit; i++) {
            int b = segment.get(ValueLayout.JAVA_BYTE, offset + i) & 0xFF;
            if ((b >= ranges[0] && b <= ranges[1]) || (b >= ranges[2] && b <= ranges[3])) {
              return (int) i;
            }
          }
        }
        pos += Long.BYTES;
      }
    }

    // Scalar tail
    for (long i = pos; i < length; i++) {
      int b = segment.get(ValueLayout.JAVA_BYTE, offset + i) & 0xFF;
      for (int r = 0; r < numRanges; r++) {
        if (b >= ranges[r * 2] && b <= ranges[r * 2 + 1]) {
          return (int) i;
        }
      }
    }
    return -1;
  }

  public static int indexOfIgnoreCase(
      MemorySegment segment, long offset, long length, String prefix, int start) {
    if (length > Integer.MAX_VALUE) {
      return Swar.UNSUPPORTED;
    }
    int prefixLen = prefix.length();
    if (prefixLen == 0) {
      return Math.min(Math.max(0, start), (int) length);
    }
    for (int i = 0; i < prefixLen; i++) {
      if (prefix.charAt(i) > 127) {
        return Swar.UNSUPPORTED;
      }
    }
    if (prefixLen > 1) {
      return SegmentAsciiSearch.indexOfIgnoreCase(segment, offset, (int) length, prefix, start);
    }

    long pos = Math.max(0, start);
    long wordEnd = length - Long.BYTES;

    char first = prefix.charAt(0);
    byte low = (byte) Ascii.toLowerCase(first);
    byte high = (byte) Ascii.toUpperCase(first);
    long repeatedLow = (low & 0xFFL) * Swar.BYTE_ONES;
    long repeatedHigh = (high & 0xFFL) * Swar.BYTE_ONES;

    while (pos <= wordEnd) {
      long word = segment.get(ValueLayout.JAVA_LONG_UNALIGNED, offset + pos);
      long diffLow = word ^ repeatedLow;
      long diffHigh = word ^ repeatedHigh;
      long matches =
          (((diffLow - Swar.BYTE_ONES) & ~diffLow) | ((diffHigh - Swar.BYTE_ONES) & ~diffHigh))
              & Swar.BYTE_HIGH_BITS;

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
      if (c1 != c2 && Ascii.toLowerCase(c1) != Ascii.toLowerCase(c2)) {
        return false;
      }
    }
    return true;
  }
}
