// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static jdk.incubator.vector.VectorOperators.EQ;
import static jdk.incubator.vector.VectorOperators.GE;
import static jdk.incubator.vector.VectorOperators.LE;
import static org.safere.Ascii.regionMatchesIgnoreCase;
import static org.safere.Ascii.toLowerCase;
import static org.safere.Ascii.toUpperCase;

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

  public static int indexOfIgnoreCase(
      byte[] bytes, int offset, int length, String prefix, int start) {
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
      return VectorScanProvider.UNSUPPORTED;
    }
    char first = prefix.charAt(0);
    byte low = (byte) Ascii.toLowerCase(first);
    byte high = (byte) Ascii.toUpperCase(first);
    if (low == high) {
      int[] range = new int[] {low, low};
      return indexOfAsciiClass(bytes, offset, length, range, start);
    }
    int[] ranges = new int[] {high, high, low, low};
    return indexOfAsciiClass(bytes, offset, length, ranges, start);
  }

  static int indexOfAsciiClass(String text, int[] ranges, int start) {
    if (!Swar.supportsAsciiRanges(ranges, 4) || !StringSupport.compatibleWith(text, ISO_8859_1)) {
      return VectorScanProvider.UNSUPPORTED;
    }
    int length = text.length();
    int position = Math.max(0, start);
    int limit = position + SPECIES.loopBound(length - position);
    for (; position < limit; position += SPECIES.length()) {
      ByteVector values = StringSupport.byteVectorFromString(SPECIES, text, position);
      VectorMask<Byte> matches = matches(values, ranges);
      if (matches.anyTrue()) {
        return position + matches.firstTrue();
      }
    }
    for (; position < length; position++) {
      char c = text.charAt(position);
      if (c < 128 && matches((byte) c, ranges)) {
        return position;
      }
    }
    return -1;
  }

  static int indexOfIgnoreCase(String text, String prefix, int start) {
    if (!StringSupport.compatibleWith(text, ISO_8859_1)) {
      return VectorScanProvider.UNSUPPORTED;
    }
    int prefixLen = prefix.length();
    int length = text.length();
    if (prefixLen == 0) {
      return Math.min(Math.max(0, start), length);
    }
    for (int i = 0; i < prefixLen; i++) {
      if (prefix.charAt(i) > 127) {
        return VectorScanProvider.UNSUPPORTED;
      }
    }

    int pos = Math.max(0, start);
    int vectorLen = SPECIES.length();
    int limit = length - vectorLen;

    char first = prefix.charAt(0);
    byte low = (byte) Ascii.toLowerCase(first);
    byte high = (byte) Ascii.toUpperCase(first);
    ByteVector lowVec = ByteVector.broadcast(SPECIES, low);
    ByteVector highVec = ByteVector.broadcast(SPECIES, high);

    for (; pos <= limit; pos += vectorLen) {
      ByteVector inputVec = StringSupport.byteVectorFromString(SPECIES, text, pos);
      VectorMask<Byte> matchMask = inputVec.compare(EQ, lowVec).or(inputVec.compare(EQ, highVec));

      if (matchMask.anyTrue()) {
        long activeLanes = matchMask.toLong();
        while (activeLanes != 0) {
          int bit = Long.numberOfTrailingZeros(activeLanes);
          int candidatePos = pos + bit;
          if (candidatePos + prefixLen <= length
              && regionMatchesIgnoreCase(text, candidatePos, prefix, prefixLen)) {
            return candidatePos;
          }
          activeLanes &= activeLanes - 1;
        }
      }
    }

    int limitScalar = length - prefixLen;
    for (; pos <= limitScalar; pos++) {
      if (regionMatchesIgnoreCase(text, pos, prefix, prefixLen)) {
        return pos;
      }
    }
    return -1;
  }

  static int indexOfAsciiPair(String text, int c1, int c2, int fromIndex, int limit) {
    if (!StringSupport.compatibleWith(text, ISO_8859_1)) {
      return -1;
    }
    int scanLimit = Math.min(limit, text.length());
    int pos = Math.max(0, fromIndex);
    int vectorLen = SPECIES.length();
    int vecLimit = scanLimit - vectorLen;

    ByteVector v1 = ByteVector.broadcast(SPECIES, (byte) c1);
    ByteVector v2 = ByteVector.broadcast(SPECIES, (byte) c2);

    for (; pos <= vecLimit; pos += vectorLen) {
      ByteVector inputVec = StringSupport.byteVectorFromString(SPECIES, text, pos);
      VectorMask<Byte> matchMask = inputVec.compare(EQ, v1).or(inputVec.compare(EQ, v2));
      if (matchMask.anyTrue()) {
        int bit = matchMask.firstTrue();
        int found = pos + bit;
        return found < scanLimit ? found : -1;
      }
    }
    for (; pos < scanLimit; pos++) {
      char c = text.charAt(pos);
      if (c == c1 || c == c2) {
        return pos;
      }
    }
    return -1;
  }

  static int indexOfAsciiTriple(String text, int c1, int c2, int c3, int fromIndex, int limit) {
    if (!StringSupport.compatibleWith(text, ISO_8859_1)) {
      return -1;
    }
    int scanLimit = Math.min(limit, text.length());
    int pos = Math.max(0, fromIndex);
    int vectorLen = SPECIES.length();
    int vecLimit = scanLimit - vectorLen;

    ByteVector v1 = ByteVector.broadcast(SPECIES, (byte) c1);
    ByteVector v2 = ByteVector.broadcast(SPECIES, (byte) c2);
    ByteVector v3 = ByteVector.broadcast(SPECIES, (byte) c3);

    for (; pos <= vecLimit; pos += vectorLen) {
      ByteVector inputVec = StringSupport.byteVectorFromString(SPECIES, text, pos);
      VectorMask<Byte> matchMask =
          inputVec.compare(EQ, v1).or(inputVec.compare(EQ, v2)).or(inputVec.compare(EQ, v3));
      if (matchMask.anyTrue()) {
        int bit = matchMask.firstTrue();
        int found = pos + bit;
        return found < scanLimit ? found : -1;
      }
    }
    for (; pos < scanLimit; pos++) {
      char c = text.charAt(pos);
      if (c == c1 || c == c2 || c == c3) {
        return pos;
      }
    }
    return -1;
  }

  private ByteVectorScan() {}
}
