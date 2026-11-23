// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Work-bound tests for find() with the start-register search. */
@DisabledForCrosscheck("WorkCounter and the UTF-8 input API are SafeRE-specific")
@Tag("work-counter")
class StartRegisterWorkTest {
  private static final String PROSE =
      "The quick brown fox jumps over the lazy dog, and a stitch in time saves nine. ".repeat(256);

  @Test
  void countsNonAsciiForwardDfaSteps() {
    Pattern pattern = Pattern.compile("\\p{L}+");
    StartRegisterSearch search = StartRegisterSearch.create(pattern.flatDfaProg(), null, null);
    String input = "ü".repeat(96);
    InputScanner scanner = new StringInputScanner(input);

    long steps =
        WorkCounter.countForwardDfaStepsForTesting(
            () -> assertThat(search.searchForTesting(scanner, 0)).isEqualTo(input.length()));

    assertThat(steps).isEqualTo(input.length());
  }

  @Test
  void denseStringFindAllScansTheInputOnce() {
    assertOnePass("\\b\\w+\\b", false);
    assertOnePass("\\b[a-z]+\\b", false);
  }

  @Test
  void denseUtf8FindAllScansTheInputOnce() {
    assertOnePass("\\b\\w+\\b", true);
    assertOnePass("\\b[a-z]+\\b", true);
  }

  @Test
  void longStretchesWithoutMatchesHandOverToTheForwardDfa() {
    // Every line has the inner literal but no match; without the handoff the search would scan
    // every line where the forward DFA skips most of them.
    String input =
        """
        2026-11-07T12:34:56.789 INFO request handled path=/api/v1/items error: -
        2026-11-07T12:34:56.789 INFO request handled path=/api/v1/items/12345
        """
            .repeat(1024);
    for (String regex : new String[] {"[^\\n]*error: \\w+", "\\b\\w+ing\\b"}) {
      Pattern pattern = Pattern.compile(regex);
      Pattern reference =
          Pattern.compile(regex, 0, EnginePathOptions.builder().startRegister(false).build());
      long with = WorkCounter.countWithForwardDfaStepsForTesting(() -> findAll(pattern, input));
      long without =
          WorkCounter.countWithForwardDfaStepsForTesting(() -> findAll(reference, input));
      assertThat(with)
          .as("%s work with the start register", regex)
          .isLessThanOrEqualTo(without + StartRegisterSearch.HANDOFF_DISTANCE + 64);
    }
  }

  @Test
  void fallbackHeavyPatternStopsUsingTheSearch() {
    // Every match of \w{2,5}\b on long words belongs to an attempt without a register, so the
    // search falls back; after a few fallbacks it pauses and find() costs what it costs without it.
    String input = "abcdefg ".repeat(2048);
    Pattern pattern = Pattern.compile("\\w{2,5}\\b");
    Pattern reference =
        Pattern.compile("\\w{2,5}\\b", 0, EnginePathOptions.builder().startRegister(false).build());
    long with = WorkCounter.countWithForwardDfaStepsForTesting(() -> findAll(pattern, input));
    long without = WorkCounter.countWithForwardDfaStepsForTesting(() -> findAll(reference, input));
    assertThat(with).isLessThanOrEqualTo(without + without / 10);
  }

  @Test
  void coldRankLookupsGrowWithTheNumberOfLiveThreads() {
    long smaller = rankLookups(64);
    long larger = rankLookups(128);
    // Doubling the run doubles both the number of transitions and the widest frontier.
    assertThat(smaller).isPositive();
    assertThat(larger).isLessThanOrEqualTo(5 * smaller);
  }

  private static long rankLookups(int width) {
    String regex = "[ab]{" + width + "}c";
    return WorkCounter.countStartRegisterRankLookupsForTesting(
        coldSearch(regex, "a".repeat(width) + "c"));
  }

  @Test
  void repeatedBoundaryExpansionReusesItsStacks() {
    int width = 64;
    String regex = "(?:[ab]\\B){" + width + "}c";
    long slots =
        WorkCounter.countStartRegisterStackSlotsForTesting(
            coldSearch(regex, "a".repeat(width) + "c"));

    assertThat(slots).isLessThanOrEqualTo(8L * width);
  }

  private static Runnable coldSearch(String regex, String input) {
    Pattern pattern = Pattern.compile(regex);
    StartRegisterSearch search = StartRegisterSearch.create(pattern.flatDfaProg(), null, null);
    InputScanner scanner = new StringInputScanner(input);
    return () -> assertThat(search.searchForTesting(scanner, 0)).isEqualTo(input.length());
  }

  private static void assertOnePass(String regex, boolean utf8) {
    Pattern pattern = Pattern.compile(regex);
    Pattern reference =
        Pattern.compile(regex, 0, EnginePathOptions.builder().startRegister(false).build());
    Runnable withRegister = () -> findAll(pattern, PROSE, utf8);
    Runnable withoutRegister = () -> findAll(reference, PROSE, utf8);
    long with = WorkCounter.countWithForwardDfaStepsForTesting(withRegister);
    long without = WorkCounter.countWithForwardDfaStepsForTesting(withoutRegister);
    assertThat(with).as("%s work with the start register", regex).isLessThan(without);
    // One pass: a forward step per character, plus the scanner's own units for UTF-8 input.
    assertThat(with)
        .as("%s work with the start register", regex)
        .isLessThanOrEqualTo((utf8 ? 4L : 2L) * PROSE.length());
  }

  private static int findAll(Pattern pattern, String input) {
    return findAll(pattern, input, false);
  }

  private static int findAll(Pattern pattern, String input, boolean utf8) {
    int count = 0;
    if (utf8) {
      Utf8Matcher matcher = pattern.matcher(Utf8Input.validated(input.getBytes(UTF_8)));
      while (matcher.find()) {
        count++;
      }
    } else {
      Matcher matcher = pattern.matcher(input);
      while (matcher.find()) {
        count++;
      }
    }
    return count;
  }
}
