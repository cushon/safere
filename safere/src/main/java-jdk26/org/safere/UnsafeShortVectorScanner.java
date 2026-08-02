// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Standard unsafe ShortVector scanning implementation. Always converts Latin-1 characters to shorts
 * in registers, making it simpler but slower than UnsafeByteVectorScanner for Latin-1.
 */
final class UnsafeShortVectorScanner implements VectorScanProvider {
  private static final VectorSpecies<Short> SPECIES = ShortVector.SPECIES_PREFERRED;
  private static final int MINIMUM_INPUT_LENGTH = 32;

  private final VectorScanProvider byteDelegate;

  UnsafeShortVectorScanner(VectorScanProvider byteDelegate) {
    this.byteDelegate = byteDelegate;
  }

  @Override
  public int minimumInputLength() {
    return MINIMUM_INPUT_LENGTH;
  }

  @Override
  public int indexOfAsciiClass(byte[] bytes, int offset, int length, int[] ranges, int start) {
    return byteDelegate.indexOfAsciiClass(bytes, offset, length, ranges, start);
  }

  @Override
  public int indexOfCharClass(String text, Pattern.CharClassScanInfo scanInfo, int start) {
    int textLen = text.length();
    int remaining = textLen - start;

    if (remaining < MINIMUM_INPUT_LENGTH) {
      return -2;
    }

    int numRanges = scanInfo.ranges.length / 2;
    if (numRanges > 4) {
      return -2;
    }

    int pos = start;
    int vectorLen = SPECIES.length();
    int limit = textLen - 2 * vectorLen;

    ShortVector[] lowVecs = new ShortVector[numRanges];
    ShortVector[] highVecs = new ShortVector[numRanges];
    for (int r = 0; r < numRanges; r++) {
      lowVecs[r] = ShortVector.broadcast(SPECIES, (short) scanInfo.ranges[r * 2]);
      highVecs[r] = ShortVector.broadcast(SPECIES, (short) scanInfo.ranges[r * 2 + 1]);
    }

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

    if (remaining < MINIMUM_INPUT_LENGTH) {
      return -2;
    }

    for (int r : ranges) {
      if (r >= 65536) {
        return -2;
      }
    }

    int numRanges = ranges.length / 2;
    if (numRanges > 4) {
      return -2;
    }

    int pos = start;
    int vectorLen = SPECIES.length();
    int limit = textLen - 2 * vectorLen;

    ShortVector[] lowVecs = new ShortVector[numRanges];
    ShortVector[] highVecs = new ShortVector[numRanges];
    for (int r = 0; r < numRanges; r++) {
      lowVecs[r] = ShortVector.broadcast(SPECIES, (short) ranges[r * 2]);
      highVecs[r] = ShortVector.broadcast(SPECIES, (short) ranges[r * 2 + 1]);
    }

    ShortVector surrogateLow = ShortVector.broadcast(SPECIES, (short) 0xD800);
    ShortVector surrogateHigh = ShortVector.broadcast(SPECIES, (short) 0xDFFF);

    for (; pos <= limit; pos += vectorLen) {
      ShortVector inputVec = StringUnsafeLoader.loadShortVector(SPECIES, text, pos);

      VectorMask<Short> surrogateMask =
          inputVec
              .compare(VectorOperators.GE, surrogateLow)
              .and(inputVec.compare(VectorOperators.LE, surrogateHigh));
      if (surrogateMask.anyTrue()) {
        return -2;
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

    for (; pos < textLen; pos++) {
      char ch = text.charAt(pos);
      if (Character.isSurrogate(ch)) {
        return -2;
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
