// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.nio.charset.StandardCharsets;
import org.safere.Pattern.CharClassScanInfo;
import org.safere.Pattern.FixedOffsetLiteral;

/**
 * Encapsulates pre-computed start acceleration search strategies for finding candidate match
 * positions in a {@link Utf8InputScanner}.
 */
sealed interface Utf8StartAccelerator {

  /**
   * Creates a {@link Utf8StartAccelerator} for the given pattern descriptor, or {@code null} if no
   * acceleration strategy applies.
   */
  static Utf8StartAccelerator create(StartDescriptor descriptor, boolean hasWordBoundary) {
    if (descriptor == null || !descriptor.hasStartAcceleration()) {
      return null;
    }
    if (descriptor.prefix() != null && !descriptor.prefixFoldCase()) {
      return Literal.create(descriptor.prefix());
    }
    if (descriptor.fixedOffsetLiteral() != null) {
      return new FixedOffset(descriptor.fixedOffsetLiteral(), descriptor.charClassPrefixAscii());
    }
    if (descriptor.charClassPrefixAscii() != null && !hasWordBoundary) {
      CharClassScanInfo scanInfo =
          Pattern.buildAsciiClassScanInfo(descriptor.charClassPrefixAscii());
      if (scanInfo != null) {
        return new CharClass(scanInfo);
      }
    }
    return null;
  }

  /**
   * Finds the next candidate match start position at or after {@code fromIndex}. Returns negative
   * if definitely not found.
   */
  int findCandidate(Utf8InputScanner scanner, int fromIndex);

  /** Returns the diagnostic strategy associated with this accelerator, or {@code null} if none. */
  MatchStrategy strategy();

  /**
   * Returns whether this accelerator identifies an exact candidate match start that can be directly
   * validated with a single anchored forward DFA pass.
   */
  boolean isExactMatchCandidate();

  @SuppressWarnings("ArrayRecordComponent")
  record Literal(byte[] prefixUtf8, int[] prefixUtf8Failure, int[] prefixUtf8Shifts)
      implements Utf8StartAccelerator {

    static Literal create(String prefix) {
      byte[] utf8 = prefix.getBytes(StandardCharsets.UTF_8);
      return new Literal(utf8, Pattern.literalFailure(utf8), Pattern.literalShifts(utf8));
    }

    @Override
    public MatchStrategy strategy() {
      return MatchStrategy.LITERAL;
    }

    @Override
    public boolean isExactMatchCandidate() {
      return true;
    }

    @Override
    public int findCandidate(Utf8InputScanner scanner, int fromIndex) {
      if (prefixUtf8 != null) {
        return scanner.indexOf(prefixUtf8, prefixUtf8Failure, prefixUtf8Shifts, fromIndex);
      }
      return fromIndex;
    }
  }

  @SuppressWarnings("ArrayRecordComponent")
  record FixedOffset(FixedOffsetLiteral fixedOffset, boolean[] charClassPrefixAscii)
      implements Utf8StartAccelerator {

    @Override
    public MatchStrategy strategy() {
      return MatchStrategy.LITERAL;
    }

    @Override
    public boolean isExactMatchCandidate() {
      return true;
    }

    @Override
    public int findCandidate(Utf8InputScanner scanner, int fromIndex) {
      return nextFixedOffsetCandidate(scanner, fixedOffset, charClassPrefixAscii, fromIndex);
    }

    private static int nextFixedOffsetCandidate(
        Utf8InputScanner scanner,
        FixedOffsetLiteral fixedOffsetLiteral,
        boolean[] charClassPrefixAscii,
        int searchFrom) {
      int literalFrom = searchFrom + fixedOffsetLiteral.minOffset();
      int[] discreteOffsets = fixedOffsetLiteral.discreteOffsets();
      while (literalFrom <= scanner.length()) {
        int literalStart =
            scanner.indexOf(
                fixedOffsetLiteral.utf8(),
                fixedOffsetLiteral.failure(),
                fixedOffsetLiteral.shifts(),
                literalFrom);
        if (literalStart < 0) {
          return -1;
        }
        if (discreteOffsets != null
            && discreteOffsets.length == 1
            && charClassPrefixAscii != null) {
          int earliestValid = -1;
          for (int offset : discreteOffsets) {
            int candidateStart = literalStart - offset;
            if (candidateStart >= searchFrom) {
              int first = scanner.asciiAt(candidateStart);
              if (first >= 0
                  && first < charClassPrefixAscii.length
                  && charClassPrefixAscii[first]
                  && (earliestValid < 0 || candidateStart < earliestValid)) {
                earliestValid = candidateStart;
              }
            }
          }
          if (earliestValid >= 0) {
            return earliestValid;
          }
          literalFrom = literalStart + 1;
          continue;
        }
        return Math.max(
            searchFrom, scanner.retreatByCodePoints(literalStart, fixedOffsetLiteral.maxOffset()));
      }
      return -1;
    }
  }

  record CharClass(CharClassScanInfo scanInfo) implements Utf8StartAccelerator {

    @Override
    public MatchStrategy strategy() {
      return MatchStrategy.CHARACTER_CLASS;
    }

    @Override
    public boolean isExactMatchCandidate() {
      return false;
    }

    @Override
    public int findCandidate(Utf8InputScanner scanner, int fromIndex) {
      if (scanInfo != null) {
        return scanner.indexOfCodePointClass(
            scanInfo.ranges, scanInfo.bitmap0, scanInfo.bitmap1, fromIndex);
      }
      return fromIndex;
    }
  }
}
