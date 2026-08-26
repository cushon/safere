// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.safere.Ascii.equalsIgnoreCase;
import static org.safere.Ascii.toLowerCase;

/** UTF-16 code unit traversal and search utilities. */
final class Utf16 {

  private Utf16() {}

  /** Returns the UTF-16 code unit at {@code index} in little-endian byte representation. */
  static char getChar(byte[] bytes, int offset, int index) {
    int byteIndex = offset + (index << 1);
    return (char) ((bytes[byteIndex] & 0xFF) | ((bytes[byteIndex + 1] & 0xFF) << 8));
  }

  /** Finds an ASCII prefix in UTF-16 code units in linear time using Two-Way exact matching. */
  static int indexOfIgnoreCase(char[] input, int offset, int length, String pattern, int start) {
    int patternLen = pattern.length();
    if (patternLen == 0) {
      return Math.min(Math.max(0, start), length);
    }
    if (patternLen == 1) {
      char target = pattern.charAt(0);
      for (int i = Math.max(0, start); i < length; i++) {
        if (equalsIgnoreCase(input[offset + i], target)) {
          return i;
        }
      }
      return -1;
    }
    int s = Math.max(0, start);
    if (s >= length || patternLen > length - s) {
      return -1;
    }

    // Step 1: Compute maximal suffix under <= (forward) and >= (backward) orders
    int ms1 = -1;
    int j = 0;
    int k = 1;
    int p1 = 1;
    while (j + k < patternLen) {
      char a = toLowerCase(pattern.charAt(ms1 + k));
      char b = toLowerCase(pattern.charAt(j + k));
      if (b < a) {
        j += k;
        k = 1;
        p1 = j - ms1;
      } else if (b == a) {
        if (k == p1) {
          j += p1;
          k = 1;
        } else {
          k++;
        }
      } else {
        ms1 = j++;
        k = 1;
        p1 = 1;
      }
    }

    int ms2 = -1;
    j = 0;
    k = 1;
    int p2 = 1;
    while (j + k < patternLen) {
      char a = toLowerCase(pattern.charAt(ms2 + k));
      char b = toLowerCase(pattern.charAt(j + k));
      if (b > a) {
        j += k;
        k = 1;
        p2 = j - ms2;
      } else if (b == a) {
        if (k == p2) {
          j += p2;
          k = 1;
        } else {
          k++;
        }
      } else {
        ms2 = j++;
        k = 1;
        p2 = 1;
      }
    }

    int ell = ms1 + 1 >= ms2 + 1 ? ms1 + 1 : ms2 + 1;
    int period = ms1 + 1 >= ms2 + 1 ? p1 : p2;

    boolean isPeriodic = true;
    for (int i = 0; i < ell; i++) {
      if (toLowerCase(pattern.charAt(i)) != toLowerCase(pattern.charAt(i + period))) {
        isPeriodic = false;
        break;
      }
    }

    int memory = 0;
    if (isPeriodic) {
      while (s <= length - patternLen) {
        int i = Math.max(ell, memory);
        while (i < patternLen && equalsIgnoreCase(pattern.charAt(i), input[offset + s + i])) {
          i++;
        }
        if (i < patternLen) {
          s += (i - ell + 1);
          memory = 0;
          continue;
        }
        int jj = ell - 1;
        while (jj >= memory && equalsIgnoreCase(pattern.charAt(jj), input[offset + s + jj])) {
          jj--;
        }
        if (jj < memory) {
          return s;
        }
        s += period;
        memory = patternLen - period;
      }
    } else {
      int periodJump = Math.max(ell, patternLen - ell) + 1;
      while (s <= length - patternLen) {
        int i = ell;
        while (i < patternLen && equalsIgnoreCase(pattern.charAt(i), input[offset + s + i])) {
          i++;
        }
        if (i < patternLen) {
          s += (i - ell + 1);
          continue;
        }
        int jj = ell - 1;
        while (jj >= 0 && equalsIgnoreCase(pattern.charAt(jj), input[offset + s + jj])) {
          jj--;
        }
        if (jj < 0) {
          return s;
        }
        s += periodJump;
      }
    }
    return -1;
  }

  /**
   * Finds an ASCII prefix in little-endian UTF-16 bytes in linear time using Two-Way exact
   * matching.
   */
  static int indexOfIgnoreCaseUtf16(
      byte[] input, int offset, int length, String pattern, int start) {
    int patternLen = pattern.length();
    if (patternLen == 0) {
      return Math.min(Math.max(0, start), length);
    }
    if (patternLen == 1) {
      char target = pattern.charAt(0);
      for (int i = Math.max(0, start); i < length; i++) {
        if (equalsIgnoreCase(getChar(input, offset, i), target)) {
          return i;
        }
      }
      return -1;
    }
    int s = Math.max(0, start);
    if (s >= length || patternLen > length - s) {
      return -1;
    }

    // Step 1: Compute maximal suffix under <= (forward) and >= (backward) orders
    int ms1 = -1;
    int j = 0;
    int k = 1;
    int p1 = 1;
    while (j + k < patternLen) {
      char a = toLowerCase(pattern.charAt(ms1 + k));
      char b = toLowerCase(pattern.charAt(j + k));
      if (b < a) {
        j += k;
        k = 1;
        p1 = j - ms1;
      } else if (b == a) {
        if (k == p1) {
          j += p1;
          k = 1;
        } else {
          k++;
        }
      } else {
        ms1 = j++;
        k = 1;
        p1 = 1;
      }
    }

    int ms2 = -1;
    j = 0;
    k = 1;
    int p2 = 1;
    while (j + k < patternLen) {
      char a = toLowerCase(pattern.charAt(ms2 + k));
      char b = toLowerCase(pattern.charAt(j + k));
      if (b > a) {
        j += k;
        k = 1;
        p2 = j - ms2;
      } else if (b == a) {
        if (k == p2) {
          j += p2;
          k = 1;
        } else {
          k++;
        }
      } else {
        ms2 = j++;
        k = 1;
        p2 = 1;
      }
    }

    int ell = ms1 + 1 >= ms2 + 1 ? ms1 + 1 : ms2 + 1;
    int period = ms1 + 1 >= ms2 + 1 ? p1 : p2;

    boolean isPeriodic = true;
    for (int i = 0; i < ell; i++) {
      if (toLowerCase(pattern.charAt(i)) != toLowerCase(pattern.charAt(i + period))) {
        isPeriodic = false;
        break;
      }
    }

    int memory = 0;
    if (isPeriodic) {
      while (s <= length - patternLen) {
        int i = Math.max(ell, memory);
        while (i < patternLen
            && equalsIgnoreCase(pattern.charAt(i), getChar(input, offset, s + i))) {
          i++;
        }
        if (i < patternLen) {
          s += (i - ell + 1);
          memory = 0;
          continue;
        }
        int jj = ell - 1;
        while (jj >= memory
            && equalsIgnoreCase(pattern.charAt(jj), getChar(input, offset, s + jj))) {
          jj--;
        }
        if (jj < memory) {
          return s;
        }
        s += period;
        memory = patternLen - period;
      }
    } else {
      int periodJump = Math.max(ell, patternLen - ell) + 1;
      while (s <= length - patternLen) {
        int i = ell;
        while (i < patternLen
            && equalsIgnoreCase(pattern.charAt(i), getChar(input, offset, s + i))) {
          i++;
        }
        if (i < patternLen) {
          s += (i - ell + 1);
          continue;
        }
        int jj = ell - 1;
        while (jj >= 0 && equalsIgnoreCase(pattern.charAt(jj), getChar(input, offset, s + jj))) {
          jj--;
        }
        if (jj < 0) {
          return s;
        }
        s += periodJump;
      }
    }
    return -1;
  }

  /** Finds a Unicode simple-folded prefix in a String in linear time. */
  static int indexOfUnicodeIgnoreCase(String input, String pattern, int start) {
    if (pattern.isEmpty()) {
      return Math.min(Math.max(0, start), input.length());
    }
    int[] patternCodePoints = pattern.codePoints().toArray();
    int[] failure = unicodeFailure(patternCodePoints);
    int matched = 0;
    for (int i = Math.max(0, start); i < input.length(); ) {
      int codePoint = input.codePointAt(i);
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

  /** Returns whether a character sequence matches a pattern prefix ignoring ASCII case. */
  static boolean regionMatchesIgnoreCase(char[] chars, int offset, String prefix, int prefixLen) {
    for (int i = 0; i < prefixLen; i++) {
      char c = chars[offset + i];
      char p = prefix.charAt(i);
      if (c != p && toLowerCase(c) != p) {
        return false;
      }
    }
    return true;
  }

  /** Returns whether little-endian UTF-16 bytes match a pattern prefix ignoring ASCII case. */
  static boolean regionMatchesIgnoreCaseUtf16(
      byte[] bytes, int byteOffset, String prefix, int prefixLen) {
    for (int i = 0; i < prefixLen; i++) {
      char c =
          (char)
              ((bytes[byteOffset + (i << 1)] & 0xFF)
                  | ((bytes[byteOffset + (i << 1) + 1] & 0xFF) << 8));
      char p = prefix.charAt(i);
      if (c != p && toLowerCase(c) != p) {
        return false;
      }
    }
    return true;
  }
}
