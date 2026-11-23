// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.MatchResult;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Verifies that existence, bounds, and capture consumers agree on the selected match. */
@DisabledForCrosscheck("compares String and UTF-8 matchers with the JDK")
class FindBoundsDemandTest {
  private static final String NULLABLE = "((a?)*)y";

  private static Stream<Arguments> searches() {
    return Stream.of(
        Arguments.of("([0-9]{3,})", "x".repeat(100) + "1234 5678 " + "x".repeat(1000)),
        Arguments.of(NULLABLE, "aay aaay " + "a".repeat(1024)),
        Arguments.of("((a?)*)", "aaa x aa"),
        Arguments.of("((a?)*)b|a", "a".repeat(800) + "b a"),
        Arguments.of("(?m)((é?)*)Y\\b$", "😀ééY\r\nY\r\n" + "x".repeat(700)),
        Arguments.of("((é?)*)Y\\b", "éYz " + "😀".repeat(300) + "ééY "));
  }

  @ParameterizedTest
  @MethodSource("searches")
  void firstFindBoundsAndCapturesFollowJdkPriority(String regex, String text) {
    var expected = java.util.regex.Pattern.compile(regex).matcher(text);
    Pattern pattern = Pattern.compile(regex);
    Matcher string = pattern.matcher(text);
    Utf8Matcher utf8 = Pattern.compile(regex).matcher(Utf8Input.trusted(text.getBytes(UTF_8)));
    while (expected.find()) {
      assertThat(string.find()).isTrue();
      assertThat(utf8.find()).isTrue();
      for (int group = 0; group <= expected.groupCount(); group++) {
        assertThat(string.start(group)).isEqualTo(expected.start(group));
        assertThat(string.end(group)).isEqualTo(expected.end(group));
        assertThat(utf8.start(group)).isEqualTo(byteOffset(text, expected.start(group)));
        assertThat(utf8.end(group)).isEqualTo(byteOffset(text, expected.end(group)));
        assertThat(string.group(group)).isEqualTo(expected.group(group));
      }
    }
    assertThat(string.find()).isFalse();
    assertThat(utf8.find()).isFalse();
  }

  @ParameterizedTest
  @MethodSource("searches")
  void unreadFindsContinueFromThePreferredEnd(String regex, String text) {
    var expected = java.util.regex.Pattern.compile(regex).matcher(text);
    Matcher string = Pattern.compile(regex).matcher(text);
    Utf8Matcher utf8 = Pattern.compile(regex).matcher(Utf8Input.trusted(text.getBytes(UTF_8)));
    while (expected.find()) {
      assertThat(string.find()).isTrue();
      assertThat(utf8.find()).isTrue();
    }
    assertThat(string.find()).isFalse();
    assertThat(utf8.find()).isFalse();
  }

  @Test
  void resetDiscardsAnUnreadMatchAndSnapshotPreservesTheNextMatch() {
    Matcher matcher = Pattern.compile(NULLABLE).matcher("aay " + "x".repeat(700));
    assertThat(matcher.find()).isTrue();

    matcher.reset("x aaay " + "x".repeat(700));
    assertThat(matcher.find()).isTrue();
    MatchResult snapshot = matcher.toMatchResult();
    assertThat(snapshot.start()).isEqualTo(2);
    assertThat(snapshot.end()).isEqualTo(6);
    assertThat(snapshot.group(1)).isEqualTo("aaa");
    matcher.reset();
    assertThat(snapshot.group()).isEqualTo("aaay");
  }

  @Test
  void usePatternContinuesAfterTheUnreadMatch() {
    String text = "aay aaay " + "x".repeat(700);
    Matcher matcher = Pattern.compile(NULLABLE).matcher(text);
    assertThat(matcher.find()).isTrue();
    matcher.usePattern(Pattern.compile("a+"));
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(4);
    assertThat(matcher.group()).isEqualTo("aaa");
  }

  @Test
  void aBoundsReadOnAnotherMatcherPreservesTheUnreadResult() {
    Pattern pattern = Pattern.compile(NULLABLE);
    Matcher unread = pattern.matcher("aay " + "x".repeat(700));
    Matcher reader = pattern.matcher("x aaay " + "x".repeat(700));
    assertThat(unread.find()).isTrue();
    assertThat(reader.find()).isTrue();
    assertThat(reader.end()).isEqualTo(6);
    assertThat(unread.start()).isZero();
    assertThat(unread.group(1)).isEqualTo("aa");
  }

  @Test
  void replacementUsesThePreferredBoundsAndCaptures() {
    String text = "aay aaay " + "x".repeat(700);
    var expected = java.util.regex.Pattern.compile(NULLABLE).matcher(text);
    var actual = Pattern.compile(NULLABLE).matcher(text);
    assertThat(actual.replaceAll("<$0>")).isEqualTo(expected.replaceAll("<$0>"));
    assertThat(actual.replaceAll("<$1>")).isEqualTo(expected.replaceAll("<$1>"));
  }

  @Test
  void appendReplacementAndTailUseTheUnreadMatch() {
    String text = "x aay " + "a".repeat(700);
    var expected = java.util.regex.Pattern.compile(NULLABLE).matcher(text);
    var actual = Pattern.compile(NULLABLE).matcher(text);
    StringBuilder expectedResult = new StringBuilder();
    StringBuilder actualResult = new StringBuilder();
    assertThat(expected.find()).isTrue();
    assertThat(actual.find()).isTrue();

    expected.appendReplacement(expectedResult, "<$1>").appendTail(expectedResult);
    actual.appendReplacement(actualResult, "<$1>").appendTail(actualResult);

    assertThat(actualResult.toString()).isEqualTo(expectedResult.toString());
  }

  @Test
  @Tag("work-counter")
  void displayingAnUnreadMatchLeavesTheNextFindEligibleForDeferral() {
    String text = "x aay aaay " + "a".repeat(700);
    var matcher = Pattern.compile(NULLABLE).matcher(text);
    assertThat(matcher.find()).isTrue();
    assertThat(
            WorkCounter.countForTesting(
                () -> assertThat(matcher.toString()).endsWith("lastmatch=aay]")))
        .isPositive();
    assertThat(matcher.find()).isTrue();
    assertThat(
            WorkCounter.countForTesting(
                () -> assertThat(matcher.toString()).endsWith("lastmatch=aaay]")))
        .isPositive();
  }

  @Test
  @Tag("work-counter")
  void restoringBoundsPerformsExactSelectionWithoutRepeatingTheForwardScan() {
    String text = "aay " + "x".repeat(700);
    Matcher matcher = Pattern.compile(NULLABLE).matcher(text);
    assertThat(
            WorkCounter.countForwardDfaStepsForTesting(() -> assertThat(matcher.find()).isTrue()))
        .isPositive();
    assertThat(WorkCounter.countForTesting(() -> assertThat(matcher.end()).isEqualTo(3)))
        .isPositive();

    Matcher another = Pattern.compile(NULLABLE).matcher(text);
    assertThat(another.find()).isTrue();
    assertThat(
            WorkCounter.countForwardDfaStepsForTesting(
                () -> assertThat(another.end()).isEqualTo(3)))
        .isZero();
  }

  private static int byteOffset(String input, int charOffset) {
    return charOffset < 0 ? -1 : input.substring(0, charOffset).getBytes(UTF_8).length;
  }
}
