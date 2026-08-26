// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.safere.Pattern.DisjointRequiredLiterals;

/**
 * Extracts whole-input rejection metadata (required literals, character classes, disjoint literals,
 * end-anchored suffixes, and infix sequences) from a regular expression AST.
 */
final class RejectDescriptorCompiler {
  private static final int MAX_DISJOINT_REQUIRED_LITERALS = 4;

  record SuffixInfo(String suffix, boolean wasDollar, boolean unixLines, boolean foldCase) {
    SuffixInfo(String suffix, boolean wasDollar) {
      this(suffix, wasDollar, false, false);
    }

    SuffixInfo(String suffix, boolean wasDollar, boolean unixLines) {
      this(suffix, wasDollar, unixLines, false);
    }
  }

  record EndAnchoredCharClassInfo(AsciiBitmap bitmap, boolean wasDollar, boolean unixLines) {
    EndAnchoredCharClassInfo(AsciiBitmap bitmap, boolean wasDollar) {
      this(bitmap, wasDollar, false);
    }
  }

  private RejectDescriptorCompiler() {}

  /** Extracts whole-input rejection metadata from the AST. */
  static RejectDescriptor compile(
      Regexp metadataAst, int flags, MultiAnchorDescriptor multiAnchor, boolean anchorStart) {
    SuffixInfo endAnchoredSuffix = extractEndAnchoredSuffix(metadataAst, flags);
    EndAnchoredCharClassInfo endAnchoredCharClass =
        endAnchoredSuffix == null ? extractEndAnchoredCharClass(metadataAst, flags) : null;
    String startAnchorNeedle = extractStartAnchorNeedle(multiAnchor);
    CharClassScanInfo ccPrefix =
        multiAnchor != null
                && multiAnchor.firstSegment().gap().kind() == MultiAnchorDescriptor.GapKind.EMPTY
                && multiAnchor.firstSegment().anchor()
                    instanceof MultiAnchorDescriptor.Anchor.CharClass cc
            ? cc.scanInfo()
            : null;
    String suffixStr = endAnchoredSuffix != null ? endAnchoredSuffix.suffix() : null;
    String startPrefixNeedle =
        multiAnchor != null
                && multiAnchor.firstSegment().gap().kind() == MultiAnchorDescriptor.GapKind.EMPTY
                && multiAnchor.firstSegment().anchor()
                    instanceof MultiAnchorDescriptor.Anchor.Single single
                && !single.foldCase()
            ? single.literal()
            : null;
    boolean isMultiAnchorWithStarGap = false;
    if (multiAnchor != null && multiAnchor.numSegments() > 1) {
      for (MultiAnchorDescriptor.Gap gap : multiAnchor.gaps()) {
        if (gap.kind() == MultiAnchorDescriptor.GapKind.ANY_STAR
            || gap.kind() == MultiAnchorDescriptor.GapKind.SINGLE_LINE_ANY_STAR) {
          isMultiAnchorWithStarGap = true;
          break;
        }
      }
    }
    RejectDescriptor.InfixSequence infixSequence = null;
    if (!anchorStart) {
      infixSequence = extractRequiredInfixSequence(metadataAst, startPrefixNeedle, suffixStr);
      if (infixSequence == null && multiAnchor != null && isMultiAnchorWithStarGap) {
        infixSequence = multiAnchor.toInfixSequence(startPrefixNeedle, suffixStr);
      }
    }
    String excludeNeedle =
        isMultiAnchorWithStarGap
            ? startPrefixNeedle
            : (startAnchorNeedle != null ? startAnchorNeedle : startPrefixNeedle);
    String requiredLiteral =
        !anchorStart ? extractRequiredLiteral(metadataAst, excludeNeedle, suffixStr) : null;
    if (requiredLiteral == null && multiAnchor != null && !anchorStart) {
      for (MultiAnchorDescriptor.Anchor anchor : multiAnchor.anchors()) {
        if (!anchor.foldCase() && anchor instanceof MultiAnchorDescriptor.Anchor.Single single) {
          String lit = single.literal();
          if (lit != null && lit.length() >= 2) {
            boolean isSubsumedByPrefix =
                excludeNeedle != null
                    && (excludeNeedle.contains(lit) || lit.contains(excludeNeedle));
            boolean isSubsumedBySuffix =
                suffixStr != null && (suffixStr.contains(lit) || lit.contains(suffixStr));
            if (!isSubsumedByPrefix && !isSubsumedBySuffix) {
              requiredLiteral = lit;
              break;
            }
          }
        }
      }
    }
    CharClassScanInfo requiredMatchClass = null;
    boolean hasLongStartAnchor = startAnchorNeedle != null && startAnchorNeedle.length() >= 2;
    if (!anchorStart
        && startPrefixNeedle == null
        && (!hasLongStartAnchor || isMultiAnchorWithStarGap)
        && endAnchoredCharClass == null
        && infixSequence == null) {
      if (ccPrefix == null) {
        requiredMatchClass = extractRequiredMatchClass(metadataAst, true);
      } else {
        CharClassScanInfo candidate = extractRequiredMatchClass(metadataAst, false);
        if (candidate != null && candidate.ranges() != null) {
          int candidateRunes = 0;
          for (int i = 0; i < candidate.ranges().length; i += 2) {
            candidateRunes += (candidate.ranges()[i + 1] - candidate.ranges()[i] + 1);
          }
          int prefixRunes = 0;
          for (int i = 0; i < ccPrefix.ranges().length; i += 2) {
            prefixRunes += (ccPrefix.ranges()[i + 1] - ccPrefix.ranges()[i] + 1);
          }
          if (candidateRunes < prefixRunes) {
            requiredMatchClass = candidate;
          }
        }
      }
    }
    String[] disjointLits = extractDisjointRequiredLiterals(metadataAst);
    DisjointRequiredLiterals disjointRequiredLiterals =
        (!anchorStart
                && startPrefixNeedle == null
                && requiredLiteral == null
                && infixSequence == null)
            ? DisjointRequiredLiterals.create(disjointLits)
            : null;
    RejectDescriptor.InfixSequence finalInfix =
        infixSequence != null
            ? infixSequence
            : (requiredLiteral != null ? RejectDescriptor.InfixSequence.of(requiredLiteral) : null);
    if (requiredMatchClass == null
        && disjointRequiredLiterals == null
        && endAnchoredSuffix == null
        && endAnchoredCharClass == null
        && finalInfix == null) {
      return null;
    }
    return new RejectDescriptor(
        requiredMatchClass,
        disjointRequiredLiterals,
        endAnchoredSuffix,
        endAnchoredCharClass,
        finalInfix);
  }

  static SuffixInfo extractEndAnchoredSuffix(Regexp metadataAst, int flags) {
    Regexp node = unwrapCaptures(metadataAst);
    if (node == null || node.op != RegexpOp.CONCAT || node.nsub() < 2) {
      return null;
    }
    List<Regexp> subs = node.subs;
    Regexp last = unwrapCaptures(subs.get(subs.size() - 1));
    if (last == null || last.op != RegexpOp.END_TEXT) {
      return null;
    }
    if ((flags & Pattern.MULTILINE) != 0 && (last.flags & ParseFlags.WAS_DOLLAR) != 0) {
      return null;
    }
    boolean wasDollar = (last.flags & ParseFlags.WAS_DOLLAR) != 0;
    boolean foldCase = false;

    Deque<String> suffixParts = new ArrayDeque<>();
    int suffixLength = 0;
    for (int i = subs.size() - 2; i >= 0; i--) {
      Regexp sub = unwrapCaptures(subs.get(i));
      if (sub == null) {
        break;
      }
      boolean subFold = (sub.flags & ParseFlags.FOLD_CASE) != 0;
      if (sub.op == RegexpOp.LITERAL) {
        if (subFold && sub.rune > 0x7F) {
          break;
        }
        String part = Character.toString(sub.rune);
        suffixParts.addFirst(part);
        suffixLength += part.length();
        foldCase |= subFold;
      } else if (sub.op == RegexpOp.LITERAL_STRING && sub.runes != null) {
        if (subFold && !isAllAscii(sub.runes)) {
          break;
        }
        String part = new String(sub.runes, 0, sub.runes.length);
        suffixParts.addFirst(part);
        suffixLength += part.length();
        foldCase |= subFold;
      } else {
        break;
      }
    }
    if (suffixParts.isEmpty()) {
      return null;
    }
    StringBuilder suffix = new StringBuilder(suffixLength);
    suffixParts.forEach(suffix::append);
    return new SuffixInfo(
        suffix.toString(), wasDollar, (flags & Pattern.UNIX_LINES) != 0, foldCase);
  }

  private static boolean isAllAscii(int[] runes) {
    for (int r : runes) {
      if (r > 0x7F) {
        return false;
      }
    }
    return true;
  }

  static EndAnchoredCharClassInfo extractEndAnchoredCharClass(Regexp metadataAst, int flags) {
    Regexp node = unwrapCaptures(metadataAst);
    if (node == null || node.op != RegexpOp.CONCAT || node.nsub() < 2) {
      return null;
    }
    List<Regexp> subs = node.subs;
    Regexp last = unwrapCaptures(subs.get(subs.size() - 1));
    if (last == null || last.op != RegexpOp.END_TEXT) {
      return null;
    }
    if ((flags & Pattern.MULTILINE) != 0 && (last.flags & ParseFlags.WAS_DOLLAR) != 0) {
      return null;
    }
    boolean wasDollar = (last.flags & ParseFlags.WAS_DOLLAR) != 0;

    Regexp sub = unwrapCaptures(subs.get(subs.size() - 2));
    if (sub == null) {
      return null;
    }
    while (sub.op == RegexpOp.PLUS || (sub.op == RegexpOp.REPEAT && sub.min >= 1)) {
      sub = unwrapCaptures(sub.sub());
      if (sub == null) {
        return null;
      }
    }
    AsciiBitmap.Builder builder = new AsciiBitmap.Builder();
    if (sub.op == RegexpOp.CHAR_CLASS && addAsciiCharClass(sub.charClass, builder)) {
      boolean unixLines = (flags & Pattern.UNIX_LINES) != 0;
      return new EndAnchoredCharClassInfo(builder.build(), wasDollar, unixLines);
    }
    return null;
  }

  private static boolean addAsciiCharClass(CharClass cc, AsciiBitmap.Builder bitmap) {
    if (cc == null || cc.isEmpty()) {
      return false;
    }
    for (int i = 0; i < cc.numRanges(); i++) {
      if (cc.hi(i) >= 128) {
        return false;
      }
    }
    for (int i = 0; i < cc.numRanges(); i++) {
      bitmap.addRange(cc.lo(i), cc.hi(i));
    }
    return true;
  }

  static CharClassScanInfo extractRequiredMatchClass(Regexp re, boolean inspectAlternation) {
    Regexp node = unwrapRequiredNode(re);
    if (node == null) {
      return null;
    }
    if (node.op != RegexpOp.CONCAT || node.subs == null) {
      CharClass required = requiredCharClass(node, inspectAlternation);
      return required != null ? CharClassScanInfo.fromCharClass(required) : null;
    }
    CharClass mostSelective = null;
    for (Regexp sub : node.subs) {
      CharClass required = requiredCharClass(sub, inspectAlternation);
      if (required != null
          && (mostSelective == null || required.numRunes() < mostSelective.numRunes())) {
        mostSelective = required;
      }
    }
    return mostSelective != null ? CharClassScanInfo.fromCharClass(mostSelective) : null;
  }

  private static CharClass requiredCharClass(Regexp re, boolean inspectAlternation) {
    Regexp node = unwrapRequiredNode(re);
    if (node == null) {
      return null;
    }
    if (node.op == RegexpOp.LITERAL) {
      return literalCharClass(node.rune, node.flags);
    }
    if (node.op == RegexpOp.LITERAL_STRING && node.runes != null && node.runes.length > 0) {
      return literalCharClass(node.runes[0], node.flags);
    }
    if (node.op == RegexpOp.CHAR_CLASS && node.charClass != null) {
      return node.charClass.isEmpty() ? null : node.charClass;
    }
    if (inspectAlternation
        && node.op == RegexpOp.ALTERNATE
        && node.subs != null
        && !node.subs.isEmpty()) {
      CharClassBuilder union = new CharClassBuilder();
      for (Regexp branch : node.subs) {
        CharClass branchClass = requiredAtomicCharClass(branch);
        if (branchClass == null) {
          return null;
        }
        union.addCharClass(branchClass);
      }
      CharClass result = union.build();
      return result.isEmpty() ? null : result;
    }
    return null;
  }

  private static CharClass requiredAtomicCharClass(Regexp re) {
    Regexp node = unwrapRequiredNode(re);
    if (node == null) {
      return null;
    }
    if (node.op == RegexpOp.LITERAL) {
      return literalCharClass(node.rune, node.flags);
    }
    if (node.op == RegexpOp.LITERAL_STRING && node.runes != null && node.runes.length > 0) {
      return literalCharClass(node.runes[0], node.flags);
    }
    if (node.op == RegexpOp.CHAR_CLASS && node.charClass != null && !node.charClass.isEmpty()) {
      return node.charClass;
    }
    return null;
  }

  private static Regexp unwrapRequiredNode(Regexp re) {
    Regexp node = re;
    while (true) {
      if (node.op == RegexpOp.CAPTURE
          || node.op == RegexpOp.NON_CAPTURE
          || node.op == RegexpOp.PLUS) {
        node = node.sub();
        continue;
      }
      if (node.op == RegexpOp.REPEAT) {
        if (node.min == 0) {
          return null;
        }
        node = node.sub();
        continue;
      }
      return node;
    }
  }

  static RejectDescriptor.InfixSequence extractRequiredInfixSequence(
      Regexp re, String excludePrefix, String excludeSuffix) {
    Regexp node = unwrapCaptures(re);
    if (node == null || node.op != RegexpOp.CONCAT || node.subs == null) {
      return null;
    }
    List<String> inOrderCandidates = new ArrayList<>();
    for (Regexp sub : node.subs) {
      Regexp unwrapped = unwrapRequiredNode(sub);
      if (unwrapped != null
          && unwrapped.op == RegexpOp.LITERAL_STRING
          && (unwrapped.flags & ParseFlags.FOLD_CASE) == 0
          && unwrapped.runes != null
          && unwrapped.runes.length >= 2) {
        String candidate = new String(unwrapped.runes, 0, unwrapped.runes.length);
        boolean isSubsumedByPrefix =
            excludePrefix != null
                && (excludePrefix.contains(candidate) || candidate.contains(excludePrefix));
        boolean isSubsumedBySuffix =
            excludeSuffix != null
                && (excludeSuffix.contains(candidate) || candidate.contains(excludeSuffix));
        if (!isSubsumedByPrefix && !isSubsumedBySuffix) {
          inOrderCandidates.add(candidate);
        }
      }
    }

    if (inOrderCandidates.size() < 2) {
      return null;
    }

    int size = Math.min(4, inOrderCandidates.size());
    String[] infixes = new String[size];
    for (int i = 0; i < size; i++) {
      infixes[i] = inOrderCandidates.get(i);
    }

    Integer[] orderBoxed = new Integer[size];
    for (int i = 0; i < size; i++) {
      orderBoxed[i] = i;
    }
    Arrays.sort(
        orderBoxed,
        (a, b) ->
            Integer.compare(
                RarityOracle.literalSelectivityScore(infixes[b]),
                RarityOracle.literalSelectivityScore(infixes[a])));

    int[] checkOrder = new int[size];
    for (int i = 0; i < size; i++) {
      checkOrder[i] = orderBoxed[i];
    }

    return new RejectDescriptor.InfixSequence(infixes, checkOrder);
  }

  static String extractRequiredLiteral(Regexp re) {
    return extractRequiredLiteral(re, null, null);
  }

  static String extractRequiredLiteral(Regexp re, String excludePrefix, String excludeSuffix) {
    String best = null;
    int bestScore = 0;
    Deque<Regexp> pending = new ArrayDeque<>();
    pending.addLast(re);
    while (!pending.isEmpty()) {
      Regexp node = pending.removeLast();
      switch (node.op) {
        case CAPTURE, NON_CAPTURE -> {
          if (node.subs != null) {
            pending.addAll(node.subs);
          }
        }
        case PLUS -> {
          if (node.subs != null && !node.subs.isEmpty()) {
            pending.addLast(node.subs.getFirst());
          }
        }
        case REPEAT -> {
          if (node.min > 0) {
            pending.addLast(node.sub());
          }
        }
        case CONCAT -> {
          if (node.subs != null) {
            StringBuilder sb = new StringBuilder();
            for (Regexp sub : node.subs) {
              if (sub.op == RegexpOp.LITERAL && (sub.flags & ParseFlags.FOLD_CASE) == 0) {
                sb.appendCodePoint(sub.rune);
              } else if (sub.op == RegexpOp.LITERAL_STRING
                  && (sub.flags & ParseFlags.FOLD_CASE) == 0
                  && sub.runes != null) {
                sb.append(new String(sub.runes, 0, sub.runes.length));
              } else {
                if (sb.length() >= 2) {
                  String candidate = sb.toString();
                  boolean isSubsumedByPrefix =
                      excludePrefix != null
                          && (excludePrefix.contains(candidate)
                              || candidate.contains(excludePrefix));
                  boolean isSubsumedBySuffix =
                      excludeSuffix != null
                          && (excludeSuffix.contains(candidate)
                              || candidate.contains(excludeSuffix));
                  if (!isSubsumedByPrefix && !isSubsumedBySuffix) {
                    int candidateScore = RarityOracle.literalSelectivityScore(candidate);
                    if (best == null || candidateScore > bestScore) {
                      best = candidate;
                      bestScore = candidateScore;
                    }
                  }
                }
                sb.setLength(0);
                if (sub.op == RegexpOp.CAPTURE
                    || sub.op == RegexpOp.NON_CAPTURE
                    || sub.op == RegexpOp.PLUS
                    || (sub.op == RegexpOp.REPEAT && sub.min > 0)
                    || sub.op == RegexpOp.CONCAT) {
                  pending.addLast(sub);
                }
              }
            }
            if (sb.length() >= 2) {
              String candidate = sb.toString();
              boolean isSubsumedByPrefix =
                  excludePrefix != null
                      && (excludePrefix.contains(candidate) || candidate.contains(excludePrefix));
              boolean isSubsumedBySuffix =
                  excludeSuffix != null
                      && (excludeSuffix.contains(candidate) || candidate.contains(excludeSuffix));
              if (!isSubsumedByPrefix && !isSubsumedBySuffix) {
                int candidateScore = RarityOracle.literalSelectivityScore(candidate);
                if (best == null || candidateScore > bestScore) {
                  best = candidate;
                  bestScore = candidateScore;
                }
              }
            }
          }
        }
        case LITERAL_STRING -> {
          if ((node.flags & ParseFlags.FOLD_CASE) == 0
              && node.runes != null
              && node.runes.length >= 2) {
            String candidate = new String(node.runes, 0, node.runes.length);
            boolean isSubsumedByPrefix =
                excludePrefix != null
                    && (excludePrefix.contains(candidate) || candidate.contains(excludePrefix));
            boolean isSubsumedBySuffix =
                excludeSuffix != null
                    && (excludeSuffix.contains(candidate) || candidate.contains(excludeSuffix));
            if (!isSubsumedByPrefix && !isSubsumedBySuffix) {
              int candidateScore = RarityOracle.literalSelectivityScore(candidate);
              if (best == null || candidateScore > bestScore) {
                best = candidate;
                bestScore = candidateScore;
              }
            }
          }
        }
        default -> {}
      }
    }
    return best;
  }

  private static String extractStartAnchorNeedle(MultiAnchorDescriptor multiAnchor) {
    if (multiAnchor == null || multiAnchor.numSegments() == 0) {
      return null;
    }
    if (multiAnchor.isReverseAnchor()) {
      MultiAnchorDescriptor.Anchor last = multiAnchor.trailingSegment().anchor();
      if (last instanceof MultiAnchorDescriptor.Anchor.Single single
          && !single.foldCase()
          && single.literal() != null
          && single.literal().length() >= 2) {
        return single.literal();
      }
    }
    MultiAnchorDescriptor.Segment first = multiAnchor.firstSegment();
    if (first.gap().kind() == MultiAnchorDescriptor.GapKind.EMPTY
        || first.gap().kind() == MultiAnchorDescriptor.GapKind.BOUNDED_CLASS_REPEAT) {
      MultiAnchorDescriptor.Anchor a0 = first.anchor();
      if (a0 instanceof MultiAnchorDescriptor.Anchor.Single single
          && !single.foldCase()
          && single.literal() != null
          && single.literal().length() >= 2) {
        return single.literal();
      }
    }
    return null;
  }

  static String[] extractDisjointRequiredLiterals(Regexp re) {
    Regexp node = unwrapCaptures(re);
    if (node == null) {
      return null;
    }
    if (node.op == RegexpOp.PLUS) {
      node = unwrapCaptures(node.sub());
    }
    if (node == null) {
      return null;
    }
    if (node.op == RegexpOp.CONCAT && node.subs != null) {
      if (!node.subs.isEmpty()) {
        Regexp first = unwrapCaptures(node.subs.get(0));
        if (first != null && (first.op == RegexpOp.BEGIN_TEXT || first.op == RegexpOp.BEGIN_LINE)) {
          return null;
        }
      }
      for (Regexp sub : node.subs) {
        String[] disjoint = extractDisjointRequiredLiteralsFromAlternate(sub);
        if (disjoint != null) {
          return disjoint;
        }
      }
      return null;
    }
    return extractDisjointRequiredLiteralsFromAlternate(node);
  }

  private static String[] extractDisjointRequiredLiteralsFromAlternate(Regexp re) {
    Regexp node = unwrapCaptures(re);
    if (node == null) {
      return null;
    }
    if (node.op == RegexpOp.PLUS) {
      node = unwrapCaptures(node.sub());
    }
    if (node == null
        || node.op != RegexpOp.ALTERNATE
        || node.subs == null
        || node.subs.size() < 2) {
      return null;
    }
    Set<String> literalSet = new LinkedHashSet<>();
    for (Regexp branch : node.subs) {
      String req = extractRequiredLiteral(branch);
      if (req == null || req.length() < 2) {
        return null;
      }
      literalSet.add(req);
      if (literalSet.size() > MAX_DISJOINT_REQUIRED_LITERALS) {
        return null;
      }
    }
    List<String> rawList = new ArrayList<>(literalSet);
    List<int[]> rawCodePoints = new ArrayList<>(rawList.size());
    List<int[]> rawFailures = new ArrayList<>(rawList.size());
    for (String literal : rawList) {
      int[] codePoints = literal.codePoints().toArray();
      rawCodePoints.add(codePoints);
      rawFailures.add(literalFailure(codePoints));
    }
    Set<String> pruned = new LinkedHashSet<>();
    for (int i = 0; i < rawList.size(); i++) {
      String s1 = rawList.get(i);
      boolean subsumed = false;
      for (int j = 0; j < rawList.size(); j++) {
        if (i != j) {
          String s2 = rawList.get(j);
          if (containsCodePointSequence(
                  rawCodePoints.get(i), rawCodePoints.get(j), rawFailures.get(j))
              && (s1.length() > s2.length() || (s1.length() == s2.length() && j < i))) {
            subsumed = true;
            break;
          }
        }
      }
      if (!subsumed) {
        pruned.add(s1);
      }
    }
    if (pruned.size() < 2) {
      return null;
    }
    return pruned.toArray(new String[0]);
  }

  private static int[] literalFailure(int[] literal) {
    int[] failure = new int[literal.length];
    int matched = 0;
    for (int index = 1; index < literal.length; index++) {
      while (matched > 0 && literal[index] != literal[matched]) {
        matched = failure[matched - 1];
      }
      if (literal[index] == literal[matched]) {
        matched++;
      }
      failure[index] = matched;
    }
    return failure;
  }

  private static boolean containsCodePointSequence(int[] value, int[] candidate, int[] failure) {
    int matched = 0;
    for (int codePoint : value) {
      while (matched > 0 && codePoint != candidate[matched]) {
        matched = failure[matched - 1];
      }
      if (codePoint == candidate[matched]) {
        matched++;
        if (matched == candidate.length) {
          return true;
        }
      }
    }
    return false;
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
}
