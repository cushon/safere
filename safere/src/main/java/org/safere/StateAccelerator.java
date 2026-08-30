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

  /** Returns the accelerator policy for this state accelerator. */
  AcceleratorPolicy policy();

  /**
   * Fast-forwards to the next escape character in a self-loop state using pattern-matched
   * devirtualization.
   *
   * <p>Direct sealed-type pattern matching avoids {@code invokeinterface} dispatch overhead on hot
   * matching loops. HotSpot C2 does not automatically devirtualize megamorphic interface calls with
   * &ge; 3 implementations across the JVM lifecycle; switching over the sealed record subtypes here
   * allows C2 to inline the underlying {@link InputScanner} search primitives directly into caller
   * loops.
   */
  static int findNextEscape(
      StateAccelerator accelerator, InputScanner text, int fromIndex, int limit) {
    return switch (accelerator) {
      case SingleAsciiEscape single -> text.indexOfAscii(single.escape(), fromIndex, limit);
      case AsciiPairEscape pair -> text.indexOfAsciiPair(pair.c1(), pair.c2(), fromIndex, limit);
      case AsciiTripleEscape triple ->
          text.indexOfAsciiTriple(triple.c1(), triple.c2(), triple.c3(), fromIndex, limit);
    };
  }

  /**
   * Fast-forwards to the next ASCII escape or non-ASCII input unit for an ASCII-only automaton.
   *
   * <p>Unlike {@link #findNextEscape}, this must stop at non-ASCII input because the caller has not
   * modeled non-ASCII transitions in its transition table.
   */
  static int findNextAsciiOrNonAsciiEscape(
      StateAccelerator accelerator, InputScanner text, int fromIndex, int limit) {
    return switch (accelerator) {
      case SingleAsciiEscape single ->
          text.indexOfAsciiOrNonAscii(single.escape(), fromIndex, limit);
      case AsciiPairEscape pair ->
          text.indexOfAsciiPairOrNonAscii(pair.c1(), pair.c2(), fromIndex, limit);
      case AsciiTripleEscape triple ->
          text.indexOfAsciiTripleOrNonAscii(
              triple.c1(), triple.c2(), triple.c3(), fromIndex, limit);
    };
  }

  /** Accelerator for a single escape character (e.g. quote {@code '"'} or newline {@code '\n'}). */
  record SingleAsciiEscape(int escape) implements StateAccelerator {
    @Override
    public int findEscape(InputScanner text, int fromIndex, int limit) {
      return text.indexOfAscii(escape, fromIndex, limit);
    }

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.LITERAL;
    }
  }

  /** Accelerator for two escape characters (e.g. quote {@code '"'} and backslash {@code '\\'}). */
  record AsciiPairEscape(int c1, int c2) implements StateAccelerator {
    @Override
    public int findEscape(InputScanner text, int fromIndex, int limit) {
      return text.indexOfAsciiPair(c1, c2, fromIndex, limit);
    }

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.CHAR_CLASS;
    }
  }

  /** Accelerator for three escape characters (e.g. comma, semicolon, newline). */
  record AsciiTripleEscape(int c1, int c2, int c3) implements StateAccelerator {
    @Override
    public int findEscape(InputScanner text, int fromIndex, int limit) {
      return text.indexOfAsciiTriple(c1, c2, c3, fromIndex, limit);
    }

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.CHAR_CLASS;
    }
  }
}
