// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TeddyTest {

  private static final String US_STATES =
      "AL|AK|AZ|AR|CA|CO|CT|DE|FL|GA|HI|ID|IL|IN|IA|KS|KY|LA|ME|MD|MA|MI|MN|MS|MO|MT|NE|NV|NH|NJ"
          + "|NM|NY|NC|ND|OH|OK|OR|PA|RI|SC|SD|TN|TX|UT|VT|VA|WA|WV|WI|WY";

  @Test
  void smallMultiLiteralStringMatch() {
    Pattern p = Pattern.compile("INFO|WARN|ERROR");
    String text = "2026-08-18 [WARN] system running normally, no ERROR reported";

    Matcher m = p.matcher(text);
    List<String> matches = new ArrayList<>();
    List<Integer> starts = new ArrayList<>();
    while (m.find()) {
      matches.add(m.group());
      starts.add(m.start());
    }

    assertThat(matches).containsExactly("WARN", "ERROR");
    assertThat(starts).containsExactly(12, 46);
  }

  @Test
  void smallMultiLiteralUtf16Match() {
    Pattern p = Pattern.compile("東京|大阪|京都|福岡");
    String text = "日本の都市: 東京と京都と大阪を訪問しました。";

    Matcher m = p.matcher(text);
    List<String> matches = new ArrayList<>();
    List<Integer> starts = new ArrayList<>();
    while (m.find()) {
      matches.add(m.group());
      starts.add(m.start());
    }

    assertThat(matches).containsExactly("東京", "京都", "大阪");
    assertThat(starts).containsExactly(7, 10, 13);
  }

  @Test
  void smallMultiLiteralUtf8Match() {
    Pattern p = Pattern.compile("cat|dog|bird");
    byte[] text = "the quick brown dog jumped over the lazy bird".getBytes(UTF_8);

    Utf8Matcher m = p.matcher(Utf8Input.validated(text));
    List<String> matches = new ArrayList<>();
    while (m.find()) {
      matches.add(new String(text, m.start(), m.end() - m.start(), UTF_8));
    }

    assertThat(matches).containsExactly("dog", "bird");
  }

  @Test
  void smallMultiLiteralAbsent() {
    Pattern p = Pattern.compile("true|false");
    String text = "none of the boolean values are present in this long sentence of text";

    Matcher m = p.matcher(text);
    assertThat(m.find()).isFalse();

    Utf8Matcher utf8Matcher = p.matcher(Utf8Input.validated(text.getBytes(UTF_8)));
    assertThat(utf8Matcher.find()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 14, 15, 16, 31, 32, 63, 64, 127, 128, 255})
  void smallMultiLiteralAtVariableOffsets(int padding) {
    Pattern p = Pattern.compile("INFO|WARN|ERROR");
    String pad = "x".repeat(padding);
    String text = pad + "ERROR" + pad + "WARN";

    Matcher m = p.matcher(text);
    assertThat(m.find()).isTrue();
    assertThat(m.start()).isEqualTo(padding);
    assertThat(m.group()).isEqualTo("ERROR");

    assertThat(m.find()).isTrue();
    assertThat(m.start()).isEqualTo(padding + 5 + padding);
    assertThat(m.group()).isEqualTo("WARN");

    assertThat(m.find()).isFalse();
  }

  @Test
  void teddyUsStatesMatch() {
    Pattern p = Pattern.compile(US_STATES);
    String text = "Packages shipped from CA and NY arrived in TX safely.";

    Matcher m = p.matcher(text);
    List<String> matches = new ArrayList<>();
    List<Integer> starts = new ArrayList<>();
    while (m.find()) {
      matches.add(m.group());
      starts.add(m.start());
    }

    assertThat(matches).containsExactly("CA", "NY", "TX");
    assertThat(starts).containsExactly(22, 29, 43);
  }

  @Test
  void teddyUsStatesUtf8Match() {
    Pattern p = Pattern.compile(US_STATES);
    byte[] text = "Orders processed in WA, OR, and NV yesterday.".getBytes(UTF_8);

    Utf8Matcher m = p.matcher(Utf8Input.validated(text));
    List<String> matches = new ArrayList<>();
    while (m.find()) {
      matches.add(new String(text, m.start(), m.end() - m.start(), UTF_8));
    }

    assertThat(matches).containsExactly("WA", "OR", "NV");
  }

  @Test
  void teddyHttpVerbsLongText() {
    Pattern p = Pattern.compile("GET|POST|PUT|DELETE|HEAD|OPTIONS|PATCH|TRACE");
    String text = "200 OK after receiving POST /api/v1/resource followed by GET /api/v1/resource";

    Matcher m = p.matcher(text);
    List<String> matches = new ArrayList<>();
    while (m.find()) {
      matches.add(m.group());
    }

    assertThat(matches).containsExactly("POST", "GET");
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 15, 16, 31, 32, 63, 64, 127, 128, 255})
  void teddyMatchAtVariableOffsets(int padding) {
    Pattern p = Pattern.compile("GET|POST|PUT|DELETE|HEAD|OPTIONS|PATCH|TRACE");
    String pad = "-".repeat(padding);
    String text = pad + "DELETE" + pad + "POST";

    Matcher m = p.matcher(text);
    assertThat(m.find()).isTrue();
    assertThat(m.start()).isEqualTo(padding);
    assertThat(m.group()).isEqualTo("DELETE");

    assertThat(m.find()).isTrue();
    assertThat(m.start()).isEqualTo(padding + 6 + padding);
    assertThat(m.group()).isEqualTo("POST");

    assertThat(m.find()).isFalse();

    byte[] bytes = text.getBytes(UTF_8);
    Utf8Matcher utf8Matcher = p.matcher(Utf8Input.validated(bytes));
    assertThat(utf8Matcher.find()).isTrue();
    assertThat(utf8Matcher.start()).isEqualTo(padding);
    assertThat(utf8Matcher.find()).isTrue();
    assertThat(utf8Matcher.start()).isEqualTo(padding + 6 + padding);
    assertThat(utf8Matcher.find()).isFalse();
  }

  @Test
  void teddyAbsentLongText() {
    Pattern p = Pattern.compile(US_STATES);
    String text = "abcdefghijklmnopqrstuvwxyz ".repeat(100);

    Matcher m = p.matcher(text);
    assertThat(m.find()).isFalse();

    Utf8Matcher utf8Matcher = p.matcher(Utf8Input.validated(text.getBytes(UTF_8)));
    assertThat(utf8Matcher.find()).isFalse();
  }

  @Test
  void teddyEquivalenceWithJdk() {
    String[] testPatterns = {
      "cat|dog|bird",
      "INFO|WARN|ERROR",
      "GET|POST|PUT|DELETE|HEAD|OPTIONS|PATCH",
      "foo|foobar|foot|foothold|football",
      US_STATES
    };

    String[] testInputs = {
      "hello world CA and NY are states",
      "POST /index.html HTTP/1.1",
      "nothing matching here whatsoever",
      "cat dog bird",
      "12345 ERROR at line 42 with WARN warning",
      "playing football on foot with a foothold"
    };

    for (String pat : testPatterns) {
      Pattern safeRe = Pattern.compile(pat);
      java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(pat);

      for (String input : testInputs) {
        Matcher m1 = safeRe.matcher(input);
        java.util.regex.Matcher m2 = jdk.matcher(input);

        while (m1.find()) {
          assertThat(m2.find()).isTrue();
          assertThat(m1.group()).isEqualTo(m2.group());
          assertThat(m1.start()).isEqualTo(m2.start());
          assertThat(m1.end()).isEqualTo(m2.end());
        }
        assertThat(m2.find()).isFalse();
      }
    }
  }

  @Test
  void teddyUtf16NonLatin1StringMatch() {
    Pattern p = Pattern.compile("GET|POST|PUT|DELETE|HEAD|OPTIONS|PATCH|TRACE");
    // Non-Latin-1 string (UTF-16 coder = 1) containing Japanese text and emojis
    String text = "リクエストログ: POST /api/v1/user 📦 レスポンス受信後に GET /api/v1/user 🚀 完了";

    Matcher m = p.matcher(text);
    List<String> matches = new ArrayList<>();
    List<Integer> starts = new ArrayList<>();
    while (m.find()) {
      matches.add(m.group());
      starts.add(m.start());
    }

    assertThat(matches).containsExactly("POST", "GET");
    assertThat(starts).containsExactly(9, 40);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 15, 16, 31, 32, 63, 64, 127, 128})
  void teddyUtf16WithNonAsciiPadding(int padding) {
    Pattern p = Pattern.compile(US_STATES);
    String nonAsciiPad = "あ".repeat(padding);
    String text = nonAsciiPad + "CA" + nonAsciiPad + "NY" + nonAsciiPad + "TX";

    Matcher m = p.matcher(text);
    assertThat(m.find()).isTrue();
    assertThat(m.start()).isEqualTo(padding);
    assertThat(m.group()).isEqualTo("CA");

    assertThat(m.find()).isTrue();
    assertThat(m.start()).isEqualTo(padding + 2 + padding);
    assertThat(m.group()).isEqualTo("NY");

    assertThat(m.find()).isTrue();
    assertThat(m.start()).isEqualTo(padding + 2 + padding + 2 + padding);
    assertThat(m.group()).isEqualTo("TX");

    assertThat(m.find()).isFalse();
  }

  @Test
  void teddyUtf16AbsentWithNonAsciiText() {
    Pattern p = Pattern.compile(US_STATES);
    String text = "こんにちは世界！これは日本語のテキストです。".repeat(50);

    Matcher m = p.matcher(text);
    assertThat(m.find()).isFalse();
  }
}
