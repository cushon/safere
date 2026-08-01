// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.vector;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.safere.Pattern.CharClassScanInfo;

class VectorScannerImplTest {
  private final VectorScannerImpl scanner = new VectorScannerImpl();

  @Test
  void testSingleRange_Match() {
    CharClassScanInfo info = new CharClassScanInfo(new int[] {'a', 'z'}, 0, 0);

    // We want a string > 32 chars to avoid short fallback
    String text = "123456789012345678901234567890ax";
    int idx = scanner.indexOfCharClass(text, info, 0);
    assertThat(idx).isEqualTo(30); // 'a'
  }

  @Test
  void testMultipleRanges_Match() {
    CharClassScanInfo info = new CharClassScanInfo(new int[] {'A', 'Z', 'a', 'z'}, 0, 0);

    String text = "123456789012345678901234567890Bx";
    int idx = scanner.indexOfCharClass(text, info, 0);
    assertThat(idx).isEqualTo(30); // 'B'
  }

  @Test
  void testNoMatch() {
    CharClassScanInfo info = new CharClassScanInfo(new int[] {'a', 'z'}, 0, 0);

    String text = "1234567890123456789012345678901234567890";
    int idx = scanner.indexOfCharClass(text, info, 0);
    assertThat(idx).isEqualTo(-1);
  }

  @Test
  void testShortInputFallback() {
    CharClassScanInfo info = new CharClassScanInfo(new int[] {'a', 'z'}, 0, 0);

    String text = "1234a"; // < 32 chars
    int idx = scanner.indexOfCharClass(text, info, 0);
    assertThat(idx).isEqualTo(-2); // fallback
  }

  @Test
  void testTooManyRangesFallback() {
    CharClassScanInfo info =
        new CharClassScanInfo(
            new int[] {'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l'},
            0,
            0); // 6 ranges

    String text = "123456789012345678901234567890ax";
    int idx = scanner.indexOfCharClass(text, info, 0);
    assertThat(idx).isEqualTo(-2); // fallback
  }

  @Test
  void testLoaderResolvesImplementation() {
    VectorScannerBridge bridge = VectorScannerLoader.getInstance();
    assertThat(bridge).isNotNull();
    assertThat(bridge).isInstanceOf(VectorScannerImpl.class);
  }
}
