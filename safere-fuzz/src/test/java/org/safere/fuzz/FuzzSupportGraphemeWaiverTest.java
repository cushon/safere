// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests the fuzzing harness waiver for unassigned code points in grapheme cluster matching.
 *
 * <p>See #925, b/564599082, and b/568211495.
 */
final class FuzzSupportGraphemeWaiverTest {
  // U+A7C2E, unassigned with Grapheme_Cluster_Break=Other (b/568211495).
  private static final String UNASSIGNED = "\uda5f\udc2e";

  @Test
  void waiverCoversUnassignedOtherBeforeExtend() {
    assertThat(FuzzSupport.hasUnassignedGraphemeDivergence("\\X", UNASSIGNED + "\u0301")).isTrue();
    // U+8D43F + U+07EF from b/564599082.
    assertThat(FuzzSupport.hasUnassignedGraphemeDivergence("\\X", "\udaf5\udc3f\u07ef")).isTrue();
  }

  @Test
  void waiverCoversExtendersOutsideGeneralCategoryMarks() {
    // SpacingMark (Lo), Other_Grapheme_Extend (Lm), and tag characters (Cf).
    for (String next : new String[] {"\u0e33", "\uff9e", "\udb40\udc20"}) {
      assertThat(FuzzSupport.hasUnassignedGraphemeDivergence("\\X", UNASSIGNED + next))
          .as("U+A7C2E followed by U+%04X", next.codePointAt(0))
          .isTrue();
    }
  }

  @Test
  void waiverCoversPrependBeforeUnassignedOther() {
    for (String prepend : new String[] {"\u0600", "\u0d4e", "\ud804\uddc2"}) {
      assertThat(FuzzSupport.hasUnassignedGraphemeDivergence("\\b{g}", prepend + UNASSIGNED))
          .as("U+%04X followed by U+A7C2E", prepend.codePointAt(0))
          .isTrue();
    }
  }

  @Test
  void waiverDoesNotApplyWhenSegmentationAgrees() {
    assertThat(FuzzSupport.hasUnassignedGraphemeDivergence("\\X", "a\u0301")).isFalse();
    assertThat(FuzzSupport.hasUnassignedGraphemeDivergence("\\X", UNASSIGNED + "a")).isFalse();
    // Unassigned default-ignorable code points are Control in both UAX #29 and the JDK.
    assertThat(FuzzSupport.hasUnassignedGraphemeDivergence("\\X", "\u2065\u0301")).isFalse();
  }

  @Test
  void waiverDoesNotApplyToPatternsWithoutGraphemeConstructs() {
    assertThat(FuzzSupport.hasUnassignedGraphemeDivergence(".", UNASSIGNED + "\u0301")).isFalse();
  }

  @Test
  void waiverDoesNotHideOtherGraphemeDivergences() {
    String thumbsUp = "\ud83d\udc4d";
    // GB11 across two ZWJs (#936) does not involve unassigned code points.
    assertThat(
            FuzzSupport.hasUnassignedGraphemeDivergence(
                "\\X", thumbsUp + "\u200d\u200d" + thumbsUp))
        .isFalse();
    // An unrelated divergence elsewhere in the input is still reported.
    assertThat(
            FuzzSupport.hasUnassignedGraphemeDivergence(
                "\\X", UNASSIGNED + "\u0301" + thumbsUp + "\u200d\u200d" + thumbsUp))
        .isFalse();
  }

  @Test
  void waivedFindReportsSafeReCluster() {
    String input = UNASSIGNED + "\u0301";
    var pair = FuzzSupport.compileOrSkip("\\X", 0).matcher(input);
    assertThat(pair.find()).isTrue();
    assertThat(pair.start()).isZero();
    assertThat(pair.end()).isEqualTo(3);
    assertThat(pair.group()).isEqualTo(input);
  }

  @Test
  void waivedSplitRunsSafeReOnly() {
    String input = "a" + UNASSIGNED + "\u0301b";
    FuzzSupport.CompiledPattern pattern = FuzzSupport.compileOrSkip("\\X", 0);
    pattern.split(input);
    pattern.split(input, 2);
    pattern.splitWithDelimiters(input);
    pattern.splitWithDelimiters(input, 2);
  }

  @Test
  void unwaivedInputsAreStillComparedWithJdk() {
    var pair = FuzzSupport.compileOrSkip("\\X", 0).matcher("a\u0301");
    assertThat(pair.find()).isTrue();
    assertThat(pair.end()).isEqualTo(2);
  }

  @Test
  void resetDoesNotReenableOracleAfterWaiver() {
    var pair = FuzzSupport.compileOrSkip("\\X", 0).matcher(UNASSIGNED + "\u0301");
    // Skipped on the JDK matcher while the oracle is unavailable.
    pair.useTransparentBounds(true);
    pair.reset("a");
    assertThat(pair.hasTransparentBounds()).isTrue();
  }
}
