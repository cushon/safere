// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Arrays;

/**
 * Immutable 128-bit bitmap representing a set of ASCII code points (0–127).
 *
 * <p>Bits 0–63 are stored in {@code bitmap0}; bits 64–127 are stored in {@code bitmap1}.
 */
record AsciiBitmap(long bitmap0, long bitmap1) {

  static final AsciiBitmap EMPTY = new AsciiBitmap(0L, 0L);

  static AsciiBitmap of(int cp) {
    if (cp >= 0 && cp < 64) {
      return new AsciiBitmap(1L << cp, 0L);
    }
    if (cp >= 64 && cp < 128) {
      return new AsciiBitmap(0L, 1L << (cp - 64));
    }
    return EMPTY;
  }

  boolean contains(int cp) {
    return (cp >>> 7 == 0) && containsAscii(cp);
  }

  /**
   * Unchecked ASCII membership test.
   *
   * <p>Only safe when the caller has already verified that {@code 0 <= cp <= 127}.
   */
  boolean containsAscii(int cp) {
    return (((cp & 64) == 0 ? bitmap0 : bitmap1) & (1L << cp)) != 0;
  }

  int cardinality() {
    return Long.bitCount(bitmap0) + Long.bitCount(bitmap1);
  }

  boolean isEmpty() {
    return bitmap0 == 0L && bitmap1 == 0L;
  }

  AsciiBitmap union(AsciiBitmap other) {
    if (other == null || other.isEmpty()) {
      return this;
    }
    return new AsciiBitmap(this.bitmap0 | other.bitmap0, this.bitmap1 | other.bitmap1);
  }

  /** Returns a 128-element boolean lookup array for maximum scalar loop throughput. */
  boolean[] toBooleanArray() {
    boolean[] array = new boolean[128];
    for (int i = 0; i < 64; i++) {
      if ((bitmap0 & (1L << i)) != 0) {
        array[i] = true;
      }
    }
    for (int i = 0; i < 64; i++) {
      if ((bitmap1 & (1L << i)) != 0) {
        array[64 + i] = true;
      }
    }
    return array;
  }

  /** Returns inclusive [low0, high0, low1, high1, ...] range pairs representing this ASCII set. */
  int[] toRanges() {
    int[] temp = new int[256];
    int count = 0;
    int start = -1;
    for (int i = 0; i < 128; i++) {
      if (containsAscii(i)) {
        if (start == -1) {
          start = i;
        }
      } else if (start != -1) {
        temp[count++] = start;
        temp[count++] = i - 1;
        start = -1;
      }
    }
    if (start != -1) {
      temp[count++] = start;
      temp[count++] = 127;
    }
    return Arrays.copyOf(temp, count);
  }

  static final class Builder {
    private long b0;
    private long b1;

    Builder add(int cp) {
      if (cp >= 0 && cp < 64) {
        b0 |= (1L << cp);
      } else if (cp >= 64 && cp < 128) {
        b1 |= (1L << (cp - 64));
      }
      return this;
    }

    Builder addRange(int lo, int hi) {
      for (int cp = Math.max(0, lo); cp <= Math.min(127, hi); cp++) {
        add(cp);
      }
      return this;
    }

    AsciiBitmap build() {
      return (b0 == 0L && b1 == 0L) ? EMPTY : new AsciiBitmap(b0, b1);
    }
  }
}
