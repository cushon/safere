// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.safere.internal.Ascii.toLowerCase;
import static org.safere.internal.Ascii.toUpperCase;
import static org.safere.internal.Swar.UNSUPPORTED;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import org.safere.internal.Swar;

/** Stateless 2-byte UTF-16 SIMD scanning kernels over {@link MemorySegment}. */
final class SegmentShortVectorScan {

  private static final VectorSpecies<Short> SPECIES = ShortVector.SPECIES_PREFERRED;
  private static final ValueLayout.OfShort UTF16_SHORT =
      ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN);

  private SegmentShortVectorScan() {}

  static int minimumInputLength() {
    return SPECIES.length();
  }

  static int indexOfCharClassUtf16(
      MemorySegment segment, long byteOffset, int charLength, int[] ranges, int start) {
    if (!Swar.supportsBmpCodeUnitRanges(ranges, 4)) {
      return UNSUPPORTED;
    }
    int numRanges = ranges.length / 2;
    int vectorLen = SPECIES.length();

    int pos = Math.max(0, start);
    int loopBound = charLength - vectorLen;

    while (pos <= loopBound) {
      long addr = byteOffset + ((long) pos << 1);
      ShortVector v =
          ShortVector.fromMemorySegment(SPECIES, segment, addr, java.nio.ByteOrder.LITTLE_ENDIAN);
      VectorMask<Short> mask = matches(v, ranges);

      int firstTrue = mask.firstTrue();
      if (firstTrue < vectorLen) {
        return pos + firstTrue;
      }
      pos += vectorLen;
    }

    // Scalar tail
    for (int i = pos; i < charLength; i++) {
      int c = segment.get(UTF16_SHORT, byteOffset + ((long) i << 1)) & 0xFFFF;
      for (int r = 0; r < numRanges; r++) {
        if (c >= (ranges[r * 2] & 0xFFFF) && c <= (ranges[r * 2 + 1] & 0xFFFF)) {
          return i;
        }
      }
    }

    return -1;
  }

  static int indexOfIgnoreCaseUtf16(
      MemorySegment segment, long byteOffset, int charLength, String prefix, int start) {
    int prefixLen = prefix.length();
    if (prefixLen == 0) {
      return Math.min(Math.max(0, start), charLength);
    }
    for (int i = 0; i < prefixLen; i++) {
      if (prefix.charAt(i) > 127) {
        return UNSUPPORTED;
      }
    }
    if (prefixLen > 1) {
      return SegmentAsciiSearch.indexOfIgnoreCaseUtf16(
          segment, byteOffset, charLength, prefix, start);
    }

    int vectorLen = SPECIES.length();

    char first = prefix.charAt(0);
    short low = (short) toLowerCase(first);
    short high = (short) toUpperCase(first);

    ShortVector vLow = ShortVector.broadcast(SPECIES, low);
    ShortVector vHigh = ShortVector.broadcast(SPECIES, high);

    int pos = Math.max(0, start);
    int loopBound = charLength - vectorLen;

    while (pos <= loopBound) {
      long addr = byteOffset + ((long) pos << 1);
      ShortVector v =
          ShortVector.fromMemorySegment(SPECIES, segment, addr, java.nio.ByteOrder.LITTLE_ENDIAN);
      VectorMask<Short> mask =
          v.compare(VectorOperators.EQ, vLow).or(v.compare(VectorOperators.EQ, vHigh));

      if (mask.anyTrue()) {
        long activeLanes = mask.toLong();
        while (activeLanes != 0) {
          int bit = Long.numberOfTrailingZeros(activeLanes);
          int cand = pos + bit;
          if (cand + prefixLen <= charLength
              && regionMatchesAsciiIgnoreCaseUtf16(segment, byteOffset, cand, prefix, prefixLen)) {
            return cand;
          }
          activeLanes &= activeLanes - 1;
        }
      }
      pos += vectorLen;
    }

    for (int i = pos; i <= charLength - prefixLen; i++) {
      if (regionMatchesAsciiIgnoreCaseUtf16(segment, byteOffset, i, prefix, prefixLen)) {
        return i;
      }
    }
    return -1;
  }

  private static boolean regionMatchesAsciiIgnoreCaseUtf16(
      MemorySegment segment, long byteOffset, int charIndex, String prefix, int len) {
    for (int i = 0; i < len; i++) {
      char c1 =
          (char) (segment.get(UTF16_SHORT, byteOffset + ((long) (charIndex + i) << 1)) & 0xFFFF);
      char c2 = prefix.charAt(i);
      if (c1 != c2 && toLowerCase(c1) != toLowerCase(c2)) {
        return false;
      }
    }
    return true;
  }

  private static VectorMask<Short> matches(ShortVector values, int[] ranges) {
    VectorMask<Short> result = matches(values, ranges[0], ranges[1]);
    for (int i = 2; i < ranges.length; i += 2) {
      result = result.or(matches(values, ranges[i], ranges[i + 1]));
    }
    return result;
  }

  private static VectorMask<Short> matches(ShortVector values, int lowBound, int highBound) {
    short low = (short) lowBound;
    short high = (short) highBound;
    if (low == high) {
      return values.eq(low);
    }
    if (highBound == lowBound + 1) {
      return values.eq(low).or(values.eq(high));
    }
    if (highBound <= 0x7FFF || lowBound >= 0x8000) {
      return values.compare(VectorOperators.GE, low).and(values.compare(VectorOperators.LE, high));
    }
    ShortVector biasedValues = values.lanewise(VectorOperators.XOR, Short.MIN_VALUE);
    short biasedLow = (short) (low ^ Short.MIN_VALUE);
    short biasedHigh = (short) (high ^ Short.MIN_VALUE);
    return biasedValues
        .compare(VectorOperators.GE, biasedLow)
        .and(biasedValues.compare(VectorOperators.LE, biasedHigh));
  }
}
