// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.nativeOrder;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_16;
import static jdk.incubator.vector.VectorOperators.EQ;
import static org.safere.Ascii.regionMatchesIgnoreCase;
import static org.safere.Ascii.toLowerCase;
import static org.safere.Ascii.toUpperCase;

import java.util.Arrays;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorSpecies;

/**
 * Adapter implementing vector-accelerated scans over {@link String} instances via reflective
 * zero-copy byte array access.
 */
final class StringVectorScan {
  private static final VectorSpecies<Byte> BYTE_SPECIES = ByteVectorScan.SPECIES;
  private static final VectorSpecies<Short> SHORT_SPECIES = ShortVectorScan.SPECIES;

  static int indexOfAsciiClass(String text, int[] ranges, int start) {
    if (!StringSupport.hasAccess() || !Swar.supportsAsciiRanges(ranges, 4)) {
      return VectorScanProvider.UNSUPPORTED;
    }
    if (StringSupport.compatibleWith(text, ISO_8859_1)) {
      return indexOfLatin1Class(text, ranges, start, text.length());
    }
    if (StringSupport.compatibleWith(text, UTF_16)) {
      return indexOfUtf16Class(text, ranges, start, text.length());
    }
    return VectorScanProvider.UNSUPPORTED;
  }

  static int indexOfCharClass(String text, int[] ranges, int start) {
    return indexOfCharClass(text, ranges, start, text.length());
  }

  static int indexOfCharClass(String text, int[] ranges, int start, int limit) {
    if (!StringSupport.hasAccess()) {
      return VectorScanProvider.UNSUPPORTED;
    }
    if (StringSupport.compatibleWith(text, ISO_8859_1)) {
      int[] clamped = clampRangesForLatin1(ranges);
      if (clamped != null) {
        return indexOfLatin1Class(text, clamped, start, limit);
      }
      return VectorScanProvider.UNSUPPORTED;
    }
    if (StringSupport.compatibleWith(text, UTF_16)) {
      return indexOfUtf16Class(text, ranges, start, limit);
    }
    return VectorScanProvider.UNSUPPORTED;
  }

  static int indexOfIgnoreCase(String text, String prefix, int start) {
    if (!StringSupport.hasAccess()) {
      return VectorScanProvider.UNSUPPORTED;
    }
    if (StringSupport.compatibleWith(text, ISO_8859_1)) {
      return indexOfIgnoreCaseLatin1(text, prefix, start);
    }
    if (StringSupport.compatibleWith(text, UTF_16)) {
      return indexOfIgnoreCaseUtf16(text, prefix, start);
    }
    return VectorScanProvider.UNSUPPORTED;
  }

  static int indexOfAsciiPair(String text, int c1, int c2, int fromIndex, int limit) {
    if (!StringSupport.hasAccess()) {
      return -1;
    }
    if (StringSupport.compatibleWith(text, ISO_8859_1)) {
      return indexOfAsciiPairLatin1(text, c1, c2, fromIndex, limit);
    }
    if (StringSupport.compatibleWith(text, UTF_16) && nativeOrder() != BIG_ENDIAN) {
      return indexOfAsciiPairUtf16(text, c1, c2, fromIndex, limit);
    }
    return -1;
  }

  static int indexOfAsciiTriple(String text, int c1, int c2, int c3, int fromIndex, int limit) {
    if (!StringSupport.hasAccess()) {
      return -1;
    }
    if (StringSupport.compatibleWith(text, ISO_8859_1)) {
      return indexOfAsciiTripleLatin1(text, c1, c2, c3, fromIndex, limit);
    }
    if (StringSupport.compatibleWith(text, UTF_16) && nativeOrder() != BIG_ENDIAN) {
      return indexOfAsciiTripleUtf16(text, c1, c2, c3, fromIndex, limit);
    }
    return -1;
  }

  private static int indexOfAsciiPairLatin1(String text, int c1, int c2, int fromIndex, int limit) {
    int scanLimit = Math.min(limit, text.length());
    int pos = Math.max(0, fromIndex);
    int vectorLen = BYTE_SPECIES.length();
    int vecLimit = scanLimit - vectorLen;

    ByteVector v1 = ByteVector.broadcast(BYTE_SPECIES, (byte) c1);
    ByteVector v2 = ByteVector.broadcast(BYTE_SPECIES, (byte) c2);

    for (; pos <= vecLimit; pos += vectorLen) {
      ByteVector inputVec = StringSupport.byteVectorFromString(BYTE_SPECIES, text, pos);
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

  private static int indexOfAsciiPairUtf16(String text, int c1, int c2, int fromIndex, int limit) {
    int scanLimit = Math.min(limit, text.length());
    int pos = Math.max(0, fromIndex);
    int vectorLen = SHORT_SPECIES.length();
    int vecLimit = scanLimit - vectorLen;

    ShortVector v1 = ShortVector.broadcast(SHORT_SPECIES, (short) c1);
    ShortVector v2 = ShortVector.broadcast(SHORT_SPECIES, (short) c2);

    for (; pos <= vecLimit; pos += vectorLen) {
      ShortVector inputVec = StringSupport.shortVectorFromString(SHORT_SPECIES, text, pos);
      VectorMask<Short> matchMask = inputVec.compare(EQ, v1).or(inputVec.compare(EQ, v2));
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

  private static int indexOfAsciiTripleLatin1(
      String text, int c1, int c2, int c3, int fromIndex, int limit) {
    int scanLimit = Math.min(limit, text.length());
    int pos = Math.max(0, fromIndex);
    int vectorLen = BYTE_SPECIES.length();
    int vecLimit = scanLimit - vectorLen;

    ByteVector v1 = ByteVector.broadcast(BYTE_SPECIES, (byte) c1);
    ByteVector v2 = ByteVector.broadcast(BYTE_SPECIES, (byte) c2);
    ByteVector v3 = ByteVector.broadcast(BYTE_SPECIES, (byte) c3);

    for (; pos <= vecLimit; pos += vectorLen) {
      ByteVector inputVec = StringSupport.byteVectorFromString(BYTE_SPECIES, text, pos);
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

  private static int indexOfAsciiTripleUtf16(
      String text, int c1, int c2, int c3, int fromIndex, int limit) {
    int scanLimit = Math.min(limit, text.length());
    int pos = Math.max(0, fromIndex);
    int vectorLen = SHORT_SPECIES.length();
    int vecLimit = scanLimit - vectorLen;

    ShortVector v1 = ShortVector.broadcast(SHORT_SPECIES, (short) c1);
    ShortVector v2 = ShortVector.broadcast(SHORT_SPECIES, (short) c2);
    ShortVector v3 = ShortVector.broadcast(SHORT_SPECIES, (short) c3);

    for (; pos <= vecLimit; pos += vectorLen) {
      ShortVector inputVec = StringSupport.shortVectorFromString(SHORT_SPECIES, text, pos);
      VectorMask<Short> matchMask =
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

  private static int indexOfLatin1Class(String text, int[] ranges, int start, int limit) {
    int scanLimit = Math.min(limit, text.length());
    int position = Math.max(0, start);
    int vecLimit = scanLimit - BYTE_SPECIES.length();
    for (; position <= vecLimit; position += BYTE_SPECIES.length()) {
      ByteVector values = StringSupport.byteVectorFromString(BYTE_SPECIES, text, position);
      VectorMask<Byte> matches = ByteVectorScan.matches(values, ranges);
      if (matches.anyTrue()) {
        int found = position + matches.firstTrue();
        return found < scanLimit ? found : -1;
      }
    }
    for (; position < scanLimit; position++) {
      char c = text.charAt(position);
      if (c < 256 && ByteVectorScan.matches((byte) c, ranges)) {
        return position;
      }
    }
    return -1;
  }

  private static int indexOfUtf16Class(String text, int[] ranges, int start, int limit) {
    if (!Swar.supportsBmpCodeUnitRanges(ranges, 4) || nativeOrder() == BIG_ENDIAN) {
      return VectorScanProvider.UNSUPPORTED;
    }
    int scanLimit = Math.min(limit, text.length());
    int position = Math.max(0, start);
    int vectorLen = SHORT_SPECIES.length();
    int vecLimit = scanLimit - vectorLen;

    for (; position <= vecLimit; position += vectorLen) {
      ShortVector values = StringSupport.shortVectorFromString(SHORT_SPECIES, text, position);
      VectorMask<Short> matches = ShortVectorScan.matches(values, ranges);
      if (matches.anyTrue()) {
        int found = position + matches.firstTrue();
        return found < scanLimit ? found : -1;
      }
    }

    for (; position < scanLimit; position++) {
      char ch = text.charAt(position);
      if (ShortVectorScan.matches(ch, ranges)) {
        return position;
      }
    }
    return -1;
  }

  private static int indexOfIgnoreCaseLatin1(String text, String prefix, int start) {
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
    int vectorLen = BYTE_SPECIES.length();
    int limit = length - vectorLen;

    char first = prefix.charAt(0);
    byte low = (byte) toLowerCase(first);
    byte high = (byte) toUpperCase(first);
    ByteVector lowVec = ByteVector.broadcast(BYTE_SPECIES, low);
    ByteVector highVec = ByteVector.broadcast(BYTE_SPECIES, high);

    for (; pos <= limit; pos += vectorLen) {
      ByteVector inputVec = StringSupport.byteVectorFromString(BYTE_SPECIES, text, pos);
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

  private static int indexOfIgnoreCaseUtf16(String text, String prefix, int start) {
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
    if (prefixLen > 1) {
      return VectorScanProvider.UNSUPPORTED;
    }
    if (nativeOrder() == BIG_ENDIAN) {
      return VectorScanProvider.UNSUPPORTED;
    }

    int pos = Math.max(0, start);
    int vectorLen = SHORT_SPECIES.length();
    int limit = length - vectorLen;

    char first = prefix.charAt(0);
    short low = (short) toLowerCase(first);
    short high = (short) toUpperCase(first);
    ShortVector lowVec = ShortVector.broadcast(SHORT_SPECIES, low);
    ShortVector highVec = ShortVector.broadcast(SHORT_SPECIES, high);

    for (; pos <= limit; pos += vectorLen) {
      ShortVector inputVec = StringSupport.shortVectorFromString(SHORT_SPECIES, text, pos);
      VectorMask<Short> matchMask = inputVec.compare(EQ, lowVec).or(inputVec.compare(EQ, highVec));

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

  private static int[] clampRangesForLatin1(int[] ranges) {
    int numRanges = ranges.length / 2;
    int[] clamped = new int[ranges.length];
    int writeIdx = 0;
    for (int r = 0; r < numRanges; r++) {
      int low = ranges[r * 2];
      int high = ranges[r * 2 + 1];
      if (low > 255) {
        continue;
      }
      int clampedHigh = Math.min(high, 255);
      if (low <= clampedHigh) {
        clamped[writeIdx++] = low;
        clamped[writeIdx++] = clampedHigh;
      }
    }
    if (writeIdx == 0) {
      return null;
    }
    if (writeIdx < ranges.length) {
      return Arrays.copyOf(clamped, writeIdx);
    }
    return clamped;
  }

  private StringVectorScan() {}
}
