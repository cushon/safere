// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import org.safere.Pattern.CharClassMatchInfo;

/**
 * Immutable descriptor capturing pre-computed match-execution fast-path metadata extracted from a
 * regular expression AST (Tier 2/3 execution acceleration).
 *
 * @param literalMatch full literal string for patterns that are entirely literal (no
 *     metacharacters, no quantifiers, no alternation), or {@code null}
 * @param singleCharClass precomputed scan data for patterns that are exactly one character class
 *     (e.g., {@code \p{javaLetter}}), allowing {@code find()} to scan directly without the full
 *     engine cascade
 * @param charClassMatch precomputed character class data for the "repeated character class" fast
 *     path in {@code matches()} (e.g., {@code [a-zA-Z]+}, {@code \d*}), allowing {@code matches()}
 *     to bypass the engine cascade with a tight scanning loop
 */
record MatchDescriptor(
    String literalMatch,
    boolean literalFoldCase,
    CharClassScanInfo singleCharClass,
    CharClassMatchInfo charClassMatch,
    int minMatchLength,
    ClassHashChain classHashChain,
    ShiftDfa shiftDfa,
    MultiAnchorDescriptor multiAnchor) {

  static final MatchDescriptor NONE =
      new MatchDescriptor(null, false, null, null, 0, null, null, null);

  MatchDescriptor(
      String literalMatch,
      boolean literalFoldCase,
      CharClassScanInfo singleCharClass,
      CharClassMatchInfo charClassMatch,
      int minMatchLength,
      ShiftDfa shiftDfa,
      MultiAnchorDescriptor multiAnchor) {
    this(
        literalMatch,
        literalFoldCase,
        singleCharClass,
        charClassMatch,
        minMatchLength,
        compileClassHashChain(literalMatch, literalFoldCase),
        shiftDfa,
        multiAnchor);
  }

  private static ClassHashChain compileClassHashChain(String literal, boolean foldCase) {
    return literal != null && foldCase ? ClassHashChain.compileCaseInsensitive(literal) : null;
  }

  boolean hasFastPath() {
    return literalMatch != null
        || singleCharClass != null
        || charClassMatch != null
        || multiAnchor != null
        || minMatchLength > 0
        || shiftDfa != null;
  }

  boolean hasFindFastPath() {
    return literalMatch != null
        || singleCharClass != null
        || (multiAnchor != null && multiAnchor.isExecutableChain());
  }
}
