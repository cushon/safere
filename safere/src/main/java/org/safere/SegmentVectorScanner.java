// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Zero-copy SIMD implementation of VectorScanProvider for String operations. Routes Latin-1 strings
 * to nested {@link Latin1} (ByteVector) and UTF-16 strings to nested {@link Utf16} (ShortVector).
 */
final class SegmentVectorScanner implements VectorScanProvider {
  private static final int MINIMUM_INPUT_LENGTH = 64;
  private static final int SCALAR_PROLOGUE_LENGTH = 4;

  private final VectorScanProvider byteDelegate;

  SegmentVectorScanner(VectorScanProvider byteDelegate) {
    this.byteDelegate = byteDelegate;
  }

  @Override
  public int minimumInputLength() {
    return MINIMUM_INPUT_LENGTH;
  }

  @Override
  public int indexOfAsciiClass(byte[] bytes, int offset, int length, int[] ranges, int start) {
    return byteDelegate.indexOfAsciiClass(bytes, offset, length, ranges, start);
  }

  @Override
  public int indexOfCharClass(String text, Pattern.CharClassScanInfo scanInfo, int start) {
    int textLen = text.length();
    int remaining = textLen - start;

    if (remaining < MINIMUM_INPUT_LENGTH) {
      return -2;
    }

    int numRanges = scanInfo.ranges.length / 2;
    if (numRanges > 4) {
      return -2;
    }

    // Scalar prologue to avoid vector initialization cost on early matches
    int scalarLimit = Math.min(textLen, start + SCALAR_PROLOGUE_LENGTH);
    for (int p = start; p < scalarLimit; p++) {
      char ch = text.charAt(p);
      for (int r = 0; r < numRanges; r++) {
        if (ch >= scanInfo.ranges[r * 2] && ch <= scanInfo.ranges[r * 2 + 1]) {
          return p;
        }
      }
    }

    SegmentAndCharset sac = StringSegmentSupport.stringAsSegment(text);

    if (sac.charset().equals(StandardCharsets.ISO_8859_1)) {
      if (scanInfo.isAscii) {
        return Latin1.indexOfCharClass(
            sac.segment(), text, scanInfo.ranges, scalarLimit, numRanges);
      } else {
        int[] clampedRanges = clampRangesForLatin1(scanInfo.ranges);
        if (clampedRanges != null) {
          int clampedNumRanges = clampedRanges.length / 2;
          if (clampedNumRanges > 0 && clampedNumRanges <= 4) {
            return Latin1.indexOfCharClass(
                sac.segment(), text, clampedRanges, scalarLimit, clampedNumRanges);
          }
        }
      }
    }

    return Utf16.indexOfCharClass(sac, text, scanInfo.ranges, scalarLimit, numRanges, false);
  }

  @Override
  public int indexOfCodePointClass(
      String text, int[] ranges, long bitmap0, long bitmap1, int start) {
    int textLen = text.length();
    int remaining = textLen - start;

    if (remaining < MINIMUM_INPUT_LENGTH) {
      return -2;
    }

    SegmentAndCharset sac = StringSegmentSupport.stringAsSegment(text);
    int[] activeRanges = ranges;
    int numRanges;

    if (sac.charset().equals(StandardCharsets.ISO_8859_1)) {
      activeRanges = clampRangesForLatin1(ranges);
      if (activeRanges == null) {
        return -1; // No ranges can match in Latin-1
      }
    }

    for (int r : activeRanges) {
      if (r >= 65536) {
        return -2;
      }
    }

    numRanges = activeRanges.length / 2;
    if (numRanges > 4) {
      return -2;
    }

    // Scalar prologue
    int scalarLimit = Math.min(textLen, start + SCALAR_PROLOGUE_LENGTH);
    for (int p = start; p < scalarLimit; p++) {
      char ch = text.charAt(p);
      for (int r = 0; r < numRanges; r++) {
        if (ch >= activeRanges[r * 2] && ch <= activeRanges[r * 2 + 1]) {
          return p;
        }
      }
    }

    if (sac.charset().equals(StandardCharsets.ISO_8859_1)) {
      return Latin1.indexOfCharClass(sac.segment(), text, activeRanges, scalarLimit, numRanges);
    }

    return Utf16.indexOfCharClass(sac, text, activeRanges, scalarLimit, numRanges, true);
  }

  private static int[] clampRangesForLatin1(int[] ranges) {
    int numRanges = ranges.length / 2;
    int[] clamped = new int[ranges.length];
    int writeIdx = 0;
    for (int r = 0; r < numRanges; r++) {
      int low = ranges[r * 2];
      int high = ranges[r * 2 + 1];
      if (low > 255) {
        // Entirely above Latin-1, skip this range
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

  @Override
  public int indexOfIgnoreCase(String text, String prefix, int start) {
    int prefixLen = prefix.length();
    if (prefixLen == 0) {
      return start;
    }
    SegmentAndCharset sac = StringSegmentSupport.stringAsSegment(text);
    if (!sac.charset().equals(StandardCharsets.ISO_8859_1)) {
      return Utf16.indexOfIgnoreCase(sac, text, prefix, start);
    }
    return Latin1.indexOfIgnoreCase(sac, text, prefix, start);
  }

  /** Nested Latin-1 SIMD routines using ByteVector. */
  static final class Latin1 {
    private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;

    static int indexOfCharClass(
        MemorySegment segment, String text, int[] ranges, int start, int numRanges) {
      int textLen = text.length();
      int pos = start;
      int vectorLen = SPECIES.length();
      int limit = textLen - vectorLen;

      ByteVector[] lowVecs = new ByteVector[numRanges];
      ByteVector[] highVecs = new ByteVector[numRanges];
      for (int r = 0; r < numRanges; r++) {
        byte low = (byte) ranges[r * 2];
        byte high = (byte) ranges[r * 2 + 1];
        lowVecs[r] = ByteVector.broadcast(SPECIES, low);
        highVecs[r] = ByteVector.broadcast(SPECIES, high);
      }

      if (numRanges == 1) {
        ByteVector low = lowVecs[0];
        ByteVector high = highVecs[0];
        for (; pos <= limit; pos += vectorLen) {
          ByteVector inputVec =
              ByteVector.fromMemorySegment(SPECIES, segment, (long) pos, ByteOrder.nativeOrder());
          VectorMask<Byte> matchMask =
              inputVec
                  .compare(VectorOperators.GE, low)
                  .and(inputVec.compare(VectorOperators.LE, high));
          if (matchMask.anyTrue()) {
            return pos + matchMask.firstTrue();
          }
        }
      } else {
        for (; pos <= limit; pos += vectorLen) {
          ByteVector inputVec =
              ByteVector.fromMemorySegment(SPECIES, segment, (long) pos, ByteOrder.nativeOrder());
          VectorMask<Byte> matchMask = SPECIES.maskAll(false);
          for (int r = 0; r < numRanges; r++) {
            VectorMask<Byte> rangeMask =
                inputVec
                    .compare(VectorOperators.GE, lowVecs[r])
                    .and(inputVec.compare(VectorOperators.LE, highVecs[r]));
            matchMask = matchMask.or(rangeMask);
          }

          if (matchMask.anyTrue()) {
            return pos + matchMask.firstTrue();
          }
        }
      }

      for (; pos < textLen; pos++) {
        char ch = text.charAt(pos);
        for (int r = 0; r < numRanges; r++) {
          if (ch >= ranges[r * 2] && ch <= ranges[r * 2 + 1]) {
            return pos;
          }
        }
      }

      return -1;
    }

    static int indexOfIgnoreCase(SegmentAndCharset sac, String text, String prefix, int start) {
      int prefixLen = prefix.length();
      // Check if the prefix has any non-ASCII characters. We only optimize ASCII prefixes.
      for (int i = 0; i < prefixLen; i++) {
        if (prefix.charAt(i) > 127) {
          return -2;
        }
      }

      int textLen = text.length();
      int pos = start;
      int vectorLen = SPECIES.length();
      int limit = textLen - vectorLen;

      char first = prefix.charAt(0);
      byte low = (byte) VectorScanProvider.asciiLower(first);
      byte high = (byte) VectorScanProvider.asciiUpper(first);
      ByteVector lowVec = ByteVector.broadcast(SPECIES, low);
      ByteVector highVec = ByteVector.broadcast(SPECIES, high);

      MemorySegment segment = sac.segment();

      for (; pos <= limit; pos += vectorLen) {
        ByteVector inputVec =
            ByteVector.fromMemorySegment(SPECIES, segment, (long) pos, ByteOrder.nativeOrder());
        VectorMask<Byte> matchMask =
            inputVec
                .compare(VectorOperators.EQ, lowVec)
                .or(inputVec.compare(VectorOperators.EQ, highVec));

        if (matchMask.anyTrue()) {
          long activeLanes = matchMask.toLong();
          while (activeLanes != 0) {
            int bit = Long.numberOfTrailingZeros(activeLanes);
            int candidatePos = pos + bit;
            // Verify match (note: we check boundary limits)
            if (candidatePos + prefixLen <= textLen
                && Matcher.regionMatchesAsciiIgnoreCase(text, candidatePos, prefix, 0, prefixLen)) {
              return candidatePos;
            }
            activeLanes &= activeLanes - 1; // Clear lowest set bit
          }
        }
      }

      // Scalar cleanup
      int limitScalar = textLen - prefixLen;
      for (; pos <= limitScalar; pos++) {
        if (Matcher.regionMatchesAsciiIgnoreCase(text, pos, prefix, 0, prefixLen)) {
          return pos;
        }
      }

      return -1;
    }

    private Latin1() {}
  }

  /**
   * Nested UTF-16 SIMD routines using ShortVector. Loaded lazily only when non-Latin1 strings are
   * encountered.
   */
  static final class Utf16 {
    private static final VectorSpecies<Short> SPECIES = ShortVector.SPECIES_PREFERRED;

    static int indexOfCharClass(
        SegmentAndCharset sac,
        String text,
        int[] ranges,
        int start,
        int numRanges,
        boolean checkSurrogates) {
      int textLen = text.length();
      int pos = start;
      int vectorLen = SPECIES.length();
      int limit = textLen - vectorLen;

      boolean activeSurrogateCheck = checkSurrogates;
      if (activeSurrogateCheck) {
        boolean overlaps = false;
        for (int r = 0; r < numRanges; r++) {
          int low = ranges[r * 2];
          int high = ranges[r * 2 + 1];
          if (low <= 0xDFFF && high >= 0xD800) {
            overlaps = true;
            break;
          }
        }
        if (!overlaps) {
          activeSurrogateCheck = false;
        }
      }

      ShortVector[] lowVecs = new ShortVector[numRanges];
      ShortVector[] highVecs = new ShortVector[numRanges];
      for (int r = 0; r < numRanges; r++) {
        short low = (short) ranges[r * 2];
        short high = (short) ranges[r * 2 + 1];
        lowVecs[r] = ShortVector.broadcast(SPECIES, low);
        highVecs[r] = ShortVector.broadcast(SPECIES, high);
      }

      ShortVector surrogateLow = ShortVector.broadcast(SPECIES, (short) 0xD800);
      ShortVector surrogateHigh = ShortVector.broadcast(SPECIES, (short) 0xDFFF);
      MemorySegment segment = sac.segment();

      if (numRanges == 1) {
        ShortVector low = lowVecs[0];
        ShortVector high = highVecs[0];
        for (; pos <= limit; pos += vectorLen) {
          ShortVector inputVec =
              ShortVector.fromMemorySegment(
                  SPECIES, segment, (long) pos << 1, ByteOrder.nativeOrder());
          if (activeSurrogateCheck) {
            VectorMask<Short> surrogateMask =
                inputVec
                    .compare(VectorOperators.GE, surrogateLow)
                    .and(inputVec.compare(VectorOperators.LE, surrogateHigh));
            if (surrogateMask.anyTrue()) {
              return -2;
            }
          }
          VectorMask<Short> matchMask =
              inputVec
                  .compare(VectorOperators.GE, low)
                  .and(inputVec.compare(VectorOperators.LE, high));
          if (matchMask.anyTrue()) {
            return pos + matchMask.firstTrue();
          }
        }
      } else {
        for (; pos <= limit; pos += vectorLen) {
          ShortVector inputVec =
              ShortVector.fromMemorySegment(
                  SPECIES, segment, (long) pos << 1, ByteOrder.nativeOrder());
          if (activeSurrogateCheck) {
            VectorMask<Short> surrogateMask =
                inputVec
                    .compare(VectorOperators.GE, surrogateLow)
                    .and(inputVec.compare(VectorOperators.LE, surrogateHigh));
            if (surrogateMask.anyTrue()) {
              return -2;
            }
          }
          VectorMask<Short> matchMask = SPECIES.maskAll(false);
          for (int r = 0; r < numRanges; r++) {
            VectorMask<Short> rangeMask =
                inputVec
                    .compare(VectorOperators.GE, lowVecs[r])
                    .and(inputVec.compare(VectorOperators.LE, highVecs[r]));
            matchMask = matchMask.or(rangeMask);
          }

          if (matchMask.anyTrue()) {
            return pos + matchMask.firstTrue();
          }
        }
      }

      for (; pos < textLen; pos++) {
        char ch = text.charAt(pos);
        if (checkSurrogates && Character.isSurrogate(ch)) {
          return -2;
        }
        for (int r = 0; r < numRanges; r++) {
          if (ch >= ranges[r * 2] && ch <= ranges[r * 2 + 1]) {
            return pos;
          }
        }
      }

      return -1;
    }

    static int indexOfIgnoreCase(SegmentAndCharset sac, String text, String prefix, int start) {
      int prefixLen = prefix.length();
      for (int i = 0; i < prefixLen; i++) {
        if (prefix.charAt(i) > 127) {
          return -2;
        }
      }

      int textLen = text.length();
      int pos = start;
      int vectorLen = SPECIES.length();
      int limit = textLen - vectorLen;

      char first = prefix.charAt(0);
      short low = (short) VectorScanProvider.asciiLower(first);
      short high = (short) VectorScanProvider.asciiUpper(first);
      ShortVector lowVec = ShortVector.broadcast(SPECIES, low);
      ShortVector highVec = ShortVector.broadcast(SPECIES, high);
      MemorySegment segment = sac.segment();

      for (; pos <= limit; pos += vectorLen) {
        ShortVector inputVec =
            ShortVector.fromMemorySegment(
                SPECIES, segment, (long) pos << 1, ByteOrder.nativeOrder());
        VectorMask<Short> matchMask =
            inputVec
                .compare(VectorOperators.EQ, lowVec)
                .or(inputVec.compare(VectorOperators.EQ, highVec));

        if (matchMask.anyTrue()) {
          long activeLanes = matchMask.toLong();
          while (activeLanes != 0) {
            int bit = Long.numberOfTrailingZeros(activeLanes);
            int candidatePos = pos + bit;
            if (candidatePos + prefixLen <= textLen
                && Matcher.regionMatchesAsciiIgnoreCase(text, candidatePos, prefix, 0, prefixLen)) {
              return candidatePos;
            }
            activeLanes &= activeLanes - 1;
          }
        }
      }

      int limitScalar = textLen - prefixLen;
      for (; pos <= limitScalar; pos++) {
        if (Matcher.regionMatchesAsciiIgnoreCase(text, pos, prefix, 0, prefixLen)) {
          return pos;
        }
      }

      return -1;
    }

    private Utf16() {}
  }
}
