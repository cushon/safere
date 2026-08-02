// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.safere.Pattern;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(value = 1)
@Warmup(iterations = 2, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class StringVectorScanBenchmark {

  @Param({"32", "2048"})
  public int length;

  private String text;
  private Pattern patternSingle;
  private Pattern patternMulti;

  @Setup
  public void setup() {
    // Create an ASCII string with match at the end
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < length - 1; i++) {
      sb.append('x');
    }
    sb.append('9');
    text = sb.toString();

    // Single-range pattern: [0-9]
    patternSingle = Pattern.compile("[0-9]");
    // Multi-range pattern: [0-9a-c] (has 2 ranges: 0-9 and a-c)
    patternMulti = Pattern.compile("[0-9a-c]");
  }

  @Benchmark
  public boolean scanSingle() {
    return patternSingle.matcher(text).find();
  }

  @Benchmark
  public boolean scanMulti() {
    return patternMulti.matcher(text).find();
  }
}
