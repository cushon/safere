// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Tests for {@link FindPlan}: its derivation per pattern shape, and agreement between users. */
@DisabledForCrosscheck("FindPlan and diagnostics events are internal SafeRE APIs")
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class FindPlanTest {

  @AfterEach
  void disableDiagnostics() {
    Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '"',
      textBlock =
          """
          # regex                           | precedes | subsumes | wide  | existence
          .*error: \\w+                     | false    | true     | true  | true
          .*?error: \\w+                    | false    | true     | true  | true
          (.*)error: \\w+                   | false    | true     | true  | true
          [^\\n]*error: \\w+                | false    | true     | true  | true
          (?:.*)?error: \\w+                | false    | true     | true  | true
          [^;]*error: \\w+                  | false    | true     | true  | true
          .*\\berror: \\w+                  | false    | true     | true  | true
          .+error: \\w+                     | false    | true     | true  | false
          \\b.*error: \\w+                  | false    | true     | true  | false
          ^.*error: \\w+                    | false    | false    | true  | false
          (?i).*error: \\w+                 | false    | false    | true  | false
          (?s).*error: \\w+                 | false    | false    | false | false
          \\s*# noqa                        | false    | false    | false | true
          \\s+# noqa                        | false    | false    | false | false
          status=3\\d\\d took=(\\d+)ms      | true     | false    | false | false
          (?i:status=3\\d\\d) took=(\\d+)ms | false    | false    | false | false
          hello                             | false    | false    | false | false
          """)
  void derivesDecisionsFromThePatternShape(
      String regex, boolean precedes, boolean subsumes, boolean wide, boolean existence) {
    FindPlan plan = Pattern.compile(regex).findPlan();
    assertThat(plan.startPrecedesReject()).as("startPrecedesReject").isEqualTo(precedes);
    assertThat(plan.startSubsumesReject()).as("startSubsumesReject").isEqualTo(subsumes);
    assertThat(plan.wideLeadingExpansion()).as("wideLeadingExpansion").isEqualTo(wide);
    assertThat(plan.existenceStartsAtInner()).as("existenceStartsAtInner").isEqualTo(existence);
    assertThat(plan.dfaStartAcceleration()).isEqualTo(!wide);
  }

  @Test
  void startDecisionsNeedStartAcceleration() {
    EnginePathOptions noStart = EnginePathOptions.builder().startAcceleration(false).build();
    for (String regex : List.of(".*error: \\w+", "status=3\\d\\d took=(\\d+)ms")) {
      FindPlan plan = Pattern.compile(regex, 0, noStart).findPlan();
      assertThat(plan.startPrecedesReject()).as(regex).isFalse();
      assertThat(plan.startSubsumesReject()).as(regex).isFalse();
      assertThat(plan.rejectMode(plan.stringReject(), 10, true))
          .as(regex)
          .isEqualTo(FindPlan.REJECT_FIRST);
    }
  }

  @Test
  void rejectModeFollowsTheInputLength() {
    FindPlan plan = Pattern.compile("status=3\\d\\d took=(\\d+)ms").findPlan();
    RejectPrefilter reject = plan.stringReject();
    assertThat(reject).isNotNull();
    int limit = FindPlan.MAX_LITERAL_START_FIRST_LENGTH;
    assertThat(plan.rejectMode(reject, limit - 1, true)).isEqualTo(FindPlan.REJECT_TAIL);
    assertThat(plan.rejectMode(reject, limit, true)).isEqualTo(FindPlan.REJECT_FIRST);
    // Without a start search, a short input still needs the whole-input reject.
    assertThat(plan.rejectMode(reject, limit - 1, false)).isEqualTo(FindPlan.REJECT_FIRST);
    assertThat(plan.rejectMode(null, limit - 1, true)).isEqualTo(FindPlan.REJECT_NONE);
    int tail = FindPlan.MIN_REJECT_AFTER_START_REMAINING;
    assertThat(FindPlan.rejectsTail(FindPlan.REJECT_TAIL, tail)).isTrue();
    assertThat(FindPlan.rejectsTail(FindPlan.REJECT_TAIL, tail - 1)).isFalse();
    assertThat(FindPlan.rejectsTail(FindPlan.REJECT_FIRST, tail)).isFalse();

    FindPlan subsumed = Pattern.compile(".*error: \\w+").findPlan();
    assertThat(subsumed.rejectMode(subsumed.stringReject(), 10_000, true))
        .isEqualTo(FindPlan.REJECT_NONE);
  }

  /**
   * The UTF-8 matcher and the matcher-free {@link Pattern#find(Utf8Input)} read the same plan, so
   * they must make the same reject and start decisions: the same rejecting strategy, the same
   * reject and start participations, and the same outcome.
   */
  @Test
  void utf8EntryPointsMakeTheSameRejectAndStartDecisions() {
    List<OperationDiagnostics> events = new ArrayList<>();
    Pattern.setDiagnostics(
        new SafeReMatchDiagnostics() {
          @Override
          public void onOperationCompleted(OperationDiagnostics event) {
            events.add(event);
          }
        });
    List<String> regexes =
        List.of(
            "status=3\\d\\d took=(\\d+)ms",
            "(?i:status=3\\d\\d) took=(\\d+)ms",
            "er\\d+<<!nav>>\\d+",
            ".*error: \\w+",
            "[^\\n]*\\bSCRUB:strip_line\\b[^\\n]*\\n?",
            ".+error: \\w+",
            "\\s*# noqa[0-9]",
            "hello [a-z]+ world");
    List<String> failures = new ArrayList<>();
    for (String regex : regexes) {
      Pattern pattern = Pattern.compile(regex);
      for (String input : inputs()) {
        Utf8Input utf8 = Utf8Input.trusted(input.getBytes(UTF_8));
        events.clear();
        boolean matcher = pattern.matcher(utf8).find();
        boolean direct = pattern.find(utf8);
        assertThat(events).hasSize(2);
        assertThat(direct).as("%s on %s", regex, describe(input)).isEqualTo(matcher);
        String expected = decisions(events.get(0));
        String actual = decisions(events.get(1));
        if (!expected.equals(actual)) {
          failures.add(
              "/"
                  + regex
                  + "/ on "
                  + describe(input)
                  + ": matcher "
                  + expected
                  + ", direct "
                  + actual);
        }
      }
    }
    assertThat(failures).isEmpty();
  }

  private static List<String> inputs() {
    String filler = "the quick brown fox jumps over the lazy dog ";
    List<String> inputs = new ArrayList<>();
    for (int size : new int[] {40, 300, 600, 20_000}) {
      String base = filler.repeat(Math.max(1, size / filler.length()));
      inputs.add(base);
      inputs.add(base + "status=");
      inputs.add(base + "status=302 took=5ms");
      inputs.add(base + "error: disk");
      inputs.add(base + "   # noqa1");
    }
    return inputs;
  }

  private static String decisions(OperationDiagnostics event) {
    List<String> roles = new ArrayList<>();
    for (StrategyParticipation participation : event.auxiliaryStrategies()) {
      if (participation.role() == StrategyRole.REJECT_PREFILTER
          && participation.strategy() != MatchStrategy.DFA) {
        roles.add("reject:" + participation.strategy());
      } else if (participation.role() == StrategyRole.START_ACCELERATION) {
        roles.add("start:" + participation.strategy());
      }
    }
    boolean rejected = event.outcome() == MatchOutcome.NO_MATCH && isPrefilter(event);
    return roles.stream().distinct().sorted().toList()
        + (rejected ? " rejected by " + event.boundaryStrategy() : "");
  }

  private static boolean isPrefilter(OperationDiagnostics event) {
    return event.auxiliaryStrategies().stream()
        .anyMatch(p ->
            p.role() == StrategyRole.REJECT_PREFILTER && p.strategy() == event.boundaryStrategy());
  }

  private static String describe(String input) {
    return input.length()
        + " chars ending '"
        + input.substring(Math.max(0, input.length() - 20))
        + "'";
  }
}
