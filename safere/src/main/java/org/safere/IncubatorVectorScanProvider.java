// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.util.Arrays;

/** Experimental scan operations implemented with the incubating Vector API. */
final class IncubatorVectorScanProvider implements VectorScanProvider {
  private static final int MINIMUM_INPUT_LENGTH = 64;

  @Override
  public int minimumInputLength() {
    return MINIMUM_INPUT_LENGTH;
  }

  @Override
  public int indexOfAsciiClass(byte[] bytes, int offset, int length, int[] ranges, int start) {
    return ByteVectorScan.indexOfAsciiClass(bytes, offset, length, ranges, start);
  }

  @Override
  public int indexOfAsciiClass(String text, int[] ranges, int start) {
    if (!StringSupport.hasAccess()) {
      return UNSUPPORTED;
    }
    if (StringSupport.compatibleWith(text, ISO_8859_1)) {
      return ByteVectorScan.indexOfAsciiClass(text, ranges, start);
    }
    return ShortVectorScan.indexOfCharClassUtf16(text, ranges, start);
  }

  @Override
  public int indexOfCharClass(String text, Pattern.CharClassScanInfo scanInfo, int start) {
    if (!StringSupport.hasAccess()) {
      return UNSUPPORTED;
    }
    if (StringSupport.compatibleWith(text, ISO_8859_1)) {
      if (scanInfo.isAscii) {
        return ByteVectorScan.indexOfAsciiClass(text, scanInfo.ranges, start);
      }
      int[] clamped = clampRangesForLatin1(scanInfo.ranges);
      if (clamped != null) {
        return ByteVectorScan.indexOfAsciiClass(text, clamped, start);
      }
      return UNSUPPORTED;
    }
    return ShortVectorScan.indexOfCharClassUtf16(text, scanInfo.ranges, start);
  }

  @Override
  public int indexOfCodePointClass(
      String text, int[] ranges, long bitmap0, long bitmap1, int start) {
    if (!StringSupport.hasAccess()) {
      return UNSUPPORTED;
    }
    if (StringSupport.compatibleWith(text, ISO_8859_1)) {
      int[] clamped = clampRangesForLatin1(ranges);
      if (clamped == null) {
        return -1;
      }
      return ByteVectorScan.indexOfAsciiClass(text, clamped, start);
    }
    return ShortVectorScan.indexOfCharClassUtf16(text, ranges, start);
  }

  @Override
  public int indexOfIgnoreCase(String text, String prefix, int start) {
    if (!StringSupport.hasAccess()) {
      return UNSUPPORTED;
    }
    if (StringSupport.compatibleWith(text, ISO_8859_1)) {
      return ByteVectorScan.indexOfIgnoreCase(text, prefix, start);
    }
    return ShortVectorScan.indexOfIgnoreCaseUtf16(text, prefix, start);
  }

  private static int[] clampRangesForLatin1(int[] ranges) {
    int numRanges = ranges.length / 2;
    int[] clamped = new int[ranges.length];
    int writeIdx = 0;
    for (int r = 0; r < numRanges; r++) {
      int low = ranges[r * 2];
      int high = ranges[r * 2 + 1];
      if (low > 255) {
        continue;
      }
      int clampedHigh = Math.min(high, 255);
      if (low <= clampedHigh) {
        clamped[writeIdx++] = low;
        clamped[writeIdx++] = clampedHigh;
      }
    }
    if (writeIdx == 0) {
      return null;
    }
    if (writeIdx < ranges.length) {
      return Arrays.copyOf(clamped, writeIdx);
    }
    return clamped;
  }
}
