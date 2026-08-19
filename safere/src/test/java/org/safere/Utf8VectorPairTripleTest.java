// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Utf8VectorPairTripleTest {

  private static boolean isVectorApiAvailable() {
    try {
      Class.forName("jdk.incubator.vector.ByteVector");
      return true;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 15, 16, 31, 32, 63, 64, 100, 128, 256, 500})
  void testPairEquivalenceWithSwar(int length) {
    assumeTrue(isVectorApiAvailable(), "Vector API not available on module path");

    byte b0 = 'x';
    byte b1 = 'y';
    Random rnd = new Random(1000 + length);

    for (int trial = 0; trial < 50; trial++) {
      byte[] bytes = new byte[length];
      for (int i = 0; i < length; i++) {
        bytes[i] = (byte) ('a' + rnd.nextInt(20)); // 'a'..'t' (no 'x' or 'y')
      }
      int start = length == 0 ? 0 : rnd.nextInt(length);

      // Absent check
      int swarAbsent = ByteSwarScan.indexOfBytePair(bytes, 0, length, b0, b1, start);
      int vectorAbsent = ByteVectorScan.indexOfAsciiPair(bytes, 0, length, b0, b1, start);
      assertThat(vectorAbsent).as("absent trial %d len %d", trial, length).isEqualTo(swarAbsent);

      // Present check
      if (length > start) {
        int pos = start + rnd.nextInt(length - start);
        bytes[pos] = rnd.nextBoolean() ? b0 : b1;

        int swarHit = ByteSwarScan.indexOfBytePair(bytes, 0, length, b0, b1, start);
        int vectorHit = ByteVectorScan.indexOfAsciiPair(bytes, 0, length, b0, b1, start);
        assertThat(vectorHit).as("hit trial %d len %d", trial, length).isEqualTo(swarHit);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 15, 16, 31, 32, 63, 64, 100, 128, 256, 500})
  void testTripleEquivalenceWithSwar(int length) {
    assumeTrue(isVectorApiAvailable(), "Vector API not available on module path");

    byte b0 = 'x';
    byte b1 = 'y';
    byte b2 = 'z';
    Random rnd = new Random(2000 + length);

    for (int trial = 0; trial < 50; trial++) {
      byte[] bytes = new byte[length];
      for (int i = 0; i < length; i++) {
        bytes[i] = (byte) ('a' + rnd.nextInt(20)); // 'a'..'t' (no 'x', 'y', 'z')
      }
      int start = length == 0 ? 0 : rnd.nextInt(length);

      // Absent check
      int swarAbsent = ByteSwarScan.indexOfByteTriple(bytes, 0, length, b0, b1, b2, start);
      int vectorAbsent = ByteVectorScan.indexOfAsciiTriple(bytes, 0, length, b0, b1, b2, start);
      assertThat(vectorAbsent).as("absent trial %d len %d", trial, length).isEqualTo(swarAbsent);

      // Present check
      if (length > start) {
        int pos = start + rnd.nextInt(length - start);
        int choice = rnd.nextInt(3);
        bytes[pos] = choice == 0 ? b0 : choice == 1 ? b1 : b2;

        int swarHit = ByteSwarScan.indexOfByteTriple(bytes, 0, length, b0, b1, b2, start);
        int vectorHit = ByteVectorScan.indexOfAsciiTriple(bytes, 0, length, b0, b1, b2, start);
        assertThat(vectorHit).as("hit trial %d len %d", trial, length).isEqualTo(swarHit);
      }
    }
  }

  @Test
  void testScannerPairAndTripleWithLimit() {
    byte[] bytes = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes(UTF_8);
    Utf8InputScanner scanner = new Utf8InputScanner(bytes);

    // 'a' is at 10, 'A' is at 36
    assertThat(scanner.indexOfAsciiPair('a', 'A', 0, 10)).isEqualTo(-1);
    assertThat(scanner.indexOfAsciiPair('a', 'A', 0, 11)).isEqualTo(10);
    assertThat(scanner.indexOfAsciiPair('a', 'A', 11, 36)).isEqualTo(-1);
    assertThat(scanner.indexOfAsciiPair('a', 'A', 11, 37)).isEqualTo(36);

    // 'b' at 11, 'm' at 22, 'Z' at 61
    assertThat(scanner.indexOfAsciiTriple('b', 'm', 'Z', 0, 11)).isEqualTo(-1);
    assertThat(scanner.indexOfAsciiTriple('b', 'm', 'Z', 0, 12)).isEqualTo(11);
    assertThat(scanner.indexOfAsciiTriple('b', 'm', 'Z', 12, 22)).isEqualTo(-1);
    assertThat(scanner.indexOfAsciiTriple('b', 'm', 'Z', 12, 23)).isEqualTo(22);
    assertThat(scanner.indexOfAsciiTriple('b', 'm', 'Z', 23, 61)).isEqualTo(-1);
    assertThat(scanner.indexOfAsciiTriple('b', 'm', 'Z', 23, 62)).isEqualTo(61);
  }
}
