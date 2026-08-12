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

/**
 * JMH benchmark to evaluate OnePass vs DFA scaling curves across input sizes and capture counts.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class OnePassScalingBenchmark {

  /** Workload pattern types covering the capture count spectrum. */
  public enum Workload {
    NO_CAPTURES,
    FEW_CAPTURES,
    MANY_CAPTURES
  }

  /** Engine execution configuration. */
  public enum Engine {
    ONE_PASS,
    DFA_SANDWICH
  }

  @Param({"NO_CAPTURES", "FEW_CAPTURES", "MANY_CAPTURES"})
  public Workload workload;

  @Param({"64", "512", "4096", "16384", "65536", "262144"})
  public int size;

  @Param({"ONE_PASS", "DFA_SANDWICH"})
  public Engine engine;

  private String input;
  private String replacement;
  private Pattern saferePattern;

  @Setup
  public void setup() {
    String regex;
    switch (workload) {
      case NO_CAPTURES -> {
        regex = "^[a-zA-Z0-9_.-]+$";
        replacement = "replacement";
        String chunk = "valid_token_abc123-xyz.";
        int repeats = Math.max(1, size / chunk.length() + 1);
        input = chunk.repeat(repeats).substring(0, size);
      }
      case FEW_CAPTURES -> {
        regex = "^([a-zA-Z]+)://([^:/]+):([0-9]+)$";
        replacement = "$1://$2-modified:$3";
        String prefix = "https://";
        String suffix = ":8080";
        int middleLen = Math.max(1, size - prefix.length() - suffix.length());
        input = prefix + "a".repeat(middleLen) + suffix;
      }
      case MANY_CAPTURES -> {
        regex =
            "^([0-9.]+) - - \\[([^\\]]+)\\] \"(GET|POST|PUT) ([^ ]+) ([^\"\\s]+)\" ([0-9]+)"
                + " ([0-9]+) \"([^\"]+)\"$";
        replacement = "$1 - [$2] $3 $4 $5 $6 $7 $8";
        String prefix = "127.0.0.1 - - [10/Oct/2000:13:55:36 -0700] \"GET /";
        String suffix = " HTTP/1.0\" 200 2326 \"http://example.com\"";
        int middleLen = Math.max(1, size - prefix.length() - suffix.length());
        input = prefix + "a".repeat(middleLen) + suffix;
      }
      default -> throw new IllegalArgumentException("Unknown workload: " + workload);
    }

    saferePattern =
        switch (engine) {
          case ONE_PASS ->
              Pattern.compile(
                  regex, 0, EnginePathOptions.builder().dfa(false).onePass(true).build());
          case DFA_SANDWICH ->
              Pattern.compile(
                  regex, 0, EnginePathOptions.builder().dfa(true).onePass(false).build());
        };
  }

  @Benchmark
  public boolean matches(Blackhole blackhole) {
    Matcher matcher = saferePattern.matcher(input);
    boolean result = matcher.matches();
    if (workload != Workload.NO_CAPTURES && result) {
      blackhole.consume(matcher.group(1));
    }
    return result;
  }

  @Benchmark
  public String replaceAll() {
    return saferePattern.matcher(input).replaceAll(replacement);
  }
}
