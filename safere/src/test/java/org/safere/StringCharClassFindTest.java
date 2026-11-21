// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Compares {@code find()} against {@code java.util.regex} for character class patterns whose String
 * scans can use vector loads, on long Latin-1 and UTF-16 inputs, with and without regions.
 *
 * <p>Which String load mode runs depends on the JVM (see {@code StringVectorLoads}); run this with
 * each {@code org.safere.experimental.stringVectorLoads} mode to cover them end to end.
 */
class StringCharClassFindTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "[0-9]",
        "\\d+",
        "\\w+",
        "[a-zA-Z0-9_-]+",
        "[a-zA-Z0-9._%+-]+@[a-z]+",
        "x[0-9]",
        "[\\u0400-\\u04FF]+",
        "[0-9\\u3040-\\u309F]",
        "[\\uE000-\\uFFFF]",
      })
  void findAllMatchesJdk(String regex) {
    Pattern pattern = Pattern.compile(regex);
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(regex);
    Random random = new Random(regex.hashCode());
    for (int iteration = 0; iteration < 60; iteration++) {
      String text = randomText(random, iteration);
      assertThat(findAll(pattern.matcher(text), 0, text.length()))
          .as("%s on %s", regex, text)
          .isEqualTo(findAll(jdkPattern.matcher(text), 0, text.length()));
      int regionStart = random.nextInt(text.length() + 1);
      int regionEnd = regionStart + random.nextInt(text.length() - regionStart + 1);
      assertThat(findAll(pattern.matcher(text), regionStart, regionEnd))
          .as("%s on region [%d, %d) of %s", regex, regionStart, regionEnd, text)
          .isEqualTo(findAll(jdkPattern.matcher(text), regionStart, regionEnd));
    }
  }

  private static List<String> findAll(Matcher matcher, int regionStart, int regionEnd) {
    matcher.region(regionStart, regionEnd);
    List<String> matches = new ArrayList<>();
    while (matcher.find()) {
      matches.add(matcher.start() + "-" + matcher.end());
    }
    return matches;
  }

  private static List<String> findAll(
      java.util.regex.Matcher matcher, int regionStart, int regionEnd) {
    matcher.region(regionStart, regionEnd);
    List<String> matches = new ArrayList<>();
    while (matcher.find()) {
      matches.add(matcher.start() + "-" + matcher.end());
    }
    return matches;
  }

  /** Returns long, mostly non-matching text, alternating Latin-1 and UTF-16 content. */
  private static String randomText(Random random, int iteration) {
    int length = 64 + random.nextInt(iteration % 4 == 0 ? 4096 : 400);
    String filler = iteration % 2 == 0 ? " .,;:!?()" : " .,;:\u00e9\u3000\u4e00\ud83d\ude00";
    StringBuilder builder = new StringBuilder(length);
    while (builder.length() < length) {
      int pick = random.nextInt(100);
      if (pick < 2) {
        builder.append((char) ('0' + random.nextInt(10)));
      } else if (pick < 4) {
        builder.append((char) ('a' + random.nextInt(26)));
      } else if (pick < 5) {
        builder.append(iteration % 2 == 0 ? "@" : "\u0416\u3042\ue000");
      } else if (pick < 6) {
        builder.append('\ud800');
      } else {
        builder.append(filler.charAt(random.nextInt(filler.length())));
      }
    }
    return builder.toString();
  }
}
