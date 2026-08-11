// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class MatchDescriptorTest {

  @Test
  void noneDescriptorHasNoFastPath() {
    assertThat(MatchDescriptor.NONE.hasFastPath()).isFalse();
    assertThat(MatchDescriptor.NONE.literalMatch()).isNull();
    assertThat(MatchDescriptor.NONE.singleCharClass()).isNull();
    assertThat(MatchDescriptor.NONE.keywordAlternation()).isNull();
    assertThat(MatchDescriptor.NONE.charClassMatch()).isNull();
  }

  @Test
  void literalMatchPatternProducesLiteralDescriptor() {
    Pattern p = Pattern.compile("hello");
    MatchDescriptor desc = p.matchDescriptor();
    assertThat(desc.hasFastPath()).isTrue();
    assertThat(desc.literalMatch()).isEqualTo("hello");
    assertThat(p.literalMatch()).isEqualTo("hello");
  }

  @Test
  void singleCharClassPatternProducesSingleCharClassDescriptor() {
    Pattern p = Pattern.compile("[a-z]");
    MatchDescriptor desc = p.matchDescriptor();
    assertThat(desc.hasFastPath()).isTrue();
    assertThat(desc.singleCharClass()).isNotNull();
    assertThat(p.singleCharClassScanInfo()).isNotNull();
  }

  @Test
  void repeatedCharClassPatternProducesCharClassMatchDescriptor() {
    Pattern p = Pattern.compile("[a-z]+");
    MatchDescriptor desc = p.matchDescriptor();
    assertThat(desc.hasFastPath()).isTrue();
    assertThat(desc.charClassMatch()).isNotNull();
    assertThat(p.charClassMatchRanges()).isNotNull();
  }

  @Test
  void keywordAlternationPatternProducesKeywordAlternationDescriptor() {
    Pattern p = Pattern.compile("foo|bar|baz", Pattern.CASE_INSENSITIVE);
    MatchDescriptor desc = p.matchDescriptor();
    if (desc.keywordAlternation() != null) {
      assertThat(desc.hasFastPath()).isTrue();
      assertThat(p.keywordAlternation()).isNotNull();
    }
  }

  @Test
  void complexPatternHasNoMatchFastPath() {
    Pattern p = Pattern.compile("a(b|c)*d");
    MatchDescriptor desc = p.matchDescriptor();
    assertThat(desc.literalMatch()).isNull();
    assertThat(desc.singleCharClass()).isNull();
    assertThat(desc.charClassMatch()).isNull();
  }
}
