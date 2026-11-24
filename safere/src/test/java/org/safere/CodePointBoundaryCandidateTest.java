// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Finds whose start accelerator picks a candidate just after a non-ASCII character. The engines
 * derive the word-boundary context of a candidate from the code points around it, so a candidate
 * inside a character used to make {@code \b} hold after a Unicode word character.
 */
@DisabledForCrosscheck("compares package-private engine path options")
class CodePointBoundaryCandidateTest {
  private static final EnginePathOptions WITHOUT_START_REGISTER =
      EnginePathOptions.builder().startRegister(false).build();

  private static final EnginePathOptions WITHOUT_DFA =
      EnginePathOptions.builder().startRegister(false).dfa(false).reverseDfa(false).build();

  @ParameterizedTest
  @CsvSource({
    // The reported case: the b admits starts one to three units back, and the floor for later
    // occurrences fell inside the é.
    "'\\ba{1,3}b\\W', 'abz\u00e9ab zzzzzzzzzzz'",
    "'\\ba{1,3}b', 'abz\u00e9ab zzzzzzzzzzz'",
    "'\\Ba{1,3}b\\W', 'abz\u00e9ab zzzzzzzzzzz'",
    "'\\ba{1,3}b\\W', 'abz\u00fcab zzzzzzzzzzz'",
    "'\\ba{1,3}b\\W', 'abz\u4e2dab zzzzzzzzzzz'",
    "'\\ba{1,3}b\\W', 'abz\uD801\uDC00ab zzzzzzzzzzz'",
    "'\\ba{1,3}b', 'abz\uD801\uDC00ab zzzzzzzzzzz'",
    "'\\ba{1,3}b\\W', 'abz\uD83D\uDE00ab zzzzzzzzzzz'",
    "'\\b[ab]{1,3}c\\W', 'abz\u00e9abc zzzzzzzzzzz'",
    "'\\ba{1,3}b\\W', 'abz-ab zzzzzzzzzzz'",
  })
  void findsAfterNonAsciiCharactersAgree(String regex, String input) {
    for (int flags : new int[] {0, Pattern.UNICODE_CHARACTER_CLASS}) {
      int jdkFlags = flags == 0 ? 0 : java.util.regex.Pattern.UNICODE_CHARACTER_CLASS;
      List<Integer> expected = spans(java.util.regex.Pattern.compile(regex, jdkFlags), input);
      List<Integer> expectedUtf8 = toUtf8(expected, input);
      for (EnginePathOptions options :
          List.of(EnginePathOptions.allEnabled(), WITHOUT_START_REGISTER, WITHOUT_DFA)) {
        Pattern pattern = Pattern.compile(regex, flags, options);
        assertThat(spans(pattern.matcher(input)))
            .as("%s flags=%d on %s with %s", regex, flags, input, options)
            .isEqualTo(expected);
        Utf8Input utf8 = Utf8Input.validated(input.getBytes(UTF_8));
        assertThat(spans(pattern.matcher(utf8)))
            .as("%s flags=%d on UTF-8 %s with %s", regex, flags, input, options)
            .isEqualTo(expectedUtf8);
      }
    }
  }

  private static List<Integer> spans(java.util.regex.Pattern pattern, String input) {
    java.util.regex.Matcher matcher = pattern.matcher(input);
    List<Integer> spans = new ArrayList<>();
    while (matcher.find()) {
      spans.add(matcher.start());
      spans.add(matcher.end());
    }
    return spans;
  }

  private static List<Integer> spans(Matcher matcher) {
    List<Integer> spans = new ArrayList<>();
    while (matcher.find()) {
      spans.add(matcher.start());
      spans.add(matcher.end());
    }
    return spans;
  }

  private static List<Integer> spans(Utf8Matcher matcher) {
    List<Integer> spans = new ArrayList<>();
    while (matcher.find()) {
      spans.add(matcher.start());
      spans.add(matcher.end());
    }
    return spans;
  }

  private static List<Integer> toUtf8(List<Integer> utf16, String input) {
    return utf16.stream().map(i -> input.substring(0, i).getBytes(UTF_8).length).toList();
  }
}
