// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

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

    if (coder == 0 && scanInfo.isAscii) {
      return indexOfCharClassByte(text, scanInfo, start, numRanges);
    } else {
      return indexOfCharClassShort(text, scanInfo, start, numRanges);
    }
  }

  private int indexOfCharClassByte(
      String text, Pattern.CharClassScanInfo scanInfo, int start, int numRanges) {
    int textLen = text.length();
    int pos = start;
    int vectorLen = BYTE_SPECIES.length();
    int limit = textLen - vectorLen;

    ByteVector[] lowVecs = new ByteVector[numRanges];
    ByteVector[] highVecs = new ByteVector[numRanges];
    for (int r = 0; r < numRanges; r++) {
      lowVecs[r] = ByteVector.broadcast(BYTE_SPECIES, (byte) scanInfo.ranges[r * 2]);
      highVecs[r] = ByteVector.broadcast(BYTE_SPECIES, (byte) scanInfo.ranges[r * 2 + 1]);
    }

    byte[] value = StringUnsafeLoader.getBackingArray(text);

    if (numRanges == 1) {
      ByteVector low = lowVecs[0];
      ByteVector high = highVecs[0];
      for (; pos <= limit; pos += vectorLen) {
        ByteVector inputVec = ByteVector.fromArray(BYTE_SPECIES, value, pos);
        VectorMask<Byte> matchMask =
            inputVec
                .compare(VectorOperators.GE, low)
                .and(inputVec.compare(VectorOperators.LE, high));
        if (matchMask.anyTrue()) {
          return pos + matchMask.firstTrue();
        }
      }
    } else {
      for (; pos <= limit; pos += vectorLen) {
        ByteVector inputVec = ByteVector.fromArray(BYTE_SPECIES, value, pos);
        VectorMask<Byte> matchMask = BYTE_SPECIES.maskAll(false);
        for (int r = 0; r < numRanges; r++) {
          VectorMask<Byte> rangeMask =
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

  private int indexOfCharClassShort(
      String text, Pattern.CharClassScanInfo scanInfo, int start, int numRanges) {
    int textLen = text.length();
    int pos = start;
    int vectorLen = SHORT_SPECIES.length();
    int limit = textLen - 2 * vectorLen;

    ShortVector[] lowVecs = new ShortVector[numRanges];
    ShortVector[] highVecs = new ShortVector[numRanges];
    for (int r = 0; r < numRanges; r++) {
      lowVecs[r] = ShortVector.broadcast(SHORT_SPECIES, (short) scanInfo.ranges[r * 2]);
      highVecs[r] = ShortVector.broadcast(SHORT_SPECIES, (short) scanInfo.ranges[r * 2 + 1]);
    }

    if (numRanges == 1) {
      ShortVector low = lowVecs[0];
      ShortVector high = highVecs[0];
      for (; pos <= limit; pos += vectorLen) {
        ShortVector inputVec = StringUnsafeLoader.loadShortVector(SHORT_SPECIES, text, pos);
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
        ShortVector inputVec = StringUnsafeLoader.loadShortVector(SHORT_SPECIES, text, pos);
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

    byte coder = StringUnsafeLoader.getCoder(text);
    boolean isAscii = true;
    for (int bound : ranges) {
      if (bound > 127) {
        isAscii = false;
        break;
      }
    }

    if (coder == 0 && isAscii) {
      return indexOfCodePointClassByte(text, ranges, start, numRanges);
    } else {
      return indexOfCodePointClassShort(text, ranges, start, numRanges);
    }
  }

  private int indexOfCodePointClassByte(String text, int[] ranges, int start, int numRanges) {
    int textLen = text.length();
    int pos = start;
    int vectorLen = BYTE_SPECIES.length();
    int limit = textLen - vectorLen;

    ByteVector[] lowVecs = new ByteVector[numRanges];
    ByteVector[] highVecs = new ByteVector[numRanges];
    for (int r = 0; r < numRanges; r++) {
      lowVecs[r] = ByteVector.broadcast(BYTE_SPECIES, (byte) ranges[r * 2]);
      highVecs[r] = ByteVector.broadcast(BYTE_SPECIES, (byte) ranges[r * 2 + 1]);
    }

    byte[] value = StringUnsafeLoader.getBackingArray(text);

    for (; pos <= limit; pos += vectorLen) {
      ByteVector inputVec = ByteVector.fromArray(BYTE_SPECIES, value, pos);
      VectorMask<Byte> matchMask = BYTE_SPECIES.maskAll(false);
      for (int r = 0; r < numRanges; r++) {
        VectorMask<Byte> rangeMask =
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
      for (int r = 0; r < numRanges; r++) {
        if (ch >= ranges[r * 2] && ch <= ranges[r * 2 + 1]) {
          return pos;
        }
      }
    }

    return -1;
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
}
