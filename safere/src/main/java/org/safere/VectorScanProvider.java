// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Internal provider for experimental Vector API scan operations. */
interface VectorScanProvider {
  int UNSUPPORTED = -2;

  int minimumInputLength();

  int minimumTeddyInputLength();

  /** Returns a match position, {@code -1} when absent, or {@link #UNSUPPORTED}. */
  int indexOfAsciiClass(byte[] bytes, int offset, int length, int[] ranges, int start);

  /** Returns a match position, {@code -1} when absent, or {@link #UNSUPPORTED}. */
  int indexOfAsciiClass(String text, int[] ranges, int start);

  /** Returns a match position, {@code -1} when absent, or {@link #UNSUPPORTED}. */
  int indexOfCharClass(String text, int[] ranges, int start);

  /** Returns a match position, {@code -1} when absent, or {@link #UNSUPPORTED}. */
  int indexOfIgnoreCase(String text, String prefix, int start);

  /** Returns a match position, {@code -1} when absent, or {@link #UNSUPPORTED}. */
  default int indexOfMultiLiteral(
      String text,
      String[] literals,
      char[] anchorChars,
      int[] anchorOffsets,
      int minLength,
      int start) {
    return UNSUPPORTED;
  }

  /** Returns a match position, {@code -1} when absent, or {@link #UNSUPPORTED}. */
  default int indexOfMultiLiteral(
      byte[] bytes,
      int offset,
      int length,
      String[] literals,
      char[] anchorChars,
      int[] anchorOffsets,
      int minLength,
      int start) {
    return UNSUPPORTED;
  }

  /** Returns a match position, {@code -1} when absent, or {@link #UNSUPPORTED}. */
  int indexOfTeddy(String text, TeddyModel model, int start);

  /** Returns a match position, {@code -1} when absent, or {@link #UNSUPPORTED}. */
  int indexOfTeddy(byte[] bytes, int offset, int length, TeddyModel model, int start);
}
