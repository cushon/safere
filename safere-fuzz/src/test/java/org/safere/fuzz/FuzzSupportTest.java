// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class FuzzSupportTest {
  @Test
  void unrestrictedCompileExplorationStillReportsDialectDifferences() {
    assertThatThrownBy(() -> FuzzSupport.compileCompatibleOrSkip("(a)\\1", 0))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("compile divergence");
  }

  @Test
  void rejectionByBothEnginesFailsWhenAcceptanceIsRequired() {
    assertThatThrownBy(() -> FuzzSupport.compileRequired("[", 0))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("expected acceptance");
  }

  @ParameterizedTest
  @ValueSource(strings = {"[", "(", "\\"})
  void rejectionByBothEnginesEndsTheComparison(String regex) {
    assertThatThrownBy(() -> org.safere.Pattern.compile(regex))
        .isInstanceOf(PatternSyntaxException.class);
    assertThatThrownBy(() -> Pattern.compile(regex)).isInstanceOf(PatternSyntaxException.class);
    assertThat(FuzzSupport.compileCompatibleOrSkip(regex, 0)).isNull();
  }

  @Test
  void appendReplacementReportsMismatchedRejections() {
    String input = "ax";
    String regex = "(?<other>x)";
    var safeRe = org.safere.Pattern.compile(regex).matcher(input);
    // Give the JDK matcher the named group so it reaches the out-of-range reference.
    var jdk = Pattern.compile("(?<part>x)").matcher(input);
    assertThat(safeRe.find()).isTrue();
    assertThat(jdk.find()).isTrue();
    var pair = new FuzzSupport.MatcherPair(regex, 0, input, safeRe, jdk);

    assertThatThrownBy(() -> pair.appendReplacement(new StringBuffer(), "lit${part}$9"))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("appendReplacement exception category")
        .hasMessageContaining("IllegalArgumentException")
        .hasMessageContaining("IndexOutOfBoundsException");
  }
}
