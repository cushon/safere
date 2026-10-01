// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FuzzGraphemeBoundariesTest {
  @ParameterizedTest
  @ValueSource(strings = {"\ud83c\udde6", "\u0915", "\ud83d\udc4d\u200d"})
  void allBoundaryQueriesShareLinearContextWork(String unit) {
    assumeTrue(WorkCounterConfig.ENABLED, "requires -Pwork-counters");
    long smaller = boundaryWork(unit.repeat(100));
    long larger = boundaryWork(unit.repeat(500));
    assertThat(smaller).isPositive();
    assertThat(larger).isLessThan(smaller * 6);
  }

  private static long boundaryWork(String text) {
    return WorkCounter.countForTesting(() -> FuzzGraphemeBoundaries.boundaries(text));
  }
}
