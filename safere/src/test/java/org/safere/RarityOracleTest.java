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
}
