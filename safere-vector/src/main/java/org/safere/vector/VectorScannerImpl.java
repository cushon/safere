// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.vector;

import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import org.safere.Pattern.CharClassScanInfo;

/** Implementation of VectorScannerBridge using zero-copy String internal reflection. */
public final class VectorScannerImpl implements VectorScannerBridge {
  private static final VectorSpecies<Short> SPECIES = ShortVector.SPECIES_PREFERRED;

  @Override
  public int indexOfCharClass(String text, CharClassScanInfo scanInfo, int start) {
    int textLen = text.length();
    int remaining = textLen - start;

    // Crossover gate: if input is too short, fallback to scalar (return -2)
    if (remaining < 32) {
      return -2;
    }

    int numRanges = scanInfo.ranges.length / 2;
    if (numRanges > 4) {
      return -2; // Fallback to scalar
    }

    int pos = start;
    int vectorLen = SPECIES.length();
    int limit = textLen - 2 * vectorLen;

    // Pre-broadcast ranges to vectors to avoid doing it in the loop
    ShortVector[] lowVecs = new ShortVector[numRanges];
    ShortVector[] highVecs = new ShortVector[numRanges];
    for (int r = 0; r < numRanges; r++) {
      lowVecs[r] = ShortVector.broadcast(SPECIES, (short) scanInfo.ranges[r * 2]);
      highVecs[r] = ShortVector.broadcast(SPECIES, (short) scanInfo.ranges[r * 2 + 1]);
    }

    // Vector scan loop
    if (numRanges == 1) {
      ShortVector low = lowVecs[0];
      ShortVector high = highVecs[0];
      for (; pos <= limit; pos += vectorLen) {
        ShortVector inputVec = StringUnsafeLoader.loadShortVector(SPECIES, text, pos);
        VectorMask<Short> matchMask =
            inputVec
                .compare(VectorOperators.GE, low)
                .and(inputVec.compare(VectorOperators.LE, high));
        if (matchMask.anyTrue()) {
          return pos + matchMask.firstTrue();
        }
      }
    } else {
      for (; pos <= limit; pos += vectorLen) {
        ShortVector inputVec = StringUnsafeLoader.loadShortVector(SPECIES, text, pos);
        VectorMask<Short> matchMask = SPECIES.maskAll(false);
        for (int r = 0; r < numRanges; r++) {
          VectorMask<Short> rangeMask =
              inputVec
                  .compare(VectorOperators.GE, lowVecs[r])
                  .and(inputVec.compare(VectorOperators.LE, highVecs[r]));
          matchMask = matchMask.or(rangeMask);
        }

        if (matchMask.anyTrue()) {
          return pos + matchMask.firstTrue();
        }
      }
    }

    // Scalar cleanup
    for (; pos < textLen; pos++) {
      char ch = text.charAt(pos);
      for (int r = 0; r < numRanges; r++) {
        if (ch >= scanInfo.ranges[r * 2] && ch <= scanInfo.ranges[r * 2 + 1]) {
          return pos;
        }
      }
    }

    return -1;
  }

  @Override
  public int indexOfCodePointClass(
      String text, int[] ranges, long bitmap0, long bitmap1, int start) {
    int textLen = text.length();
    int remaining = textLen - start;

    // Crossover gate: if input is too short, fallback to scalar
    if (remaining < 32) {
      return -2;
    }

    // Fallback if ranges contain non-BMP code points
    for (int r : ranges) {
      if (r >= 65536) {
        return -2;
      }
    }

    int numRanges = ranges.length / 2;
    if (numRanges > 4) {
      return -2; // Fallback to scalar
    }

    int pos = start;
    int vectorLen = SPECIES.length();
    int limit = textLen - 2 * vectorLen;

    // Pre-broadcast ranges
    ShortVector[] lowVecs = new ShortVector[numRanges];
    ShortVector[] highVecs = new ShortVector[numRanges];
    for (int r = 0; r < numRanges; r++) {
      lowVecs[r] = ShortVector.broadcast(SPECIES, (short) ranges[r * 2]);
      highVecs[r] = ShortVector.broadcast(SPECIES, (short) ranges[r * 2 + 1]);
    }

    ShortVector surrogateLow = ShortVector.broadcast(SPECIES, (short) 0xD800);
    ShortVector surrogateHigh = ShortVector.broadcast(SPECIES, (short) 0xDFFF);

    // Vector scan loop
    if (numRanges == 1) {
      ShortVector low = lowVecs[0];
      ShortVector high = highVecs[0];
      for (; pos <= limit; pos += vectorLen) {
        ShortVector inputVec = StringUnsafeLoader.loadShortVector(SPECIES, text, pos);

        // Check for surrogates
        VectorMask<Short> surrogateMask =
            inputVec
                .compare(VectorOperators.GE, surrogateLow)
                .and(inputVec.compare(VectorOperators.LE, surrogateHigh));
        if (surrogateMask.anyTrue()) {
          return -2; // Fallback to scalar starting from the current position
        }

        VectorMask<Short> matchMask =
            inputVec
                .compare(VectorOperators.GE, low)
                .and(inputVec.compare(VectorOperators.LE, high));
        if (matchMask.anyTrue()) {
          return pos + matchMask.firstTrue();
        }
      }
    } else {
      for (; pos <= limit; pos += vectorLen) {
        ShortVector inputVec = StringUnsafeLoader.loadShortVector(SPECIES, text, pos);

        // Check for surrogates
        VectorMask<Short> surrogateMask =
            inputVec
                .compare(VectorOperators.GE, surrogateLow)
                .and(inputVec.compare(VectorOperators.LE, surrogateHigh));
        if (surrogateMask.anyTrue()) {
          return -2; // Fallback to scalar
        }

        VectorMask<Short> matchMask = SPECIES.maskAll(false);
        for (int r = 0; r < numRanges; r++) {
          VectorMask<Short> rangeMask =
              inputVec
                  .compare(VectorOperators.GE, lowVecs[r])
                  .and(inputVec.compare(VectorOperators.LE, highVecs[r]));
          matchMask = matchMask.or(rangeMask);
        }

        if (matchMask.anyTrue()) {
          return pos + matchMask.firstTrue();
        }
      }
    }

    // Scalar cleanup
    for (; pos < textLen; pos++) {
      char ch = text.charAt(pos);
      if (Character.isSurrogate(ch)) {
        return -2; // Fallback to scalar
      }
      for (int r = 0; r < numRanges; r++) {
        if (ch >= ranges[r * 2] && ch <= ranges[r * 2 + 1]) {
          return pos;
        }
      }
    }

    return -1;
  }
}
