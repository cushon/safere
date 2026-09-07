// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class FuzzSupportOracleTimeoutTest {
  private static final String TIMEOUT_PROPERTY = "safere.fuzz.jdkOracleTimeoutMillis";

  @Test
  @DisplayName("JDK oracle timeout marks pathological backtracking input unavailable")
  void jdkOracleTimeoutMarksPathologicalBacktrackingInputUnavailable() {
    String previous = System.getProperty(TIMEOUT_PROPERTY);
    System.setProperty(TIMEOUT_PROPERTY, "1");
    try {
      assertTimeoutPreemptively(
          Duration.ofSeconds(2),
          () ->
              assertFalse(
                  FuzzSupport.jdkOracleCompletesForTesting(
                      "(?:(?:(a|aa))+)+y", "a".repeat(64) + "x")));
    } finally {
      if (previous == null) {
        System.clearProperty(TIMEOUT_PROPERTY);
      } else {
        System.setProperty(TIMEOUT_PROPERTY, previous);
      }
    }
  }

  @Test
  @DisplayName("JDK oracle stack overflow marks recursive matching unavailable")
  void jdkOracleStackOverflowMarksRecursiveMatchingUnavailable() {
    assertFalse(FuzzSupport.jdkOracleStackOverflowIsAvailableForTesting());
  }

  @Test
  @DisplayName("malformed intersection exclusion validates the syntax at the error index")
  void malformedIntersectionExclusionValidatesSyntaxAtErrorIndex() {
    assertTrue(
        FuzzSupport.isMalformedCharacterClassIntersectionForTesting(
            "[a&&&b]", 0, "invalid character class intersection", 2));
    assertFalse(
        FuzzSupport.isMalformedCharacterClassIntersectionForTesting(
            "(?x)[a& &b]", 0, "invalid character class intersection", 6));
    assertFalse(
        FuzzSupport.isMalformedCharacterClassIntersectionForTesting(
            "[a-z&&[def]]", 0, "empty left side of character class intersection", 4));
    assertTrue(
        FuzzSupport.isMalformedCharacterClassIntersectionForTesting(
            "[a&&]", 0, "empty right side of character class intersection", 4));
    assertTrue(
        FuzzSupport.isMalformedCharacterClassIntersectionForTesting(
            "[a&&-b]", 0, "dangling character class '-'", 4));
  }
}
