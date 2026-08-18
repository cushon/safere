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
    if (descriptor.multiLiteral() != null
        && !hasWordBoundary
        && VectorScanProviders.providerForLength(64) != null) {
      return new MultiLiteral(descriptor.multiLiteral());
    }
    if (descriptor.teddyModel() != null
        && !hasWordBoundary
        && VectorScanProviders.providerForLength(64) != null) {
      return new Teddy(descriptor.teddyModel(), descriptor.charClassPrefixAscii());
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

  /**
   * Finds the next candidate match start position at or after {@code fromIndex} using
   * pattern-matched devirtualization.
   *
   * <p>Direct sealed-type pattern matching avoids {@code invokeinterface} dispatch overhead on hot
   * matching loops. HotSpot C2 does not automatically devirtualize megamorphic interface calls with
   * &ge; 3 implementations across the JVM lifecycle; switching over the sealed subtypes here allows
   * C2 to inline candidate searches directly into caller loops.
   */
  static int findNextCandidate(
      StringStartAccelerator accelerator, String text, int fromIndex, boolean unixLines) {
    return switch (accelerator) {
      case Literal lit -> lit.findCandidate(text, fromIndex, unixLines);
      case FixedOffset fo -> fo.findCandidate(text, fromIndex, unixLines);
      case CharClass cc -> cc.findCandidate(text, fromIndex, unixLines);
      case LineAnchor la -> la.findCandidate(text, fromIndex, unixLines);
      case MultiLiteral ml -> ml.findCandidate(text, fromIndex, unixLines);
      case Teddy t -> t.findCandidate(text, fromIndex, unixLines);
    };
  }

  /** Returns the tuning and diagnostic policy for this accelerator. */
  default AcceleratorPolicy policy() {
    return AcceleratorPolicy.DEFAULT;
  }

  final class Literal implements StringStartAccelerator {
    private final String prefix;
    private final boolean prefixFoldCase;
    private final int[] failure;
    private final int anchorOffset;
    private final char anchorLow;
    private final char anchorHigh;

    Literal(String prefix, boolean prefixFoldCase) {
      this.prefix = prefix;
      this.prefixFoldCase = prefixFoldCase;
      if (prefixFoldCase && prefix != null && !prefix.isEmpty()) {
        this.failure = Ascii.ignoreCaseFailure(prefix);
        this.anchorOffset = RarityOracle.rarestAsciiOffset(prefix, prefix.length());
        char anchor = prefix.charAt(anchorOffset);
        this.anchorLow = Ascii.toLowerCase(anchor);
        this.anchorHigh = Ascii.toUpperCase(anchor);
      } else {
        this.failure = null;
        this.anchorOffset = 0;
        this.anchorLow = 0;
        this.anchorHigh = 0;
      }
    }

    public String prefix() {
      return prefix;
    }

    public boolean prefixFoldCase() {
      return prefixFoldCase;
    }

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.LITERAL;
    }

    @Override
    public int findCandidate(String text, int fromIndex, boolean unixLines) {
      if (prefixFoldCase) {
        VectorScanProvider provider = VectorScanProviders.providerForLength(text.length());
        if (provider != null) {
          int vectorIndex = provider.indexOfIgnoreCase(text, prefix, fromIndex);
          if (vectorIndex != VectorScanProvider.UNSUPPORTED) {
            return vectorIndex;
          }
        }
        return Matcher.indexOfIgnoreCase(
            text, prefix, failure, anchorOffset, anchorLow, anchorHigh, fromIndex);
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
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.LITERAL;
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
    private final int[] ranges;

    CharClass(AsciiBitmap asciiMap) {
      this.asciiMap = asciiMap;
      this.asciiTable = asciiMap.toBooleanArray();
      this.ranges = asciiMap.toRanges();
    }

    public AsciiBitmap asciiMap() {
      return asciiMap;
    }

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.CHAR_CLASS;
    }

    @Override
    public int findCandidate(String text, int fromIndex, boolean unixLines) {
      VectorScanProvider provider = VectorScanProviders.providerForLength(text.length());
      if (provider != null) {
        int idx = provider.indexOfAsciiClass(text, ranges, fromIndex);
        if (idx != VectorScanProvider.UNSUPPORTED) {
          return idx;
        }
      }
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
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.LINE_ANCHOR;
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

  final class MultiLiteral implements StringStartAccelerator {
    private final MultiLiteralInfo info;

    MultiLiteral(MultiLiteralInfo info) {
      this.info = info;
    }

    public MultiLiteralInfo info() {
      return info;
    }

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.VECTOR_MULTI_LITERAL;
    }

    @Override
    public int findCandidate(String text, int fromIndex, boolean unixLines) {
      VectorScanProvider provider = VectorScanProviders.providerForLength(text.length());
      if (provider != null) {
        int idx =
            provider.indexOfMultiLiteral(
                text,
                info.literals(),
                info.anchorChars(),
                info.anchorOffsets(),
                info.minLength(),
                fromIndex);
        if (idx != VectorScanProvider.UNSUPPORTED) {
          return idx;
        }
      }
      return findScalar(text, fromIndex);
    }

    private int findScalar(String text, int fromIndex) {
      int len = text.length();
      int minLen = info.minLength();
      String[] literals = info.literals();
      for (int i = fromIndex; i <= len - minLen; i++) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        for (String lit : literals) {
          if (i + lit.length() <= len && text.startsWith(lit, i)) {
            return i;
          }
        }
      }
      return -1;
    }
  }

  final class Teddy implements StringStartAccelerator {
    private final TeddyModel model;
    private final AsciiBitmap ccPrefixAscii;
    private final boolean[] asciiTable;

    Teddy(TeddyModel model, AsciiBitmap ccPrefixAscii) {
      this.model = model;
      this.ccPrefixAscii = ccPrefixAscii;
      this.asciiTable = ccPrefixAscii != null ? ccPrefixAscii.toBooleanArray() : null;
    }

    public TeddyModel model() {
      return model;
    }

    public AsciiBitmap ccPrefixAscii() {
      return ccPrefixAscii;
    }

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.VECTOR_MULTI_LITERAL;
    }

    @Override
    public int findCandidate(String text, int fromIndex, boolean unixLines) {
      VectorScanProvider provider = VectorScanProviders.providerForLength(text.length());
      if (provider != null) {
        int idx = provider.indexOfTeddy(text, model, fromIndex);
        if (idx != VectorScanProvider.UNSUPPORTED) {
          return idx;
        }
      }
      if (asciiTable != null) {
        return indexOfCharClass(text, asciiTable, fromIndex);
      }
      return findScalar(text, fromIndex);
    }

    private int findScalar(String text, int fromIndex) {
      int len = text.length();
      int minLen = model.minLength();
      String[] literals = model.literals();
      for (int i = fromIndex; i <= len - minLen; i++) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        for (String lit : literals) {
          if (i + lit.length() <= len && text.startsWith(lit, i)) {
            return i;
          }
        }
      }
      return -1;
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
}
