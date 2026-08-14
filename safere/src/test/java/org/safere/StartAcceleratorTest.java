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
  void caseInsensitiveLiteralAcceleratesStringOnly() {
    StartDescriptor desc = new StartDescriptor("needle", true, null, null, null);
    assertThat(desc.hasStartAcceleration()).isTrue();

    StringStartAccelerator strAcc = StringStartAccelerator.create(desc, false);
    assertThat(strAcc).isInstanceOf(StringStartAccelerator.Literal.class);
    assertThat(strAcc.findCandidate("haystack with NEEDLE here", 0, false)).isEqualTo(14);

    // Utf8 accelerator does not support case-insensitive literal prefix directly
    assertThat(Utf8StartAccelerator.create(desc, false)).isNull();
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

  private static Utf8InputScanner utf8Scanner(String text) {
    byte[] bytes = text.getBytes(UTF_8);
    return new Utf8InputScanner(bytes, 0, bytes.length);
  }
}
