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
    return a == b || toLowerCase(a) == toLowerCase(b);
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
}
