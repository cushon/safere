// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;
import org.safere.Pattern;

final class UnsupportedSyntaxTest {
  private static final String GROUP_ERROR = "invalid Perl operator";
  private static final String ESCAPE_ERROR = "invalid escape sequence";
  private static final String REFERENCE_ERROR = "backreferences are not supported";
  private static final String POSSESSIVE_ERROR = "possessive quantifiers are not supported";

  static final List<Feature> FEATURES =
      List.of(
          new Feature("(?=a)a", null, GROUP_ERROR, true),
          new Feature("(?!b)a", null, GROUP_ERROR, true),
          new Feature("a(?<=a)b", null, GROUP_ERROR, true),
          new Feature("a(?<!b)", null, GROUP_ERROR, true),
          new Feature("(?>a+)", null, GROUP_ERROR, true),
          new Feature("(a)\\1", null, REFERENCE_ERROR, true),
          new Feature("(a)\\12", null, REFERENCE_ERROR, true),
          new Feature("\\123", null, REFERENCE_ERROR, true),
          new Feature("\\400", null, REFERENCE_ERROR, true),
          new Feature("\\777", null, REFERENCE_ERROR, true),
          new Feature("\\12*", null, REFERENCE_ERROR, true),
          new Feature("\\2222*", null, REFERENCE_ERROR, true),
          new Feature("3*\\2222*", null, REFERENCE_ERROR, true),
          new Feature("(?<item>a)\\k<item>", null, ESCAPE_ERROR, true),
          new Feature("a", "+", POSSESSIVE_ERROR, true),
          new Feature("[ab]", "*", POSSESSIVE_ERROR, true),
          new Feature("(a?)", "?", POSSESSIVE_ERROR, true),
          new Feature("(?:a{0}|b)", "{0,2}", POSSESSIVE_ERROR, true),
          new Feature("a", "{0}", POSSESSIVE_ERROR, true),
          new Feature("\\C", null, ESCAPE_ERROR, false));
  static final List<String> CONTEXTS = List.of("%s", "x(?:%s)y", "(?:z|%s)$", "(?:%s){0}");
  static final List<String> GAPS = List.of("", "\\Q\\E", " ", " #q\n ");

  private record Feature(String body, String quantifier, String description, boolean jdkAccepts) {
    String syntax(String gap) {
      return quantifier == null ? body : body + quantifier + gap + "+";
    }
  }

  private enum Mode {
    COMMENTS,
    SCOPED_COMMENTS,
    PLAIN
  }

  private record Rejection(String regex, int flags, String description) {}

  @Test
  void generatedRejectionsHaveTheExpectedCauseAndControlsStillMatch() {
    for (var feature : FEATURES) {
      for (String context : CONTEXTS) {
        for (String gap : GAPS) {
          for (var mode : Mode.values()) {
            var rejection = rejection(feature, context, gap, mode);
            assertRejected(rejection);
            if (feature.jdkAccepts()) {
              assertThatNoException()
                  .isThrownBy(
                      () -> java.util.regex.Pattern.compile(rejection.regex(), rejection.flags()));
            } else {
              assertThatThrownBy(
                      () -> java.util.regex.Pattern.compile(rejection.regex(), rejection.flags()))
                  .isInstanceOf(PatternSyntaxException.class);
            }
          }
        }
      }
      assertSupportedControls(feature);
    }
  }

  @Test
  void unexpectedAcceptanceFailsARejectionCheck() {
    var supported = new Rejection("(a)", 0, "invalid Perl operator");
    assertThatThrownBy(() -> assertRejected(supported))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("must reject");
  }

  private static Rejection rejection(Feature feature, String context, String gap, Mode mode) {
    String regex = context.formatted(feature.syntax(mode == Mode.PLAIN ? "" : gap));
    return switch (mode) {
      case COMMENTS -> new Rejection(regex, Pattern.COMMENTS, feature.description());
      case SCOPED_COMMENTS -> new Rejection("(?x:" + regex + ")", 0, feature.description());
      case PLAIN -> new Rejection(regex, 0, feature.description());
    };
  }

  private static void assertRejected(Rejection rejection) {
    try {
      Pattern.compile(rejection.regex(), rejection.flags());
    } catch (PatternSyntaxException error) {
      assertThat(error.getDescription())
          .as("rejection reason for /%s/ with flags %s", rejection.regex(), rejection.flags())
          .isEqualTo(rejection.description());
      return;
    }
    throw new AssertionError(
        "SafeRE must reject /%s/ with flags %s".formatted(rejection.regex(), rejection.flags()));
  }

  private static void assertSupportedControls(Feature feature) {
    String syntax = feature.syntax("");
    for (String prefix : List.of("x", "é", "😀")) {
      String subject = prefix + syntax + "z";
      assertMatches(Pattern.quote(prefix + syntax) + "z", 0, subject);
      assertMatches(subject, Pattern.LITERAL, subject);
      assertMatches("(?x:" + prefix + " # " + syntax + "\nz)", 0, prefix + "z");
    }
    if (feature.quantifier() != null) {
      // The same possessive modifier is valid when the immediate operand cannot consume.
      String zeroWidth = "(?:(?:" + feature.body() + "){0})";
      assertMatches(zeroWidth + feature.quantifier() + " #q\n +", Pattern.COMMENTS, "");
    }
  }

  private static void assertMatches(String regex, int flags, String subject) {
    assertThat(FuzzSupport.compileRequired(regex, flags).matcher(subject).matches())
        .as("/%s/ must match %s with flags %s", regex, subject, flags)
        .isTrue();
  }
}
