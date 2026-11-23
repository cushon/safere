// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Existence proofs must not publish unguarded nullable-loop bounds or captures. */
@DisabledForCrosscheck("compares SafeRE's guarded and DFA execution paths")
class NullableExistenceTest {
  private static final EnginePathOptions EXACT = EnginePathOptions.builder().dfa(false).build();

  @Test
  void matcherFreeExistenceAgreesWithGuardedExecution() {
    List<String> patterns =
        List.of(
            "(a?)*y",
            "(?:a?)*y",
            "((a?)*)y",
            "(a?)*?y",
            "((a?){2,4})*y",
            "(a?)*(z)?y",
            "(a?)*z|y",
            "(a?)*y.*",
            "(a?)*y$",
            "^(a?)*y",
            "(a?)*",
            "(|a)*y",
            "(a|)*y",
            "(\\b|a)*y",
            "(é?)*😀",
            "(😀?)*y");
    List<String> inputs =
        List.of(
            "",
            "y",
            "aaay",
            "aaaz",
            "y" + "a".repeat(1024) + "z",
            "ay".repeat(32),
            "a".repeat(1023) + "y",
            "é".repeat(512) + "😀",
            "😀😀y");
    for (String regex : patterns) {
      for (String input : inputs) {
        Pattern actual = Pattern.compile(regex);
        Pattern expected = Pattern.compile(regex, 0, EXACT);
        boolean found = expected.matcher(input).find();
        assertThat(actual.asPredicate().test(input))
            .as("/%s/ on %s", regex, input)
            .isEqualTo(found);
        assertThat(actual.find(utf8(input))).as(regex).isEqualTo(found);
      }
    }
  }

  private static Utf8Input utf8(String text) {
    return Utf8Input.trusted(text.getBytes(UTF_8));
  }
}
