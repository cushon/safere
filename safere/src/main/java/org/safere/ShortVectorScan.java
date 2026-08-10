// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static jdk.incubator.vector.VectorOperators.GE;
import static jdk.incubator.vector.VectorOperators.LE;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Stateless SIMD kernels using the incubating Vector API for 2-byte sequences (UTF-16 and char[]).
 */
public final class ShortVectorScan {
  private static final VectorSpecies<Short> SPECIES = ShortVector.SPECIES_PREFERRED;
  private static final VectorSpecies<Byte> BYTE_SPECIES = SPECIES.withLanes(byte.class);

  private static final ShortVector SURROGATE_LOW = ShortVector.broadcast(SPECIES, (short) 0xD800);
  private static final ShortVector SURROGATE_HIGH = ShortVector.broadcast(SPECIES, (short) 0xDFFF);

  public static int indexOfCharClass(
      char[] chars, int offset, int length, int[] ranges, int start) {
    if (ranges.length < 2 || ranges.length > 8 || (ranges.length & 1) != 0) {
      return VectorScanProvider.UNSUPPORTED;
    }
    boolean checkSurrogates = overlapsSurrogates(ranges);
    int position = Math.max(0, start);
    int vectorLen = SPECIES.length();
    int limit = length - vectorLen;

    for (; position <= limit; position += vectorLen) {
      ShortVector values = ShortVector.fromCharArray(SPECIES, chars, offset + position);
      if (checkSurrogates && hasSurrogates(values)) {
        return VectorScanProvider.UNSUPPORTED;
      }
      VectorMask<Short> matches = matches(values, ranges);
      if (matches.anyTrue()) {
        return position + matches.firstTrue();
      }
    }

    for (; position < length; position++) {
      char ch = chars[offset + position];
      if (checkSurrogates && Character.isSurrogate(ch)) {
        return VectorScanProvider.UNSUPPORTED;
      }
      if (matches(ch, ranges)) {
        return position;
      }
    }
    return -1;
  }

  public static int indexOfCharClassUtf16(
      byte[] bytes, int offset, int length, int[] ranges, int start) {
    if (ranges.length < 2 || ranges.length > 8 || (ranges.length & 1) != 0) {
      return VectorScanProvider.UNSUPPORTED;
    }
    boolean checkSurrogates = overlapsSurrogates(ranges);
    int position = Math.max(0, start);
    int vectorLen = SPECIES.length();
    int limit = length - vectorLen;

    for (; position <= limit; position += vectorLen) {
      ShortVector values =
          ByteVector.fromArray(BYTE_SPECIES, bytes, offset + (position << 1)).reinterpretAsShorts();
      if (checkSurrogates && hasSurrogates(values)) {
        return VectorScanProvider.UNSUPPORTED;
      }
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
      if (checkSurrogates && Character.isSurrogate(ch)) {
        return VectorScanProvider.UNSUPPORTED;
      }
      if (matches(ch, ranges)) {
        return position;
      }
    }
    return -1;
  }

  public static int indexOfIgnoreCase(
      char[] chars, int offset, int length, String prefix, int start) {
    int prefixLen = prefix.length();
    for (int i = 0; i < prefixLen; i++) {
      if (prefix.charAt(i) > 127) {
        return VectorScanProvider.UNSUPPORTED;
      }
    }

    int pos = Math.max(0, start);
    int vectorLen = SPECIES.length();
    int limit = length - vectorLen;

    char first = prefix.charAt(0);
    short low = (short) VectorScanProvider.asciiLower(first);
    short high = (short) VectorScanProvider.asciiUpper(first);
    ShortVector lowVec = ShortVector.broadcast(SPECIES, low);
    ShortVector highVec = ShortVector.broadcast(SPECIES, high);

    for (; pos <= limit; pos += vectorLen) {
      ShortVector inputVec = ShortVector.fromCharArray(SPECIES, chars, offset + pos);
      VectorMask<Short> matchMask =
          inputVec
              .compare(VectorOperators.EQ, lowVec)
              .or(inputVec.compare(VectorOperators.EQ, highVec));

      if (matchMask.anyTrue()) {
        long activeLanes = matchMask.toLong();
        while (activeLanes != 0) {
          int bit = Long.numberOfTrailingZeros(activeLanes);
          int candidatePos = pos + bit;
          if (candidatePos + prefixLen <= length
              && regionMatchesAsciiIgnoreCase(chars, offset + candidatePos, prefix, prefixLen)) {
            return candidatePos;
          }
          activeLanes &= activeLanes - 1;
        }
      }
    }

    int limitScalar = length - prefixLen;
    for (; pos <= limitScalar; pos++) {
      if (regionMatchesAsciiIgnoreCase(chars, offset + pos, prefix, prefixLen)) {
        return pos;
      }
    }
    return -1;
  }

  public static int indexOfIgnoreCaseUtf16(
      byte[] bytes, int offset, int length, String prefix, int start) {
    int prefixLen = prefix.length();
    for (int i = 0; i < prefixLen; i++) {
      if (prefix.charAt(i) > 127) {
        return VectorScanProvider.UNSUPPORTED;
      }
    }

    int pos = Math.max(0, start);
    int vectorLen = SPECIES.length();
    int limit = length - vectorLen;

    char first = prefix.charAt(0);
    short low = (short) VectorScanProvider.asciiLower(first);
    short high = (short) VectorScanProvider.asciiUpper(first);
    ShortVector lowVec = ShortVector.broadcast(SPECIES, low);
    ShortVector highVec = ShortVector.broadcast(SPECIES, high);

    for (; pos <= limit; pos += vectorLen) {
      ShortVector inputVec =
          ByteVector.fromArray(BYTE_SPECIES, bytes, offset + (pos << 1)).reinterpretAsShorts();
      VectorMask<Short> matchMask =
          inputVec
              .compare(VectorOperators.EQ, lowVec)
              .or(inputVec.compare(VectorOperators.EQ, highVec));

      if (matchMask.anyTrue()) {
        long activeLanes = matchMask.toLong();
        while (activeLanes != 0) {
          int bit = Long.numberOfTrailingZeros(activeLanes);
          int candidatePos = pos + bit;
          if (candidatePos + prefixLen <= length
              && regionMatchesAsciiIgnoreCaseUtf16(
                  bytes, offset + (candidatePos << 1), prefix, prefixLen)) {
            return candidatePos;
          }
          activeLanes &= activeLanes - 1;
        }
      }
    }

    int limitScalar = length - prefixLen;
    for (; pos <= limitScalar; pos++) {
      if (regionMatchesAsciiIgnoreCaseUtf16(bytes, offset + (pos << 1), prefix, prefixLen)) {
        return pos;
      }
    }
    return -1;
  }

  private static boolean overlapsSurrogates(int[] ranges) {
    for (int i = 0; i < ranges.length; i += 2) {
      int low = ranges[i];
      int high = ranges[i + 1];
      if (low <= 0xDFFF && high >= 0xD800) {
        return false;
      }
    }
    return true;
  }

  private static boolean hasSurrogates(ShortVector values) {
    return values.compare(GE, SURROGATE_LOW).and(values.compare(LE, SURROGATE_HIGH)).anyTrue();
  }

  private static VectorMask<Short> matches(ShortVector values, int[] ranges) {
    VectorMask<Short> matches = matches(values, ranges[0], ranges[1]);
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

  private static VectorMask<Short> matches(ShortVector values, int lowBound, int highBound) {
    short low = (short) lowBound;
    short high = (short) highBound;
    if (low == high) {
      return values.eq(low);
    }
    if (high == low + 1) {
      return values.eq(low).or(values.eq(high));
    }
    return values.compare(GE, low).and(values.compare(LE, high));
  }

  private static boolean matches(char value, int[] ranges) {
    for (int index = 0; index < ranges.length; index += 2) {
      if (value >= ranges[index] && value <= ranges[index + 1]) {
        return true;
      }
    }
    return false;
  }

  private static boolean regionMatchesAsciiIgnoreCase(
      char[] chars, int offset, String prefix, int prefixLen) {
    for (int i = 0; i < prefixLen; i++) {
      char c = chars[offset + i];
      char p = prefix.charAt(i);
      if (c != p && VectorScanProvider.asciiLower(c) != VectorScanProvider.asciiLower(p)) {
        return false;
      }
    }
    return true;
  }

  private static boolean regionMatchesAsciiIgnoreCaseUtf16(
      byte[] bytes, int byteOffset, String prefix, int prefixLen) {
    for (int i = 0; i < prefixLen; i++) {
      char c =
          (char)
              ((bytes[byteOffset + (i << 1)] & 0xFF)
                  | ((bytes[byteOffset + (i << 1) + 1] & 0xFF) << 8));
      char p = prefix.charAt(i);
      if (c != p && VectorScanProvider.asciiLower(c) != VectorScanProvider.asciiLower(p)) {
        return false;
      }
    }
    return true;
  }

  private ShortVectorScan() {}
}
