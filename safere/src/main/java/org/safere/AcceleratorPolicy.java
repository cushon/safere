// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/**
 * Immutable tuning policy and diagnostic metadata for start-position and DFA accelerators.
 *
 * @param minProfitableSkip Minimum skip distance (in chars or bytes) required for this accelerator
 *     to be profitable over direct scalar DFA execution.
 * @param strikeBudget Number of consecutive sub-threshold skips tolerated before declaring adaptive
 *     defeat.
 * @param isExactMatchCandidate Whether this accelerator identifies an exact candidate match start
 *     that can be validated directly.
 * @param strategy The diagnostic strategy associated with this accelerator, or {@code null} if
 *     none.
 */
record AcceleratorPolicy(
    int minProfitableSkip,
    int strikeBudget,
    boolean isExactMatchCandidate,
    MatchStrategy strategy) {

  // TODO: Conduct systematic empirical micro-benchmarking across diverse CPU architectures (x86
  // AVX-512/AVX2, ARM Neon) to precisely tune minimum profitable skip thresholds.
  // TODO: Measure defeat sensitivity across varied payload densities and evaluate whether dynamic
  // backoff or payload-length-proportional strike budgets outperform static constant budgets.

  /** Policy for vectorized literal and fixed-offset substring searches (AVX2 / SWAR). */
  static final AcceleratorPolicy LITERAL =
      new AcceleratorPolicy(16, 4, true, MatchStrategy.LITERAL);

  /** Policy for character class bitmap and range table scanning. */
  static final AcceleratorPolicy CHAR_CLASS =
      new AcceleratorPolicy(24, 3, false, MatchStrategy.CHARACTER_CLASS);

  /** Policy for line anchor ('^', '$') boundary acceleration. */
  static final AcceleratorPolicy LINE_ANCHOR = new AcceleratorPolicy(16, 3, false, null);

  /** Policy for vectorized multi-literal and Teddy multi-keyword scans. */
  static final AcceleratorPolicy VECTOR_MULTI_LITERAL =
      new AcceleratorPolicy(64, 4, true, MatchStrategy.LITERAL);

  /** Default fallback policy for generic or composite accelerators. */
  static final AcceleratorPolicy DEFAULT = new AcceleratorPolicy(32, 3, false, null);
}
