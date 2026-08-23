// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("package-private HashChain tests exercise SafeRE internals")
class HashChainTest {

  @Test
  void compileReturnsNullForShortLiterals() {
    assertThat(HashChain.compile(null)).isNull();
    assertThat(HashChain.compile(new byte[0])).isNull();
    assertThat(HashChain.compile("a".getBytes(UTF_8))).isNull();
    assertThat(HashChain.compile("ab".getBytes(UTF_8))).isNull();
    assertThat(HashChain.compile("abc".getBytes(UTF_8))).isNull();
    assertThat(HashChain.compile("abcd".getBytes(UTF_8))).isNotNull();
  }

  @Test
  void exactMatchesFoundAcrossAlignmentsAndHaystacks() {
    List<String> literals =
        List.of("POST", "ERROR", "content-length", "abcdefghijklmnop", "coolfunctionname");
    for (String lit : literals) {
      byte[] needle = lit.getBytes(UTF_8);
      HashChain hc = HashChain.compile(needle);
      assertThat(hc).isNotNull();

      for (int prefixLen = 0; prefixLen <= 64; prefixLen++) {
        for (int suffixLen = 0; suffixLen <= 64; suffixLen++) {
          String text = "x".repeat(prefixLen) + lit + "y".repeat(suffixLen);
          byte[] haystack = text.getBytes(UTF_8);
          long limit = WorkLimit.forRemaining(haystack.length);

          int found = hc.search(haystack, 0, haystack.length, 0, limit);
          assertThat(found).as("Pattern %s in %s", lit, text).isEqualTo(prefixLen);
        }
      }
    }
  }

  @Test
  void nonMatchingTextReturnsNegativeOne() {
    byte[] needle = "ERROR".getBytes(UTF_8);
    HashChain hc = HashChain.compile(needle);
    assertThat(hc).isNotNull();

    byte[] haystack = "request completed normally without failure".getBytes(UTF_8);
    int found = hc.search(haystack, 0, haystack.length, 0, WorkLimit.forRemaining(haystack.length));
    assertThat(found).isEqualTo(-1);
  }

  @Test
  void adversarialCollisionsTripWorkLimitAndReturnSentinel() {
    byte[] needle = "cbabab".getBytes(UTF_8);
    HashChain hc = HashChain.compile(needle);
    assertThat(hc).isNotNull();

    // Repeated "ab" creates terminal 2-gram matches but prefix fails on "c"
    byte[] haystack = "ab".repeat(100).getBytes(UTF_8);
    int result =
        hc.search(haystack, 0, haystack.length, 0, WorkLimit.forRemaining(haystack.length));
    assertThat(result).isEqualTo(-2);
  }

  @Test
  void classHashChainCaseInsensitiveMatches() {
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
      String text = "Header: " + variant + " = 42";
      byte[] haystack = text.getBytes(UTF_8);
      int found =
          chc.search(haystack, 0, haystack.length, 0, WorkLimit.forRemaining(haystack.length));
      assertThat(found).as("Variant %s in %s", variant, text).isEqualTo(8);
    }
  }

  @Test
  void classHashChainReturnsNullForShortOrNonAscii() {
    assertThat(ClassHashChain.compileCaseInsensitive(null)).isNull();
    assertThat(ClassHashChain.compileCaseInsensitive("abc")).isNull();
    assertThat(ClassHashChain.compileCaseInsensitive("café")).isNull(); // Non-ASCII 'é'
  }

  @Test
  void classHashChainAdversarialCollisionsTripWorkLimit() {
    ClassHashChain chc = ClassHashChain.compileCaseInsensitive("cbabab");
    assertThat(chc).isNotNull();

    byte[] haystack = "aBaBaB".repeat(100).getBytes(UTF_8);
    int result =
        chc.search(haystack, 0, haystack.length, 0, WorkLimit.forRemaining(haystack.length));
    assertThat(result).isEqualTo(-2);
  }

  @Test
  void classHashChainCaseInsensitiveStringMatches() {
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
  void classHashChainCaseInsensitiveStringNonMatching() {
    ClassHashChain chc = ClassHashChain.compileCaseInsensitive("content-length");
    assertThat(chc).isNotNull();

    String text = "The quick brown fox jumps over the lazy dog.";
    int found = chc.search(text, 0, WorkLimit.forRemaining(text.length()));
    assertThat(found).isEqualTo(-1);
  }

  @Test
  void shiftAtReturnsZeroForMatchingTerminal2Gram() {
    ClassHashChain chc = ClassHashChain.compileCaseInsensitive("keyword_to_find");
    assertThat(chc).isNotNull();

    // "KEYWORD_TO_FIND" has terminal 2-gram "ND"
    assertThat(chc.shiftAt("KEYWORD_TO_FIND", 0)).isEqualTo(0);
    assertThat(chc.shiftAt("prefix_keyword_to_find", 7)).isEqualTo(0);
  }

  @Test
  void shiftAtReturnsPositiveShiftForMismatchedTerminal2Gram() {
    ClassHashChain chc = ClassHashChain.compileCaseInsensitive("keyword_to_find");
    assertThat(chc).isNotNull();

    // Candidate window [0..14] ends with "xy" -> not in pattern -> shifts 14
    assertThat(chc.shiftAt("k_other_words_xy_rest", 0)).isEqualTo(14);
  }

  @Test
  void shiftAtOutOfBoundsReturnsZero() {
    ClassHashChain chc = ClassHashChain.compileCaseInsensitive("keyword_to_find");
    assertThat(chc).isNotNull();
    assertThat(chc.shiftAt("short", 0)).isEqualTo(0);
    assertThat(chc.shiftAt("keyword_to_find", 50)).isEqualTo(0);
  }

  @Test
  void periodicIdentical2GramsFoundAcrossHaystacks() {
    List<String> periodicLiterals = List.of("aaaa", "bbbbbb", "XXXXX", "11111111");
    for (String lit : periodicLiterals) {
      byte[] needle = lit.getBytes(UTF_8);
      HashChain hc = HashChain.compile(needle);
      assertThat(hc).isNotNull();

      String text = "yyy" + lit + "zzz";
      byte[] haystack = text.getBytes(UTF_8);
      int found =
          hc.search(haystack, 0, haystack.length, 0, WorkLimit.forRemaining(haystack.length));
      assertThat(found).isEqualTo(3);
    }
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

  @Test
  void classHashChain16CompileReturnsNullForShort() {
    assertThat(ClassHashChain16.compileCaseInsensitive(null)).isNull();
    assertThat(ClassHashChain16.compileCaseInsensitive("")).isNull();
    assertThat(ClassHashChain16.compileCaseInsensitive("абв")).isNull();
    assertThat(ClassHashChain16.compileCaseInsensitive("привет")).isNotNull();
  }

  @Test
  void classHashChain16MatchesCyrillicAcrossCasing() {
    String pattern = "привет_мир";
    ClassHashChain16 chc16 = ClassHashChain16.compileCaseInsensitive(pattern);
    assertThat(chc16).isNotNull();

    List<String> variants = List.of("привет_мир", "ПРИВЕТ_МИР", "Привет_Мир", "пРиВеТ_мИр");
    for (String variant : variants) {
      for (int prefixLen = 0; prefixLen <= 16; prefixLen++) {
        for (int suffixLen = 0; suffixLen <= 16; suffixLen++) {
          String text = "х".repeat(prefixLen) + variant + "у".repeat(suffixLen);
          int found = chc16.search(text, 0, WorkLimit.forRemaining(text.length()));
          assertThat(found).as("Variant %s in %s", variant, text).isEqualTo(prefixLen);
        }
      }
    }
  }

  @Test
  void classHashChain16MatchesGermanAndGreekAcrossCasing() {
    String germanPattern = "über_spitzen";
    ClassHashChain16 germanChc = ClassHashChain16.compileCaseInsensitive(germanPattern);
    assertThat(germanChc).isNotNull();

    List<String> germanVariants = List.of("über_spitzen", "ÜBER_SPITZEN", "Über_Spitzen");
    for (String variant : germanVariants) {
      String text = "Vor dem Text " + variant + " nach dem Text";
      int found = germanChc.search(text, 0, WorkLimit.forRemaining(text.length()));
      assertThat(found).isEqualTo(13);
    }

    String greekPattern = "αβγδεζηθ";
    ClassHashChain16 greekChc = ClassHashChain16.compileCaseInsensitive(greekPattern);
    assertThat(greekChc).isNotNull();
    assertThat(greekChc.search("Τεστ ΑΒΓΔΕΖΗΘ τέλος", 0, 1000)).isEqualTo(5);
  }

  @Test
  void classHashChain16SurrogatePairs() {
    String pattern = "👋🏼👋🏼👋🏼👋🏼";
    ClassHashChain16 chc16 = ClassHashChain16.compileCaseInsensitive(pattern);
    assertThat(chc16).isNotNull();

    String text = "Prefix 👋🏼👋🏼👋🏼👋🏼 Suffix";
    int found = chc16.search(text, 0, WorkLimit.forRemaining(text.length()));
    assertThat(found).isEqualTo(7);
  }

  @Test
  void classHashChain16AdversarialCollisionsTripWorkLimit() {
    ClassHashChain16 chc16 = ClassHashChain16.compileCaseInsensitive("сбабаб");
    assertThat(chc16).isNotNull();

    String haystack = "аБаБаБ".repeat(100);
    int result = chc16.search(haystack, 0, WorkLimit.forRemaining(haystack.length()));
    assertThat(result).isEqualTo(-2);
  }

  @Test
  void classHashChain16ShiftAtReturnsExpectedShifts() {
    ClassHashChain16 chc16 = ClassHashChain16.compileCaseInsensitive("конфигурация");
    assertThat(chc16).isNotNull();

    // Matching terminal 2-gram returns 0
    assertThat(chc16.shiftAt("КОНФИГУРАЦИЯ", 0)).isEqualTo(0);
    assertThat(chc16.shiftAt("префикс_конфигурация", 8)).isEqualTo(0);

    // Mismatched terminal 2-gram returns positive shift
    assertThat(chc16.shiftAt("к_другие_слова_хх_конец", 0)).isGreaterThan(0);
    assertThat(chc16.shiftAt("короткий", 50)).isEqualTo(0); // Out of bounds returns 0
  }
}
