// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/**
 * Fast-forward scanner for DFA self-loop states.
 *
 * <p>When a DFA state transitions to itself on almost all characters, this accelerator uses SWAR /
 * vector intrinsics to scan directly to the next escape character that could cause a state
 * transition change.
 */
sealed interface StateAccelerator {

  /**
   * Finds the next index at or after {@code fromIndex} and strictly less than {@code limit}
   * containing an escape character.
   *
   * @return the index of the escape character, or {@code -1} if no escape character occurs before
   *     {@code limit}.
   */
  int findEscape(InputScanner text, int fromIndex, int limit);

  /** Accelerator for a single escape character (e.g. quote {@code '"'} or newline {@code '\n'}). */
  record SingleAsciiEscape(int escape) implements StateAccelerator {
    @Override
    public int findEscape(InputScanner text, int fromIndex, int limit) {
      return text.indexOfAscii(escape, fromIndex, limit);
    }
  }

  /** Accelerator for two escape characters (e.g. quote {@code '"'} and backslash {@code '\\'}). */
  record AsciiPairEscape(int c1, int c2) implements StateAccelerator {
    @Override
    public int findEscape(InputScanner text, int fromIndex, int limit) {
      return text.indexOfAsciiPair(c1, c2, fromIndex, limit);
    }
  }

  /** Accelerator for three escape characters (e.g. comma, semicolon, newline). */
  record AsciiTripleEscape(int c1, int c2, int c3) implements StateAccelerator {
    @Override
    public int findEscape(InputScanner text, int fromIndex, int limit) {
      return text.indexOfAsciiTriple(c1, c2, c3, fromIndex, limit);
    }
  }
}
