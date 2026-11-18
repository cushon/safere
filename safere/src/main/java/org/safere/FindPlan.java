// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/**
 * Find decisions made once per pattern and read by every find entry point: {@link Matcher}'s {@code
 * find()} and the matcher-free existence searches {@link Pattern#find(Utf8Input)} and {@link
 * Pattern#asPredicate()}, with or without diagnostics. Keeping them here stops the entry points
 * from each rebuilding the conditions, which is how they drifted apart before.
 *
 * @param stringReject whole-input reject prefilter for String searches, or {@code null}
 * @param utf8Reject UTF-8 counterpart of {@code stringReject}, which may omit disjoint literals
 *     that UTF-8 start acceleration already covers, or {@code null}
 * @param startPrecedesReject whether short inputs run the literal start search before the reject
 *     prefilter, then reject only the tail after the first candidate (see {@link #rejectMode})
 * @param startSubsumesReject whether a failed start search already proves rejection, so the reject
 *     prefilter would only repeat its scan
 * @param wideLeadingExpansion whether the start plan is a leading expansion over a wide class such
 *     as non-DOTALL {@code .}, which can walk back over a whole line: the DFA loop doesn't use it,
 *     and existence searches keep its minimum skip. Its accelerators also use {@link
 *     AcceleratorPolicy#WIDE_LEADING_EXPANSION}, which isn't charged to the start backoff
 * @param existenceStartsAtInner whether an existence-only search may start at the first inner
 *     candidate of a leading expansion instead of its expanded start (see {@link
 *     #existenceStartsAtInner(MultiAnchorDescriptor.StartPlan)})
 */
record FindPlan(
    RejectPrefilter stringReject,
    RejectPrefilter utf8Reject,
    boolean startPrecedesReject,
    boolean startSubsumesReject,
    boolean wideLeadingExpansion,
    boolean existenceStartsAtInner) {

  /** No reject prefilter applies. */
  static final int REJECT_NONE = 0;

  /** Run the reject prefilter over the whole input before start acceleration. */
  static final int REJECT_FIRST = 1;

  /** Run start acceleration first, then the reject prefilter over the tail after its candidate. */
  static final int REJECT_TAIL = 2;

  /**
   * Inputs shorter than this run a competitive literal start search before the reject prefilter,
   * where the separate prefilter call dominates. Longer inputs keep the single-pass reject first.
   */
  static final int MAX_LITERAL_START_FIRST_LENGTH = 512;

  /**
   * After a short input's start search, the reject prefilter runs on the tail only when at least
   * this many units remain. On a shorter tail the DFA's own check costs less than the call.
   */
  static final int MIN_REJECT_AFTER_START_REMAINING = 64;

  /** Returns the reject prefilter for {@code utf8} or String input, or {@code null}. */
  RejectPrefilter reject(boolean utf8) {
    return utf8 ? utf8Reject : stringReject;
  }

  /**
   * Returns how a search with {@code remaining} input units uses {@code reject}: {@link
   * #REJECT_NONE}, {@link #REJECT_FIRST} or {@link #REJECT_TAIL}. {@code startSearchRuns} says
   * whether the caller runs the literal start search at all; without it a short input rejects
   * first, as a long one does.
   */
  int rejectMode(RejectPrefilter reject, int remaining, boolean startSearchRuns) {
    if (reject == null) {
      return REJECT_NONE;
    }
    if (startPrecedesReject && startSearchRuns && remaining < MAX_LITERAL_START_FIRST_LENGTH) {
      return REJECT_TAIL;
    }
    return startSubsumesReject ? REJECT_NONE : REJECT_FIRST;
  }

  /** Returns whether a {@link #REJECT_TAIL} search rejects the tail after its start candidate. */
  static boolean rejectsTail(int rejectMode, int remainingAfterStart) {
    return rejectMode == REJECT_TAIL && remainingAfterStart >= MIN_REJECT_AFTER_START_REMAINING;
  }

  /**
   * Returns whether the forward DFA may call the start accelerator from inside its loop. A wide
   * leading expansion could repeat its walk back at each position before the same inner hit.
   */
  boolean dfaStartAcceleration() {
    return !wideLeadingExpansion;
  }

  /**
   * Returns whether an {@link #existenceStartsAtInner} search over {@code length} units starts at
   * the search start without a literal scan. A wide expansion declines short inputs, where the
   * scan's setup costs more than the forward search it could skip.
   */
  boolean skipsExistenceScan(int length) {
    return wideLeadingExpansion && length < MultiAnchorCompiler.MIN_WIDE_LEADING_SKIP;
  }

  /**
   * Returns the existence search start for the first inner {@code candidate}. A wide expansion
   * leaves nearby hits to the forward search, keeping its minimum profitable skip.
   */
  int existenceStart(int candidate) {
    return wideLeadingExpansion && candidate < MultiAnchorCompiler.MIN_WIDE_LEADING_SKIP
        ? 0
        : candidate;
  }

  static FindPlan create(
      MultiAnchorDescriptor multiAnchor,
      RejectPrefilter stringReject,
      RejectPrefilter utf8Reject,
      boolean startSearchAvailable) {
    MultiAnchorDescriptor.StartPlan startPlan = multiAnchor.startPlan();
    return new FindPlan(
        stringReject,
        utf8Reject,
        startSearchAvailable && stringReject != null && multiAnchor.startPrecedesFindReject(),
        startSearchAvailable && multiAnchor.startSubsumesFindReject(),
        isWideLeadingExpansion(startPlan),
        existenceStartsAtInner(startPlan));
  }

  static boolean isWideLeadingExpansion(MultiAnchorDescriptor.StartPlan startPlan) {
    return startPlan instanceof MultiAnchorDescriptor.StartPlan.LeadingExpansion le
        && MultiAnchorCompiler.isWideLeadingClass(le.leadingClass());
  }

  /**
   * Returns whether an existence-only search can start at the first inner candidate of a leading
   * expansion. With a zero minimum and no assertions before the leading class, any match [s, e) has
   * a suffix [q, e) starting at an inner candidate at or after the first one, and that suffix is
   * itself a match with zero leading repetitions. Searching from the full scanner keeps the
   * assertion context at q. A wide class also needs an exact literal inner plan, the only inner
   * plan it is measured with.
   */
  static boolean existenceStartsAtInner(MultiAnchorDescriptor.StartPlan startPlan) {
    if (!(startPlan instanceof MultiAnchorDescriptor.StartPlan.LeadingExpansion le)
        || le.minRepetition() != 0
        || le.hasLeadingAssertions()) {
      return false;
    }
    return !MultiAnchorCompiler.isWideLeadingClass(le.leadingClass())
        || (le.innerPlan() instanceof MultiAnchorDescriptor.StartPlan.Literal lit
            && !lit.foldCase());
  }
}
