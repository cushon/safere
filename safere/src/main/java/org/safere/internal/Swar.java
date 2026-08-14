// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.internal;

/** Shared 64-bit SWAR (SIMD within a register) broadword constants and arithmetic operations. */
public final class Swar {

  public static final long BYTE_ONES = 0x0101_0101_0101_0101L;
  public static final long BYTE_HIGH_BITS = 0x8080_8080_8080_8080L;

  public static final long SHORT_ONES = 0x0001_0001_0001_0001L;
  public static final long SHORT_HIGH_BITS = 0x8000_8000_8000_8000L;

  private Swar() {}

  /** Returns whether the ranges can be matched one UTF-16 code unit at a time. */
  public static boolean supportsBmpCodeUnitRanges(int[] ranges, int maximumRanges) {
    if (ranges.length < 2 || ranges.length > maximumRanges * 2 || (ranges.length & 1) != 0) {
      return false;
    }
    for (int i = 0; i < ranges.length; i += 2) {
      int low = ranges[i];
      int high = ranges[i + 1];
      if (low < 0
          || high > Character.MAX_VALUE
          || low > high
          || (low <= Character.MAX_SURROGATE && high >= Character.MIN_SURROGATE)) {
        return false;
      }
    }
    return true;
  }

  /** Returns whether the ranges can be matched by an ASCII byte kernel. */
  public static boolean supportsAsciiRanges(int[] ranges, int maximumRanges) {
    if (ranges.length < 2 || ranges.length > maximumRanges * 2 || (ranges.length & 1) != 0) {
      return false;
    }
    for (int i = 0; i < ranges.length; i += 2) {
      if (ranges[i] < 0 || ranges[i] > ranges[i + 1] || ranges[i + 1] > 0x7F) {
        return false;
      }
    }
    return true;
  }

  /** Computes a borrow-free 1-byte SWAR range mask for {@code [low, high]}. */
  public static long exactAsciiRangeMask(
      long values, long ascii, long repeatedLow, long repeatedHigh) {
    long atLeastLow = ((values | BYTE_HIGH_BITS) - repeatedLow) & BYTE_HIGH_BITS;
    long atMostHigh = ((repeatedHigh | BYTE_HIGH_BITS) - values) & BYTE_HIGH_BITS;
    return ascii & atLeastLow & atMostHigh;
  }

  /** Computes a borrow-free 2-byte UTF-16 SWAR range mask for {@code [low, high]}. */
  public static long exactShortRangeMask(long word, long repeatedLow, long repeatedHigh) {
    long atLeastLow = ((word | SHORT_HIGH_BITS) - repeatedLow) & SHORT_HIGH_BITS;
    long atMostHigh = ((repeatedHigh | SHORT_HIGH_BITS) - word) & SHORT_HIGH_BITS;
    return atLeastLow & atMostHigh;
  }
}
