// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class AsciiTest {

  @Test
  void toLower_convertsUppercaseChar() {
    assertThat(Ascii.toLowerCase('A')).isEqualTo('a');
    assertThat(Ascii.toLowerCase('Z')).isEqualTo('z');
    assertThat(Ascii.toLowerCase('M')).isEqualTo('m');
  }

  @Test
  void toLower_leavesOthersUnchangedChar() {
    assertThat(Ascii.toLowerCase('a')).isEqualTo('a');
    assertThat(Ascii.toLowerCase('0')).isEqualTo('0');
    assertThat(Ascii.toLowerCase('!')).isEqualTo('!');
    assertThat(Ascii.toLowerCase('é')).isEqualTo('é');
  }

  @Test
  void toUpper_convertsLowercaseChar() {
    assertThat(Ascii.toUpperCase('a')).isEqualTo('A');
    assertThat(Ascii.toUpperCase('z')).isEqualTo('Z');
    assertThat(Ascii.toUpperCase('m')).isEqualTo('M');
  }

  @Test
  void toUpper_leavesOthersUnchangedChar() {
    assertThat(Ascii.toUpperCase('A')).isEqualTo('A');
    assertThat(Ascii.toUpperCase('0')).isEqualTo('0');
    assertThat(Ascii.toUpperCase('!')).isEqualTo('!');
    assertThat(Ascii.toUpperCase('é')).isEqualTo('é');
  }

  @Test
  void toLower_convertsUppercaseInt() {
    assertThat(Ascii.toLowerCase((int) 'A')).isEqualTo((int) 'a');
    assertThat(Ascii.toLowerCase((int) 'Z')).isEqualTo((int) 'z');
  }

  @Test
  void toUpper_convertsLowercaseInt() {
    assertThat(Ascii.toUpperCase((int) 'a')).isEqualTo((int) 'A');
    assertThat(Ascii.toUpperCase((int) 'z')).isEqualTo((int) 'Z');
  }

  @Test
  void equalsIgnoreCase() {
    assertThat(Ascii.equalsIgnoreCase('a', 'A')).isTrue();
    assertThat(Ascii.equalsIgnoreCase('A', 'a')).isTrue();
    assertThat(Ascii.equalsIgnoreCase('x', 'x')).isTrue();
    assertThat(Ascii.equalsIgnoreCase('a', 'b')).isFalse();
    assertThat(Ascii.equalsIgnoreCase('!', '!')).isTrue();
  }

  @Test
  void isUpperAndIsLower() {
    assertThat(Ascii.isUpper('A')).isTrue();
    assertThat(Ascii.isUpper('Z')).isTrue();
    assertThat(Ascii.isUpper('a')).isFalse();
    assertThat(Ascii.isUpper('0')).isFalse();

    assertThat(Ascii.isLower('a')).isTrue();
    assertThat(Ascii.isLower('z')).isTrue();
    assertThat(Ascii.isLower('A')).isFalse();
    assertThat(Ascii.isLower('0')).isFalse();
  }

  @Test
  void isAlphaAndIsDigit() {
    assertThat(Ascii.isAlpha('A')).isTrue();
    assertThat(Ascii.isAlpha('z')).isTrue();
    assertThat(Ascii.isAlpha('0')).isFalse();
    assertThat(Ascii.isAlpha('_')).isFalse();

    assertThat(Ascii.isDigit('0')).isTrue();
    assertThat(Ascii.isDigit('9')).isTrue();
    assertThat(Ascii.isDigit('a')).isFalse();
  }

  @Test
  void isAlnumAndIsWordChar() {
    assertThat(Ascii.isAlnum('A')).isTrue();
    assertThat(Ascii.isAlnum('0')).isTrue();
    assertThat(Ascii.isAlnum('_')).isFalse();

    assertThat(Ascii.isWordChar('A')).isTrue();
    assertThat(Ascii.isWordChar('0')).isTrue();
    assertThat(Ascii.isWordChar('_')).isTrue();
    assertThat(Ascii.isWordChar('-')).isFalse();
  }

  @Test
  void hexMethods() {
    assertThat(Ascii.isHexDigit('0')).isTrue();
    assertThat(Ascii.isHexDigit('9')).isTrue();
    assertThat(Ascii.isHexDigit('A')).isTrue();
    assertThat(Ascii.isHexDigit('F')).isTrue();
    assertThat(Ascii.isHexDigit('a')).isTrue();
    assertThat(Ascii.isHexDigit('f')).isTrue();
    assertThat(Ascii.isHexDigit('G')).isFalse();

    assertThat(Ascii.unhex('0')).isEqualTo(0);
    assertThat(Ascii.unhex('9')).isEqualTo(9);
    assertThat(Ascii.unhex('A')).isEqualTo(10);
    assertThat(Ascii.unhex('F')).isEqualTo(15);
    assertThat(Ascii.unhex('a')).isEqualTo(10);
    assertThat(Ascii.unhex('f')).isEqualTo(15);
    assertThat(Ascii.unhex('G')).isEqualTo(-1);
  }

  @Test
  void indexOfLinearIgnoreCase() {
    assertThat(Ascii.indexOfLinearIgnoreCase("", "", 0)).isEqualTo(0);
    assertThat(Ascii.indexOfLinearIgnoreCase("hello", "", 0)).isEqualTo(0);
    assertThat(Ascii.indexOfLinearIgnoreCase("hello", "HELL", 0)).isEqualTo(0);
    assertThat(Ascii.indexOfLinearIgnoreCase("hello WORLD", "world", 0)).isEqualTo(6);
    assertThat(Ascii.indexOfLinearIgnoreCase("abAbAbab", "abab", 0)).isEqualTo(0);
    assertThat(Ascii.indexOfLinearIgnoreCase("abAbAbab", "abab", 1)).isEqualTo(2);
    assertThat(Ascii.indexOfLinearIgnoreCase("abAbAbab", "abab", 3)).isEqualTo(4);
    assertThat(Ascii.indexOfLinearIgnoreCase("aaaaa", "aa", 0)).isEqualTo(0);
    assertThat(Ascii.indexOfLinearIgnoreCase("aaaaa", "aa", 1)).isEqualTo(1);
    assertThat(Ascii.indexOfLinearIgnoreCase("aaaaa", "ab", 0)).isEqualTo(-1);
  }

  @Test
  void indexOfIgnoreCase() {
    assertThat(Ascii.indexOfIgnoreCase("hello World", 'w', 0)).isEqualTo(6);
    assertThat(Ascii.indexOfIgnoreCase("hello World", 'W', 0)).isEqualTo(6);
    assertThat(Ascii.indexOfIgnoreCase("hello World", 'o', 0)).isEqualTo(4);
    assertThat(Ascii.indexOfIgnoreCase("hello World", 'o', 5)).isEqualTo(7);
    assertThat(Ascii.indexOfIgnoreCase("hello World", 'z', 0)).isEqualTo(-1);
    assertThat(Ascii.indexOfIgnoreCase("hello 123", '1', 0)).isEqualTo(6);
    assertThat(Ascii.indexOfIgnoreCase("hello é", 'é', 0)).isEqualTo(6);
  }

  @Test
  void minNonNegative() {
    assertThat(Ascii.minNonNegative(-1, -1)).isEqualTo(-1);
    assertThat(Ascii.minNonNegative(-1, 5)).isEqualTo(5);
    assertThat(Ascii.minNonNegative(3, -1)).isEqualTo(3);
    assertThat(Ascii.minNonNegative(10, 4)).isEqualTo(4);
    assertThat(Ascii.minNonNegative(2, 7)).isEqualTo(2);
  }
}
