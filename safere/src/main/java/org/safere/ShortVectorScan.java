// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.nativeOrder;
import static jdk.incubator.vector.VectorOperators.LE;
import static org.safere.Ascii.toLowerCase;
import static org.safere.Ascii.toUpperCase;
import static org.safere.Utf16.regionMatchesIgnoreCase;
import static org.safere.Utf16.regionMatchesIgnoreCaseUtf16;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Stateless SIMD kernels using the incubating Vector API for 2-byte sequences (UTF-16 and char[]).
 */
final class ShortVectorScan {
  private static final VectorSpecies<Short> SPECIES = ShortVector.SPECIES_PREFERRED;

  /** The most ranges {@link #matches(ShortVector, int[])} accepts. */
  static final int MAXIMUM_RANGES = 8;

  static int indexOfCharClass(char[] chars, int offset, int length, int[] ranges, int start) {
    return indexOfCharClass(SPECIES, chars, offset, length, ranges, start);
  }

  static int indexOfCharClass(
      VectorSpecies<Short> species, char[] chars, int offset, int length, int[] ranges, int start) {
    if (!Swar.supportsBmpCodeUnitRanges(ranges, 4)) {
      return VectorScanProvider.UNSUPPORTED;
    }
    int position = Math.max(0, start);
    int vectorLen = species.length();
    int limit = length - vectorLen;

    for (; position <= limit; position += vectorLen) {
      ShortVector values = ShortVector.fromCharArray(species, chars, offset + position);
      VectorMask<Short> matches = matches(values, ranges);
      if (matches.anyTrue()) {
        return position + matches.firstTrue();
      }
    }

    for (; position < length; position++) {
      char ch = chars[offset + position];
      if (matches(ch, ranges)) {
        return position;
      }
    }
    return -1;
  }

  static int indexOfCharClassUtf16(byte[] bytes, int offset, int length, int[] ranges, int start) {
    return indexOfCharClassUtf16(SPECIES, bytes, offset, length, ranges, start);
  }

  static int indexOfCharClassUtf16(
      VectorSpecies<Short> species, byte[] bytes, int offset, int length, int[] ranges, int start) {
    if (!Swar.supportsBmpCodeUnitRanges(ranges, 4) || nativeOrder() == BIG_ENDIAN) {
      return VectorScanProvider.UNSUPPORTED;
    }
    int position = Math.max(0, start);
    int vectorLen = species.length();
    int limit = length - vectorLen;
    VectorSpecies<Byte> byteSpecies = species.withLanes(byte.class);

    for (; position <= limit; position += vectorLen) {
      ShortVector values =
          ByteVector.fromArray(byteSpecies, bytes, offset + (position << 1)).reinterpretAsShorts();
      VectorMask<Short> matches = matches(values, ranges);
      if (matches.anyTrue()) {
        return position + matches.firstTrue();
      }
    }

    for (; position < length; position++) {
      char ch =
          (char)
              ((bytes[offset + (position << 1)] & 0xFF)
                  | ((bytes[offset + (position << 1) + 1] & 0xFF) << 8));
      if (matches(ch, ranges)) {
        return position;
      }
    }
    return -1;
  }

  static int indexOfIgnoreCase(char[] chars, int offset, int length, String prefix, int start) {
    return indexOfIgnoreCase(SPECIES, chars, offset, length, prefix, start);
  }

  static int indexOfIgnoreCase(
      VectorSpecies<Short> species,
      char[] chars,
      int offset,
      int length,
      String prefix,
      int start) {
    int prefixLen = prefix.length();
    if (prefixLen == 0) {
      return Math.min(Math.max(0, start), length);
    }
    for (int i = 0; i < prefixLen; i++) {
      if (prefix.charAt(i) > 127) {
        return VectorScanProvider.UNSUPPORTED;
      }
    }
    if (prefixLen > 1) {
      return Utf16.indexOfIgnoreCase(chars, offset, length, prefix, start);
    }

    int pos = Math.max(0, start);
    int vectorLen = species.length();
    int limit = length - vectorLen;

    char first = prefix.charAt(0);
    short low = (short) toLowerCase(first);
    short high = (short) toUpperCase(first);
    ShortVector lowVec = ShortVector.broadcast(species, low);
    ShortVector highVec = ShortVector.broadcast(species, high);

    for (; pos <= limit; pos += vectorLen) {
      ShortVector inputVec = ShortVector.fromCharArray(species, chars, offset + pos);
      VectorMask<Short> matchMask =
          inputVec
              .compare(VectorOperators.EQ, lowVec)
              .or(inputVec.compare(VectorOperators.EQ, highVec));

      if (matchMask.anyTrue()) {
        long activeLanes = matchMask.toLong();
        while (activeLanes != 0) {
          int bit = Long.numberOfTrailingZeros(activeLanes);
          int candidatePos = pos + bit;
          if (WorkLimit.candidateInBounds(candidatePos, start, length, prefixLen)
              && regionMatchesIgnoreCase(chars, offset + candidatePos, prefix, prefixLen)) {
            return candidatePos;
          }
          activeLanes &= activeLanes - 1;
        }
      }
    }

    int limitScalar = length - prefixLen;
    for (; pos <= limitScalar; pos++) {
      if (regionMatchesIgnoreCase(chars, offset + pos, prefix, prefixLen)) {
        return pos;
      }
    }
    return -1;
  }

  static int indexOfIgnoreCaseUtf16(
      byte[] bytes, int offset, int length, String prefix, int start) {
    return indexOfIgnoreCaseUtf16(SPECIES, bytes, offset, length, prefix, start);
  }

  static int indexOfIgnoreCaseUtf16(
      VectorSpecies<Short> species,
      byte[] bytes,
      int offset,
      int length,
      String prefix,
      int start) {
    int prefixLen = prefix.length();
    if (prefixLen == 0) {
      return Math.min(Math.max(0, start), length);
    }
    for (int i = 0; i < prefixLen; i++) {
      if (prefix.charAt(i) > 127) {
        return VectorScanProvider.UNSUPPORTED;
      }
    }
    if (prefixLen > 1) {
      return Utf16.indexOfIgnoreCaseUtf16(bytes, offset, length, prefix, start);
    }
    if (nativeOrder() == BIG_ENDIAN) {
      return VectorScanProvider.UNSUPPORTED;
    }

    int pos = Math.max(0, start);
    int vectorLen = species.length();
    int limit = length - vectorLen;
    VectorSpecies<Byte> byteSpecies = species.withLanes(byte.class);

    char first = prefix.charAt(0);
    short low = (short) toLowerCase(first);
    short high = (short) toUpperCase(first);
    ShortVector lowVec = ShortVector.broadcast(species, low);
    ShortVector highVec = ShortVector.broadcast(species, high);

    for (; pos <= limit; pos += vectorLen) {
      ShortVector inputVec =
          ByteVector.fromArray(byteSpecies, bytes, offset + (pos << 1)).reinterpretAsShorts();
      VectorMask<Short> matchMask =
          inputVec
              .compare(VectorOperators.EQ, lowVec)
              .or(inputVec.compare(VectorOperators.EQ, highVec));

      if (matchMask.anyTrue()) {
        long activeLanes = matchMask.toLong();
        while (activeLanes != 0) {
          int bit = Long.numberOfTrailingZeros(activeLanes);
          int candidatePos = pos + bit;
          if (WorkLimit.candidateInBounds(candidatePos, start, length, prefixLen)
              && regionMatchesIgnoreCaseUtf16(
                  bytes, offset + (candidatePos << 1), prefix, prefixLen)) {
            return candidatePos;
          }
          activeLanes &= activeLanes - 1;
        }
      }
    }

    int limitScalar = length - prefixLen;
    for (; pos <= limitScalar; pos++) {
      if (regionMatchesIgnoreCaseUtf16(bytes, offset + (pos << 1), prefix, prefixLen)) {
        return pos;
      }
    }
    return -1;
  }

  /**
   * Returns the lanes of {@code values} inside one of up to {@link #MAXIMUM_RANGES} BMP code unit
   * ranges.
   *
   * <p>The vector code must inline into the scan loop: a call that C2 does not inline boxes its
   * vector arguments and result. So the work is split into methods under C1's and C2's inlining
   * size limit ({@code MaxInlineSize}, 35 bytecodes), which inline everywhere and are never hot
   * enough to be compiled on their own into a body too big to inline later. Ranges are combined
   * without loops so that no mask flows through a loop phi.
   */
  static VectorMask<Short> matches(ShortVector values, int[] ranges) {
    VectorMask<Short> low = matchesUpTo4(values, ranges, 0);
    return ranges.length <= 8 ? low : low.or(matchesUpTo4(values, ranges, 8));
  }

  /** Matches the up to four ranges starting at {@code ranges[from]}. */
  private static VectorMask<Short> matchesUpTo4(ShortVector values, int[] ranges, int from) {
    VectorMask<Short> low = matchesUpTo2(values, ranges, from);
    return ranges.length <= from + 4 ? low : low.or(matchesUpTo2(values, ranges, from + 4));
  }

  /** Matches the one or two ranges starting at {@code ranges[from]}. */
  private static VectorMask<Short> matchesUpTo2(ShortVector values, int[] ranges, int from) {
    VectorMask<Short> low = inRange(values, ranges, from);
    return ranges.length <= from + 2 ? low : low.or(inRange(values, ranges, from + 2));
  }

  /**
   * Matches {@code [ranges[index], ranges[index + 1]]} with one subtraction and one comparison:
   * {@code low <= v <= high} exactly when {@code v - low}, as an unsigned 16-bit value, is at most
   * {@code high - low}. Flipping the sign bit of both sides turns that into a signed comparison,
   * and subtracting {@code low ^ 0x8000} computes {@code (v - low) ^ 0x8000}.
   */
  private static VectorMask<Short> inRange(ShortVector values, int[] ranges, int index) {
    int low = ranges[index];
    return values
        .sub((short) (low ^ 0x8000))
        .compare(LE, (short) ((ranges[index + 1] - low) ^ 0x8000));
  }

  static boolean matches(char value, int[] ranges) {
    for (int index = 0; index < ranges.length; index += 2) {
      if (value >= ranges[index] && value <= ranges[index + 1]) {
        return true;
      }
    }
    return false;
  }

  private ShortVectorScan() {}
}
