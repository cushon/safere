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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class WuManberTest {

  @Test
  @DisplayName("WuManberModel compiles and matches across 40 dictionary keywords")
  void compilesAndMatchesAcrossKeywords() {
    List<String> keywords = new ArrayList<>();
    for (int i = 10; i < 50; i++) {
      keywords.add("keyword" + i);
    }
    String[] dict = keywords.toArray(new String[0]);
    WuManberModel model = WuManberModel.compile(dict);

    assertThat(model).isNotNull();
    assertThat(model.minLength()).isEqualTo(9);

    String text = "The target is keyword37 in this sentence.";
    int matchPos = model.findCandidate(text, 0);
    assertThat(matchPos).isEqualTo(14);

    Utf8InputScanner scanner = new Utf8InputScanner(text.getBytes(UTF_8));
    int utf8MatchPos = model.findCandidate(scanner, 0);
    assertThat(utf8MatchPos).isEqualTo(14);
  }

  @Test
  @DisplayName("WuManberModel returns -1 on non-matching text")
  void returnsNegativeOneOnNonMatch() {
    List<String> keywords = new ArrayList<>();
    for (int i = 10; i < 50; i++) {
      keywords.add("target" + i);
    }
    WuManberModel model = WuManberModel.compile(keywords.toArray(new String[0]));
    assertThat(model).isNotNull();

    String text = "none of the keywords exist in this long text payload whatsoever";
    assertThat(model.findCandidate(text, 0)).isEqualTo(-1);

    Utf8InputScanner scanner = new Utf8InputScanner(text.getBytes(UTF_8));
    assertThat(model.findCandidate(scanner, 0)).isEqualTo(-1);
  }

  @Test
  @DisplayName("WuManber preserves leftmost-first matching for overlapping alternatives")
  void preservesLeftmostFirstAlternationOrder() {
    String[] dict = {"application", "apple", "applet", "appl"};
    // Minimum length is 4 ("appl")
    WuManberModel model = WuManberModel.compile(dict);
    assertThat(model).isNotNull();
    assertThat(model.minLength()).isEqualTo(4);

    String text = "eating an apple a day";
    // "apple" starts at index 10. "application", "apple", "applet", "appl"
    // "apple" comes before "appl" in the dictionary
    int matchPos = model.findCandidate(text, 0);
    assertThat(matchPos).isEqualTo(10);

    Utf8InputScanner scanner = new Utf8InputScanner(text.getBytes(UTF_8));
    assertThat(model.findCandidate(scanner, 0)).isEqualTo(10);
  }

  @Test
  @DisplayName("WuManber rejects keywords shorter than 4 characters to favor CharClass")
  void rejectsKeywordsShorterThanFour() {
    String[] dict = {"SELECT", "FROM", "WHERE", "AS", "BY"};
    assertThat(WuManberModel.compile(dict)).isNull();
  }

  @Test
  @DisplayName("Pattern with 40-keyword alternation uses WuManber start accelerator")
  void patternWithAlternationUsesWuManber() {
    StringBuilder regex = new StringBuilder();
    for (int i = 10; i < 50; i++) {
      if (i > 10) {
        regex.append("|");
      }
      regex.append("token").append(i);
    }

    Pattern p = Pattern.compile(regex.toString());
    assertThat(p.startDescriptor().wuManberModel()).isNotNull();
    assertThat(p.stringStartAccelerator()).isInstanceOf(StringStartAccelerator.WuManber.class);

    String haystack = "prefix padding before token33 and some trailing text";
    Matcher m = p.matcher(haystack);
    assertThat(m.find()).isTrue();
    assertThat(m.start()).isEqualTo(22);
    assertThat(m.group()).isEqualTo("token33");

    Utf8Matcher mUtf8 = p.matcher(Utf8Input.trusted(haystack.getBytes(UTF_8)));
    assertThat(mUtf8.find()).isTrue();
    assertThat(mUtf8.start()).isEqualTo(22);
    assertThat(mUtf8.end()).isEqualTo(22 + "token33".length());
  }

  @Test
  @DisplayName("Cross-engine equivalence against JDK for 100-keyword dictionary")
  void crossEngineEquivalenceAgainstJdk() {
    List<String> keywords = new ArrayList<>();
    Random rnd = new Random(42);
    for (int i = 0; i < 100; i++) {
      StringBuilder kw = new StringBuilder();
      int len = 4 + rnd.nextInt(8);
      for (int c = 0; c < len; c++) {
        kw.append((char) ('a' + rnd.nextInt(26)));
      }
      keywords.add(kw.toString());
    }

    String regex = String.join("|", keywords);
    Pattern safeRePattern = Pattern.compile(regex);
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(regex);

    // Test with 50 randomized haystacks
    for (int trial = 0; trial < 50; trial++) {
      StringBuilder text = new StringBuilder();
      int textLen = 100 + rnd.nextInt(500);
      for (int i = 0; i < textLen; i++) {
        text.append((char) ('a' + rnd.nextInt(26)));
      }
      // Insert one random keyword at random position in 50% of trials
      if (rnd.nextBoolean()) {
        String kw = keywords.get(rnd.nextInt(keywords.size()));
        int insertPos = rnd.nextInt(text.length());
        text.insert(insertPos, kw);
      }

      String haystack = text.toString();
      Matcher safeMatcher = safeRePattern.matcher(haystack);
      java.util.regex.Matcher jdkMatcher = jdkPattern.matcher(haystack);

      boolean safeFound = safeMatcher.find();
      boolean jdkFound = jdkMatcher.find();

      assertThat(safeFound).as("Match status mismatch on trial %d", trial).isEqualTo(jdkFound);

      if (safeFound) {
        assertThat(safeMatcher.start())
            .as("Match start mismatch on trial %d", trial)
            .isEqualTo(jdkMatcher.start());
        assertThat(safeMatcher.end())
            .as("Match end mismatch on trial %d", trial)
            .isEqualTo(jdkMatcher.end());
      }

      // Also test Utf8Input
      Utf8Matcher safeUtf8Matcher =
          safeRePattern.matcher(Utf8Input.trusted(haystack.getBytes(UTF_8)));
      boolean safeUtf8Found = safeUtf8Matcher.find();
      assertThat(safeUtf8Found).isEqualTo(jdkFound);
      if (safeUtf8Found) {
        assertThat(safeUtf8Matcher.start()).isEqualTo(jdkMatcher.start());
        assertThat(safeUtf8Matcher.end()).isEqualTo(jdkMatcher.end());
      }
    }
  }

  @Test
  @DisplayName("Handles non-ASCII and edge boundary haystacks cleanly")
  void handlesNonAsciiAndBoundaries() {
    String[] dict = {"alpha", "bravo", "charlie", "delta", "echo"};
    WuManberModel model = WuManberModel.compile(dict);
    assertThat(model).isNotNull();

    // Text containing multibyte characters
    String text = "hello \u00E9\u00E8\u00EA world bravo \uD83D\uDE00 trailing";
    int pos = model.findCandidate(text, 0);
    assertThat(pos).isEqualTo(text.indexOf("bravo"));

    Utf8InputScanner scanner = new Utf8InputScanner(text.getBytes(UTF_8));
    int utf8Pos = model.findCandidate(scanner, 0);
    assertThat(utf8Pos)
        .isEqualTo(
            text.getBytes(UTF_8).length - "bravo \uD83D\uDE00 trailing".getBytes(UTF_8).length);

    // Empty text
    assertThat(model.findCandidate("", 0)).isEqualTo(-1);
    assertThat(model.findCandidate(new Utf8InputScanner(new byte[0]), 0)).isEqualTo(-1);

    // Text shorter than minLength
    assertThat(model.findCandidate("ab", 0)).isEqualTo(-1);
    assertThat(model.findCandidate(new Utf8InputScanner("ab".getBytes(UTF_8)), 0)).isEqualTo(-1);
  }

  @Test
  @DisplayName("Mixed-length candidates at the input end stay within UTF-8 bounds")
  void mixedLengthCandidatesAtInputEndStayWithinUtf8Bounds() {
    WuManberModel model = WuManberModel.compile(new String[] {"abcd", "abcdEFGH", "zzzz", "yyyy"});
    String text = "xxabcd";

    assertThat(model.findCandidate(text, 0)).isEqualTo(2);
    assertThat(model.findCandidate(new Utf8InputScanner(text.getBytes(UTF_8)), 0)).isEqualTo(2);
  }

  @Test
  @DisplayName("Collision-budget fallback participates in adaptive accelerator defeat")
  void collisionBudgetFallbackParticipatesInAdaptiveAcceleratorDefeat() {
    assertThat(AcceleratorPolicy.WU_MANBER.isExactMatchCandidate()).isFalse();
  }

  @Test
  @DisplayName("ASCII matches immediately after non-ASCII text are not skipped")
  void asciiMatchesImmediatelyAfterNonAsciiTextAreNotSkipped() {
    WuManberModel model = WuManberModel.compile(new String[] {"abcd", "wxyz", "lmno", "pqrs"});

    for (String prefix : List.of("\u00e9", "a\u00e9", "ab\u00e9", "abc\u00e9", "\ud83d\ude00")) {
      String text = prefix + "abcd";
      assertThat(model.findCandidate(text, 0))
          .as("String prefix %s", prefix)
          .isEqualTo(prefix.length());

      byte[] utf8 = text.getBytes(UTF_8);
      assertThat(model.findCandidate(new Utf8InputScanner(utf8), 0))
          .as("UTF-8 prefix %s", prefix)
          .isEqualTo(prefix.getBytes(UTF_8).length);
    }
  }
}
