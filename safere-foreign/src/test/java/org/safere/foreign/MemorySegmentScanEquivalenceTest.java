// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.foreign;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.safere.ByteSwarScan;
import org.safere.ByteVectorScan;
import org.safere.ShortSwarScan;
import org.safere.ShortVectorScan;

/**
 * Differential equivalence test comparing {@link MemorySegment} vector and SWAR scan kernels in
 * {@code safere-foreign} against standard array kernels and scalar reference implementations.
 */
class MemorySegmentScanEquivalenceTest {

  private static final int[][] BYTE_RANGES_CASES = {
    {'a', 'z'},
    {'0', '9'},
    {'A', 'Z', 'a', 'z'},
    {'a', 'z', '0', '9'},
  };

  private static final int[][] UTF16_RANGES_CASES = {
    {'a', 'z'},
    {'0', '9'},
    {'A', 'Z', 'a', 'z'},
    {'\u0400', '\u04FF'}, // Cyrillic
    {'a', 'z', '\u0400', '\u04FF'},
  };

  private static final String[] TEST_PREFIXES = {
    "a", "abc", "HTTP", "Content-Type", "xyz", "hello-world"
  };

  private static boolean isVectorApiAvailable() {
    try {
      Class.forName("jdk.incubator.vector.ByteVector");
      return true;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 3, 7, 8, 15, 16, 31, 32, 63, 64, 127, 128, 512, 1024, 2048})
  @DisplayName("1-byte Latin-1 char class scan equivalence across Array, Heap-Segment, Off-Heap")
  void testLatin1CharClassEquivalence(int length) {
    Random random = new Random(42 + length);
    byte[] input = new byte[length];
    random.nextBytes(input);

    for (int i = 0; i < length; i++) {
      input[i] = (byte) (input[i] & 0x7F);
    }

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment heapSegment = MemorySegment.ofArray(input);
      MemorySegment offHeapSegment = arena.allocateFrom(ValueLayout.JAVA_BYTE, input);

      for (int[] ranges : BYTE_RANGES_CASES) {
        for (int start : new int[] {0, 1, 7, 15, 31, Math.max(0, length - 5)}) {
          if (start > length) continue;

          int expected = scalarIndexOfAsciiClass(input, ranges, start);

          // SWAR Kernels (Always available)
          int byteSwar =
              (ranges.length <= 4)
                  ? ByteSwarScan.indexOfAsciiClass(input, 0, length, ranges, start)
                  : expected;
          int segByteSwar =
              (ranges.length <= 4)
                  ? SegmentByteSwarScan.indexOfAsciiClass(heapSegment, 0, length, ranges, start)
                  : expected;
          int offHeapByteSwar =
              (ranges.length <= 4)
                  ? SegmentByteSwarScan.indexOfAsciiClass(offHeapSegment, 0, length, ranges, start)
                  : expected;

          assertThat(byteSwar)
              .as("ByteSwarScan at start=%d, len=%d", start, length)
              .isEqualTo(expected);
          assertThat(segByteSwar)
              .as("SegmentByteSwar (heap) at start=%d", start)
              .isEqualTo(expected);
          assertThat(offHeapByteSwar)
              .as("SegmentByteSwar (off-heap) at start=%d", start)
              .isEqualTo(expected);

          // Vector API Kernels (When enabled on runtime)
          if (isVectorApiAvailable()) {
            int byteVector = ByteVectorScan.indexOfAsciiClass(input, 0, length, ranges, start);
            int segByteVector =
                SegmentByteVectorScan.indexOfAsciiClass(heapSegment, 0, length, ranges, start);
            int offHeapByteVector =
                SegmentByteVectorScan.indexOfAsciiClass(offHeapSegment, 0, length, ranges, start);

            assertThat(byteVector)
                .as("ByteVectorScan at start=%d, len=%d", start, length)
                .isEqualTo(expected);
            assertThat(segByteVector)
                .as("SegmentByteVector (heap) at start=%d", start)
                .isEqualTo(expected);
            assertThat(offHeapByteVector)
                .as("SegmentByteVector (off-heap) at start=%d", start)
                .isEqualTo(expected);
          }
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 3, 7, 15, 16, 31, 32, 63, 64, 127, 128, 512, 1024, 2048})
  @DisplayName("2-byte UTF-16 char class scan equivalence across Array, Heap-Segment, Off-Heap")
  void testUtf16CharClassEquivalence(int charLength) {
    Random random = new Random(100 + charLength);
    char[] chars = new char[charLength];
    for (int i = 0; i < charLength; i++) {
      chars[i] = (char) (random.nextInt(26) + 'a');
    }
    if (charLength > 10) {
      chars[charLength / 2] = '\u0416';
    }

    String str = new String(chars);
    byte[] utf16Bytes = str.getBytes(UTF_16LE);

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment heapSegment = MemorySegment.ofArray(utf16Bytes);
      MemorySegment offHeapSegment = arena.allocateFrom(ValueLayout.JAVA_BYTE, utf16Bytes);

      for (int[] ranges : UTF16_RANGES_CASES) {
        for (int start : new int[] {0, 1, 7, 15, 31, Math.max(0, charLength - 5)}) {
          if (start > charLength) continue;

          int expected = scalarIndexOfCharClassUtf16(chars, ranges, start);

          // Array Kernels
          int charSwar = ShortSwarScan.indexOfCharClass(chars, 0, charLength, ranges, start);
          int utf16ByteSwar =
              ShortSwarScan.indexOfCharClassUtf16(utf16Bytes, 0, charLength, ranges, start);

          assertThat(charSwar).as("char[] SWAR at start=%d", start).isEqualTo(expected);
          assertThat(utf16ByteSwar).as("byte[] UTF-16 SWAR at start=%d", start).isEqualTo(expected);

          // MemorySegment SWAR Kernels
          int segSwarHeap =
              SegmentShortSwarScan.indexOfCharClassUtf16(heapSegment, 0, charLength, ranges, start);
          int segSwarOffHeap =
              SegmentShortSwarScan.indexOfCharClassUtf16(
                  offHeapSegment, 0, charLength, ranges, start);

          assertThat(segSwarHeap)
              .as("SegmentShortSwar (heap) at start=%d", start)
              .isEqualTo(expected);
          assertThat(segSwarOffHeap)
              .as("SegmentShortSwar (off-heap) at start=%d", start)
              .isEqualTo(expected);

          // Vector API Kernels
          if (isVectorApiAvailable()) {
            int charVector = ShortVectorScan.indexOfCharClass(chars, 0, charLength, ranges, start);
            int utf16ByteVector =
                ShortVectorScan.indexOfCharClassUtf16(utf16Bytes, 0, charLength, ranges, start);
            int segVecHeap =
                SegmentShortVectorScan.indexOfCharClassUtf16(
                    heapSegment, 0, charLength, ranges, start);
            int segVecOffHeap =
                SegmentShortVectorScan.indexOfCharClassUtf16(
                    offHeapSegment, 0, charLength, ranges, start);

            assertThat(charVector).as("char[] Vector at start=%d", start).isEqualTo(expected);
            assertThat(utf16ByteVector)
                .as("byte[] UTF-16 Vector at start=%d", start)
                .isEqualTo(expected);
            assertThat(segVecHeap)
                .as("SegmentShortVector (heap) at start=%d", start)
                .isEqualTo(expected);
            assertThat(segVecOffHeap)
                .as("SegmentShortVector (off-heap) at start=%d", start)
                .isEqualTo(expected);
          }
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"hello world", "HTTP/1.1 200 OK", "quick brown fox jumps", "FoObAr12345"})
  @DisplayName("IgnoreCase Prefix scan equivalence across Array and MemorySegment")
  void testIgnoreCaseEquivalence(String sample) {
    byte[] latin1 = sample.getBytes(ISO_8859_1);
    char[] chars = sample.toCharArray();
    byte[] utf16 = sample.getBytes(UTF_16LE);

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment latin1Seg = arena.allocateFrom(ValueLayout.JAVA_BYTE, latin1);
      MemorySegment utf16Seg = arena.allocateFrom(ValueLayout.JAVA_BYTE, utf16);

      for (String prefix : TEST_PREFIXES) {
        int expectedLatin1 = scalarIndexOfIgnoreCase(latin1, prefix, 0);
        int segByteSwar =
            SegmentByteSwarScan.indexOfIgnoreCase(latin1Seg, 0, latin1.length, prefix, 0);
        assertThat(segByteSwar).isEqualTo(expectedLatin1);

        if (isVectorApiAvailable()) {
          int segByteVec =
              SegmentByteVectorScan.indexOfIgnoreCase(latin1Seg, 0, latin1.length, prefix, 0);
          assertThat(segByteVec).isEqualTo(expectedLatin1);
        }

        int expectedUtf16 = scalarIndexOfIgnoreCaseUtf16(chars, prefix, 0);
        int segShortSwar =
            SegmentShortSwarScan.indexOfIgnoreCaseUtf16(utf16Seg, 0, chars.length, prefix, 0);
        assertThat(segShortSwar).isEqualTo(expectedUtf16);

        if (isVectorApiAvailable()) {
          int segShortVec =
              SegmentShortVectorScan.indexOfIgnoreCaseUtf16(utf16Seg, 0, chars.length, prefix, 0);
          assertThat(segShortVec).isEqualTo(expectedUtf16);
        }
      }
    }
  }

  @Test
  @DisplayName("MemorySegment vector kernels include every accepted range")
  void vectorKernelsIncludeAllAcceptedRanges() {
    byte[] bytes = new byte[128];
    java.util.Arrays.fill(bytes, (byte) 'x');
    bytes[10] = '7';
    int[] byteRanges = {'a', 'a', 'b', 'b', '0', '9', '_', '_'};

    char[] chars = new char[128];
    java.util.Arrays.fill(chars, 'x');
    chars[10] = '7';
    byte[] utf16 = new String(chars).getBytes(UTF_16LE);
    int[] shortRanges = {'a', 'a', 'b', 'b', '0', '9', '_', '_'};

    assertThat(
            SegmentByteVectorScan.indexOfAsciiClass(
                MemorySegment.ofArray(bytes), 0, bytes.length, byteRanges, 0))
        .isEqualTo(10);
    assertThat(
            SegmentShortVectorScan.indexOfCharClassUtf16(
                MemorySegment.ofArray(utf16), 0, chars.length, shortRanges, 0))
        .isEqualTo(10);
  }

  @Test
  @DisplayName("MemorySegment UTF-16 kernels handle range boundaries")
  void segmentUtf16RangeBoundaries() {
    char[] chars = new char[64];
    java.util.Arrays.fill(chars, '\uA000');
    chars[10] = '\u7500';
    MemorySegment utf16 = MemorySegment.ofArray(new String(chars).getBytes(UTF_16LE));
    int[] crossingSignedBoundary = {'\u7000', '\u9000'};

    assertThat(
            SegmentShortSwarScan.indexOfCharClassUtf16(
                utf16, 0, chars.length, crossingSignedBoundary, 0))
        .isEqualTo(10);
    assertThat(
            SegmentShortVectorScan.indexOfCharClassUtf16(
                utf16, 0, chars.length, crossingSignedBoundary, 0))
        .isEqualTo(10);

    int[] supplementaryRange = {0x1F600, 0x1F64F};
    assertThat(
            SegmentShortSwarScan.indexOfCharClassUtf16(
                utf16, 0, chars.length, supplementaryRange, 0))
        .isEqualTo(-2);
    assertThat(
            SegmentShortVectorScan.indexOfCharClassUtf16(
                utf16, 0, chars.length, supplementaryRange, 0))
        .isEqualTo(-2);
  }

  @Test
  @DisplayName("Byte MemorySegment kernels reject unrepresentable result positions")
  void byteSegmentKernelsRejectUnrepresentableLength() {
    MemorySegment segment = MemorySegment.ofArray(new byte[] {'a'});
    long unrepresentableLength = (long) Integer.MAX_VALUE + 1;

    assertThat(
            SegmentByteSwarScan.indexOfAsciiClass(
                segment, 0, unrepresentableLength, new int[] {'a', 'a'}, 0))
        .isEqualTo(-2);
    assertThat(
            SegmentByteVectorScan.indexOfAsciiClass(
                segment, 0, unrepresentableLength, new int[] {'a', 'a'}, 0))
        .isEqualTo(-2);
    assertThat(SegmentByteSwarScan.indexOfIgnoreCase(segment, 0, unrepresentableLength, "a", 0))
        .isEqualTo(-2);
    assertThat(SegmentByteVectorScan.indexOfIgnoreCase(segment, 0, unrepresentableLength, "a", 0))
        .isEqualTo(-2);
  }

  @Test
  @DisplayName("Public segment kernels handle empty prefixes and unsupported byte ranges")
  void publicSegmentKernelEdgeInputs() {
    MemorySegment bytes = MemorySegment.ofArray("abc".getBytes(ISO_8859_1));
    MemorySegment utf16 = MemorySegment.ofArray("abc".getBytes(UTF_16LE));

    assertThat(SegmentByteSwarScan.indexOfIgnoreCase(bytes, 0, 3, "", 1)).isEqualTo(1);
    assertThat(SegmentByteVectorScan.indexOfIgnoreCase(bytes, 0, 3, "", 10)).isEqualTo(3);
    assertThat(SegmentShortSwarScan.indexOfIgnoreCaseUtf16(utf16, 0, 3, "", -1)).isZero();
    assertThat(SegmentShortVectorScan.indexOfIgnoreCaseUtf16(utf16, 0, 3, "", 1)).isEqualTo(1);

    assertThat(SegmentByteSwarScan.indexOfAsciiClass(bytes, 0, 3, new int[] {233, 233}, 0))
        .isEqualTo(-2);
    assertThat(SegmentByteSwarScan.indexOfAsciiClass(bytes, 0, 3, new int[] {'z', 'a'}, 0))
        .isEqualTo(-2);
  }

  private static int scalarIndexOfAsciiClass(byte[] bytes, int[] ranges, int start) {
    for (int i = start; i < bytes.length; i++) {
      int b = bytes[i] & 0xFF;
      for (int r = 0; r < ranges.length; r += 2) {
        if (b >= ranges[r] && b <= ranges[r + 1]) {
          return i;
        }
      }
    }
    return -1;
  }

  private static int scalarIndexOfCharClassUtf16(char[] chars, int[] ranges, int start) {
    for (int i = start; i < chars.length; i++) {
      char c = chars[i];
      for (int r = 0; r < ranges.length; r += 2) {
        if (c >= ranges[r] && c <= ranges[r + 1]) {
          if (Character.isLowSurrogate(c) && i > 0 && Character.isHighSurrogate(chars[i - 1])) {
            continue;
          }
          return i;
        }
      }
    }
    return -1;
  }

  private static int scalarIndexOfIgnoreCase(byte[] bytes, String prefix, int start) {
    int len = prefix.length();
    for (int i = start; i <= bytes.length - len; i++) {
      boolean match = true;
      for (int j = 0; j < len; j++) {
        char c1 = (char) (bytes[i + j] & 0xFF);
        char c2 = prefix.charAt(j);
        if (Character.toLowerCase(c1) != Character.toLowerCase(c2)) {
          match = false;
          break;
        }
      }
      if (match) return i;
    }
    return -1;
  }

  private static int scalarIndexOfIgnoreCaseUtf16(char[] chars, String prefix, int start) {
    int len = prefix.length();
    for (int i = start; i <= chars.length - len; i++) {
      boolean match = true;
      for (int j = 0; j < len; j++) {
        char c1 = chars[i + j];
        char c2 = prefix.charAt(j);
        if (Character.toLowerCase(c1) != Character.toLowerCase(c2)) {
          match = false;
          break;
        }
      }
      if (match) return i;
    }
    return -1;
  }
}
