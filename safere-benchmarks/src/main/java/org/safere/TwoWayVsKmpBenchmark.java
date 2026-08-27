// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Microbenchmark directly comparing Crochemore-Perrin Two-Way search against Knuth-Morris-Pratt
 * (KMP) across various text sizes, needle lengths, and input distributions.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class TwoWayVsKmpBenchmark {

  public enum Workload {
    RANDOM_TEXT,
    PERIODIC_ADVERSARIAL,
    DENSE_PREFIX_NOISE
  }

  @Param({"64", "1024", "65536"})
  public int haystackLength;

  @Param({"4", "16", "64"})
  public int needleLength;

  @Param({"RANDOM_TEXT", "PERIODIC_ADVERSARIAL", "DENSE_PREFIX_NOISE"})
  public Workload workload;

  private byte[] haystack;
  private byte[] needle;
  private int[] precomputedFailure;

  @Setup
  public void setup() {
    Random rng = new Random(42);
    needle = new byte[needleLength];
    haystack = new byte[haystackLength];

    switch (workload) {
      case RANDOM_TEXT -> {
        rng.nextBytes(haystack);
        rng.nextBytes(needle);
        if (haystackLength >= needleLength) {
          System.arraycopy(needle, 0, haystack, haystackLength - needleLength, needleLength);
        }
      }
      case PERIODIC_ADVERSARIAL -> {
        Arrays.fill(needle, (byte) 'a');
        needle[needleLength - 1] = (byte) 'b';
        Arrays.fill(haystack, (byte) 'a');
        if (haystackLength >= needleLength) {
          System.arraycopy(needle, 0, haystack, haystackLength - needleLength, needleLength);
        }
      }
      case DENSE_PREFIX_NOISE -> {
        byte[] prefix = "prefix_token_candidate_".getBytes(UTF_8);
        for (int i = 0; i < haystackLength; i++) {
          haystack[i] = prefix[i % prefix.length];
        }
        rng.nextBytes(needle);
        System.arraycopy(prefix, 0, needle, 0, Math.min(prefix.length, needleLength / 2));
      }
    }

    precomputedFailure = Kmp.computeFailure(needle);
  }

  /** Crochemore-Perrin Two-Way (Zero-Allocation on thread stack). */
  @Benchmark
  public int twoWay(Blackhole bh) {
    int res = TwoWay.indexOf(haystack, 0, haystackLength, needle, 0);
    bh.consume(res);
    return res;
  }

  /** KMP with dynamic failure table allocation (simulates un-cached runtime fallback). */
  @Benchmark
  public int kmpDynamicAllocation(Blackhole bh) {
    int[] failure = Kmp.computeFailure(needle);
    int res = Kmp.indexOf(haystack, 0, haystackLength, needle, failure, 0);
    bh.consume(res);
    return res;
  }

  /** KMP with precomputed failure table (simulates pattern-retained table). */
  @Benchmark
  public int kmpPrecomputed(Blackhole bh) {
    int res = Kmp.indexOf(haystack, 0, haystackLength, needle, precomputedFailure, 0);
    bh.consume(res);
    return res;
  }

  public static void main(String[] args) throws Exception {
    Options opt =
        new OptionsBuilder()
            .include(TwoWayVsKmpBenchmark.class.getSimpleName())
            .addProfiler("gc")
            .build();
    new Runner(opt).run();
  }
}
