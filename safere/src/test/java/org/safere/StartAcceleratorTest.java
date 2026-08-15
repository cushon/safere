// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.safere.Pattern.FixedOffsetLiteral;

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class StartAcceleratorTest {

  @Test
  void nullAndNoneDescriptorsProduceNullAccelerators() {
    assertThat(StringStartAccelerator.create(null, false)).isNull();
    assertThat(StringStartAccelerator.create(StartDescriptor.NONE, false)).isNull();
    assertThat(Utf8StartAccelerator.create(null, false)).isNull();
    assertThat(Utf8StartAccelerator.create(StartDescriptor.NONE, false)).isNull();
    assertThat(StartDescriptor.NONE.hasStartAcceleration()).isFalse();
  }

  @Test
  void literalPrefixAcceleratesStringAndUtf8() {
    StartDescriptor desc = new StartDescriptor("needle", false, null, null, null);
    assertThat(desc.hasStartAcceleration()).isTrue();

    StringStartAccelerator strAcc = StringStartAccelerator.create(desc, false);
    assertThat(strAcc).isInstanceOf(StringStartAccelerator.Literal.class);
    assertThat(strAcc.policy().strategy()).isEqualTo(MatchStrategy.LITERAL);
    assertThat(strAcc.policy().isExactMatchCandidate()).isTrue();
    assertThat(strAcc.findCandidate("haystack with needle here", 0, false)).isEqualTo(14);
    assertThat(strAcc.findCandidate("haystack with needle here", 15, false)).isEqualTo(-1);

    Utf8StartAccelerator utf8Acc = Utf8StartAccelerator.create(desc, false);
    assertThat(utf8Acc).isInstanceOf(Utf8StartAccelerator.Literal.class);
    assertThat(utf8Acc.policy().strategy()).isEqualTo(MatchStrategy.LITERAL);
    assertThat(utf8Acc.policy().isExactMatchCandidate()).isTrue();
    assertThat(utf8Acc.findCandidate(utf8Scanner("haystack with needle here"), 0)).isEqualTo(14);
    assertThat(utf8Acc.findCandidate(utf8Scanner("haystack with needle here"), 15)).isEqualTo(-1);
  }

  @Test
  void caseInsensitiveLiteralAcceleratesStringAndUtf8() {
    StartDescriptor desc = new StartDescriptor("needle", true, null, null, null);
    assertThat(desc.hasStartAcceleration()).isTrue();

    StringStartAccelerator strAcc = StringStartAccelerator.create(desc, false);
    assertThat(strAcc).isInstanceOf(StringStartAccelerator.Literal.class);
    assertThat(strAcc.findCandidate("haystack with NEEDLE here", 0, false)).isEqualTo(14);

    Utf8StartAccelerator utf8Acc = Utf8StartAccelerator.create(desc, false);
    assertThat(utf8Acc).isInstanceOf(Utf8StartAccelerator.CaseInsensitiveLiteral.class);
    assertThat(utf8Acc.policy().strategy()).isEqualTo(MatchStrategy.LITERAL);
    assertThat(utf8Acc.policy().isExactMatchCandidate()).isTrue();
    assertThat(utf8Acc.findCandidate(utf8Scanner("haystack with NEEDLE here"), 0)).isEqualTo(14);
    assertThat(utf8Acc.findCandidate(utf8Scanner("haystack with nEeDlE here"), 0)).isEqualTo(14);
    assertThat(utf8Acc.findCandidate(utf8Scanner("haystack with needle here"), 15)).isEqualTo(-1);

    // Single character case-insensitive prefix
    StartDescriptor singleDesc = new StartDescriptor("a", true, null, null, null);
    Utf8StartAccelerator singleUtf8 = Utf8StartAccelerator.create(singleDesc, false);
    assertThat(singleUtf8).isInstanceOf(Utf8StartAccelerator.CaseInsensitiveLiteral.class);
    assertThat(singleUtf8.findCandidate(utf8Scanner("xxxA"), 0)).isEqualTo(3);
    assertThat(singleUtf8.findCandidate(utf8Scanner("xxxa"), 0)).isEqualTo(3);

    // Non-ASCII case-insensitive prefix falls back (null)
    StartDescriptor nonAsciiDesc = new StartDescriptor("café", true, null, null, null);
    assertThat(Utf8StartAccelerator.create(nonAsciiDesc, false)).isNull();
  }

  @Test
  void fixedOffsetLiteralAcceleratesStringAndUtf8() {
    FixedOffsetLiteral fixed = new FixedOffsetLiteral("token", 2, 2, new int[] {2});
    StartDescriptor desc = new StartDescriptor(null, false, fixed, null, null);

    StringStartAccelerator strAcc = StringStartAccelerator.create(desc, false);
    assertThat(strAcc).isInstanceOf(StringStartAccelerator.FixedOffset.class);
    assertThat(strAcc.policy().strategy()).isEqualTo(MatchStrategy.LITERAL);
    assertThat(strAcc.findCandidate("abtoken cd", 0, false)).isEqualTo(0);

    Utf8StartAccelerator utf8Acc = Utf8StartAccelerator.create(desc, false);
    assertThat(utf8Acc).isInstanceOf(Utf8StartAccelerator.FixedOffset.class);
    assertThat(utf8Acc.policy().strategy()).isEqualTo(MatchStrategy.LITERAL);
    assertThat(utf8Acc.findCandidate(utf8Scanner("abtoken cd"), 0)).isEqualTo(0);
  }

  @Test
  void charClassPrefixAcceleratesStringAndUtf8() {
    AsciiBitmap ascii = new AsciiBitmap.Builder().add('a').add('b').build();
    StartDescriptor desc = new StartDescriptor(null, false, null, ascii, null);

    StringStartAccelerator strAcc = StringStartAccelerator.create(desc, false);
    assertThat(strAcc).isInstanceOf(StringStartAccelerator.CharClass.class);
    assertThat(strAcc.policy().strategy()).isEqualTo(MatchStrategy.CHARACTER_CLASS);
    assertThat(strAcc.policy().isExactMatchCandidate()).isFalse();
    assertThat(strAcc.findCandidate("xxxa", 0, false)).isEqualTo(3);

    Utf8StartAccelerator utf8Acc = Utf8StartAccelerator.create(desc, false);
    assertThat(utf8Acc).isInstanceOf(Utf8StartAccelerator.CharClass.class);
    assertThat(utf8Acc.policy().strategy()).isEqualTo(MatchStrategy.CHARACTER_CLASS);
    assertThat(utf8Acc.policy().isExactMatchCandidate()).isFalse();
    assertThat(utf8Acc.findCandidate(utf8Scanner("xxxb"), 0)).isEqualTo(3);
  }

  @Test
  void compiledPatternAcceleratorsInSync() {
    String[] testPatterns = {
      "(?i)needle.*", "(?i)a.*", "(?i)HTTP://.*", "needle.*", "[a-z].*", "[0-9].*", "ab+c.*"
    };

    String[] testInputs = {
      "prefix with NEEDLE in middle",
      "prefix with needle in middle",
      "prefix with nEeDlE in middle",
      "prefix with no match",
      "HTTP://EXAMPLE.COM",
      "http://example.com",
      "123 numbers",
      "letters abc"
    };

    for (String patStr : testPatterns) {
      Pattern pattern = Pattern.compile(patStr);
      StringStartAccelerator strAcc = pattern.stringStartAccelerator();
      Utf8StartAccelerator utf8Acc = pattern.utf8StartAccelerator();

      if (strAcc != null) {
        assertThat(utf8Acc)
            .as(
                "Utf8StartAccelerator should match StringStartAccelerator presence for pattern: %s",
                patStr)
            .isNotNull();
        assertThat(utf8Acc.policy().strategy())
            .as("Strategies should match for pattern: %s", patStr)
            .isEqualTo(strAcc.policy().strategy());

        for (String input : testInputs) {
          int strCandidate = strAcc.findCandidate(input, 0, false);
          int utf8Candidate = utf8Acc.findCandidate(utf8Scanner(input), 0);
          assertThat(utf8Candidate)
              .as("Candidate indices should match for pattern '%s' on input '%s'", patStr, input)
              .isEqualTo(strCandidate);
        }
      }
    }
  }

  private static Utf8InputScanner utf8Scanner(String text) {
    byte[] bytes = text.getBytes(UTF_8);
    return new Utf8InputScanner(bytes, 0, bytes.length);
  }
}
