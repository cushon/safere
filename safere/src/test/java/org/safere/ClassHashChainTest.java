// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("package-private ClassHashChain tests exercise SafeRE internals")
class ClassHashChainTest {

  @Test
  void classHashChainReturnsNullForShort() {
    assertThat(ClassHashChain.compileCaseInsensitive(null)).isNull();
    assertThat(ClassHashChain.compileCaseInsensitive("")).isNull();
    assertThat(ClassHashChain.compileCaseInsensitive("abc")).isNull();
    assertThat(ClassHashChain.compileCaseInsensitive("абв")).isNull();
  }

  @Test
  void classHashChainCaseInsensitiveAsciiMatches() {
    String pattern = "content-length";
    ClassHashChain chc = ClassHashChain.compileCaseInsensitive(pattern);
    assertThat(chc).isNotNull();

    List<String> variants =
        List.of(
            "content-length",
            "CONTENT-LENGTH",
            "Content-Length",
            "CoNtEnT-LeNgTh",
            "cOnTeNt-lEnGtH");

    for (String variant : variants) {
      for (int prefixLen = 0; prefixLen <= 32; prefixLen++) {
        for (int suffixLen = 0; suffixLen <= 32; suffixLen++) {
          String text = "x".repeat(prefixLen) + variant + "y".repeat(suffixLen);
          int found = chc.search(text, 0, WorkLimit.forRemaining(text.length()));
          assertThat(found).as("Variant %s in %s", variant, text).isEqualTo(prefixLen);
        }
      }
    }
  }

  @Test
  void classHashChainCaseInsensitiveAsciiNonMatching() {
    ClassHashChain chc = ClassHashChain.compileCaseInsensitive("content-length");
    assertThat(chc).isNotNull();

    String text = "The quick brown fox jumps over the lazy dog.";
    int found = chc.search(text, 0, WorkLimit.forRemaining(text.length()));
    assertThat(found).isEqualTo(-1);
  }

  @Test
  void classHashChainMatchesCyrillicAcrossCasing() {
    String pattern = "привет_мир";
    ClassHashChain chc = ClassHashChain.compileCaseInsensitive(pattern);
    assertThat(chc).isNotNull();

    List<String> variants = List.of("привет_мир", "ПРИВЕТ_МИР", "Привет_Мир", "пРиВеТ_мИр");
    for (String variant : variants) {
      for (int prefixLen = 0; prefixLen <= 16; prefixLen++) {
        for (int suffixLen = 0; suffixLen <= 16; suffixLen++) {
          String text = "х".repeat(prefixLen) + variant + "у".repeat(suffixLen);
          int found = chc.search(text, 0, WorkLimit.forRemaining(text.length()));
          assertThat(found).as("Variant %s in %s", variant, text).isEqualTo(prefixLen);
        }
      }
    }
  }

  @Test
  void classHashChainMatchesGermanAndGreekAcrossCasing() {
    String germanPattern = "über_spitzen";
    ClassHashChain germanChc = ClassHashChain.compileCaseInsensitive(germanPattern);
    assertThat(germanChc).isNotNull();

    List<String> germanVariants = List.of("über_spitzen", "ÜBER_SPITZEN", "Über_Spitzen");
    for (String variant : germanVariants) {
      String text = "Vor dem Text " + variant + " nach dem Text";
      int found = germanChc.search(text, 0, WorkLimit.forRemaining(text.length()));
      assertThat(found).isEqualTo(13);
    }

    String greekPattern = "αβγδεζηθ";
    ClassHashChain greekChc = ClassHashChain.compileCaseInsensitive(greekPattern);
    assertThat(greekChc).isNotNull();
    assertThat(greekChc.search("Τεστ ΑΒΓΔΕΖΗΘ τέλος", 0, 1000)).isEqualTo(5);
  }

  @Test
  void classHashChainSurrogatePairs() {
    String pattern = "👋🏼👋🏼👋🏼👋🏼";
    ClassHashChain chc = ClassHashChain.compileCaseInsensitive(pattern);
    assertThat(chc).isNotNull();

    String text = "Prefix 👋🏼👋🏼👋🏼👋🏼 Suffix";
    int found = chc.search(text, 0, WorkLimit.forRemaining(text.length()));
    assertThat(found).isEqualTo(7);
  }

  @Test
  void classHashChainMatchesSupplementaryCaseFolds() {
    String upper = new String(Character.toChars(0x10400)).repeat(4);
    String lower = new String(Character.toChars(0x10428)).repeat(4);
    ClassHashChain chain = ClassHashChain.compileCaseInsensitive(upper);
    assertThat(chain).isNotNull();
    assertThat(chain.search(lower, 0, 100)).isZero();

    Matcher matcher = Pattern.compile("(?iu)" + upper).matcher(lower);
    assertThat(matcher.find()).isTrue();
  }

  @Test
  void classHashChainAdversarialCollisionsTripWorkLimit() {
    ClassHashChain chc = ClassHashChain.compileCaseInsensitive("cbabab");
    assertThat(chc).isNotNull();

    String haystack = "aBaBaB".repeat(100);
    int result = chc.search(haystack, 0, WorkLimit.forRemaining(haystack.length()));
    assertThat(result).isEqualTo(-2);

    ClassHashChain cyrillicChc = ClassHashChain.compileCaseInsensitive("сбабаб");
    assertThat(cyrillicChc).isNotNull();

    String cyrillicHaystack = "аБаБаБ".repeat(100);
    int cyrillicResult =
        cyrillicChc.search(cyrillicHaystack, 0, WorkLimit.forRemaining(cyrillicHaystack.length()));
    assertThat(cyrillicResult).isEqualTo(-2);
  }

  @Test
  void shiftAtReturnsZeroForMatchingTerminal2Gram() {
    ClassHashChain chc = ClassHashChain.compileCaseInsensitive("keyword_to_find");
    assertThat(chc).isNotNull();

    // "KEYWORD_TO_FIND" has terminal 2-gram "ND"
    assertThat(chc.shiftAt("KEYWORD_TO_FIND", 0)).isEqualTo(0);
    assertThat(chc.shiftAt("prefix_keyword_to_find", 7)).isEqualTo(0);

    ClassHashChain cyrillicChc = ClassHashChain.compileCaseInsensitive("конфигурация");
    assertThat(cyrillicChc).isNotNull();
    assertThat(cyrillicChc.shiftAt("КОНФИГУРАЦИЯ", 0)).isEqualTo(0);
    assertThat(cyrillicChc.shiftAt("префикс_конфигурация", 8)).isEqualTo(0);
  }

  @Test
  void shiftAtReturnsPositiveShiftForMismatchedTerminal2Gram() {
    ClassHashChain chc = ClassHashChain.compileCaseInsensitive("keyword_to_find");
    assertThat(chc).isNotNull();

    // Candidate window [0..14] ends with "xy" -> not in pattern -> shifts 14
    assertThat(chc.shiftAt("k_other_words_xy_rest", 0)).isEqualTo(14);

    ClassHashChain cyrillicChc = ClassHashChain.compileCaseInsensitive("конфигурация");
    assertThat(cyrillicChc).isNotNull();
    assertThat(cyrillicChc.shiftAt("к_другие_слова_хх_конец", 0)).isGreaterThan(0);
  }

  @Test
  void shiftAtOutOfBoundsReturnsZero() {
    ClassHashChain chc = ClassHashChain.compileCaseInsensitive("keyword_to_find");
    assertThat(chc).isNotNull();
    assertThat(chc.shiftAt("short", 0)).isEqualTo(0);
    assertThat(chc.shiftAt("keyword_to_find", 50)).isEqualTo(0);
  }

  @Test
  void stringCaseInsensitiveSearchFallsBackAfterWorkLimitExhaustion() {
    String literal = "c" + "ab".repeat(20);
    String prefix = "ab".repeat(10_000);
    Matcher matcher =
        Pattern.compile("(?i)c(?:ab){20}").matcher(prefix + literal.toUpperCase(Locale.ROOT));

    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(prefix.length());
  }

  @Test
  void repeatedFindAdvancesCorrectlyOnStringWithClassHashChain() {
    Pattern pattern = Pattern.compile("(?i)apple_pie");
    String text = "apple_pie and APPLE_PIE and Apple_Pie!";
    Matcher matcher = pattern.matcher(text);

    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(0);
    assertThat(matcher.group()).isEqualTo("apple_pie");

    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(14);
    assertThat(matcher.group()).isEqualTo("APPLE_PIE");

    assertThat(matcher.find()).isTrue();
    assertThat(matcher.start()).isEqualTo(28);
    assertThat(matcher.group()).isEqualTo("Apple_Pie");

    assertThat(matcher.find()).isFalse();
  }
}
