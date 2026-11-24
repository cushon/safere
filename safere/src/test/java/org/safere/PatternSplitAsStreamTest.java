// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for {@link Pattern#splitAsStream(CharSequence)}. */
class PatternSplitAsStreamTest {
  private static final class LiteralCharSequence implements CharSequence {
    private final String value;

    LiteralCharSequence(String value) {
      this.value = value;
    }

    @Override
    public int length() {
      return value.length();
    }

    @Override
    public char charAt(int index) {
      return value.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return value.subSequence(start, end);
    }
  }

  private static final class MutableCharSequence implements CharSequence {
    private final StringBuilder value;

    MutableCharSequence(String value) {
      this.value = new StringBuilder(value);
    }

    void set(String newValue) {
      value.setLength(0);
      value.append(newValue);
    }

    @Override
    public int length() {
      return value.length();
    }

    @Override
    public char charAt(int index) {
      return value.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return value.subSequence(start, end);
    }

    @Override
    public String toString() {
      return value.toString();
    }
  }

  @Test
  @DisplayName("splitAsStream splits input around matches")
  void splitAsStreamBasic() {
    Pattern p = Pattern.compile(",");
    List<String> parts = p.splitAsStream("a,b,c").collect(Collectors.toList());
    assertThat(parts).containsExactly("a", "b", "c");
  }

  @Test
  @DisplayName("splitAsStream with no match returns entire input")
  void splitAsStreamNoMatch() {
    Pattern p = Pattern.compile(",");
    List<String> parts = p.splitAsStream("abc").collect(Collectors.toList());
    assertThat(parts).containsExactly("abc");
  }

  @Test
  @DisplayName("splitAsStream with regex pattern")
  void splitAsStreamRegex() {
    Pattern p = Pattern.compile("\\s+");
    List<String> parts = p.splitAsStream("hello  world\tfoo").collect(Collectors.toList());
    assertThat(parts).containsExactly("hello", "world", "foo");
  }

  @Test
  @DisplayName("splitAsStream count() works without collecting")
  void splitAsStreamCount() {
    Pattern p = Pattern.compile(",");
    long count = p.splitAsStream("a,b,c,d").count();
    assertThat(count).isEqualTo(4);
  }

  @Test
  @DisplayName("splitAsStream discards trailing empty strings")
  void splitAsStreamTrailingEmpty() {
    Pattern p = Pattern.compile(",");
    List<String> parts = p.splitAsStream("a,b,").collect(Collectors.toList());
    assertThat(parts).containsExactly("a", "b");
  }

  @Test
  @DisplayName("splitAsStream reads custom CharSequence content via charAt()")
  void splitAsStreamCustomCharSequence() {
    Pattern p = Pattern.compile(",");
    List<String> parts =
        p.splitAsStream(new LiteralCharSequence("a,b,c")).collect(Collectors.toList());
    assertThat(parts).containsExactly("a", "b", "c");
  }

  @Test
  @DisplayName("splitAsStream reads input lazily when stream is consumed")
  void splitAsStreamReadsInputLazily() {
    Pattern p = Pattern.compile(",");
    MutableCharSequence input = new MutableCharSequence("a,b");
    Stream<String> stream = p.splitAsStream(input);

    input.set("x,y,z");

    assertThat(stream.collect(Collectors.toList())).containsExactly("x", "y", "z");
  }

  @ParameterizedTest
  @MethodSource("splitCases")
  void splitAsStreamPreservesTokenOrderAndEmptyTokenRules(
      String regex, String input, List<String> expected) {
    Pattern pattern = Pattern.compile(regex);
    assertThat(pattern.splitAsStream(input).toList()).containsExactlyElementsOf(expected);
    assertThat(pattern.splitAsStream(input).limit(2).toList())
        .containsExactlyElementsOf(expected.stream().limit(2).toList());
  }

  private static Stream<Arguments> splitCases() {
    return Stream.of(
        Arguments.of(",", "", List.of("")),
        Arguments.of("", "", List.of("")),
        Arguments.of(",", ",,,", List.of()),
        Arguments.of(",", ",,a,,,b,,,", List.of("", "", "a", "", "", "b")),
        Arguments.of("", "abc", List.of("a", "b", "c")),
        Arguments.of("^|,", "a,b", List.of("a", "b")),
        Arguments.of("\\X?[\\uDC00]", "\uD800\uDC00", List.of("\uD800\uDC00")),
        Arguments.of("\\G,", ",,a,b", List.of("", "", "a,b")),
        Arguments.of("\\G,|;", ",,a;,,b;", List.of("", "", "a", "", "", "b")),
        Arguments.of("\\G|,", "a,,b", List.of("a", "", ",b")));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisabledForCrosscheck("Thread allocation accounting checks SafeRE implementation behavior")
  void shortCircuitAllocationDoesNotScaleWithUnreadTail(boolean firstOnly) throws Exception {
    Class<?> managementFactory = Class.forName("java.lang.management.ManagementFactory");
    Object bean = managementFactory.getMethod("getThreadMXBean").invoke(null);
    Class<?> allocationBean = Class.forName("com.sun.management.ThreadMXBean");
    assumeTrue(allocationBean.isInstance(bean));
    assumeTrue((boolean) allocationBean.getMethod("isThreadAllocatedMemoryEnabled").invoke(bean));
    Method allocatedBytes = allocationBean.getMethod("getCurrentThreadAllocatedBytes");

    Pattern pattern = Pattern.compile(",");
    String small = "a,".repeat(16) + "last";
    String large = "a,".repeat(131_072) + "last";
    List<String> expected = firstOnly ? List.of("a") : List.of("a", "a", "a", "a", "a");
    assertThat(readPrefix(pattern, small, firstOnly)).isEqualTo(expected);

    long beforeSmall = (long) allocatedBytes.invoke(bean);
    List<String> smallPrefix = readPrefix(pattern, small, firstOnly);
    long beforeLarge = (long) allocatedBytes.invoke(bean);
    List<String> largePrefix = readPrefix(pattern, large, firstOnly);
    long afterLarge = (long) allocatedBytes.invoke(bean);

    assertThat(smallPrefix).isEqualTo(expected);
    assertThat(largePrefix).isEqualTo(expected);
    long growth = (afterLarge - beforeLarge) - (beforeLarge - beforeSmall);
    assertThat(growth).isLessThan(32_768);
  }

  private static List<String> readPrefix(Pattern pattern, String input, boolean firstOnly) {
    Stream<String> tokens = pattern.splitAsStream(input);
    return firstOnly ? List.of(tokens.findFirst().orElseThrow()) : tokens.limit(5).toList();
  }
}
