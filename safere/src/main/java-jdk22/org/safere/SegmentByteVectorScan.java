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
import java.nio.ByteOrder;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import org.safere.internal.Swar;

/** Stateless 1-byte SIMD scanning kernels over {@link MemorySegment}. */
final class SegmentByteVectorScan {

  private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;
  private static final ByteOrder NATIVE_ORDER = ByteOrder.nativeOrder();

  private SegmentByteVectorScan() {}

  public static int minimumInputLength() {
    return SPECIES.length();
  }

  public static int indexOfAsciiClass(
      MemorySegment segment, long offset, long length, int[] ranges, int start) {
    int numRanges = ranges.length / 2;
    if (length > Integer.MAX_VALUE || !Swar.supportsAsciiRanges(ranges, 4)) {
      return UNSUPPORTED;
    }
    int vectorLen = SPECIES.length();

    long pos = Math.max(0, start);
    long loopBound = length - vectorLen;

    while (pos <= loopBound) {
      ByteVector v = ByteVector.fromMemorySegment(SPECIES, segment, offset + pos, NATIVE_ORDER);
      VectorMask<Byte> mask = matches(v, ranges);

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
    if (length > Integer.MAX_VALUE) {
      return UNSUPPORTED;
    }
    int prefixLen = prefix.length();
    if (prefixLen == 0) {
      return Math.min(Math.max(0, start), (int) length);
    }
    for (int i = 0; i < prefixLen; i++) {
      if (prefix.charAt(i) > 127) {
        return UNSUPPORTED;
      }
    }
    if (prefixLen > 1) {
      return SegmentAsciiSearch.indexOfIgnoreCase(segment, offset, (int) length, prefix, start);
    }

    int vectorLen = SPECIES.length();

    char first = prefix.charAt(0);
    byte low = (byte) toLowerCase(first);
    byte high = (byte) toUpperCase(first);

    ByteVector vLow = ByteVector.broadcast(SPECIES, low);
    ByteVector vHigh = ByteVector.broadcast(SPECIES, high);

    long pos = Math.max(0, start);
    long loopBound = length - vectorLen;

    while (pos <= loopBound) {
      ByteVector v = ByteVector.fromMemorySegment(SPECIES, segment, offset + pos, NATIVE_ORDER);
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
      if (c1 != c2 && toLowerCase(c1) != toLowerCase(c2)) {
        return false;
      }
    }
    return true;
  }

  private static VectorMask<Byte> matches(ByteVector values, int[] ranges) {
    VectorMask<Byte> result = matches(values, ranges[0], ranges[1]);
    for (int i = 2; i < ranges.length; i += 2) {
      result = result.or(matches(values, ranges[i], ranges[i + 1]));
    }
    return result;
  }

  private static VectorMask<Byte> matches(ByteVector values, int low, int high) {
    return values
        .compare(VectorOperators.GE, (byte) low)
        .and(values.compare(VectorOperators.LE, (byte) high));
  }
}
