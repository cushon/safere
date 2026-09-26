// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/**
 * EXPERIMENT ONLY, never to be merged: system-property knobs for isolating the PR #938 aarch64
 * String regressions. The values are {@code static final}, so C2 folds them as constants once this
 * class is initialized. With no properties set, behavior is identical to {@code 49ed2739}.
 */
final class Pr938Exp {
  private Pr938Exp() {}

  /** Chars probed before the per-member searches in the start accelerator (default 8). */
  static final int CANDIDATE_PROBE_CHARS = Integer.getInteger("safere.exp.candidateProbe", 8);

  /** Chars probed before the per-member searches in the reject prefilter (default 16). */
  static final int REJECT_PROBE_CHARS = Integer.getInteger("safere.exp.rejectProbe", 16);

  /** Skips the small-set String reject check for searches that do not start at 0. */
  static final boolean REJECT_ONLY_AT_START = Boolean.getBoolean("safere.exp.rejectOnlyAtStart");

  /**
   * Declines String start acceleration for a small set with a non-ASCII member (the shape PR #938
   * adds), so the DFA steps the start state as on {@code main}. The reject prefilter is unchanged.
   */
  static final boolean NO_UNICODE_SMALL_SET_START =
      Boolean.getBoolean("safere.exp.noUnicodeSmallSetStart");

  static {
    if (System.getProperties().stringPropertyNames().stream()
        .anyMatch(k -> k.startsWith("safere.exp."))) {
      System.err.println(
          "Pr938Exp knobs: candidateProbe="
              + CANDIDATE_PROBE_CHARS
              + " rejectProbe="
              + REJECT_PROBE_CHARS
              + " rejectOnlyAtStart="
              + REJECT_ONLY_AT_START
              + " noUnicodeSmallSetStart="
              + NO_UNICODE_SMALL_SET_START);
    }
  }
}
