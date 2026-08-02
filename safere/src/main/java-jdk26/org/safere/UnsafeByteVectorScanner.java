// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Arrays;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Advanced zero-copy implementation of VectorScanProvider for String operations. Uses ByteVector
 * direct scanning for ASCII strings to maximize throughput, and falls back to ShortVector scanning
 * for UTF-16 or non-ASCII classes.
 */
final class UnsafeByteVectorScanner implements VectorScanProvider {
  private static final VectorSpecies<Byte> BYTE_SPECIES = ByteVector.SPECIES_PREFERRED;
  private static final VectorSpecies<Short> SHORT_SPECIES = ShortVector.SPECIES_PREFERRED;
  private static final int MINIMUM_INPUT_LENGTH = 32;

  private final VectorScanProvider byteDelegate;

  UnsafeByteVectorScanner(VectorScanProvider byteDelegate) {
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

    byte coder = StringUnsafeLoader.getCoder(text);

    if (coder == 0) {
      if (scanInfo.isAscii) {
        return indexOfCharClassByte(text, scanInfo.ranges, start, numRanges, true /* isAscii */);
      } else {
        int[] clampedRanges = clampRangesForLatin1(scanInfo.ranges);
        if (clampedRanges != null) {
          int clampedNumRanges = clampedRanges.length / 2;
          if (clampedNumRanges > 0 && clampedNumRanges <= 4) {
            return indexOfCharClassByte(text, clampedRanges, start, clampedNumRanges, false /* isAscii */);
          }
        }
      }
    }

    return indexOfCharClassShort(text, scanInfo, start, numRanges);
  }

  private int indexOfCharClassByte(
      String text, int[] ranges, int start, int numRanges, boolean isAscii) {
    int textLen = text.length();
    int pos = start;
    int vectorLen = BYTE_SPECIES.length();
    int limit = textLen - vectorLen;

    ByteVector[] lowVecs = new ByteVector[numRanges];
    ByteVector[] highMinusLowVecs = new ByteVector[numRanges];
    for (int r = 0; r < numRanges; r++) {
      byte low = (byte) ranges[r * 2];
      byte high = (byte) ranges[r * 2 + 1];
      lowVecs[r] = ByteVector.broadcast(BYTE_SPECIES, low);
      highMinusLowVecs[r] = ByteVector.broadcast(BYTE_SPECIES, (byte) (high - low));
    }

    byte[] value = StringUnsafeLoader.getBackingArray(text);

    if (numRanges == 1) {
      ByteVector low = lowVecs[0];
      if (isAscii) {
        ByteVector high = ByteVector.broadcast(BYTE_SPECIES, (byte) ranges[1]);
        int unrolledLimit = limit - 3 * vectorLen;
        for (; pos <= unrolledLimit; pos += 4 * vectorLen) {
          ByteVector v0 = ByteVector.fromArray(BYTE_SPECIES, value, pos);
          ByteVector v1 = ByteVector.fromArray(BYTE_SPECIES, value, pos + vectorLen);
          ByteVector v2 = ByteVector.fromArray(BYTE_SPECIES, value, pos + 2 * vectorLen);
          ByteVector v3 = ByteVector.fromArray(BYTE_SPECIES, value, pos + 3 * vectorLen);

          VectorMask<Byte> m0 = v0.compare(VectorOperators.GE, low).and(v0.compare(VectorOperators.LE, high));
          VectorMask<Byte> m1 = v1.compare(VectorOperators.GE, low).and(v1.compare(VectorOperators.LE, high));
          VectorMask<Byte> m2 = v2.compare(VectorOperators.GE, low).and(v2.compare(VectorOperators.LE, high));
          VectorMask<Byte> m3 = v3.compare(VectorOperators.GE, low).and(v3.compare(VectorOperators.LE, high));

          VectorMask<Byte> merged = m0.or(m1).or(m2).or(m3);
          if (merged.anyTrue()) {
            if (m0.anyTrue()) return pos + m0.firstTrue();
            if (m1.anyTrue()) return pos + vectorLen + m1.firstTrue();
            if (m2.anyTrue()) return pos + 2 * vectorLen + m2.firstTrue();
            return pos + 3 * vectorLen + m3.firstTrue();
          }
        }
        for (; pos <= limit; pos += vectorLen) {
          ByteVector inputVec = ByteVector.fromArray(BYTE_SPECIES, value, pos);
          VectorMask<Byte> matchMask =
              inputVec.compare(VectorOperators.GE, low).and(inputVec.compare(VectorOperators.LE, high));
          if (matchMask.anyTrue()) {
            return pos + matchMask.firstTrue();
          }
        }
      } else {
        ByteVector highMinusLow = highMinusLowVecs[0];
        int unrolledLimit = limit - 3 * vectorLen;
        for (; pos <= unrolledLimit; pos += 4 * vectorLen) {
          ByteVector v0 = ByteVector.fromArray(BYTE_SPECIES, value, pos);
          ByteVector v1 = ByteVector.fromArray(BYTE_SPECIES, value, pos + vectorLen);
          ByteVector v2 = ByteVector.fromArray(BYTE_SPECIES, value, pos + 2 * vectorLen);
          ByteVector v3 = ByteVector.fromArray(BYTE_SPECIES, value, pos + 3 * vectorLen);

          VectorMask<Byte> m0 = v0.sub(low).compare(VectorOperators.ULE, highMinusLow);
          VectorMask<Byte> m1 = v1.sub(low).compare(VectorOperators.ULE, highMinusLow);
          VectorMask<Byte> m2 = v2.sub(low).compare(VectorOperators.ULE, highMinusLow);
          VectorMask<Byte> m3 = v3.sub(low).compare(VectorOperators.ULE, highMinusLow);

          VectorMask<Byte> merged = m0.or(m1).or(m2).or(m3);
          if (merged.anyTrue()) {
            if (m0.anyTrue()) return pos + m0.firstTrue();
            if (m1.anyTrue()) return pos + vectorLen + m1.firstTrue();
            if (m2.anyTrue()) return pos + 2 * vectorLen + m2.firstTrue();
            return pos + 3 * vectorLen + m3.firstTrue();
          }
        }
        for (; pos <= limit; pos += vectorLen) {
          ByteVector inputVec = ByteVector.fromArray(BYTE_SPECIES, value, pos);
          VectorMask<Byte> matchMask =
              inputVec.sub(low).compare(VectorOperators.ULE, highMinusLow);
          if (matchMask.anyTrue()) {
            return pos + matchMask.firstTrue();
          }
        }
      }
    } else {
      for (; pos <= limit; pos += vectorLen) {
        ByteVector inputVec = ByteVector.fromArray(BYTE_SPECIES, value, pos);
        VectorMask<Byte> matchMask = BYTE_SPECIES.maskAll(false);
        for (int r = 0; r < numRanges; r++) {
          VectorMask<Byte> rangeMask =
              inputVec.sub(lowVecs[r]).compare(VectorOperators.ULE, highMinusLowVecs[r]);
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
        if (ch >= ranges[r * 2] && ch <= ranges[r * 2 + 1]) {
          return pos;
        }
      }
    }

    return -1;
  }

  private int indexOfCharClassShort(
      String text, Pattern.CharClassScanInfo scanInfo, int start, int numRanges) {
    int textLen = text.length();
    int pos = start;
    int vectorLen = SHORT_SPECIES.length();
    int limit = textLen - 2 * vectorLen;

    ShortVector[] lowVecs = new ShortVector[numRanges];
    ShortVector[] highMinusLowVecs = new ShortVector[numRanges];
    for (int r = 0; r < numRanges; r++) {
      short low = (short) scanInfo.ranges[r * 2];
      short high = (short) scanInfo.ranges[r * 2 + 1];
      lowVecs[r] = ShortVector.broadcast(SHORT_SPECIES, low);
      highMinusLowVecs[r] = ShortVector.broadcast(SHORT_SPECIES, (short) (high - low));
    }

    if (numRanges == 1) {
      ShortVector low = lowVecs[0];
      ShortVector high = ShortVector.broadcast(SHORT_SPECIES, (short) scanInfo.ranges[1]);
      int unrolledLimit = limit - 3 * vectorLen;
      for (; pos <= unrolledLimit; pos += 4 * vectorLen) {
        ShortVector v0 = StringUnsafeLoader.loadShortVector(SHORT_SPECIES, text, pos);
        ShortVector v1 = StringUnsafeLoader.loadShortVector(SHORT_SPECIES, text, pos + vectorLen);
        ShortVector v2 = StringUnsafeLoader.loadShortVector(SHORT_SPECIES, text, pos + 2 * vectorLen);
        ShortVector v3 = StringUnsafeLoader.loadShortVector(SHORT_SPECIES, text, pos + 3 * vectorLen);

        VectorMask<Short> m0 = v0.compare(VectorOperators.GE, low).and(v0.compare(VectorOperators.LE, high));
        VectorMask<Short> m1 = v1.compare(VectorOperators.GE, low).and(v1.compare(VectorOperators.LE, high));
        VectorMask<Short> m2 = v2.compare(VectorOperators.GE, low).and(v2.compare(VectorOperators.LE, high));
        VectorMask<Short> m3 = v3.compare(VectorOperators.GE, low).and(v3.compare(VectorOperators.LE, high));

        VectorMask<Short> merged = m0.or(m1).or(m2).or(m3);
        if (merged.anyTrue()) {
          if (m0.anyTrue()) return pos + m0.firstTrue();
          if (m1.anyTrue()) return pos + vectorLen + m1.firstTrue();
          if (m2.anyTrue()) return pos + 2 * vectorLen + m2.firstTrue();
          return pos + 3 * vectorLen + m3.firstTrue();
        }
      }
      for (; pos <= limit; pos += vectorLen) {
        ShortVector inputVec = StringUnsafeLoader.loadShortVector(SHORT_SPECIES, text, pos);
        VectorMask<Short> matchMask =
            inputVec.compare(VectorOperators.GE, low).and(inputVec.compare(VectorOperators.LE, high));
        if (matchMask.anyTrue()) {
          return pos + matchMask.firstTrue();
        }
      }
    } else {
      for (; pos <= limit; pos += vectorLen) {
        ShortVector inputVec = StringUnsafeLoader.loadShortVector(SHORT_SPECIES, text, pos);
        VectorMask<Short> matchMask = SHORT_SPECIES.maskAll(false);
        for (int r = 0; r < numRanges; r++) {
          VectorMask<Short> rangeMask =
              inputVec.sub(lowVecs[r]).compare(VectorOperators.ULE, highMinusLowVecs[r]);
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

    byte coder = StringUnsafeLoader.getCoder(text);
    int[] activeRanges = ranges;
    int numRanges;

    if (coder == 0) {
      activeRanges = clampRangesForLatin1(ranges);
      if (activeRanges == null) {
        return -1; // No ranges can match in Latin-1
      }
    }

    for (int r : activeRanges) {
      if (r >= 65536) {
        return -2;
      }
    }

    numRanges = activeRanges.length / 2;
    if (numRanges > 4) {
      return -2;
    }

    boolean isAscii = true;
    for (int bound : activeRanges) {
      if (bound > 127) {
        isAscii = false;
        break;
      }
    }

    if (coder == 0) {
      return indexOfCharClassByte(text, activeRanges, start, numRanges, isAscii);
    }

    return indexOfCodePointClassShort(text, activeRanges, start, numRanges);
  }
  private int indexOfCodePointClassShort(String text, int[] ranges, int start, int numRanges) {
    int textLen = text.length();
    int pos = start;
    int vectorLen = SHORT_SPECIES.length();
    int limit = textLen - 2 * vectorLen;

    ShortVector[] lowVecs = new ShortVector[numRanges];
    ShortVector[] highVecs = new ShortVector[numRanges];
    for (int r = 0; r < numRanges; r++) {
      lowVecs[r] = ShortVector.broadcast(SHORT_SPECIES, (short) ranges[r * 2]);
      highVecs[r] = ShortVector.broadcast(SHORT_SPECIES, (short) ranges[r * 2 + 1]);
    }

    ShortVector surrogateLow = ShortVector.broadcast(SHORT_SPECIES, (short) 0xD800);
    ShortVector surrogateHigh = ShortVector.broadcast(SHORT_SPECIES, (short) 0xDFFF);

    for (; pos <= limit; pos += vectorLen) {
      ShortVector inputVec = StringUnsafeLoader.loadShortVector(SHORT_SPECIES, text, pos);

      VectorMask<Short> surrogateMask =
          inputVec
              .compare(VectorOperators.GE, surrogateLow)
              .and(inputVec.compare(VectorOperators.LE, surrogateHigh));
      if (surrogateMask.anyTrue()) {
        return -2;
      }

      VectorMask<Short> matchMask = SHORT_SPECIES.maskAll(false);
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

  private static int[] clampRangesForLatin1(int[] ranges) {
    int numRanges = ranges.length / 2;
    int[] clamped = new int[ranges.length];
    int writeIdx = 0;
    for (int r = 0; r < numRanges; r++) {
      int low = ranges[r * 2];
      int high = ranges[r * 2 + 1];
      if (low > 255) {
        // Entirely above Latin-1, skip this range
        continue;
      }
      int clampedHigh = Math.min(high, 255);
      if (low <= clampedHigh) {
        clamped[writeIdx++] = low;
        clamped[writeIdx++] = clampedHigh;
      }
    }
    if (writeIdx == 0) {
      return null;
    }
    if (writeIdx < ranges.length) {
      return Arrays.copyOf(clamped, writeIdx);
    }
    return clamped;
  }
}
