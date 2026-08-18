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
  void caseInsensitivePrefixRepeatedFindIsLinearAcrossString() {
    Pattern pattern = Pattern.compile("(?i)keyword_to_find");
    assertRepeatedFindWorkIsLinear(
        size -> pattern.matcher("KEYWORD_TO_FIND ".repeat(size))::find, "String");
  }

  @Test
  void caseInsensitiveSingleCharacterRepeatedFindIsLinearAcrossString() {
    Pattern pattern = Pattern.compile("(?i)z");
    assertRepeatedFindWorkIsLinear(size -> pattern.matcher("z".repeat(size))::find, "String");
  }

  @Test
  void caseInsensitiveSparseFalseCandidatesAreLinearAcrossString() {
    Pattern pattern = Pattern.compile("(?i)zq");
    IntFunction<String> input = size -> ("zX" + "a".repeat(32)).repeat(size) + "Zq";

    long smallerWork = countAllMatches(pattern.matcher(input.apply(100))::find, 1);
    long largerWork = countAllMatches(pattern.matcher(input.apply(400))::find, 1);

    assertThat(largerWork)
        .as("String sparse false-candidate work should scale linearly")
        .isLessThanOrEqualTo(smallerWork * 6);
  }

  @Test
  void caseInsensitivePrefixRepeatedFindIsLinearAcrossUtf8() {
    Pattern pattern = Pattern.compile("(?i)keyword_to_find");
    assertRepeatedFindWorkIsLinear(
        size ->
            pattern.matcher(Utf8Input.trusted("KEYWORD_TO_FIND ".repeat(size).getBytes(UTF_8)))
                ::find,
        "UTF-8");
  }

  @Test
  void caseInsensitiveDensePrefixFailureIsLinearForStringInput() {
    Pattern pattern =
        Pattern.compile("(?i)aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaab");
    String input = "a".repeat(10_000);
    long work =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(input).find()).isFalse());
    assertThat(work)
        .as("Dense false candidate prefix verification on String must remain linearly bounded")
        .isLessThan(input.length() * 3L);
  }

  @Test
  void caseInsensitiveDensePrefixFailureIsLinearForUtf8Input() {
    Pattern pattern =
        Pattern.compile("(?i)aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaab");
    String input = "a".repeat(10_000);
    long work =
        WorkCounter.countForTesting(
            () ->
                assertThat(pattern.matcher(Utf8Input.trusted(input.getBytes(UTF_8))).find())
                    .isFalse());
    assertThat(work)
        .as("Dense false candidate prefix verification on UTF-8 must remain linearly bounded")
        .isLessThan(input.length() * 3L);
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

  @Test
  void disjointRequiredLiteralCandidateIsScannedOnlyOnce() {
    Pattern pattern = Pattern.compile(".*(?:apple|banana|cherry).*");
    String input = "x".repeat(32_768) + "cherry";

    long work =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(input).find()).isTrue());

    assertThat(work)
        .as("a positive disjoint-literal candidate should not repeat every full-input scan")
        .isLessThan(input.length() * 5L);
  }

  @Test
  void fixedOffsetLiteralSelectsRareTokenToAvoidCandidateVerificationWork() {
    // "____" has length 4 with common underscores.
    // "zq" has length 2 with rare letters 'z' and 'q'.
    Pattern pattern = Pattern.compile("[0-9]{2}____[a-z]zq[a-z]");
    String input = "user_name_field_data____common_suffix\n".repeat(1_000);

    long work =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(input).find()).isFalse());

    assertThat(work)
        .as("RarityOracle selection must avoid false candidate verification work on common tokens")
        .isLessThanOrEqualTo(input.length() + 100);
  }

  @Test
  void requiredLiteralSelectsRareTokenToRejectNoiseWithMinimalWork() {
    // "____________" has length 12 with common underscores.
    // "404_ERR" has length 7 with high-rarity digits and uppercase letters.
    Pattern pattern = Pattern.compile(".*(____________).*?(404_ERR).*");
    String input = "log_entry_line_with____________separators_and_delimiters\n".repeat(1_000);

    long work =
        WorkCounter.countForTesting(() -> assertThat(pattern.matcher(input).find()).isFalse());

    assertThat(work)
        .as("Required literal prefilter must reject on the selective token with minimal work")
        .isLessThanOrEqualTo(input.length() + 100);
  }

  @Test
  void literalSelectivityScoringIsLinearInPatternSize() {
    long smallerWork = WorkCounter.countForTesting(() -> Pattern.compile(selectivityPattern(100)));
    long largerWork = WorkCounter.countForTesting(() -> Pattern.compile(selectivityPattern(400)));

    assertThat(largerWork)
        .as("Literal selectivity scoring should scale linearly with pattern size")
        .isLessThanOrEqualTo(smallerWork * 6);
  }

  private static void assertRepeatedFindWorkIsLinear(
      IntFunction<FindIterator> matcherFactory, String description) {
    long smallerWork = countAllMatches(matcherFactory.apply(500), 500);
    long largerWork = countAllMatches(matcherFactory.apply(2_000), 2_000);

    assertThat(largerWork)
        .as("%s repeated find work should scale linearly", description)
        .isLessThanOrEqualTo(Math.max(10, smallerWork * 6));
  }

  private static String selectivityPattern(int size) {
    StringBuilder pattern = new StringBuilder("[0-9]").append("z".repeat(size));
    for (int i = 0; i < size; i++) {
      pattern.append("[0-9]aa");
    }
    return pattern.toString();
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
