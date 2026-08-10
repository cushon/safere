// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import org.safere.Pattern.CharClassScanInfo;
import org.safere.Pattern.FixedOffsetLiteral;

/**
 * Encapsulates pre-computed start acceleration search strategies for finding candidate match
 * positions in a {@link Utf8InputScanner}.
 */
sealed interface Utf8StartAccelerator {

  /**
   * Creates a {@link Utf8StartAccelerator} for the given pattern metadata, or {@code null} if no
   * acceleration strategy applies.
   */
  static Utf8StartAccelerator create(
      byte[] prefixUtf8,
      boolean prefixFoldCase,
      int[] prefixUtf8Failure,
      int[] prefixUtf8Shifts,
      FixedOffsetLiteral fixedOffsetLiteral,
      CharClassScanInfo charClassPrefixScanInfo,
      boolean hasWordBoundary) {
    if (prefixUtf8 != null && !prefixFoldCase) {
      return new Literal(prefixUtf8, prefixUtf8Failure, prefixUtf8Shifts);
    }
    if (fixedOffsetLiteral != null) {
      return new FixedOffset(fixedOffsetLiteral);
    }
    if (charClassPrefixScanInfo != null && !hasWordBoundary) {
      return new CharClass(charClassPrefixScanInfo);
    }
    return null;
  }

  /**
   * Finds the next candidate match start position at or after {@code fromIndex}. Returns negative
   * if definitely not found.
   */
  int findCandidate(Pattern pattern, Utf8InputScanner scanner, int fromIndex);

  /** Returns the diagnostic strategy associated with this accelerator, or {@code null} if none. */
  MatchStrategy diagnosticStrategy();

  /**
   * Returns whether this accelerator identifies an exact candidate match start that can be directly
   * validated with a single anchored forward DFA pass.
   */
  boolean isExactMatchCandidate();

  final class Literal implements Utf8StartAccelerator {
    private final byte[] prefixUtf8;
    private final int[] prefixUtf8Failure;
    private final int[] prefixUtf8Shifts;

    Literal(byte[] prefixUtf8, int[] prefixUtf8Failure, int[] prefixUtf8Shifts) {
      this.prefixUtf8 = prefixUtf8;
      this.prefixUtf8Failure = prefixUtf8Failure;
      this.prefixUtf8Shifts = prefixUtf8Shifts;
    }

    public byte[] prefixUtf8() {
      return prefixUtf8;
    }

    public int[] prefixUtf8Failure() {
      return prefixUtf8Failure;
    }

    public int[] prefixUtf8Shifts() {
      return prefixUtf8Shifts;
    }

    @Override
    public MatchStrategy diagnosticStrategy() {
      return MatchStrategy.LITERAL;
    }

    @Override
    public boolean isExactMatchCandidate() {
      return true;
    }

    @Override
    public int findCandidate(Pattern pattern, Utf8InputScanner scanner, int fromIndex) {
      if (prefixUtf8 != null) {
        return scanner.indexOf(prefixUtf8, prefixUtf8Failure, prefixUtf8Shifts, fromIndex);
      }
      return fromIndex;
    }
  }

  final class FixedOffset implements Utf8StartAccelerator {
    private final FixedOffsetLiteral fixedOffset;

    FixedOffset(FixedOffsetLiteral fixedOffset) {
      this.fixedOffset = fixedOffset;
    }

    public FixedOffsetLiteral fixedOffset() {
      return fixedOffset;
    }

    @Override
    public MatchStrategy diagnosticStrategy() {
      return MatchStrategy.LITERAL;
    }

    @Override
    public boolean isExactMatchCandidate() {
      return true;
    }

    @Override
    public int findCandidate(Pattern pattern, Utf8InputScanner scanner, int fromIndex) {
      return pattern.nextFixedOffsetCandidate(scanner, fromIndex);
    }
  }

  final class CharClass implements Utf8StartAccelerator {
    private final CharClassScanInfo scanInfo;

    CharClass(CharClassScanInfo scanInfo) {
      this.scanInfo = scanInfo;
    }

    public CharClassScanInfo scanInfo() {
      return scanInfo;
    }

    @Override
    public MatchStrategy diagnosticStrategy() {
      return MatchStrategy.CHARACTER_CLASS;
    }

    @Override
    public boolean isExactMatchCandidate() {
      return false;
    }

    @Override
    public int findCandidate(Pattern pattern, Utf8InputScanner scanner, int fromIndex) {
      if (pattern.prog().hasWordBoundary()) {
        return fromIndex;
      }
      if (scanInfo != null) {
        return scanner.indexOfCodePointClass(
            scanInfo.ranges, scanInfo.bitmap0, scanInfo.bitmap1, fromIndex);
      }
      return fromIndex;
    }
  }
}
