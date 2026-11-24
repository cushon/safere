// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

/**
 * Forward leftmost-first search that also reports where the match starts, so that an unanchored
 * {@code find()} needs no reverse DFA pass.
 *
 * <p>The automaton mirrors the forward DFA ({@code Dfa.computeStartState}, {@code Dfa.computeNext},
 * {@code Dfa.expand}), but every thread also carries a label for the attempt (the start position)
 * it belongs to, and the labels are part of the state key. The youngest attempt started where the
 * current state was entered, so it needs no storage. The next two attempts, in priority order, keep
 * their start positions in two registers. A cached transition either leaves the registers alone,
 * and the scalar loop follows it, or carries an event that loads or permutes them, which the slow
 * branch applies. On the inputs this was measured on, the automaton has about one state more than
 * the forward DFA.
 *
 * <p>A search answers {@link #FALLBACK} whenever it can't vouch for the match start: the match
 * belongs to an attempt without a register, the match is empty, the start position is not a code
 * point boundary, or the state budget ran out. The caller then runs the ordinary forward and
 * reverse DFA search. Fallbacks waste a forward scan, so {@link #find} also tracks them and stops
 * using the automaton for a while when they are too frequent (see {@link #FALLBACK_COST}).
 *
 * <p>Instances are not thread-safe; each thread gets its own through {@code Pattern.ThreadCache}.
 */
final class StartRegisterSearch {
  /** No match exists at or after the start position. */
  static final long NO_MATCH = -1;

  /** The caller must use the ordinary DFA search; this search scanned some of the input. */
  static final long FALLBACK = -2;

  /** The caller must use the ordinary DFA search; this search did not run. */
  static final long SKIPPED = -3;

  /**
   * Results at or below this value hand the search over: no match starts before {@link
   * #handoffPosition}, and the caller continues with the ordinary DFA search from there.
   */
  static final long HANDOFF = -4;

  /**
   * Distance after which a search that has seen no match hands over at the next position where a
   * new search could begin. The start register only pays for itself on matches (it saves their
   * reverse scan); long stretches without one are better served by the forward DFA's start-state
   * acceleration.
   */
  static final int HANDOFF_DISTANCE = 1024;

  // Adaptive switch. Each search that finds a match, proves there is none or hands over earns
  // HIT_GAIN credit, up to MAX_CREDIT; each fallback costs FALLBACK_COST. A fallback can rescan up
  // to
  // the match end (about twice the forward work), while a hit saves a reverse scan over the match,
  // so the automaton has to win most of the time: at FALLBACK_COST = 4 credit only shrinks when
  // more
  // than one search in five falls back. When credit runs out, the next RETRY_INTERVAL searches skip
  // the automaton, after which it gets RETRY_CREDIT (two fallbacks) to show that the input has
  // changed. The state lives in this object, which Pattern.ThreadCache keeps per thread, so all
  // Matchers of a Pattern on one thread share it: a fallback-heavy input also pauses the search for
  // the next finds on other inputs.

  /** Credit at construction: allows eight fallbacks in a row before the first pause. */
  static final int INITIAL_CREDIT = 32;

  static final int MAX_CREDIT = 64;
  static final int HIT_GAIN = 1;
  static final int FALLBACK_COST = 4;
  static final int RETRY_INTERVAL = 1024;
  static final int RETRY_CREDIT = 2 * FALLBACK_COST;

  /**
   * Most cached states before the cache is cleared. Lower than Dfa's 10,000: rows are at least 129
   * ints and carry attempt labels, and the searches this targets need a handful of states.
   */
  static final int MAX_STATES = 4096;

  /** Most cached transitions (ints) before the cache is cleared: 16 MiB. */
  private static final int MAX_TRANSITIONS = 1 << 22;

  /** Rows allocated up front; the table doubles as states are added. */
  private static final int INITIAL_STATE_CAPACITY = 4;

  // Cache budget. A search that runs out of states falls back and clears the cache before the next
  // find. More than MAX_RESETS clears, net of one forgiven per RESET_DECAY answered searches, free
  // the cache and pause the search for RESET_PAUSE finds, so a pattern that keeps overflowing costs
  // at most a few cache rebuilds per RESET_PAUSE finds.

  static final int MAX_RESETS = 4;
  static final int RESET_DECAY = 1 << 16;
  static final int RESET_PAUSE = 1 << 16;

  /** Non-ASCII columns above which non-ASCII input makes the search fall back. */
  private static final int MAX_NON_ASCII_COLUMNS = 4096;

  private static final int LOOP = -5;
  private static final int NEW = Integer.MAX_VALUE;

  /** Label (and rank) of the attempt that started where the state was entered. */
  private static final int FRESH = -2;

  /** Register source: the previous state's fresh attempt, which started at that state's entry. */
  private static final int OLD_FRESH = -4;

  private static final int UNKNOWN = -3;

  private static final int WORD_BOUNDARY_FLAGS =
      EmptyOp.WORD_BOUNDARY
          | EmptyOp.NON_WORD_BOUNDARY
          | EmptyOp.UNICODE_WORD_BOUNDARY
          | EmptyOp.UNICODE_NON_WORD_BOUNDARY;
  private static final int NONE = -1;
  private static final int REGISTERS = 2;

  /** Transition code for a plain self-loop of a state with an escape accelerator. */
  private static final int ACCEL = Integer.MIN_VALUE;

  /**
   * Flags on a plain transition into a match state whose match belongs to the attempt in register 0
   * ({@code MATCH0}) or register 1 ({@code MATCH1}). The registers don't change, so the scalar loop
   * records the match itself.
   */
  private static final int MATCH0 = 1 << 30;

  private static final int MATCH1 = 1 << 29;

  /**
   * Flag on a plain transition into a start-like state from a state that is not: the point where
   * the search may hand over or skip ahead. The scalar loop only leaves for it when one is due.
   */
  private static final int START_ENTRY = 1 << 28;

  private final Prog prog;
  private final StringStartAccelerator stringAccelerator;
  private final Utf8StartAccelerator utf8Accelerator;
  private final boolean flat;
  private final int unanchoredStart;
  private final boolean hasWordBoundary;
  private static final int STATE_FLAGS_MASK =
      EmptyOp.ALL_FLAGS & ~EmptyOp.GRAPHEME_CLUSTER_BOUNDARY;
  private final int[] visited;
  private final int[] rankByLabel;
  private int[] stackPc = new int[16];
  private int[] stackG = new int[16];
  private int generation;

  /** Lower bounds of the non-ASCII equivalence classes, starting with 128; null if too many. */
  private final int[] nonAsciiBounds;

  /** Columns per state: ASCII, non-ASCII classes (doubled for Unicode word-ness), end of text. */
  private final int row;

  private final int eotColumn;

  /** State budget: {@link #MAX_STATES}, or fewer for wide rows. */
  private final int maxStates;

  private final ArrayList<St> states = new ArrayList<>();
  private final HashMap<St, St> interned = new HashMap<>();
  private final St[] startStates = new St[16];
  private int[] matchRankOf = new int[64];
  private boolean[] stopOf = new boolean[64];
  private StateAccelerator[] accelOf = new StateAccelerator[64];
  private boolean[] accelChecked = new boolean[64];
  private boolean[] accelSkipsNonAscii = new boolean[64];

  /** States without live attempts except the fresh one, where a new search could start. */
  private boolean[] startLikeOf = new boolean[64];

  /** Cached transitions: 0 unknown, positive plain next state id (with match flags), else event. */
  private int[] transitions;

  // Events: transitions that change the registers or report a match the scalar loop doesn't.
  private int[] infoNext = new int[64];
  private int[] infoSrc0 = new int[64];
  private int[] infoSrc1 = new int[64];
  private int[] infoDeferredRank = new int[64];
  private int infoCount;

  private boolean overflowed;
  private int resets;
  private int answeredSinceReset;
  private int credit = INITIAL_CREDIT;

  /** Remaining finds to skip before the search runs again. */
  private int pause;

  /** The caller's start-acceleration backoff (see {@link AdaptiveBackoff}), carried in and out. */
  private long startBackoff;

  private StartRegisterSearch(
      Prog prog, StringStartAccelerator stringAccelerator, Utf8StartAccelerator utf8Accelerator) {
    this.prog = prog;
    this.stringAccelerator = stringAccelerator;
    this.utf8Accelerator = utf8Accelerator;
    this.flat = prog.didFlatten();
    this.unanchoredStart = prog.startUnanchored();
    this.hasWordBoundary = prog.hasWordBoundary();
    this.visited = new int[prog.size()];
    this.rankByLabel = new int[prog.size() + 1];
    IntArrayList bounds = new IntArrayList();
    bounds.add(128);
    for (int b : Dfa.buildBoundaries(prog)) {
      if (b > 128 && b <= Utils.MAX_RUNE) {
        bounds.add(b);
      }
    }
    int columns = bounds.size() * (hasWordBoundary ? 2 : 1);
    if (columns <= MAX_NON_ASCII_COLUMNS) {
      this.nonAsciiBounds = bounds.toArray();
      this.eotColumn = 128 + columns;
    } else {
      this.nonAsciiBounds = null;
      this.eotColumn = 128;
    }
    this.row = eotColumn + 1;
    // State IDs begin at 1, so the backing table includes one unused row.
    this.maxStates = Math.min(MAX_STATES, MAX_TRANSITIONS / row - 1);
    // Start small, like Dfa: the searches this targets need a handful of states.
    this.transitions = new int[row * INITIAL_STATE_CAPACITY];
    states.add(null); // ids start at 1
  }

  /**
   * Returns a search for {@code prog}, or {@code null} when the automaton does not model its
   * semantics: anchors and other assertions except word boundaries, graphemes, and loop registers.
   * Like the forward DFA, the search uses the start accelerators to skip ahead from states where a
   * new search could begin.
   */
  static StartRegisterSearch create(
      Prog prog, StringStartAccelerator stringAccelerator, Utf8StartAccelerator utf8Accelerator) {
    if (prog.anchorStart()
        || prog.anchorEnd()
        || prog.hasTextAnchor()
        || prog.hasGraphemeSemantics()
        || prog.numLoopRegs() != 0
        || prog.startUnanchored() == 0) {
      return null;
    }
    // Fail closed: an instruction expand doesn't model must not silently drop threads.
    for (int i = 0; i < prog.size(); i++) {
      Inst ip = prog.inst(i);
      boolean modeled =
          switch (ip.opCode) {
            case InstOp.OP_ALT,
                InstOp.OP_ALT_MATCH,
                InstOp.OP_NOP,
                InstOp.OP_CAPTURE,
                InstOp.OP_CHAR_RANGE,
                InstOp.OP_CHAR_CLASS,
                InstOp.OP_MATCH,
                InstOp.OP_FAIL ->
                true;
            case InstOp.OP_EMPTY_WIDTH -> (ip.arg & ~WORD_BOUNDARY_FLAGS) == 0;
            default -> false;
          };
      if (!modeled) {
        return null;
      }
    }
    return new StartRegisterSearch(prog, stringAccelerator, utf8Accelerator);
  }

  /**
   * Finds the leftmost-first match at or after {@code startPos}. Returns {@link #NO_MATCH}, {@link
   * #FALLBACK}, {@link #SKIPPED}, or the match as {@code (start << 32) | end}. The search continues
   * from, and updates, the start-acceleration backoff {@code startBackoff}; read the new value with
   * {@link #startBackoff()}.
   */
  long find(InputScanner text, int startPos, long startBackoff) {
    this.startBackoff = startBackoff;
    if (pause > 0) {
      pause--;
      return SKIPPED;
    }
    if (overflowed) {
      clearCache();
      if (++resets > MAX_RESETS) {
        releaseCache();
        resets = 0;
        pause = RESET_PAUSE;
        return SKIPPED;
      }
    }
    long result = text.isCodePointBoundary(startPos) ? search(text, startPos) : FALLBACK;
    if (result == FALLBACK) {
      credit -= FALLBACK_COST;
      if (credit <= 0) {
        pause = RETRY_INTERVAL;
        credit = RETRY_CREDIT;
      }
    } else {
      if (credit < MAX_CREDIT) {
        credit += HIT_GAIN;
      }
      if (resets > 0 && ++answeredSinceReset >= RESET_DECAY) {
        resets--;
        answeredSinceReset = 0;
      }
    }
    return result;
  }

  /** Returns the position at which a {@link #HANDOFF} result continues. */
  static int handoffPosition(long result) {
    return (int) (HANDOFF - result);
  }

  /** Returns the start-acceleration backoff after the last {@link #find}. */
  long startBackoff() {
    return startBackoff;
  }

  /** Runs one search without the adaptive switch, for differential tests. */
  long searchForTesting(InputScanner text, int startPos) {
    startBackoff = AdaptiveBackoff.NEUTRAL;
    if (overflowed) {
      clearCache();
    }
    return text.isCodePointBoundary(startPos) ? search(text, startPos) : FALLBACK;
  }

  /** The remaining credit, for tests. */
  int creditForTesting() {
    return credit;
  }

  /** Number of states built since the last cache reset, for tests and measurements. */
  int stateCount() {
    return states.size() - 1;
  }

  /** Number of transition slots currently allocated, for tests. */
  int transitionCapacityForTesting() {
    return transitions.length;
  }

  /** The state budget, for tests. */
  int maxStatesForTesting() {
    return maxStates;
  }

  /** Number of cache resets, for tests. */
  int resetsForTesting() {
    return resets;
  }

  @MaxBytecodeSize(
      value = 856,
      reason =
          "The scalar loop of the start-register find. Keep event handling in the slow branch;"
              + " A/B ReverseDfaFindBenchmark.wordProse and RepeatedClassFind with -f 6 before"
              + " raising this.")
  private long search(InputScanner text, int startPos) {
    int len = text.length();
    St s = startState(text, startPos);
    if (s == null || s.matchRank != NONE) {
      // An empty match at the start position: leave it to the ordinary search.
      return FALLBACK;
    }
    // The start state holds only the fresh attempt, whose start is the current position.
    int r0 = -1;
    int r1 = -1;
    int matchStart = -2;
    int matchEnd = -1;
    int pos = startPos;
    int[] transitions = this.transitions;
    int row = this.row;
    int sid = s.id;
    // The first position at which the start accelerator may run, or MAX_VALUE when it never may.
    int resumePos =
        startPolicy(text) != null ? AdaptiveBackoff.resumePos(startBackoff) : Integer.MAX_VALUE;
    // Set on entering a start-like state, with no match seen, when a handoff or skip may be due.
    boolean startEntry = false;
    while (true) {
      if (startEntry) {
        // Every attempt but the fresh one died: no match starts before pos.
        startEntry = false;
        if (pos - startPos > HANDOFF_DISTANCE) {
          return HANDOFF - pos;
        }
        if (pos >= resumePos) {
          long restart = restart(text, pos, len, sid);
          resumePos = AdaptiveBackoff.resumePos(startBackoff);
          if (restart < 0) {
            return restart;
          }
          transitions = this.transitions;
          sid = (int) restart;
          pos = (int) (restart >>> 32);
        }
      }
      // Plain cached ASCII transitions keep both registers. On exit, ch and t describe pos.
      int ch = -1;
      int t = 0;
      while (pos < len) {
        ch = text.asciiAt(pos);
        if (ch < 0) {
          break;
        }
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.recordForwardDfaStep();
        }
        t = transitions[sid * row + ch];
        if (t <= 0) {
          break;
        }
        pos++;
        if (t >= START_ENTRY) {
          if (t >= MATCH1) {
            matchEnd = pos;
            if (t >= MATCH0) {
              matchStart = r0;
              t -= MATCH0;
            } else {
              matchStart = r1;
              t -= MATCH1;
            }
          } else {
            t -= START_ENTRY;
            // No start-like state follows a recorded match: the match cuts the unanchored loop.
            if (pos - startPos > HANDOFF_DISTANCE || pos >= resumePos) {
              sid = t;
              startEntry = true;
              break;
            }
          }
        }
        sid = t;
      }
      if (startEntry) {
        continue;
      }
      int cp;
      int nextPos;
      int column;
      if (pos >= len) {
        cp = -1;
        nextPos = pos;
        column = eotColumn;
        t = transitions[sid * row + column];
      } else if (ch >= 0) {
        cp = ch;
        nextPos = pos + 1;
        column = ch;
      } else {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.recordForwardDfaStep();
        }
        long decoded = text.decodeForward(pos);
        cp = InputScanner.codePoint(decoded);
        nextPos = InputScanner.position(decoded);
        column = nonAsciiColumn(cp);
        if (column < 0) {
          return FALLBACK;
        }
        t = transitions[sid * row + column];
      }
      if (t == ACCEL) {
        // A plain self-loop: skip to the next escape (or non-ASCII input), keeping the registers.
        int escape =
            accelSkipsNonAscii[sid]
                ? StateAccelerator.findNextEscape(accelOf[sid], text, nextPos, len)
                : StateAccelerator.findNextAsciiOrNonAsciiEscape(accelOf[sid], text, nextPos, len);
        pos = escape < 0 ? len : escape;
        continue;
      }
      if (t == 0) {
        t = computeTransition(states.get(sid), cp, column);
        if (t == 0) {
          return FALLBACK;
        }
        transitions = this.transitions;
        if (t == ACCEL) {
          continue;
        }
      }
      if (t > 0) {
        if (t >= MATCH1) {
          matchEnd = nextPos;
          if (t >= MATCH0) {
            matchStart = r0;
            t -= MATCH0;
          } else {
            matchStart = r1;
            t -= MATCH1;
          }
        } else if (t >= START_ENTRY) {
          t -= START_ENTRY;
          startEntry = true;
        }
        sid = t;
        pos = nextPos;
        continue;
      }
      int info = -t - 1;
      int deferred = infoDeferredRank[info];
      if (deferred != NONE) {
        matchStart = start(deferred, r0, r1, pos);
        matchEnd = pos;
      }
      int n0 = register(infoSrc0[info], r0, r1, pos);
      int n1 = register(infoSrc1[info], r0, r1, pos);
      r0 = n0;
      r1 = n1;
      int next = infoNext[info];
      int rank = matchRankOf[next];
      if (rank != NONE) {
        matchStart = start(rank, r0, r1, nextPos);
        matchEnd = nextPos;
      }
      if (stopOf[next] || cp < 0) {
        return finish(matchStart, matchEnd);
      }
      sid = next;
      pos = nextPos;
      startEntry = startLikeOf[next];
    }
  }

  private AcceleratorPolicy startPolicy(InputScanner text) {
    return switch (text) {
      case Utf8InputScanner unusedUtf8 -> utf8Accelerator != null ? utf8Accelerator.policy() : null;
      case StringInputScanner unusedString ->
          stringAccelerator != null ? stringAccelerator.policy() : null;
      case StringRegionInputScanner unusedRegion ->
          stringAccelerator != null ? stringAccelerator.policy() : null;
    };
  }

  /**
   * Skips from start-like state {@code sid}, entered at {@code pos}, to the next start candidate.
   * Returns the position and state to continue from as {@code (pos << 32) | stateId}, or {@link
   * #NO_MATCH} or {@link #FALLBACK} to end the search.
   */
  private long restart(InputScanner text, int pos, int len, int sid) {
    int candidate = nextCandidate(text, pos, len);
    if (candidate < 0) {
      return NO_MATCH;
    }
    if (candidate == pos) {
      return ((long) pos << 32) | sid;
    }
    // The start state's word context comes from the code points around the candidate, so it must
    // not split one. Accelerator candidates are boundaries; this keeps the search safe if not.
    if (!text.isCodePointBoundary(candidate)) {
      return FALLBACK;
    }
    St s = startState(text, candidate);
    if (s == null || s.matchRank != NONE) {
      return FALLBACK;
    }
    return ((long) candidate << 32) | s.id;
  }

  /**
   * Returns the next start candidate at or after {@code pos} from the start accelerator, or -1 when
   * there is none, and charges the call to {@link #startBackoff} as {@code Dfa} does.
   */
  private int nextCandidate(InputScanner text, int pos, int len) {
    AcceleratorPolicy policy = startPolicy(text);
    if (len - pos < policy.minProfitableSkip()) {
      return pos;
    }
    int candidate =
        switch (text) {
          case Utf8InputScanner utf8 ->
              Utf8StartAccelerator.findNextCandidate(utf8Accelerator, utf8, pos);
          case StringInputScanner string ->
              StringStartAccelerator.findNextCandidate(
                  stringAccelerator, string.text(), pos, prog.lineStartUnixLines());
          case StringRegionInputScanner region ->
              StringStartAccelerator.findNextCandidate(
                  stringAccelerator, region, pos, prog.lineStartUnixLines());
        };
    if (WorkCounterConfig.ENABLED) {
      WorkCounter.recordStartScan(candidate < 0 ? len - pos : candidate - pos);
    }
    if (candidate >= 0) {
      startBackoff = AdaptiveBackoff.recordSkip(startBackoff, pos, candidate, len, policy);
    }
    return candidate;
  }

  /** Start of the attempt with rank {@code rank} in a state entered at {@code entry}. */
  private static int start(int rank, int r0, int r1, int entry) {
    return switch (rank) {
      case 0 -> r0;
      case 1 -> r1;
      case FRESH -> entry;
      default -> -1;
    };
  }

  /** New register value from {@code source}, for a transition out of a state entered at pos. */
  private static int register(int source, int r0, int r1, int pos) {
    return switch (source) {
      case 0 -> r0;
      case 1 -> r1;
      case OLD_FRESH -> pos;
      default -> -1;
    };
  }

  private static long finish(int matchStart, int matchEnd) {
    if (matchEnd < 0) {
      return NO_MATCH;
    }
    if (matchStart < 0 || matchStart >= matchEnd) {
      return FALLBACK;
    }
    return ((long) matchStart << 32) | matchEnd;
  }

  /**
   * Returns the transition column of a non-ASCII code point, or -1 when non-ASCII columns are not
   * cached for this program.
   */
  private int nonAsciiColumn(int cp) {
    int[] bounds = nonAsciiBounds;
    if (bounds == null) {
      return -1;
    }
    int index = Arrays.binarySearch(bounds, cp);
    if (index < 0) {
      index = -index - 2;
    }
    if (hasWordBoundary) {
      index = 2 * index + (Nfa.isUnicodeWordChar(cp) ? 1 : 0);
    }
    return 128 + index;
  }

  /** Clears every cached state and transition after the state budget ran out. */
  private void clearCache() {
    overflowed = false;
    states.clear();
    states.add(null);
    interned.clear();
    Arrays.fill(startStates, null);
    Arrays.fill(transitions, 0);
    Arrays.fill(accelOf, null);
    Arrays.fill(accelChecked, false);
    Arrays.fill(accelSkipsNonAscii, false);
    infoCount = 0;
  }

  /** Clears the cache and shrinks its arrays back to their initial size. */
  private void releaseCache() {
    clearCache();
    transitions = new int[row * INITIAL_STATE_CAPACITY];
    matchRankOf = new int[64];
    stopOf = new boolean[64];
    accelOf = new StateAccelerator[64];
    accelChecked = new boolean[64];
    accelSkipsNonAscii = new boolean[64];
    startLikeOf = new boolean[64];
    infoNext = new int[64];
    infoSrc0 = new int[64];
    infoSrc1 = new int[64];
    infoDeferredRank = new int[64];
  }

  // ------------------------------------------------------------------------------------------
  // State construction (mirrors Dfa.computeStartState / computeNext / expand, with attempt labels)

  private static final class St {
    final int[] pcs;
    final int[] groups;
    final int storedFlags;
    final boolean lastWord;
    final boolean lastUnicodeWord;
    int id;
    int matchRank = NONE;
    boolean highestPriorityMatch;
    private final int hash;

    St(int[] pcs, int[] groups, int storedFlags, boolean lastWord, boolean lastUnicodeWord) {
      this.pcs = pcs;
      this.groups = groups;
      this.storedFlags = storedFlags;
      this.lastWord = lastWord;
      this.lastUnicodeWord = lastUnicodeWord;
      this.hash =
          (Arrays.hashCode(pcs) * 31 + Arrays.hashCode(groups)) * 31
              + storedFlags * 4
              + (lastWord ? 2 : 0)
              + (lastUnicodeWord ? 1 : 0);
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof St other
          && hash == other.hash
          && storedFlags == other.storedFlags
          && lastWord == other.lastWord
          && lastUnicodeWord == other.lastUnicodeWord
          && Arrays.equals(pcs, other.pcs)
          && Arrays.equals(groups, other.groups);
    }

    @Override
    public int hashCode() {
      return hash;
    }
  }

  private St startState(InputScanner text, int pos) {
    int index = 0;
    if (hasWordBoundary) {
      if (pos > 0) {
        int cp = text.codePointBefore(pos);
        boolean word = Nfa.isWordChar(cp);
        index |= (word ? 1 : 0) | ((cp < 128 ? word : Nfa.isUnicodeWordChar(cp)) ? 2 : 0);
      }
      if (pos < text.length()) {
        int cp = text.codePointAt(pos);
        boolean word = Nfa.isWordChar(cp);
        index |= (word ? 4 : 0) | ((cp < 128 ? word : Nfa.isUnicodeWordChar(cp)) ? 8 : 0);
      }
    }
    St s = startStates[index];
    if (s != null) {
      return s;
    }
    int flags = emptyFlags(text, pos);
    IntArrayList pc = new IntArrayList();
    IntArrayList g = new IntArrayList();
    expand(new int[] {unanchoredStart}, new int[] {NEW}, 1, flags, pc, g);
    s =
        intern(
            normalize(pc, g, null), flags & STATE_FLAGS_MASK, (index & 1) != 0, (index & 2) != 0);
    startStates[index] = s;
    return s;
  }

  private int emptyFlags(InputScanner text, int pos) {
    if (!hasWordBoundary) {
      return 0;
    }
    return Nfa.emptyFlags(text, pos, false, null, true, false);
  }

  /** Normalized thread lists: {pcs, ranks}; src receives the source label of each new rank. */
  private int[][] normalize(IntArrayList pc, IntArrayList g, int[] src) {
    int n = pc.size();
    int[] pcs = new int[n];
    int[] ranks = new int[n];
    int[] labels = new int[n];
    int distinct = 0;
    for (int i = 0; i < n; i++) {
      pcs[i] = pc.get(i);
      int label = g.get(i);
      if (label == LOOP) {
        ranks[i] = LOOP;
        continue;
      }
      if (label == NEW) {
        ranks[i] = FRESH;
        continue;
      }
      // Old ranks are dense. Slot zero is reserved for the previous state's fresh attempt.
      int index = label == FRESH ? 0 : label + 1;
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.recordStartRegisterRankLookups(1);
      }
      int rank = rankByLabel[index];
      if (rank == 0) {
        labels[distinct] = label;
        rank = ++distinct;
        rankByLabel[index] = rank;
      }
      ranks[i] = rank - 1;
    }
    // Clear only the slots assigned by this frontier.
    for (int k = 0; k < distinct; k++) {
      int label = labels[k];
      rankByLabel[label == FRESH ? 0 : label + 1] = 0;
    }
    if (src != null) {
      for (int k = 0; k < REGISTERS; k++) {
        if (k >= distinct) {
          src[k] = NONE;
        } else if (labels[k] == FRESH) {
          src[k] = OLD_FRESH;
        } else {
          // Sources beyond the registers are unknown.
          src[k] = labels[k] < REGISTERS ? labels[k] : UNKNOWN;
        }
      }
    }
    return new int[][] {pcs, ranks};
  }

  /** Returns the interned state, or null (and marks the cache for reset) when over budget. */
  private St intern(int[][] threads, int storedFlags, boolean lastWord, boolean lastUnicodeWord) {
    St s = new St(threads[0], threads[1], storedFlags, lastWord, lastUnicodeWord);
    St existing = interned.get(s);
    if (existing != null) {
      return existing;
    }
    if (states.size() > maxStates) {
      overflowed = true;
      return null;
    }
    s.id = states.size();
    states.add(s);
    interned.put(s, s);
    for (int i = 0; i < s.pcs.length; i++) {
      if (prog.inst(s.pcs[i]).opCode == InstOp.OP_MATCH) {
        s.matchRank = s.groups[i] < REGISTERS ? s.groups[i] : UNKNOWN;
        s.highestPriorityMatch = i == 0;
        break;
      }
    }
    if (matchRankOf.length <= s.id) {
      int size = matchRankOf.length * 2;
      matchRankOf = Arrays.copyOf(matchRankOf, size);
      stopOf = Arrays.copyOf(stopOf, size);
      accelOf = Arrays.copyOf(accelOf, size);
      accelChecked = Arrays.copyOf(accelChecked, size);
      accelSkipsNonAscii = Arrays.copyOf(accelSkipsNonAscii, size);
      startLikeOf = Arrays.copyOf(startLikeOf, size);
    }
    boolean startLike = s.matchRank == NONE && s.pcs.length != 0;
    for (int g : s.groups) {
      startLike &= g == LOOP || g == FRESH;
    }
    startLikeOf[s.id] = startLike;
    matchRankOf[s.id] = s.matchRank;
    stopOf[s.id] = s.highestPriorityMatch || s.pcs.length == 0;
    int requiredTransitions = (s.id + 1) * row;
    if (transitions.length < requiredTransitions) {
      int capacity =
          Math.min((maxStates + 1) * row, Math.max(transitions.length * 2, requiredTransitions));
      transitions = Arrays.copyOf(transitions, capacity);
    }
    return s;
  }

  /** Computes and caches a transition; returns 0 when the state budget ran out. */
  private int computeTransition(St s, int cp, int column) {
    if (!accelChecked[s.id]) {
      accelChecked[s.id] = true;
      if (analyzeAcceleration(s) && transitions[s.id * row + column] != 0) {
        return transitions[s.id * row + column];
      }
    }
    return computeTransitionUncached(s, cp, column);
  }

  /**
   * Gives {@code s} an escape accelerator when every ASCII character but at most three is a plain
   * self-loop, and marks those self-loops. Like {@code Dfa.analyzeStateAcceleration}, it compares
   * successors without building states or caching other transitions.
   */
  private boolean analyzeAcceleration(St s) {
    // Start-like states skip ahead only through the start accelerator and its backoff, as in Dfa:
    // an escape scan from every failed candidate would cost a call per candidate.
    if (s.matchRank != NONE || s.pcs.length == 0 || startLikeOf[s.id]) {
      return false;
    }
    for (int pc : s.pcs) {
      int op = prog.inst(pc).opCode;
      if (op == InstOp.OP_EMPTY_WIDTH || op == InstOp.OP_MATCH) {
        return false;
      }
    }
    int[] escapes = new int[3];
    int escapeCount = 0;
    for (int ch = 0; ch < 128; ch++) {
      if (!isPlainSelfLoop(s, ch)) {
        if (escapeCount == 3) {
          return false;
        }
        escapes[escapeCount++] = ch;
      }
    }
    StateAccelerator accelerator =
        switch (escapeCount) {
          case 1 -> new StateAccelerator.SingleAsciiEscape(escapes[0]);
          case 2 -> new StateAccelerator.AsciiPairEscape(escapes[0], escapes[1]);
          case 3 -> new StateAccelerator.AsciiTripleEscape(escapes[0], escapes[1], escapes[2]);
          default -> null;
        };
    if (accelerator == null) {
      return false;
    }
    accelOf[s.id] = accelerator;
    accelSkipsNonAscii[s.id] = nonAsciiSelfLoops(s);
    int base = s.id * row;
    for (int ch = 0, e = 0; ch < 128; ch++) {
      if (e < escapeCount && escapes[e] == ch) {
        e++;
      } else {
        transitions[base + ch] = ACCEL;
      }
    }
    return true;
  }

  /** Returns whether {@code cp} leads from {@code s} back to {@code s} without an event. */
  private boolean isPlainSelfLoop(St s, int cp) {
    int[] src = new int[REGISTERS];
    int[] deferred = {NONE};
    St successor = next(s, cp, src, deferred, /* probe= */ true);
    return deferred[0] == NONE
        && (src[0] == NONE || src[0] == 0)
        && (src[1] == NONE || src[1] == 1)
        && successor.equals(s);
  }

  /**
   * Returns whether every non-ASCII code point is a plain self-loop of {@code s}. The code points
   * of one non-ASCII column match the same instructions, so one representative per column suffices.
   * With word boundaries, word-ness varies within a class, so the accelerator stops at non-ASCII
   * input instead.
   */
  private boolean nonAsciiSelfLoops(St s) {
    if (hasWordBoundary || nonAsciiBounds == null) {
      return false;
    }
    for (int bound : nonAsciiBounds) {
      if (!isPlainSelfLoop(s, bound)) {
        return false;
      }
    }
    return true;
  }

  private int computeTransitionUncached(St s, int cp, int column) {
    int[] src = new int[REGISTERS];
    int[] deferred = {NONE};
    St ns = next(s, cp, src, deferred, /* probe= */ false);
    if (ns == null) {
      return 0;
    }
    boolean plain =
        deferred[0] == NONE
            && (ns.matchRank == NONE
                || ((ns.matchRank == 0 || ns.matchRank == 1) && !ns.highestPriorityMatch))
            && ns.pcs.length != 0
            && cp >= 0
            && (src[0] == NONE || src[0] == 0)
            && (src[1] == NONE || src[1] == 1);
    int t;
    if (plain) {
      t =
          switch (ns.matchRank) {
            case 0 -> ns.id | MATCH0;
            case 1 -> ns.id | MATCH1;
            // Entering a start-like state from elsewhere is where the search may skip or hand over.
            default -> startLikeOf[ns.id] && !startLikeOf[s.id] ? ns.id | START_ENTRY : ns.id;
          };
    } else {
      if (infoCount == infoNext.length) {
        int size = infoCount * 2;
        infoNext = Arrays.copyOf(infoNext, size);
        infoSrc0 = Arrays.copyOf(infoSrc0, size);
        infoSrc1 = Arrays.copyOf(infoSrc1, size);
        infoDeferredRank = Arrays.copyOf(infoDeferredRank, size);
      }
      infoNext[infoCount] = ns.id;
      infoSrc0[infoCount] = src[0];
      infoSrc1[infoCount] = src[1];
      infoDeferredRank[infoCount] = deferred[0];
      infoCount++;
      t = -infoCount;
    }
    transitions[s.id * row + column] = t;
    return t;
  }

  /**
   * Returns the successor of {@code s} on {@code cp} (end of text when negative), filling in the
   * register sources and the deferred match. With {@code probe}, returns an uninterned state for
   * comparison and reports any deferred match as rank 0.
   */
  private St next(St s, int cp, int[] src, int[] deferredRank, boolean probe) {
    if (cp < 0) {
      int flags = 0;
      if (hasWordBoundary) {
        flags |= s.lastWord ? EmptyOp.WORD_BOUNDARY : EmptyOp.NON_WORD_BOUNDARY;
        flags |=
            s.lastUnicodeWord ? EmptyOp.UNICODE_WORD_BOUNDARY : EmptyOp.UNICODE_NON_WORD_BOUNDARY;
      }
      IntArrayList sp = new IntArrayList();
      IntArrayList sg = new IntArrayList();
      for (int i = 0; i < s.pcs.length; i++) {
        Inst ip = prog.inst(s.pcs[i]);
        if (ip.opCode == InstOp.OP_EMPTY_WIDTH && (ip.arg & ~flags) == 0) {
          sp.add(ip.out);
          sg.add(s.groups[i]);
        }
      }
      IntArrayList pc = new IntArrayList();
      IntArrayList g = new IntArrayList();
      if (sp.size() > 0) {
        expand(sp.toArray(), sg.toArray(), sp.size(), flags, pc, g);
      }
      return intern(normalize(pc, g, src), flags & STATE_FLAGS_MASK, false, false);
    }
    boolean isWord = false;
    boolean isUnicodeWord = false;
    int wb = 0;
    int uwb = 0;
    if (hasWordBoundary) {
      isWord = Nfa.isWordChar(cp);
      wb = isWord != s.lastWord ? EmptyOp.WORD_BOUNDARY : EmptyOp.NON_WORD_BOUNDARY;
      isUnicodeWord = cp < 128 ? isWord : Nfa.isUnicodeWordChar(cp);
      uwb =
          isUnicodeWord != s.lastUnicodeWord
              ? EmptyOp.UNICODE_WORD_BOUNDARY
              : EmptyOp.UNICODE_NON_WORD_BOUNDARY;
    }
    int re = s.storedFlags | wb | uwb;
    IntArrayList tp = new IntArrayList();
    IntArrayList tg = new IntArrayList();
    boolean deferredMatch = false;
    int deferredGroup = NONE;
    IntArrayList ep = null;
    IntArrayList eg = null;
    for (int i = 0; i < s.pcs.length; i++) {
      int id = s.pcs[i];
      Inst ip = prog.inst(id);
      if (ip.opCode == InstOp.OP_MATCH) {
        continue;
      }
      if (ip.opCode == InstOp.OP_EMPTY_WIDTH && (ip.arg & ~re) == 0) {
        if (ep == null) {
          ep = new IntArrayList();
          eg = new IntArrayList();
        } else {
          ep.clear();
          eg.clear();
        }
        stackPc[0] = ip.out;
        stackG[0] = s.groups[i];
        expand(1, re, ep, eg);
        int firstMatch = -1;
        for (int k = 0; k < ep.size(); k++) {
          if (prog.inst(ep.get(k)).opCode == InstOp.OP_MATCH) {
            firstMatch = k;
            break;
          }
        }
        if (firstMatch >= 0) {
          deferredMatch = true;
          deferredGroup = eg.get(firstMatch);
          for (int k = 0; k < ep.size(); k++) {
            if (prog.inst(ep.get(k)).opCode != InstOp.OP_MATCH) {
              tp.add(ep.get(k));
              tg.add(eg.get(k));
            }
          }
          // Without end anchors every match is valid: lower-priority threads are cut.
          break;
        } else {
          for (int k = 0; k < ep.size(); k++) {
            tp.add(ep.get(k));
            tg.add(eg.get(k));
          }
        }
      } else {
        tp.add(id);
        tg.add(s.groups[i]);
      }
    }
    int g1 = ++generation;
    IntArrayList dp = new IntArrayList();
    IntArrayList dg = new IntArrayList();
    for (int i = 0; i < tp.size(); i++) {
      if (visited[tp.get(i)] != g1) {
        visited[tp.get(i)] = g1;
        dp.add(tp.get(i));
        dg.add(tg.get(i));
      }
    }
    IntArrayList succ = new IntArrayList();
    IntArrayList succG = new IntArrayList();
    boolean loop = false;
    for (int i = 0; i < dp.size(); i++) {
      Inst ip = prog.inst(dp.get(i));
      boolean matches =
          (ip.opCode == InstOp.OP_CHAR_RANGE && ip.matchesChar(cp))
              || (ip.opCode == InstOp.OP_CHAR_CLASS && ip.matchesCharClass(cp));
      if (matches) {
        if (ip.out == unanchoredStart) {
          loop = true;
        } else {
          succ.add(ip.out);
          succG.add(dg.get(i));
        }
      }
    }
    if (loop && !deferredMatch) {
      succ.add(unanchoredStart);
      succG.add(NEW);
    }
    IntArrayList pc = new IntArrayList();
    IntArrayList g = new IntArrayList();
    if (succ.size() > 0) {
      // Non-boundary flags at the next position are position-independent here (no text anchors).
      expand(succ.toArray(), succG.toArray(), succ.size(), 0, pc, g);
    }
    int[][] threads = normalize(pc, g, src);
    if (probe) {
      deferredRank[0] = deferredMatch ? 0 : NONE;
      return new St(threads[0], threads[1], 0, isWord, isUnicodeWord);
    }
    St ns = intern(threads, 0, isWord, isUnicodeWord);
    if (ns == null) {
      return null;
    }
    // The threads of ns all come from threads with higher priority than the deferred match, so a
    // match in ns replaces it; only report the deferred match when ns has none.
    if (deferredMatch && ns.matchRank == NONE) {
      deferredRank[0] = deferredGroup < REGISTERS ? deferredGroup : UNKNOWN;
    }
    return ns;
  }

  private void expand(
      int[] seedPc, int[] seedG, int n, int flags, IntArrayList outPc, IntArrayList outG) {
    if (stackPc.length < n) {
      growExpansionStacks(n);
    }
    for (int i = 0; i < n; i++) {
      stackPc[i] = seedPc[n - i - 1];
      stackG[i] = seedG[n - i - 1];
    }
    expand(n, flags, outPc, outG);
  }

  private void growExpansionStacks(int minimum) {
    int capacity = Math.max(minimum, 2 * stackPc.length);
    stackPc = Arrays.copyOf(stackPc, capacity);
    stackG = Arrays.copyOf(stackG, capacity);
    if (WorkCounterConfig.ENABLED) {
      WorkCounter.recordStartRegisterStackSlots(2L * capacity);
    }
  }

  private void expand(int top, int flags, IntArrayList outPc, IntArrayList outG) {
    int g0 = ++generation;
    while (top > 0) {
      top--;
      int id = stackPc[top];
      int g = stackG[top];
      if (id == 0 || id >= prog.size() || visited[id] == g0) {
        continue;
      }
      visited[id] = g0;
      Inst ip = prog.inst(id);
      if (top + 3 >= stackPc.length) {
        growExpansionStacks(top + 3);
      }
      if (flat && !ip.last) {
        stackPc[top] = id + 1;
        stackG[top++] = g;
      }
      switch (ip.opCode) {
        case InstOp.OP_ALT, InstOp.OP_ALT_MATCH -> {
          if (!flat) {
            stackPc[top] = ip.out1;
            stackG[top++] = g;
            stackPc[top] = ip.out;
            stackG[top++] = g;
          }
        }
        case InstOp.OP_NOP, InstOp.OP_CAPTURE -> {
          stackPc[top] = ip.out;
          stackG[top++] = g;
        }
        case InstOp.OP_EMPTY_WIDTH -> {
          if ((ip.arg & ~flags) == 0) {
            stackPc[top] = ip.out;
            stackG[top++] = g;
          } else {
            outPc.add(id);
            outG.add(g);
          }
        }
        case InstOp.OP_CHAR_RANGE, InstOp.OP_CHAR_CLASS -> {
          outPc.add(id);
          outG.add(ip.out == unanchoredStart ? LOOP : g);
        }
        case InstOp.OP_MATCH -> {
          outPc.add(id);
          outG.add(g);
          top = 0;
        }
        case InstOp.OP_FAIL -> {}
        default -> throw new IllegalStateException("opcode not modeled: " + ip.opCode);
      }
    }
  }
}
