// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Shift-based Scalar DFA engine for small deterministic regular expressions (&le; 10 states).
 *
 * <p>Implements Per Vognsen's Shift DFA model with integrated {@link StateAccelerator} vector
 * acceleration for self-loop states. Instead of looking up transition destinations by indexing
 * array memory with {@code state}, transition tables are indexed by the incoming character {@code
 * c} to retrieve a 64-bit integer packing next-state destinations for all states:
 *
 * <pre>{@code
 * long row = table[c];
 * state = (int) ((row >>> state) & 0x3F);
 * }</pre>
 *
 * <p>This decouples memory load latency from the loop-carried state dependency, reducing the
 * critical-path state transition to a single 1-cycle ALU shift instruction ({@code shrx}) and
 * achieving ~1 byte / clock cycle (~4.5 GB/s) scalar throughput. When in a self-loop state, it
 * fast-forwards using vector SIMD intrinsics (~30 GB/s).
 */
final class ShiftDfa {

  static final int MAX_STATES = 10;
  static final int STATE_SHIFT_STEP = 6;
  static final int DEAD_STATE = MAX_STATES * STATE_SHIFT_STEP; // 60
  static final int STATE_MASK = 0x3F;

  private final long[] table;
  private final long acceptMask;
  private final int initialShiftState;
  private final int numStates;
  private final StateAccelerator[] accelerators;

  private ShiftDfa(
      long[] table,
      long acceptMask,
      int initialShiftState,
      int numStates,
      StateAccelerator[] accelerators) {
    this.table = table;
    this.acceptMask = acceptMask;
    this.initialShiftState = initialShiftState;
    this.numStates = numStates;
    this.accelerators = accelerators;
  }

  int numStates() {
    return numStates;
  }

  StateAccelerator[] accelerators() {
    return accelerators;
  }

  /**
   * Attempts to compile the compiled {@link Prog} into a 64-bit {@link ShiftDfa}.
   *
   * @param prog the compiled NFA program
   * @return a compiled {@link ShiftDfa} if the DFA determinizes to &le; 10 states, or {@code null}
   *     if it exceeds 10 states or contains unsupported constructs.
   */
  static ShiftDfa compile(Prog prog) {
    if (prog == null || prog.numCaptures() > 1) {
      return null; // Only group 0 supported
    }
    if (prog.hasGraphemeSemantics()
        || prog.hasWordBoundary()
        || prog.anchorEnd()
        || prog.dollarAnchorEnd()) {
      return null;
    }

    int startInst = prog.start();
    if (startInst == 0) {
      return null;
    }

    int[] initialInsts = expand(prog, new int[] {startInst});
    if (initialInsts == null) {
      return null;
    }
    List<int[]> dfaStates = new ArrayList<>(MAX_STATES + 1);
    dfaStates.add(initialInsts);

    int[][] transitions = new int[MAX_STATES][128];

    for (int s = 0; s < dfaStates.size(); s++) {
      int[] currentInsts = dfaStates.get(s);

      for (int c = 0; c < 128; c++) {
        int[] nextInsts = step(prog, currentInsts, c);
        if (nextInsts == null) {
          return null; // Unsupported instruction encountered
        }
        if (nextInsts.length == 0) {
          transitions[s][c] = MAX_STATES; // DEAD_STATE index 10
        } else {
          int existingIndex = findState(dfaStates, nextInsts);
          if (existingIndex >= 0) {
            transitions[s][c] = existingIndex;
          } else {
            if (dfaStates.size() >= MAX_STATES) {
              return null; // State budget exceeded (> 10 states)
            }
            int newIndex = dfaStates.size();
            dfaStates.add(nextInsts);
            transitions[s][c] = newIndex;
          }
        }
      }
    }

    int numDfaStates = dfaStates.size();
    long[] table = new long[256];

    for (int c = 0; c < 128; c++) {
      long row = 0L;
      for (int s = 0; s < numDfaStates; s++) {
        int targetState = transitions[s][c] * STATE_SHIFT_STEP;
        row |= ((long) targetState) << (s * STATE_SHIFT_STEP);
      }
      // Dead state slot (slot 10 at bit offset 60) self-loops to dead state (60)
      row |= ((long) DEAD_STATE) << DEAD_STATE;
      table[c] = row;
    }

    // For non-ASCII bytes (128..255), all states transition to DEAD_STATE
    long nonAsciiDeadRow = 0L;
    for (int s = 0; s <= MAX_STATES; s++) {
      nonAsciiDeadRow |= ((long) DEAD_STATE) << (s * STATE_SHIFT_STEP);
    }
    for (int c = 128; c < 256; c++) {
      table[c] = nonAsciiDeadRow;
    }

    long acceptMask = 0L;
    for (int s = 0; s < numDfaStates; s++) {
      if (hasMatch(prog, dfaStates.get(s))) {
        acceptMask |= (1L << (s * STATE_SHIFT_STEP));
      }
    }

    // Analyze StateAccelerator for self-loop states
    StateAccelerator[] accelerators = null;
    for (int s = 0; s < numDfaStates; s++) {
      int selfLoopCount = 0;
      int escapeCount = 0;
      int[] escapes = new int[4];

      for (int c = 0; c < 128; c++) {
        if (transitions[s][c] == s) {
          selfLoopCount++;
        } else {
          if (escapeCount < 4) {
            escapes[escapeCount] = c;
          }
          escapeCount++;
        }
      }

      // Only accelerate if this is genuinely a dominant self-loop state (>= 120 self-loops)
      StateAccelerator acc = null;
      if (selfLoopCount >= 120 && escapeCount >= 1 && escapeCount <= 3) {
        if (escapeCount == 1) {
          acc = new StateAccelerator.SingleAsciiEscape(escapes[0]);
        } else if (escapeCount == 2) {
          acc = new StateAccelerator.AsciiPairEscape(escapes[0], escapes[1]);
        } else {
          acc = new StateAccelerator.AsciiTripleEscape(escapes[0], escapes[1], escapes[2]);
        }
      }

      if (acc != null) {
        if (accelerators == null) {
          accelerators = new StateAccelerator[numDfaStates];
        }
        accelerators[s] = acc;
      }
    }

    return new ShiftDfa(table, acceptMask, 0, numDfaStates, accelerators);
  }

  private static int findState(List<int[]> states, int[] target) {
    for (int i = 0; i < states.size(); i++) {
      if (Arrays.equals(states.get(i), target)) {
        return i;
      }
    }
    return -1;
  }

  private static boolean hasMatch(Prog prog, int[] insts) {
    for (int id : insts) {
      if (prog.inst(id).opCode == InstOp.OP_MATCH) {
        return true;
      }
    }
    return false;
  }

  private static int[] expand(Prog prog, int[] seeds) {
    int[] stack = new int[prog.size() * 2 + 16];
    int stackTop = 0;
    for (int seed : seeds) {
      stack[stackTop++] = seed;
    }

    int[] frontier = new int[prog.size() + 1];
    int frontierSize = 0;
    boolean[] visited = new boolean[prog.size() + 1];

    while (stackTop > 0) {
      int id = stack[--stackTop];
      if (id == 0 || id >= prog.size() || visited[id]) {
        continue;
      }
      visited[id] = true;

      Inst ip = prog.inst(id);
      switch (ip.opCode) {
        case InstOp.OP_FAIL -> {}
        case InstOp.OP_ALT, InstOp.OP_ALT_MATCH -> {
          stack[stackTop++] = ip.out1;
          stack[stackTop++] = ip.out;
        }
        case InstOp.OP_NOP, InstOp.OP_CAPTURE -> {
          stack[stackTop++] = ip.out;
        }
        case InstOp.OP_PROGRESS_CHECK -> {
          stack[stackTop++] = ip.out1;
          stack[stackTop++] = ip.out;
        }
        case InstOp.OP_CHAR_RANGE -> {
          if (ip.hi >= 128) {
            return null; // Non-ASCII character range not supported in ShiftDfa
          }
          frontier[frontierSize++] = id;
        }
        case InstOp.OP_CHAR_CLASS -> {
          if (ip.ranges != null && ip.ranges.length > 0 && ip.ranges[ip.ranges.length - 1] >= 128) {
            return null; // Non-ASCII character class not supported in ShiftDfa
          }
          frontier[frontierSize++] = id;
        }
        case InstOp.OP_MATCH -> {
          frontier[frontierSize++] = id;
        }
        default -> {
          return null; // Unsupported instruction (e.g. EMPTY_WIDTH, GRAPHEME_CLUSTER)
        }
      }
    }

    int[] result = Arrays.copyOf(frontier, frontierSize);
    Arrays.sort(result);
    return result;
  }

  private static int[] step(Prog prog, int[] currentInsts, int c) {
    int[] seeds = new int[currentInsts.length];
    int seedCount = 0;

    for (int id : currentInsts) {
      Inst ip = prog.inst(id);
      if (ip.opCode == InstOp.OP_CHAR_RANGE) {
        if (ip.lo <= c && c <= ip.hi) {
          seeds[seedCount++] = ip.out;
        }
      } else if (ip.opCode == InstOp.OP_CHAR_CLASS) {
        if (c < 64) {
          if ((ip.bitmap0 & (1L << c)) != 0) {
            seeds[seedCount++] = ip.out;
          }
        } else if (c < 128) {
          if ((ip.bitmap1 & (1L << (c - 64))) != 0) {
            seeds[seedCount++] = ip.out;
          }
        }
      }
    }

    if (seedCount == 0) {
      return new int[0];
    }
    return expand(prog, Arrays.copyOf(seeds, seedCount));
  }

  /** Matches full input string from {@code start} to {@code end}. */
  boolean matches(String text, int start, int end) {
    int state = initialShiftState;
    final long[] tab = this.table;
    final StateAccelerator[] accs = this.accelerators;
    StringInputScanner scanner = accs != null ? new StringInputScanner(text) : null;
    int i = start;
    while (i < end) {
      if (accs != null && end - i >= 16) {
        StateAccelerator acc = accs[state / STATE_SHIFT_STEP];
        if (acc != null) {
          int nextPos = StateAccelerator.findNextAsciiOrNonAsciiEscape(acc, scanner, i, end);
          if (nextPos == -1) {
            break;
          }
          if (nextPos > i) {
            i = nextPos;
            if (i >= end) {
              break;
            }
          }
        }
      }
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      char c = text.charAt(i++);
      if (c >= 128) {
        return false;
      }
      state = (int) ((tab[c] >>> state) & STATE_MASK);
      if (state == DEAD_STATE) {
        return false;
      }
    }
    return (acceptMask & (1L << state)) != 0;
  }

  /** Matches full input UTF-8 byte scanner from {@code start} to {@code end}. */
  boolean matches(Utf8InputScanner scanner, int start, int end) {
    int state = initialShiftState;
    final long[] tab = this.table;
    final StateAccelerator[] accs = this.accelerators;
    byte[] bytes = scanner.bytes();
    int i = start;
    while (i < end) {
      if (accs != null && end - i >= 16) {
        StateAccelerator acc = accs[state / STATE_SHIFT_STEP];
        if (acc != null) {
          int nextPos = StateAccelerator.findNextAsciiOrNonAsciiEscape(acc, scanner, i, end);
          if (nextPos == -1) {
            break;
          }
          if (nextPos > i) {
            i = nextPos;
            if (i >= end) {
              break;
            }
          }
        }
      }
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      int b = bytes[scanner.offset() + i++] & 0xFF;
      state = (int) ((tab[b] >>> state) & STATE_MASK);
      if (state == DEAD_STATE) {
        return false;
      }
    }
    return (acceptMask & (1L << state)) != 0;
  }
}
