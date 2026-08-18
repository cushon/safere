// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** ASCII character classification and case-folding utilities. */
final class Ascii {

  private Ascii() {}

  /** Converts an ASCII uppercase character to lowercase; leaves other characters unchanged. */
  static char toLowerCase(char ch) {
    return ch >= 'A' && ch <= 'Z' ? (char) (ch + 32) : ch;
  }

  /** Converts an ASCII lowercase character to uppercase; leaves other characters unchanged. */
  static char toUpperCase(char ch) {
    return ch >= 'a' && ch <= 'z' ? (char) (ch - 32) : ch;
  }

  /** Converts an ASCII uppercase code point to lowercase; leaves other code points unchanged. */
  static int toLowerCase(int cp) {
    return cp >= 'A' && cp <= 'Z' ? (cp + 32) : cp;
  }

  /** Converts an ASCII lowercase code point to uppercase; leaves other code points unchanged. */
  static int toUpperCase(int cp) {
    return cp >= 'a' && cp <= 'z' ? (cp - 32) : cp;
  }

  /** Returns whether two characters are equal ignoring ASCII case. */
  static boolean equalsIgnoreCase(char a, char b) {
    return a == b || ((a ^ b) == 0x20 && isAlpha(a));
  }

  /** Returns whether two code points or bytes are equal ignoring ASCII case. */
  static boolean equalsIgnoreCase(int a, int b) {
    return a == b || ((a ^ b) == 0x20 && isAlpha(a));
  }

  /** Returns true if the code point is an ASCII uppercase letter. */
  static boolean isUpper(int r) {
    return r >= 'A' && r <= 'Z';
  }

  /** Returns true if the code point is an ASCII lowercase letter. */
  static boolean isLower(int r) {
    return r >= 'a' && r <= 'z';
  }

  /** Returns true if the code point is an ASCII letter. */
  static boolean isAlpha(int r) {
    return (r >= 'A' && r <= 'Z') || (r >= 'a' && r <= 'z');
  }

  /** Returns true if the code point is an ASCII digit. */
  static boolean isDigit(int r) {
    return r >= '0' && r <= '9';
  }

  /** Returns true if the code point is an ASCII letter or digit. */
  static boolean isAlnum(int r) {
    return (r >= '0' && r <= '9') || (r >= 'A' && r <= 'Z') || (r >= 'a' && r <= 'z');
  }

  /** Returns true if the code point is an ASCII word character (letter, digit, or underscore). */
  static boolean isWordChar(int r) {
    return isAlnum(r) || r == '_';
  }

  /** Returns true if the code point is an ASCII hex digit. */
  static boolean isHexDigit(int r) {
    return (r >= '0' && r <= '9') || (r >= 'A' && r <= 'F') || (r >= 'a' && r <= 'f');
  }

  /**
   * Returns the value of a hex digit, or -1 if not a hex digit.
   *
   * @param r a code point
   * @return 0-15 for valid hex digits, -1 otherwise
   */
  static int unhex(int r) {
    if (r >= '0' && r <= '9') {
      return r - '0';
    }
    if (r >= 'A' && r <= 'F') {
      return r - 'A' + 10;
    }
    if (r >= 'a' && r <= 'f') {
      return r - 'a' + 10;
    }
    return -1;
  }

  /** Builds the KMP failure function for an ASCII case-insensitive pattern. */
  static int[] ignoreCaseFailure(String pattern) {
    int[] failure = new int[pattern.length()];
    int matched = 0;
    for (int i = 1; i < pattern.length(); i++) {
      while (matched > 0 && !equalsIgnoreCase(pattern.charAt(i), pattern.charAt(matched))) {
        matched = failure[matched - 1];
      }
      if (equalsIgnoreCase(pattern.charAt(i), pattern.charAt(matched))) {
        matched++;
      }
      failure[i] = matched;
    }
    return failure;
  }

  /** Knuth-Morris-Pratt scan on String, strictly linear in text length regardless of pattern. */
  static int indexOfLinearIgnoreCase(String text, String prefix, int[] failure, int start) {
    int matched = 0;
    int prefixLen = prefix.length();
    int length = text.length();
    for (int position = Math.max(0, start); position < length; position++) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      char current = text.charAt(position);
      while (matched > 0 && !equalsIgnoreCase(current, prefix.charAt(matched))) {
        matched = failure[matched - 1];
      }
      if (equalsIgnoreCase(current, prefix.charAt(matched))) {
        matched++;
        if (matched == prefixLen) {
          return position - prefixLen + 1;
        }
      }
    }
    return -1;
  }

  /** Returns the first index of character {@code ch} ignoring ASCII case, or -1 if not found. */
  static int indexOfIgnoreCase(String text, char ch, int fromIndex) {
    if (ch > 127) {
      return text.indexOf(ch, fromIndex);
    }
    char low = toLowerCase(ch);
    char high = toUpperCase(ch);
    if (low == high) {
      return text.indexOf(low, fromIndex);
    }
    for (int i = Math.max(0, fromIndex); i < text.length(); i++) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      char value = text.charAt(i);
      if (value == low || value == high) {
        return i;
      }
    }
    return -1;
  }

  /** Returns the minimum of two indices that is >= 0, or -1 if both are negative. */
  static int minNonNegative(int a, int b) {
    if (a < 0) {
      return b;
    }
    if (b < 0) {
      return a;
    }
    return Math.min(a, b);
  }

  /** Returns whether a string matches a pattern prefix ignoring ASCII case. */
  static boolean regionMatchesIgnoreCase(String text, int offset, String prefix, int prefixLen) {
    for (int i = 0; i < prefixLen; i++) {
      if (!equalsIgnoreCase(text.charAt(offset + i), prefix.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  /** Returns whether a byte array matches a pattern prefix ignoring ASCII case. */
  static boolean regionMatchesIgnoreCase(byte[] bytes, int offset, String prefix, int prefixLen) {
    for (int i = 0; i < prefixLen; i++) {
      if (!equalsIgnoreCase(bytes[offset + i] & 0xFF, prefix.charAt(i))) {
        return false;
      }
    }
    return true;
  }
}
