// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Transforms regular expression ASTs by recursively factoring out shared longest common prefixes
 * (LCP) and longest common suffixes (LCS) across branches of {@link RegexpOp#ALTERNATE} nodes.
 *
 * <p>For example, transforms:
 *
 * <pre>
 *   (https://example\.com/users\?id=\d+ | https://example\.com/orders\?id=\d+)
 * </pre>
 *
 * into the canonical linear concatenation:
 *
 * <pre>
 *   https://example\.com/(users|orders)\?id=\d+
 * </pre>
 *
 * enabling the Universal Multi-Anchor Gap-Decomposition engine to convert branching expressions
 * into fast vector SIMD anchor pipelines.
 */
final class AlternationFactorizer {

  private AlternationFactorizer() {}

  /**
   * Recursively factorizes all alternations in the given AST node. Returns a new or transformed
   * {@link Regexp} tree.
   */
  static Regexp factorize(Regexp re) {
    return factorize(re, 0);
  }

  private static Regexp factorize(Regexp re, int depth) {
    if (re == null || depth > 64) {
      return re;
    }
    // Bottom-up recursion: factorize children first
    if (re.subs != null && !re.subs.isEmpty()) {
      List<Regexp> newSubs = new ArrayList<>(re.subs.size());
      boolean changed = false;
      for (Regexp sub : re.subs) {
        Regexp factored = factorize(sub, depth + 1);
        if (factored != sub) {
          changed = true;
        }
        newSubs.add(factored);
      }
      if (changed) {
        re = rebuildWithSubs(re, newSubs);
      }
    }

    if (re.op == RegexpOp.ALTERNATE && re.subs != null && re.subs.size() >= 2) {
      re = factorizeAlternate(re);
    } else if (re.op == RegexpOp.CONCAT && re.subs != null) {
      re = flattenConcat(re);
    }

    return re;
  }

  private static boolean isPureLiteralBranch(Regexp re) {
    if (re == null) {
      return false;
    }
    if (re.op == RegexpOp.LITERAL || re.op == RegexpOp.LITERAL_STRING) {
      return true;
    }
    if (re.op == RegexpOp.CONCAT && re.subs != null) {
      for (Regexp sub : re.subs) {
        if (!isPureLiteralBranch(sub)) {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  private static Regexp factorizeAlternate(Regexp alt) {
    List<Regexp> rawBranches = new ArrayList<>();
    flattenAlternateBranches(alt, rawBranches);
    if (rawBranches.size() < 2) {
      return rawBranches.isEmpty() ? alt : rawBranches.getFirst();
    }

    boolean allPureLiterals = true;
    for (Regexp b : rawBranches) {
      if (!isPureLiteralBranch(b)) {
        allPureLiterals = false;
        break;
      }
    }
    if (allPureLiterals) {
      return alt;
    }

    // Wrap single branches into mutable branch lists of AST nodes
    List<List<Regexp>> branchNodes = new ArrayList<>(rawBranches.size());
    for (Regexp b : rawBranches) {
      List<Regexp> list = new ArrayList<>();
      if (b.op == RegexpOp.CONCAT && b.subs != null) {
        list.addAll(b.subs);
      } else if (b.op != RegexpOp.EMPTY_MATCH) {
        list.add(b);
      }
      branchNodes.add(list);
    }

    List<Regexp> commonPrefixNodes = new ArrayList<>();
    List<Regexp> commonSuffixNodes = new ArrayList<>();

    // If not all branches are pure literals, extract structural common suffix sub-expressions
    if (!allPureLiterals) {
      while (true) {
        boolean allNonEmpty = true;
        for (List<Regexp> list : branchNodes) {
          if (list.isEmpty()) {
            allNonEmpty = false;
            break;
          }
        }
        if (!allNonEmpty) {
          break;
        }
        Regexp last0 = branchNodes.getFirst().getLast();
        boolean allMatch = true;
        for (int i = 1; i < branchNodes.size(); i++) {
          List<Regexp> list = branchNodes.get(i);
          Regexp lastI = list.getLast();
          if (!Simplifier.equal(last0, lastI)) {
            allMatch = false;
            break;
          }
        }
        if (!allMatch) {
          break;
        }
        // Pop from all branches
        for (List<Regexp> list : branchNodes) {
          list.removeLast();
        }
        commonSuffixNodes.addFirst(last0);
      }
    }

    // Extract common suffix literal runes from trailing nodes (only if not pure short keywords, or
    // if suffix length >= 2)
    int[][] branchSuffixRunes = new int[branchNodes.size()][];
    for (int i = 0; i < branchNodes.size(); i++) {
      branchSuffixRunes[i] = extractTrailingLiteralRunes(branchNodes.get(i));
    }
    int lcsLen = commonSuffixLength(branchSuffixRunes);
    if (lcsLen > 0 && (!allPureLiterals || lcsLen >= 2)) {
      int[] sample = branchSuffixRunes[0];
      int[] lcs = Arrays.copyOfRange(sample, sample.length - lcsLen, sample.length);
      for (List<Regexp> list : branchNodes) {
        sliceTrailingRunesFromList(list, lcsLen);
      }
      commonSuffixNodes.addFirst(literalFromRunes(lcs, alt.flags));
    }

    // Extract structural common prefix sub-expressions
    if (!allPureLiterals) {
      while (true) {
        boolean allNonEmpty = true;
        for (List<Regexp> list : branchNodes) {
          if (list.isEmpty()) {
            allNonEmpty = false;
            break;
          }
        }
        if (!allNonEmpty) {
          break;
        }
        Regexp first0 = branchNodes.getFirst().getFirst();
        boolean allMatch = true;
        for (int i = 1; i < branchNodes.size(); i++) {
          List<Regexp> list = branchNodes.get(i);
          Regexp firstI = list.getFirst();
          if (!Simplifier.equal(first0, firstI)) {
            allMatch = false;
            break;
          }
        }
        if (!allMatch) {
          break;
        }
        // Pop from all branches
        for (List<Regexp> list : branchNodes) {
          list.removeFirst();
        }
        commonPrefixNodes.add(first0);
      }
    }

    // Extract common prefix literal runes from leading nodes
    int[][] branchPrefixRunes = new int[branchNodes.size()][];
    for (int i = 0; i < branchNodes.size(); i++) {
      branchPrefixRunes[i] = extractLeadingLiteralRunes(branchNodes.get(i));
    }
    int lcpLen = commonPrefixLength(branchPrefixRunes);
    if (lcpLen > 0 && (!allPureLiterals || lcpLen >= 2)) {
      int[] lcp = Arrays.copyOf(branchPrefixRunes[0], lcpLen);
      for (List<Regexp> list : branchNodes) {
        sliceLeadingRunesFromList(list, lcpLen);
      }
      commonPrefixNodes.add(literalFromRunes(lcp, alt.flags));
    }

    if (commonPrefixNodes.isEmpty() && commonSuffixNodes.isEmpty()) {
      return alt;
    }

    List<Regexp> peeledBranches = new ArrayList<>(branchNodes.size());
    for (List<Regexp> list : branchNodes) {
      if (list.isEmpty()) {
        peeledBranches.add(Regexp.emptyMatch(alt.flags));
      } else if (list.size() == 1) {
        peeledBranches.add(list.getFirst());
      } else {
        peeledBranches.add(flattenConcat(Regexp.concat(list, alt.flags)));
      }
    }

    List<Regexp> concatChildren = new ArrayList<>();
    concatChildren.addAll(commonPrefixNodes);
    concatChildren.add(Regexp.alternate(peeledBranches, alt.flags));
    concatChildren.addAll(commonSuffixNodes);

    return flattenConcat(Regexp.concat(concatChildren, alt.flags));
  }

  private static void flattenAlternateBranches(Regexp alt, List<Regexp> result) {
    if (alt.op == RegexpOp.ALTERNATE && alt.subs != null) {
      for (Regexp sub : alt.subs) {
        flattenAlternateBranches(sub, result);
      }
    } else {
      result.add(alt);
    }
  }

  private static Regexp flattenConcat(Regexp concat) {
    if (concat.op != RegexpOp.CONCAT || concat.subs == null) {
      return concat;
    }
    List<Regexp> flattened = new ArrayList<>();
    for (Regexp sub : concat.subs) {
      if (sub.op == RegexpOp.CONCAT && sub.subs != null) {
        flattened.addAll(sub.subs);
      } else if (sub.op == RegexpOp.NON_CAPTURE && sub.subs != null) {
        for (Regexp child : sub.subs) {
          if (child.op == RegexpOp.CONCAT && child.subs != null) {
            flattened.addAll(child.subs);
          } else if (child.op != RegexpOp.EMPTY_MATCH) {
            flattened.add(child);
          }
        }
      } else if (sub.op != RegexpOp.EMPTY_MATCH) {
        flattened.add(sub);
      }
    }

    // Merge adjacent literals
    List<Regexp> merged = new ArrayList<>();
    for (int i = 0; i < flattened.size(); i++) {
      Regexp cur = flattened.get(i);
      int[] curRunes = extractAllLiteralRunes(cur);
      if (curRunes != null) {
        IntArrayList combined = new IntArrayList(curRunes.length);
        addRunes(combined, curRunes);
        while (i + 1 < flattened.size()) {
          int[] nextRunes = extractAllLiteralRunes(flattened.get(i + 1));
          if (nextRunes == null || flattened.get(i + 1).flags != cur.flags) {
            break;
          }
          addRunes(combined, nextRunes);
          i++;
        }
        merged.add(literalFromRunes(combined.toArray(), cur.flags));
      } else {
        merged.add(cur);
      }
    }

    if (merged.isEmpty()) {
      return Regexp.emptyMatch(concat.flags);
    }
    if (merged.size() == 1) {
      return merged.getFirst();
    }
    return Regexp.concat(merged, concat.flags);
  }

  private static void addRunes(IntArrayList list, int[] runes) {
    for (int r : runes) {
      list.add(r);
    }
  }

  private static int[] extractAllLiteralRunes(Regexp re) {
    if (re == null) {
      return null;
    }
    if (re.op == RegexpOp.LITERAL) {
      return new int[] {re.rune};
    }
    if (re.op == RegexpOp.LITERAL_STRING && re.runes != null) {
      return re.runes;
    }
    return null;
  }

  private static int[] extractLeadingLiteralRunes(List<Regexp> list) {
    if (list == null || list.isEmpty()) {
      return new int[0];
    }
    IntArrayList runes = new IntArrayList();
    for (Regexp sub : list) {
      int[] subRunes = extractAllLiteralRunes(sub);
      if (subRunes == null) {
        break;
      }
      addRunes(runes, subRunes);
    }
    return runes.toArray();
  }

  private static int[] extractTrailingLiteralRunes(List<Regexp> list) {
    if (list == null || list.isEmpty()) {
      return new int[0];
    }
    List<int[]> parts = new ArrayList<>();
    int total = 0;
    for (int i = list.size() - 1; i >= 0; i--) {
      int[] subRunes = extractAllLiteralRunes(list.get(i));
      if (subRunes == null) {
        break;
      }
      parts.add(0, subRunes);
      total += subRunes.length;
    }
    int[] res = new int[total];
    int offset = 0;
    for (int[] p : parts) {
      System.arraycopy(p, 0, res, offset, p.length);
      offset += p.length;
    }
    return res;
  }

  private static int commonPrefixLength(int[][] runeArrays) {
    if (runeArrays == null || runeArrays.length == 0) {
      return 0;
    }
    int minLen = Integer.MAX_VALUE;
    for (int[] arr : runeArrays) {
      if (arr == null || arr.length == 0) {
        return 0;
      }
      minLen = Math.min(minLen, arr.length);
    }
    for (int idx = 0; idx < minLen; idx++) {
      int expected = runeArrays[0][idx];
      for (int i = 1; i < runeArrays.length; i++) {
        if (runeArrays[i][idx] != expected) {
          return idx;
        }
      }
    }
    return minLen;
  }

  private static int commonSuffixLength(int[][] runeArrays) {
    if (runeArrays == null || runeArrays.length == 0) {
      return 0;
    }
    int minLen = Integer.MAX_VALUE;
    for (int[] arr : runeArrays) {
      if (arr == null || arr.length == 0) {
        return 0;
      }
      minLen = Math.min(minLen, arr.length);
    }
    for (int offset = 1; offset <= minLen; offset++) {
      int expected = runeArrays[0][runeArrays[0].length - offset];
      for (int i = 1; i < runeArrays.length; i++) {
        if (runeArrays[i][runeArrays[i].length - offset] != expected) {
          return offset - 1;
        }
      }
    }
    return minLen;
  }

  private static void sliceLeadingRunesFromList(List<Regexp> list, int count) {
    int remaining = count;
    while (remaining > 0 && !list.isEmpty()) {
      Regexp first = list.get(0);
      int[] runes = extractAllLiteralRunes(first);
      if (runes == null) {
        break;
      }
      if (remaining >= runes.length) {
        remaining -= runes.length;
        list.remove(0);
      } else {
        list.set(
            0, literalFromRunes(Arrays.copyOfRange(runes, remaining, runes.length), first.flags));
        remaining = 0;
      }
    }
  }

  private static void sliceTrailingRunesFromList(List<Regexp> list, int count) {
    int remaining = count;
    while (remaining > 0 && !list.isEmpty()) {
      int lastIdx = list.size() - 1;
      Regexp last = list.get(lastIdx);
      int[] runes = extractAllLiteralRunes(last);
      if (runes == null) {
        break;
      }
      if (remaining >= runes.length) {
        remaining -= runes.length;
        list.remove(lastIdx);
      } else {
        list.set(
            lastIdx,
            literalFromRunes(Arrays.copyOfRange(runes, 0, runes.length - remaining), last.flags));
        remaining = 0;
      }
    }
  }

  private static Regexp literalFromRunes(int[] runes, int flags) {
    if (runes.length == 1) {
      return Regexp.literal(runes[0], flags);
    }
    return Regexp.literalString(runes, flags);
  }

  private static Regexp rebuildWithSubs(Regexp re, List<Regexp> newSubs) {
    return switch (re.op) {
      case CONCAT -> Regexp.concat(newSubs, re.flags);
      case ALTERNATE -> Regexp.alternate(newSubs, re.flags);
      case STAR -> Regexp.rawQuantifier(RegexpOp.STAR, newSubs.get(0), re.flags);
      case PLUS -> Regexp.rawQuantifier(RegexpOp.PLUS, newSubs.get(0), re.flags);
      case QUEST -> Regexp.rawQuantifier(RegexpOp.QUEST, newSubs.get(0), re.flags);
      case REPEAT -> Regexp.repeat(newSubs.get(0), re.flags, re.min, re.max);
      case CAPTURE -> Regexp.capture(newSubs.get(0), re.flags, re.cap, re.name);
      case NON_CAPTURE -> Regexp.nonCapture(newSubs.get(0), re.flags);
      default -> re;
    };
  }
}
