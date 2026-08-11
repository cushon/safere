// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.safere.Pattern.CharClassScanInfo;

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class RejectPrefilterTest {

  @Test
  void nullAndNoneDescriptorsProduceNullPrefilter() {
    assertThat(RejectPrefilter.create(null)).isNull();
    assertThat(RejectPrefilter.create(RejectDescriptor.NONE)).isNull();
    assertThat(RejectDescriptor.NONE.hasRejectionFilter()).isFalse();
  }

  @Test
  void literalRejectPrefilterRejectsMissingLiteral() {
    RejectDescriptor desc = new RejectDescriptor("needle", null);
    assertThat(desc.hasRejectionFilter()).isTrue();

    RejectPrefilter prefilter = RejectPrefilter.create(desc);
    assertThat(prefilter).isInstanceOf(RejectPrefilter.Literal.class);
    RejectPrefilter.Literal lit = (RejectPrefilter.Literal) prefilter;
    assertThat(lit.strategy()).isEqualTo(MatchStrategy.LITERAL);
    assertThat(lit.literal()).isEqualTo("needle");
    assertThat(lit.utf8()).isEqualTo("needle".getBytes(UTF_8));
    assertThat(lit.failure()).isNotNull();
    assertThat(lit.shifts()).isNotNull();

    EnginePathOptions options = EnginePathOptions.allEnabled();

    // String input
    assertThat(prefilter.canReject(null, "haystack with needle in it", 0, options)).isFalse();
    assertThat(prefilter.canReject(null, "haystack without keyword in it", 0, options)).isTrue();
    assertThat(prefilter.canReject(null, "needle is at start", 7, options)).isTrue();

    // UTF-8 scanner
    Utf8InputScanner matchingScanner = utf8Scanner("haystack with needle in it");
    Utf8InputScanner nonMatchingScanner = utf8Scanner("haystack without keyword in it");
    assertThat(prefilter.canReject(matchingScanner, 0, options)).isFalse();
    assertThat(prefilter.canReject(nonMatchingScanner, 0, options)).isTrue();

    // Disabled option
    EnginePathOptions disabled = EnginePathOptions.builder().literalFastPaths(false).build();
    assertThat(prefilter.canReject(nonMatchingScanner, 0, disabled)).isFalse();
  }

  @Test
  void charClassRejectPrefilterRejectsMissingClass() {
    // Digit class [0-9]
    int[] ranges = new int[] {'0', '9'};
    long b0 = 0x03FF000000000000L; // digits 0-9
    long b1 = 0L;
    CharClassScanInfo scanInfo = new CharClassScanInfo(ranges, b0, b1, true);

    RejectDescriptor desc = new RejectDescriptor(null, scanInfo);
    assertThat(desc.hasRejectionFilter()).isTrue();

    RejectPrefilter prefilter = RejectPrefilter.create(desc);
    assertThat(prefilter).isInstanceOf(RejectPrefilter.CharClass.class);
    RejectPrefilter.CharClass cc = (RejectPrefilter.CharClass) prefilter;
    assertThat(cc.strategy()).isEqualTo(MatchStrategy.CHARACTER_CLASS);
    assertThat(cc.ranges()).isEqualTo(ranges);
    assertThat(cc.bitmap0()).isEqualTo(b0);
    assertThat(cc.bitmap1()).isEqualTo(b1);

    EnginePathOptions options = EnginePathOptions.allEnabled();

    Utf8InputScanner matchingScanner = utf8Scanner("item-42-test");
    Utf8InputScanner nonMatchingScanner = utf8Scanner("item-no-digits");
    assertThat(prefilter.canReject(matchingScanner, 0, options)).isFalse();
    assertThat(prefilter.canReject(nonMatchingScanner, 0, options)).isTrue();

    // Disabled option
    EnginePathOptions disabled = EnginePathOptions.builder().charClassMatchFastPaths(false).build();
    assertThat(prefilter.canReject(nonMatchingScanner, 0, disabled)).isFalse();
  }

  @Test
  void compositeRejectPrefilterRejectsIfAnyFilterRejects() {
    int[] ranges = new int[] {'0', '9'};
    long b0 = 0x03FF000000000000L;
    long b1 = 0L;
    CharClassScanInfo scanInfo = new CharClassScanInfo(ranges, b0, b1, true);

    RejectDescriptor desc = new RejectDescriptor("token", scanInfo);
    RejectPrefilter prefilter = RejectPrefilter.create(desc);

    assertThat(prefilter).isInstanceOf(RejectPrefilter.Composite.class);
    RejectPrefilter.Composite composite = (RejectPrefilter.Composite) prefilter;
    assertThat(composite.filters()).hasSize(2);

    EnginePathOptions options = EnginePathOptions.allEnabled();

    // Has both token and digits -> not rejected
    assertThat(prefilter.canReject(utf8Scanner("prefix token 123 suffix"), 0, options)).isFalse();

    // Missing token -> rejected
    assertThat(prefilter.canReject(utf8Scanner("prefix missing 123 suffix"), 0, options)).isTrue();

    // Missing digits -> rejected
    assertThat(prefilter.canReject(utf8Scanner("prefix token no-digits suffix"), 0, options))
        .isTrue();
  }

  @Test
  void diagnosticsAccumulateOnRejection() {
    RejectDescriptor desc = new RejectDescriptor("needle", null);
    RejectPrefilter prefilter = RejectPrefilter.create(desc);

    DiagnosticAccumulator accumulator = new DiagnosticAccumulator();
    Utf8InputScanner scanner = utf8Scanner("no match here");

    boolean rejected =
        prefilter.canRejectWithDiagnostics(scanner, 0, EnginePathOptions.allEnabled(), accumulator);
    assertThat(rejected).isTrue();

    Pattern pattern = Pattern.compile("needle");
    OperationDiagnostics event =
        accumulator.toEvent(
            pattern.descriptor(),
            MatchOperation.FIND,
            MatchOutcome.NO_MATCH,
            CaptureMode.NONE,
            scanner.length());
    assertThat(event.auxiliaryStrategies())
        .contains(new StrategyParticipation(MatchStrategy.LITERAL, StrategyRole.REJECT_PREFILTER));
    assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.LITERAL);
  }

  @Test
  void disjointLiteralsRejectPrefilterRejectsWhenAllMissing() {
    String[] literals = new String[] {"apple", "banana", "orange"};
    Pattern.DisjointRequiredLiterals disjoint = new Pattern.DisjointRequiredLiterals(literals);
    RejectDescriptor desc = new RejectDescriptor(null, null, disjoint);
    assertThat(desc.hasRejectionFilter()).isTrue();

    RejectPrefilter.DisjointLiterals prefilter = RejectPrefilter.DisjointLiterals.create(disjoint);
    assertThat(prefilter).isNotNull();
    assertThat(prefilter.strategy()).isEqualTo(MatchStrategy.LITERAL);
    assertThat(prefilter.literals()).isEqualTo(literals);

    EnginePathOptions options = EnginePathOptions.allEnabled();

    // String input
    assertThat(prefilter.canReject(null, "I like banana smoothie", 0, options)).isFalse();
    assertThat(prefilter.canReject(null, "I like apple pie", 0, options)).isFalse();
    assertThat(prefilter.canReject(null, "I like grape juice", 0, options)).isTrue();

    // UTF-8 input (disjoint literals prefilter is String-only to avoid redundant UTF-8 scans)
    assertThat(prefilter.canReject(utf8Scanner("I like orange juice"), 0, options)).isFalse();
    assertThat(prefilter.canReject(utf8Scanner("I like grape juice"), 0, options)).isFalse();

    // Disabled option
    EnginePathOptions disabled = EnginePathOptions.builder().literalFastPaths(false).build();
    assertThat(prefilter.canReject(null, "I like grape juice", 0, disabled)).isFalse();
  }

  private static Utf8InputScanner utf8Scanner(String text) {
    byte[] bytes = text.getBytes(UTF_8);
    return new Utf8InputScanner(bytes, 0, bytes.length);
  }
}
