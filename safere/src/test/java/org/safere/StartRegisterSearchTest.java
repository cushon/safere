// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for {@link StartRegisterSearch}, the forward find that also recovers the match start. */
@DisabledForCrosscheck("tests package-private SafeRE engine internals")
class StartRegisterSearchTest {
  private static final EnginePathOptions WITHOUT_START_REGISTER =
      EnginePathOptions.builder().startRegister(false).build();

  /**
   * Minimum share of the differential tests' direct searches that answer rather than fall back
   * (measured: 66% curated, 34% random).
   */
  private static final double CURATED_ANSWERED_FLOOR = 0.6;

  private static final double RANDOM_ANSWERED_FLOOR = 0.3;

  private static final String PROSE =
      "The quick brown fox jumps over the lazy dog. A stitch in time saves nine; "
          + "naïve café owners résumé-writing at 9:30, don't they? __init__ x_1 Ünïcödé ok. ";

  private static final String[] PATTERNS = {
    "\\b\\w+\\b",
    "\\w+",
    "[a-z]+ing\\b",
    "\\b[a-z]+\\b",
    "[A-Z][a-z]+",
    "(?:the|he) ",
    "[a-z]{3}",
    "\\w{2,5}\\b",
    "(?s)<b>.*?</b>",
    "<<tag.*?>>",
    "(?:ab|a)+c",
    "a+?b",
    "\\d+[.:]\\d+",
    "[^ ]+",
    "\\p{L}+",
    "é+|ü",
    "\\B\\w\\B",
    "x*",
    "[a-c]|b+",
  };

  private static final String[] INPUTS = {
    PROSE.repeat(4),
    "abc abcd abcde abcdef ab a abcdefghijkl",
    "<b>one</b> <b>two</b>x<b></b> <<tag a>> <<tag>> <tag>>",
    "aab ab acab abababc aaaa b",
    "",
    "é",
    "\uD83D\uDE00a\uD83D\uDE00 bb\uD83D\uDE00",
  };

  @Test
  void curatedPatternsAgreeWithTheReverseDfa() {
    Tally tally = new Tally();
    for (String regex : PATTERNS) {
      for (int flags : new int[] {0, Pattern.UNICODE_CHARACTER_CLASS}) {
        for (String input : INPUTS) {
          assertAgrees(regex, flags, input, tally);
        }
      }
    }
    // Agreement alone would pass if every search fell back.
    assertThat(tally.answeredFraction()).isGreaterThan(CURATED_ANSWERED_FLOOR);
  }

  /**
   * A seeded random differential against the forward and reverse DFA. The probe used to develop the
   * search runs the same generator over 3,000 patterns; this keeps a smaller slice in the suite.
   */
  @Test
  void randomPatternsAgreeWithTheReverseDfa() {
    Random random = new Random(73);
    String[] atoms = {
      "a",
      "b",
      "\\w",
      "\\W",
      "\\b",
      "\\B",
      "[a-c]",
      ".",
      "x",
      " ",
      "\\d",
      "é",
      "(?:ab|a)",
      "[^ ]",
      "\\s",
      // Alternatives whose priority differs from their length, and nested groups.
      "(?:a|ab)",
      "(?:c|bcd)",
      "(?:x|\\b)a",
      "(?:ab|a)(?:ba|b)",
      "(?:a(?:b|bc)?)",
      "a{1,3}b",
      // Case folding, including the Kelvin sign that folds to k.
      "(?i)k",
      "\\u212A"
    };
    String[] quantifiers = {"", "", "+", "*", "?", "{1,3}", "+?", "*?", "{2}"};
    String[] alphabet = {
      "a",
      "b",
      "c",
      "d",
      "x",
      " ",
      "1",
      "é",
      "\uD83D\uDE00",
      "-",
      "ab",
      "\n",
      "k",
      "K",
      "\u212A",
      "\uD800"
    };
    Tally tally = new Tally();
    for (int i = 0; i < 400; i++) {
      StringBuilder regex = new StringBuilder();
      int atomCount = 1 + random.nextInt(4);
      for (int k = 0; k < atomCount; k++) {
        regex.append(atoms[random.nextInt(atoms.length)]);
        regex.append(quantifiers[random.nextInt(quantifiers.length)]);
        if (random.nextInt(6) == 0) {
          regex.append('|');
        }
      }
      if (regex.charAt(regex.length() - 1) == '|') {
        regex.append('x');
      }
      StringBuilder input = new StringBuilder();
      int length = random.nextInt(random.nextInt(5) == 0 ? 400 : 60);
      for (int k = 0; k < length; k++) {
        input.append(alphabet[random.nextInt(alphabet.length)]);
      }
      int flags = random.nextInt(4) == 0 ? Pattern.UNICODE_CHARACTER_CLASS : 0;
      assertAgrees(regex.toString(), flags, input.toString(), tally);
    }
    assertThat(tally.answeredFraction()).isGreaterThan(RANDOM_ANSWERED_FLOOR);
  }

  @Test
  void wordsNeedNoRegisterForTheYoungestAttempt() {
    // Inside a word the DFA keeps a younger attempt waiting on its leading \b. That attempt started
    // where the state was entered, so it needs no register, and every search succeeds.
    Pattern pattern = Pattern.compile("\\b\\w+\\b");
    StartRegisterSearch search = pattern.startRegisterSearch(new Pattern.ThreadCache());
    InputScanner text = new StringInputScanner(PROSE);
    int from = 0;
    int matches = 0;
    while (true) {
      long result = search.searchForTesting(text, from);
      if (result == StartRegisterSearch.NO_MATCH) {
        break;
      }
      assertThat(result).isNotEqualTo(StartRegisterSearch.FALLBACK);
      from = (int) result;
      matches++;
    }
    assertThat(matches).isEqualTo(count(Pattern.compile("\\b\\w+\\b", 0, WITHOUT_START_REGISTER)));
    // A handful of states: the forward DFA's, plus one or two for the attempt labels.
    assertThat(search.stateCount()).isLessThanOrEqualTo(16);
  }

  @Test
  void longStretchWithoutMatchHandsOver() {
    String input = "word ".repeat(400) + "running";
    // Without a start accelerator, so that the search walks the words instead of skipping them.
    StartRegisterSearch search =
        StartRegisterSearch.create(Pattern.compile("\\b\\w+ing\\b").flatDfaProg(), null, null);
    long result = search.searchForTesting(new StringInputScanner(input), 0);
    assertThat(result).isLessThanOrEqualTo(StartRegisterSearch.HANDOFF);
    int handoff = StartRegisterSearch.handoffPosition(result);
    assertThat(handoff).isGreaterThan(StartRegisterSearch.HANDOFF_DISTANCE);
    assertThat(handoff).isLessThanOrEqualTo(input.indexOf("running"));

    Matcher matcher = Pattern.compile("\\b\\w+ing\\b").matcher(input);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(input.indexOf("running"));
    assertThat(matcher.find()).isFalse();
  }

  @Test
  void matchOfAnAttemptWithoutRegisterFallsBack() {
    // The match "cdefg" belongs to the third live attempt.
    StartRegisterSearch search = search("\\w{2,5}\\b");
    assertThat(search.searchForTesting(new StringInputScanner("abcdefg"), 0))
        .isEqualTo(StartRegisterSearch.FALLBACK);
  }

  @Test
  void emptyMatchFallsBack() {
    StartRegisterSearch search = search("x*");
    assertThat(search.searchForTesting(new StringInputScanner("abc"), 0))
        .isEqualTo(StartRegisterSearch.FALLBACK);
  }

  @Test
  void startInsideSurrogatePairFallsBack() {
    StartRegisterSearch search = search("a+");
    assertThat(search.searchForTesting(new StringInputScanner("\uD83D\uDE00aa"), 1))
        .isEqualTo(StartRegisterSearch.FALLBACK);
    assertThat(search.searchForTesting(new StringInputScanner("\uD83D\uDE00aa"), 2))
        .isEqualTo((2L << 32) | 4);
  }

  @Test
  void nonAsciiTransitionsAreCached() {
    StartRegisterSearch search = search("\\p{L}+");
    InputScanner text = new StringInputScanner("ü".repeat(1000) + " x");
    assertThat(search.searchForTesting(text, 0)).isEqualTo(1000L);
    int states = search.stateCount();
    assertThat(search.searchForTesting(text, 0)).isEqualTo(1000L);
    assertThat(search.stateCount()).isEqualTo(states);
  }

  @Test
  void escapeAcceleratedStatesSkipToTheNextEscape() {
    StartRegisterSearch search = search("(?s)<b>.*?</b>");
    String filler = "content é ".repeat(2000);
    String input = "x<b>" + filler + "</b>y";
    assertThat(search.searchForTesting(new StringInputScanner(input), 0))
        .isEqualTo((1L << 32) | (input.length() - 1));
    byte[] bytes = input.getBytes(UTF_8);
    assertThat(search.searchForTesting(new Utf8InputScanner(bytes), 0))
        .isEqualTo((1L << 32) | (bytes.length - 1));
  }

  @Test
  void frequentFallbacksPauseTheSearch() {
    StartRegisterSearch search = search("\\w{2,5}\\b");
    InputScanner text = new StringInputScanner("abcdefg");
    int fallbacks = 0;
    while (search.find(text, 0, AdaptiveBackoff.NEUTRAL) == StartRegisterSearch.FALLBACK) {
      fallbacks++;
    }
    assertThat(fallbacks)
        .isEqualTo(StartRegisterSearch.INITIAL_CREDIT / StartRegisterSearch.FALLBACK_COST);
    // Paused: the loop above already saw the first skip; the rest of the interval skips too.
    for (int i = 1; i < StartRegisterSearch.RETRY_INTERVAL; i++) {
      assertThat(search.find(text, 0, AdaptiveBackoff.NEUTRAL))
          .isEqualTo(StartRegisterSearch.SKIPPED);
    }
    // Then it gets two more tries.
    assertThat(search.find(text, 0, AdaptiveBackoff.NEUTRAL))
        .isEqualTo(StartRegisterSearch.FALLBACK);
    assertThat(search.find(text, 0, AdaptiveBackoff.NEUTRAL))
        .isEqualTo(StartRegisterSearch.FALLBACK);
    assertThat(search.find(text, 0, AdaptiveBackoff.NEUTRAL))
        .isEqualTo(StartRegisterSearch.SKIPPED);
  }

  @Test
  void successfulSearchesEarnCreditBack() {
    StartRegisterSearch search = search("\\w{2,5}\\b");
    InputScanner miss = new StringInputScanner("abcdefg");
    InputScanner hit = new StringInputScanner("ab cd");
    for (int i = 0; i < 4; i++) {
      assertThat(search.find(miss, 0, AdaptiveBackoff.NEUTRAL))
          .isEqualTo(StartRegisterSearch.FALLBACK);
    }
    int credit = search.creditForTesting();
    for (int i = 0; i < 100; i++) {
      assertThat(search.find(hit, 0, AdaptiveBackoff.NEUTRAL)).isEqualTo(2L);
    }
    assertThat(search.creditForTesting()).isEqualTo(StartRegisterSearch.MAX_CREDIT);
    assertThat(credit).isLessThan(StartRegisterSearch.MAX_CREDIT);
  }

  @Test
  void growingWideRowsRespectsTheTransitionBudget() {
    StringBuilder members = new StringBuilder();
    for (int i = 0; i < 1450; i++) {
      members.appendCodePoint(0x100 + i * 2);
    }
    String input = "z" + "a".repeat(1100);
    Pattern pattern = Pattern.compile(input + "|q[" + members + "]");
    StartRegisterSearch search = StartRegisterSearch.create(pattern.flatDfaProg(), null, null);

    assertThat(search.searchForTesting(new StringInputScanner(input), 0)).isEqualTo(input.length());
    assertThat(search.transitionCapacityForTesting()).isLessThanOrEqualTo(1 << 22);
  }

  @Test
  void stateBudgetResetsTheCacheAndEventuallyStops() {
    // Unanchored [ab]*a[ab]{13} needs a state per window of the last 14 characters.
    StartRegisterSearch search =
        StartRegisterSearch.create(Pattern.compile("a[ab]{13}c").flatDfaProg(), null, null);
    StringBuilder input = new StringBuilder();
    Random random = new Random(1);
    for (int i = 0; i < 200_000; i++) {
      input.append(random.nextBoolean() ? 'a' : 'b');
    }
    InputScanner text = new StringInputScanner(input.toString());
    assertThat(search.find(text, 0, AdaptiveBackoff.NEUTRAL))
        .isEqualTo(StartRegisterSearch.FALLBACK);
    assertThat(search.stateCount()).isEqualTo(search.maxStatesForTesting());
    for (int reset = 1; reset <= StartRegisterSearch.MAX_RESETS; reset++) {
      assertThat(search.find(text, 0, AdaptiveBackoff.NEUTRAL))
          .isEqualTo(StartRegisterSearch.FALLBACK);
      assertThat(search.resetsForTesting()).isEqualTo(reset);
    }
    // This find frees the cache and skips, and so do the next RESET_PAUSE finds.
    assertThat(search.find(text, 0, AdaptiveBackoff.NEUTRAL))
        .isEqualTo(StartRegisterSearch.SKIPPED);
    assertThat(search.stateCount()).isZero();
    assertThat(search.resetsForTesting()).isZero();
    for (int i = 0; i < StartRegisterSearch.RESET_PAUSE; i++) {
      assertThat(search.find(text, 0, AdaptiveBackoff.NEUTRAL))
          .isEqualTo(StartRegisterSearch.SKIPPED);
    }
    assertThat(search.find(text, 0, AdaptiveBackoff.NEUTRAL))
        .isEqualTo(StartRegisterSearch.FALLBACK);
  }

  @Test
  void answeredSearchesForgiveResets() {
    StartRegisterSearch search =
        StartRegisterSearch.create(Pattern.compile("a[ab]{13}c").flatDfaProg(), null, null);
    StringBuilder input = new StringBuilder();
    Random random = new Random(1);
    for (int i = 0; i < 200_000; i++) {
      input.append(random.nextBoolean() ? 'a' : 'b');
    }
    assertThat(search.find(new StringInputScanner(input.toString()), 0, AdaptiveBackoff.NEUTRAL))
        .isEqualTo(StartRegisterSearch.FALLBACK);
    InputScanner small = new StringInputScanner("xc");
    assertThat(search.find(small, 0, AdaptiveBackoff.NEUTRAL))
        .isEqualTo(StartRegisterSearch.NO_MATCH);
    assertThat(search.resetsForTesting()).isEqualTo(1);
    for (int i = 1; i < StartRegisterSearch.RESET_DECAY; i++) {
      assertThat(search.find(small, 0, AdaptiveBackoff.NEUTRAL))
          .isEqualTo(StartRegisterSearch.NO_MATCH);
    }
    assertThat(search.resetsForTesting()).isZero();
  }

  @Test
  void unicodeWordBoundaryUsesTheWordnessOfNonAsciiInput() {
    // The € and Ü columns differ only in Unicode word-ness: \b holds after "foo" before €, not Ü.
    String input = "foo€ fooÜ";
    Pattern pattern = Pattern.compile("(?U)\\bfoo\\b");
    StartRegisterSearch search = search("(?U)\\bfoo\\b");
    assertThat(search.searchForTesting(new StringInputScanner(input), 0)).isEqualTo(3L);
    assertThat(search.searchForTesting(new StringInputScanner(input), 1))
        .isEqualTo(StartRegisterSearch.NO_MATCH);
    assertThat(matches(pattern.matcher(input))).containsExactly(3L);
    assertAgrees("(?U)\\bfoo\\b", 0, input);
  }

  @Test
  void nonAsciiInputLeavesAnEscapeAcceleratedState() {
    // [^ ]+ loops on ASCII with ' ' as the only escape, but é also starts the match of é.
    StartRegisterSearch search = search("[^ ]+é");
    assertThat(search.searchForTesting(new StringInputScanner("abé"), 0)).isEqualTo(3L);
    byte[] bytes = "abé".getBytes(UTF_8);
    assertThat(search.searchForTesting(new Utf8InputScanner(bytes), 0)).isEqualTo(4L);
    assertAgrees("[^ ]+é", 0, "abé");
    assertAgrees("[^ ]+é", 0, "abé xé ééé é");
  }

  @Test
  void unmodeledInstructionsFailClosed() {
    // Loop bodies that can match empty get a progress check, which the automaton doesn't model.
    // The flattened DFA program the Pattern passes in has none, so it is accepted.
    Pattern pattern = Pattern.compile("(?:a|b?)+c");
    Prog prog = pattern.prog();
    boolean hasProgressCheck = false;
    for (int i = 0; i < prog.size(); i++) {
      hasProgressCheck |= prog.inst(i).opCode == InstOp.OP_PROGRESS_CHECK;
    }
    assertThat(hasProgressCheck).isTrue();
    assertThat(StartRegisterSearch.create(prog, null, null)).isNull();
    assertThat(StartRegisterSearch.create(pattern.flatDfaProg(), null, null)).isNotNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"(a)b", "^ab", "ab$", "(?m)^ab", "a\\z", "ab\\Z", "\\Aab"})
  void unsupportedPatternsUseTheReverseDfa(String regex) {
    assertThat(Pattern.compile(regex).startRegisterSearch(new Pattern.ThreadCache())).isNull();
  }

  /**
   * Inner-literal narrowing (see {@code InnerLiteralActivationWorkTest}) lives in the forward DFA,
   * so patterns that have it keep the forward and reverse DFA.
   */
  @ParameterizedTest
  @ValueSource(strings = {"<[^>]*>", "<<tag.*?>>", "record:[^\\r\\n;]*val=200"})
  void innerLiteralPatternsKeepTheForwardDfa(String regex) {
    assertThat(Pattern.compile(regex).startRegisterSearch(new Pattern.ThreadCache())).isNull();
  }

  @Test
  void usePatternAndResetKeepFindsCorrect() {
    Matcher matcher = Pattern.compile("[a-z]+").matcher("abc 123 def 456");
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group()).isEqualTo("abc");
    matcher.usePattern(Pattern.compile("[0-9]+"));
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group()).isEqualTo("123");
    matcher.reset("zz 789");
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group()).isEqualTo("789");
    matcher.reset();
    matcher.region(4, 6);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group()).isEqualTo("89");
  }

  private static StartRegisterSearch search(String regex) {
    StartRegisterSearch search =
        Pattern.compile(regex).startRegisterSearch(new Pattern.ThreadCache());
    assertThat(search).as(regex).isNotNull();
    return search;
  }

  private static int count(Pattern pattern) {
    Matcher matcher = pattern.matcher(PROSE);
    int count = 0;
    while (matcher.find()) {
      count++;
    }
    return count;
  }

  private static void assertAgrees(String regex, int flags, String input) {
    assertAgrees(regex, flags, input, new Tally());
  }

  /** Direct searches run and answered (not fallen back) by {@link #assertSearchAgrees}. */
  private static final class Tally {
    int searches;
    int answered;

    double answeredFraction() {
      return (double) answered / searches;
    }
  }

  /**
   * Checks the search against find() without it, both directly and through the public API, for
   * String and UTF-8 input.
   */
  private static void assertAgrees(String regex, int flags, String input, Tally tally) {
    Pattern pattern;
    try {
      pattern = Pattern.compile(regex, flags);
    } catch (PatternSyntaxException e) {
      return;
    }
    Pattern reference = Pattern.compile(regex, flags, WITHOUT_START_REGISTER);
    byte[] bytes = input.getBytes(UTF_8);
    Utf8Input utf8 = Utf8Input.validated(bytes);

    List<Long> expected = matches(reference.matcher(input));
    assertThat(matches(pattern.matcher(input))).as("%s on %s", regex, input).isEqualTo(expected);
    List<Long> expectedUtf8 = matches(reference.matcher(utf8));
    assertThat(matches(pattern.matcher(utf8)))
        .as("%s on UTF-8 %s", regex, input)
        .isEqualTo(expectedUtf8);

    StartRegisterSearch search = pattern.startRegisterSearch(new Pattern.ThreadCache());
    if (search != null) {
      assertSearchAgrees(regex, search, new StringInputScanner(input), expected, false, tally);
      StartRegisterSearch utf8Search = pattern.startRegisterSearch(new Pattern.ThreadCache());
      assertSearchAgrees(regex, utf8Search, new Utf8InputScanner(bytes), expectedUtf8, true, tally);
    }
  }

  /** Replays a find() loop with the search, which may fall back but must not disagree. */
  private static void assertSearchAgrees(
      String regex,
      StartRegisterSearch search,
      InputScanner text,
      List<Long> expected,
      boolean utf8,
      Tally tally) {
    int from = 0;
    for (long match : expected) {
      long result = search.searchForTesting(text, from);
      tally.searches++;
      int start = (int) (match >>> 32);
      if (result <= StartRegisterSearch.HANDOFF) {
        // No match may start before the handoff position.
        assertThat(StartRegisterSearch.handoffPosition(result))
            .as("%s from %d", regex, from)
            .isBetween(from, start);
        tally.answered++;
      } else if (result != StartRegisterSearch.FALLBACK) {
        assertThat(result).as("%s from %d", regex, from).isEqualTo(match);
        tally.answered++;
      }
      int end = (int) match;
      // Matcher advances one UTF-16 unit on String input and one code point on UTF-8 input.
      from = end > start ? end : utf8 ? InputScanner.position(text.decodeForward(end)) : end + 1;
      if (from > text.length()) {
        return;
      }
    }
    long result = search.searchForTesting(text, from);
    tally.searches++;
    if (result <= StartRegisterSearch.HANDOFF) {
      assertThat(StartRegisterSearch.handoffPosition(result))
          .as("%s from %d", regex, from)
          .isBetween(from, text.length());
      tally.answered++;
    } else if (result != StartRegisterSearch.FALLBACK) {
      assertThat(result).as("%s from %d", regex, from).isEqualTo(StartRegisterSearch.NO_MATCH);
      tally.answered++;
    }
  }

  private static List<Long> matches(Matcher matcher) {
    List<Long> matches = new ArrayList<>();
    while (matcher.find()) {
      matches.add(((long) matcher.start() << 32) | matcher.end());
    }
    return matches;
  }

  private static List<Long> matches(Utf8Matcher matcher) {
    List<Long> matches = new ArrayList<>();
    while (matcher.find()) {
      matches.add(((long) matcher.start() << 32) | matcher.end());
    }
    return matches;
  }
}
