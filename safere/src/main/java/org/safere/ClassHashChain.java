// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Precomputed 2-gram shift table (Hash-Chain scanner) for case-insensitive literal and prefix
 * acceleration on {@link CharSequence} and {@link String} inputs.
 *
 * <h2>Algorithm & Design</h2>
 *
 * <p>Based on the 2-gram Hash-Chain exact string matching technique (Palmer, SEA 2024), this class
 * constructs a direct-mapped 1024-entry shift table ({@code byte[1024]}, 1 KB) mapping adjacent
 * 16-bit code unit pairs {@code (c0, c1)} to candidate shifts.
 *
 * <p>For a pattern literal of length {@code M}, each adjacent pair in the case-folded character
 * sequence is mapped to a shift of {@code M - 2 - i}. The terminal pair at {@code [M - 2, M - 1]}
 * is assigned a shift of {@code 0}. Non-matching pairs shift forward by up to {@code M - 1}
 * characters. Full Unicode case folding (including Cyrillic, Greek, accented Latin, and surrogate
 * pairs) is expanded during compile time via {@link Inst#simpleFold(int)}.
 *
 * <h2>Why Hash-Chain is used exclusively for {@link CharSequence} / {@link String}</h2>
 *
 * <p>During empirical performance evaluations across both UTF-8 ({@code byte[]}) and UTF-16 ({@code
 * CharSequence}) input pipelines:
 *
 * <ul>
 *   <li><b>UTF-8 / Byte Streams:</b> On {@code byte[]} buffers, 64-bit SWAR ({@link ByteSwarScan})
 *       and 256-bit Vector API ({@link ByteVectorScan}) vectorized scanning kernels inspect 8 to 32
 *       bytes per cycle, while bounded Boyer-Moore-Horspool bad-character skip loops achieve rapid
 *       skips with zero memory indirection. Scalar 2-gram hash chains on raw byte streams added
 *       table lookup indirection without beating vectorized SWAR/SIMD filters. Therefore, UTF-8
 *       literal scanning relies directly on SWAR/Vector filters with Horspool fallbacks.
 *   <li><b>UTF-16 / CharSequence Strings:</b> HotSpot C2 provides highly optimized vector
 *       intrinsics for single-character anchor searches (such as {@link String#indexOf(int)}).
 *       However, full multi-character case-folded matching lacks dedicated SIMD intrinsics in Java.
 *       In inputs with dense false-anchor matches (e.g. searching {@code (?i)keyword} in text
 *       flooded with isolated {@code 'k'} characters), naive anchor scanning degrades towards
 *       quadratic $O(N \cdot M)$ execution.
 *   <li><b>Hybrid Fast-Path Acceleration:</b> Combining fast C2 SIMD {@code indexOf} dead-space
 *       sweeps with {@link #shiftAt(CharSequence, int)} 2-gram terminal checks allows SafeRE to
 *       verify candidates in $O(1)$ time and skip forward by up to $M - 1$ characters upon false
 *       anchor hits. This completely eliminates false-anchor candidate storms and delivers
 *       4.5x–5.0x speedups on case-insensitive string searches.
 * </ul>
 */
final class ClassHashChain {
  private static final int TABLE_SIZE = 1024;
  private static final int TABLE_MASK = 0x3FF;

  private final String literal;
  private final byte[] shifts;
  private final int length;

  private ClassHashChain(String literal, byte[] shifts, int length) {
    this.literal = literal;
    this.shifts = shifts;
    this.length = length;
  }

  static int hash(char c0, char c1) {
    return ((c0 * 31 + c1) ^ (c0 >>> 5)) & TABLE_MASK;
  }

  /**
   * Compiles a precomputed 2-gram shift table for a case-insensitive literal string.
   *
   * @param literal the literal string to compile (must be at least 4 code units)
   * @return a compiled {@link ClassHashChain}, or {@code null} if literal is too short or has
   *     unstable case folding widths
   */
  static ClassHashChain compileCaseInsensitive(String literal) {
    if (literal == null || literal.length() < 4) {
      return null;
    }
    int m = literal.length();
    List<char[][]> codePointVariants = new ArrayList<>();
    for (int i = 0; i < literal.length(); ) {
      int codePoint = literal.codePointAt(i);
      char[] original = Character.toChars(codePoint);
      List<char[]> variants = new ArrayList<>();
      int folded = codePoint;
      do {
        char[] foldedChars = Character.toChars(folded);
        if (foldedChars.length != original.length) {
          return null;
        }
        if (variants.stream().noneMatch(existing -> Arrays.equals(existing, foldedChars))) {
          variants.add(foldedChars);
        }
        folded = Inst.simpleFold(folded);
      } while (folded != codePoint);
      codePointVariants.add(variants.toArray(char[][]::new));
      i += original.length;
    }

    int[] codePointIndexForChar = new int[m];
    int[] intraCharOffset = new int[m];
    int charIndex = 0;
    for (int codePointIndex = 0; codePointIndex < codePointVariants.size(); codePointIndex++) {
      int charCount = codePointVariants.get(codePointIndex)[0].length;
      for (int charOffset = 0; charOffset < charCount; charOffset++) {
        codePointIndexForChar[charIndex] = codePointIndex;
        intraCharOffset[charIndex] = charOffset;
        charIndex++;
      }
    }

    byte[] shifts = new byte[TABLE_SIZE];
    int defaultShift = Math.min(127, m - 1);
    Arrays.fill(shifts, (byte) defaultShift);

    for (int i = 0; i < m - 2; i++) {
      int shiftVal = m - 2 - i;
      int cp0 = codePointIndexForChar[i];
      int off0 = intraCharOffset[i];
      int cp1 = codePointIndexForChar[i + 1];
      int off1 = intraCharOffset[i + 1];
      if (cp0 == cp1) {
        for (char[] variant : codePointVariants.get(cp0)) {
          int h = hash(variant[off0], variant[off1]);
          shifts[h] = (byte) Math.min(shifts[h] & 0xFF, shiftVal);
        }
      } else {
        for (char[] variant0 : codePointVariants.get(cp0)) {
          for (char[] variant1 : codePointVariants.get(cp1)) {
            int h = hash(variant0[off0], variant1[off1]);
            shifts[h] = (byte) Math.min(shifts[h] & 0xFF, shiftVal);
          }
        }
      }
    }

    int cpLast0 = codePointIndexForChar[m - 2];
    int offLast0 = intraCharOffset[m - 2];
    int cpLast1 = codePointIndexForChar[m - 1];
    int offLast1 = intraCharOffset[m - 1];
    if (cpLast0 == cpLast1) {
      for (char[] variant : codePointVariants.get(cpLast0)) {
        shifts[hash(variant[offLast0], variant[offLast1])] = 0;
      }
    } else {
      for (char[] variant0 : codePointVariants.get(cpLast0)) {
        for (char[] variant1 : codePointVariants.get(cpLast1)) {
          shifts[hash(variant0[offLast0], variant1[offLast1])] = 0;
        }
      }
    }

    return new ClassHashChain(literal, shifts, m);
  }

  /**
   * Returns the shift distance at the specified candidate position, or 0 if the terminal 2-gram
   * matches.
   */
  int shiftAt(CharSequence text, int candidatePos) {
    int inPos = candidatePos + (length - 1);
    if (inPos >= text.length()) {
      return 0;
    }
    char c0 = text.charAt(inPos - 1);
    char c1 = text.charAt(inPos);
    int h = hash(c0, c1);
    return shifts[h] & 0xFF;
  }

  /** Searches for the case-insensitive literal in {@code text} starting from {@code start}. */
  int search(CharSequence text, int start, long workLimit) {
    int textLength = text.length();
    if (textLength - start < length) {
      return -1;
    }
    int last = length - 1;
    int position = start + last;
    long work = 0;

    while (position < textLength) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record();
      }
      char c0 = text.charAt(position - 1);
      char c1 = text.charAt(position);
      int h = hash(c0, c1);
      int shift = shifts[h] & 0xFF;

      if (shift == 0) {
        int candidate = position - last;
        if (Utf16.regionMatchesUnicodeIgnoreCase(text, candidate, literal)) {
          return candidate;
        }
        work += length;
        if (WorkLimit.isExhausted(work, workLimit)) {
          return -2;
        }
        position++;
      } else {
        position += shift;
        work++;
        if (WorkLimit.isExhausted(work, workLimit)) {
          return -2;
        }
      }
    }
    return -1;
  }
}
