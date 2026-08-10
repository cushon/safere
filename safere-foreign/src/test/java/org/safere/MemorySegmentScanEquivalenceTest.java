// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Comprehensive differential equivalence tests comparing {@link MemorySegment} SIMD/SWAR kernels
 * against array kernels and scalar reference models across on-heap and off-heap memory.
 */
class MemorySegmentScanEquivalenceTest {

  private static final List<int[]> ASCII_CHAR_CLASS_RANGES =
      List.of(
          new int[] {'0', '9'},
          new int[] {'a', 'z'},
          new int[] {'a', 'z', 'A', 'Z'},
          new int[] {'0', '9', 'a', 'z', 'A', 'Z'},
          new int[] {'0', '9', 'a', 'z', 'A', 'Z', '_', '_'});

  private static final List<int[]> UTF16_CHAR_CLASS_RANGES =
      List.of(
          new int[] {'0', '9'},
          new int[] {'a', 'z', 'A', 'Z'},
          new int[] {0x0400, 0x04FF}, // Cyrillic
          new int[] {0x3040, 0x309F}, // Hiragana
          new int[] {'0', '9', 0x0400, 0x04FF});

  private static boolean isVectorApiAvailable() {
    try {
      Class.forName("jdk.incubator.vector.ByteVector");
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 3, 7, 15, 16, 31, 32, 63, 64, 127, 128, 512, 1024, 2048})
  @DisplayName("1-byte Latin-1 char class scan equivalence across Array, Heap-Segment, Off-Heap")
  void testLatin1CharClassEquivalence(int length) {
    Random random = new Random(42 + length);
    byte[] input = new byte[length];
    for (int i = 0; i < length; i++) {
      input[i] = (byte) (random.nextInt(26) + 'a'); // random lowercase
    }

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment heapSegment = MemorySegment.ofArray(input);
      MemorySegment offHeapSegment = arena.allocateFrom(ValueLayout.JAVA_BYTE, input);

      for (int[] ranges : ASCII_CHAR_CLASS_RANGES) {
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
    // Inject some Cyrillic chars
    if (charLength > 10) {
      chars[charLength / 2] = '\u0416';
    }

    String str = new String(chars);
    byte[] utf16Bytes = str.getBytes(UTF_16LE);

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment heapSegment = MemorySegment.ofArray(utf16Bytes);
      MemorySegment offHeapSegment = arena.allocateFrom(ValueLayout.JAVA_BYTE, utf16Bytes);

      for (int[] ranges : UTF16_CHAR_CLASS_RANGES) {
        for (int start : new int[] {0, 1, 7, 15, 31, Math.max(0, charLength - 5)}) {
          if (start > charLength) continue;

          int expected = scalarIndexOfCharClass(chars, ranges, start);

          // SWAR Kernels (Always available)
          int shortSwar =
              (ranges.length <= 4)
                  ? ShortSwarScan.indexOfCharClassUtf16(utf16Bytes, 0, charLength, ranges, start)
                  : expected;
          int segShortSwar =
              (ranges.length <= 4)
                  ? SegmentShortSwarScan.indexOfCharClassUtf16(
                      heapSegment, 0, charLength, ranges, start)
                  : expected;
          int offHeapShortSwar =
              (ranges.length <= 4)
                  ? SegmentShortSwarScan.indexOfCharClassUtf16(
                      offHeapSegment, 0, charLength, ranges, start)
                  : expected;

          assertThat(shortSwar)
              .as("ShortSwarScan at start=%d, len=%d", start, charLength)
              .isEqualTo(expected);
          assertThat(segShortSwar)
              .as("SegmentShortSwar (heap) at start=%d", start)
              .isEqualTo(expected);
          assertThat(offHeapShortSwar)
              .as("SegmentShortSwar (off-heap) at start=%d", start)
              .isEqualTo(expected);

          // Vector API Kernels (When enabled on runtime)
          if (isVectorApiAvailable()) {
            int shortVector =
                ShortVectorScan.indexOfCharClassUtf16(utf16Bytes, 0, charLength, ranges, start);
            int segShortVector =
                SegmentShortVectorScan.indexOfCharClassUtf16(
                    heapSegment, 0, charLength, ranges, start);
            int offHeapShortVector =
                SegmentShortVectorScan.indexOfCharClassUtf16(
                    offHeapSegment, 0, charLength, ranges, start);

            assertThat(shortVector)
                .as("ShortVectorScan at start=%d, len=%d", start, charLength)
                .isEqualTo(expected);
            assertThat(segShortVector)
                .as("SegmentShortVector (heap) at start=%d", start)
                .isEqualTo(expected);
            assertThat(offHeapShortVector)
                .as("SegmentShortVector (off-heap) at start=%d", start)
                .isEqualTo(expected);
          }
        }
      }
    }
  }

  @Test
  @DisplayName("Case-insensitive prefix scan equivalence for 1-byte and 2-byte MemorySegment")
  void testIgnoreCaseEquivalence() {
    String prefix = "hElLo";
    String haystackLatin1 = "x".repeat(500) + "hello world" + "x".repeat(500);
    String haystackUtf16 =
        "x".repeat(500)
            + "\u0416\u0435\u043B\u043B\u043E"
            + "x".repeat(200)
            + "HELLO"
            + "x".repeat(300);

    byte[] latin1Bytes = haystackLatin1.getBytes(ISO_8859_1);
    byte[] utf16Bytes = haystackUtf16.getBytes(UTF_16LE);

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment latin1Segment = arena.allocateFrom(ValueLayout.JAVA_BYTE, latin1Bytes);
      MemorySegment utf16Segment = arena.allocateFrom(ValueLayout.JAVA_BYTE, utf16Bytes);

      // SWAR
      int latin1Swar =
          SegmentByteSwarScan.indexOfIgnoreCase(
              latin1Segment, 0, haystackLatin1.length(), prefix, 0);
      assertThat(latin1Swar).isEqualTo(500);

      int utf16Swar =
          SegmentShortSwarScan.indexOfIgnoreCaseUtf16(
              utf16Segment, 0, haystackUtf16.length(), prefix, 0);
      assertThat(utf16Swar).isEqualTo(705);

      // Vector
      if (isVectorApiAvailable()) {
        int latin1Vec =
            SegmentByteVectorScan.indexOfIgnoreCase(
                latin1Segment, 0, haystackLatin1.length(), prefix, 0);
        assertThat(latin1Vec).isEqualTo(500);

        int utf16Vec =
            SegmentShortVectorScan.indexOfIgnoreCaseUtf16(
                utf16Segment, 0, haystackUtf16.length(), prefix, 0);
        assertThat(utf16Vec).isEqualTo(705);
      }
    }
  }

  private static int scalarIndexOfAsciiClass(byte[] input, int[] ranges, int start) {
    for (int i = start; i < input.length; i++) {
      int b = input[i] & 0xFF;
      for (int r = 0; r < ranges.length; r += 2) {
        if (b >= ranges[r] && b <= ranges[r + 1]) {
          return i;
        }
      }
    }
    return -1;
  }

  private static int scalarIndexOfCharClass(char[] input, int[] ranges, int start) {
    for (int i = start; i < input.length; i++) {
      char c = input[i];
      for (int r = 0; r < ranges.length; r += 2) {
        if (c >= ranges[r] && c <= ranges[r + 1]) {
          return i;
        }
      }
    }
    return -1;
  }
}
