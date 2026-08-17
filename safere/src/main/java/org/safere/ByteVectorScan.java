// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static jdk.incubator.vector.VectorOperators.EQ;
import static jdk.incubator.vector.VectorOperators.GE;
import static jdk.incubator.vector.VectorOperators.LE;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorSpecies;

/**
 * Stateless SIMD kernels using the incubating Vector API for 1-byte sequences (UTF-8 and Latin-1).
 */
final class ByteVectorScan {
  static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;

  static int indexOfAsciiClass(byte[] bytes, int offset, int length, int[] ranges, int start) {
    if (!Swar.supportsAsciiRanges(ranges, 4)) {
      return VectorScanProvider.UNSUPPORTED;
    }
    int position = Math.max(0, start);
    int limit = position + SPECIES.loopBound(length - position);
    for (; position < limit; position += SPECIES.length()) {
      ByteVector values = ByteVector.fromArray(SPECIES, bytes, offset + position);
      VectorMask<Byte> matches = matches(values, ranges);
      if (matches.anyTrue()) {
        return position + matches.firstTrue();
      }
    }
    for (; position < length; position++) {
      if (matches(bytes[offset + position], ranges)) {
        return position;
      }
    }
    return -1;
  }

  static VectorMask<Byte> matches(ByteVector values, int[] ranges) {
    VectorMask<Byte> matches = matches(values, ranges[0], ranges[1]);
    if (ranges.length >= 4) {
      matches = matches.or(matches(values, ranges[2], ranges[3]));
    }
    if (ranges.length >= 6) {
      matches = matches.or(matches(values, ranges[4], ranges[5]));
    }
    if (ranges.length == 8) {
      matches = matches.or(matches(values, ranges[6], ranges[7]));
    }
    return matches;
  }

  private static VectorMask<Byte> matches(ByteVector values, int lowBound, int highBound) {
    byte low = (byte) lowBound;
    byte high = (byte) highBound;
    if (low == high) {
      return values.eq(low);
    }
    if (highBound == lowBound + 1) {
      return values.eq(low).or(values.eq(high));
    }
    return values.compare(GE, low).and(values.compare(LE, high));
  }

  static boolean matches(byte value, int[] ranges) {
    for (int index = 0; index < ranges.length; index += 2) {
      if (value >= (byte) ranges[index] && value <= (byte) ranges[index + 1]) {
        return true;
      }
    }
    return false;
  }

  public static int indexOfIgnoreCase(
      byte[] bytes,
      int offset,
      int length,
      String prefix,
      int prefixLen,
      int anchorOffset,
      byte low,
      byte high,
      int start) {
    if (prefixLen == 0) {
      return Math.min(Math.max(0, start), length);
    }
    int pos = Math.max(0, start);
    long verificationWork = 0;
    long workLimit = WorkLimit.forRemaining(length - pos);

    // Fast scalar prologue to catch immediate matches without SIMD setup
    int scalarPrologueLimit = Math.min(length - prefixLen + 1, pos + Integer.BYTES);
    for (; pos < scalarPrologueLimit; pos++) {
      int b = bytes[offset + pos + anchorOffset] & 0xFF;
      if ((b == (low & 0xFF) || b == (high & 0xFF))
          && Ascii.regionMatchesIgnoreCase(bytes, offset + pos, prefix, prefixLen)) {
        return pos;
      }
      if (b == (low & 0xFF) || b == (high & 0xFF)) {
        verificationWork += prefixLen;
        if (WorkLimit.isExhausted(verificationWork, workLimit)) {
          return VectorScanProvider.UNSUPPORTED;
        }
      }
    }

    int vectorLen = SPECIES.length();
    int limit = length - vectorLen;
    if (pos > limit) {
      int limitScalar = length - prefixLen;
      for (int p = Math.max(start, pos - anchorOffset); p <= limitScalar; p++) {
        int b = bytes[offset + p + anchorOffset] & 0xFF;
        if (b != (low & 0xFF) && b != (high & 0xFF)) {
          continue;
        }
        if (Ascii.regionMatchesIgnoreCase(bytes, offset + p, prefix, prefixLen)) {
          return p;
        }
        verificationWork += prefixLen;
        if (WorkLimit.isExhausted(verificationWork, workLimit)) {
          return VectorScanProvider.UNSUPPORTED;
        }
      }
      return -1;
    }

    ByteVector lowVec = ByteVector.broadcast(SPECIES, low);
    ByteVector highVec = ByteVector.broadcast(SPECIES, high);

    for (; pos <= limit; pos += vectorLen) {
      ByteVector inputVec = ByteVector.fromArray(SPECIES, bytes, offset + pos);
      VectorMask<Byte> matchMask = inputVec.compare(EQ, lowVec).or(inputVec.compare(EQ, highVec));

      if (matchMask.anyTrue()) {
        long activeLanes = matchMask.toLong();
        while (activeLanes != 0) {
          int bit = Long.numberOfTrailingZeros(activeLanes);
          int candidatePos = pos + bit - anchorOffset;
          if (WorkLimit.candidateInBounds(candidatePos, start, length, prefixLen)
              && Ascii.regionMatchesIgnoreCase(bytes, offset + candidatePos, prefix, prefixLen)) {
            return candidatePos;
          }
          if (WorkLimit.candidateInBounds(candidatePos, start, length, prefixLen)) {
            verificationWork += prefixLen;
            if (WorkLimit.isExhausted(verificationWork, workLimit)) {
              return VectorScanProvider.UNSUPPORTED;
            }
          }
          activeLanes &= activeLanes - 1;
        }
      }
    }

    int limitScalar = length - prefixLen;
    for (int p = Math.max(start, pos - anchorOffset); p <= limitScalar; p++) {
      int b = bytes[offset + p + anchorOffset] & 0xFF;
      if (b != (low & 0xFF) && b != (high & 0xFF)) {
        continue;
      }
      if (Ascii.regionMatchesIgnoreCase(bytes, offset + p, prefix, prefixLen)) {
        return p;
      }
      verificationWork += prefixLen;
      if (WorkLimit.isExhausted(verificationWork, workLimit)) {
        return VectorScanProvider.UNSUPPORTED;
      }
    }
    return -1;
  }

  private ByteVectorScan() {}
}
