// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated
@Execution(ExecutionMode.SAME_THREAD)
@DisabledForCrosscheck("verifies SafeRE engine selection thresholds and lifecycle invariants")
class OnePassThresholdTest {

  private final RecordingDiagnostics diagnostics = new RecordingDiagnostics();

  @BeforeEach
  void setUp() {
    Pattern.setDiagnostics(diagnostics);
  }

  @AfterEach
  void tearDown() {
    Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
  }

  @Test
  void noDeclaredGroups_underThreshold_usesOnePass() {
    Pattern pattern = Pattern.compile("^[a-z]+:[0-9]+$");
    String input = "abc:" + "1".repeat(50); // ~55 bytes <= 256

    Matcher matcher = pattern.matcher(input);
    assertThat(matcher.matches()).isTrue();

    OperationDiagnostics op = lastOperationFor(pattern);
    assertThat(op.boundaryStrategy()).isEqualTo(MatchStrategy.ONE_PASS);
  }

  @Test
  void noDeclaredGroups_overThreshold_routesToDfa() {
    Pattern pattern = Pattern.compile("^[a-z]+:[0-9]+$");
    String input = "abc:" + "1".repeat(1_000); // 1005 bytes > 256

    Matcher matcher = pattern.matcher(input);
    assertThat(matcher.matches()).isTrue();

    OperationDiagnostics op = lastOperationFor(pattern);
    assertThat(op.boundaryStrategy()).isEqualTo(MatchStrategy.DFA);
  }

  @Test
  void declaredGroups_unread_staysInGroupZeroTier() {
    Pattern pattern = Pattern.compile("^([a-z]+):([0-9]+)$");
    String input = "abc:" + "1".repeat(1_000); // 1005 bytes > 256

    Matcher matcher = pattern.matcher(input);
    assertThat(matcher.matches()).isTrue();

    // Caller only called matches(), never accessed inner groups -> routes to DFA
    OperationDiagnostics op = lastOperationFor(pattern);
    assertThat(op.boundaryStrategy()).isEqualTo(MatchStrategy.DFA);
  }

  @Test
  void declaredGroups_read_switchesToInnerCaptureTier() {
    Pattern pattern = Pattern.compile("^([a-z]+):([0-9]+)$");

    Matcher matcher = pattern.matcher("abc:123");
    assertThat(matcher.matches()).isTrue();
    // Access group 1 to record inner capture demand
    assertThat(matcher.group(1)).isEqualTo("abc");

    // Subsequent match on 1,000-byte input (> 256 B, <= 64 KB) now uses OnePass
    String largeInput = "abc:" + "1".repeat(1_000);
    matcher.reset(largeInput);
    assertThat(matcher.matches()).isTrue();
    assertThat(matcher.group(1)).isEqualTo("abc");

    OperationDiagnostics op = lastOperationFor(pattern);
    assertThat(op.boundaryStrategy()).isEqualTo(MatchStrategy.ONE_PASS);
    assertThat(op.captureStrategy()).isEqualTo(MatchStrategy.ONE_PASS);
  }

  @Test
  void captureDemand_survivesReset() {
    Pattern pattern = Pattern.compile("^([a-z]+):([0-9]+)$");

    Matcher matcher = pattern.matcher("abc:123");
    assertThat(matcher.matches()).isTrue();
    assertThat(matcher.group(1)).isEqualTo("abc");

    // Demand survives parameterless reset()
    matcher.reset();
    String largeInput = "abc:" + "1".repeat(1_000);
    matcher.reset(largeInput);
    assertThat(matcher.matches()).isTrue();

    OperationDiagnostics op = lastOperationFor(pattern);
    assertThat(op.boundaryStrategy()).isEqualTo(MatchStrategy.ONE_PASS);
  }

  @Test
  void captureDemand_resetsOnUsePattern() {
    Pattern pattern = Pattern.compile("^([a-z]+):([0-9]+)$");
    Pattern newPattern = Pattern.compile("^([a-z]+)=([0-9]+)$");

    Matcher matcher = pattern.matcher("abc:123");
    assertThat(matcher.matches()).isTrue();
    assertThat(matcher.group(1)).isEqualTo("abc");

    // Switching pattern resets capture demand
    matcher.usePattern(newPattern);
    String largeInput = "abc=" + "1".repeat(1_000);
    matcher.reset(largeInput);
    assertThat(matcher.matches()).isTrue();

    OperationDiagnostics op = lastOperationFor(newPattern);
    // Unread on newPattern -> routes to DFA
    assertThat(op.boundaryStrategy()).isEqualTo(MatchStrategy.DFA);
  }

  @Test
  void twoMatchersOnSamePatternAreIndependent() {
    Pattern pattern = Pattern.compile("^([a-z]+):([0-9]+)$");
    String largeInput = "abc:" + "1".repeat(1_000);

    Matcher matcherA = pattern.matcher("abc:123");
    assertThat(matcherA.matches()).isTrue();
    assertThat(matcherA.group(1)).isEqualTo("abc"); // Demand recorded on A

    Matcher matcherB = pattern.matcher(largeInput); // Fresh matcher, no demand
    assertThat(matcherB.matches()).isTrue();

    OperationDiagnostics opB = lastOperationFor(pattern);
    assertThat(opB.boundaryStrategy()).isEqualTo(MatchStrategy.DFA);

    matcherA.reset(largeInput);
    assertThat(matcherA.matches()).isTrue();
    OperationDiagnostics opA = lastOperationFor(pattern);
    assertThat(opA.boundaryStrategy()).isEqualTo(MatchStrategy.ONE_PASS);
  }

  @Test
  void replaceAll_groupZero_staysInGroupZeroTier() {
    Pattern pattern = Pattern.compile("^[a-z]+:[0-9]+$");
    String input = "abc:" + "1".repeat(1_000);

    Matcher matcher = pattern.matcher(input);
    String replaced = matcher.replaceAll("[$0]");
    assertThat(replaced).isEqualTo("[" + input + "]");

    // $0 does not need inner captures, so input > 256 B bypasses OnePass
    OperationDiagnostics op = lastOperationFor(pattern);
    assertThat(op.boundaryStrategy()).isNotEqualTo(MatchStrategy.ONE_PASS);
  }

  @Test
  void replaceAll_innerNumberedGroup_usesOnePassTier() {
    Pattern pattern = Pattern.compile("^([a-z]+):([0-9]+)$");
    String input = "abc:" + "1".repeat(1_000);

    Matcher matcher = pattern.matcher(input);
    String replaced = matcher.replaceAll("[$1]");
    assertThat(replaced).isEqualTo("[abc]");

    OperationDiagnostics op = lastOperationFor(pattern);
    assertThat(op.boundaryStrategy()).isEqualTo(MatchStrategy.ONE_PASS);
  }

  @Test
  void replaceAll_namedGroup_usesOnePassTier() {
    Pattern pattern = Pattern.compile("^(?<prefix>[a-z]+):(?<suffix>[0-9]+)$");
    String input = "abc:" + "1".repeat(1_000);

    Matcher matcher = pattern.matcher(input);
    String replaced = matcher.replaceAll("[${prefix}]");
    assertThat(replaced).isEqualTo("[abc]");

    OperationDiagnostics op = lastOperationFor(pattern);
    assertThat(op.boundaryStrategy()).isEqualTo(MatchStrategy.ONE_PASS);
  }

  private OperationDiagnostics lastOperationFor(Pattern pattern) {
    long patternId = pattern.descriptor().patternId();
    List<OperationDiagnostics> ops =
        diagnostics.operations.stream()
            .filter(event -> event.pattern().patternId() == patternId)
            .toList();
    assertThat(ops).isNotEmpty();
    return ops.get(ops.size() - 1);
  }

  private static final class RecordingDiagnostics extends SafeReMatchDiagnostics {
    final List<OperationDiagnostics> operations = new ArrayList<>();

    @Override
    public void onOperationCompleted(OperationDiagnostics event) {
      operations.add(event);
    }
  }
}
