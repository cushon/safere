// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Stable reason for a strategy decision. */
public enum StrategyReason {
  INPUT_TOO_SMALL,
  INPUT_TOO_LARGE,
  CAPTURES_REQUIRED,
  AUTHORITATIVE_BOUNDS_REQUIRED,
  EXACT_NULLABLE_LOOP_SEMANTICS_REQUIRED,
  /**
   * The lazy DFA filled its state cache and declined to clear it, so the operation fell back to a
   * slower engine. The DFA clears and rebuilds a full cache while doing so is productive; it
   * declines only when recent cache generations covered too little input per cached state.
   */
  DFA_BUDGET_EXCEEDED,
  WORK_BUDGET_EXCEEDED,
  OPTIMIZED_PATH_DISABLED
}
