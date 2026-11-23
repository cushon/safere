// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.List;
import org.junit.jupiter.api.Test;

public final class ReplacementFuzzer {
  private static final List<String> PARTIAL_OUTPUT_PATTERNS =
      List.of("(?<part>x)", "(x)(?<part>y)?");
  private static final List<String> PARTIAL_OUTPUT_PREFIXES =
      List.of("", "lit", "$0", "$1", "${part}", "lit$1");
  private static final List<String> MALFORMED_REPLACEMENT_SUFFIXES =
      List.of("\\", "$", "$9", "$x", "${", "${}", "${nope}", "${part");

  @Test
  void terminalEmptyReplacementState() {
    // Exercise MatcherPair's state exclusion; MatcherTest pins the public API behavior.
    for (String regex : List.of("x*", "x?", "(x)*", "(?<part>x*)", "", "$", "\\b", "x+")) {
      for (String input : List.of("", "x", "yxxy", "yyyy")) {
        FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip(regex, 0);
        assertThat(pattern.matcher(input).replaceAll("-")).isTrue();
        assertThat(pattern.matcher(input).replaceAll(result -> "-")).isTrue();
        FuzzSupport.MatcherPair first = pattern.matcher(input);
        assertThat(first.replaceFirst("-")).isTrue();
        first.hasReplacementMatchState();
      }
    }
  }

  @Test
  void nonAsciiDigitsInGroupReferences() {
    // Only '0'-'9' are group-reference digits. The group counts straddle the values a
    // code-point-derived number would take (U+0661 - '0' == 1585, and "$1" + U+0662 accumulates to
    // 1596), so a non-ASCII digit that is wrongly accepted selects an in-range group.
    for (int groups : List.of(1, 2, 10, 1585, 1596, 1800)) {
      FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip(manyGroups(groups), 0);
      assertThat(pattern).isNotNull();
      String input = "a";
      for (String replacement :
          List.of("$\u0661", "$1\u0662", "$12\u0662", "$\u06F1", "$1\u06F0", "$\uFF19")) {
        pattern.matcher(input).replaceAll(replacement);
        pattern.matcher(input).replaceFirst(replacement);
        appendReplacementLoop(pattern, input, replacement, false);
        appendReplacementLoop(pattern, input, replacement, true);
      }
    }
  }

  @Test
  void rejectedReplacementAfterPartialOutput() {
    // Each template fails after literal text or a valid group reference has been expanded, so the
    // oracle compares destinations after an IllegalArgumentException rollback and after an
    // IndexOutOfBoundsException that keeps partial output.
    for (String regex : PARTIAL_OUTPUT_PATTERNS) {
      FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip(regex, 0);
      assertThat(pattern).isNotNull();
      for (String prefix : PARTIAL_OUTPUT_PREFIXES) {
        for (String suffix : MALFORMED_REPLACEMENT_SUFFIXES) {
          String replacement = prefix + suffix;
          for (String input : List.of("axb", "xyax", "")) {
            appendReplacementLoop(pattern, input, replacement, false);
            appendReplacementLoop(pattern, input, replacement, true);
          }
        }
      }
    }
  }

  /**
   * Generates templates whose expansion fails after literal text or a valid group reference has
   * been written, so the append oracle checks the destination after partial output. This target has
   * its own decoder so saved inputs for {@link #replacement} keep their meaning.
   */
  @FuzzTest(maxDuration = "30s")
  void rejectedReplacementRollback(FuzzedDataProvider data) {
    String regex = data.pickValue(PARTIAL_OUTPUT_PATTERNS);
    StringBuilder input = new StringBuilder();
    int length = data.consumeInt(0, 16);
    for (int i = 0; i < length; i++) {
      input.append(data.pickValue(List.of("x", "y", "a", "xy")));
    }
    String replacement =
        data.pickValue(PARTIAL_OUTPUT_PREFIXES) + data.pickValue(MALFORMED_REPLACEMENT_SUFFIXES);
    FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip(regex, 0);
    if (pattern == null) {
      return;
    }
    appendReplacementLoop(pattern, input.toString(), replacement, false);
    appendReplacementLoop(pattern, input.toString(), replacement, true);
  }

  @FuzzTest(maxDuration = "30s")
  void replacement(FuzzedDataProvider data) {
    fuzzerTestOneInput(data);
  }

  public static void fuzzerTestOneInput(FuzzedDataProvider data) {
    boolean useStringBuffer = data.consumeBoolean();
    String regex;
    int flags;
    String input;
    String replacement;
    if (data.consumeBoolean()) {
      List<String> patternCharacters = List.of("\u03D1", "\u00DF");
      List<String> foldedCharacters = List.of("\u03F4", "\u1E9E");
      int variant = data.consumeInt(0, patternCharacters.size() - 1);
      String suffix = data.pickValue(List.of(".", "[xy]", "(?:x|y)"));
      regex = "(?iu)" + patternCharacters.get(variant) + suffix;
      flags = 0;
      input =
          data.consumeString(32)
              + foldedCharacters.get(variant)
              + data.pickValue(List.of("x", "y"))
              + data.consumeString(32);
      replacement = data.consumeString(32);
    } else if (data.consumeBoolean()) {
      String atom = data.pickValue(List.of("x", "[xy]", "(x)", "(?<part>x)", "(?:x|y)"));
      regex = atom + data.pickValue(List.of("*", "?", "{0,3}", "*?", "+"));
      flags = 0;
      input = data.consumeString(64);
      replacement = data.pickValue(List.of("-", "", "[$0]"));
    } else if (data.consumeBoolean()) {
      String leading = data.pickValue(List.of("\\s", "[é😀]", "[ab]"));
      String repetition = data.pickValue(List.of("*", "*?", "+", "{0,3}", "{1,3}"));
      regex = "(" + leading + repetition + ")# noqa" + data.pickValue(List.of("[0-9]", "\\b"));
      flags = 0;
      input =
          data.pickValue(List.of(" ", "é😀", "ab")).repeat(data.consumeInt(0, 64))
              + data.pickValue(List.of("# noqaX", "# noqa1", "# nopeX"))
              + data.pickValue(List.of("", "    # noqa2", "é😀# noqa3"));
      replacement = data.pickValue(List.of("X", "", "<$0>", "<$1>"));
    } else if (data.consumeBoolean()) {
      String low = data.pickValue(List.of("\uDC00", "\uDE00", "\uDFFF"));
      String high = data.pickValue(List.of("\uD800", "\uD83D", "\uDBFF"));
      regex = "(\\s" + data.pickValue(List.of("*", "*?", "+", "{0,3}")) + ")" + low + "[bc]";
      flags = 0;
      input =
          " " + low + "b;" + (high + low + "b;").repeat(data.consumeInt(1, 64)) + " " + low + "c";
      replacement = data.pickValue(List.of("X", "<$0>", "<$1>"));
    } else if (data.consumeBoolean()) {
      int window = data.consumeInt(1, 16);
      regex = "(\\s*)# noqa[ab]*a[ab]{" + window + "}Z";
      flags = 0;
      StringBuilder binary = new StringBuilder();
      int length = data.consumeInt(0, 512);
      for (int i = 0; i < length; i++) {
        binary.append(data.consumeBoolean() ? 'a' : 'b');
      }
      String candidate =
          "# noqa" + binary + "a" + "b".repeat(window) + data.pickValue(List.of("Z", "Y"));
      input =
          data.pickValue(List.of("", "# noqa" + "a" + "b".repeat(window) + "Z;"))
              + " ".repeat(data.consumeInt(0, 64))
              + candidate;
      replacement = data.pickValue(List.of("X", "<$0>", "<$1>"));
    } else if (data.consumeBoolean()) {
      // Non-ASCII decimal digits must not be accepted as group-reference digits, directly after
      // '$' or after an ASCII digit. Use enough groups that a number derived from the code point
      // (or accumulated from it) is a valid group, so a wrongly accepted digit selects a group
      // instead of failing.
      int groups = data.pickValue(List.of(1, 2, 10, 11, 100, 1585, 1594, 1596, 1738, 1800));
      regex = manyGroups(groups);
      flags = 0;
      input = "a" + data.consumeString(8);
      replacement = nonAsciiDigitReplacement(data);
    } else {
      int template = data.consumeInt(0, 4);
      flags = FuzzSupport.consumeFlags(data);
      SemanticDomain.Sample sample = SemanticDomain.generate(data, flags);
      regex = sample.regex();
      input = sample.input(data);
      FuzzSupport.CompiledPattern compiled = FuzzSupport.compileRequired(regex, flags);
      replacement =
          switch (template) {
            case 0 -> "[$0]";
            case 1 -> "";
            case 2 -> "X";
            case 3 -> compiled.matcher("").groupCount() > 0 ? "[$1]" : "[$0]";
            default -> data.consumeString(1024);
          };
      compareReplacements(compiled, input, replacement, useStringBuffer);
      return;
    }
    compareReplacements(regex, flags, input, replacement, useStringBuffer);
  }

  static void compareReplacements(
      String regex, int flags, String input, String replacement, boolean useStringBuffer) {
    compareReplacements(
        FuzzSupport.compileRequired(regex, flags), input, replacement, useStringBuffer);
  }

  private static void compareReplacements(
      FuzzSupport.CompiledPattern pattern,
      String input,
      String replacement,
      boolean useStringBuffer) {
    FuzzSupport.MatcherPair replaceAllMatcher = pattern.matcher(input);
    if (!replaceAllMatcher.replaceAll(replacement)) {
      // A rejected template may still produce partial output before the append loop reaches the
      // invalid part, so check the destination after rejection on a fresh matcher.
      appendReplacementLoop(pattern, input, replacement, useStringBuffer);
      return;
    }
    replaceAllMatcher.reset();
    replaceAllMatcher.find();

    FuzzSupport.MatcherPair replaceFirstMatcher = pattern.matcher(input);
    if (!replaceFirstMatcher.replaceFirst(replacement)) {
      appendReplacementLoop(pattern, input, replacement, useStringBuffer);
      return;
    }
    replaceFirstMatcher.hasReplacementMatchState();

    appendReplacementLoop(pattern, input, replacement, useStringBuffer);
  }

  /**
   * Returns a pattern with {@code groups} capture groups where only group 1 captures text. The
   * other groups are empty, so matching stays cheap even with thousands of groups, while a wrongly
   * selected group still changes the replacement output.
   */
  private static String manyGroups(int groups) {
    return "(a)" + "()".repeat(groups - 1);
  }

  private static String nonAsciiDigitReplacement(FuzzedDataProvider data) {
    String nonAscii =
        data.pickValue(
            List.of(
                "\u0660",
                "\u0661",
                "\u0669",
                "\u06F0",
                "\u06F1",
                "\u06F9",
                "\u0966",
                "\uFF11",
                "\uFF19",
                "\uD835\uDFCE"));
    String digit = data.pickValue(List.of("0", "1", "2", "9"));
    String reference =
        switch (data.consumeInt(0, 3)) {
          case 0 -> "$" + nonAscii;
          case 1 -> "$" + digit + nonAscii;
          case 2 -> "$" + digit + digit + nonAscii;
          default -> "$" + digit + nonAscii + digit;
        };
    return data.pickValue(List.of("", "[", "x"))
        + reference
        + data.pickValue(List.of("", "]", "$1"));
  }

  private static void appendReplacementLoop(
      FuzzSupport.CompiledPattern pattern,
      String input,
      String replacement,
      boolean useStringBuffer) {
    FuzzSupport.MatcherPair matcher = pattern.matcher(input);
    if (useStringBuffer) {
      StringBuffer sb = new StringBuffer();
      while (matcher.find()) {
        if (!matcher.appendReplacement(sb, replacement)) {
          return;
        }
      }
      matcher.appendTail(sb);
    } else {
      StringBuilder sb = new StringBuilder();
      while (matcher.find()) {
        if (!matcher.appendReplacement(sb, replacement)) {
          return;
        }
      }
      matcher.appendTail(sb);
    }
  }
}
