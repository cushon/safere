// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.safere.StringVectorLoads.Mode;

/**
 * Tests the String vector scan in every load mode this JVM supports, against a scalar reference.
 *
 * <p>The production mode is chosen once per JVM, so these tests force each mode through {@link
 * StringVectorScan#indexOfCharClass}. The scans use the preferred species; run with {@code
 * -XX:MaxVectorSize=8}, {@code 16} or {@code 32} to cover narrower ones. The JDK mode needs a
 * runtime with {@code ShortVector.fromString}, and the reflective mode needs {@code --add-opens
 * java.base/java.lang=ALL-UNNAMED}; tests for unavailable modes are skipped. The shared kernel is
 * also run over {@code char[]} copies, so it is covered whenever the incubator module is present.
 */
@DisabledForCrosscheck("implementation test uses package-private String vector internals")
class StringVectorScanTest {
  private static final boolean VECTOR_MODULE = vectorModulePresent();

  private static final List<int[]> RANGES =
      List.of(
          new int[] {'0', '9'},
          new int[] {'_', '_'},
          new int[] {'a', 'b'},
          new int[] {'0', '9', 'A', 'Z', '_', '_', 'a', 'z'},
          new int[] {'-', '-', '0', '9', 'A', 'Z', '_', '_', 'a', 'z'},
          new int[] {'%', '%', '+', '+', '-', '.', '0', '9', 'A', 'Z', 'a', 'z'},
          new int[] {
            '!', '!', '#', '#', '%', '%', '\'', '\'', '0', '9', 'A', 'Z', '_', '_', 'a', 'z'
          },
          new int[] {0xE9, 0xE9},
          new int[] {0x0400, 0x04FF},
          new int[] {'0', '9', 0x3040, 0x309F},
          new int[] {0xE000, 0xFFFF},
          new int[] {0x80, 0xD7FF});

  private static boolean vectorModulePresent() {
    return ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent();
  }

  /** Ways to run the scan: each String load mode, and the kernel over a {@code char[]} copy. */
  enum Backend {
    JDK,
    REFLECTIVE,
    CHAR_ARRAY;

    boolean available() {
      return switch (this) {
        case JDK -> StringVectorScan.available(Mode.JDK);
        case REFLECTIVE -> StringVectorScan.available(Mode.REFLECTIVE);
        case CHAR_ARRAY -> true;
      };
    }

    int indexOfCharClass(String text, int[] ranges, int start) {
      return switch (this) {
        case JDK -> StringVectorScan.indexOfCharClass(Mode.JDK, text, ranges, start);
        case REFLECTIVE -> StringVectorScan.indexOfCharClass(Mode.REFLECTIVE, text, ranges, start);
        case CHAR_ARRAY -> StringVectorScan.indexOfCharClass(text.toCharArray(), ranges, start);
      };
    }
  }

  static Stream<Backend> backends() {
    return Stream.of(Backend.values());
  }

  private static void assumeAvailable(Backend backend) {
    assumeTrue(VECTOR_MODULE, "needs --add-modules jdk.incubator.vector");
    assumeTrue(backend.available(), backend + " is not available in this JVM");
  }

  @ParameterizedTest
  @MethodSource("backends")
  void randomStringsMatchScalarReference(Backend backend) {
    assumeAvailable(backend);
    Random random = new Random(backend.name().hashCode());
    for (int iteration = 0; iteration < 400; iteration++) {
      String text = randomString(random, random.nextInt(300), iteration % 3);
      for (int[] ranges : RANGES) {
        for (int start : starts(text.length())) {
          assertThat(backend.indexOfCharClass(text, ranges, start))
              .as("%s, ranges %s, start %d, text %s", backend, ranges, start, text)
              .isEqualTo(reference(text, ranges, start));
        }
      }
    }
  }

  @ParameterizedTest
  @MethodSource("backends")
  void findsEveryPositionInBothCoders(Backend backend) {
    assumeAvailable(backend);
    int[] digits = {'0', '9'};
    for (String filler : new String[] {"x", "\u00e9", "\u0100", "\ud83d\ude00"}) {
      for (int length : new int[] {1, 3, 4, 7, 8, 15, 16, 17, 31, 32, 33, 64, 127, 129, 1025}) {
        String base = filler.repeat(length);
        assertThat(backend.indexOfCharClass(base, digits, 0)).isEqualTo(-1);
        for (int position = 0; position < base.length(); position += 1 + position / 8) {
          String text = base.substring(0, position) + '7' + base.substring(position + 1);
          int start = Math.max(0, position - 40);
          assertThat(backend.indexOfCharClass(text, digits, start))
              .as("%s, filler %s, length %d, position %d", backend, filler, length, position)
              .isEqualTo(reference(text, digits, start));
        }
      }
    }
  }

  @ParameterizedTest
  @MethodSource("backends")
  void surrogatesNeverMatchBmpRanges(Backend backend) {
    assumeAvailable(backend);
    // Every code unit of these supplementary code points is outside the class, although the code
    // points' low bits fall inside it.
    String text = "\ud800\udc30\udbff\udfff\ud83d\ude00".repeat(40) + "\ud800" + "a" + "\udc00";
    int[] ranges = {'0', '9', 'a', 'z', 0xE000, 0xFFFF};
    assertThat(backend.indexOfCharClass(text, ranges, 0)).isEqualTo(text.indexOf('a'));
  }

  @ParameterizedTest
  @MethodSource("backends")
  void rangesTheKernelCannotMatchAsCodeUnitsAreUnsupported(Backend backend) {
    assumeAvailable(backend);
    String text = "x".repeat(200);
    int[][] unsupported = {
      {0xD800, 0xDBFF},
      {'a', 'z', 0x10000, 0x10FFFF},
      {0, 1, 3, 3, 5, 5, 7, 7, 9, 9, 11, 11, 13, 13, 15, 15, 17, 17}
    };
    for (int[] ranges : unsupported) {
      assertThat(backend.indexOfCharClass(text, ranges, 0))
          .isEqualTo(VectorScanProvider.UNSUPPORTED);
    }
  }

  @Test
  void shortRemaindersStayScalar() {
    String text = "x".repeat(StringVectorLoads.MINIMUM_REMAINING_LENGTH + 10);
    int[] digits = {'0', '9'};
    assertThat(StringVectorLoads.indexOfCharClass(text, digits, 11))
        .isEqualTo(VectorScanProvider.UNSUPPORTED);
    assertThat(StringVectorLoads.indexOfCharClass("", digits, 0))
        .isEqualTo(VectorScanProvider.UNSUPPORTED);
  }

  @Test
  void defaultModeUsesJdkLoadsOnlyWhenTheRuntimeHasThem() {
    assumeTrue(System.getProperty(StringVectorLoads.PROPERTY) == null);
    Mode expected = VECTOR_MODULE && jdkHasShortVectorFromString() ? Mode.JDK : Mode.NONE;
    assertThat(StringVectorLoads.MODE).isEqualTo(expected);
    assertThat(StringVectorLoads.select("")).isEqualTo(expected);
  }

  @Test
  void explicitModesFallBackWhenUnavailable() {
    Mode defaultMode = StringVectorLoads.select("");
    assertThat(StringVectorLoads.select("none")).isEqualTo(Mode.NONE);
    assertThat(StringVectorLoads.select("jdk")).isEqualTo(defaultMode);
    Mode reflective = StringVectorLoads.select("reflective");
    if (VECTOR_MODULE && StringVectorScan.available(Mode.REFLECTIVE)) {
      assertThat(reflective).isEqualTo(Mode.REFLECTIVE);
    } else {
      assertThat(reflective).isEqualTo(defaultMode);
    }
    for (String unknown : new String[] {"bogus", "chunked"}) {
      assertThatThrownBy(() -> StringVectorLoads.select(unknown))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(unknown);
    }
  }

  private static boolean jdkHasShortVectorFromString() {
    try {
      Class<?> shortVector = Class.forName("jdk.incubator.vector.ShortVector");
      Class<?> species = Class.forName("jdk.incubator.vector.VectorSpecies");
      shortVector.getMethod("fromString", species, String.class, int.class);
      return true;
    } catch (ReflectiveOperationException e) {
      return false;
    }
  }

  private static int[] starts(int length) {
    return new int[] {
      -1, 0, 1, length / 3, Math.max(0, length - 33), Math.max(0, length - 17), length - 1, length
    };
  }

  /**
   * Returns a string drawn from ASCII and Latin-1 ({@code kind} 0, Latin-1 when compact strings are
   * on), with BMP chars ({@code kind} 1), or also with paired and unpaired surrogates ({@code kind}
   * 2).
   */
  private static String randomString(Random random, int length, int kind) {
    StringBuilder builder = new StringBuilder(length);
    while (builder.length() < length) {
      int pick = random.nextInt(20);
      if (pick < 12 || (kind == 0 && pick >= 16)) {
        builder.append((char) (' ' + random.nextInt(0x5F)));
      } else if (pick < 16) {
        builder.append((char) (0x80 + random.nextInt(0x80)));
      } else if (kind == 1 || pick < 18) {
        builder.append((char) random.nextInt(Character.MIN_SURROGATE));
      } else if (pick == 18) {
        builder.appendCodePoint(Character.MIN_SUPPLEMENTARY_CODE_POINT + random.nextInt(0x10000));
      } else {
        builder.append((char) (Character.MIN_SURROGATE + random.nextInt(0x800)));
      }
    }
    return builder.toString();
  }

  private static int reference(String text, int[] ranges, int start) {
    for (int i = Math.max(0, start); i < text.length(); i++) {
      char ch = text.charAt(i);
      for (int r = 0; r < ranges.length; r += 2) {
        if (ch >= ranges[r] && ch <= ranges[r + 1]) {
          return i;
        }
      }
    }
    return -1;
  }
}
