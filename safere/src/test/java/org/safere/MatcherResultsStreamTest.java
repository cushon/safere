// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for {@link Matcher#results()}. */
class MatcherResultsStreamTest {

  @Test
  @DisplayName("results() returns all matches as a stream")
  void resultsStream() {
    Pattern p = Pattern.compile("\\d+");
    Matcher m = p.matcher("abc 123 def 456 ghi");
    List<String> matches = m.results().map(MatchResult::group).collect(Collectors.toList());
    assertThat(matches).containsExactly("123", "456");
  }

  @Test
  @DisplayName("results() returns empty stream when no matches")
  void resultsNoMatch() {
    Pattern p = Pattern.compile("\\d+");
    Matcher m = p.matcher("no digits here");
    assertThat(m.results().count()).isEqualTo(0);
  }

  @Test
  @DisplayName("results() match results have correct positions")
  void resultsPositions() {
    Pattern p = Pattern.compile("[a-z]+");
    Matcher m = p.matcher("123 abc 456 def");
    List<MatchResult> results = m.results().collect(Collectors.toList());
    assertThat(results).hasSize(2);
    assertThat(results.get(0).start()).isEqualTo(4);
    assertThat(results.get(0).end()).isEqualTo(7);
    assertThat(results.get(1).start()).isEqualTo(12);
    assertThat(results.get(1).end()).isEqualTo(15);
  }

  @Test
  @DisabledForCrosscheck(
      "callback mutation is covered by MatcherStateMachineTraceTest; generated wrapper cannot "
          + "bind mutation separately to each engine")
  @DisplayName("results() detects matcher mutation during stream traversal")
  void resultsDetectsMatcherMutation() {
    Pattern p = Pattern.compile("a");
    Matcher m = p.matcher("aa");

    assertThatThrownBy(
            () ->
                m.results()
                    .map(
                        result -> {
                          m.find();
                          return result.group();
                        })
                    .collect(Collectors.toList()))
        .isInstanceOf(ConcurrentModificationException.class);
  }
}
