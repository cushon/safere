// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.nio.charset.StandardCharsets;

/**
 * Encapsulates pre-computed start acceleration search strategies for finding candidate match
 * positions in a {@link Utf8InputScanner}.
 */
sealed interface Utf8StartAccelerator {

  /**
   * Creates a {@link Utf8StartAccelerator} for the given multi-anchor descriptor, or {@code null}
   * if no acceleration strategy applies.
   */
  static Utf8StartAccelerator create(MultiAnchorDescriptor descriptor, boolean hasWordBoundary) {
    return create(descriptor, hasWordBoundary, null);
  }

  /**
   * Creates a {@link Utf8StartAccelerator} for the given multi-anchor descriptor and reverse prefix
   * program, or {@code null} if no acceleration strategy applies.
   */
  static Utf8StartAccelerator create(
      MultiAnchorDescriptor descriptor, boolean hasWordBoundary, Prog reversePrefixProg) {
    if (descriptor == null) {
      return null;
    }
    if (descriptor.isReverseAnchor() && reversePrefixProg != null) {
      MultiAnchorDescriptor.Segment lastSeg = descriptor.trailingSegment();
      if (lastSeg.anchor() instanceof MultiAnchorDescriptor.Anchor.Single single) {
        Utf8StartAccelerator inner =
            single.foldCase()
                ? CaseInsensitiveLiteral.create(single.literal())
                : Literal.create(single.literal());
        if (inner != null) {
          Dfa revDfa = Dfa.createReverse(reversePrefixProg);
          if (revDfa != null) {
            return new ReverseAnchor(inner, revDfa, descriptor.minTotalLength());
          }
        }
      }
    }

    MultiAnchorDescriptor.Segment s0 = descriptor.firstSegment();
    MultiAnchorDescriptor.Gap g0 = s0.gap();
    MultiAnchorDescriptor.Anchor a0 = s0.anchor();

    if (g0.kind() == MultiAnchorDescriptor.GapKind.EMPTY) {
      if (descriptor.isStartAnchored()) {
        if (a0 instanceof MultiAnchorDescriptor.Anchor.Single single && !single.foldCase()) {
          return Literal.create(single.literal());
        }
      } else {
        if (a0 instanceof MultiAnchorDescriptor.Anchor.Single single) {
          return single.foldCase()
              ? CaseInsensitiveLiteral.create(single.literal())
              : Literal.create(single.literal());
        } else if (a0 instanceof MultiAnchorDescriptor.Anchor.Alternation alt && !alt.foldCase()) {
          if (alt.multiLiteral() != null
              && VectorScanProviders.multiLiteralProviderAvailable()
              && !hasWordBoundary) {
            return new MultiLiteral(alt.multiLiteral(), alt.teddyModel());
          } else if (alt.teddyModel() != null
              && VectorScanProviders.teddyProviderAvailable()
              && !hasWordBoundary) {
            return new Teddy(alt.teddyModel());
          } else if (!hasWordBoundary) {
            CharClassScanInfo scanInfo = a0.scanInfo();
            if (scanInfo != null) {
              return new CharClass(scanInfo);
            }
          }
        } else if (a0 instanceof MultiAnchorDescriptor.Anchor.CharClass cc
            && !hasWordBoundary
            && cc.scanInfo() != null) {
          return new CharClass(cc.scanInfo());
        }
      }
    }

    if (g0.kind() == MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT) {
      if (g0.maxLength() < Integer.MAX_VALUE && g0.maxLength() > 0) {
        String lit = a0.primaryLiteral();
        if (lit != null && !lit.isEmpty()) {
          byte[] utf8 = lit.getBytes(StandardCharsets.UTF_8);
          int[] failure = Pattern.literalFailure(utf8);
          int[] shifts = Pattern.literalShifts(utf8);
          CharClassScanInfo cc =
              g0.scanInfo() != null
                  ? g0.scanInfo()
                  : (g0.charClass() != null
                      ? CharClassScanInfo.fromAsciiBitmap(g0.charClass())
                      : null);
          return new FixedOffset(
              utf8, failure, shifts, g0.minLength(), g0.maxLength(), g0.discreteOffsets(), cc);
        }
      } else if (g0.maxLength() == Integer.MAX_VALUE) {
        CharClassScanInfo cc =
            g0.scanInfo() != null
                ? g0.scanInfo()
                : (g0.charClass() != null
                    ? CharClassScanInfo.fromAsciiBitmap(g0.charClass())
                    : null);
        if (cc != null) {
          Utf8StartAccelerator inner =
              a0 instanceof MultiAnchorDescriptor.Anchor.Single single
                  ? (single.foldCase()
                      ? CaseInsensitiveLiteral.create(single.literal())
                      : Literal.create(single.literal()))
                  : (a0 instanceof MultiAnchorDescriptor.Anchor.CharClass ccAnchor
                          && ccAnchor.scanInfo() != null
                      ? new CharClass(ccAnchor.scanInfo())
                      : null);
          if (inner != null) {
            return new LeadingExpansion(cc, g0.minLength(), -1, inner);
          }
        }
      }
    }

    if (a0 instanceof MultiAnchorDescriptor.Anchor.CharClass cc
        && !hasWordBoundary
        && cc.scanInfo() != null) {
      return new CharClass(cc.scanInfo());
    }

    return null;
  }

  /**
   * Finds the next candidate match start position at or after {@code pos} using pattern-matched
   * devirtualization.
   *
   * <p>Direct sealed-type pattern matching avoids {@code invokeinterface} dispatch overhead on hot
   * matching loops. HotSpot C2 does not automatically devirtualize megamorphic interface calls with
   * &ge; 3 implementations across the JVM lifecycle; switching over the sealed record subtypes here
   * allows C2 to inline candidate searches directly into caller loops.
   */
  static int findNextCandidate(
      Utf8StartAccelerator accelerator, Utf8InputScanner scanner, int pos) {
    return switch (accelerator) {
      case Literal lit -> lit.findCandidate(scanner, pos);
      case CaseInsensitiveLiteral cil -> cil.findCandidate(scanner, pos);
      case FixedOffset fo -> fo.findCandidate(scanner, pos);
      case CharClass cc -> cc.findCandidate(scanner, pos);
      case Teddy t -> t.findCandidate(scanner, pos);
      case MultiLiteral ml -> ml.findCandidate(scanner, pos);
      case LeadingExpansion le -> le.findCandidate(scanner, pos);
      case ReverseAnchor ra -> ra.findCandidate(scanner, pos);
    };
  }

  /** Returns the tuning and diagnostic policy for this accelerator. */
  default AcceleratorPolicy policy() {
    return AcceleratorPolicy.DEFAULT;
  }

  @SuppressWarnings("ArrayRecordComponent")
  record Literal(byte[] prefixUtf8, int[] prefixUtf8Failure, int[] prefixUtf8Shifts)
      implements Utf8StartAccelerator {

    static Literal create(String prefix) {
      byte[] utf8 = prefix.getBytes(StandardCharsets.UTF_8);
      return new Literal(utf8, Pattern.literalFailure(utf8), Pattern.literalShifts(utf8));
    }

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.LITERAL;
    }

    int findCandidate(Utf8InputScanner scanner, int fromIndex) {
      if (prefixUtf8 != null) {
        return scanner.indexOf(prefixUtf8, prefixUtf8Failure, prefixUtf8Shifts, fromIndex);
      }
      return fromIndex;
    }
  }

  @SuppressWarnings("ArrayRecordComponent")
  record CaseInsensitiveLiteral(
      String prefix, int[] failure, int anchorOffset, byte anchorLow, byte anchorHigh)
      implements Utf8StartAccelerator {

    static Utf8StartAccelerator create(String prefix) {
      for (int i = 0; i < prefix.length(); i++) {
        if (prefix.charAt(i) > 127) {
          return null;
        }
      }
      int anchorOffset = RarityOracle.rarestAsciiOffset(prefix, prefix.length());
      char anchor = prefix.charAt(anchorOffset);
      byte low = (byte) Ascii.toLowerCase(anchor);
      byte high = (byte) Ascii.toUpperCase(anchor);
      return new CaseInsensitiveLiteral(
          prefix, Ascii.ignoreCaseFailure(prefix), anchorOffset, low, high);
    }

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.LITERAL;
    }

    int findCandidate(Utf8InputScanner scanner, int fromIndex) {
      return scanner.indexOfIgnoreCase(
          prefix, failure, anchorOffset, anchorLow, anchorHigh, fromIndex);
    }
  }

  @SuppressWarnings("ArrayRecordComponent")
  record FixedOffset(
      byte[] utf8,
      int[] failure,
      int[] shifts,
      int minOffset,
      int maxOffset,
      int[] discreteOffsets,
      CharClassScanInfo charClassPrefix)
      implements Utf8StartAccelerator {

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.LITERAL;
    }

    int findCandidate(Utf8InputScanner scanner, int fromIndex) {
      return nextFixedOffsetCandidate(
          scanner,
          utf8,
          failure,
          shifts,
          minOffset,
          maxOffset,
          discreteOffsets,
          charClassPrefix,
          fromIndex);
    }

    private static int nextFixedOffsetCandidate(
        Utf8InputScanner scanner,
        byte[] utf8,
        int[] failure,
        int[] shifts,
        int minOffset,
        int maxOffset,
        int[] discreteOffsets,
        CharClassScanInfo charClassPrefix,
        int searchFrom) {
      int literalFrom = searchFrom + minOffset;
      while (literalFrom <= scanner.length()) {
        int literalStart = scanner.indexOf(utf8, failure, shifts, literalFrom);
        if (literalStart < 0) {
          return -1;
        }
        if (discreteOffsets != null && discreteOffsets.length == 1 && charClassPrefix != null) {
          int earliestValid = -1;
          for (int offset : discreteOffsets) {
            int candidateStart = literalStart - offset;
            if (candidateStart >= searchFrom) {
              int first =
                  candidateStart < scanner.length() ? scanner.codePointAt(candidateStart) : -1;
              if (first >= 0
                  && charClassPrefix.contains(first)
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
        return Math.max(searchFrom, scanner.retreatByCodePoints(literalStart, maxOffset));
      }
      return -1;
    }
  }

  record CharClass(CharClassScanInfo scanInfo) implements Utf8StartAccelerator {

    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.CHAR_CLASS;
    }

    int findCandidate(Utf8InputScanner scanner, int fromIndex) {
      if (scanInfo == null) {
        return fromIndex;
      }
      return switch (scanInfo) {
        case CharClassScanInfo.AsciiSmallSet smallSet -> {
          char[] chars = smallSet.chars();
          yield switch (chars.length) {
            case 1 -> scanner.indexOfAscii(chars[0], fromIndex, scanner.length());
            case 2 -> scanner.indexOfAsciiPair(chars[0], chars[1], fromIndex, scanner.length());
            case 3 ->
                scanner.indexOfCodePointClass(
                    smallSet.ranges(),
                    smallSet.bitmap0(),
                    smallSet.bitmap1(),
                    fromIndex,
                    scanner.length());
            default ->
                scanner.indexOfCodePointClass(
                    smallSet.ranges(),
                    smallSet.bitmap0(),
                    smallSet.bitmap1(),
                    fromIndex,
                    scanner.length());
          };
        }
        case CharClassScanInfo other ->
            scanner.indexOfCodePointClass(
                other.ranges(), other.bitmap0(), other.bitmap1(), fromIndex, scanner.length());
      };
    }
  }

  record Teddy(TeddyModel model) implements Utf8StartAccelerator {
    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.VECTOR_MULTI_LITERAL;
    }

    int findCandidate(Utf8InputScanner scanner, int fromIndex) {
      VectorScanProvider provider = VectorScanProviders.providerForTeddyLength(scanner.length());
      if (provider == null) {
        return fromIndex;
      }
      int idx =
          provider.indexOfTeddy(
              scanner.bytes(), scanner.offset(), scanner.length(), model, fromIndex);
      if (idx != VectorScanProvider.UNSUPPORTED) {
        return idx;
      }
      int len = scanner.length();
      int minLen = model.minLength();
      byte[] bytes = scanner.bytes();
      int offset = scanner.offset();
      for (int i = fromIndex; i <= len - minLen; i++) {
        for (String lit : model.literals()) {
          if (i + lit.length() <= len
              && Ascii.regionMatches(bytes, offset + i, lit, lit.length())) {
            return i;
          }
        }
      }
      return -1;
    }
  }

  record LeadingExpansion(
      CharClassScanInfo leadingClass,
      int minRepetition,
      int maxRepetition,
      Utf8StartAccelerator inner)
      implements Utf8StartAccelerator {

    @Override
    public AcceleratorPolicy policy() {
      return new AcceleratorPolicy(16, 4, false, inner.policy().strategy());
    }

    int findCandidate(Utf8InputScanner scanner, int fromIndex) {
      int searchPos = Math.max(0, fromIndex);
      int textLen = scanner.length();
      while (searchPos < textLen) {
        int innerMatch = Utf8StartAccelerator.findNextCandidate(inner, scanner, searchPos);
        if (innerMatch < 0) {
          return -1;
        }
        int start = innerMatch;
        int count = 0;
        while (start > fromIndex) {
          int cp = scanner.singleUnitCodePointBefore(start);
          int prevPos;
          if (cp >= 0) {
            prevPos = start - 1;
          } else {
            long decoded = scanner.decodeBackward(start);
            cp = InputScanner.codePoint(decoded);
            prevPos = InputScanner.position(decoded);
          }
          if (!leadingClass.contains(cp)) {
            break;
          }
          if (maxRepetition >= 0 && count + 1 > maxRepetition) {
            break;
          }
          count++;
          start = prevPos;
        }
        if (count >= minRepetition) {
          return start;
        }
        searchPos = innerMatch + 1;
      }
      return -1;
    }
  }

  record MultiLiteral(MultiLiteralInfo info, TeddyModel teddyModel)
      implements Utf8StartAccelerator {
    @Override
    public AcceleratorPolicy policy() {
      return AcceleratorPolicy.VECTOR_MULTI_LITERAL;
    }

    int findCandidate(Utf8InputScanner scanner, int fromIndex) {
      VectorScanProvider provider =
          VectorScanProviders.providerForMultiLiteralLength(scanner.length());
      if (provider != null) {
        int idx =
            provider.indexOfMultiLiteral(
                scanner.bytes(),
                scanner.offset(),
                scanner.length(),
                info.literals(),
                info.anchorChars(),
                info.anchorOffsets(),
                info.anchorRanges(),
                info.minLength(),
                teddyModel,
                fromIndex);
        if (idx != VectorScanProvider.UNSUPPORTED) {
          return idx;
        }
        return fromIndex;
      }
      return findScalar(scanner, fromIndex);
    }

    private int findScalar(Utf8InputScanner scanner, int fromIndex) {
      int len = scanner.length();
      int minLen = info.minLength();
      String[] literals = info.literals();
      byte[] bytes = scanner.bytes();
      int offset = scanner.offset();
      for (int i = fromIndex; i <= len - minLen; i++) {
        for (String lit : literals) {
          if (i + lit.length() <= len) {
            if (WorkCounterConfig.ENABLED) {
              WorkCounter.record(lit.length());
            }
            if (Ascii.regionMatches(bytes, offset + i, lit, lit.length())) {
              return i;
            }
          }
        }
      }
      return -1;
    }
  }

  record ReverseAnchor(Utf8StartAccelerator anchor, Dfa reverseDfa, int minLength)
      implements Utf8StartAccelerator {

    @Override
    public AcceleratorPolicy policy() {
      return new AcceleratorPolicy(16, 4, false, anchor.policy().strategy());
    }

    int findCandidate(Utf8InputScanner scanner, int pos) {
      int searchPos = Math.max(0, pos);
      int textLen = scanner.length();
      int minBound = Math.max(0, pos);
      while (searchPos <= textLen - minLength) {
        int anchorPos = Utf8StartAccelerator.findNextCandidate(anchor, scanner, searchPos);
        if (anchorPos < 0) {
          return -1;
        }
        Dfa.SearchResult revResult =
            reverseDfa.doSearchReverse(scanner, anchorPos, minBound, true, true);
        if (revResult != null && revResult.matched() && !revResult.ambiguous()) {
          return revResult.pos();
        }
        searchPos = anchorPos + 1;
      }
      return -1;
    }
  }
}
