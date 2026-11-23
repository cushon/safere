// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("compares optimized searches with the JDK")
class ExistenceSearchTest {
  private static final List<String> PATTERNS =
      List.of(
          "([\\S]+)\\b\\[(.+)\\]",
          "a.*z",
          "(a.*z|b)",
          "(b|a.*z)",
          "a.*?z",
          "(a.*z)?",
          "(a|ab)+",
          "a.*$",
          "(?s:a.*)\\z",
          "(a?)*y",
          "\\b[a-z]+\\b",
          "(?:a.*z|.)");

  @Test
  void matcherFreeExistenceAgreesWithJdk() {
    for (String regex : PATTERNS) {
      for (String input :
          List.of(
              "config.overrides[some_value] ".repeat(80),
              "a b " + "x".repeat(2000) + "z suffix",
              "a b\n" + "x".repeat(2000) + "z\n",
              "éλ a b " + "x".repeat(2000) + "z",
              "x".repeat(2000))) {
        Pattern pattern = Pattern.compile(regex);
        assertThat(pattern.asPredicate().test(input))
            .as(regex)
            .isEqualTo(java.util.regex.Pattern.compile(regex).matcher(input).find());
        assertThat(pattern.find(Utf8Input.trusted(input.getBytes(UTF_8))))
            .as(regex)
            .isEqualTo(java.util.regex.Pattern.compile(regex).matcher(input).find());
      }
    }
  }

  @Test
  void existenceDfaStopsBeforeGreedyPreferredEnd() {
    Pattern pattern = Pattern.compile("a.*z");
    String input = "az" + "x".repeat(2000) + "z";
    Dfa dfa = pattern.forwardExistenceDfa();
    for (InputScanner scanner :
        List.of(new StringInputScanner(input), new Utf8InputScanner(input.getBytes(UTF_8)))) {
      Dfa.SearchResult result = dfa.searchExists(scanner, 0, false, false, AdaptiveBackoff.NEUTRAL);
      assertThat(result.matched()).isTrue();
      assertThat(result.pos()).isLessThan(10);
      assertThat(pattern.forwardFirstMatchDfa().doSearch(scanner, 0, false, false).pos())
          .isEqualTo(input.length());
    }
  }
}
