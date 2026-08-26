// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.safere.MultiAnchorDescriptor.Anchor;
import org.safere.MultiAnchorDescriptor.GapKind;

class MultiAnchorMatchTest {

  @Nested
  @DisplayName("Descriptor Extraction Tests")
  class DescriptorExtractionTests {

    @Test
    @DisplayName("Extracts 2-anchor descriptor correctly")
    void testTwoAnchorDescriptor() {
      Pattern p = Pattern.compile("foo.*bar");
      assertThat(p.multiAnchorDescriptor()).isNotNull();
      assertThat(p.multiAnchorDescriptor().anchors()).hasSize(2);
      assertThat(p.multiAnchorDescriptor().anchors()[0].literal()).isEqualTo("foo");
      assertThat(p.multiAnchorDescriptor().anchors()[1].literal()).isEqualTo("bar");
      assertThat(p.multiAnchorDescriptor().isStartAnchored()).isFalse();
      assertThat(p.multiAnchorDescriptor().isEndAnchored()).isFalse();
      assertThat(p.multiAnchorDescriptor().gapBetween(0, 1).kind())
          .isEqualTo(GapKind.SINGLE_LINE_ANY_STAR);
    }

    @Test
    @DisplayName("Extracts anchored descriptor with bounded repetitions")
    void testAnchoredBoundedDescriptor() {
      Pattern p = Pattern.compile("^START[0-9]{2,5}END$");
      assertThat(p.multiAnchorDescriptor()).isNotNull();
      assertThat(p.multiAnchorDescriptor().isStartAnchored()).isTrue();
      assertThat(p.multiAnchorDescriptor().isEndAnchored()).isTrue();
      assertThat(p.multiAnchorDescriptor().gapBetween(0, 1).minLength()).isEqualTo(2);
      assertThat(p.multiAnchorDescriptor().gapBetween(0, 1).maxLength()).isEqualTo(5);
      assertThat(p.multiAnchorDescriptor().gapBetween(0, 1).kind())
          .isEqualTo(GapKind.BOUNDED_CLASS_REPEAT);
    }

    @Test
    @DisplayName("Rejects patterns with unsupported structures from multi-anchor descriptor")
    void testUnsupportedStructures() {
      Pattern pNoLiteral = Pattern.compile("[a-z]+.*[0-9]+");
      assertThat(pNoLiteral.multiAnchorDescriptor()).isNull();
    }

    @Test
    @DisplayName("Extracts case-insensitive multi-anchor descriptor")
    void testCaseInsensitiveDescriptor() {
      Pattern pCaseInsensitive = Pattern.compile("foo.*bar", Pattern.CASE_INSENSITIVE);
      assertThat(pCaseInsensitive.multiAnchorDescriptor()).isNotNull();
      assertThat(pCaseInsensitive.multiAnchorDescriptor().anchors()[0].foldCase()).isTrue();
      assertThat(pCaseInsensitive.multiAnchorDescriptor().anchors()[1].foldCase()).isTrue();
    }
  }

  @Nested
  @DisplayName("Two-anchor patterns")
  class TwoAnchorTests {

    @Test
    @DisplayName("Basic matches() and find() with greedy .*")
    void testBasicGreedy() {
      Pattern p = Pattern.compile("foo.*bar");
      Matcher m = p.matcher("prefix foo 123 bar suffix");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(7);
      assertThat(m.end()).isEqualTo(18);
      assertThat(m.group()).isEqualTo("foo 123 bar");

      Matcher fullMatcher = p.matcher("foo 123 bar");
      assertThat(fullMatcher.matches()).isTrue();
      assertThat(fullMatcher.group()).isEqualTo("foo 123 bar");
    }

    @Test
    @DisplayName("Greedy vs lazy matching with multiple occurrences")
    void testGreedyVsLazy() {
      Pattern pGreedy = Pattern.compile("foo.*bar");
      Matcher mGreedy = pGreedy.matcher("foo 1 bar 2 bar");
      assertThat(mGreedy.find()).isTrue();
      assertThat(mGreedy.group()).isEqualTo("foo 1 bar 2 bar");
      assertThat(mGreedy.start()).isEqualTo(0);
      assertThat(mGreedy.end()).isEqualTo(15);

      Pattern pLazy = Pattern.compile("foo.*?bar");
      Matcher mLazy = pLazy.matcher("foo 1 bar 2 bar");
      assertThat(mLazy.find()).isTrue();
      assertThat(mLazy.group()).isEqualTo("foo 1 bar");
      assertThat(mLazy.start()).isEqualTo(0);
      assertThat(mLazy.end()).isEqualTo(9);
    }

    @Test
    @DisplayName("Start-anchored and end-anchored variations")
    void testAnchors() {
      Pattern pBoth = Pattern.compile("^foo.*bar$");
      assertThat(pBoth.matcher("foo 123 bar").matches()).isTrue();
      assertThat(pBoth.matcher("foo 123 bar").find()).isTrue();
      assertThat(pBoth.matcher("x foo 123 bar").find()).isFalse();
      assertThat(pBoth.matcher("foo 123 bar x").find()).isFalse();

      Pattern pStart = Pattern.compile("^foo.*bar");
      assertThat(pStart.matcher("foo 123 bar extra").find()).isTrue();
      assertThat(pStart.matcher("x foo 123 bar extra").find()).isFalse();

      Pattern pEnd = Pattern.compile("foo.*bar$");
      assertThat(pEnd.matcher("prefix foo 123 bar").find()).isTrue();
      assertThat(pEnd.matcher("prefix foo 123 bar extra").find()).isFalse();
    }

    @Test
    @DisplayName("lookingAt() behavior")
    void testLookingAt() {
      Pattern p = Pattern.compile("foo.*bar");
      assertThat(p.matcher("foo 123 bar suffix").lookingAt()).isTrue();
      assertThat(p.matcher("prefix foo 123 bar").lookingAt()).isFalse();
    }
  }

  @Nested
  @DisplayName("Three and more anchor patterns")
  class MultiAnchorTests {

    @Test
    @DisplayName("Three anchors with wildcards")
    void testThreeAnchors() {
      Pattern p = Pattern.compile("START.*MID.*END");
      Matcher m = p.matcher("head START 111 MID 222 END tail");
      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(5);
      assertThat(m.end()).isEqualTo(26);
      assertThat(m.group()).isEqualTo("START 111 MID 222 END");
    }

    @Test
    @DisplayName("Three anchors with character class gaps")
    void testClassGaps() {
      Pattern p = Pattern.compile("foo[a-z]+bar\\d+baz");
      Matcher m1 = p.matcher("fooabcbar123baz");
      assertThat(m1.matches()).isTrue();
      assertThat(m1.group()).isEqualTo("fooabcbar123baz");

      Matcher m2 = p.matcher("foo123bar123baz");
      assertThat(m2.matches()).isFalse();

      Matcher m3 = p.matcher("fooabcbarabcbaz");
      assertThat(m3.matches()).isFalse();
    }

    @Test
    @DisplayName("Whitespace and word gaps")
    void testWhitespaceGaps() {
      Pattern p = Pattern.compile("key\\s*:\\s*val");
      Matcher m = p.matcher("data: key : val ;");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("key : val");
      assertThat(m.start()).isEqualTo(6);
      assertThat(m.end()).isEqualTo(15);
    }
  }

  @Nested
  @DisplayName("Newline and DOTALL semantics")
  class MultilineTests {

    @Test
    @DisplayName("Non-DOTALL rejects newlines in .* gap")
    void testNonDotall() {
      Pattern p = Pattern.compile("foo.*bar");
      Matcher m = p.matcher("foo\nbar");
      assertThat(m.find()).isFalse();
      assertThat(m.matches()).isFalse();
    }

    @Test
    @DisplayName("DOTALL accepts newlines in .* gap")
    void testDotall() {
      Pattern p = Pattern.compile("(?s)foo.*bar");
      Matcher m = p.matcher("foo\nbar");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("foo\nbar");
      assertThat(m.matches()).isTrue();
    }
  }

  @Nested
  @DisplayName("Iterative find() calls")
  class IterativeFindTests {

    @Test
    @DisplayName("Successive find() across distinct matches")
    void testMultipleMatches() {
      Pattern p = Pattern.compile("<<.*?>>");
      Matcher m = p.matcher("first <<one>> middle <<two>> last <<three>>");

      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("<<one>>");
      assertThat(m.start()).isEqualTo(6);
      assertThat(m.end()).isEqualTo(13);

      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("<<two>>");
      assertThat(m.start()).isEqualTo(21);
      assertThat(m.end()).isEqualTo(28);

      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("<<three>>");
      assertThat(m.start()).isEqualTo(34);
      assertThat(m.end()).isEqualTo(43);

      assertThat(m.find()).isFalse();
    }
  }

  @Nested
  @DisplayName("UTF-8 scanner input")
  class Utf8ScannerTests {

    @Test
    @DisplayName("UTF-8 scanner matches multi-anchor patterns")
    void testUtf8Scanner() {
      Pattern p = Pattern.compile("alpha.*beta.*gamma");
      byte[] bytes = "xx alpha 123 beta 456 gamma yy".getBytes(UTF_8);
      Utf8InputScanner scanner = new Utf8InputScanner(bytes, 0, bytes.length);
      Matcher m = new Matcher(p, scanner);

      assertThat(m.find()).isTrue();
      assertThat(m.start()).isEqualTo(3);
      assertThat(m.end()).isEqualTo(27);
    }

    @Test
    @DisplayName("Multibyte UTF-8 literals in anchors")
    void testMultibyteUtf8() {
      Pattern p = Pattern.compile("開始.*中間.*終了");
      String input = "前置 開始 -- 123 -- 中間 -- 456 -- 終了 後置";
      Matcher m = p.matcher(input);

      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("開始 -- 123 -- 中間 -- 456 -- 終了");
    }
  }

  @Nested
  @DisplayName("Parity against java.util.regex")
  class ParityTests {

    @ParameterizedTest
    @CsvSource({
      "'foo.*bar', 'foo123bar'",
      "'foo.*bar', 'foo bar baz bar'",
      "'^foo.*bar$', 'foo123bar'",
      "'foo[a-z]+bar', 'fooabcdefbar'",
      "'foo\\d+bar', 'foo987654bar'",
      "'foo.*bar.*baz', 'foo11bar22baz'",
      "'(?s)foo.*bar', 'foo\\nbar'",
      "'foo.*bar', 'foo\\nbar'",
      "'foo.*bar', 'no match here'",
      "'foo.*bar', 'foo without ending'",
      "'foo.*bar', 'bar without start'",
      "'foo.*bar', ''",
      "'a.*?b.*?c', 'xx a 1 b 2 c yy a 3 b 4 c zz'",
      "'header:\\s*\\w+;', 'body header: val1; header: val2; end'"
    })
    @DisplayName("Verify find() and matches() equivalence with java.util.regex")
    void testParity(String regex, String input) {
      String actualInput = input.replace("\\n", "\n");
      Pattern p = Pattern.compile(regex);
      java.util.regex.Pattern jp = java.util.regex.Pattern.compile(regex);

      Matcher m = p.matcher(actualInput);
      java.util.regex.Matcher jm = jp.matcher(actualInput);

      boolean matchFound = m.find();
      boolean jMatchFound = jm.find();

      assertThat(matchFound)
          .withFailMessage("find() mismatch for regex '%s' on '%s'", regex, actualInput)
          .isEqualTo(jMatchFound);

      if (matchFound) {
        assertThat(m.start()).isEqualTo(jm.start());
        assertThat(m.end()).isEqualTo(jm.end());
        assertThat(m.group()).isEqualTo(jm.group());
      }

      m.reset();
      jm.reset();
      boolean matchesResult = m.matches();
      boolean jMatchesResult = jm.matches();

      assertThat(matchesResult)
          .withFailMessage("matches() mismatch for regex '%s' on '%s'", regex, actualInput)
          .isEqualTo(jMatchesResult);

      if (matchesResult) {
        assertThat(m.group()).isEqualTo(jm.group());
      }
    }
  }

  @Nested
  @DisplayName("Case-insensitive multi-anchor patterns")
  class CaseInsensitiveMultiAnchorTests {

    @Test
    @DisplayName("Matches case-insensitive multi-anchor patterns on String")
    void testCaseInsensitiveString() {
      Pattern p = Pattern.compile("(?i)get\\s+http");
      assertThat(p.multiAnchorDescriptor()).isNotNull();
      assertThat(p.multiAnchorDescriptor().anchors()).hasSize(2);
      assertThat(p.multiAnchorDescriptor().anchors()[0].foldCase()).isTrue();
      assertThat(p.multiAnchorDescriptor().anchors()[1].foldCase()).isTrue();

      Matcher m = p.matcher("prefix GET   HTTP suffix");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("GET   HTTP");
      assertThat(m.start()).isEqualTo(7);
      assertThat(m.end()).isEqualTo(17);

      Matcher m2 = p.matcher("prefix get  http suffix");
      assertThat(m2.find()).isTrue();
      assertThat(m2.group()).isEqualTo("get  http");

      Matcher m3 = p.matcher("prefix post 123 http suffix");
      assertThat(m3.find()).isFalse();
    }

    @Test
    @DisplayName("Matches case-insensitive multi-anchor patterns on UTF-8 scanner")
    void testCaseInsensitiveUtf8() {
      Pattern p = Pattern.compile("(?i)content-type:[a-z/]+");
      assertThat(p.multiAnchorDescriptor()).isNotNull();

      byte[] b1 = "Header: val\r\nContent-Type:text/html\r\n".getBytes(UTF_8);
      Utf8Matcher m = p.matcher(Utf8Input.validated(b1));
      assertThat(m.find()).isTrue();
      assertThat(new String(b1, m.start(), m.end() - m.start(), UTF_8))
          .isEqualTo("Content-Type:text/html");

      byte[] b2 = "CONTENT-TYPE:application/json".getBytes(UTF_8);
      Utf8Matcher m2 = p.matcher(Utf8Input.validated(b2));
      assertThat(m2.find()).isTrue();
      assertThat(new String(b2, m2.start(), m2.end() - m2.start(), UTF_8))
          .isEqualTo("CONTENT-TYPE:application/json");
    }

    @ParameterizedTest
    @CsvSource({
      "'(?i)foo.*bar', 'FOO 123 BAR'",
      "'(?i)foo.*bar', 'Foo abc Bar'",
      "'(?i)start\\d{2,4}end', 'START99END'",
      "'(?i)start\\d{2,4}end', 'start1234end'",
      "'(?i)alpha\\s+beta', 'ALPHA   BETA'",
      "'(?i)alpha\\s+beta', 'Alpha beta'"
    })
    @DisplayName("Crosscheck case-insensitive multi-anchor patterns with JDK")
    void testCaseInsensitiveCrosscheck(String regex, String actualInput) {
      Pattern p = Pattern.compile(regex);
      java.util.regex.Pattern jp = java.util.regex.Pattern.compile(regex);

      Matcher m = p.matcher(actualInput);
      java.util.regex.Matcher jm = jp.matcher(actualInput);

      boolean matchFound = m.find();
      boolean jMatchFound = jm.find();

      assertThat(matchFound)
          .withFailMessage("find() mismatch for regex '%s' on '%s'", regex, actualInput)
          .isEqualTo(jMatchFound);

      if (matchFound) {
        assertThat(m.start()).isEqualTo(jm.start());
        assertThat(m.end()).isEqualTo(jm.end());
        assertThat(m.group()).isEqualTo(jm.group());
      }
    }
  }

  @Nested
  @DisplayName("Alternation Multi-Anchor Tests")
  class AlternationMultiAnchorTests {

    @Test
    @DisplayName("Extracts alternation anchor descriptor correctly")
    void testAlternationDescriptorExtraction() {
      Pattern p = Pattern.compile("(GET|POST|PUT|DELETE)\\s+HTTP");
      assertThat(p.multiAnchorDescriptor()).isNotNull();
      assertThat(p.multiAnchorDescriptor().anchors()).hasSize(2);
      assertThat(p.multiAnchorDescriptor().anchors()[0]).isInstanceOf(Anchor.Alternation.class);
      Anchor.Alternation alt = (Anchor.Alternation) p.multiAnchorDescriptor().anchors()[0];
      assertThat(alt.literals()).containsExactly("GET", "POST", "PUT", "DELETE");
      assertThat(p.multiAnchorDescriptor().anchors()[1]).isInstanceOf(Anchor.Single.class);
      assertThat(p.multiAnchorDescriptor().anchors()[1].literal()).isEqualTo("HTTP");
    }

    @Test
    @DisplayName("Matches multi-verb HTTP log pattern on strings")
    void testMultiVerbHttpLogMatching() {
      Pattern p = Pattern.compile("(GET|POST|PUT|DELETE)\\s+HTTP");

      Matcher m1 = p.matcher("Client: 10.0.0.1 - GET HTTP/1.1");
      assertThat(m1.find()).isTrue();
      assertThat(m1.group()).isEqualTo("GET HTTP");

      Matcher m2 = p.matcher("Client: 10.0.0.1 - POST HTTP/2.0");
      assertThat(m2.find()).isTrue();
      assertThat(m2.group()).isEqualTo("POST HTTP");

      Matcher m3 = p.matcher("Client: 10.0.0.1 - PATCH HTTP/2.0");
      assertThat(m3.find()).isFalse();
    }

    @Test
    @DisplayName("Matches pattern with alternation as intermediate anchor")
    void testIntermediateAlternationAnchor() {
      Pattern p = Pattern.compile("prefix:\\s*(cat|dog|fish)\\s*suffix");

      Matcher m1 = p.matcher("prefix:   dog   suffix");
      assertThat(m1.find()).isTrue();
      assertThat(m1.group()).isEqualTo("prefix:   dog   suffix");

      Matcher m2 = p.matcher("prefix:   fish   suffix");
      assertThat(m2.find()).isTrue();
      assertThat(m2.group()).isEqualTo("prefix:   fish   suffix");

      Matcher m3 = p.matcher("prefix:   bird   suffix");
      assertThat(m3.find()).isFalse();
    }

    @Test
    @DisplayName("Matches alternation multi-anchor on UTF-8 scanner inputs")
    void testUtf8ScannerAlternation() {
      Pattern p = Pattern.compile("(GET|POST|PUT)\\s+[a-z0-9]+\\s+HTTP");

      byte[] b1 = "req: GET data123 HTTP/1.1\n".getBytes(UTF_8);
      Utf8Matcher m1 = p.matcher(Utf8Input.validated(b1));
      assertThat(m1.find()).isTrue();
      assertThat(new String(b1, m1.start(), m1.end() - m1.start(), UTF_8))
          .isEqualTo("GET data123 HTTP");

      byte[] b2 = "req: POST test99 HTTP/1.1\n".getBytes(UTF_8);
      Utf8Matcher m2 = p.matcher(Utf8Input.validated(b2));
      assertThat(m2.find()).isTrue();
      assertThat(new String(b2, m2.start(), m2.end() - m2.start(), UTF_8))
          .isEqualTo("POST test99 HTTP");
    }

    @Test
    @DisplayName("Matches case-insensitive alternation multi-anchor patterns")
    void testCaseInsensitiveAlternation() {
      Pattern p = Pattern.compile("(?i)(get|post)\\s+[a-z0-9]+\\s+http");

      Matcher m1 = p.matcher("GET test1234 HTTP");
      assertThat(m1.find()).isTrue();
      assertThat(m1.group()).isEqualTo("GET test1234 HTTP");

      Matcher m2 = p.matcher("Post hello HTTP");
      assertThat(m2.find()).isTrue();
      assertThat(m2.group()).isEqualTo("Post hello HTTP");
    }

    @Test
    @DisplayName("Matches patterns with word boundary gaps")
    void testWordBoundaryAssertions() {
      Pattern p = Pattern.compile("\\b(GET|POST).*HTTP");
      assertThat(p.multiAnchorDescriptor()).isNotNull();

      Matcher m1 = p.matcher("req: GET data HTTP");
      assertThat(m1.find()).isTrue();
      assertThat(m1.group()).isEqualTo("GET data HTTP");

      Matcher m2 = p.matcher("req: TARGET data HTTP");
      assertThat(m2.find()).isFalse();
    }

    @Test
    @DisplayName("Matches patterns with line boundary gaps")
    void testLineBoundaryAssertions() {
      Pattern p = Pattern.compile("(?m)^START.*END$");
      assertThat(p.multiAnchorDescriptor()).isNotNull();

      Matcher m = p.matcher("header\nSTART body END\nfooter");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo("START body END");
    }

    @ParameterizedTest
    @CsvSource({
      "'(GET|POST|PUT)\\s+HTTP', 'GET HTTP'",
      "'(GET|POST|PUT)\\s+HTTP', 'POST HTTP'",
      "'(GET|POST|PUT)\\s+HTTP', 'PUT HTTP'",
      "'(GET|POST|PUT)\\s+HTTP', 'PATCH HTTP'",
      "'prefix:\\s*(cat|dog|fish)\\s*suffix', 'prefix: dog suffix'",
      "'prefix:\\s*(cat|dog|fish)\\s*suffix', 'prefix: fish suffix'",
      "'prefix:\\s*(cat|dog|fish)\\s*suffix', 'prefix: lion suffix'",
      "'(?i)(alpha|beta)\\s+\\d{2,4}\\s+omega', 'ALPHA 123 OMEGA'",
      "'(?i)(alpha|beta)\\s+\\d{2,4}\\s+omega', 'Beta 4567 omega'",
      "'\\b(cat|dog)\\b.*end', 'cat in the hat end'",
      "'\\b(cat|dog)\\b.*end', 'scat in the hat end'"
    })
    @DisplayName("Crosscheck alternation multi-anchor patterns with JDK")
    void testAlternationCrosscheckWithJdk(String regex, String actualInput) {
      Pattern p = Pattern.compile(regex);
      java.util.regex.Pattern jp = java.util.regex.Pattern.compile(regex);

      Matcher m = p.matcher(actualInput);
      java.util.regex.Matcher jm = jp.matcher(actualInput);

      boolean matchFound = m.find();
      boolean jMatchFound = jm.find();

      assertThat(matchFound)
          .withFailMessage("find() mismatch for regex '%s' on '%s'", regex, actualInput)
          .isEqualTo(jMatchFound);

      if (matchFound) {
        assertThat(m.start()).isEqualTo(jm.start());
        assertThat(m.end()).isEqualTo(jm.end());
        assertThat(m.group()).isEqualTo(jm.group());
      }
    }
  }

  @Nested
  @DisplayName("Alternation Factorization Tests")
  class AlternationFactorizationTests {

    @Test
    @DisplayName("Factors out shared prefix across top-level URL alternations")
    void testTopLevelUrlFactorization() {
      Pattern p =
          Pattern.compile(
              "https://example\\.com/users\\?id=\\d+|https://example\\.com/orders\\?id=\\d+");
      assertThat(p.multiAnchorDescriptor()).isNotNull();
      assertThat(p.multiAnchorDescriptor().anchors()[0].literal())
          .isEqualTo("https://example.com/");

      Matcher m1 = p.matcher("link: https://example.com/users?id=123 end");
      assertThat(m1.find()).isTrue();
      assertThat(m1.group()).isEqualTo("https://example.com/users?id=123");

      Matcher m2 = p.matcher("link: https://example.com/orders?id=456 end");
      assertThat(m2.find()).isTrue();
      assertThat(m2.group()).isEqualTo("https://example.com/orders?id=456");

      Matcher m3 = p.matcher("link: https://example.com/products?id=789 end");
      assertThat(m3.find()).isFalse();
    }

    @Test
    @DisplayName("Factors nested alternations with shared prefixes and suffixes")
    void testNestedAlternationFactorization() {
      Pattern p = Pattern.compile("api/(?:getItemById|getDetailsById)\\.json");
      assertThat(p.multiAnchorDescriptor()).isNotNull();

      Matcher m1 = p.matcher("fetch api/getItemById.json now");
      assertThat(m1.find()).isTrue();
      assertThat(m1.group()).isEqualTo("api/getItemById.json");

      Matcher m2 = p.matcher("fetch api/getDetailsById.json now");
      assertThat(m2.find()).isTrue();
      assertThat(m2.group()).isEqualTo("api/getDetailsById.json");

      Matcher m3 = p.matcher("fetch api/getOtherById.json now");
      assertThat(m3.find()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
      "'https://example\\.com/users\\?id=\\d+|https://example\\.com/orders\\?id=\\d+',"
          + " 'https://example.com/users?id=123'",
      "'https://example\\.com/users\\?id=\\d+|https://example\\.com/orders\\?id=\\d+',"
          + " 'https://example.com/orders?id=456'",
      "'https://example\\.com/users\\?id=\\d+|https://example\\.com/orders\\?id=\\d+',"
          + " 'https://example.com/other?id=789'",
      "'api/(?:getItemById|getDetailsById)\\.json', 'api/getItemById.json'",
      "'api/(?:getItemById|getDetailsById)\\.json', 'api/getDetailsById.json'",
      "'api/(?:getItemById|getDetailsById)\\.json', 'api/getOther.json'"
    })
    @DisplayName("Crosscheck factored alternation patterns with JDK")
    void testFactoredAlternationCrosscheckWithJdk(String regex, String actualInput) {
      Pattern p = Pattern.compile(regex);
      java.util.regex.Pattern jp = java.util.regex.Pattern.compile(regex);

      Matcher m = p.matcher(actualInput);
      java.util.regex.Matcher jm = jp.matcher(actualInput);

      boolean matchFound = m.find();
      boolean jMatchFound = jm.find();

      assertThat(matchFound)
          .withFailMessage("find() mismatch for regex '%s' on '%s'", regex, actualInput)
          .isEqualTo(jMatchFound);

      if (matchFound) {
        assertThat(m.start()).isEqualTo(jm.start());
        assertThat(m.end()).isEqualTo(jm.end());
        assertThat(m.group()).isEqualTo(jm.group());
      }
    }
  }

  @Nested
  @DisplayName("Vectorized Gap and CharClass Anchor Tests")
  class VectorizedGapAndCharClassAnchorTests {

    @Test
    @DisplayName("Matches long character class repetitions in gaps")
    void testLongGapCharClassRepeat() {
      Pattern p = Pattern.compile("KEY:\\s{10,50}VAL");
      assertThat(p.multiAnchorDescriptor()).isNotNull();

      String longSpaces = "KEY:" + " ".repeat(30) + "VAL";
      Matcher m = p.matcher("prefix " + longSpaces + " suffix");
      assertThat(m.find()).isTrue();
      assertThat(m.group()).isEqualTo(longSpaces);

      String tooFewSpaces = "KEY:   VAL";
      assertThat(p.matcher(tooFewSpaces).find()).isFalse();

      String nonSpaces = "KEY:" + " ".repeat(15) + "X" + " ".repeat(14) + "VAL";
      assertThat(p.matcher(nonSpaces).find()).isFalse();
    }

    @Test
    @DisplayName("Matches long character class repetitions on UTF-8 scanner")
    void testLongGapCharClassRepeatUtf8() {
      Pattern p = Pattern.compile("HEADER:[a-zA-Z0-9_]{16,64}:BODY");
      assertThat(p.multiAnchorDescriptor()).isNotNull();

      String inputStr = "prefix HEADER:" + "a1B2_c3D4".repeat(4) + ":BODY suffix";
      byte[] inputBytes = inputStr.getBytes(UTF_8);
      Utf8Matcher m = p.matcher(Utf8Input.validated(inputBytes));
      assertThat(m.find()).isTrue();
      assertThat(new String(inputBytes, m.start(), m.end() - m.start(), UTF_8))
          .isEqualTo("HEADER:" + "a1B2_c3D4".repeat(4) + ":BODY");
    }

    @Test
    @DisplayName("Matches selective CharClass anchor in multi-anchor sequence")
    void testCharClassAnchor() {
      Pattern p = Pattern.compile("token[@#$]name");
      assertThat(p.multiAnchorDescriptor()).isNotNull();
      assertThat(p.multiAnchorDescriptor().anchors()).hasSize(2);

      Matcher m1 = p.matcher("find token@name here");
      assertThat(m1.find()).isTrue();
      assertThat(m1.group()).isEqualTo("token@name");

      Matcher m2 = p.matcher("find token#name here");
      assertThat(m2.find()).isTrue();
      assertThat(m2.group()).isEqualTo("token#name");

      Matcher m3 = p.matcher("find token%name here");
      assertThat(m3.find()).isFalse();
    }
  }
}
