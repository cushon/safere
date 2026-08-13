// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.internal;

/** ASCII character classification and case-folding utilities. */
public final class Ascii {

  private Ascii() {}

  /** Converts an ASCII uppercase character to lowercase; leaves other characters unchanged. */
  public static char toLowerCase(char ch) {
    return ch >= 'A' && ch <= 'Z' ? (char) (ch + 32) : ch;
  }

  /** Converts an ASCII lowercase character to uppercase; leaves other characters unchanged. */
  public static char toUpperCase(char ch) {
    return ch >= 'a' && ch <= 'z' ? (char) (ch - 32) : ch;
  }

  /** Converts an ASCII uppercase code point to lowercase; leaves other code points unchanged. */
  public static int toLowerCase(int cp) {
    return cp >= 'A' && cp <= 'Z' ? (cp + 32) : cp;
  }

  /** Converts an ASCII lowercase code point to uppercase; leaves other code points unchanged. */
  public static int toUpperCase(int cp) {
    return cp >= 'a' && cp <= 'z' ? (cp - 32) : cp;
  }

  /** Returns whether two characters are equal ignoring ASCII case. */
  public static boolean equalsIgnoreCase(char a, char b) {
    return a == b || toLowerCase(a) == toLowerCase(b);
  }

  /** Builds the KMP failure function for an ASCII case-insensitive pattern. */
  public static int[] ignoreCaseFailure(String pattern) {
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

  /** Finds an ASCII prefix in UTF-16 code units in linear time. */
  public static int indexOfIgnoreCase(
      char[] input, int offset, int length, String pattern, int start) {
    if (pattern.isEmpty()) {
      return Math.min(Math.max(0, start), length);
    }
    int[] failure = ignoreCaseFailure(pattern);
    int matched = 0;
    for (int i = Math.max(0, start); i < length; i++) {
      char ch = input[offset + i];
      while (matched > 0 && !equalsIgnoreCase(ch, pattern.charAt(matched))) {
        matched = failure[matched - 1];
      }
      if (equalsIgnoreCase(ch, pattern.charAt(matched))) {
        matched++;
        if (matched == pattern.length()) {
          return i - pattern.length() + 1;
        }
      }
    }
    return -1;
  }

  /** Finds an ASCII prefix in little-endian UTF-16 bytes in linear time. */
  public static int indexOfIgnoreCaseUtf16(
      byte[] input, int offset, int length, String pattern, int start) {
    if (pattern.isEmpty()) {
      return Math.min(Math.max(0, start), length);
    }
    int[] failure = ignoreCaseFailure(pattern);
    int matched = 0;
    for (int i = Math.max(0, start); i < length; i++) {
      int byteIndex = offset + (i << 1);
      char ch = (char) ((input[byteIndex] & 0xFF) | ((input[byteIndex + 1] & 0xFF) << 8));
      while (matched > 0 && !equalsIgnoreCase(ch, pattern.charAt(matched))) {
        matched = failure[matched - 1];
      }
      if (equalsIgnoreCase(ch, pattern.charAt(matched))) {
        matched++;
        if (matched == pattern.length()) {
          return i - pattern.length() + 1;
        }
      }
    }
    return -1;
  }

  /** Returns whether a character sequence matches a pattern prefix ignoring ASCII case. */
  public static boolean regionMatchesIgnoreCase(
      char[] chars, int offset, String prefix, int prefixLen) {
    for (int i = 0; i < prefixLen; i++) {
      if (!equalsIgnoreCase(chars[offset + i], prefix.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  /** Returns whether little-endian UTF-16 bytes match a pattern prefix ignoring ASCII case. */
  public static boolean regionMatchesIgnoreCaseUtf16(
      byte[] bytes, int byteOffset, String prefix, int prefixLen) {
    for (int i = 0; i < prefixLen; i++) {
      char c =
          (char)
              ((bytes[byteOffset + (i << 1)] & 0xFF)
                  | ((bytes[byteOffset + (i << 1) + 1] & 0xFF) << 8));
      if (!equalsIgnoreCase(c, prefix.charAt(i))) {
        return false;
      }
    }
    return true;
  }
}
