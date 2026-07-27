// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** Benchmarks for character classes including non-ASCII Latin-1 character class prefiltering. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class JavaCharacterClassBenchmark {
  private static final String JAVA_LETTER = "\\p{javaLetter}";
  private static final int INPUT_COUNT = 4096;

  private String[] inputs;
  private int index;
  private org.safere.Pattern saferePattern;
  private java.util.regex.Pattern jdkPattern;

  // Latin-1 Character Class Prefilter Test Case ([\u00C0-\u00FF][a-z]+)
  private org.safere.Pattern safeLatin1;
  private java.util.regex.Pattern jdkLatin1;
  private String latin1Text;

  @Setup
  public void setup() {
    inputs = new String[INPUT_COUNT];
    Random random = new Random(0x5AFE355L);
    for (int i = 0; i < inputs.length; i++) {
      inputs[i] = new String(new char[] {(char) random.nextInt()});
    }
    saferePattern = org.safere.Pattern.compile(JAVA_LETTER);
    jdkPattern = java.util.regex.Pattern.compile(JAVA_LETTER);

    // Latin-1 character class prefilter matching ([\u00C0-\u00FF][a-z]+)
    String latin1Pattern = "[\\u00C0-\\u00FF][a-z]+";
    safeLatin1 = org.safere.Pattern.compile(latin1Pattern);
    jdkLatin1 = java.util.regex.Pattern.compile(latin1Pattern);
    StringBuilder sbLatin1 = new StringBuilder(10240);
    while (sbLatin1.length() < 10230) {
      sbLatin1.append("abcdefghijklmnopqrstuvwxyz ");
    }
    sbLatin1.append("\u00C9cole "); // \u00C9 = É (Latin-1 uppercase E acute)
    latin1Text = sbLatin1.toString();
  }

  @Benchmark
  public boolean compileAndFindJavaLetter_safere() {
    org.safere.Pattern pattern = org.safere.Pattern.compile(JAVA_LETTER);
    return pattern.matcher(nextInput()).find();
  }

  @Benchmark
  public boolean compileAndFindJavaLetter_jdk() {
    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(JAVA_LETTER);
    return pattern.matcher(nextInput()).find();
  }

  @Benchmark
  public boolean findJavaLetter_safere() {
    return saferePattern.matcher(nextInput()).find();
  }

  @Benchmark
  public boolean findJavaLetter_jdk() {
    return jdkPattern.matcher(nextInput()).find();
  }

  // ===== Latin-1 Character Class Prefilter Find =====

  @Benchmark
  public boolean latin1Find_safere() {
    return safeLatin1.matcher(latin1Text).find();
  }

  @Benchmark
  public boolean latin1Find_jdk() {
    return jdkLatin1.matcher(latin1Text).find();
  }

  private String nextInput() {
    String input = inputs[index];
    index = (index + 1) & (INPUT_COUNT - 1);
    return input;
  }
}
