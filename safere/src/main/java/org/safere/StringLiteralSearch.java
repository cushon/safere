// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/**
 * Exact-case literal search in a {@link String}, anchored on the literal's rarest byte.
 *
 * <p>{@link String#indexOf(String, int)} is intrinsified, but its multi-character form is several
 * times slower than its single-character form: on a 100 KB haystack a 3-character needle scans at
 * roughly 8 GB/s while {@code indexOf(char)} reaches roughly 50 GB/s. Searching for one rare
 * character of the literal and verifying the rest keeps the scan on the faster kernel and only pays
 * for verification where that character actually occurs.
 *
 * <p>This mirrors what the case-insensitive path already does. {@link
 * StringStartAccelerator.CaseInsensitiveLiteral} picks an anchor with {@link
 * RarityOracle#rarestAsciiOffset} and scans with {@link Matcher#indexOfIgnoreCase}; before this
 * class existed, the exact-case path — much the more common one — issued a bare {@code
 * text.indexOf(literal)}.
 *
 * <p>Verification work is bounded by {@link WorkLimit}. A literal whose rarest character turns out
 * to be common in this particular haystack falls back to {@link String#indexOf(String, int)} for
 * the remainder of the search, so a corpus-derived frequency model that is wrong about one input
 * costs a bounded amount rather than an unbounded one.
 */
final class StringLiteralSearch {

  /** Sentinel {@code anchorOffset} meaning "search with {@link String#indexOf(String, int)}". */
  static final int NO_ANCHOR = -1;

  /**
   * Chooses the offset within {@code literal} to anchor the scan on, or {@link #NO_ANCHOR} if this
   * literal is better served by {@link String#indexOf(String, int)} directly.
   *
   * <p>Declines in three cases:
   *
   * <ul>
   *   <li>Literals shorter than two characters, where the anchor would be the literal itself, so
   *       verification could never reject a candidate. {@link #indexOfDirect} puts these on the
   *       single-character kernel instead, which is where the speed the anchor was after lives.
   *   <li>Literals containing no ASCII character, where the rarity model has nothing to say.
   *   <li>Literals whose rarest character is still common enough to be a poisonous anchor, where
   *       verification would dominate the scan.
   * </ul>
   */
  static int anchorOffset(String literal) {
    if (literal == null || literal.length() < 2) {
      return NO_ANCHOR;
    }
    int offset = RarityOracle.rarestAsciiOffset(literal, literal.length());
    char anchor = literal.charAt(offset);
    if (anchor >= 128) {
      return NO_ANCHOR;
    }
    if (RarityOracle.byteRarity(anchor) <= RarityOracle.POISONOUS_ANCHOR_MAX_RARITY) {
      return NO_ANCHOR;
    }
    return offset;
  }

  /** Returns the anchor character for an offset returned by {@link #anchorOffset}. */
  static char anchorAt(String literal, int anchorOffset) {
    return anchorOffset == NO_ANCHOR ? '\0' : literal.charAt(anchorOffset);
  }

  /**
   * Returns the index of the first occurrence of {@code literal} at or after {@code fromIndex}, or
   * {@code -1}.
   *
   * @param anchorOffset an offset from {@link #anchorOffset}, or {@link #NO_ANCHOR}
   * @param anchor the character at {@code anchorOffset}, precomputed at compile time
   */
  static int indexOf(String text, String literal, int anchorOffset, char anchor, int fromIndex) {
    if (anchorOffset == NO_ANCHOR) {
      return indexOfDirect(text, literal, fromIndex);
    }
    int length = text.length();
    int literalLength = literal.length();
    int lastStart = length - literalLength;
    int pos = Math.max(0, fromIndex);
    long verificationWork = 0;
    long workLimit = WorkLimit.forRemaining(length - pos);

    while (pos <= lastStart) {
      int searchFrom = pos + anchorOffset;
      int hit = text.indexOf(anchor, searchFrom);
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record(hit < 0 ? length - searchFrom : hit - searchFrom + 1);
      }
      if (hit < 0) {
        return -1;
      }
      int candidate = hit - anchorOffset;
      if (candidate > lastStart) {
        return -1;
      }
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record(literalLength);
      }
      if (text.startsWith(literal, candidate)) {
        return candidate;
      }
      verificationWork += literalLength;
      pos = candidate + 1;
      if (WorkLimit.isExhausted(verificationWork, workLimit)) {
        return indexOfDirect(text, literal, pos);
      }
    }
    return -1;
  }

  /** Unanchored search, with the work accounting the accelerators previously applied inline. */
  static int indexOfDirect(String text, String literal, int fromIndex) {
    int idx = text.indexOf(literal, fromIndex);
    if (WorkCounterConfig.ENABLED) {
      int scanned = idx >= 0 ? idx - fromIndex + literal.length() : text.length() - fromIndex;
      WorkCounter.record(Math.max(0, scanned));
    }
    return idx;
  }

  private StringLiteralSearch() {}
}
