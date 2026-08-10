// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.foreign;

import static org.safere.internal.Ascii.toLowerCase;
import static org.safere.internal.Ascii.toUpperCase;
import static org.safere.internal.Swar.UNSUPPORTED;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/** Stateless 2-byte UTF-16 SIMD scanning kernels over {@link MemorySegment}. */
public final class SegmentShortVectorScan {

  private static final VectorSpecies<Short> SPECIES = ShortVector.SPECIES_PREFERRED;
  private static final ByteOrder NATIVE_ORDER = ByteOrder.nativeOrder();

  private SegmentShortVectorScan() {}

  public static int minimumInputLength() {
    return SPECIES.length();
  }

  public static int indexOfCharClassUtf16(
      MemorySegment segment, long byteOffset, int charLength, int[] ranges, int start) {
    int numRanges = ranges.length / 2;
    if (numRanges < 1 || numRanges > 4 || (ranges.length & 1) != 0) {
      return UNSUPPORTED;
    }
    int vectorLen = SPECIES.length();

    short low1 = (short) ranges[0];
    short high1 = (short) ranges[1];
    ShortVector vLow1 = ShortVector.broadcast(SPECIES, low1);
    ShortVector vHigh1 = ShortVector.broadcast(SPECIES, high1);

    boolean hasSecondRange = ranges.length >= 4;
    ShortVector vLow2 = hasSecondRange ? ShortVector.broadcast(SPECIES, (short) ranges[2]) : null;
    ShortVector vHigh2 = hasSecondRange ? ShortVector.broadcast(SPECIES, (short) ranges[3]) : null;

    int pos = Math.max(0, start);
    int loopBound = charLength - vectorLen;

    while (pos <= loopBound) {
      long addr = byteOffset + ((long) pos << 1);
      ShortVector v = ShortVector.fromMemorySegment(SPECIES, segment, addr, NATIVE_ORDER);
      VectorMask<Short> mask =
          v.compare(VectorOperators.GE, vLow1).and(v.compare(VectorOperators.LE, vHigh1));

      if (hasSecondRange) {
        mask =
            mask.or(
                v.compare(VectorOperators.GE, vLow2).and(v.compare(VectorOperators.LE, vHigh2)));
      }

      int firstTrue = mask.firstTrue();
      if (firstTrue < vectorLen) {
        int matchPos = pos + firstTrue;
        char matchedChar =
            (char)
                (segment.get(ValueLayout.JAVA_SHORT_UNALIGNED, byteOffset + ((long) matchPos << 1))
                    & 0xFFFF);
        if (Character.isLowSurrogate(matchedChar) && matchPos > 0) {
          char prevChar =
              (char)
                  (segment.get(
                          ValueLayout.JAVA_SHORT_UNALIGNED,
                          byteOffset + ((long) (matchPos - 1) << 1))
                      & 0xFFFF);
          if (Character.isHighSurrogate(prevChar)) {
            pos = matchPos + 1;
            continue;
          }
        }
        return matchPos;
      }
      pos += vectorLen;
    }

    // Scalar tail
    for (int i = pos; i < charLength; i++) {
      int c = segment.get(ValueLayout.JAVA_SHORT_UNALIGNED, byteOffset + ((long) i << 1)) & 0xFFFF;
      for (int r = 0; r < numRanges; r++) {
        if (c >= (ranges[r * 2] & 0xFFFF) && c <= (ranges[r * 2 + 1] & 0xFFFF)) {
          if (Character.isLowSurrogate((char) c) && i > 0) {
            char prev =
                (char)
                    (segment.get(
                            ValueLayout.JAVA_SHORT_UNALIGNED, byteOffset + ((long) (i - 1) << 1))
                        & 0xFFFF);
            if (Character.isHighSurrogate(prev)) {
              continue;
            }
          }
          return i;
        }
      }
    }

    return -1;
  }

  public static int indexOfIgnoreCaseUtf16(
      MemorySegment segment, long byteOffset, int charLength, String prefix, int start) {
    int prefixLen = prefix.length();
    for (int i = 0; i < prefixLen; i++) {
      if (prefix.charAt(i) > 127) {
        return UNSUPPORTED;
      }
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
      ShortVector v = ShortVector.fromMemorySegment(SPECIES, segment, addr, NATIVE_ORDER);
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
          (char)
              (segment.get(
                      ValueLayout.JAVA_SHORT_UNALIGNED, byteOffset + ((long) (charIndex + i) << 1))
                  & 0xFFFF);
      char c2 = prefix.charAt(i);
      if (c1 != c2 && toLowerCase(c1) != toLowerCase(c2)) {
        return false;
      }
    }
    return true;
  }
}
