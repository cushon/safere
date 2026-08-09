// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Correctness tests for prefix and character-class start acceleration scanning. */
class PrefixAccelerationTest {

  @Test
  void testCaseInsensitivePrefixFind() {
    Pattern p = Pattern.compile("(?i)hello");

    // 1. Match at the end
    String text = "x".repeat(1100) + "Hello, world!";
    Matcher m = p.matcher(text);
    assertThat(m.find()).isTrue();
    assertThat(m.start()).isEqualTo(1100);

    // 2. Mixed case match
    String text2 = "x".repeat(1050) + "hElLo";
    Matcher m2 = p.matcher(text2);
    assertThat(m2.find()).isTrue();
    assertThat(m2.start()).isEqualTo(1050);

    // 3. No match at all
    String text3 = "x".repeat(1200);
    Matcher m3 = p.matcher(text3);
    assertThat(m3.find()).isFalse();

    // 4. Exact 1024 boundary matching
    String text4 = "x".repeat(1024) + "hello";
    Matcher m4 = p.matcher(text4);
    assertThat(m4.find()).isTrue();
    assertThat(m4.start()).isEqualTo(1024);

    // 5. Match within body
    String text5 = "x".repeat(1015) + "hello" + "x".repeat(100);
    Matcher m5 = p.matcher(text5);
    assertThat(m5.find()).isTrue();
    assertThat(m5.start()).isEqualTo(1015);
  }

  @Test
  void testNonAlphabeticFirstChar() {
    Pattern p = Pattern.compile("(?i)-hello");
    String text = "x".repeat(1100) + "-Hello";
    Matcher m = p.matcher(text);
    assertThat(m.find()).isTrue();
    assertThat(m.start()).isEqualTo(1100);
  }

  @Test
  void testCaseSensitivePrefixFind() {
    Pattern p = Pattern.compile("foobar_xyz");
    String text = "a".repeat(2000) + "foobar_xyz" + "b".repeat(500);
    Matcher m = p.matcher(text);
    assertThat(m.find()).isTrue();
    assertThat(m.start()).isEqualTo(2000);
    assertThat(m.end()).isEqualTo(2010);

    // UTF-8 input scanner test
    Matcher mUtf8 = p.matcher(Utf8Input.trusted(text.getBytes(StandardCharsets.UTF_8)));
    assertThat(mUtf8.find()).isTrue();
    assertThat(mUtf8.start()).isEqualTo(2000);
  }

  @Test
  void testCharClassPrefixFind() {
    Pattern p = Pattern.compile("[0-9][a-z]+");
    String text = "x".repeat(1500) + "7abc" + "y".repeat(100);
    Matcher m = p.matcher(text);
    assertThat(m.find()).isTrue();
    assertThat(m.start()).isEqualTo(1500);
    assertThat(m.end()).isEqualTo(1504);

    Matcher mUtf8 = p.matcher(Utf8Input.trusted(text.getBytes(StandardCharsets.UTF_8)));
    assertThat(mUtf8.find()).isTrue();
    assertThat(mUtf8.start()).isEqualTo(1500);
  }
}
