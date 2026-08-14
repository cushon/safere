// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

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
  public int indexOfCharClass(String text, Pattern.CharClassScanInfo scanInfo, int start) {
    if (!StringSupport.hasAccess()) {
      return UNSUPPORTED;
    }
    byte[] value = StringSupport.value(text);
    byte coder = StringSupport.coder(text);
    int length = text.length();

    if (coder == 0) {
      if (scanInfo.isAscii) {
        return ByteVectorScan.indexOfAsciiClass(value, 0, length, scanInfo.ranges, start);
      }
      int[] clamped = clampRangesForLatin1(scanInfo.ranges);
      if (clamped != null) {
        return ByteVectorScan.indexOfAsciiClass(value, 0, length, clamped, start);
      }
      return UNSUPPORTED;
    }
    return ShortVectorScan.indexOfCharClassUtf16(value, 0, length, scanInfo.ranges, start);
  }

  @Override
  public int indexOfCodePointClass(
      String text, int[] ranges, long bitmap0, long bitmap1, int start) {
    if (!StringSupport.hasAccess()) {
      return UNSUPPORTED;
    }
    byte[] value = StringSupport.value(text);
    byte coder = StringSupport.coder(text);
    int length = text.length();

    if (coder == 0) {
      int[] clamped = clampRangesForLatin1(ranges);
      if (clamped == null) {
        return -1;
      }
      return ByteVectorScan.indexOfAsciiClass(value, 0, length, clamped, start);
    }
    return ShortVectorScan.indexOfCharClassUtf16(value, 0, length, ranges, start);
  }

  @Override
  public int indexOfIgnoreCase(String text, String prefix, int start) {
    if (!StringSupport.hasAccess()) {
      return UNSUPPORTED;
    }
    byte[] value = StringSupport.value(text);
    byte coder = StringSupport.coder(text);
    int length = text.length();

    if (coder == 0) {
      return ByteVectorScan.indexOfIgnoreCase(value, 0, length, prefix, start);
    }
    return ShortVectorScan.indexOfIgnoreCaseUtf16(value, 0, length, prefix, start);
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
