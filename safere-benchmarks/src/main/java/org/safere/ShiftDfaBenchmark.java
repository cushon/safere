// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/** JMH Benchmark measuring Shift DFA throughput against OnePass, Lazy DFA, and JDK regex. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class ShiftDfaBenchmark {

  /** Regex pattern types evaluated across engine variants. */
  public enum PatternType {
    KEYWORD("true|false"),
    IDENTIFIER("[a-zA-Z_][a-zA-Z0-9_]*"),
    DIGITS_RANGE("[0-9]{1,4}"),
    STRING_LITERAL_ACCELERATED("[\\x00-\\x21\\x23-\\x7F]*\"");

    final String regex;

    PatternType(String regex) {
      this.regex = regex;
    }
  }

  /** Size categories for synthetic inputs. */
  public enum InputSize {
    SMALL,
    MEDIUM,
    LARGE
  }

  @Param({"KEYWORD", "IDENTIFIER", "DIGITS_RANGE", "STRING_LITERAL_ACCELERATED"})
  public PatternType patternType;

  @Param({"SMALL", "MEDIUM", "LARGE"})
  public InputSize inputSize;

  private String input;
  private Pattern safereShiftDfa;
  private Pattern safereOnePass;
  private Pattern safereLazyDfa;
  private java.util.regex.Pattern jdkPattern;

  /** Prepares patterns and inputs for the trial. */
  @Setup
  public void setup() {
    String regex = patternType.regex;
    input =
        switch (patternType) {
          case KEYWORD -> "false";
          case IDENTIFIER ->
              switch (inputSize) {
                case SMALL -> "variable_name_1";
                case MEDIUM -> "variable_name_" + "x".repeat(200);
                case LARGE -> "variable_name_" + "x".repeat(5000);
              };
          case DIGITS_RANGE -> "1234";
          case STRING_LITERAL_ACCELERATED ->
              switch (inputSize) {
                case SMALL -> "hello_world\"";
                case MEDIUM -> "a".repeat(250) + "\"";
                case LARGE -> "a".repeat(5000) + "\"";
              };
        };

    safereShiftDfa = Pattern.compile(regex);
    safereOnePass = Pattern.compile(regex, 0, EnginePathOptions.builder().shiftDfa(false).build());
    safereLazyDfa =
        Pattern.compile(
            regex, 0, EnginePathOptions.builder().shiftDfa(false).onePass(false).build());
    jdkPattern = java.util.regex.Pattern.compile(regex);
  }

  /** Evaluates SafeRE with default Shift DFA engine. */
  @Benchmark
  public void safere_shiftDfa(Blackhole bh) {
    bh.consume(safereShiftDfa.matcher(input).matches());
  }

  /** Evaluates SafeRE routing through OnePass engine. */
  @Benchmark
  public void safere_onePass(Blackhole bh) {
    bh.consume(safereOnePass.matcher(input).matches());
  }

  /** Evaluates SafeRE routing through standard Lazy DFA engine. */
  @Benchmark
  public void safere_lazyDfa(Blackhole bh) {
    bh.consume(safereLazyDfa.matcher(input).matches());
  }

  /** Evaluates standard java.util.regex. */
  @Benchmark
  public void jdk(Blackhole bh) {
    bh.consume(jdkPattern.matcher(input).matches());
  }
}
