// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static jdk.incubator.vector.VectorOperators.GE;
import static jdk.incubator.vector.VectorOperators.LE;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorSpecies;

/**
 * Stateless SIMD kernels using the incubating Vector API for 1-byte sequences (UTF-8 and Latin-1).
 */
final class ByteVectorScan {
  private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;

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

  private static VectorMask<Byte> matches(ByteVector values, int[] ranges) {
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

  private static boolean matches(byte value, int[] ranges) {
    for (int index = 0; index < ranges.length; index += 2) {
      if (value >= (byte) ranges[index] && value <= (byte) ranges[index + 1]) {
        return true;
      }
    }
    return false;
  }

  private ByteVectorScan() {}
}
