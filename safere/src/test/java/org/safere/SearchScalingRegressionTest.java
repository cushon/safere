// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("WorkCounter is an internal SafeRE API")
@Tag("work-counter")
class SearchScalingRegressionTest {

  @Test
  void reverseDfaSuffixFailureIsConstantWorkForStringInput() {
    Pattern pattern = Pattern.compile("[ -~]*ABCDEFGHIJKLMNOPQRSTUVWXYZ$");
    assertReverseDfaSuffixFailureIsConstantWork(size -> pattern.matcher("a".repeat(size)).find());
  }

  @Test
  void reverseDfaSuffixFailureIsConstantWorkForUtf8Input() {
    Pattern pattern = Pattern.compile("[ -~]*ABCDEFGHIJKLMNOPQRSTUVWXYZ$");
    assertReverseDfaSuffixFailureIsConstantWork(
        size -> pattern.matcher(Utf8Input.trusted("a".repeat(size).getBytes(UTF_8))).find());
  }

  @Test
  void greedyKeywordAlternationSuccessNearEndIsConstantWorkForUtf8Input() {
    Pattern pattern = Pattern.compile("(?is).*\\b(you|your)\\b.*");
    assertConstantWork(
        size ->
            pattern
                .matcher(Utf8Input.trusted(("a".repeat(size) + " YOUR tail").getBytes(UTF_8)))
                .find(),
        "UTF-8 keyword search");
  }

  @Test
  void disjointRequiredLiteralPrefilterIsLinearAcrossStringFindIteration() {
    Pattern pattern = Pattern.compile("(?:banana\\d|apple\\d)");
    assertRepeatedFindWorkIsLinear(size -> pattern.matcher("apple0 ".repeat(size))::find, "String");
  }

  @Test
  void disjointRequiredLiteralPrefilterIsLinearAcrossUtf8FindIteration() {
    Pattern pattern = Pattern.compile("(?:banana\\d|apple\\d)");
    assertRepeatedFindWorkIsLinear(
        size -> pattern.matcher(Utf8Input.trusted("apple0 ".repeat(size).getBytes(UTF_8)))::find,
        "UTF-8");
  }

  @Test
  void disjointRequiredLiteralOptimizationDoesNotAddRedundantUtf8Scans() {
    String regex = "(?:banana\\d|apple\\d)";
    Pattern defaultPattern = Pattern.compile(regex);
    Pattern withoutLiteralFastPaths =
        Pattern.compile(regex, 0, EnginePathOptions.builder().literalFastPaths(false).build());
    Utf8Input input = Utf8Input.trusted("x".repeat(32_768).getBytes(UTF_8));

    long defaultWork = countAllMatches(defaultPattern.matcher(input)::find, 0);
    long fallbackWork = countAllMatches(withoutLiteralFastPaths.matcher(input)::find, 0);

    assertThat(defaultWork)
        .as("UTF-8 literal optimization should not add full-input scans before the DFA")
        .isLessThanOrEqualTo(fallbackWork);
  }

  @Test
  void disjointRequiredLiteralOptimizationDoesNotScanStartAnchoredInput() {
    Pattern pattern = Pattern.compile("^(?:banana\\d|apple\\d)");

    long work =
        WorkCounter.countForTesting(
            () -> assertThat(pattern.matcher("x".repeat(32_768)).find()).isFalse());

    assertThat(work)
        .as("start-anchored rejection should inspect only the viable start position")
        .isLessThan(100);
  }

  private static void assertRepeatedFindWorkIsLinear(
      IntFunction<FindIterator> matcherFactory, String description) {
    long smallerWork = countAllMatches(matcherFactory.apply(500), 500);
    long largerWork = countAllMatches(matcherFactory.apply(2_000), 2_000);

    assertThat(largerWork)
        .as("%s repeated find work should scale linearly", description)
        .isLessThan(smallerWork * 6);
  }

  private static long countAllMatches(FindIterator matcher, int expectedMatches) {
    return WorkCounter.countForTesting(
        () -> {
          int matches = 0;
          while (matcher.find()) {
            matches++;
          }
          assertThat(matches).isEqualTo(expectedMatches);
        });
  }

  @FunctionalInterface
  private interface FindIterator {
    boolean find();
  }

  private static void assertReverseDfaSuffixFailureIsConstantWork(IntPredicate find) {
    long work2000 =
        WorkCounter.countForTesting(
            () -> {
              boolean matched = find.test(2_000);
              assertThat(matched).isFalse();
            });

    long work10000 =
        WorkCounter.countForTesting(
            () -> {
              boolean matched = find.test(10_000);
              assertThat(matched).isFalse();
            });

    // If a required-content prefilter runs first, it scans the entire input, resulting in at least
    // 2,000 and 10,000 operations respectively.
    //
    // Under reverse DFA suffix acceleration, it rejects after inspecting only a few characters
    // from the end of the text, executing in constant time independent of text size.
    assertThat(work2000)
        .as("Short text failing scan should run in constant-time reverse DFA setup")
        .isGreaterThanOrEqualTo(0)
        .isLessThan(200);

    assertThat(work10000)
        .as("Long text failing scan should also run in constant-time reverse DFA setup")
        .isGreaterThanOrEqualTo(0)
        .isLessThan(200);

    // Assert that scaling is sub-linear (effectively constant)
    assertThat(work10000)
        .as("Work scaling should be flat, not linear with input size increase")
        .isLessThanOrEqualTo(Math.max(10, work2000 * 2));
  }

  private static void assertConstantWork(IntPredicate find, String description) {
    long work2000 = WorkCounter.countForTesting(() -> assertThat(find.test(2_000)).isTrue());
    long work10000 = WorkCounter.countForTesting(() -> assertThat(find.test(10_000)).isTrue());

    assertThat(work2000).as("%s on short input", description).isPositive().isLessThan(200);
    assertThat(work10000).as("%s on long input", description).isPositive().isLessThan(200);
    assertThat(work10000)
        .as("%s should not scale with the prefix", description)
        .isLessThan(work2000 * 2);
  }
}
