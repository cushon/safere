// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Compiles AST representations of regexes into {@link MultiAnchorDescriptor} instances, analyzing
 * multi-anchor chains, gaps, fixed-offset literals, leading expansions, and prefix accelerators.
 */
final class MultiAnchorCompiler {

  record PrefixResult(String prefix, boolean foldCase) {}

  @SuppressWarnings("ArrayRecordComponent")
  record FixedOffsetLiteral(String literal, int minOffset, int maxOffset, int[] discreteOffsets) {}

  private static final class AsciiWidthRange {
    static final AsciiWidthRange INVALID = new AsciiWidthRange(-1, -1, null);
    static final AsciiWidthRange ZERO = new AsciiWidthRange(0, 0, new int[] {0});
    static final AsciiWidthRange ONE = new AsciiWidthRange(1, 1, new int[] {1});
    static final AsciiWidthRange NON_DISCRETE_ONE = new AsciiWidthRange(1, 1, null);

    final int minWidth;
    final int maxWidth;
    final int[] discreteWidths;

    AsciiWidthRange(int minWidth, int maxWidth, int[] discreteWidths) {
      this.minWidth = minWidth;
      this.maxWidth = maxWidth;
      this.discreteWidths = discreteWidths;
    }

    static AsciiWidthRange exact(int width) {
      return new AsciiWidthRange(width, width, new int[] {width});
    }

    boolean isValid() {
      return minWidth >= 0;
    }

    boolean isExact() {
      return minWidth >= 0 && minWidth == maxWidth;
    }
  }

  private MultiAnchorCompiler() {}

  static MultiAnchorDescriptor compile(Regexp re, int flags) {
    if (re == null) {
      return null;
    }
    Regexp node = unwrapCaptures(re);
    if (node == null) {
      return null;
    }

    boolean anchorStart = false;
    boolean anchorEnd = false;
    if (node.op == RegexpOp.CONCAT && node.subs != null && !node.subs.isEmpty()) {
      int n = node.subs.size();
      if (node.subs.get(0).op == RegexpOp.BEGIN_TEXT) {
        anchorStart = true;
      }
      if (n > 0 && node.subs.get(n - 1).op == RegexpOp.END_TEXT) {
        anchorEnd = true;
      }
    }

    // 1. Multi-anchor sequence or anchored chain
    MultiAnchorDescriptor multiChain = extractMultiAnchorChain(node, flags, anchorStart, anchorEnd);
    if (multiChain != null
        && (multiChain.numSegments() >= 2
            || multiChain.leadingGap().kind() != MultiAnchorDescriptor.GapKind.EMPTY
            || multiChain.trailingGap().kind() != MultiAnchorDescriptor.GapKind.EMPTY
            || multiChain.firstSegment().anchor()
                instanceof MultiAnchorDescriptor.Anchor.Alternation)) {
      return multiChain;
    }

    // 2. Direct anchor on single node
    MultiAnchorDescriptor.Anchor directAnchor = extractLiteralAnchor(node, flags);
    if (directAnchor != null) {
      return new MultiAnchorDescriptor(
          new MultiAnchorDescriptor.Segment[] {
            new MultiAnchorDescriptor.Segment(MultiAnchorDescriptor.Gap.EMPTY, directAnchor)
          },
          MultiAnchorDescriptor.Gap.EMPTY,
          new int[] {0},
          directAnchor.minLength(),
          anchorStart,
          anchorEnd);
    }

    if (node.op != RegexpOp.CONCAT || node.subs == null || node.subs.isEmpty()) {
      CharClassScanInfo ccPrefix = extractCharClassPrefix(node);
      if (ccPrefix != null) {
        MultiAnchorDescriptor.Anchor.CharClass ccAnchor =
            MultiAnchorDescriptor.Anchor.CharClass.create(ccPrefix);
        return new MultiAnchorDescriptor(
            new MultiAnchorDescriptor.Segment[] {
              new MultiAnchorDescriptor.Segment(MultiAnchorDescriptor.Gap.EMPTY, ccAnchor)
            },
            MultiAnchorDescriptor.Gap.EMPTY,
            new int[] {0},
            1,
            anchorStart,
            anchorEnd);
      }
      return null;
    }

    // 3. Literal prefix on the concat
    PrefixResult prefixResult = extractPrefix(node);
    if (prefixResult.prefix() == null && anchorStart) {
      Regexp candidate = firstPrefixCandidateAfterTextAnchor(node);
      if (candidate != null) {
        prefixResult = extractPrefix(candidate);
      }
    }
    if (prefixResult.prefix() != null) {
      MultiAnchorDescriptor.Anchor.Single prefixAnchor =
          MultiAnchorDescriptor.Anchor.Single.create(
              prefixResult.prefix(), prefixResult.foldCase());
      return new MultiAnchorDescriptor(
          new MultiAnchorDescriptor.Segment[] {
            new MultiAnchorDescriptor.Segment(MultiAnchorDescriptor.Gap.EMPTY, prefixAnchor)
          },
          MultiAnchorDescriptor.Gap.EMPTY,
          new int[] {0},
          prefixResult.prefix().length(),
          anchorStart,
          anchorEnd);
    }

    // 4. Fixed offset literal
    FixedOffsetLiteral fol = extractFixedOffsetLiteral(node);
    if (fol != null) {
      CharClassScanInfo ccPrefix = extractCharClassPrefix(node);
      MultiAnchorDescriptor.Gap gap =
          new MultiAnchorDescriptor.Gap(
              MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT,
              fol.minOffset(),
              fol.maxOffset(),
              fol.discreteOffsets(),
              null,
              ccPrefix,
              true);
      MultiAnchorDescriptor.Anchor.Single anchor =
          MultiAnchorDescriptor.Anchor.Single.create(fol.literal(), false);
      return new MultiAnchorDescriptor(
          new MultiAnchorDescriptor.Segment[] {new MultiAnchorDescriptor.Segment(gap, anchor)},
          MultiAnchorDescriptor.Gap.EMPTY,
          new int[] {0},
          fol.minOffset() + fol.literal().length(),
          anchorStart,
          anchorEnd);
    }

    // 5. Leading character class expansion
    MultiAnchorDescriptor leadingExpansion = extractLeadingExpansion(node);
    if (leadingExpansion != null) {
      return leadingExpansion;
    }

    // 6. Reverse suffix anchor
    MultiAnchorDescriptor revAnchor = extractReverseMultiAnchor(node, flags, anchorEnd);
    if (revAnchor != null) {
      return revAnchor;
    }

    // 7. Character class prefix
    CharClassScanInfo ccPrefix = extractCharClassPrefix(node);
    if (ccPrefix == null && anchorStart) {
      Regexp candidate = firstPrefixCandidateAfterTextAnchor(node);
      if (candidate != null) {
        ccPrefix = extractCharClassPrefix(candidate);
      }
    }
    if (ccPrefix != null) {
      MultiAnchorDescriptor.Anchor.CharClass ccAnchor =
          MultiAnchorDescriptor.Anchor.CharClass.create(ccPrefix);
      return new MultiAnchorDescriptor(
          new MultiAnchorDescriptor.Segment[] {
            new MultiAnchorDescriptor.Segment(MultiAnchorDescriptor.Gap.EMPTY, ccAnchor)
          },
          MultiAnchorDescriptor.Gap.EMPTY,
          new int[] {0},
          1,
          anchorStart,
          anchorEnd);
    }

    return null;
  }

  private static MultiAnchorDescriptor extractMultiAnchorChain(
      Regexp node, int flags, boolean anchorStart, boolean anchorEnd) {
    if (node == null || node.op != RegexpOp.CONCAT || node.subs == null) {
      return null;
    }
    List<MultiAnchorDescriptor.Anchor> anchors = new ArrayList<>();
    List<MultiAnchorDescriptor.Gap> gaps = new ArrayList<>();

    int idx = 0;
    int n = node.subs.size();

    if (idx < n && node.subs.get(idx).op == RegexpOp.BEGIN_TEXT) {
      idx++;
    }

    MultiAnchorDescriptor.Gap leadingGap = MultiAnchorDescriptor.Gap.EMPTY;
    while (idx < n) {
      Regexp sub = node.subs.get(idx);
      if (isLeadingZeroWidth(sub)) {
        MultiAnchorDescriptor.Gap zwGap = classifyGap(sub, flags);
        if (zwGap != null) {
          leadingGap = zwGap;
        }
        idx++;
        continue;
      }
      MultiAnchorDescriptor.Gap gapCandidate = classifyGap(sub, flags);
      if (gapCandidate != null
          && gapCandidate.kind() == MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT
          && gapCandidate.maxLength() < Integer.MAX_VALUE) {
        if (leadingGap.kind() == MultiAnchorDescriptor.GapKind.EMPTY) {
          leadingGap = gapCandidate;
        } else if (leadingGap.kind() == MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT
            && leadingGap.maxLength() < Integer.MAX_VALUE) {
          leadingGap =
              new MultiAnchorDescriptor.Gap(
                  MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT,
                  leadingGap.minLength() + gapCandidate.minLength(),
                  leadingGap.maxLength() + gapCandidate.maxLength(),
                  null,
                  true);
        }
        idx++;
        continue;
      }
      if (gapCandidate != null
          && gapCandidate.kind() != MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT
          && leadingGap.kind() == MultiAnchorDescriptor.GapKind.EMPTY) {
        leadingGap = gapCandidate;
        idx++;
        continue;
      }
      break;
    }

    if (idx < n) {
      MultiAnchorDescriptor.Anchor firstAnchor = extractLiteralAnchor(node.subs.get(idx), flags);
      if (firstAnchor != null) {
        anchors.add(firstAnchor);
        gaps.add(leadingGap);
        idx++;

        while (idx < n) {
          Regexp gapSub = node.subs.get(idx);
          if (idx == n - 1 && gapSub.op == RegexpOp.END_TEXT) {
            idx++;
            break;
          }

          MultiAnchorDescriptor.Gap gap = classifyGap(gapSub, flags);
          if (gap == null) {
            MultiAnchorDescriptor.Anchor nextAnchor = extractLiteralAnchor(gapSub, flags);
            if (nextAnchor != null) {
              gaps.add(MultiAnchorDescriptor.Gap.EMPTY);
              anchors.add(nextAnchor);
              idx++;
              continue;
            }
            break;
          }
          idx++;

          if (idx >= n) {
            gaps.add(gap);
            break;
          }

          MultiAnchorDescriptor.Anchor nextAnchor = extractLiteralAnchor(node.subs.get(idx), flags);
          if (nextAnchor == null) {
            MultiAnchorDescriptor.Gap trailing = gap;
            boolean validTrailing = true;
            while (idx < n) {
              Regexp rem = node.subs.get(idx);
              if (idx == n - 1 && rem.op == RegexpOp.END_TEXT) {
                idx++;
                break;
              }
              MultiAnchorDescriptor.Gap remGap = classifyGap(rem, flags);
              if (remGap == null) {
                validTrailing = false;
                break;
              }
              if (remGap.kind() == MultiAnchorDescriptor.GapKind.ANY_STAR
                  || remGap.kind() == MultiAnchorDescriptor.GapKind.SINGLE_LINE_ANY_STAR) {
                trailing = remGap;
              }
              idx++;
            }
            if (validTrailing) {
              gaps.add(trailing);
              break;
            }
            break;
          }
          gaps.add(gap);
          anchors.add(nextAnchor);
          idx++;
        }
      }
    }

    if (idx < n) {
      return null;
    }

    if (gaps.size() == anchors.size()) {
      gaps.add(MultiAnchorDescriptor.Gap.EMPTY);
    }

    if (anchors.isEmpty() || gaps.size() != anchors.size() + 1) {
      return null;
    }

    int maxAnchorLen = 0;
    int minTotalLength = 0;
    for (MultiAnchorDescriptor.Anchor a : anchors) {
      int len = a.minLength();
      minTotalLength += len;
      if (len > maxAnchorLen) {
        maxAnchorLen = len;
      }
    }
    for (MultiAnchorDescriptor.Gap g : gaps) {
      minTotalLength += g.minLength();
    }

    if (maxAnchorLen < 2) {
      return null;
    }
    if (maxAnchorLen < 3 && anchors.size() < 3 && anchors.size() > 1) {
      return null;
    }

    int numAnchors = anchors.size();
    Integer[] orderBoxed = new Integer[numAnchors];
    for (int i = 0; i < numAnchors; i++) {
      orderBoxed[i] = i;
    }
    Arrays.sort(
        orderBoxed,
        (a, b) ->
            Integer.compare(anchors.get(b).selectivityScore(), anchors.get(a).selectivityScore()));

    int[] checkOrder = new int[numAnchors];
    for (int i = 0; i < numAnchors; i++) {
      checkOrder[i] = orderBoxed[i];
    }

    return new MultiAnchorDescriptor(
        anchors.toArray(MultiAnchorDescriptor.Anchor[]::new),
        gaps.toArray(MultiAnchorDescriptor.Gap[]::new),
        checkOrder,
        minTotalLength,
        anchorStart,
        anchorEnd);
  }

  private static MultiAnchorDescriptor.Anchor extractTailAnchor(Regexp tail, int flags) {
    if (tail == null) {
      return null;
    }
    PrefixResult prefix = extractPrefix(tail);
    if (prefix.prefix() != null) {
      return MultiAnchorDescriptor.Anchor.Single.create(prefix.prefix(), prefix.foldCase());
    }
    MultiAnchorDescriptor.Anchor.Alternation alt = extractAlternationAnchor(tail, flags);
    if (alt != null) {
      return alt;
    }
    CharClassScanInfo ccPrefix = extractCharClassPrefix(tail);
    if (ccPrefix != null) {
      return MultiAnchorDescriptor.Anchor.CharClass.create(ccPrefix);
    }
    return extractLiteralAnchor(tail, flags);
  }

  private static boolean isSelectiveLeadingExpansionAnchor(MultiAnchorDescriptor.Anchor anchor) {
    if (anchor == null) {
      return false;
    }
    if (anchor instanceof MultiAnchorDescriptor.Anchor.Single single) {
      String lit = single.literal();
      if (lit == null || lit.isEmpty()) {
        return false;
      }
      if (lit.length() < 2 && RarityOracle.byteRarity(lit.charAt(0)) < 40) {
        return false;
      }
      return RarityOracle.literalSelectivityScore(lit) > 0;
    }
    if (anchor instanceof MultiAnchorDescriptor.Anchor.Alternation) {
      return true;
    }
    if (anchor instanceof MultiAnchorDescriptor.Anchor.CharClass cc && cc.scanInfo() != null) {
      int numRunes = 0;
      int[] ranges = cc.scanInfo().ranges();
      if (ranges != null) {
        for (int i = 0; i < ranges.length; i += 2) {
          numRunes += (ranges[i + 1] - ranges[i] + 1);
        }
      }
      return numRunes > 0 && numRunes <= 8;
    }
    return false;
  }

  static MultiAnchorDescriptor extractLeadingExpansion(Regexp re) {
    if (re == null) {
      return null;
    }
    re = unwrapCaptures(re);
    if (re == null || re.op != RegexpOp.CONCAT || re.nsub() < 2) {
      return null;
    }

    int idx = 0;
    while (idx < re.nsub() && isLeadingZeroWidth(re.subs.get(idx))) {
      idx++;
    }
    if (idx >= re.nsub() - 1) {
      return null;
    }

    Regexp first = unwrapCaptures(re.subs.get(idx));
    if (first == null) {
      return null;
    }

    int minRepetition;
    int maxRepetition;
    Regexp repeated;
    if (first.op == RegexpOp.STAR) {
      minRepetition = 0;
      maxRepetition = Integer.MAX_VALUE;
      repeated = unwrapCaptures(first.sub());
    } else if (first.op == RegexpOp.PLUS) {
      minRepetition = 1;
      maxRepetition = Integer.MAX_VALUE;
      repeated = unwrapCaptures(first.sub());
    } else if (first.op == RegexpOp.REPEAT) {
      minRepetition = first.min;
      maxRepetition = first.max == -1 ? Integer.MAX_VALUE : first.max;
      repeated = unwrapCaptures(first.sub());
    } else {
      return null;
    }

    if (repeated == null || repeated.op == RegexpOp.ANY_CHAR || repeated.op == RegexpOp.ANY_BYTE) {
      return null;
    }

    CharClassScanInfo leadingClass;
    if (repeated.op == RegexpOp.CHAR_CLASS
        && repeated.charClass != null
        && !repeated.charClass.isEmpty()) {
      leadingClass = CharClassScanInfo.fromCharClass(repeated.charClass);
    } else if (repeated.op == RegexpOp.LITERAL) {
      leadingClass =
          CharClassScanInfo.fromCharClass(literalCharClass(repeated.rune, repeated.flags));
    } else {
      return null;
    }

    if (leadingClass == null) {
      return null;
    }

    int numRunes = 0;
    int[] ranges = leadingClass.ranges();
    if (ranges != null) {
      for (int i = 0; i < ranges.length; i += 2) {
        numRunes += (ranges[i + 1] - ranges[i] + 1);
      }
    }
    if (numRunes > 150_000) {
      return null;
    }

    List<Regexp> tailSubs = re.subs.subList(idx + 1, re.nsub());
    Regexp tail = tailSubs.size() == 1 ? tailSubs.getFirst() : Regexp.concat(tailSubs, 0);

    MultiAnchorDescriptor.Anchor inner = extractTailAnchor(tail, 0);
    if (inner == null || !isSelectiveLeadingExpansionAnchor(inner)) {
      return null;
    }

    MultiAnchorDescriptor.Gap gap =
        new MultiAnchorDescriptor.Gap(
            MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT,
            minRepetition,
            maxRepetition,
            null,
            leadingClass,
            true);
    return new MultiAnchorDescriptor(
        new MultiAnchorDescriptor.Segment[] {new MultiAnchorDescriptor.Segment(gap, inner)},
        MultiAnchorDescriptor.Gap.EMPTY,
        new int[] {0},
        minRepetition + inner.minLength(),
        false,
        false);
  }

  private static int reverseAnchorSelectivityScore(MultiAnchorDescriptor.Anchor anchor) {
    return switch (anchor) {
      case null -> 0;
      case MultiAnchorDescriptor.Anchor.Single single -> {
        String literal = single.literal();
        if (literal == null || literal.isEmpty()) {
          yield 0;
        }
        if (literal.length() < 2 && RarityOracle.byteRarity(literal.charAt(0)) < 40) {
          yield 0;
        }
        yield RarityOracle.literalSelectivityScore(literal);
      }
      case MultiAnchorDescriptor.Anchor.Alternation alt -> 80;
      default -> 0;
    };
  }

  static MultiAnchorDescriptor extractReverseMultiAnchor(Regexp re, int flags, boolean anchorEnd) {
    if (re == null || re.op != RegexpOp.CONCAT || re.nsub() < 2) {
      return null;
    }
    int startIdx = 0;
    while (startIdx < re.nsub() && isLeadingZeroWidth(re.subs.get(startIdx))) {
      startIdx++;
    }
    if (startIdx >= re.nsub() - 1) {
      return null;
    }

    int bestScore = 0;
    MultiAnchorDescriptor.Anchor bestAnchor = null;
    int bestMinLength = 0;

    for (int anchorIdx = startIdx + 1; anchorIdx < re.nsub(); anchorIdx++) {
      List<Regexp> tailSubs = re.subs.subList(anchorIdx, re.nsub());
      Regexp tail = tailSubs.size() == 1 ? tailSubs.getFirst() : Regexp.concat(tailSubs, 0);

      MultiAnchorDescriptor.Anchor anchorDesc = extractTailAnchor(tail, flags);
      if (anchorDesc == null) {
        continue;
      }
      int score = reverseAnchorSelectivityScore(anchorDesc);
      if (score <= bestScore) {
        continue;
      }

      List<Regexp> prefixSubs = re.subs.subList(startIdx, anchorIdx);
      Regexp prefix = prefixSubs.size() == 1 ? prefixSubs.getFirst() : Regexp.concat(prefixSubs, 0);

      int minPrefixLen = AstAnalysis.analyze(prefix).minMatchLength();
      if (minPrefixLen < 1) {
        continue;
      }
      if (containsWildcardOrZeroWidth(prefix)) {
        continue;
      }

      Prog reverseProg = Compiler.compileForDfa(prefix, true);
      if (reverseProg == null) {
        continue;
      }

      int minTailLen = AstAnalysis.analyze(tail).minMatchLength();
      int minLength = minPrefixLen + minTailLen;

      bestScore = score;
      bestAnchor = anchorDesc;
      bestMinLength = minLength;
    }

    if (bestAnchor != null) {
      return new MultiAnchorDescriptor(
          new MultiAnchorDescriptor.Segment[] {
            new MultiAnchorDescriptor.Segment(MultiAnchorDescriptor.Gap.ANY_STAR_GREEDY, bestAnchor)
          },
          MultiAnchorDescriptor.Gap.EMPTY,
          new int[] {0},
          bestMinLength,
          false,
          anchorEnd);
    }
    return null;
  }

  private static boolean containsWildcardOrZeroWidth(Regexp re) {
    if (re == null) {
      return false;
    }
    Deque<Regexp> stack = new ArrayDeque<>();
    stack.push(re);
    while (!stack.isEmpty()) {
      Regexp node = stack.pop();
      if (node == null) {
        continue;
      }
      switch (node.op) {
        case ANY_CHAR,
            ANY_BYTE,
            WORD_BOUNDARY,
            NO_WORD_BOUNDARY,
            BEGIN_LINE,
            END_LINE,
            BEGIN_TEXT,
            END_TEXT,
            GRAPHEME_CLUSTER_BOUNDARY,
            GRAPHEME_CLUSTER -> {
          return true;
        }
        case STAR, PLUS, QUEST, REPEAT, CAPTURE, NON_CAPTURE -> {
          if (node.subs != null) {
            for (Regexp sub : node.subs) {
              stack.push(sub);
            }
          }
        }
        case CONCAT, ALTERNATE -> {
          if (node.subs != null) {
            for (Regexp sub : node.subs) {
              stack.push(sub);
            }
          }
        }
        default -> {}
      }
    }
    return false;
  }

  static FixedOffsetLiteral extractFixedOffsetLiteral(Regexp re) {
    Regexp node = unwrapCaptures(re);
    if (node == null || node.op != RegexpOp.CONCAT || node.subs == null) {
      return null;
    }
    FixedOffsetLiteral best = null;
    int bestScore = 0;
    AsciiWidthRange prefixWidth = AsciiWidthRange.ZERO;

    for (int index = 0; index < node.subs.size(); ) {
      String literalPart = extractExactAsciiLiteral(node.subs.get(index));
      if (literalPart != null) {
        StringBuilder literal = new StringBuilder(literalPart);
        int next = index + 1;
        while (next < node.subs.size()) {
          String nextPart = extractExactAsciiLiteral(node.subs.get(next));
          if (nextPart == null) {
            break;
          }
          literal.append(nextPart);
          next++;
        }
        if (index > 0 && (prefixWidth.minWidth > 0 || prefixWidth.maxWidth > 0)) {
          int minimumLiteralLength = prefixWidth.discreteWidths != null ? 1 : 2;
          if (literal.length() >= minimumLiteralLength) {
            int candidateScore = RarityOracle.literalSelectivityScore(literal);
            if (best == null || candidateScore > bestScore) {
              best =
                  new FixedOffsetLiteral(
                      literal.toString(),
                      prefixWidth.minWidth,
                      prefixWidth.maxWidth,
                      prefixWidth.discreteWidths);
              bestScore = candidateScore;
            }
          }
        }
        prefixWidth = concatenateWidths(prefixWidth, AsciiWidthRange.exact(literal.length()));
        if (!prefixWidth.isValid()) {
          break;
        }
        index = next;
        continue;
      }

      prefixWidth = concatenateWidths(prefixWidth, computeAsciiWidthRange(node.subs.get(index)));
      if (!prefixWidth.isValid()) {
        break;
      }
      index++;
    }
    return best;
  }

  private static AsciiWidthRange computeAsciiWidthRange(Regexp re) {
    return new AsciiWidthRangeWalker().walk(re, AsciiWidthRange.INVALID);
  }

  private static final class AsciiWidthRangeWalker extends Walker<AsciiWidthRange> {
    @Override
    protected AsciiWidthRange postVisit(
        Regexp node,
        AsciiWidthRange parentArg,
        AsciiWidthRange preArg,
        List<AsciiWidthRange> childArgs) {
      return switch (node.op) {
        case CAPTURE, NON_CAPTURE ->
            childArgs.isEmpty() ? AsciiWidthRange.INVALID : childArgs.getFirst();
        case EMPTY_MATCH,
            BEGIN_LINE,
            END_LINE,
            BEGIN_TEXT,
            END_TEXT,
            WORD_BOUNDARY,
            NO_WORD_BOUNDARY ->
            AsciiWidthRange.ZERO;
        case LITERAL ->
            node.rune >= 0 && node.rune < 128 && (node.flags & ParseFlags.FOLD_CASE) == 0
                ? AsciiWidthRange.ONE
                : AsciiWidthRange.INVALID;
        case LITERAL_STRING -> literalStringWidth(node);
        case CHAR_CLASS -> characterClassWidth(node);
        case REPEAT -> repeatWidth(node, childArgs);
        case QUEST -> optionalWidth(childArgs);
        case ALTERNATE -> alternateWidth(childArgs);
        case CONCAT -> concatenateWidths(childArgs);
        default -> AsciiWidthRange.INVALID;
      };
    }

    @Override
    protected AsciiWidthRange shortVisit(Regexp re, AsciiWidthRange parentArg) {
      return AsciiWidthRange.INVALID;
    }

    private static AsciiWidthRange literalStringWidth(Regexp node) {
      if ((node.flags & ParseFlags.FOLD_CASE) != 0 || node.runes == null) {
        return AsciiWidthRange.INVALID;
      }
      for (int rune : node.runes) {
        if (rune < 0 || rune >= 128) {
          return AsciiWidthRange.INVALID;
        }
      }
      return AsciiWidthRange.exact(node.runes.length);
    }

    private static AsciiWidthRange characterClassWidth(Regexp node) {
      if (node.charClass == null || node.charClass.isEmpty()) {
        return AsciiWidthRange.INVALID;
      }
      return node.charClass.hi(node.charClass.numRanges() - 1) < 128
          ? AsciiWidthRange.ONE
          : AsciiWidthRange.NON_DISCRETE_ONE;
    }

    private static AsciiWidthRange repeatWidth(Regexp node, List<AsciiWidthRange> childArgs) {
      if (node.min < 0 || node.max < 0 || childArgs.isEmpty()) {
        return AsciiWidthRange.INVALID;
      }
      AsciiWidthRange child = childArgs.getFirst();
      if (!child.isValid()) {
        return AsciiWidthRange.INVALID;
      }
      int minWidth = multiplyWidth(child.minWidth, node.min);
      int maxWidth = multiplyWidth(child.maxWidth, node.max);
      if (minWidth < 0 || maxWidth < 0) {
        return AsciiWidthRange.INVALID;
      }
      if (child.discreteWidths != null && child.isExact() && node.max - node.min <= 8) {
        int[] discrete = new int[node.max - node.min + 1];
        for (int index = 0; index < discrete.length; index++) {
          int width = multiplyWidth(child.minWidth, node.min + index);
          if (width < 0) {
            return AsciiWidthRange.INVALID;
          }
          discrete[index] = width;
        }
        return new AsciiWidthRange(minWidth, maxWidth, discrete);
      }
      return new AsciiWidthRange(minWidth, maxWidth, null);
    }

    private static AsciiWidthRange optionalWidth(List<AsciiWidthRange> childArgs) {
      if (childArgs.isEmpty() || !childArgs.getFirst().isValid()) {
        return AsciiWidthRange.INVALID;
      }
      AsciiWidthRange child = childArgs.getFirst();
      if (child.discreteWidths == null) {
        return new AsciiWidthRange(0, child.maxWidth, null);
      }
      TreeSet<Integer> discrete = new TreeSet<>();
      discrete.add(0);
      for (int width : child.discreteWidths) {
        discrete.add(width);
      }
      return new AsciiWidthRange(
          0,
          child.maxWidth,
          discrete.size() <= 16 ? discrete.stream().mapToInt(Integer::intValue).toArray() : null);
    }

    private static AsciiWidthRange alternateWidth(List<AsciiWidthRange> childArgs) {
      if (childArgs.isEmpty()) {
        return AsciiWidthRange.INVALID;
      }
      int minWidth = Integer.MAX_VALUE;
      int maxWidth = Integer.MIN_VALUE;
      TreeSet<Integer> discrete = new TreeSet<>();
      boolean allDiscrete = true;
      for (AsciiWidthRange child : childArgs) {
        if (!child.isValid()) {
          return AsciiWidthRange.INVALID;
        }
        minWidth = Math.min(minWidth, child.minWidth);
        maxWidth = Math.max(maxWidth, child.maxWidth);
        if (allDiscrete && child.discreteWidths != null) {
          for (int width : child.discreteWidths) {
            discrete.add(width);
          }
        } else {
          allDiscrete = false;
        }
      }
      return new AsciiWidthRange(
          minWidth,
          maxWidth,
          allDiscrete && discrete.size() <= 8
              ? discrete.stream().mapToInt(Integer::intValue).toArray()
              : null);
    }
  }

  private static AsciiWidthRange concatenateWidths(List<AsciiWidthRange> widths) {
    AsciiWidthRange result = AsciiWidthRange.ZERO;
    for (AsciiWidthRange width : widths) {
      result = concatenateWidths(result, width);
      if (!result.isValid()) {
        return result;
      }
    }
    return result;
  }

  private static AsciiWidthRange concatenateWidths(AsciiWidthRange left, AsciiWidthRange right) {
    if (!left.isValid() || !right.isValid()) {
      return AsciiWidthRange.INVALID;
    }
    int minWidth = addWidth(left.minWidth, right.minWidth);
    int maxWidth = addWidth(left.maxWidth, right.maxWidth);
    if (minWidth < 0 || maxWidth < 0) {
      return AsciiWidthRange.INVALID;
    }
    int[] discrete = null;
    if (left.discreteWidths != null
        && right.discreteWidths != null
        && left.discreteWidths.length * right.discreteWidths.length <= 16) {
      TreeSet<Integer> combined = new TreeSet<>();
      for (int leftWidth : left.discreteWidths) {
        for (int rightWidth : right.discreteWidths) {
          int width = addWidth(leftWidth, rightWidth);
          if (width < 0) {
            return AsciiWidthRange.INVALID;
          }
          combined.add(width);
        }
      }
      discrete = combined.stream().mapToInt(Integer::intValue).toArray();
    }
    return new AsciiWidthRange(minWidth, maxWidth, discrete);
  }

  private static int addWidth(int left, int right) {
    return left > Integer.MAX_VALUE - right ? -1 : left + right;
  }

  private static int multiplyWidth(int width, int count) {
    return width != 0 && count > Integer.MAX_VALUE / width ? -1 : width * count;
  }

  static MultiAnchorDescriptor.Anchor extractLiteralAnchor(Regexp re, int flags) {
    if (re == null) {
      return null;
    }
    Regexp unwrapped = unwrapCaptures(re);
    if (unwrapped == null) {
      return null;
    }
    boolean foldCase =
        (flags & Pattern.CASE_INSENSITIVE) != 0 || (unwrapped.flags & ParseFlags.FOLD_CASE) != 0;

    if (unwrapped.op == RegexpOp.LITERAL_STRING
        && unwrapped.runes != null
        && unwrapped.runes.length >= 1) {
      String lit = new String(unwrapped.runes, 0, unwrapped.runes.length);
      return MultiAnchorDescriptor.Anchor.Single.create(lit, foldCase);
    }
    if (unwrapped.op == RegexpOp.LITERAL) {
      String lit = new String(Character.toChars(unwrapped.rune));
      return MultiAnchorDescriptor.Anchor.Single.create(lit, foldCase);
    }
    if (unwrapped.op == RegexpOp.CONCAT && unwrapped.subs != null) {
      String lit = extractExactAsciiLiteralIgnoringCase(unwrapped);
      if (lit != null) {
        return MultiAnchorDescriptor.Anchor.Single.create(lit, foldCase);
      }
    }
    if (unwrapped.op == RegexpOp.ALTERNATE) {
      MultiAnchorDescriptor.Anchor.Alternation alt = extractAlternationAnchor(unwrapped, flags);
      if (alt != null) {
        return alt;
      }
      CharClassScanInfo scanInfo = extractCharClassPrefix(unwrapped);
      if (scanInfo != null) {
        return MultiAnchorDescriptor.Anchor.CharClass.create(scanInfo);
      }
    }
    if (unwrapped.op == RegexpOp.CHAR_CLASS && unwrapped.charClass != null) {
      AsciiBitmap bm = buildAsciiBitmapFromCharClass(unwrapped.charClass);
      if (bm != null && !bm.isEmpty() && bm.cardinality() <= 32) {
        CharClassScanInfo scanInfo = CharClassScanInfo.fromCharClass(unwrapped.charClass);
        if (scanInfo != null) {
          return MultiAnchorDescriptor.Anchor.CharClass.create(scanInfo);
        }
      }
    }
    return null;
  }

  static MultiAnchorDescriptor.Anchor.Alternation extractAlternationAnchor(Regexp re, int flags) {
    if (re == null) {
      return null;
    }
    re = unwrapCaptures(re);
    if (re == null || re.op != RegexpOp.ALTERNATE || re.nsub() < 2) {
      return null;
    }
    boolean globalFold =
        (flags & Pattern.CASE_INSENSITIVE) != 0 || (re.flags & ParseFlags.FOLD_CASE) != 0;
    String[] literals = new String[re.nsub()];
    for (int i = 0; i < re.nsub(); i++) {
      Regexp branch = unwrapCaptures(re.subs.get(i));
      if (branch == null) {
        return null;
      }
      boolean branchFold = globalFold || (branch.flags & ParseFlags.FOLD_CASE) != 0;
      if (branchFold != globalFold) {
        return null;
      }
      String lit = extractExactAsciiLiteralIgnoringCase(branch);
      if (lit == null || lit.isEmpty()) {
        return null;
      }
      literals[i] = lit;
    }
    return MultiAnchorDescriptor.Anchor.Alternation.create(literals, globalFold);
  }

  static PrefixResult extractPrefix(Regexp re) {
    PrefixResult direct = extractPrefixFromCandidate(firstPrefixCandidate(re));
    return direct.prefix() != null ? direct : extractUnicodeFoldedPrefix(re);
  }

  static PrefixResult extractUnicodeFoldedPrefix(Regexp re) {
    Deque<Regexp> work = new ArrayDeque<>();
    work.add(re);
    StringBuilder prefix = new StringBuilder();
    boolean sawFoldClass = false;

    while (!work.isEmpty()) {
      Regexp node = unwrapCaptures(work.removeFirst());
      if (node == null) {
        continue;
      }
      if (node.op == RegexpOp.CONCAT) {
        for (int i = node.subs.size() - 1; i >= 0; i--) {
          work.addFirst(node.subs.get(i));
        }
        continue;
      }
      if (isLeadingZeroWidth(node)) {
        continue;
      }

      if (node.op == RegexpOp.CHAR_CLASS) {
        int representative = simpleFoldClassRepresentative(node.charClass);
        if (representative < 0) {
          break;
        }
        prefix.appendCodePoint(representative);
        sawFoldClass = true;
        continue;
      }

      if (node.op == RegexpOp.LITERAL) {
        if (!appendFoldCompatibleLiteral(prefix, node.rune, node.flags)) {
          break;
        }
        continue;
      }
      if (node.op == RegexpOp.LITERAL_STRING && node.runes != null) {
        boolean compatible = true;
        for (int rune : node.runes) {
          if (!appendFoldCompatibleLiteral(prefix, rune, node.flags)) {
            compatible = false;
            break;
          }
        }
        if (compatible) {
          continue;
        }
      }
      break;
    }

    return sawFoldClass && !prefix.isEmpty()
        ? new PrefixResult(prefix.toString().toLowerCase(Locale.ROOT), true)
        : new PrefixResult(null, false);
  }

  private static boolean appendFoldCompatibleLiteral(StringBuilder prefix, int rune, int flags) {
    if ((flags & ParseFlags.FOLD_CASE) == 0 && Inst.simpleFold(rune) != rune) {
      return false;
    }
    prefix.appendCodePoint(rune);
    return true;
  }

  private static int simpleFoldClassRepresentative(CharClass charClass) {
    if (charClass == null || charClass.isEmpty()) {
      return -1;
    }
    int representative = charClass.lo(0);
    CharClass expected =
        literalCharClass(representative, ParseFlags.FOLD_CASE | ParseFlags.UNICODE_CASE);
    if (expected.numRanges() != charClass.numRanges()) {
      return -1;
    }
    for (int i = 0; i < expected.numRanges(); i++) {
      if (expected.lo(i) != charClass.lo(i) || expected.hi(i) != charClass.hi(i)) {
        return -1;
      }
    }
    int utf8Width = utf8Width(representative);
    int folded = Inst.simpleFold(representative);
    while (folded != representative) {
      if (utf8Width(folded) != utf8Width) {
        return -1;
      }
      folded = Inst.simpleFold(folded);
    }
    return representative;
  }

  private static int utf8Width(int codePoint) {
    if (codePoint <= 0x7F) {
      return 1;
    }
    if (codePoint <= 0x7FF) {
      return 2;
    }
    return codePoint <= 0xFFFF ? 3 : 4;
  }

  private static PrefixResult extractPrefixFromCandidate(Regexp node) {
    if (node == null) {
      return new PrefixResult(null, false);
    }

    boolean foldCase = (node.flags & ParseFlags.FOLD_CASE) != 0;
    StringBuilder sb = new StringBuilder();
    if (node.op == RegexpOp.LITERAL) {
      sb.appendCodePoint(node.rune);
    } else if (node.op == RegexpOp.LITERAL_STRING && node.runes != null) {
      for (int r : node.runes) {
        sb.appendCodePoint(r);
      }
    } else {
      return new PrefixResult(null, false);
    }

    if (sb.isEmpty()) {
      return new PrefixResult(null, false);
    }

    String prefix = foldCase ? sb.toString().toLowerCase(Locale.ROOT) : sb.toString();
    return new PrefixResult(prefix, foldCase);
  }

  static Regexp firstPrefixCandidate(Regexp re) {
    Deque<Regexp> stack = new ArrayDeque<>();
    stack.push(re);
    while (!stack.isEmpty()) {
      Regexp node = unwrapCaptures(stack.pop());
      if (node == null || isLeadingZeroWidth(node)) {
        continue;
      }
      if (node.op == RegexpOp.CONCAT) {
        for (int i = node.subs.size() - 1; i >= 0; i--) {
          stack.push(node.subs.get(i));
        }
      } else {
        return node;
      }
    }
    return null;
  }

  static Regexp firstPrefixCandidateAfterTextAnchor(Regexp re) {
    Deque<Regexp> stack = new ArrayDeque<>();
    stack.push(re);
    boolean sawTextAnchor = false;
    while (!stack.isEmpty()) {
      Regexp node = unwrapCaptures(stack.pop());
      if (node == null) {
        continue;
      }
      if (node.op == RegexpOp.CONCAT && node.subs != null) {
        for (int i = node.subs.size() - 1; i >= 0; i--) {
          stack.push(node.subs.get(i));
        }
        continue;
      }
      if (!sawTextAnchor) {
        if (isLeadingZeroWidth(node)) {
          continue;
        }
        if (node.op != RegexpOp.BEGIN_TEXT) {
          return null;
        }
        sawTextAnchor = true;
        continue;
      }
      if (!isLeadingZeroWidth(node)) {
        return node;
      }
    }
    return null;
  }

  static String extractExactAsciiLiteral(Regexp re) {
    if (re == null) {
      return null;
    }
    StringBuilder literal = new StringBuilder();
    Deque<Regexp> pending = new ArrayDeque<>();
    pending.push(re);
    while (!pending.isEmpty()) {
      Regexp node = unwrapCaptures(pending.pop());
      if (node == null || (node.flags & ParseFlags.FOLD_CASE) != 0) {
        return null;
      }
      if (node.op == RegexpOp.LITERAL && node.rune >= 0 && node.rune < 128) {
        literal.append((char) node.rune);
        continue;
      }
      if (node.op == RegexpOp.LITERAL_STRING && node.runes != null && node.runes.length > 0) {
        for (int rune : node.runes) {
          if (rune < 0 || rune >= 128) {
            return null;
          }
          literal.append((char) rune);
        }
        continue;
      }
      if (node.op == RegexpOp.CONCAT && node.subs != null) {
        for (int index = node.subs.size() - 1; index >= 0; index--) {
          pending.push(node.subs.get(index));
        }
        continue;
      }
      return null;
    }
    return literal.isEmpty() ? null : literal.toString();
  }

  static String extractExactAsciiLiteralIgnoringCase(Regexp re) {
    if (re == null) {
      return null;
    }
    StringBuilder literal = new StringBuilder();
    Deque<Regexp> pending = new ArrayDeque<>();
    pending.push(re);
    while (!pending.isEmpty()) {
      Regexp node = unwrapCaptures(pending.pop());
      if (node == null) {
        return null;
      }
      if (node.op == RegexpOp.LITERAL && node.rune >= 0 && node.rune < 128) {
        literal.append((char) node.rune);
        continue;
      }
      if (node.op == RegexpOp.LITERAL_STRING && node.runes != null && node.runes.length > 0) {
        for (int rune : node.runes) {
          if (rune < 0 || rune >= 128) {
            return null;
          }
          literal.append((char) rune);
        }
        continue;
      }
      if (node.op == RegexpOp.CONCAT && node.subs != null) {
        for (int index = node.subs.size() - 1; index >= 0; index--) {
          pending.push(node.subs.get(index));
        }
        continue;
      }
      return null;
    }
    return literal.isEmpty() ? null : literal.toString();
  }

  static CharClassScanInfo extractCharClassPrefix(Regexp re) {
    CharClassBuilder builder = new CharClassBuilder();
    Deque<Regexp> work = new ArrayDeque<>();
    work.add(re);

    while (!work.isEmpty()) {
      Regexp node = work.removeLast();

      for (; ; ) {
        node = unwrapCaptures(node);
        if (node == null) {
          return null;
        }
        if (node.op == RegexpOp.CONCAT && node.nsub() > 0) {
          int i = 0;
          while (i < node.nsub() && isLeadingZeroWidth(node.subs.get(i))) {
            i++;
          }
          if (i < node.nsub()) {
            node = node.subs.get(i);
            continue;
          }
          return null;
        }
        if (node.op == RegexpOp.PLUS || (node.op == RegexpOp.REPEAT && node.min >= 1)) {
          node = node.sub();
          continue;
        }
        break;
      }

      switch (node.op) {
        case LITERAL -> {
          builder.addCharClass(literalCharClass(node.rune, node.flags));
        }
        case LITERAL_STRING -> {
          if (node.runes == null || node.runes.length == 0) {
            return null;
          }
          builder.addCharClass(literalCharClass(node.runes[0], node.flags));
        }
        case CHAR_CLASS -> {
          if (node.charClass == null || node.charClass.isEmpty()) {
            return null;
          }
          builder.addCharClass(node.charClass);
        }
        case ALTERNATE -> {
          if (node.nsub() == 0) {
            return null;
          }
          for (Regexp sub : node.subs) {
            work.add(sub);
          }
        }
        default -> {
          return null;
        }
      }
    }

    CharClass cc = builder.build();
    if (cc.isEmpty()) {
      return null;
    }
    if (cc.numRunes() > 0x80000) {
      return null;
    }
    return CharClassScanInfo.fromCharClass(cc);
  }

  private static boolean isDotCharClass(CharClass cc) {
    if (cc == null) {
      return false;
    }
    return !cc.contains('\n')
        && cc.contains('a')
        && cc.contains(' ')
        && cc.contains('0')
        && cc.numRanges() <= 6
        && cc.numRunes() > 1000;
  }

  static MultiAnchorDescriptor.Gap classifyGap(Regexp re, int flags) {
    if (re == null || AstAnalysis.analyze(re).hasUserCaptures()) {
      return null;
    }
    re = unwrapCaptures(re);
    if (re == null) {
      return null;
    }
    if (re.op == RegexpOp.WORD_BOUNDARY) {
      return MultiAnchorDescriptor.Gap.WORD_BOUNDARY;
    }
    if (re.op == RegexpOp.NO_WORD_BOUNDARY) {
      return MultiAnchorDescriptor.Gap.NO_WORD_BOUNDARY;
    }
    if (re.op == RegexpOp.BEGIN_LINE) {
      return MultiAnchorDescriptor.Gap.LINE_START;
    }
    if (re.op == RegexpOp.END_LINE) {
      return MultiAnchorDescriptor.Gap.LINE_END;
    }
    boolean greedy = (re.flags & ParseFlags.NON_GREEDY) == 0;
    if (re.op == RegexpOp.STAR) {
      Regexp sub = unwrapCaptures(re.sub());
      if (sub != null && sub.op == RegexpOp.ANY_CHAR) {
        boolean dotAll =
            (flags & Pattern.DOTALL) != 0
                || (re.flags & (ParseFlags.DOT_NL | ParseFlags.MATCH_NL)) != 0
                || (sub.flags & (ParseFlags.DOT_NL | ParseFlags.MATCH_NL)) != 0;
        if (dotAll) {
          return greedy
              ? MultiAnchorDescriptor.Gap.ANY_STAR_GREEDY
              : MultiAnchorDescriptor.Gap.ANY_STAR_LAZY;
        } else {
          return greedy
              ? MultiAnchorDescriptor.Gap.SINGLE_LINE_ANY_STAR_GREEDY
              : MultiAnchorDescriptor.Gap.SINGLE_LINE_ANY_STAR_LAZY;
        }
      }
      if (sub != null && sub.op == RegexpOp.CHAR_CLASS) {
        if (isDotCharClass(sub.charClass)) {
          return greedy
              ? MultiAnchorDescriptor.Gap.SINGLE_LINE_ANY_STAR_GREEDY
              : MultiAnchorDescriptor.Gap.SINGLE_LINE_ANY_STAR_LAZY;
        }
        AsciiBitmap bitmap = buildAsciiBitmapFromCharClass(sub.charClass);
        return new MultiAnchorDescriptor.Gap(
            MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT,
            0,
            Integer.MAX_VALUE,
            bitmap,
            greedy);
      }
    } else if (re.op == RegexpOp.PLUS) {
      Regexp sub = unwrapCaptures(re.sub());
      if (sub != null && sub.op == RegexpOp.ANY_CHAR) {
        boolean dotAll =
            (flags & Pattern.DOTALL) != 0
                || (re.flags & (ParseFlags.DOT_NL | ParseFlags.MATCH_NL)) != 0
                || (sub.flags & (ParseFlags.DOT_NL | ParseFlags.MATCH_NL)) != 0;
        return dotAll
            ? new MultiAnchorDescriptor.Gap(
                MultiAnchorDescriptor.GapKind.ANY_STAR, 1, Integer.MAX_VALUE, null, greedy)
            : new MultiAnchorDescriptor.Gap(
                MultiAnchorDescriptor.GapKind.SINGLE_LINE_ANY_STAR,
                1,
                Integer.MAX_VALUE,
                null,
                greedy);
      }
      if (sub != null && sub.op == RegexpOp.CHAR_CLASS) {
        if (isDotCharClass(sub.charClass)) {
          return new MultiAnchorDescriptor.Gap(
              MultiAnchorDescriptor.GapKind.SINGLE_LINE_ANY_STAR,
              1,
              Integer.MAX_VALUE,
              null,
              greedy);
        }
        AsciiBitmap bitmap = buildAsciiBitmapFromCharClass(sub.charClass);
        return new MultiAnchorDescriptor.Gap(
            MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT,
            1,
            Integer.MAX_VALUE,
            bitmap,
            greedy);
      }
    } else if (re.op == RegexpOp.REPEAT) {
      Regexp sub = unwrapCaptures(re.sub());
      int max = re.max == -1 ? Integer.MAX_VALUE : re.max;
      if (sub != null && sub.op == RegexpOp.ANY_CHAR) {
        boolean dotAll =
            (flags & Pattern.DOTALL) != 0
                || (re.flags & (ParseFlags.DOT_NL | ParseFlags.MATCH_NL)) != 0
                || (sub.flags & (ParseFlags.DOT_NL | ParseFlags.MATCH_NL)) != 0;
        return dotAll
            ? new MultiAnchorDescriptor.Gap(
                MultiAnchorDescriptor.GapKind.ANY_STAR, re.min, max, null, greedy)
            : new MultiAnchorDescriptor.Gap(
                MultiAnchorDescriptor.GapKind.SINGLE_LINE_ANY_STAR, re.min, max, null, greedy);
      }
      if (sub != null && sub.op == RegexpOp.CHAR_CLASS) {
        if (isDotCharClass(sub.charClass)) {
          return new MultiAnchorDescriptor.Gap(
              MultiAnchorDescriptor.GapKind.SINGLE_LINE_ANY_STAR, re.min, max, null, greedy);
        }
        AsciiBitmap bitmap = buildAsciiBitmapFromCharClass(sub.charClass);
        return new MultiAnchorDescriptor.Gap(
            MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT, re.min, max, bitmap, greedy);
      }
    } else if (re.op == RegexpOp.QUEST) {
      Regexp sub = unwrapCaptures(re.sub());
      if (sub != null && sub.op == RegexpOp.ANY_CHAR) {
        boolean dotAll =
            (flags & Pattern.DOTALL) != 0
                || (re.flags & (ParseFlags.DOT_NL | ParseFlags.MATCH_NL)) != 0
                || (sub.flags & (ParseFlags.DOT_NL | ParseFlags.MATCH_NL)) != 0;
        return dotAll
            ? new MultiAnchorDescriptor.Gap(
                MultiAnchorDescriptor.GapKind.ANY_STAR, 0, 1, null, greedy)
            : new MultiAnchorDescriptor.Gap(
                MultiAnchorDescriptor.GapKind.SINGLE_LINE_ANY_STAR, 0, 1, null, greedy);
      }
      if (sub != null && sub.op == RegexpOp.CHAR_CLASS) {
        if (isDotCharClass(sub.charClass)) {
          return new MultiAnchorDescriptor.Gap(
              MultiAnchorDescriptor.GapKind.SINGLE_LINE_ANY_STAR, 0, 1, null, greedy);
        }
        AsciiBitmap bitmap = buildAsciiBitmapFromCharClass(sub.charClass);
        return new MultiAnchorDescriptor.Gap(
            MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT, 0, 1, bitmap, greedy);
      }
    } else if (re.op == RegexpOp.CHAR_CLASS) {
      if (isDotCharClass(re.charClass)) {
        return new MultiAnchorDescriptor.Gap(
            MultiAnchorDescriptor.GapKind.SINGLE_LINE_ANY_STAR, 1, 1, null, true);
      }
      AsciiBitmap bitmap = buildAsciiBitmapFromCharClass(re.charClass);
      return new MultiAnchorDescriptor.Gap(
          MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT, 1, 1, bitmap, true);
    }
    return null;
  }

  static AsciiBitmap buildAsciiBitmapFromCharClass(CharClass cc) {
    if (cc == null || cc.isEmpty()) {
      return null;
    }
    AsciiBitmap.Builder builder = new AsciiBitmap.Builder();
    boolean hasAscii = false;
    for (int i = 0; i < cc.numRanges(); i++) {
      int lo = cc.lo(i);
      int hi = cc.hi(i);
      if (lo < 128) {
        builder.addRange(lo, Math.min(127, hi));
        hasAscii = true;
      }
    }
    return hasAscii ? builder.build() : null;
  }

  private static CharClass literalCharClass(int cp, int flags) {
    CharClassBuilder ccb = new CharClassBuilder();
    if ((flags & ParseFlags.FOLD_CASE) == 0) {
      ccb.addRange(cp, cp);
    } else if ((flags & ParseFlags.UNICODE_CASE) == 0) {
      UnicodeCaseFolding.addAsciiFoldedRange(ccb, cp, cp);
    } else {
      UnicodeCaseFolding.addUnicodeFoldedRange(ccb, cp, cp);
    }
    return ccb.build();
  }

  private static Regexp unwrapCaptures(Regexp re) {
    Regexp node = re;
    while (node != null && (node.op == RegexpOp.CAPTURE || node.op == RegexpOp.NON_CAPTURE)) {
      node = node.sub();
    }
    return node;
  }

  static Prog extractReversePrefixProg(Regexp re, MultiAnchorDescriptor multiAnchor) {
    if (re == null || multiAnchor == null) {
      return null;
    }
    re = Simplifier.simplify(unwrapCaptures(re));
    if (re == null || re.op != RegexpOp.CONCAT || re.nsub() < 2) {
      return null;
    }
    int startIdx = 0;
    while (startIdx < re.nsub() && isLeadingZeroWidth(re.subs.get(startIdx))) {
      startIdx++;
    }
    if (startIdx >= re.nsub() - 1) {
      return null;
    }
    MultiAnchorDescriptor.Anchor anchor = multiAnchor.firstSegment().anchor();
    String targetLiteral = anchor.primaryLiteral();
    int anchorIdx = re.nsub() - 1;
    for (int i = startIdx + 1; i < re.nsub(); i++) {
      List<Regexp> tailSubs = re.subs.subList(i, re.nsub());
      Regexp tail = tailSubs.size() == 1 ? tailSubs.getFirst() : Regexp.concat(tailSubs, 0);
      MultiAnchorDescriptor.Anchor a = extractTailAnchor(tail, 0);
      if (a != null && Objects.equals(a.primaryLiteral(), targetLiteral)) {
        anchorIdx = i;
        break;
      }
    }
    List<Regexp> prefixSubs = re.subs.subList(startIdx, anchorIdx);
    Regexp prefix = prefixSubs.size() == 1 ? prefixSubs.getFirst() : Regexp.concat(prefixSubs, 0);
    return Compiler.compileForDfa(prefix, true);
  }

  private static boolean isLeadingZeroWidth(Regexp re) {
    return switch (re.op) {
      case EMPTY_MATCH, WORD_BOUNDARY, NO_WORD_BOUNDARY -> true;
      default -> false;
    };
  }
}
