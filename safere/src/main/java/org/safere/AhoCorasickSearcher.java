// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * A fast multi-string literal search engine using the Aho-Corasick algorithm compiled into a
 * flattened direct DFA transition table. Designed for allocation-free, single-indexed O(1) matching.
 */
final class AhoCorasickSearcher {

  private static final class BuilderNode {
    final Map<Character, BuilderNode> children = new HashMap<>();
    BuilderNode fail;
    int matchIndex = -1;
  }

  // Pre-computed DFA transition table: [numStates * 128]
  private final short[] transitions;
  private final int[] failureLinks;
  private final int[] matchIndices;
  private final int[] patternLengths;
  private final int maxPatternLength;
  private final boolean caseInsensitive;
  private final boolean isAsciiOnly;
  private final Map<Long, Short> nonAsciiTransitions;

  AhoCorasickSearcher(List<String> patterns, boolean caseInsensitive) {
    this.caseInsensitive = caseInsensitive;
    this.patternLengths = new int[patterns.size()];
    int maxLen = 0;
    boolean allAscii = true;
    for (int i = 0; i < patterns.size(); i++) {
      String p = patterns.get(i);
      int length = p.length();
      this.patternLengths[i] = length;
      maxLen = Math.max(maxLen, length);
      if (allAscii) {
        for (int j = 0; j < length; j++) {
          if (p.charAt(j) >= 128) {
            allAscii = false;
            break;
          }
        }
      }
    }
    this.maxPatternLength = maxLen;
    this.isAsciiOnly = allAscii;

    BuilderNode root = new BuilderNode();

    // 1. Insert patterns into Trie
    for (int i = 0; i < patterns.size(); i++) {
      String pattern = patterns.get(i);
      BuilderNode curr = root;
      for (int j = 0; j < pattern.length(); j++) {
        char c = pattern.charAt(j);
        if (caseInsensitive) {
          c = asciiLower(c);
        }
        curr = curr.children.computeIfAbsent(c, k -> new BuilderNode());
      }
      curr.matchIndex = i;
    }

    // 2. Compute failure links via BFS
    Queue<BuilderNode> queue = new ArrayDeque<>();
    root.fail = root;
    for (BuilderNode child : root.children.values()) {
      child.fail = root;
      queue.add(child);
    }

    while (!queue.isEmpty()) {
      BuilderNode curr = queue.poll();
      for (Map.Entry<Character, BuilderNode> entry : curr.children.entrySet()) {
        char c = entry.getKey();
        BuilderNode child = entry.getValue();
        BuilderNode f = curr.fail;
        while (f != root && !f.children.containsKey(c)) {
          f = f.fail;
        }
        child.fail = f.children.getOrDefault(c, root);
        if (child.matchIndex == -1) {
          child.matchIndex = child.fail.matchIndex;
        }
        queue.add(child);
      }
    }

    // 3. Assign sequential state IDs in BFS order (root is state 0)
    List<BuilderNode> nodeList = new ArrayList<>();
    Queue<BuilderNode> compileQueue = new ArrayDeque<>();
    nodeList.add(root);
    compileQueue.add(root);
    Map<BuilderNode, Integer> nodeIndices = new HashMap<>();
    nodeIndices.put(root, 0);

    while (!compileQueue.isEmpty()) {
      BuilderNode curr = compileQueue.poll();
      for (BuilderNode child : curr.children.values()) {
        nodeIndices.put(child, nodeList.size());
        nodeList.add(child);
        compileQueue.add(child);
      }
    }

    int numNodes = nodeList.size();
    this.transitions = new short[numNodes * 128];
    this.failureLinks = new int[numNodes];
    this.matchIndices = new int[numNodes];
    this.nonAsciiTransitions = allAscii ? null : new HashMap<>();

    // 4. Precompute direct DFA transitions in BFS order
    for (int state = 0; state < numNodes; state++) {
      BuilderNode node = nodeList.get(state);
      this.failureLinks[state] = nodeIndices.get(node.fail);
      this.matchIndices[state] = node.matchIndex;
      int baseOffset = state << 7;

      for (int c = 0; c < 128; c++) {
        BuilderNode child = node.children.get((char) c);
        if (child != null) {
          transitions[baseOffset | c] = (short) (int) nodeIndices.get(child);
        } else if (state == 0) {
          transitions[baseOffset | c] = 0;
        } else {
          int failState = nodeIndices.get(node.fail);
          // failState < state by BFS invariant, so failState transitions are already computed
          transitions[baseOffset | c] = transitions[(failState << 7) | c];
        }
      }

      if (!allAscii) {
        for (Map.Entry<Character, BuilderNode> entry : node.children.entrySet()) {
          char c = entry.getKey();
          if (c >= 128) {
            nonAsciiTransitions.put(
                ((long) state << 32) | c, (short) (int) nodeIndices.get(entry.getValue()));
          }
        }
      }
    }
  }

  /**
   * Scans the text starting from the given offset, and returns the earliest start index of any
   * matched literal pattern. Returns -1 if no matches are found.
   */
  public int findNext(CharSequence text, int start) {
    int state = 0;
    int len = text.length();
    int bestStart = -1;

    if (isAsciiOnly) {
      if (caseInsensitive) {
        for (int i = start; i < len; i++) {
          char c = asciiLower(text.charAt(i));
          state = (c < 128) ? transitions[(state << 7) | c] : 0;
          int patternIdx = matchIndices[state];
          if (patternIdx != -1) {
            int patternLen = patternLengths[patternIdx];
            int matchStart = i - patternLen + 1;
            if (bestStart < 0 || matchStart < bestStart) {
              bestStart = matchStart;
            }
          }
          if (canReturnBestStart(bestStart, i)) {
            return bestStart;
          }
        }
      } else {
        for (int i = start; i < len; i++) {
          char c = text.charAt(i);
          state = (c < 128) ? transitions[(state << 7) | c] : 0;
          int patternIdx = matchIndices[state];
          if (patternIdx != -1) {
            int patternLen = patternLengths[patternIdx];
            int matchStart = i - patternLen + 1;
            if (bestStart < 0 || matchStart < bestStart) {
              bestStart = matchStart;
            }
          }
          if (canReturnBestStart(bestStart, i)) {
            return bestStart;
          }
        }
      }
    } else {
      for (int i = start; i < len; i++) {
        char c = caseInsensitive ? asciiLower(text.charAt(i)) : text.charAt(i);
        if (c < 128) {
          state = transitions[(state << 7) | c];
        } else {
          Short next = nonAsciiTransitions.get(((long) state << 32) | c);
          if (next != null) {
            state = next;
          } else {
            int f = failureLinks[state];
            while (f != 0 && (next = nonAsciiTransitions.get(((long) f << 32) | c)) == null) {
              f = failureLinks[f];
            }
            state = (next != null) ? next : (f == 0 ? nonAsciiTransitions.getOrDefault((long) c, (short) 0) : 0);
          }
        }

        int patternIdx = matchIndices[state];
        if (patternIdx != -1) {
          int patternLen = patternLengths[patternIdx];
          int matchStart = i - patternLen + 1;
          if (bestStart < 0 || matchStart < bestStart) {
            bestStart = matchStart;
          }
        }
        if (canReturnBestStart(bestStart, i)) {
          return bestStart;
        }
      }
    }
    return bestStart;
  }

  private boolean canReturnBestStart(int bestStart, int currentIndex) {
    return bestStart >= 0 && currentIndex >= bestStart + maxPatternLength - 1;
  }

  private static char asciiLower(char c) {
    return ('A' <= c && c <= 'Z') ? (char) (c + ('a' - 'A')) : c;
  }
}
