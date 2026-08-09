// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** 64-bit SWAR implementation of VectorScanProvider for String operations. */
final class SwarVectorScanner implements VectorScanProvider {
  private static final int MINIMUM_INPUT_LENGTH = 64;
  private final VectorScanProvider byteDelegate;

  SwarVectorScanner(VectorScanProvider byteDelegate) {
    this.byteDelegate = byteDelegate;
  }

  @Override
  public int minimumInputLength() {
    return MINIMUM_INPUT_LENGTH;
  }

  @Override
  public int indexOfAsciiClass(byte[] bytes, int offset, int length, int[] ranges, int start) {
    return byteDelegate.indexOfAsciiClass(bytes, offset, length, ranges, start);
  }

  @Override
  public int indexOfCharClass(String text, Pattern.CharClassScanInfo scanInfo, int start) {
    int textLen = text.length();
    int remaining = textLen - start;
    if (remaining < MINIMUM_INPUT_LENGTH) {
      return -2;
    }
    int numRanges = scanInfo.ranges.length / 2;
    if (numRanges > 2) {
      return -2;
    }
    SegmentAndCharset sac = StringSegmentSupport.stringAsSegment(text);
    return SegmentSwarScanner.indexOfCharClass(sac, text, scanInfo.ranges, start, numRanges);
  }

  @Override
  public int indexOfCodePointClass(
      String text, int[] ranges, long bitmap0, long bitmap1, int start) {
    int textLen = text.length();
    int remaining = textLen - start;
    if (remaining < MINIMUM_INPUT_LENGTH) {
      return -2;
    }
    for (int r : ranges) {
      if (r >= 65536) {
        return -2;
      }
    }
    int numRanges = ranges.length / 2;
    if (numRanges > 2) {
      return -2;
    }
    SegmentAndCharset sac = StringSegmentSupport.stringAsSegment(text);
    return SegmentSwarScanner.indexOfCharClass(sac, text, ranges, start, numRanges);
  }

  @Override
  public int indexOfIgnoreCase(String text, String prefix, int start) {
    int textLen = text.length();
    int remaining = textLen - start;
    if (remaining < MINIMUM_INPUT_LENGTH) {
      return -2;
    }
    SegmentAndCharset sac = StringSegmentSupport.stringAsSegment(text);
    return SegmentSwarScanner.indexOfIgnoreCase(sac, text, prefix, start);
  }
}
