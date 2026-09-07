// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class RarityOracleTest {

  @Test
  void spaceIsMostCommonAndRareLettersHaveHighRank() {
    assertThat(RarityOracle.byteRarity(' ')).isEqualTo(0);
    assertThat(RarityOracle.byteRarity('e')).isLessThan(RarityOracle.byteRarity('z'));
    assertThat(RarityOracle.byteRarity('t')).isLessThan(RarityOracle.byteRarity('q'));
    assertThat(RarityOracle.byteRarity('a')).isLessThan(RarityOracle.byteRarity('x'));
  }

  @Test
  void caseInsensitiveLettersShareIdenticalRanks() {
    assertThat(RarityOracle.byteRarity('A')).isEqualTo(RarityOracle.byteRarity('a'));
    assertThat(RarityOracle.byteRarity('Z')).isEqualTo(RarityOracle.byteRarity('z'));
    assertThat(RarityOracle.byteRarity('E')).isEqualTo(RarityOracle.byteRarity('e'));
  }

  @Test
  void rarestAsciiOffsetFindsRarestCharacter() {
    // 't', 'h', 'e' are common, 'q' is rare
    String prefix = "the_query";
    int offset = RarityOracle.rarestAsciiOffset(prefix, prefix.length());
    assertThat(offset).isEqualTo(prefix.indexOf('q'));

    // 'a' is common, 'z' is rare
    String zone = "authorization";
    assertThat(RarityOracle.rarestAsciiOffset(zone, zone.length())).isEqualTo(zone.indexOf('z'));
  }

  @Test
  void literalSelectivityRewardsRareCharacters() {
    // "404_NOT_FOUND" contains digits, underscores, and rare letters
    int rareScore = RarityOracle.literalSelectivityScore("404_NOT_FOUND");
    // "              " (spaces of equal length) has very low score
    int commonScore = RarityOracle.literalSelectivityScore("             ");
    assertThat(rareScore).isGreaterThan(commonScore * 3);
  }

  @Test
  void literalSelectivityRetainsLengthForTheMostCommonCharacter() {
    assertThat(RarityOracle.literalSelectivityScore(" ".repeat(32)))
        .isGreaterThan(RarityOracle.literalSelectivityScore("ee"));
  }

  @Test
  void poisonousAnchorDetection() {
    assertThat(RarityOracle.isPoisonousAnchor(" ")).isTrue();
    assertThat(RarityOracle.isPoisonousAnchor("e")).isTrue();
    assertThat(RarityOracle.isPoisonousAnchor("E")).isTrue();
    assertThat(RarityOracle.isPoisonousAnchor("z")).isFalse();
    assertThat(RarityOracle.isPoisonousAnchor("q")).isFalse();
    assertThat(RarityOracle.isPoisonousAnchor("404")).isFalse();
    assertThat(RarityOracle.isPoisonousAnchor("  ")).isFalse();
    assertThat(RarityOracle.isPoisonousAnchor(null)).isFalse();
    assertThat(RarityOracle.isPoisonousAnchor("")).isFalse();
  }

  @Test
  void rarestAsciiPairIgnoreCaseReturnsNullForShortOrNonAscii() {
    assertThat(RarityOracle.rarestAsciiPairIgnoreCase("", 0)).isNull();
    assertThat(RarityOracle.rarestAsciiPairIgnoreCase("a", 1)).isNull();
    assertThat(RarityOracle.rarestAsciiPairIgnoreCase("a\u0080", 2)).isNull();
    assertThat(RarityOracle.rarestAsciiPairIgnoreCase("hello\u00FFworld", 12)).isNull();
  }

  @Test
  void rarestAsciiPairIgnoreCaseFindsTwoRarestWithOrderedOffsets() {
    String prefix = "content-type";
    RarityOracle.AsciiPair pair = RarityOracle.rarestAsciiPairIgnoreCase(prefix, prefix.length());
    assertThat(pair).isNotNull();
    assertThat(pair.offset1()).isLessThan(pair.offset2());
    char c1 = prefix.charAt(pair.offset1());
    char c2 = prefix.charAt(pair.offset2());
    assertThat(pair.low1()).isEqualTo((byte) Ascii.toLowerCase(c1));
    assertThat(pair.high1()).isEqualTo((byte) Ascii.toUpperCase(c1));
    assertThat(pair.low2()).isEqualTo((byte) Ascii.toLowerCase(c2));
    assertThat(pair.high2()).isEqualTo((byte) Ascii.toUpperCase(c2));
  }

  @Test
  void rarestAsciiPairIgnoreCaseHandlesIdenticalCharacters() {
    String prefix = "banana";
    RarityOracle.AsciiPair pair = RarityOracle.rarestAsciiPairIgnoreCase(prefix, prefix.length());
    assertThat(pair).isNotNull();
    assertThat(pair.offset1()).isLessThan(pair.offset2());
    char c1 = prefix.charAt(pair.offset1());
    char c2 = prefix.charAt(pair.offset2());
    assertThat(pair.low1()).isEqualTo((byte) Ascii.toLowerCase(c1));
    assertThat(pair.high1()).isEqualTo((byte) Ascii.toUpperCase(c1));
    assertThat(pair.low2()).isEqualTo((byte) Ascii.toLowerCase(c2));
    assertThat(pair.high2()).isEqualTo((byte) Ascii.toUpperCase(c2));
  }
}
