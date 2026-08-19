// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class WorkLimitTest {

  @Test
  void forRemainingCalculatesDoubleRemainingWithMinimumOne() {
    assertThat(WorkLimit.forRemaining(0)).isEqualTo(1L);
    assertThat(WorkLimit.forRemaining(-5)).isEqualTo(1L);
    assertThat(WorkLimit.forRemaining(100)).isEqualTo(200L);
    assertThat(WorkLimit.forRemaining(Integer.MAX_VALUE)).isEqualTo(4_294_967_294L);
  }

  @Test
  void candidateInBoundsHandlesValidAndOutOfBoundsCandidates() {
    assertThat(WorkLimit.candidateInBounds(0, 0, 10, 5)).isTrue();
    assertThat(WorkLimit.candidateInBounds(5, 0, 10, 5)).isTrue();
    assertThat(WorkLimit.candidateInBounds(6, 0, 10, 5)).isFalse();
    assertThat(WorkLimit.candidateInBounds(-1, 0, 10, 5)).isFalse();
    assertThat(WorkLimit.candidateInBounds(2, 3, 10, 5)).isFalse();
  }

  @Test
  void candidateInBoundsDoesNotOverflowNearIntegerMax() {
    assertThat(WorkLimit.candidateInBounds(Integer.MAX_VALUE - 4, 0, Integer.MAX_VALUE, 8))
        .isFalse();
    assertThat(WorkLimit.candidateInBounds(Integer.MAX_VALUE - 8, 0, Integer.MAX_VALUE, 8))
        .isTrue();
  }

  @Test
  void addCandidateWorkAccumulatesCandidateCountAndMatchLength() {
    long work = 0;
    work = WorkLimit.addCandidateWork(work, 2, 8);
    assertThat(work).isEqualTo(2 * 8 + Long.BYTES);

    work = WorkLimit.addCandidateWork(work, 3, 8);
    assertThat(work).isEqualTo((2 * 8 + Long.BYTES) + (3 * 8 + Long.BYTES));
  }

  @Test
  void isExhaustedThresholdChecks() {
    assertThat(WorkLimit.isExhausted(99, 100)).isFalse();
    assertThat(WorkLimit.isExhausted(100, 100)).isTrue();
    assertThat(WorkLimit.isExhausted(101, 100)).isTrue();
  }
}
