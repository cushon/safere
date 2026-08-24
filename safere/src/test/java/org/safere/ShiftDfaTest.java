// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("ShiftDfa is an internal SafeRE engine")
class ShiftDfaTest {

  private static ShiftDfa compile(String regex, int flags) {
    Regexp ast = Parser.parse(regex, flags);
    Prog prog = Compiler.compile(ast);
    return ShiftDfa.compile(prog);
  }

  private static ShiftDfa compile(String regex) {
    return compile(regex, ParseFlags.LIKE_PERL);
  }

  @Test
  @DisplayName("simple literals and short tokens")
  void simpleLiteralsAndShortTokens() {
    ShiftDfa dfa = compile("abc");
    assertThat(dfa).isNotNull();
    assertThat(dfa.numStates()).isEqualTo(4);
    assertThat(dfa.matches("abc", 0, 3)).isTrue();
    assertThat(dfa.matches("ab", 0, 2)).isFalse();
    assertThat(dfa.matches("abcd", 0, 4)).isFalse();
    assertThat(dfa.matches("axc", 0, 3)).isFalse();

    byte[] bytes = "abc".getBytes(UTF_8);
    Utf8InputScanner scanner = new Utf8InputScanner(bytes, 0, bytes.length);
    assertThat(dfa.matches(scanner, 0, 3)).isTrue();
  }

  @Test
  @DisplayName("json boolean and null tokens")
  void jsonBooleanAndNullTokens() {
    ShiftDfa boolDfa = compile("true|false");
    assertThat(boolDfa).isNotNull();
    assertThat(boolDfa.numStates()).isLessThanOrEqualTo(10);
    assertThat(boolDfa.matches("true", 0, 4)).isTrue();
    assertThat(boolDfa.matches("false", 0, 5)).isTrue();
    assertThat(boolDfa.matches("tru", 0, 3)).isFalse();
    assertThat(boolDfa.matches("truee", 0, 5)).isFalse();
    assertThat(boolDfa.matches("null", 0, 4)).isFalse();

    ShiftDfa nullDfa = compile("null");
    assertThat(nullDfa).isNotNull();
    assertThat(nullDfa.numStates()).isEqualTo(5);
    assertThat(nullDfa.matches("null", 0, 4)).isTrue();
    assertThat(nullDfa.matches("nul", 0, 3)).isFalse();
  }

  @Test
  @DisplayName("quantified digits and repetition ranges")
  void quantifiedDigitsAndRepetitions() {
    ShiftDfa dfa = compile("[0-9]{1,3}");
    assertThat(dfa).isNotNull();
    assertThat(dfa.numStates()).isLessThanOrEqualTo(10);

    assertThat(dfa.matches("1", 0, 1)).isTrue();
    assertThat(dfa.matches("12", 0, 2)).isTrue();
    assertThat(dfa.matches("123", 0, 3)).isTrue();
    assertThat(dfa.matches("1234", 0, 4)).isFalse();
    assertThat(dfa.matches("", 0, 0)).isFalse();
    assertThat(dfa.matches("a", 0, 1)).isFalse();
  }

  @Test
  @DisplayName("c-style identifiers")
  void identifiers() {
    ShiftDfa dfa = compile("[a-zA-Z_][a-zA-Z0-9_]*");
    assertThat(dfa).isNotNull();
    assertThat(dfa.numStates()).isLessThanOrEqualTo(10);

    assertThat(dfa.matches("foo", 0, 3)).isTrue();
    assertThat(dfa.matches("_var123", 0, 7)).isTrue();
    assertThat(dfa.matches("123var", 0, 6)).isFalse();
    assertThat(dfa.matches("", 0, 0)).isFalse();
  }

  @Test
  @DisplayName("year-month date pattern")
  void yearMonthDatePattern() {
    ShiftDfa dfa = compile("[0-9]{4}-[0-9]{2}");
    assertThat(dfa).isNotNull();
    assertThat(dfa.numStates()).isLessThanOrEqualTo(10);

    assertThat(dfa.matches("2026-08", 0, 7)).isTrue();
    assertThat(dfa.matches("1999-12", 0, 7)).isTrue();
    assertThat(dfa.matches("2026-8", 0, 6)).isFalse();
    assertThat(dfa.matches("2026/08", 0, 7)).isFalse();
  }

  @Test
  @DisplayName("lookingAt prefix extraction")
  void lookingAtPrefixExtraction() {
    ShiftDfa dfa = compile("a(?:b+)c");
    assertThat(dfa).isNotNull();

    assertThat(dfa.lookingAt("abbcx", 0, 5)).isEqualTo(4);
    assertThat(dfa.lookingAt("abbc", 0, 4)).isEqualTo(4);
    assertThat(dfa.lookingAt("ac", 0, 2)).isEqualTo(-1);
    assertThat(dfa.lookingAt("xabbc", 0, 5)).isEqualTo(-1);

    byte[] bytes = "abbcx".getBytes(UTF_8);
    Utf8InputScanner scanner = new Utf8InputScanner(bytes, 0, bytes.length);
    assertThat(dfa.lookingAt(scanner, 0, 5)).isEqualTo(4);
  }

  @Test
  @DisplayName("self-loop vector acceleration via StateAccelerator")
  void stateAcceleratorSelfLoop() {
    ShiftDfa dfa = compile("[\\x00-\\x21\\x23-\\x7F]*\"");
    assertThat(dfa).isNotNull();
    assertThat(dfa.accelerators()).isNotNull();

    String longInput = "a".repeat(1000) + "\"";
    assertThat(dfa.matches(longInput, 0, longInput.length())).isTrue();

    String longNonMatching = "a".repeat(1000) + "x";
    assertThat(dfa.matches(longNonMatching, 0, longNonMatching.length())).isFalse();
  }

  @Test
  @DisplayName("rejects excessive state count (> 10 states)")
  void rejectsExcessiveStates() {
    // 15 linear states
    String longPattern = "a".repeat(15);
    ShiftDfa dfa = compile(longPattern);
    assertThat(dfa).isNull();
  }

  @Test
  @DisplayName("full pattern matcher integration")
  void patternMatcherIntegration() {
    Pattern pattern = Pattern.compile("true|false");
    Matcher matcher = pattern.matcher("true");
    assertThat(matcher.matches()).isTrue();

    Matcher matcherFalse = pattern.matcher("false");
    assertThat(matcherFalse.matches()).isTrue();

    Matcher matcherOther = pattern.matcher("other");
    assertThat(matcherOther.matches()).isFalse();
  }
}
