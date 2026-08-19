// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/**
 * Bounds checking and work-budget accounting for candidate-filtering string search kernels.
 *
 * <p>Enforces SafeRE's linear-time guarantee by bounding candidate verification work to {@code 2 *
 * remaining} bytes, falling back to linear Knuth-Morris-Pratt when false-candidate density exceeds
 * the budget.
 */
final class WorkLimit {

  /** Computes the maximum verification work allowed for a search over {@code remaining} bytes. */
  static long forRemaining(int remaining) {
    return Math.max(1L, (long) remaining * 2);
  }

  /** Checks whether candidate work has exhausted the budget. */
  static boolean isExhausted(long work, long workLimit) {
    return work >= workLimit;
  }

  /** Accumulates verification work and returns the updated total. */
  static long addCandidateWork(long currentWork, int candidateCount, int matchLength) {
    return currentWork + (long) candidateCount * matchLength + Long.BYTES;
  }

  /**
   * Checks whether a candidate match starting at {@code candidate} is within search bounds.
   * Overflow-safe for inputs approaching {@link Integer#MAX_VALUE}.
   */
  static boolean candidateInBounds(int candidate, int start, int length, int matchLength) {
    return candidate >= start && candidate <= length - matchLength;
  }

  private WorkLimit() {}
}
