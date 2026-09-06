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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    EnginePathOptions options = EnginePathOptions.builder().shiftDfa(false).build();
    Pattern pattern = Pattern.compile("^[a-z]+:[0-9]+$", 0, options);
    String input = "abc:" + "1".repeat(50); // ~55 bytes <= 256

    Matcher matcher = pattern.matcher(input);
    assertThat(matcher.matches()).isTrue();

    OperationDiagnostics op = lastOperationFor(pattern);
    assertThat(op.boundaryStrategy()).isEqualTo(MatchStrategy.ONE_PASS);
  }

  @Test
  void noDeclaredGroups_overThreshold_routesToDfa() {
    EnginePathOptions options = EnginePathOptions.builder().shiftDfa(false).build();
    Pattern pattern = Pattern.compile("^[a-z]+:[0-9]+$", 0, options);
    String input = "abc:" + "1".repeat(1_000); // 1005 bytes > 256

    Matcher matcher = pattern.matcher(input);
    assertThat(matcher.matches()).isTrue();

    OperationDiagnostics op = lastOperationFor(pattern);
    assertThat(op.boundaryStrategy()).isEqualTo(MatchStrategy.DFA);
  }

  @Test
  void declaredGroups_underThreshold_usesOnePass() {
    Pattern pattern = Pattern.compile("^([a-z]+):([0-9]+)$");
    String input = "abc:" + "1".repeat(1_000); // 1005 bytes > 256 B, <= 64 KB

    Matcher matcher = pattern.matcher(input);
    assertThat(matcher.matches()).isTrue();
    assertThat(matcher.group(1)).isEqualTo("abc");

    // Pattern has capturing groups -> uses OnePass up to 64 KB in single pass
    OperationDiagnostics op = lastOperationFor(pattern);
    assertThat(op.boundaryStrategy()).isEqualTo(MatchStrategy.ONE_PASS);
  }

  @Test
  void declaredGroups_overThreshold_routesToDfa() {
    Pattern pattern = Pattern.compile("^([a-z]+):([0-9]+)$");
    String input = "abc:" + "1".repeat(70_000); // 70005 bytes > 64 KB

    Matcher matcher = pattern.matcher(input);
    assertThat(matcher.matches()).isTrue();
    assertThat(matcher.group(1)).isEqualTo("abc");

    // Over 64 KB -> routes to DFA
    OperationDiagnostics op = lastOperationFor(pattern);
    assertThat(op.boundaryStrategy()).isEqualTo(MatchStrategy.DFA);
  }

  @Test
  void replaceAll_groupZero_staysInGroupZeroTier() {
    Pattern pattern = Pattern.compile("^([a-z]+):([0-9]+)$");
    String input = "abc:" + "1".repeat(1_000); // 1005 bytes > 256 B

    Matcher matcher = pattern.matcher(input);
    String replaced = matcher.replaceAll("[$0]");
    assertThat(replaced).isEqualTo("[" + input + "]");

    // $0 does not need inner captures, so input > 256 B routes to DFA even with declared groups
    OperationDiagnostics op = lastOperationFor(pattern);
    assertThat(op.boundaryStrategy()).isNotEqualTo(MatchStrategy.ONE_PASS);
  }

  @ParameterizedTest
  @ValueSource(strings = {"$01", "$09", "$099999999999999999999999999999999999999"})
  void replaceAll_groupZeroFollowedByDigits_staysInGroupZeroTier(String replacement) {
    Pattern pattern = Pattern.compile("^[a-z]+$");
    String input = "a".repeat(1_000);

    assertThat(pattern.matcher(input).replaceAll(replacement))
        .isEqualTo(input + replacement.substring(2));
    assertThat(lastOperationFor(pattern).boundaryStrategy()).isNotEqualTo(MatchStrategy.ONE_PASS);
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
