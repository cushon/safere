// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;
import org.safere.Pattern;

final class SemanticDomainTest {
  @Test
  void sampledPatternsCompileInTheirDeclaredDomains() {
    Random choices = new Random(1209);
    for (int i = 0; i < 512; i++) {
      var sample = SemanticDomain.generate(choices::nextInt);
      FuzzSupport.compileRequired(sample.regex(), 0);
      FuzzSupport.compileRequired(
          sample.regex(), Pattern.COMMENTS | Pattern.UNICODE_CHARACTER_CLASS);
      FuzzSupport.compileRequired(CaptureSemanticsFuzzer.generateRegex(choices::nextInt, false), 0);
      FuzzSupport.compileRequired(CaptureSemanticsFuzzer.generateRegex(choices::nextInt, true), 0);
    }
  }

  @Test
  void generatedPatternsExerciseCapturesReplacementAndBothSplitOperations() {
    var sample = SemanticDomain.generate(choices(3, 1, 0, 1, 1, 1, 0));
    assertThat(sample.regex()).isEqualTo("(,)(a)");
    String subject = "x" + sample.witness() + "y" + sample.witness() + "z";
    var compiled = FuzzSupport.compileRequired(sample.regex(), 0);
    var found = compiled.matcher(subject);
    assertThat(found.find()).isTrue();
    assertThat(found.group(2)).isEqualTo("a");
    assertThat(found.find()).isTrue();
    assertThat(found.find()).isFalse();
    assertThat(compiled.matcher(subject).replaceAll("[$2$1]")).isTrue();
    ReplacementFuzzer.compareReplacements(sample.regex(), 0, subject, "[$2$1]", false);
    SplitFuzzer.compareSplits(sample.regex(), 0, subject, 0);
    assertThat(compiled.safeRePattern().split(subject, 0)).containsExactly("x", "y", "z");
    assertThat(compiled.safeRePattern().splitWithDelimiters(subject, 0))
        .containsExactly("x", ",a", "y", ",a", "z");
  }

  @Test
  void generatedCompositeCanRepeatAndRetainACapture() {
    var sample = SemanticDomain.generate(choices(1, 0, 0, 6, 0, 1, 1, 2, 0, 0, 1, 0, 1));
    assertThat(sample.regex()).isEqualTo("(?:(a)(?:b)){1,2}");
    assertThat(sample.witness()).isEqualTo("abab");
    var matcher = FuzzSupport.compileRequired(sample.regex(), 0).matcher(sample.witness());
    assertThat(matcher.matches()).isTrue();
    assertThat(matcher.group(1)).isEqualTo("a");
    ReplacementFuzzer.compareReplacements(sample.regex(), 0, sample.witness(), "[$1]", false);
  }

  @Test
  void possessiveChoiceDependsOnTheImmediateOperand() {
    // The directly quantified consuming capture cannot take a possessive modifier.
    String consuming = CaptureSemanticsFuzzer.generateRegex(choices(1, 0, 10, 0, 0, 0, 0), false);
    assertThat(consuming).isEqualTo("(?:(a))*");
    // An inner zero count makes the wrapped operand statically zero-width.
    String zeroCount =
        CaptureSemanticsFuzzer.generateRegex(choices(1, 1, 1, 0, 1, 10, 0, 0), false);
    assertThat(zeroCount).isEqualTo("(?:(?:(a)){0})*+");
    var pattern = FuzzSupport.compileRequired(zeroCount, 0);
    assertThat(pattern.matcher("").matches()).isTrue();
    // A count following a quantifier owns a separate empty operand.
    String implicit = CaptureSemanticsFuzzer.generateRegex(choices(1, 0, 0, 1, 6, 0, 0, 0), false);
    assertThat(implicit).isEqualTo("(?:(a))*{1}+");
    assertThat(FuzzSupport.compileRequired(implicit, 0).matcher("aaa").matches()).isTrue();
  }

  @Test
  void previousMatchAssertionRemainsInTheSemanticDomain() {
    var sample = SemanticDomain.generate(choices(1, 1, 0, 4));
    assertThat(sample.regex()).isEqualTo("\\G(a)");
    assertThat(FuzzSupport.compileRequired(Pattern.quote("\\G"), 0).matcher("\\G").matches())
        .isTrue();
    assertThat(FuzzSupport.compileRequired("\\G", Pattern.LITERAL).matcher("\\G").matches())
        .isTrue();
    var matcher = FuzzSupport.compileRequired(sample.regex(), 0).matcher("aa!a");
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.find()).isFalse();
  }

  @Test
  void replayTextCasesFromThePreviousCorpus() {
    // The old files used newline-separated pattern, subject and replacement fields.
    MatchFuzzer.compareMatchOperations("a*", 0, "", 0);
    MatchFuzzer.compareMatchOperations("^foo$", Pattern.MULTILINE, "foo\nbar", 0);
    MatchFuzzer.compareMatchOperations("^.$", 0, "\u2028", 0);
    MatchFuzzer.compareMatchOperations(".", 0, "😀", 0);

    CaptureSemanticsFuzzer.compareCaptures("(a)+", "aaay", false);
    CaptureSemanticsFuzzer.compareCaptures("(a?)*", "aaa", false);
    CaptureSemanticsFuzzer.compareCaptures("(?<word>a){1,2}", "xaa", true);
    ReplacementFuzzer.compareReplacements("([a-z]+)-(\\d+)", 0, "abc-123", "$2:$1", false);
    ReplacementFuzzer.compareReplacements("([.$])", 0, "a.$b", "\\$$1", true);
    SplitFuzzer.compareSplits(",\\s*", 0, "alpha, beta,,gamma", 0);
    SplitFuzzer.compareSplits(".*", 0, "abc", 0);

    RegionBoundsFuzzer.compareRegion("^foo", 0, "xxfoo", 2, 5, true, false);
    RegionBoundsFuzzer.compareRegion("\\bword\\b", 0, "word sword word", 6, 10, false, true);

    compareFinds("\\ba", "a");
    compareFinds("(a)?b", "aaab");
    compareFinds("\\b", "abc");
    compareFinds("(?:(a)?)??c?", "c" + "b".repeat(63));
  }

  private static void compareFinds(String regex, String subject) {
    var matcher = FuzzSupport.compileRequired(regex, 0).matcher(subject);
    while (matcher.find()) {
      matcher.group();
    }
  }

  private static IntUnaryOperator choices(Integer... values) {
    Iterator<Integer> next = List.of(values).iterator();
    return bound -> {
      int value = next.next();
      assertThat(value).isBetween(0, bound - 1);
      return value;
    };
  }
}
