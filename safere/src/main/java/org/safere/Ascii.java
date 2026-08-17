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

  /** Returns whether a string matches a pattern prefix ignoring ASCII case. */
  static boolean regionMatchesIgnoreCase(String text, int offset, String prefix, int prefixLen) {
    for (int i = 0; i < prefixLen; i++) {
      char c = text.charAt(offset + i);
      char p = prefix.charAt(i);
      if (c != p && ((c ^ p) != 0x20 || !isAlpha(c))) {
        return false;
      }
    }
    return true;
  }

  /** Returns whether a byte array matches a pattern prefix ignoring ASCII case. */
  static boolean regionMatchesIgnoreCase(byte[] bytes, int offset, String prefix, int prefixLen) {
    for (int i = 0; i < prefixLen; i++) {
      int c = bytes[offset + i] & 0xFF;
      char p = prefix.charAt(i);
      if (c != p && ((c ^ p) != 0x20 || !isAlpha(c))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Approximate byte frequency ranks (0 = most common in text/code, 127 = rarest) for ASCII
   * characters, mapping uppercase and lowercase to the same rank.
   */
  private static final byte[] ASCII_FREQUENCY_RANK = new byte[128];

  static {
    // Default all unlisted ASCII to high rarity
    for (int i = 0; i < 128; i++) {
      ASCII_FREQUENCY_RANK[i] = 100;
    }
    // Control characters (rare in text)
    for (int i = 0; i < 32; i++) {
      ASCII_FREQUENCY_RANK[i] = 120;
    }
    // High-frequency whitespace
    ASCII_FREQUENCY_RANK[' '] = 0;
    ASCII_FREQUENCY_RANK['\n'] = 15;
    ASCII_FREQUENCY_RANK['\t'] = 45;
    ASCII_FREQUENCY_RANK['\r'] = 55;

    // Common letters (case-folded: 'a'/'A' share rank)
    setLetterRank('e', 1);
    setLetterRank('t', 2);
    setLetterRank('a', 3);
    setLetterRank('o', 4);
    setLetterRank('i', 5);
    setLetterRank('n', 6);
    setLetterRank('s', 7);
    setLetterRank('r', 8);
    setLetterRank('h', 9);
    setLetterRank('l', 10);
    setLetterRank('d', 11);
    setLetterRank('c', 12);
    setLetterRank('u', 13);
    setLetterRank('m', 14);
    setLetterRank('f', 16);
    setLetterRank('p', 17);
    setLetterRank('g', 18);
    setLetterRank('w', 19);
    setLetterRank('y', 20);
    setLetterRank('b', 21);
    setLetterRank('v', 22);
    setLetterRank('k', 23);
    setLetterRank('x', 24);
    setLetterRank('j', 25);
    setLetterRank('q', 26);
    setLetterRank('z', 27);

    // Common code/JSON/protocol punctuation
    ASCII_FREQUENCY_RANK['"'] = 15;
    ASCII_FREQUENCY_RANK[':'] = 16;
    ASCII_FREQUENCY_RANK[','] = 17;
    ASCII_FREQUENCY_RANK['.'] = 18;
    ASCII_FREQUENCY_RANK['/'] = 19;
    ASCII_FREQUENCY_RANK['-'] = 20;
    ASCII_FREQUENCY_RANK['_'] = 21;
    ASCII_FREQUENCY_RANK['='] = 22;
    ASCII_FREQUENCY_RANK[';'] = 23;
    ASCII_FREQUENCY_RANK['('] = 25;
    ASCII_FREQUENCY_RANK[')'] = 25;
    ASCII_FREQUENCY_RANK['{'] = 26;
    ASCII_FREQUENCY_RANK['}'] = 26;
    ASCII_FREQUENCY_RANK['['] = 27;
    ASCII_FREQUENCY_RANK[']'] = 27;

    // Digits
    for (char d = '0'; d <= '9'; d++) {
      ASCII_FREQUENCY_RANK[d] = (byte) (30 + (d - '0'));
    }
  }

  private static void setLetterRank(char c, int rank) {
    ASCII_FREQUENCY_RANK[c] = (byte) rank;
    ASCII_FREQUENCY_RANK[Character.toUpperCase(c)] = (byte) rank;
  }

  /** Returns the byte frequency rank for an ASCII character (higher = rarer). */
  static int frequencyRank(char c) {
    return c < 128 ? (ASCII_FREQUENCY_RANK[c] & 0xFF) : 127;
  }

  /**
   * Returns the offset of the rarest ASCII character in the prefix (up to prefixLen). If all
   * characters have identical rank, returns 0.
   */
  static int rarestAsciiOffset(String prefix, int prefixLen) {
    int bestOffset = 0;
    int maxRank = -1;
    for (int i = 0; i < prefixLen; i++) {
      char c = prefix.charAt(i);
      int rank = frequencyRank(c);
      if (rank > maxRank) {
        maxRank = rank;
        bestOffset = i;
      }
    }
    return bestOffset;
  }
}
