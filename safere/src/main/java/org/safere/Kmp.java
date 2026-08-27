// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/**
 * Knuth-Morris-Pratt (KMP) string matching algorithms and failure table precomputation.
 *
 * <p>Used for linear-time compile-time literal pruning and as a comparative reference baseline for
 * runtime string search fallback benchmarks.
 */
final class Kmp {

  private Kmp() {}

  /** Computes the standard KMP prefix failure table for an integer code-point sequence. */
  static int[] literalFailure(int[] literal) {
    int[] failure = new int[literal.length];
    int matched = 0;
    for (int index = 1; index < literal.length; index++) {
      while (matched > 0 && literal[index] != literal[matched]) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        matched = failure[matched - 1];
      }
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      if (literal[index] == literal[matched]) {
        matched++;
      }
      failure[index] = matched;
    }
    return failure;
  }

  /**
   * Tests whether {@code value} contains {@code candidate} as a contiguous subsequence in linear
   * time using the precomputed KMP {@code failure} table.
   */
  static boolean containsCodePointSequence(int[] value, int[] candidate, int[] failure) {
    if (candidate.length == 0) {
      return true;
    }
    int matched = 0;
    for (int codePoint : value) {
      while (matched > 0 && codePoint != candidate[matched]) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        matched = failure[matched - 1];
      }
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      if (codePoint == candidate[matched]) {
        matched++;
        if (matched == candidate.length) {
          return true;
        }
      }
    }
    return false;
  }

  /** Computes the standard KMP prefix failure table for a UTF-8 byte sequence. */
  static int[] computeFailure(byte[] literal) {
    int[] failure = new int[literal.length];
    int matched = 0;
    for (int index = 1; index < literal.length; index++) {
      while (matched > 0 && literal[index] != literal[matched]) {
        matched = failure[matched - 1];
      }
      if (literal[index] == literal[matched]) {
        matched++;
      }
      failure[index] = matched;
    }
    return failure;
  }

  /** Searches for {@code literal} in {@code text} using KMP and a precomputed failure table. */
  static int indexOf(
      byte[] text, int offset, int length, byte[] literal, int[] failure, int start) {
    int m = literal.length;
    if (m == 0) {
      return start;
    }
    if (length < m || start < 0 || start > length - m) {
      return -1;
    }
    int textEnd = offset + length;
    int textPos = offset + start;
    int litPos = 0;
    while (textPos < textEnd) {
      if (text[textPos] == literal[litPos]) {
        textPos++;
        litPos++;
        if (litPos == m) {
          return textPos - offset - m;
        }
      } else if (litPos > 0) {
        litPos = failure[litPos - 1];
      } else {
        textPos++;
      }
    }
    return -1;
  }

  /** Computes the KMP failure table for ASCII case-insensitive String matching. */
  static int[] computeFailureIgnoreCase(String literal) {
    int m = literal.length();
    int[] failure = new int[m];
    int matched = 0;
    for (int index = 1; index < m; index++) {
      while (matched > 0
          && !Ascii.equalsIgnoreCase(literal.charAt(index), literal.charAt(matched))) {
        matched = failure[matched - 1];
      }
      if (Ascii.equalsIgnoreCase(literal.charAt(index), literal.charAt(matched))) {
        matched++;
      }
      failure[index] = matched;
    }
    return failure;
  }

  /** Searches for {@code literal} in {@code text} with ASCII case folding using KMP. */
  static int indexOfIgnoreCase(String text, String literal, int[] failure, int start) {
    int m = literal.length();
    if (m == 0) {
      return start;
    }
    int n = text.length();
    if (n < m || start < 0 || start > n - m) {
      return -1;
    }
    int textPos = start;
    int litPos = 0;
    while (textPos < n) {
      if (Ascii.equalsIgnoreCase(text.charAt(textPos), literal.charAt(litPos))) {
        textPos++;
        litPos++;
        if (litPos == m) {
          return textPos - m;
        }
      } else if (litPos > 0) {
        litPos = failure[litPos - 1];
      } else {
        textPos++;
      }
    }
    return -1;
  }
}
