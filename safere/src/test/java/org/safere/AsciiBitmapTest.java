// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class AsciiBitmapTest {

  @Test
  void emptyBitmapContainsNothing() {
    AsciiBitmap bitmap = AsciiBitmap.EMPTY;
    assertThat(bitmap.isEmpty()).isTrue();
    assertThat(bitmap.cardinality()).isZero();
    for (int cp = -1; cp <= 256; cp++) {
      assertThat(bitmap.contains(cp)).isFalse();
    }
  }

  @Test
  void singleCodePointContainsOnlyThatCodePoint() {
    for (int target = 0; target < 128; target++) {
      AsciiBitmap bitmap = AsciiBitmap.of(target);
      assertThat(bitmap.isEmpty()).isFalse();
      assertThat(bitmap.cardinality()).isEqualTo(1);
      assertThat(bitmap.contains(target)).isTrue();
      assertThat(bitmap.contains(target - 1)).isFalse();
      assertThat(bitmap.contains(target + 1)).isFalse();
      assertThat(bitmap.contains(-1)).isFalse();
      assertThat(bitmap.contains(128)).isFalse();
    }
  }

  @Test
  void builderAddRangeAndContains() {
    AsciiBitmap digits = new AsciiBitmap.Builder().addRange('0', '9').build();
    assertThat(digits.cardinality()).isEqualTo(10);
    for (char c = '0'; c <= '9'; c++) {
      assertThat(digits.contains(c)).isTrue();
    }
    assertThat(digits.contains('a')).isFalse();
    assertThat(digits.contains('/')).isFalse();
    assertThat(digits.contains(':')).isFalse();
  }

  @Test
  void unionCombinesSets() {
    AsciiBitmap digits = new AsciiBitmap.Builder().addRange('0', '9').build();
    AsciiBitmap letters = new AsciiBitmap.Builder().addRange('a', 'z').build();
    AsciiBitmap alphaNum = digits.union(letters);

    assertThat(alphaNum.cardinality()).isEqualTo(36);
    assertThat(alphaNum.contains('5')).isTrue();
    assertThat(alphaNum.contains('x')).isTrue();
    assertThat(alphaNum.contains('A')).isFalse();
    assertThat(alphaNum.contains('@')).isFalse();
  }

  @Test
  void toBooleanArrayMatchesContains() {
    AsciiBitmap bitmap =
        new AsciiBitmap.Builder().add('a').add('z').addRange('0', '9').add('E').build();
    boolean[] array = bitmap.toBooleanArray();

    assertThat(array).hasSize(128);
    for (int cp = 0; cp < 128; cp++) {
      assertThat(array[cp]).isEqualTo(bitmap.contains(cp));
    }
  }
}
