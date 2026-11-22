// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Unicode case-insensitive search over UTF-16 text. */
final class Utf16 {

  private Utf16() {}

  /** Finds a Unicode simple-folded prefix in a String in linear time. */
  static int indexOfUnicodeIgnoreCase(CharSequence input, String pattern, int start) {
    if (pattern.isEmpty()) {
      return Math.min(Math.max(0, start), input.length());
    }
    int[] patternCodePoints = pattern.codePoints().toArray();
    int[] failure = unicodeFailure(patternCodePoints);
    int matched = 0;
    for (int i = Math.max(0, start); i < input.length(); ) {
      int codePoint = Character.codePointAt(input, i);
      int next = i + Character.charCount(codePoint);
      while (matched > 0 && !unicodeEqualsIgnoreCase(codePoint, patternCodePoints[matched])) {
        matched = failure[matched - 1];
      }
      if (unicodeEqualsIgnoreCase(codePoint, patternCodePoints[matched])) {
        matched++;
        if (matched == patternCodePoints.length) {
          return next - pattern.length();
        }
      }
      i = next;
    }
    return -1;
  }

  static boolean regionMatchesUnicodeIgnoreCase(CharSequence input, int offset, String pattern) {
    int inputIndex = offset;
    int patternIndex = 0;
    while (patternIndex < pattern.length()) {
      if (inputIndex >= input.length()) {
        return false;
      }
      int inputCodePoint = Character.codePointAt(input, inputIndex);
      int patternCodePoint = pattern.codePointAt(patternIndex);
      if (!unicodeEqualsIgnoreCase(inputCodePoint, patternCodePoint)) {
        return false;
      }
      inputIndex += Character.charCount(inputCodePoint);
      patternIndex += Character.charCount(patternCodePoint);
    }
    return inputIndex == offset + pattern.length();
  }

  private static int[] unicodeFailure(int[] pattern) {
    int[] failure = new int[pattern.length];
    int matched = 0;
    for (int i = 1; i < pattern.length; i++) {
      while (matched > 0 && !unicodeEqualsIgnoreCase(pattern[i], pattern[matched])) {
        matched = failure[matched - 1];
      }
      if (unicodeEqualsIgnoreCase(pattern[i], pattern[matched])) {
        matched++;
      }
      failure[i] = matched;
    }
    return failure;
  }

  private static boolean unicodeEqualsIgnoreCase(int left, int right) {
    if (left == right) {
      return true;
    }
    int folded = Inst.simpleFold(left);
    while (folded != left) {
      if (folded == right) {
        return true;
      }
      folded = Inst.simpleFold(folded);
    }
    return false;
  }
}
