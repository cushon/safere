// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import org.safere.Pattern.FixedOffsetLiteral;
import org.safere.Pattern.StartAcceleration;

/**
 * Encapsulates pre-computed start acceleration search strategies for finding candidate match
 * positions in a {@link String}.
 */
sealed interface StringStartAccelerator {

  /**
   * Creates a {@link StringStartAccelerator} for the given pattern descriptor, or {@code null} if
   * no acceleration strategy applies.
   */
  static StringStartAccelerator create(StartDescriptor descriptor, boolean hasWordBoundary) {
    if (descriptor == null || !descriptor.hasStartAcceleration()) {
      return null;
    }
    if (descriptor.prefix() != null) {
      return new Literal(descriptor.prefix(), descriptor.prefixFoldCase());
    }
    if (descriptor.fixedOffsetLiteral() != null) {
      return new FixedOffset(descriptor.fixedOffsetLiteral(), descriptor.charClassPrefixAscii());
    }
    if (descriptor.charClassPrefixAscii() != null && !hasWordBoundary) {
      return new CharClass(descriptor.charClassPrefixAscii());
    }
    if (descriptor.lineAnchor() != null && !hasWordBoundary) {
      return new LineAnchor(descriptor.lineAnchor());
    }
    return null;
  }

  /**
   * Finds the next candidate match start position at or after {@code fromIndex}. Returns negative
   * if definitely not found.
   */
  int findCandidate(String text, int fromIndex, boolean unixLines);

  /** Returns the diagnostic strategy associated with this accelerator, or {@code null} if none. */
  MatchStrategy strategy();

  /**
   * Returns whether this accelerator identifies an exact candidate match start that can be directly
   * validated with a single anchored forward DFA pass.
   */
  boolean isExactMatchCandidate();

  final class Literal implements StringStartAccelerator {
    private final String prefix;
    private final boolean prefixFoldCase;

    Literal(String prefix, boolean prefixFoldCase) {
      this.prefix = prefix;
      this.prefixFoldCase = prefixFoldCase;
    }

    public String prefix() {
      return prefix;
    }

    public boolean prefixFoldCase() {
      return prefixFoldCase;
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
    public int findCandidate(String text, int fromIndex, boolean unixLines) {
      if (prefixFoldCase) {
        return Matcher.indexOfIgnoreCase(text, prefix, fromIndex);
      }
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record(Math.max(0, text.length() - fromIndex));
      }
      return text.indexOf(prefix, fromIndex);
    }
  }

  final class FixedOffset implements StringStartAccelerator {
    private final FixedOffsetLiteral fixedOffset;
    private final AsciiBitmap firstAscii;

    FixedOffset(FixedOffsetLiteral fixedOffset, AsciiBitmap firstAscii) {
      this.fixedOffset = fixedOffset;
      this.firstAscii = firstAscii;
    }

    public FixedOffsetLiteral fixedOffset() {
      return fixedOffset;
    }

    public AsciiBitmap firstAscii() {
      return firstAscii;
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
    public int findCandidate(String text, int fromIndex, boolean unixLines) {
      return nextFixedOffsetCandidate(text, fixedOffset, firstAscii, fromIndex);
    }

    private static int nextFixedOffsetCandidate(
        String text, FixedOffsetLiteral fixedOffsetLiteral, AsciiBitmap firstAscii, int fromIndex) {
      int minOffset = fixedOffsetLiteral.minOffset();
      if (minOffset > text.length() - fromIndex) {
        return -1;
      }
      int literalFrom = fromIndex + minOffset;
      int[] discreteOffsets = fixedOffsetLiteral.discreteOffsets();

      while (literalFrom <= text.length()) {
        int literalStart = text.indexOf(fixedOffsetLiteral.literal(), literalFrom);
        if (WorkCounterConfig.ENABLED) {
          int scanned =
              literalStart >= 0
                  ? literalStart - literalFrom + fixedOffsetLiteral.literal().length()
                  : text.length() - literalFrom;
          WorkCounter.record(Math.max(0, scanned));
        }
        if (literalStart < 0) {
          return -1;
        }
        if (discreteOffsets != null && discreteOffsets.length == 1 && firstAscii != null) {
          boolean matchFound = false;
          int earliestValid = -1;
          for (int offset : discreteOffsets) {
            int candidateStart = literalStart - offset;
            if (candidateStart >= fromIndex) {
              int first = candidateStart < text.length() ? text.charAt(candidateStart) : -1;
              if (first >= 0 && firstAscii.contains(first)) {
                matchFound = true;
                if (earliestValid < 0 || candidateStart < earliestValid) {
                  earliestValid = candidateStart;
                }
              }
            }
          }
          if (matchFound) {
            return earliestValid;
          }
          literalFrom = literalStart + 1;
          continue;
        }
        return Math.max(
            fromIndex,
            retreatByCodePoints(text, literalStart, fixedOffsetLiteral.maxOffset(), fromIndex));
      }
      return -1;
    }

    private static int retreatByCodePoints(String text, int index, int count, int minIndex) {
      int pos = index;
      while (count > 0 && pos > minIndex) {
        pos--;
        if (pos > minIndex
            && Character.isLowSurrogate(text.charAt(pos))
            && Character.isHighSurrogate(text.charAt(pos - 1))) {
          pos--;
        }
        count--;
      }
      return Math.max(minIndex, pos);
    }
  }

  final class CharClass implements StringStartAccelerator {
    private final AsciiBitmap asciiMap;
    private final boolean[] asciiTable;

    CharClass(AsciiBitmap asciiMap) {
      this.asciiMap = asciiMap;
      this.asciiTable = asciiMap.toBooleanArray();
    }

    public AsciiBitmap asciiMap() {
      return asciiMap;
    }

    @Override
    public MatchStrategy strategy() {
      return MatchStrategy.CHARACTER_CLASS;
    }

    @Override
    public boolean isExactMatchCandidate() {
      return false;
    }

    @Override
    public int findCandidate(String text, int fromIndex, boolean unixLines) {
      return indexOfCharClass(text, asciiTable, fromIndex);
    }

    private static int indexOfCharClass(String text, boolean[] asciiTable, int fromIndex) {
      for (int i = fromIndex; i < text.length(); i++) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        char ch = text.charAt(i);
        if (ch < 128 && asciiTable[ch]) {
          return i;
        }
      }
      return -1;
    }
  }

  final class LineAnchor implements StringStartAccelerator {
    private final StartAcceleration startAcceleration;

    LineAnchor(StartAcceleration startAcceleration) {
      this.startAcceleration = startAcceleration;
    }

    public StartAcceleration startAcceleration() {
      return startAcceleration;
    }

    @Override
    public MatchStrategy strategy() {
      return null;
    }

    @Override
    public boolean isExactMatchCandidate() {
      return false;
    }

    @Override
    public int findCandidate(String text, int fromIndex, boolean unixLines) {
      return nextAcceleratedStart(text, startAcceleration, fromIndex, unixLines);
    }

    private static int nextAcceleratedStart(
        String text, StartAcceleration acceleration, int fromIndex, boolean unixLines) {
      int start = Math.max(0, fromIndex);
      for (int i = start; i < text.length(); i++) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        if (matchesStartAcceleration(text, i, acceleration, unixLines)) {
          return i;
        }
        int cp = text.codePointAt(i);
        i += Character.charCount(cp) - 1;
      }
      return -1;
    }

    private static boolean matchesStartAcceleration(
        String text, int pos, StartAcceleration acceleration, boolean unixLines) {
      boolean lineStart = isBeginLine(text, pos, unixLines);
      boolean asciiStart = matchesAsciiStart(text, pos, acceleration.asciiStart);
      if (acceleration.requireLineStart) {
        return lineStart && (acceleration.asciiStart == null || asciiStart);
      }
      return (acceleration.allowLineStart && lineStart) || asciiStart;
    }

    private static boolean matchesAsciiStart(String text, int pos, AsciiBitmap asciiStart) {
      if (asciiStart == null || pos >= text.length()) {
        return false;
      }
      char ch = text.charAt(pos);
      return asciiStart.contains(ch);
    }

    private static boolean isBeginLine(String text, int pos, boolean unixLines) {
      if (pos == 0) {
        return !text.isEmpty();
      }
      if (pos >= text.length()) {
        return false;
      }
      char prev = text.charAt(pos - 1);
      if (unixLines) {
        return prev == '\n';
      }
      return prev == '\n'
          || prev == '\u0085'
          || prev == '\u2028'
          || prev == '\u2029'
          || (prev == '\r' && text.charAt(pos) != '\n');
    }
  }
}
