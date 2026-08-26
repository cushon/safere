// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class TwoWaySearchTest {

  @Test
  @DisplayName("Empty and single-character edge cases across all Two-Way variants")
  void emptyAndSingleCharacterEdgeCases() {
    String text = "Hello World";
    byte[] utf8 = text.getBytes(UTF_8);

    // Empty needle
    assertThat(Ascii.indexOfLinearIgnoreCase(text, "", 0)).isEqualTo(0);
    assertThat(Ascii.indexOfLinearIgnoreCase(text, "", 5)).isEqualTo(5);
    assertThat(Ascii.indexOfLinearIgnoreCase(text, "", 20)).isEqualTo(text.length());
    assertThat(Utf8InputScanner.indexOfLinear(utf8, 0, utf8.length, new byte[0], 0)).isEqualTo(0);
    assertThat(Utf8InputScanner.indexOfLinearIgnoreCase(utf8, 0, utf8.length, "", 0)).isEqualTo(0);
    assertThat(Utf16.indexOfIgnoreCase(text.toCharArray(), 0, text.length(), "", 0)).isEqualTo(0);
    assertThat(Utf16.indexOfIgnoreCaseUtf16(toUtf16Bytes(text), 0, text.length(), "", 0))
        .isEqualTo(0);

    // Single character needle
    assertThat(Ascii.indexOfLinearIgnoreCase(text, "h", 0)).isEqualTo(0);
    assertThat(Ascii.indexOfLinearIgnoreCase(text, "H", 0)).isEqualTo(0);
    assertThat(Ascii.indexOfLinearIgnoreCase(text, "w", 0)).isEqualTo(6);
    assertThat(Ascii.indexOfLinearIgnoreCase(text, "W", 0)).isEqualTo(6);
    assertThat(Ascii.indexOfLinearIgnoreCase(text, "z", 0)).isEqualTo(-1);

    assertThat(Utf8InputScanner.indexOfLinear(utf8, 0, utf8.length, new byte[] {(byte) 'W'}, 0))
        .isEqualTo(6);
    assertThat(Utf8InputScanner.indexOfLinear(utf8, 0, utf8.length, new byte[] {(byte) 'w'}, 0))
        .isEqualTo(-1);
    assertThat(Utf8InputScanner.indexOfLinearIgnoreCase(utf8, 0, utf8.length, "w", 0)).isEqualTo(6);
  }

  @Test
  @DisplayName("Periodic patterns (small period) correctly locate matches")
  void periodicPatterns() {
    String text = "aaaaabaaaaba";
    byte[] utf8 = text.getBytes(UTF_8);

    assertThat(Ascii.indexOfLinearIgnoreCase(text, "aaaa", 0)).isEqualTo(0);
    assertThat(Ascii.indexOfLinearIgnoreCase(text, "aaaa", 1)).isEqualTo(1);
    assertThat(Ascii.indexOfLinearIgnoreCase(text, "aaaa", 2)).isEqualTo(6);

    assertThat(Utf8InputScanner.indexOfLinear(utf8, 0, utf8.length, "aaaa".getBytes(UTF_8), 0))
        .isEqualTo(0);
    assertThat(Utf8InputScanner.indexOfLinear(utf8, 0, utf8.length, "aaaa".getBytes(UTF_8), 1))
        .isEqualTo(1);
    assertThat(Utf8InputScanner.indexOfLinear(utf8, 0, utf8.length, "aaaa".getBytes(UTF_8), 2))
        .isEqualTo(6);

    String alternating = "ababababababab";
    byte[] altUtf8 = alternating.getBytes(UTF_8);
    assertThat(Ascii.indexOfLinearIgnoreCase(alternating, "abab", 0)).isEqualTo(0);
    assertThat(Ascii.indexOfLinearIgnoreCase(alternating, "abab", 1)).isEqualTo(2);
    assertThat(Ascii.indexOfLinearIgnoreCase(alternating, "abab", 3)).isEqualTo(4);

    assertThat(
            Utf8InputScanner.indexOfLinear(altUtf8, 0, altUtf8.length, "abab".getBytes(UTF_8), 1))
        .isEqualTo(2);
    assertThat(Utf8InputScanner.indexOfLinearIgnoreCase(altUtf8, 0, altUtf8.length, "ABAB", 1))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("Aperiodic patterns and critical cut verification")
  void aperiodicPatterns() {
    String text = "abacabadabacaba";
    byte[] utf8 = text.getBytes(UTF_8);

    assertThat(Ascii.indexOfLinearIgnoreCase(text, "abacaba", 0)).isEqualTo(0);
    assertThat(Ascii.indexOfLinearIgnoreCase(text, "abacaba", 1)).isEqualTo(8);
    assertThat(Ascii.indexOfLinearIgnoreCase(text, "abad", 0)).isEqualTo(4);
    assertThat(Ascii.indexOfLinearIgnoreCase(text, "xyz", 0)).isEqualTo(-1);

    assertThat(Utf8InputScanner.indexOfLinear(utf8, 0, utf8.length, "abacaba".getBytes(UTF_8), 1))
        .isEqualTo(8);
    assertThat(Utf8InputScanner.indexOfLinearIgnoreCase(utf8, 0, utf8.length, "ABACABA", 1))
        .isEqualTo(8);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 5, 8, 13, 21})
  @DisplayName("Fibonacci word search (classic string matching worst-case)")
  void fibonacciWordMatching(int start) {
    // Generate Fibonacci words
    String f0 = "b";
    String f1 = "a";
    String f2 = f1 + f0; // "ab"
    String f3 = f2 + f1; // "aba"
    String f4 = f3 + f2; // "abaab"
    String f5 = f4 + f3; // "abaababa"
    String f6 = f5 + f4; // "abaababaabaab"
    String f7 = f6 + f5; // "abaababaabaababaababa"

    byte[] f7Utf8 = f7.getBytes(UTF_8);
    int expected = f7.indexOf(f4, start);

    assertThat(Ascii.indexOfLinearIgnoreCase(f7, f4, start)).isEqualTo(expected);
    assertThat(Utf8InputScanner.indexOfLinear(f7Utf8, 0, f7Utf8.length, f4.getBytes(UTF_8), start))
        .isEqualTo(expected);
    assertThat(Utf8InputScanner.indexOfLinearIgnoreCase(f7Utf8, 0, f7Utf8.length, f4, start))
        .isEqualTo(expected);
    assertThat(Utf16.indexOfIgnoreCase(f7.toCharArray(), 0, f7.length(), f4, start))
        .isEqualTo(expected);
    assertThat(Utf16.indexOfIgnoreCaseUtf16(toUtf16Bytes(f7), 0, f7.length(), f4, start))
        .isEqualTo(expected);
  }

  @Test
  @DisplayName("Thue-Morse sequence matching (overlap and cube-free)")
  void thueMorseSequenceMatching() {
    StringBuilder tm = new StringBuilder("0");
    for (int i = 0; i < 6; i++) {
      StringBuilder next = new StringBuilder();
      for (int j = 0; j < tm.length(); j++) {
        next.append(tm.charAt(j) == '0' ? '1' : '0');
      }
      tm.append(next);
    }
    String haystack = tm.toString();
    String needle = haystack.substring(15, 27);
    byte[] hayUtf8 = haystack.getBytes(UTF_8);

    for (int start = 0; start <= 20; start++) {
      int expected = haystack.indexOf(needle, start);
      assertThat(Ascii.indexOfLinearIgnoreCase(haystack, needle, start)).isEqualTo(expected);
      assertThat(
              Utf8InputScanner.indexOfLinear(
                  hayUtf8, 0, hayUtf8.length, needle.getBytes(UTF_8), start))
          .isEqualTo(expected);
    }
  }

  @Test
  @DisplayName("Adversarial prefix match failure test")
  void adversarialPrefixMatchFailure() {
    String needle = "a".repeat(30) + "b";
    String haystack = "a".repeat(1000);
    byte[] hayUtf8 = haystack.getBytes(UTF_8);

    assertThat(Ascii.indexOfLinearIgnoreCase(haystack, needle, 0)).isEqualTo(-1);
    assertThat(
            Utf8InputScanner.indexOfLinear(hayUtf8, 0, hayUtf8.length, needle.getBytes(UTF_8), 0))
        .isEqualTo(-1);
    assertThat(Utf8InputScanner.indexOfLinearIgnoreCase(hayUtf8, 0, hayUtf8.length, needle, 0))
        .isEqualTo(-1);

    String haystackWithMatch = "a".repeat(500) + needle + "a".repeat(500);
    byte[] hayMatchUtf8 = haystackWithMatch.getBytes(UTF_8);
    assertThat(Ascii.indexOfLinearIgnoreCase(haystackWithMatch, needle, 0)).isEqualTo(500);
    assertThat(
            Utf8InputScanner.indexOfLinear(
                hayMatchUtf8, 0, hayMatchUtf8.length, needle.getBytes(UTF_8), 0))
        .isEqualTo(500);
  }

  @Test
  @DisplayName("Differential random fuzzing against standard library index search")
  void differentialRandomFuzzing() {
    Random rng = new Random(42);
    char[] alphabet = "abcdeABCDE \n\t012".toCharArray();

    for (int iter = 0; iter < 5000; iter++) {
      int textLen = rng.nextInt(200);
      char[] textChars = new char[textLen];
      for (int i = 0; i < textLen; i++) {
        textChars[i] = alphabet[rng.nextInt(alphabet.length)];
      }
      String text = new String(textChars);

      int patLen = rng.nextInt(15) + 1;
      char[] patChars = new char[patLen];
      for (int i = 0; i < patLen; i++) {
        patChars[i] = alphabet[rng.nextInt(alphabet.length)];
      }
      String pattern = new String(patChars);

      int start = textLen == 0 ? 0 : rng.nextInt(textLen + 2);

      // Exact matching check
      byte[] textBytes = text.getBytes(UTF_8);
      byte[] patBytes = pattern.getBytes(UTF_8);
      int expectedExact = text.indexOf(pattern, start);
      int actualExact =
          Utf8InputScanner.indexOfLinear(textBytes, 0, textBytes.length, patBytes, start);
      assertThat(actualExact).isEqualTo(expectedExact);

      // Case-insensitive check
      String textLower = text.toLowerCase(Locale.ROOT);
      String patLower = pattern.toLowerCase(Locale.ROOT);
      int expectedCase = textLower.indexOf(patLower, start);

      int actualAscii = Ascii.indexOfLinearIgnoreCase(text, pattern, start);
      int actualUtf8Case =
          Utf8InputScanner.indexOfLinearIgnoreCase(textBytes, 0, textBytes.length, pattern, start);
      int actualUtf16 =
          Utf16.indexOfIgnoreCase(text.toCharArray(), 0, text.length(), pattern, start);
      int actualUtf16Bytes =
          Utf16.indexOfIgnoreCaseUtf16(toUtf16Bytes(text), 0, text.length(), pattern, start);

      assertThat(actualAscii).isEqualTo(expectedCase);
      assertThat(actualUtf8Case).isEqualTo(expectedCase);
      assertThat(actualUtf16).isEqualTo(expectedCase);
      assertThat(actualUtf16Bytes).isEqualTo(expectedCase);
    }
  }

  private static byte[] toUtf16Bytes(String s) {
    byte[] bytes = new byte[s.length() * 2];
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      bytes[i * 2] = (byte) (c & 0xFF);
      bytes[i * 2 + 1] = (byte) ((c >>> 8) & 0xFF);
    }
    return bytes;
  }
}
