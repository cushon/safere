// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("implementation test invokes the experimental Teddy vector kernel directly")
class TeddyVectorScanTest {
  private static final String[][] LITERALS = {
    {"a", "pqr"},
    {"ab", "pqr"},
    {"abc", "pqr"},
    {"cherry", "delta"}
  };

  @Test
  void alternatingStageCountsPreserveEarliestMatch() {
    assumeTrue(VectorScanProviders.vectorProviderAvailable(), "Vector provider is not enabled");
    TeddyModel[] models = models();
    for (int round = 0; round < 8; round++) {
      for (int stage : new int[] {2, 1, 0, 3, 1, 2, 0}) {
        for (String text :
            new String[] {
              "x".repeat(4096),
              "a b c ".repeat(512) + "abc pqr cherry delta",
              "x".repeat(2048) + "pqr" + "x".repeat(2048) + "abc",
              "z".repeat(2048) + "cherry" + "z".repeat(2048) + "delta"
            }) {
          byte[] bytes = text.getBytes(US_ASCII);
          for (int start : new int[] {0, 1, 31, 64, 2049, bytes.length}) {
            int expected =
                TeddyVectorScan.isSupported()
                    ? expected(text, LITERALS[stage], start)
                    : VectorScanProvider.UNSUPPORTED;
            assertThat(
                    TeddyVectorScan.indexOfTeddyUtf8(bytes, 0, bytes.length, models[stage], start))
                .as("stage %s, round %s, start %s", stage + 1, round, start)
                .isEqualTo(expected);
          }
        }
      }
    }
  }

  @Test
  void stageCountsRespectWindowAndVectorTailBoundaries() {
    assumeTrue(VectorScanProviders.vectorProviderAvailable(), "Vector provider is not enabled");
    TeddyModel[] models = models();
    for (int length : new int[] {15, 16, 17, 31, 32, 33, 63, 64, 65, 1024, 4096}) {
      for (int stage : new int[] {2, 0, 1}) {
        for (int position : new int[] {0, 1, 14, 15, 16, 30, 31, 32, 62, 63, 64, length - 3}) {
          if (position < 0 || position + 3 > length) {
            continue;
          }
          byte[] bytes = new byte[length + 14];
          Arrays.fill(bytes, (byte) 'x');
          byte[] literal = "abc".getBytes(US_ASCII);
          System.arraycopy(literal, 0, bytes, 0, literal.length);
          System.arraycopy(literal, 0, bytes, 7 + position, literal.length);
          System.arraycopy(literal, 0, bytes, 7 + length, literal.length);
          String window = new String(bytes, 7, length, US_ASCII);
          for (int start : new int[] {-1, 0, position, position + 1, length}) {
            int expected =
                TeddyVectorScan.isSupported()
                    ? expected(window, LITERALS[stage], start)
                    : VectorScanProvider.UNSUPPORTED;
            assertThat(TeddyVectorScan.indexOfTeddyUtf8(bytes, 7, length, models[stage], start))
                .as(
                    "stage %s, length %s, position %s, start %s",
                    stage + 1, length, position, start)
                .isEqualTo(expected);
          }
        }
      }
    }
  }

  @Test
  void patternSearchPreservesMatchesWithHighLowNibblesAcrossAllWidths() {
    assumeTrue(VectorScanProviders.vectorProviderAvailable(), "Vector provider is not enabled");
    // Ensure a 6-literal Teddy plan and a 4-literal adaptive multi-literal plan stay exact even
    // when literals contain bytes with low nibble >= 8 ('h'=0x68, 'l'=0x6c, 'z'=0x7a).
    String pad = "m ".repeat(600);
    String hay = pad + "cherry " + pad + "delta " + pad + "zebra";
    byte[] bytes = hay.getBytes(US_ASCII);
    Pattern teddyPattern = Pattern.compile("alpha|beta|cherry|delta|echo|zebra");
    Utf8Matcher m = teddyPattern.matcher(Utf8Input.validated(bytes));
    assertThat(m.find()).isTrue();
    assertThat(m.start()).isEqualTo(pad.length());
    assertThat(m.find()).isTrue();
    assertThat(m.start()).isEqualTo(pad.length() + "cherry ".length() + pad.length());
    assertThat(m.find()).isTrue();
    assertThat(m.start())
        .isEqualTo(
            pad.length() + "cherry ".length() + pad.length() + "delta ".length() + pad.length());
    assertThat(m.find()).isFalse();
  }

  private static TeddyModel[] models() {
    TeddyModel[] models = new TeddyModel[LITERALS.length];
    for (int i = 0; i < models.length; i++) {
      models[i] = TeddyModel.compile(LITERALS[i], 64);
      assertThat(models[i]).isNotNull();
      assertThat(models[i].is2Byte()).isEqualTo(i >= 1);
      assertThat(models[i].is3Byte()).isEqualTo(i >= 2);
    }
    return models;
  }

  private static int expected(String text, String[] literals, int start) {
    int first = -1;
    for (String literal : literals) {
      int found = text.indexOf(literal, Math.max(0, start));
      if (found >= 0 && (first < 0 || found < first)) {
        first = found;
      }
    }
    return first;
  }
}
