// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Experimental scan operations implemented with the incubating Vector API. */
final class IncubatorVectorScanProvider implements VectorScanProvider {
  private static final int MINIMUM_INPUT_LENGTH = 1024;

  private final VectorScanProvider stringDelegate;

  public IncubatorVectorScanProvider() {
    String mode = System.getProperty("safere.vector.mode", "auto");
    if (mode.equals("copy")) {
      this.stringDelegate = new CopyVectorScanner(this);
    } else if (mode.equals("swar")) {
      this.stringDelegate = new SwarVectorScanner(this);
    } else if (mode.equals("segment")) {
      this.stringDelegate = new SegmentVectorScanner(this);
    } else if (StringSegmentSupport.isAvailable()) {
      this.stringDelegate = new SegmentVectorScanner(this);
    } else {
      this.stringDelegate = new CopyVectorScanner(this);
    }
  }

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
    return stringDelegate.indexOfCharClass(text, scanInfo, start);
  }

  @Override
  public int indexOfCodePointClass(
      String text, int[] ranges, long bitmap0, long bitmap1, int start) {
    return stringDelegate.indexOfCodePointClass(text, ranges, bitmap0, bitmap1, start);
  }

  @Override
  public int indexOfIgnoreCase(String text, String prefix, int start) {
    return stringDelegate.indexOfIgnoreCase(text, prefix, start);
  }
}
