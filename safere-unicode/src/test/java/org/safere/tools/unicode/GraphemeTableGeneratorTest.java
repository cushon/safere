// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.tools.unicode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphemeTableGeneratorTest {
  @TempDir Path temporary;

  @Test
  void parsesSingletonsRangesAndIndependentPropertyColumns() throws IOException {
    prepareData();
    Map<String, int[][]> output = GraphemeTableGenerator.generate(temporary);
    assertThat(output.get("GCB_CONTROL"))
        .isDeepEqualTo(new int[][] {{0xa, 0xa}, {0xd, 0xd}, {0x20, 0x22}});
    assertThat(output.get("GCB_ZWJ")).isDeepEqualTo(new int[][] {{0x200d, 0x200d}});
    assertThat(output.get("INCB_EXTEND")).isDeepEqualTo(new int[][] {{0x60, 0x61}});
    assertThat(output.get("EXTENDED_PICTOGRAPHIC")).isDeepEqualTo(new int[][] {{0xa9, 0xa9}});
    Map<String, int[][]> regenerated = GraphemeTableGenerator.generate(temporary);
    assertThat(regenerated.keySet()).containsExactlyElementsOf(output.keySet());
    output.forEach((key, ranges) -> assertThat(regenerated.get(key)).isDeepEqualTo(ranges));
  }

  @Test
  void rejectsWrongVersionAndMissingRequiredProperty() throws IOException {
    prepareData();
    Path file = temporary.resolve("GraphemeBreakProperty.txt");
    String original = Files.readString(file);
    Files.writeString(file, original.replace("17.0.0", "16.0.0"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> GraphemeTableGenerator.generate(temporary))
        .withMessageContaining("Wrong Unicode version");
    Files.writeString(file, original.replace("0040 ; Prepend", "#0040 ; Prepend"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> GraphemeTableGenerator.generate(temporary))
        .withMessageContaining("Missing Unicode property GCB_PREPEND");
  }

  @Test
  void rejectsOverlapsAndOutOfRangeCodePoints() throws IOException {
    prepareData();
    Path file = temporary.resolve("GraphemeBreakProperty.txt");
    String original = Files.readString(file);
    Files.writeString(file, original + "0021 ; Control\n");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> GraphemeTableGenerator.generate(temporary))
        .withMessageContaining("Overlapping Unicode ranges");
    Files.writeString(file, original + "110000 ; Control\n");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> GraphemeTableGenerator.generate(temporary))
        .withMessageContaining("Invalid range");
  }

  @Test
  void rejectsUnknownPropertyInsteadOfSilentlyDroppingIt() throws IOException {
    prepareData();
    Path file = temporary.resolve("DerivedCoreProperties.txt");
    Files.writeString(file, Files.readString(file) + "0010 ; InCB; FutureValue\n");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> GraphemeTableGenerator.generate(temporary))
        .withMessageContaining("Unknown InCB value");
  }

  private void prepareData() throws IOException {
    Files.writeString(
        temporary.resolve("GraphemeBreakProperty.txt"),
        """
        # GraphemeBreakProperty-17.0.0.txt
        # @missing: 0000..10FFFF; Other
        000D ; CR
        000A ; LF
        0020..0021 ; Control # comment
        0022 ; Control
        0030 ; Extend
        0040 ; Prepend
        0050 ; SpacingMark
        0060 ; L
        0070 ; V
        0080 ; T
        0090 ; LV
        00A0 ; LVT
        200D ; ZWJ
        1F1E6..1F1FF ; Regional_Indicator
        """);
    Files.writeString(
        temporary.resolve("DerivedCoreProperties.txt"),
        """
        # DerivedCoreProperties-17.0.0.txt
        0030 ; Alphabetic
        0040 ; InCB; Linker
        0050 ; InCB ; Consonant
        0060..0061 ; InCB; Extend # comment
        """);
    Files.writeString(
        temporary.resolve("emoji-data.txt"),
        """
        # Version: 17.0
        0023 ; Emoji
        00A9 ; Extended_Pictographic# comment without preceding space
        """);
  }
}
