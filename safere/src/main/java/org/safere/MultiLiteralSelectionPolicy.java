// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Chooses between the low-overhead multi-broadcast scanner and Teddy. */
final class MultiLiteralSelectionPolicy {
  /**
   * Limit adaptation to a small prefix so its bookkeeping does not become steady-state overhead.
   */
  private static final int OBSERVATION_BYTES = 256;

  /**
   * Prefer Teddy when estimated full-literal verification consumes at least half as much work as
   * scanning the observed bytes. This crossover is benchmark-tuned: multi-broadcast wins on sparse
   * candidates, while Teddy's extra filtering wins once candidate verification becomes this
   * frequent.
   */
  private static final int TEDDY_CROSSOVER_PERCENT = 50;

  static boolean shouldObserve(int observedBytes) {
    return observedBytes < OBSERVATION_BYTES;
  }

  static boolean prefersTeddy(long estimatedVerificationBytes, int observedBytes) {
    return observedBytes > 0
        && estimatedVerificationBytes * 100 >= (long) observedBytes * TEDDY_CROSSOVER_PERCENT;
  }

  private MultiLiteralSelectionPolicy() {}
}
