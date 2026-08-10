// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/** Stateless 1-byte SIMD scanning kernels over {@link MemorySegment}. */
public final class SegmentByteVectorScan {

  public static final int UNSUPPORTED = -2;
  private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;

  private SegmentByteVectorScan() {}

  public static int minimumInputLength() {
    return SPECIES.length();
  }

  public static int indexOfAsciiClass(
      MemorySegment segment, long offset, long length, int[] ranges, int start) {
    int numRanges = ranges.length / 2;
    if (numRanges < 1 || numRanges > 4 || (ranges.length & 1) != 0) {
      return UNSUPPORTED;
    }
    int vectorLen = SPECIES.length();

    byte low1 = (byte) ranges[0];
    byte high1 = (byte) ranges[1];
    ByteVector vLow1 = ByteVector.broadcast(SPECIES, low1);
    ByteVector vHigh1 = ByteVector.broadcast(SPECIES, high1);

    boolean hasSecondRange = ranges.length >= 4;
    ByteVector vLow2 = hasSecondRange ? ByteVector.broadcast(SPECIES, (byte) ranges[2]) : null;
    ByteVector vHigh2 = hasSecondRange ? ByteVector.broadcast(SPECIES, (byte) ranges[3]) : null;

    long pos = Math.max(0, start);
    long loopBound = length - vectorLen;

    while (pos <= loopBound) {
      ByteVector v =
          ByteVector.fromMemorySegment(
              SPECIES, segment, offset + pos, java.nio.ByteOrder.nativeOrder());
      VectorMask<Byte> mask =
          v.compare(VectorOperators.GE, vLow1).and(v.compare(VectorOperators.LE, vHigh1));

      if (hasSecondRange) {
        mask =
            mask.or(
                v.compare(VectorOperators.GE, vLow2).and(v.compare(VectorOperators.LE, vHigh2)));
      }

      int firstTrue = mask.firstTrue();
      if (firstTrue < vectorLen) {
        return (int) (pos + firstTrue);
      }
      pos += vectorLen;
    }

    // Scalar tail
    for (long i = pos; i < length; i++) {
      int b = segment.get(ValueLayout.JAVA_BYTE, offset + i) & 0xFF;
      for (int r = 0; r < numRanges; r++) {
        if (b >= ranges[r * 2] && b <= ranges[r * 2 + 1]) {
          return (int) i;
        }
      }
    }

    return -1;
  }

  public static int indexOfIgnoreCase(
      MemorySegment segment, long offset, long length, String prefix, int start) {
    int prefixLen = prefix.length();
    for (int i = 0; i < prefixLen; i++) {
      if (prefix.charAt(i) > 127) {
        return UNSUPPORTED;
      }
    }

    int vectorLen = SPECIES.length();

    char first = prefix.charAt(0);
    byte low = (byte) asciiLower(first);
    byte high = (byte) asciiUpper(first);

    ByteVector vLow = ByteVector.broadcast(SPECIES, low);
    ByteVector vHigh = ByteVector.broadcast(SPECIES, high);

    long pos = Math.max(0, start);
    long loopBound = length - vectorLen;

    while (pos <= loopBound) {
      ByteVector v =
          ByteVector.fromMemorySegment(
              SPECIES, segment, offset + pos, java.nio.ByteOrder.nativeOrder());
      VectorMask<Byte> mask =
          v.compare(VectorOperators.EQ, vLow).or(v.compare(VectorOperators.EQ, vHigh));

      if (mask.anyTrue()) {
        long activeLanes = mask.toLong();
        while (activeLanes != 0) {
          int bit = Long.numberOfTrailingZeros(activeLanes);
          long cand = pos + bit;
          if (cand + prefixLen <= length
              && regionMatchesAsciiIgnoreCase(segment, offset + cand, prefix, prefixLen)) {
            return (int) cand;
          }
          activeLanes &= activeLanes - 1;
        }
      }
      pos += vectorLen;
    }

    for (long i = pos; i <= length - prefixLen; i++) {
      if (regionMatchesAsciiIgnoreCase(segment, offset + i, prefix, prefixLen)) {
        return (int) i;
      }
    }
    return -1;
  }

  private static boolean regionMatchesAsciiIgnoreCase(
      MemorySegment segment, long offset, String prefix, int len) {
    for (int i = 0; i < len; i++) {
      char c1 = (char) (segment.get(ValueLayout.JAVA_BYTE, offset + i) & 0xFF);
      char c2 = prefix.charAt(i);
      if (c1 != c2 && asciiLower(c1) != asciiLower(c2)) {
        return false;
      }
    }
    return true;
  }

  private static char asciiLower(char ch) {
    return ch >= 'A' && ch <= 'Z' ? (char) (ch + 32) : ch;
  }

  private static char asciiUpper(char ch) {
    return ch >= 'a' && ch <= 'z' ? (char) (ch - 32) : ch;
  }
}
