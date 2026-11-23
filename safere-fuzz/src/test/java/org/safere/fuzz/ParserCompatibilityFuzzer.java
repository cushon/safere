// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import static org.safere.fuzz.RegexFragment.consuming;
import static org.safere.fuzz.RegexFragment.zeroWidth;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.safere.Matcher;
import org.safere.Pattern;

public final class ParserCompatibilityFuzzer {

  private static final RegexFragment[] ATOMS = {
    zeroWidth(""),
    consuming("a"),
    consuming("."),
    consuming("\\d"),
    consuming("\\D"),
    consuming("\\w"),
    consuming("\\W"),
    consuming("\\s"),
    consuming("\\S"),
    consuming("\\p{Lower}"),
    consuming("\\P{Lower}"),
    zeroWidth("\\Q\\E"),
    zeroWidth("\\Q\\E\\Q\\E"),
    consuming("\\Q*\\E"),
    zeroWidth("^"),
    zeroWidth("$"),
    zeroWidth("\\b"),
    zeroWidth("\\B"),
    zeroWidth("\\G"),
    zeroWidth("\\b{g}"),
    consuming("a\\Q\\E"),
    consuming("[a]"),
    consuming("[^a]"),
    consuming("[a-z]"),
    consuming("[a-z&&[def]]"),
    zeroWidth("()"),
    zeroWidth("(?)"),
    consuming("(a)"),
    consuming("(?:a)"),
    consuming("(?<name>a)"),
    consuming("a\u001C\\^]"),
    consuming("a\u001D\\^]"),
    consuming("a\u001E\\^]"),
    consuming("a\u001F\\^]"),
    consuming("[a\u001Cb]"),
    consuming("[a\u001Db]"),
    consuming("[a\u001Eb]"),
    consuming("[a\u001Fb]")
  };
  private static final String[] PREFIXES = {"", "^", "(?i)", "(?x)", "(?m)", "(?s)"};
  private static final String[] CONNECTORS = {
    "",
    "",
    "|",
    "?",
    "??",
    "*",
    "*?",
    "* #q\n ?",
    "+",
    "+?",
    "+ #q\n ?",
    "{0}",
    "{00}",
    "{1,3}",
    "{01,03}",
    "{1}",
    "{01}",
    "{1} #q\n ?",
    "??{1,3}",
    "??{01,03}",
    "?{1,3}",
    "?{01,03}",
    "*{1,3}",
    "*{01,03}",
    "+{1,3}",
    "+{0}?+",
    "+{0}?+{1}",
    "+{1}{0}?+?",
    "+{01,03}",
    "{1,3}{1,3}",
    "{01,03}{02}"
  };
  // These connectors can attach a possessive modifier to the first atom.
  private static final String[] ZERO_WIDTH_CONNECTORS = {
    "* #q\n +",
    "* #q\n + +",
    "+ #q\n +",
    "+ #q\n + +",
    "{2}+",
    "{0,2}+",
    "{1} #q\n +",
    "{1} #q\n + +",
    "+++",
    "++?{0,2}?"
  };
  private static final String[] SUFFIXES = {"", "$", "?", "*", "+", "{2}", "{02}", "{1,3}"};
  private static final String[] LEADING_COUNTED_REPEATS = {
    "{0}",
    "{00}",
    "{1}",
    "{01}",
    "{1,3}",
    "{01,03}",
    "{1,}",
    "{01,}",
    "{1,3}?",
    "{01,03}?",
    "{1,3}$",
    "{01,03}$",
    "^{1,3}",
    "^{01,03}",
    "a|{1,3}",
    "a|{01,03}",
    "({1,3})",
    "({01,03})"
  };
  private static final String[] COMMENT_TERMINATED_PREFIXES = {
    "a#\0", "a#\n", "a#\r", "a#\u0085", "a#\u2028", "a#\u2029"
  };
  private static final String[] MALFORMED_GROUP_SUFFIXES = {"(", "|(", "b|(", "(?:b)|("};
  private static final List<String> INPUTS =
      List.of(
          "",
          "a",
          "aa",
          "abc",
          "def",
          "0",
          " ",
          "\n",
          "*",
          "name",
          "a\u001C^]",
          "a\u001D^]",
          "a\u001E^]",
          "a\u001F^]",
          "a^]",
          "\u001C",
          "\u001D",
          "\u001E",
          "\u001F");

  @FuzzTest(maxDuration = "30s")
  void parserCompatibility(FuzzedDataProvider data) {
    fuzzerTestOneInput(data);
  }

  public static void fuzzerTestOneInput(FuzzedDataProvider data) {
    int flags = FuzzSupport.consumeParserFlags(data);
    String regex;
    if (data.consumeBoolean()) {
      flags |= Pattern.COMMENTS;
      regex =
          data.pickValue(COMMENT_TERMINATED_PREFIXES) + data.pickValue(MALFORMED_GROUP_SUFFIXES);
    } else if (data.consumeBoolean()) {
      regex = data.pickValue(PREFIXES) + data.pickValue(LEADING_COUNTED_REPEATS);
    } else {
      String prefix = data.pickValue(PREFIXES);
      RegexFragment first = data.pickValue(ATOMS);
      String connector = pickConnector(data, first);
      regex =
          compoundPattern(
              prefix, first, connector, data.pickValue(ATOMS), data.pickValue(SUFFIXES));
    }
    FuzzSupport.assertFullMatchesJdk(regex, flags, INPUTS);
    assertOperandFreePossessiveCountedRepeatMatchesJdk(data);
    assertQuantifierBoundaryFindsMatchJdk(data);
    assertCountedRepeatQuoteGapsMatchJdk(data);
    assertZeroWidthPossessiveMatchesJdk(data);
  }

  private static String pickConnector(FuzzedDataProvider data, RegexFragment first) {
    if (first.canConsume()) {
      return data.pickValue(CONNECTORS);
    }
    int index = data.consumeInt(0, CONNECTORS.length + ZERO_WIDTH_CONNECTORS.length - 1);
    return index < CONNECTORS.length
        ? CONNECTORS[index]
        : ZERO_WIDTH_CONNECTORS[index - CONNECTORS.length];
  }

  private static String compoundPattern(
      String prefix, RegexFragment first, String connector, RegexFragment second, String suffix) {
    return prefix + first.regex() + connector + second.group("(?:").regex() + suffix;
  }

  @Test
  void quotedEmptyOperandsRetainPossessiveConnectorsInCompoundPatterns() {
    RegexFragment first = zeroWidth("\\Q\\E\\Q\\E");
    RegexFragment second = consuming("\\P{Lower}");
    for (String prefix : List.of("", "^", "(?i)", "(?x)")) {
      for (String suffix : List.of("", "$", "?", "{2}")) {
        String regex = compoundPattern(prefix, first, "{1} #q\n +", second, suffix);
        var pattern = FuzzSupport.compileRequired(regex, Pattern.COMMENTS);
        for (String input : INPUTS) {
          pattern.matcher(input).matches();
        }
      }
    }
  }

  private static void assertZeroWidthPossessiveMatchesJdk(FuzzedDataProvider data) {
    RegexFragment operand =
        data.consumeBoolean()
            ? RegexFragment.zeroWidth(data.pickValue(List.of("", "^", "$", "\\b", "\\G")))
            : RegexFragment.consuming(data.pickValue(List.of("a", "[ab]", "a|b")))
                .group("(?:")
                .repeat("{0}", 0);
    String count = data.pickValue(List.of("*", "+", "?", "{2}", "{0,2}"));
    String gap = data.pickValue(List.of("", " #q\n ", " ", "\\Q\\E"));
    String regex = operand.group("(?:").regex() + count + gap + "+";
    var pattern = FuzzSupport.compileRequired(regex, Pattern.COMMENTS);
    for (String input : INPUTS) {
      pattern.matcher(input).matches();
    }
  }

  private static void assertCountedRepeatQuoteGapsMatchJdk(FuzzedDataProvider data) {
    // Generate gaps inside the token, rather than only between a quantifier and its modifier.
    String count =
        data.pickValue(List.of("{1}", "{01,03}", "{1,}", "{0}", "{12}", "{,2}", "{2,1}", "{1,2"));
    String gap = data.pickValue(List.of("\\Q\\E", "\\Q\\E\\Q\\E", " \\Q\\E#c\n", "\\Q1\\E"));
    int gapPosition = data.consumeInt(1, count.length() - 1);
    boolean allGaps = data.consumeBoolean();
    StringBuilder quotedCount = new StringBuilder("{");
    for (int i = 1; i < count.length(); i++) {
      if (allGaps || i == gapPosition) quotedCount.append(gap);
      quotedCount.append(count.charAt(i));
    }
    String operand = data.pickValue(List.of("a", "(a)", "[a]", "😀", "()", ""));
    String modifier = data.pickValue(List.of("", "?", "\\Q\\E?", "?+"));
    int flags = data.consumeBoolean() ? Pattern.COMMENTS : 0;
    String regex = operand + quotedCount + modifier;
    FuzzSupport.CompiledPattern compiled = FuzzSupport.compileCompatibleOrSkip(regex, flags);
    if (compiled == null) return;
    for (String input : List.of("", "a", "aa", "aaa", "baaaabaaa", "😀😀😀")) {
      compiled.matcher(input).matches();
      var pair = compiled.matcher(input);
      while (pair.find()) {
        // find() compares bounds and every capture on each successful step.
      }
    }
  }

  private static void assertQuantifierBoundaryFindsMatchJdk(FuzzedDataProvider data) {
    String operand = data.pickValue(List.of("a", "(a)", "(a?)", "()", "(?i)"));
    String repeat = data.pickValue(List.of("?", "*", "+", "{0}", "{1,3}"));
    String gap = data.pickValue(List.of("\\Q\\E", "\\Q\\E\\Q\\E", " \\Q\\E#c\n"));
    String modifier = data.pickValue(List.of("?", "?+", "??"));
    String regex = operand + repeat + gap + modifier;
    int flags = Pattern.COMMENTS;
    FuzzSupport.assertFullMatchesJdk(regex, flags, INPUTS);
    java.util.regex.Pattern expected;
    try {
      expected = java.util.regex.Pattern.compile(regex, flags);
    } catch (java.util.regex.PatternSyntaxException rejected) {
      return; // Compile agreement was already checked above.
    }
    Pattern actual = Pattern.compile(regex, flags);
    for (String input : INPUTS) {
      java.util.regex.Matcher jdk = expected.matcher(input);
      Matcher safe = actual.matcher(input);
      while (true) {
        boolean found = jdk.find();
        if (safe.find() != found) throw new AssertionError("find disagreement: " + regex);
        if (!found) break;
        for (int group = 0; group <= jdk.groupCount(); group++) {
          if (safe.start(group) != jdk.start(group)
              || safe.end(group) != jdk.end(group)
              || !Objects.equals(safe.group(group), jdk.group(group))) {
            throw new AssertionError("find capture disagreement: " + regex + " on " + input);
          }
        }
      }
    }
  }

  private static void assertOperandFreePossessiveCountedRepeatMatchesJdk(FuzzedDataProvider data) {
    String prefix = data.pickValue(List.of("", "\\Q\\E", "(?i)", "a*", "a*?", "a{2}", "x|"));
    String count = data.pickValue(List.of("{0}", "{1}", "{1,2}", "{1,}"));
    boolean comments = data.consumeBoolean();
    String modifier = comments && data.consumeBoolean() ? " #q\n +" : "+";
    String suffix = data.pickValue(List.of("a", "", "[ab]"));
    String expression = prefix + count + modifier + suffix;
    String regex = data.consumeBoolean() ? "(" + expression + ")" : expression;
    int flags = comments ? Pattern.COMMENTS : 0;
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex, flags);
    // These generated forms all have an implicit empty operand. Compare them directly, including
    // captures, alongside broad parser generation.
    Pattern safeRe = Pattern.compile(regex, flags);
    for (String input : List.of("", "a", "aa", "A", "ab", "x", "xa", "b")) {
      java.util.regex.Matcher expected = jdk.matcher(input);
      Matcher actual = safeRe.matcher(input);
      boolean expectedMatch = expected.matches();
      if (actual.matches() != expectedMatch) {
        throw new AssertionError("Operand-free possessive count divergence for /" + regex + "/");
      }
      if (expectedMatch) {
        for (int group = 0; group <= expected.groupCount(); group++) {
          if (!Objects.equals(actual.group(group), expected.group(group))) {
            throw new AssertionError(
                "Operand-free possessive capture divergence for /" + regex + "/");
          }
        }
      }
    }
  }
}
