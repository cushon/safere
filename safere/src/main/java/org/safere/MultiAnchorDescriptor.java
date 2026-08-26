// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Immutable descriptor capturing pre-computed multi-anchor sequence metadata extracted from a
 * regular expression AST. Enables divide-and-conquer execution by pinning match positions around
 * fast SIMD anchors and verifying intermediate gaps.
 */
@SuppressWarnings("ArrayRecordComponent")
record MultiAnchorDescriptor(
    Segment[] segments,
    Gap trailingGap,
    int[] checkOrder,
    int minTotalLength,
    boolean isStartAnchored,
    boolean isEndAnchored) {

  record Segment(Gap gap, Anchor anchor) {
    Segment {
      Objects.requireNonNull(gap, "gap");
      Objects.requireNonNull(anchor, "anchor");
    }

    int findCandidate(String text, int searchFrom) {
      int p = anchor.findNext(text, searchFrom + gap.minLength());
      if (p < 0) {
        return -1;
      }
      return gap.matchBackward(text, p, searchFrom);
    }

    int findCandidate(Utf8InputScanner scanner, int searchFrom) {
      int p = anchor.findNext(scanner, searchFrom + gap.minLength());
      if (p < 0) {
        return -1;
      }
      return gap.matchBackward(scanner, p, searchFrom);
    }
  }

  MultiAnchorDescriptor {
    Objects.requireNonNull(segments, "segments");
    Objects.requireNonNull(trailingGap, "trailingGap");
    Objects.requireNonNull(checkOrder, "checkOrder");
    if (segments.length == 0) {
      throw new IllegalArgumentException("segments cannot be empty");
    }
  }

  MultiAnchorDescriptor(
      Anchor[] anchors,
      Gap[] gaps,
      int[] checkOrder,
      int minTotalLength,
      boolean isStartAnchored,
      boolean isEndAnchored) {
    this(
        createSegments(anchors, gaps),
        gaps[gaps.length - 1],
        checkOrder,
        minTotalLength,
        isStartAnchored,
        isEndAnchored);
  }

  MultiAnchorDescriptor(
      Segment[] segments, Gap trailingGap, int minTotalLength, boolean isStartAnchored) {
    this(
        segments,
        trailingGap,
        defaultOrder(segments.length),
        minTotalLength,
        isStartAnchored,
        false);
  }

  private static int[] defaultOrder(int len) {
    int[] order = new int[len];
    for (int i = 0; i < len; i++) {
      order[i] = i;
    }
    return order;
  }

  boolean hasStartAcceleration() {
    Segment s0 = segments[0];
    Gap g0 = s0.gap();
    Anchor a0 = s0.anchor();
    if (g0.kind() == GapKind.EMPTY) {
      if (isStartAnchored) {
        return a0 instanceof Anchor.Single single && !single.foldCase();
      }
      return a0 instanceof Anchor.Single
          || (a0 instanceof Anchor.Alternation alt && !alt.foldCase())
          || (a0 instanceof Anchor.CharClass cc && cc.scanInfo() != null);
    }
    if (g0.kind() == GapKind.BOUNDED_CLASS_REPEAT) {
      return true;
    }
    return isReverseAnchor();
  }

  private static Segment[] createSegments(Anchor[] anchors, Gap[] gaps) {
    Objects.requireNonNull(anchors, "anchors");
    Objects.requireNonNull(gaps, "gaps");
    if (gaps.length != anchors.length + 1) {
      throw new IllegalArgumentException(
          "gaps.length ("
              + gaps.length
              + ") must equal anchors.length + 1 ("
              + (anchors.length + 1)
              + ")");
    }
    Segment[] segments = new Segment[anchors.length];
    for (int i = 0; i < anchors.length; i++) {
      segments[i] = new Segment(gaps[i], anchors[i]);
    }
    return segments;
  }

  Segment firstSegment() {
    return segments[0];
  }

  Segment trailingSegment() {
    return segments[segments.length - 1];
  }

  Gap leadingGap() {
    return segments[0].gap();
  }

  Anchor[] anchors() {
    Anchor[] result = new Anchor[segments.length];
    for (int i = 0; i < segments.length; i++) {
      result[i] = segments[i].anchor();
    }
    return result;
  }

  Gap[] gaps() {
    Gap[] result = new Gap[segments.length + 1];
    for (int i = 0; i < segments.length; i++) {
      result[i] = segments[i].gap();
    }
    result[segments.length] = trailingGap;
    return result;
  }

  Gap gapBetween(int leftAnchorIndex, int rightAnchorIndex) {
    if (rightAnchorIndex != leftAnchorIndex + 1) {
      throw new IllegalArgumentException(
          "rightAnchorIndex ("
              + rightAnchorIndex
              + ") must equal leftAnchorIndex + 1 ("
              + (leftAnchorIndex + 1)
              + ")");
    }
    return segments[rightAnchorIndex].gap();
  }

  Anchor primaryAnchor() {
    return segments[checkOrder[0]].anchor();
  }

  int numSegments() {
    return segments.length;
  }

  boolean isSingle() {
    return segments.length == 1;
  }

  boolean isReverseAnchor() {
    return (isEndAnchored
            || trailingGap.kind() == GapKind.LINE_END
            || trailingGap.kind() == GapKind.EMPTY)
        && segments[0].gap().maxLength() == Integer.MAX_VALUE
        && (segments[0].gap().kind() == GapKind.ANY_STAR
            || segments[0].gap().kind() == GapKind.SINGLE_LINE_ANY_STAR);
  }

  boolean isExecutableChain() {
    if (segments.length < 2 || isReverseAnchor()) {
      return false;
    }
    for (Segment segment : segments) {
      if (segment.gap().kind() == GapKind.BOUNDED_CLASS_REPEAT) {
        return false;
      }
      if (!(segment.anchor() instanceof Anchor.Single)
          && !(segment.anchor() instanceof Anchor.Alternation)) {
        return false;
      }
    }
    return true;
  }

  RejectDescriptor.InfixSequence toInfixSequence(String excludePrefix, String excludeSuffix) {
    List<String> list = new ArrayList<>();
    for (Segment segment : segments) {
      Anchor anchor = segment.anchor();
      if (!anchor.foldCase() && anchor instanceof Anchor.Single single) {
        String lit = single.literal();
        if (lit != null && lit.length() >= 2) {
          boolean isSubsumedByPrefix =
              excludePrefix != null && (excludePrefix.contains(lit) || lit.contains(excludePrefix));
          boolean isSubsumedBySuffix =
              excludeSuffix != null && (excludeSuffix.contains(lit) || lit.contains(excludeSuffix));
          if (!isSubsumedByPrefix && !isSubsumedBySuffix) {
            list.add(lit);
          }
        }
      }
    }
    if (list.size() < 2) {
      return null;
    }
    int size = Math.min(4, list.size());
    String[] infixes = new String[size];
    for (int i = 0; i < size; i++) {
      infixes[i] = list.get(i);
    }
    Integer[] order = new Integer[size];
    for (int i = 0; i < size; i++) {
      order[i] = i;
    }
    Arrays.sort(
        order,
        (a, b) ->
            Integer.compare(
                RarityOracle.literalSelectivityScore(infixes[b]),
                RarityOracle.literalSelectivityScore(infixes[a])));
    int[] checkOrder = new int[size];
    for (int i = 0; i < size; i++) {
      checkOrder[i] = order[i];
    }
    return new RejectDescriptor.InfixSequence(infixes, checkOrder);
  }

  RejectDescriptor.InfixSequence toInfixSequence() {
    return toInfixSequence(null, null);
  }

  enum GapKind {
    /** Zero-width gap (adjacent anchors or no leading/trailing gap). */
    EMPTY,
    /** Zero-width word boundary assertion (\b). */
    WORD_BOUNDARY,
    /** Zero-width non-word boundary assertion (\B). */
    NO_WORD_BOUNDARY,
    /** Zero-width line start assertion (^ or (?m)^). */
    LINE_START,
    /** Zero-width line end assertion ($ or (?m)$). */
    LINE_END,
    /** Unbounded arbitrary characters ({@code .*} in DOTALL mode). */
    ANY_STAR,
    /** Unbounded single-line characters ({@code .*} in non-DOTALL mode or {@code [^\n]*}). */
    SINGLE_LINE_ANY_STAR,
    /** Bounded or unbounded character class repetition (e.g. {@code \s+}, {@code \d{1,4}}). */
    BOUNDED_CLASS_REPEAT
  }

  @SuppressWarnings("ArrayRecordComponent")
  record Gap(
      GapKind kind,
      int minLength,
      int maxLength,
      int[] discreteOffsets,
      AsciiBitmap charClass,
      int[] charClassRanges,
      CharClassScanInfo scanInfo,
      boolean isGreedy) {
    static final Gap EMPTY = new Gap(GapKind.EMPTY, 0, 0, null, null, null, null, true);
    static final Gap WORD_BOUNDARY =
        new Gap(GapKind.WORD_BOUNDARY, 0, 0, null, null, null, null, true);
    static final Gap NO_WORD_BOUNDARY =
        new Gap(GapKind.NO_WORD_BOUNDARY, 0, 0, null, null, null, null, true);
    static final Gap LINE_START = new Gap(GapKind.LINE_START, 0, 0, null, null, null, null, true);
    static final Gap LINE_END = new Gap(GapKind.LINE_END, 0, 0, null, null, null, null, true);
    static final Gap ANY_STAR_GREEDY =
        new Gap(GapKind.ANY_STAR, 0, Integer.MAX_VALUE, null, null, null, null, true);
    static final Gap ANY_STAR_LAZY =
        new Gap(GapKind.ANY_STAR, 0, Integer.MAX_VALUE, null, null, null, null, false);
    static final Gap SINGLE_LINE_ANY_STAR_GREEDY =
        new Gap(GapKind.SINGLE_LINE_ANY_STAR, 0, Integer.MAX_VALUE, null, null, null, null, true);
    static final Gap SINGLE_LINE_ANY_STAR_LAZY =
        new Gap(GapKind.SINGLE_LINE_ANY_STAR, 0, Integer.MAX_VALUE, null, null, null, null, false);

    Gap(GapKind kind, int minLength, int maxLength, AsciiBitmap charClass, boolean isGreedy) {
      this(
          kind,
          minLength,
          maxLength,
          null,
          charClass,
          charClass != null ? charClass.toRanges() : null,
          null,
          isGreedy);
    }

    Gap(
        GapKind kind,
        int minLength,
        int maxLength,
        AsciiBitmap charClass,
        CharClassScanInfo scanInfo,
        boolean isGreedy) {
      this(
          kind,
          minLength,
          maxLength,
          null,
          charClass,
          charClass != null ? charClass.toRanges() : (scanInfo != null ? scanInfo.ranges() : null),
          scanInfo,
          isGreedy);
    }

    Gap(
        GapKind kind,
        int minLength,
        int maxLength,
        int[] discreteOffsets,
        AsciiBitmap charClass,
        CharClassScanInfo scanInfo,
        boolean isGreedy) {
      this(
          kind,
          minLength,
          maxLength,
          discreteOffsets,
          charClass,
          charClass != null ? charClass.toRanges() : (scanInfo != null ? scanInfo.ranges() : null),
          scanInfo,
          isGreedy);
    }

    private static boolean isAsciiWord(int ch) {
      return (ch >= 'a' && ch <= 'z')
          || (ch >= 'A' && ch <= 'Z')
          || (ch >= '0' && ch <= '9')
          || ch == '_';
    }

    private static boolean isWordBoundary(String text, int pos) {
      boolean prev = pos > 0 && isAsciiWord(text.charAt(pos - 1));
      boolean next = pos < text.length() && isAsciiWord(text.charAt(pos));
      return prev != next;
    }

    private static boolean isWordBoundary(Utf8InputScanner scanner, int pos) {
      boolean prev = pos > 0 && isAsciiWord(scanner.asciiAt(pos - 1));
      boolean next = pos < scanner.length() && isAsciiWord(scanner.asciiAt(pos));
      return prev != next;
    }

    private static boolean isLineStart(String text, int pos) {
      return pos == 0 || text.charAt(pos - 1) == '\n';
    }

    private static boolean isLineStart(Utf8InputScanner scanner, int pos) {
      return pos == 0 || scanner.asciiAt(pos - 1) == '\n';
    }

    private static boolean isLineEnd(String text, int pos) {
      return pos == text.length()
          || text.charAt(pos) == '\n'
          || (text.charAt(pos) == '\r'
              && (pos + 1 == text.length() || text.charAt(pos + 1) == '\n'));
    }

    private static boolean isLineEnd(Utf8InputScanner scanner, int pos) {
      return pos == scanner.length()
          || scanner.asciiAt(pos) == '\n'
          || (scanner.asciiAt(pos) == '\r'
              && (pos + 1 == scanner.length() || scanner.asciiAt(pos + 1) == '\n'));
    }

    boolean matchesSlice(String text, int from, int to) {
      int len = to - from;
      if (len < minLength || len > maxLength) {
        return false;
      }
      return switch (kind) {
        case EMPTY -> len == 0;
        case WORD_BOUNDARY -> len == 0 && isWordBoundary(text, from);
        case NO_WORD_BOUNDARY -> len == 0 && !isWordBoundary(text, from);
        case LINE_START -> len == 0 && isLineStart(text, from);
        case LINE_END -> len == 0 && isLineEnd(text, from);
        case ANY_STAR -> true;
        case SINGLE_LINE_ANY_STAR -> text.indexOf('\n', from) < 0 || text.indexOf('\n', from) >= to;
        case BOUNDED_CLASS_REPEAT -> {
          if (charClass == null) {
            yield true;
          }
          for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c > 127 || !charClass.containsAscii(c)) {
              yield false;
            }
          }
          yield true;
        }
      };
    }

    boolean matchesSlice(Utf8InputScanner scanner, int from, int to) {
      int len = to - from;
      if (len < minLength || len > maxLength) {
        return false;
      }
      return switch (kind) {
        case EMPTY -> len == 0;
        case WORD_BOUNDARY -> len == 0 && isWordBoundary(scanner, from);
        case NO_WORD_BOUNDARY -> len == 0 && !isWordBoundary(scanner, from);
        case LINE_START -> len == 0 && isLineStart(scanner, from);
        case LINE_END -> len == 0 && isLineEnd(scanner, from);
        case ANY_STAR -> true;
        case SINGLE_LINE_ANY_STAR -> {
          int nl = scanner.indexOfAscii('\n', from, to);
          yield nl < 0 || nl >= to;
        }
        case BOUNDED_CLASS_REPEAT -> {
          if (charClass == null) {
            yield true;
          }
          if (charClassRanges != null && len >= 8 && charClassRanges.length <= 8) {
            yield scanner.matchAsciiClassSlice(from, to, charClassRanges);
          }
          for (int i = from; i < to; i++) {
            if (!charClass.contains(scanner.asciiAt(i))) {
              yield false;
            }
          }
          yield true;
        }
      };
    }

    int matchBackward(String text, int anchorPos, int minPos) {
      return switch (kind) {
        case EMPTY -> anchorPos;
        case WORD_BOUNDARY -> isWordBoundary(text, anchorPos) ? anchorPos : -1;
        case NO_WORD_BOUNDARY -> !isWordBoundary(text, anchorPos) ? anchorPos : -1;
        case LINE_START -> isLineStart(text, anchorPos) ? anchorPos : -1;
        case LINE_END -> isLineEnd(text, anchorPos) ? anchorPos : -1;
        case BOUNDED_CLASS_REPEAT -> {
          int limit = Math.max(minPos, maxLength == Integer.MAX_VALUE ? 0 : anchorPos - maxLength);
          int cur = anchorPos;
          while (cur > limit) {
            int cp = text.codePointBefore(cur);
            int prevPos = cur - Character.charCount(cp);
            if (prevPos < limit) {
              break;
            }
            if (scanInfo != null) {
              if (!scanInfo.contains(cp)) {
                break;
              }
            } else if (charClass != null) {
              if (!charClass.contains(cp)) {
                break;
              }
            }
            cur = prevPos;
          }
          int matched = anchorPos - cur;
          if (matched < minLength) {
            yield -1;
          }
          yield isGreedy ? cur : anchorPos - minLength;
        }
        case SINGLE_LINE_ANY_STAR -> {
          if (!isGreedy) {
            yield anchorPos - minLength >= minPos ? anchorPos - minLength : -1;
          }
          int nl = text.lastIndexOf('\n', anchorPos - 1);
          int start = (nl >= minPos) ? nl + 1 : minPos;
          yield (anchorPos - start >= minLength) ? start : -1;
        }
        case ANY_STAR -> {
          if (!isGreedy) {
            yield anchorPos - minLength >= minPos ? anchorPos - minLength : -1;
          }
          yield (anchorPos - minPos >= minLength) ? minPos : -1;
        }
      };
    }

    int matchBackward(Utf8InputScanner scanner, int anchorPos, int minPos) {
      return switch (kind) {
        case EMPTY -> anchorPos;
        case WORD_BOUNDARY -> isWordBoundary(scanner, anchorPos) ? anchorPos : -1;
        case NO_WORD_BOUNDARY -> !isWordBoundary(scanner, anchorPos) ? anchorPos : -1;
        case LINE_START -> isLineStart(scanner, anchorPos) ? anchorPos : -1;
        case LINE_END -> isLineEnd(scanner, anchorPos) ? anchorPos : -1;
        case BOUNDED_CLASS_REPEAT -> {
          int limit = Math.max(minPos, maxLength == Integer.MAX_VALUE ? 0 : anchorPos - maxLength);
          int cur = anchorPos;
          while (cur > limit) {
            long decoded = scanner.decodeBackward(cur);
            int cp = InputScanner.codePoint(decoded);
            int prevPos = InputScanner.position(decoded);
            if (prevPos < limit) {
              break;
            }
            if (scanInfo != null) {
              if (!scanInfo.contains(cp)) {
                break;
              }
            } else if (charClass != null) {
              if (!charClass.contains(cp)) {
                break;
              }
            }
            cur = prevPos;
          }
          int matched = anchorPos - cur;
          if (matched < minLength) {
            yield -1;
          }
          yield isGreedy ? cur : anchorPos - minLength;
        }
        case SINGLE_LINE_ANY_STAR -> {
          if (!isGreedy) {
            yield anchorPos - minLength >= minPos ? anchorPos - minLength : -1;
          }
          int nl = -1;
          for (int i = anchorPos - 1; i >= minPos; i--) {
            if (scanner.asciiAt(i) == '\n') {
              nl = i;
              break;
            }
          }
          int start = (nl >= minPos) ? nl + 1 : minPos;
          yield (anchorPos - start >= minLength) ? start : -1;
        }
        case ANY_STAR -> {
          if (!isGreedy) {
            yield anchorPos - minLength >= minPos ? anchorPos - minLength : -1;
          }
          yield (anchorPos - minPos >= minLength) ? minPos : -1;
        }
      };
    }

    int matchForward(String text, int fromPos, int maxPos) {
      return switch (kind) {
        case EMPTY -> fromPos;
        case WORD_BOUNDARY -> isWordBoundary(text, fromPos) ? fromPos : -1;
        case NO_WORD_BOUNDARY -> !isWordBoundary(text, fromPos) ? fromPos : -1;
        case LINE_START -> isLineStart(text, fromPos) ? fromPos : -1;
        case LINE_END -> isLineEnd(text, fromPos) ? fromPos : -1;
        case BOUNDED_CLASS_REPEAT -> {
          int limit =
              Math.min(maxPos, maxLength == Integer.MAX_VALUE ? maxPos : fromPos + maxLength);
          int cur = fromPos;
          while (cur < limit && (charClass == null || charClass.contains(text.charAt(cur)))) {
            cur++;
          }
          int matched = cur - fromPos;
          if (matched < minLength) {
            yield -1;
          }
          yield isGreedy ? cur : fromPos + minLength;
        }
        case SINGLE_LINE_ANY_STAR -> {
          if (!isGreedy) {
            yield fromPos + minLength <= maxPos ? fromPos + minLength : -1;
          }
          int nl = text.indexOf('\n', fromPos);
          int end = (nl >= fromPos && nl <= maxPos) ? nl : maxPos;
          yield (end - fromPos >= minLength) ? end : -1;
        }
        case ANY_STAR -> {
          if (!isGreedy) {
            yield fromPos + minLength <= maxPos ? fromPos + minLength : -1;
          }
          yield (maxPos - fromPos >= minLength) ? maxPos : -1;
        }
      };
    }

    int matchForward(Utf8InputScanner scanner, int fromPos, int maxPos) {
      return switch (kind) {
        case EMPTY -> fromPos;
        case WORD_BOUNDARY -> isWordBoundary(scanner, fromPos) ? fromPos : -1;
        case NO_WORD_BOUNDARY -> !isWordBoundary(scanner, fromPos) ? fromPos : -1;
        case LINE_START -> isLineStart(scanner, fromPos) ? fromPos : -1;
        case LINE_END -> isLineEnd(scanner, fromPos) ? fromPos : -1;
        case BOUNDED_CLASS_REPEAT -> {
          int limit =
              Math.min(maxPos, maxLength == Integer.MAX_VALUE ? maxPos : fromPos + maxLength);
          int cur = fromPos;
          while (cur < limit && (charClass == null || charClass.contains(scanner.asciiAt(cur)))) {
            cur++;
          }
          int matched = cur - fromPos;
          if (matched < minLength) {
            yield -1;
          }
          yield isGreedy ? cur : fromPos + minLength;
        }
        case SINGLE_LINE_ANY_STAR -> {
          if (!isGreedy) {
            yield fromPos + minLength <= maxPos ? fromPos + minLength : -1;
          }
          int nl = scanner.indexOfAscii('\n', fromPos, maxPos);
          int end = (nl >= fromPos && nl <= maxPos) ? nl : maxPos;
          yield (end - fromPos >= minLength) ? end : -1;
        }
        case ANY_STAR -> {
          if (!isGreedy) {
            yield fromPos + minLength <= maxPos ? fromPos + minLength : -1;
          }
          yield (maxPos - fromPos >= minLength) ? maxPos : -1;
        }
      };
    }
  }

  sealed interface Anchor permits Anchor.Single, Anchor.Alternation, Anchor.CharClass {
    default int selectivityScore() {
      return RarityOracle.literalSelectivityScore(primaryLiteral());
    }

    static Anchor create(String literal) {
      return Single.create(literal, false);
    }

    static Anchor create(String literal, boolean foldCase) {
      return Single.create(literal, foldCase);
    }

    static Anchor create(String[] literals, boolean foldCase) {
      return Alternation.create(literals, foldCase);
    }

    static Anchor create(AsciiBitmap bitmap) {
      return CharClass.create(bitmap);
    }

    int minLength();

    int maxLength();

    boolean foldCase();

    default String literal() {
      return primaryLiteral();
    }

    default CharClassScanInfo scanInfo() {
      return null;
    }

    String primaryLiteral();

    int findNext(String text, int fromIndex);

    int findNext(Utf8InputScanner scanner, int fromIndex);

    boolean startsWith(String text, int pos);

    boolean startsWith(Utf8InputScanner scanner, int pos);

    int matchForward(String text, int pos);

    int matchForward(Utf8InputScanner scanner, int pos);

    int lengthAt(String text, int pos);

    int lengthAt(Utf8InputScanner scanner, int pos);

    @SuppressWarnings("ArrayRecordComponent")
    record Single(
        String literal,
        boolean foldCase,
        byte[] literalUtf8,
        int[] failure,
        int[] shifts,
        int anchorOffset,
        char anchorLowChar,
        char anchorHighChar,
        byte anchorLowByte,
        byte anchorHighByte)
        implements Anchor {

      static Single create(String literal) {
        return create(literal, false);
      }

      static Single create(String literal, boolean foldCase) {
        Objects.requireNonNull(literal);
        byte[] utf8 = literal.getBytes(StandardCharsets.UTF_8);
        if (!foldCase) {
          int[] failure = Pattern.literalFailure(utf8);
          int[] shifts = Pattern.literalShifts(utf8);
          return new Single(
              literal, false, utf8, failure, shifts, 0, '\0', '\0', (byte) 0, (byte) 0);
        }
        int[] failure = Ascii.ignoreCaseFailure(literal);
        int anchorOffset = RarityOracle.rarestAsciiOffset(literal, literal.length());
        char anchor = literal.charAt(anchorOffset);
        char anchorLow = Ascii.toLowerCase(anchor);
        char anchorHigh = Ascii.toUpperCase(anchor);
        return new Single(
            literal,
            true,
            utf8,
            failure,
            null,
            anchorOffset,
            anchorLow,
            anchorHigh,
            (byte) anchorLow,
            (byte) anchorHigh);
      }

      @Override
      public int minLength() {
        return literal.length();
      }

      @Override
      public int maxLength() {
        return literal.length();
      }

      @Override
      public String primaryLiteral() {
        return literal;
      }

      @Override
      public int findNext(String text, int fromIndex) {
        if (foldCase) {
          return Matcher.indexOfIgnoreCase(
              text, literal, anchorOffset, anchorLowChar, anchorHighChar, fromIndex);
        }
        return text.indexOf(literal, fromIndex);
      }

      @Override
      public int findNext(Utf8InputScanner scanner, int fromIndex) {
        if (foldCase) {
          return scanner.indexOfIgnoreCase(
              literal, failure, anchorOffset, anchorLowByte, anchorHighByte, fromIndex);
        }
        return scanner.indexOf(literalUtf8, failure, shifts, fromIndex);
      }

      @Override
      public boolean startsWith(String text, int pos) {
        if (pos < 0 || pos + literal.length() > text.length()) {
          return false;
        }
        return foldCase
            ? Ascii.regionMatchesIgnoreCase(text, pos, literal, literal.length())
            : text.startsWith(literal, pos);
      }

      @Override
      public boolean startsWith(Utf8InputScanner scanner, int pos) {
        return scanner.startsWith(literalUtf8, pos, foldCase);
      }

      @Override
      public int matchForward(String text, int pos) {
        return startsWith(text, pos) ? pos + literal.length() : -1;
      }

      @Override
      public int matchForward(Utf8InputScanner scanner, int pos) {
        return startsWith(scanner, pos) ? pos + literalUtf8.length : -1;
      }

      @Override
      public int lengthAt(String text, int pos) {
        return startsWith(text, pos) ? literal.length() : -1;
      }

      @Override
      public int lengthAt(Utf8InputScanner scanner, int pos) {
        return startsWith(scanner, pos) ? literalUtf8.length : -1;
      }
    }

    @SuppressWarnings("ArrayRecordComponent")
    record Alternation(
        String[] literals,
        byte[][] literalsUtf8,
        boolean foldCase,
        int minLength,
        int maxLength,
        MultiLiteralInfo multiLiteral,
        TeddyModel teddyModel)
        implements Anchor {

      static Alternation create(String[] literals, boolean foldCase) {
        Objects.requireNonNull(literals);
        if (literals.length < 2) {
          throw new IllegalArgumentException("Alternation requires at least 2 literals");
        }
        int min = Integer.MAX_VALUE;
        int max = 0;
        byte[][] utf8 = new byte[literals.length][];
        for (int i = 0; i < literals.length; i++) {
          String lit = literals[i];
          utf8[i] = lit.getBytes(StandardCharsets.UTF_8);
          min = Math.min(min, lit.length());
          max = Math.max(max, lit.length());
        }

        MultiLiteralInfo multiLit = !foldCase ? MultiLiteralInfo.create(literals) : null;
        TeddyModel teddy = !foldCase ? TeddyModel.compileForSelectedProvider(literals) : null;

        return new Alternation(literals.clone(), utf8, foldCase, min, max, multiLit, teddy);
      }

      @Override
      public int selectivityScore() {
        if (teddyModel != null || multiLiteral != null) {
          return 80;
        }
        int minScore = Integer.MAX_VALUE;
        for (String lit : literals) {
          minScore = Math.min(minScore, RarityOracle.literalSelectivityScore(lit));
        }
        return minScore == Integer.MAX_VALUE ? 0 : minScore;
      }

      @Override
      public String primaryLiteral() {
        return literals[0];
      }

      @Override
      public CharClassScanInfo scanInfo() {
        if (foldCase) {
          return null;
        }
        CharClassBuilder builder = new CharClassBuilder();
        for (String lit : literals) {
          if (!lit.isEmpty()) {
            builder.addRune(lit.codePointAt(0));
          }
        }
        org.safere.CharClass cc = builder.build();
        return cc.isEmpty() ? null : CharClassScanInfo.fromCharClass(cc);
      }

      @Override
      public int findNext(String text, int fromIndex) {
        if (literals.length == 2) {
          String lit0 = literals[0];
          String lit1 = literals[1];
          int p0 =
              foldCase
                  ? Matcher.indexOfIgnoreCase(text, lit0, fromIndex)
                  : text.indexOf(lit0, fromIndex);
          if (p0 == fromIndex) {
            return p0;
          }
          int p1 =
              foldCase
                  ? Matcher.indexOfIgnoreCase(text, lit1, fromIndex)
                  : text.indexOf(lit1, fromIndex);
          if (p0 < 0) {
            return p1;
          }
          if (p1 < 0) {
            return p0;
          }
          return Math.min(p0, p1);
        }
        int bestPos = Integer.MAX_VALUE;
        for (String lit : literals) {
          int pos =
              foldCase
                  ? Matcher.indexOfIgnoreCase(text, lit, fromIndex)
                  : text.indexOf(lit, fromIndex);
          if (pos >= 0 && pos < bestPos) {
            bestPos = pos;
            if (bestPos == fromIndex) {
              return bestPos;
            }
          }
        }
        return bestPos == Integer.MAX_VALUE ? -1 : bestPos;
      }

      @Override
      public int findNext(Utf8InputScanner scanner, int fromIndex) {
        if (!foldCase) {
          if (teddyModel != null && VectorScanProviders.teddyProviderAvailable()) {
            VectorScanProvider provider =
                VectorScanProviders.providerForTeddyLength(scanner.length());
            if (provider != null) {
              int idx =
                  provider.indexOfTeddy(
                      scanner.bytes(), scanner.offset(), scanner.length(), teddyModel, fromIndex);
              if (idx != VectorScanProvider.UNSUPPORTED) {
                return idx;
              }
            }
          }
          if (multiLiteral != null) {
            VectorScanProvider provider =
                VectorScanProviders.providerForMultiLiteralLength(scanner.length());
            if (provider != null) {
              int idx =
                  provider.indexOfMultiLiteral(
                      scanner.bytes(),
                      scanner.offset(),
                      scanner.length(),
                      multiLiteral.literals(),
                      multiLiteral.anchorChars(),
                      multiLiteral.anchorOffsets(),
                      multiLiteral.anchorRanges(),
                      multiLiteral.minLength(),
                      teddyModel,
                      fromIndex);
              if (idx != VectorScanProvider.UNSUPPORTED) {
                return idx;
              }
            }
          }
        }

        int len = scanner.length();
        for (int pos = fromIndex; pos <= len - minLength; pos++) {
          for (int i = 0; i < literalsUtf8.length; i++) {
            if (scanner.startsWith(literalsUtf8[i], pos, foldCase)) {
              return pos;
            }
          }
        }
        return -1;
      }

      @Override
      public boolean startsWith(String text, int pos) {
        if (pos < 0 || pos + minLength > text.length()) {
          return false;
        }
        for (String lit : literals) {
          if (pos + lit.length() <= text.length()) {
            boolean match =
                foldCase
                    ? Ascii.regionMatchesIgnoreCase(text, pos, lit, lit.length())
                    : text.startsWith(lit, pos);
            if (match) {
              return true;
            }
          }
        }
        return false;
      }

      @Override
      public boolean startsWith(Utf8InputScanner scanner, int pos) {
        if (pos < 0 || pos + minLength > scanner.length()) {
          return false;
        }
        for (byte[] litUtf8 : literalsUtf8) {
          if (scanner.startsWith(litUtf8, pos, foldCase)) {
            return true;
          }
        }
        return false;
      }

      @Override
      public int matchForward(String text, int pos) {
        if (pos < 0 || pos + minLength > text.length()) {
          return -1;
        }
        for (String lit : literals) {
          if (pos + lit.length() <= text.length()) {
            boolean match =
                foldCase
                    ? Ascii.regionMatchesIgnoreCase(text, pos, lit, lit.length())
                    : text.startsWith(lit, pos);
            if (match) {
              return pos + lit.length();
            }
          }
        }
        return -1;
      }

      @Override
      public int matchForward(Utf8InputScanner scanner, int pos) {
        if (pos < 0 || pos + minLength > scanner.length()) {
          return -1;
        }
        for (byte[] litUtf8 : literalsUtf8) {
          if (scanner.startsWith(litUtf8, pos, foldCase)) {
            return pos + litUtf8.length;
          }
        }
        return -1;
      }

      @Override
      public int lengthAt(String text, int pos) {
        if (pos < 0 || pos + minLength > text.length()) {
          return -1;
        }
        for (String lit : literals) {
          if (pos + lit.length() <= text.length()) {
            boolean match =
                foldCase
                    ? Ascii.regionMatchesIgnoreCase(text, pos, lit, lit.length())
                    : text.startsWith(lit, pos);
            if (match) {
              return lit.length();
            }
          }
        }
        return -1;
      }

      @Override
      public int lengthAt(Utf8InputScanner scanner, int pos) {
        if (pos < 0 || pos + minLength > scanner.length()) {
          return -1;
        }
        for (byte[] litUtf8 : literalsUtf8) {
          if (scanner.startsWith(litUtf8, pos, foldCase)) {
            return litUtf8.length;
          }
        }
        return -1;
      }
    }

    record CharClass(AsciiBitmap bitmap, int[] ranges, CharClassScanInfo scanInfo)
        implements Anchor {
      static CharClass create(AsciiBitmap bitmap) {
        int[] ranges = bitmap.toRanges();
        CharClassScanInfo scanInfo =
            ranges.length <= 8 ? CharClassScanInfo.fromAsciiBitmap(bitmap) : null;
        return new CharClass(bitmap, ranges, scanInfo);
      }

      static CharClass create(CharClassScanInfo scanInfo) {
        if (scanInfo == null) {
          return null;
        }
        AsciiBitmap bitmap =
            scanInfo.isAscii() ? new AsciiBitmap(scanInfo.bitmap0(), scanInfo.bitmap1()) : null;
        int[] ranges = scanInfo.ranges();
        return new CharClass(bitmap, ranges, scanInfo);
      }

      @Override
      public int selectivityScore() {
        if (bitmap != null) {
          return Math.max(1, 128 - bitmap.cardinality());
        }
        if (scanInfo != null && scanInfo.ranges() != null) {
          int count = 0;
          for (int i = 0; i < scanInfo.ranges().length; i += 2) {
            count += (scanInfo.ranges()[i + 1] - scanInfo.ranges()[i] + 1);
          }
          return Math.max(1, 128 - Math.min(120, count / 100));
        }
        return 1;
      }

      @Override
      public int minLength() {
        return 1;
      }

      @Override
      public int maxLength() {
        return 1;
      }

      @Override
      public boolean foldCase() {
        return false;
      }

      @Override
      public String primaryLiteral() {
        return null;
      }

      @Override
      public int findNext(String text, int fromIndex) {
        int len = text.length();
        for (int i = Math.max(0, fromIndex); i < len; ) {
          int cp = text.codePointAt(i);
          if (scanInfo != null) {
            if (scanInfo.contains(cp)) {
              return i;
            }
          } else if (cp < 128 && bitmap != null && bitmap.containsAscii(cp)) {
            return i;
          }
          i += Character.charCount(cp);
        }
        return -1;
      }

      @Override
      public int findNext(Utf8InputScanner scanner, int fromIndex) {
        if (scanInfo != null) {
          return scanner.indexOfCodePointClass(
              scanInfo.ranges(),
              scanInfo.bitmap0(),
              scanInfo.bitmap1(),
              fromIndex,
              scanner.length());
        }
        int len = scanner.length();
        for (int i = Math.max(0, fromIndex); i < len; i++) {
          int c = scanner.asciiAt(i);
          if (c >= 0 && bitmap != null && bitmap.containsAscii(c)) {
            return i;
          }
        }
        return -1;
      }

      @Override
      public boolean startsWith(String text, int pos) {
        if (pos >= 0 && pos < text.length()) {
          int cp = text.codePointAt(pos);
          if (scanInfo != null) {
            return scanInfo.contains(cp);
          }
          return cp < 128 && bitmap != null && bitmap.containsAscii(cp);
        }
        return false;
      }

      @Override
      public boolean startsWith(Utf8InputScanner scanner, int pos) {
        if (pos >= 0 && pos < scanner.length()) {
          long decoded = scanner.decodeForward(pos);
          int cp = InputScanner.codePoint(decoded);
          if (scanInfo != null) {
            return scanInfo.contains(cp);
          }
          return cp < 128 && bitmap != null && bitmap.containsAscii(cp);
        }
        return false;
      }

      @Override
      public int matchForward(String text, int pos) {
        if (pos >= 0 && pos < text.length()) {
          int cp = text.codePointAt(pos);
          if (scanInfo != null
              ? scanInfo.contains(cp)
              : (cp < 128 && bitmap != null && bitmap.containsAscii(cp))) {
            return pos + Character.charCount(cp);
          }
        }
        return -1;
      }

      @Override
      public int matchForward(Utf8InputScanner scanner, int pos) {
        if (pos >= 0 && pos < scanner.length()) {
          long decoded = scanner.decodeForward(pos);
          int cp = InputScanner.codePoint(decoded);
          if (scanInfo != null
              ? scanInfo.contains(cp)
              : (cp < 128 && bitmap != null && bitmap.containsAscii(cp))) {
            return InputScanner.position(decoded);
          }
        }
        return -1;
      }

      @Override
      public int lengthAt(String text, int pos) {
        if (pos >= 0 && pos < text.length()) {
          int cp = text.codePointAt(pos);
          if (scanInfo != null
              ? scanInfo.contains(cp)
              : (cp < 128 && bitmap != null && bitmap.containsAscii(cp))) {
            return Character.charCount(cp);
          }
        }
        return -1;
      }

      @Override
      public int lengthAt(Utf8InputScanner scanner, int pos) {
        if (pos >= 0 && pos < scanner.length()) {
          long decoded = scanner.decodeForward(pos);
          int cp = InputScanner.codePoint(decoded);
          if (scanInfo != null
              ? scanInfo.contains(cp)
              : (cp < 128 && bitmap != null && bitmap.containsAscii(cp))) {
            return InputScanner.position(decoded) - pos;
          }
        }
        return -1;
      }
    }
  }
}
