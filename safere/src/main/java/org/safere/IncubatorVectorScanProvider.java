// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Experimental scan operations implemented with the incubating Vector API. */
final class IncubatorVectorScanProvider implements VectorScanProvider {
  private static final int MINIMUM_INPUT_LENGTH = 1024;
  private static final int MINIMUM_TEDDY_INPUT_LENGTH = 1024;

  @Override
  public int minimumInputLength() {
    return MINIMUM_INPUT_LENGTH;
  }

  @Override
  public int minimumTeddyInputLength() {
    return MINIMUM_TEDDY_INPUT_LENGTH;
  }

  @Override
  public int minimumMultiLiteralInputLength() {
    return 64;
  }

  @Override
  public int indexOfAsciiClass(byte[] bytes, int offset, int length, int[] ranges, int start) {
    return ByteVectorScan.indexOfAsciiClass(bytes, offset, length, ranges, start);
  }

  @Override
  public int indexOfAsciiClass(String text, int[] ranges, int start) {
    return StringVectorScan.indexOfAsciiClass(text, ranges, start);
  }

  @Override
  public int indexOfCharClass(String text, int[] ranges, int start) {
    return StringVectorScan.indexOfCharClass(text, ranges, start);
  }

  @Override
  public int indexOfIgnoreCase(String text, String prefix, int start) {
    return StringVectorScan.indexOfIgnoreCase(text, prefix, start);
  }

  @Override
  public int indexOfMultiLiteral(
      String text,
      String[] literals,
      char[] anchorChars,
      int[] anchorOffsets,
      int minLength,
      int start) {
    return StringVectorScan.indexOfMultiLiteral(
        text, literals, anchorChars, anchorOffsets, minLength, start);
  }

  @Override
  public int indexOfMultiLiteral(
      byte[] bytes,
      int offset,
      int length,
      String[] literals,
      char[] anchorChars,
      int[] anchorOffsets,
      int minLength,
      int start) {
    return ByteVectorScan.indexOfMultiLiteral(
        bytes, offset, length, literals, anchorChars, anchorOffsets, minLength, start);
  }

  @Override
  public int indexOfTeddy(String text, TeddyModel model, int start) {
    return StringVectorScan.indexOfTeddy(text, model, start);
  }

  @Override
  public int indexOfTeddy(byte[] bytes, int offset, int length, TeddyModel model, int start) {
    return TeddyVectorScan.indexOfTeddyUtf8(bytes, offset, length, model, start);
  }
}
