// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

/**
 * Exhaustive length/offset/tail boundary tests for {@link ByteVectorScan}. Multiple SIMD widths are
 * exercised by running the test JVM under {@code -XX:MaxVectorSize} (see the {@code vector-tests}
 * Maven profile).
 */
@DisabledForCrosscheck("implementation test invokes package-private Vector API kernels")
class VectorScanBoundaryTest {

  private static final boolean VECTOR_AVAILABLE = isVectorApiAvailable();
  private static final String EXPECTED_VECTOR_BITS_PROPERTY =
      "org.safere.testing.expectedVectorBitSize";

  private static boolean isVectorApiAvailable() {
    try {
      Class.forName("jdk.incubator.vector.ByteVector");
      return true;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  private static int preferredBitSize(String vectorClassName) throws ReflectiveOperationException {
    Class<?> vectorClass = Class.forName(vectorClassName);
    Class<?> speciesInterface = Class.forName("jdk.incubator.vector.VectorSpecies");
    Object species = vectorClass.getField("SPECIES_PREFERRED").get(null);
    return (int) speciesInterface.getMethod("vectorBitSize").invoke(species);
  }

  @Test
  void preferredSpeciesMatchesConfiguredVectorWidth() throws ReflectiveOperationException {
    assumeTrue(VECTOR_AVAILABLE, "Vector API not available on module path");
    String expected = System.getProperty(EXPECTED_VECTOR_BITS_PROPERTY);
    assumeTrue(expected != null && !expected.isEmpty(), "No explicit vector bit size requested");
    int expectedBits = Integer.parseInt(expected);
    assertThat(preferredBitSize("jdk.incubator.vector.ByteVector")).isEqualTo(expectedBits);
    assertThat(preferredBitSize("jdk.incubator.vector.ShortVector")).isEqualTo(expectedBits);
  }

  @Test
  void byteMatchingAcrossLengthsAndStarts() {
    assumeTrue(VECTOR_AVAILABLE, "Vector API not available on module path");
    for (int len = 0; len <= 256; len++) {
      byte[] absent = "A".repeat(len).getBytes(UTF_8);
      for (int start = 0; start <= len; start++) {
        assertThat(ByteVectorScan.indexOfByte(absent, 0, len, (byte) 'z', start))
            .as("Byte absent for len %d, start %d", len, start)
            .isEqualTo(-1);
      }

      if (len > 0) {
        byte[] matchStart = absent.clone();
        matchStart[0] = 'z';
        assertThat(ByteVectorScan.indexOfByte(matchStart, 0, len, (byte) 'z', 0))
            .as("Byte match start for len %d", len)
            .isEqualTo(0);

        int mid = len / 2;
        byte[] matchMid = absent.clone();
        matchMid[mid] = 'z';
        assertThat(ByteVectorScan.indexOfByte(matchMid, 0, len, (byte) 'z', 0))
            .as("Byte match mid for len %d, mid %d", len, mid)
            .isEqualTo(mid);

        byte[] matchEnd = absent.clone();
        matchEnd[len - 1] = 'z';
        assertThat(ByteVectorScan.indexOfByte(matchEnd, 0, len, (byte) 'z', 0))
            .as("Byte match end for len %d", len)
            .isEqualTo(len - 1);
      }
    }
  }

  @Test
  void asciiClassMatchingAcrossLengthsAndStarts() {
    assumeTrue(VECTOR_AVAILABLE, "Vector API not available on module path");
    int[] ranges = {'0', '9', 'a', 'z'};
    for (int len = 0; len <= 256; len++) {
      byte[] absent = "A".repeat(len).getBytes(UTF_8);
      for (int start = 0; start <= len; start++) {
        assertThat(ByteVectorScan.indexOfAsciiClass(absent, 0, len, ranges, start))
            .as("Absent for len %d, start %d", len, start)
            .isEqualTo(-1);
      }

      if (len > 0) {
        byte[] matchStart = absent.clone();
        matchStart[0] = '5';
        assertThat(ByteVectorScan.indexOfAsciiClass(matchStart, 0, len, ranges, 0))
            .as("Match start for len %d", len)
            .isEqualTo(0);

        int mid = len / 2;
        byte[] matchMid = absent.clone();
        matchMid[mid] = 'b';
        assertThat(ByteVectorScan.indexOfAsciiClass(matchMid, 0, len, ranges, 0))
            .as("Match mid for len %d, mid %d", len, mid)
            .isEqualTo(mid);

        byte[] matchEnd = absent.clone();
        matchEnd[len - 1] = 'z';
        assertThat(ByteVectorScan.indexOfAsciiClass(matchEnd, 0, len, ranges, 0))
            .as("Match end for len %d", len)
            .isEqualTo(len - 1);
      }
    }
  }

  @Test
  void byteIgnoreCaseMatchingAcrossOffsets() {
    assumeTrue(VECTOR_AVAILABLE, "Vector API not available on module path");
    String prefix = "needle";
    int prefixLen = prefix.length();
    byte low = 'n';
    byte high = 'N';

    for (int pad = 0; pad <= 128; pad++) {
      byte[] bytes = ("x".repeat(pad) + "NeEdLe" + "y".repeat(64)).getBytes(UTF_8);
      assertThat(
              ByteVectorScan.indexOfIgnoreCase(
                  bytes, 0, bytes.length, prefix, prefixLen, 0, low, high, 0))
          .as("IgnoreCase match for pad %d", pad)
          .isEqualTo(pad);

      assertThat(
              ByteVectorScan.indexOfIgnoreCase(
                  bytes, 0, bytes.length, prefix, prefixLen, 3, (byte) 'd', (byte) 'D', 0))
          .as("IgnoreCase anchored match for pad %d", pad)
          .isEqualTo(pad);

      byte[] absent = "x".repeat(pad + 64).getBytes(UTF_8);
      assertThat(
              ByteVectorScan.indexOfIgnoreCase(
                  absent, 0, absent.length, prefix, prefixLen, 0, low, high, 0))
          .as("IgnoreCase absent for pad %d", pad)
          .isEqualTo(-1);
    }
  }
}
