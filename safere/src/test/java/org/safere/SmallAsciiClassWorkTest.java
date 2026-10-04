// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("WorkCounter and input scanners are internal SafeRE APIs")
@Tag("work-counter")
class SmallAsciiClassWorkTest {
  @Test
  void failedSearchAccountsForEachMemberSearch() {
    String text = "a".repeat(8_192);
    InputScanner scanner = new StringInputScanner(text);
    CharClassScanInfo info = smallSet("XZ_");
    long work =
        WorkCounter.countForTesting(
            () -> assertThat(scanner.indexOfCharClass(info, 0)).isEqualTo(-1));
    // The bitmap prologue inspects each unit once; each absent member searches the remainder.
    assertThat(work).isEqualTo(16L + 3L * (text.length() - 16));
  }

  @Test
  void repeatedSearchWorkIsBoundedByDistanceAdvanced() {
    for (String members : new String[] {"YZ", "XZ_"}) {
      CharClassScanInfo info = smallSet(members);
      for (String noise : new String[] {"a", "é", "中"}) {
        for (int gap : new int[] {17, 65, 200, 5_000}) {
          for (int matches : new int[] {16, 128}) {
            String text = (noise.repeat(gap - 1) + "Z").repeat(matches);
            InputScanner scanner = new StringInputScanner(text);
            long work =
                WorkCounter.countForTesting(
                    () -> {
                      int from = 0;
                      for (int i = 0; i < matches; i++) {
                        int found = scanner.indexOfCharClass(info, from);
                        assertThat(found).isEqualTo((i + 1) * gap - 1);
                        from = found + 1;
                      }
                      assertThat(scanner.indexOfCharClass(info, from)).isEqualTo(-1);
                    });
            assertThat(work).as("observed %s gap %s", members, gap).isPositive();
            assertThat(work)
                .as("%s gap %s matches %s", members, gap, matches)
                .isLessThanOrEqualTo(members.length() * (2L * text.length() + 64L * matches));
          }
        }
      }
    }
  }

  @Test
  void matcherIterationUsesTheInstrumentedWindowSearch() {
    Pattern pattern = Pattern.compile("[XZ_]");
    for (int gap : new int[] {17, 65, 200, 5_000}) {
      String text = ("a".repeat(gap - 1) + "Z").repeat(128);
      long work =
          WorkCounter.countForTesting(
              () -> {
                Matcher matcher = pattern.matcher(text);
                int matches = 0;
                while (matcher.find()) {
                  matches++;
                }
                assertThat(matches).isEqualTo(128);
              });
      assertThat(work).isGreaterThan(text.length());
      assertThat(work).isLessThanOrEqualTo(4L * (2L * text.length() + 64L * 128));
    }
  }

  @Test
  void nonAsciiMembersKeepTheSameBound() {
    // Mixed and all-non-ASCII pairs; the second member is absent or appears only at the end.
    for (String members : new String[] {"[\uFF3B", "\u3010\uFF3B"}) {
      CharClassScanInfo info = unicodeSmallSet(members);
      assertThat(info).isInstanceOf(CharClassScanInfo.UnicodeSmallSet.class);
      char present = members.charAt(1);
      char other = members.charAt(0);
      for (String noise : new String[] {"a", "\u00e9", "\u4e2d"}) {
        for (int gap : new int[] {17, 65, 200, 5_000}) {
          for (String suffix : new String[] {"", String.valueOf(other)}) {
            int matches = 128;
            String text = (noise.repeat(gap - 1) + present).repeat(matches) + suffix;
            InputScanner scanner = new StringInputScanner(text);
            long work =
                WorkCounter.countForTesting(
                    () -> {
                      int from = 0;
                      for (int i = 0; i < matches; i++) {
                        int found = scanner.indexOfCharClass(info, from);
                        assertThat(found).isEqualTo((i + 1) * gap - 1);
                        from = found + 1;
                      }
                      assertThat(scanner.indexOfCharClass(info, from))
                          .isEqualTo(suffix.isEmpty() ? -1 : text.length() - 1);
                    });
            assertThat(work)
                .as("%s noise %s gap %s suffix '%s'", members, noise, gap, suffix)
                .isLessThanOrEqualTo(2L * (2L * text.length() + 64L * (matches + 1)));
          }
        }
      }
    }
  }

  @Test
  void utf8MixedRejectStopsNearEitherMember() {
    RejectPrefilter filter = RejectPrefilter.CharClass.create(unicodeSmallSet("]\uFF3D"));
    for (String member : new String[] {"]", "\uFF3D"}) {
      for (int length : new int[] {8_192, 65_536}) {
        byte[] bytes =
            ("中".repeat(32) + member + "中".repeat(length)).getBytes(StandardCharsets.UTF_8);
        long work =
            WorkCounter.countForTesting(
                () ->
                    assertThat(
                            filter.canReject(
                                new Utf8InputScanner(bytes), 0, EnginePathOptions.allEnabled()))
                        .isFalse());
        assertThat(work).isLessThanOrEqualTo(512);
      }
    }
  }

  @Test
  void denseCandidateDecisionIsBoundedAndReused() {
    CharClassScanInfo info = unicodeSmallSet("[\uFF3B");
    StringInputScanner dense = new StringInputScanner(("中".repeat(12) + "\uFF3B").repeat(1_000));
    long work =
        WorkCounter.countForTesting(
            () -> {
              for (int i = 0; i < 1_000; i++) {
                assertThat(dense.hasDenseCandidates(info)).isTrue();
              }
            });
    assertThat(work).isLessThanOrEqualTo(256);
    StringInputScanner sparse = new StringInputScanner("中".repeat(1_000) + "\uFF3B");
    assertThat(sparse.hasDenseCandidates(info)).isFalse();
    assertThat(dense.hasDenseCandidates(unicodeSmallSet("【】"))).isFalse();
  }

  @Test
  void absentMixedUtf8RejectHasLinearWork() {
    RejectPrefilter filter = RejectPrefilter.CharClass.create(unicodeSmallSet("]\uFF3D"));
    for (int length : new int[] {512, 8_192, 65_536}) {
      byte[] bytes = "中😀a".repeat(length).getBytes(StandardCharsets.UTF_8);
      long work =
          WorkCounter.countForTesting(
              () ->
                  assertThat(
                          filter.canReject(
                              new Utf8InputScanner(bytes), 0, EnginePathOptions.allEnabled()))
                      .isTrue());
      assertThat(work).isBetween(1L, 3L * bytes.length);
    }
  }

  @Test
  void utf8DensityDecisionIsBoundedAndReused() {
    CharClassScanInfo info = unicodeSmallSet("[\uFF3B");
    Utf8InputScanner scanner =
        new Utf8InputScanner("中\uFF3B".repeat(10_000).getBytes(StandardCharsets.UTF_8));
    long work =
        WorkCounter.countForTesting(
            () -> {
              for (int i = 0; i < 1_000; i++) {
                assertThat(scanner.hasDenseCandidates(info)).isTrue();
              }
            });
    assertThat(work).isBetween(1L, 259L);
  }

  @Test
  void sharedUtf8DensityQueriesKeepEachClassWithItsVerdict() {
    CharClassScanInfo present = unicodeSmallSet("[\uFF3B");
    CharClassScanInfo absent = unicodeSmallSet("]\uFF3D");
    Utf8InputScanner scanner =
        new Utf8InputScanner("中\uFF3B".repeat(1_000).getBytes(StandardCharsets.UTF_8));
    IntStream.range(0, 10_000)
        .parallel()
        .forEach(
            i -> {
              boolean dense = (i & 1) == 0;
              assertThat(scanner.hasDenseCandidates(dense ? present : absent)).isEqualTo(dense);
            });
  }

  @Test
  void absentMixedUtf8RejectScansAsciiInputOnce() {
    RejectPrefilter filter = RejectPrefilter.CharClass.create(unicodeSmallSet("]\uFF3D"));
    for (int length : new int[] {80, 2048, 100_000}) {
      byte[] bytes = "a".repeat(length).getBytes(StandardCharsets.UTF_8);
      long work =
          WorkCounter.countForTesting(
              () ->
                  assertThat(
                          filter.canReject(
                              new Utf8InputScanner(bytes), 0, EnginePathOptions.allEnabled()))
                      .isTrue());
      assertThat(work).isEqualTo(bytes.length);
    }
  }

  private static CharClassScanInfo unicodeSmallSet(String members) {
    CharClassBuilder builder = new CharClassBuilder();
    members.chars().forEach(builder::addRune);
    return CharClassScanInfo.fromCharClass(builder.build());
  }

  private static CharClassScanInfo smallSet(String members) {
    AsciiBitmap.Builder builder = new AsciiBitmap.Builder();
    for (int i = 0; i < members.length(); i++) {
      builder.add(members.charAt(i));
    }
    return CharClassScanInfo.fromAsciiBitmap(builder.build());
  }
}
