// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Utility methods for Unicode code point handling and character classification. */
final class Utils {

  /** The maximum valid Unicode code point. */
  public static final int MAX_RUNE = 0x10FFFF;

  /** The minimum valid Unicode code point. */
  public static final int MIN_RUNE = 0;

  /** Unicode replacement character, used for invalid code points. */
  public static final int REPLACEMENT_CHAR = 0xFFFD;

  /** The maximum value of a Basic Multilingual Plane (BMP) code point. */
  public static final int MAX_BMP = 0xFFFF;

  /** Start of the surrogate range (not valid code points). */
  public static final int MIN_SURROGATE = 0xD800;

  /** End of the surrogate range (not valid code points). */
  public static final int MAX_SURROGATE = 0xDFFF;

  private Utils() {} // Non-instantiable.

  /** Returns true if {@code r} is a valid Unicode code point. */
  public static boolean isValidRune(int r) {
    return r >= MIN_RUNE && r <= MAX_RUNE && (r < MIN_SURROGATE || r > MAX_SURROGATE);
  }

  /** Returns true if the code point is an ASCII letter or digit. */
  public static boolean isAlnum(int r) {
    return Ascii.isAlnum(r);
  }

  /** Returns true if the code point is an ASCII letter. */
  public static boolean isAlpha(int r) {
    return Ascii.isAlpha(r);
  }

  /** Returns true if the code point is an ASCII digit. */
  public static boolean isDigit(int r) {
    return Ascii.isDigit(r);
  }

  /** Returns true if the code point is an ASCII hex digit. */
  public static boolean isHexDigit(int r) {
    return Ascii.isHexDigit(r);
  }

  /** Returns true if the code point is an ASCII word character (letter, digit, or underscore). */
  public static boolean isWordChar(int r) {
    return Ascii.isWordChar(r);
  }

  /** Returns true if the code point is an ASCII uppercase letter. */
  public static boolean isUpper(int r) {
    return Ascii.isUpper(r);
  }

  /** Returns true if the code point is an ASCII lowercase letter. */
  public static boolean isLower(int r) {
    return Ascii.isLower(r);
  }

  /**
   * Returns the value of a hex digit, or -1 if not a hex digit.
   *
   * @param r a code point
   * @return 0-15 for valid hex digits, -1 otherwise
   */
  public static int unhex(int r) {
    return Ascii.unhex(r);
  }

  /**
   * Converts a code point to a Java String, handling supplementary characters correctly.
   *
   * @param r a Unicode code point
   * @return a String containing the character(s) for that code point
   */
  public static String runeToString(int r) {
    return new String(Character.toChars(r));
  }

  /**
   * Returns the number of Unicode code points in the string. Unlike {@link String#length()}, this
   * correctly counts supplementary (non-BMP) characters as one.
   */
  public static int runeCount(String s) {
    return s.codePointCount(0, s.length());
  }

  /**
   * Returns the code point at a given code-point index (not char index) in a string.
   *
   * @param s the string
   * @param index the code point index
   * @return the code point at that index
   * @throws IndexOutOfBoundsException if index is out of bounds
   */
  public static int runeAt(String s, int index) {
    int charIndex = s.offsetByCodePoints(0, index);
    return s.codePointAt(charIndex);
  }
}
