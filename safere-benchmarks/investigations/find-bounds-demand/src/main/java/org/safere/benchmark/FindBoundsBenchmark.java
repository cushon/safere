// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.Objects;
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
import org.safere.benchmark.RegexEngineVariant.CompiledRegex;
import org.safere.benchmark.RegexEngineVariant.MatchCursor;
import org.safere.benchmark.RegexEngineVariant.RegexInput;

/** Measures fresh matchers that reuse a compiled pattern and a prepared input. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class FindBoundsBenchmark {
  /** Materialized workload and input representation. */
  @Param({})
  public String findTrial;

  private enum Consumption {
    BOOLEAN,
    BOUNDS,
    GROUP,
    REPEATED,
    MIXED
  }

  private CompiledRegex pattern;
  private RegexInput input;
  private Consumption consumption;
  private int group;

  /** Checks the fixture against the JDK using a different pattern from the timed one. */
  @Setup
  public void setup() {
    BenchmarkData data = BenchmarkData.get();
    var trial = CrossEngineBenchmarkPlan.load().resolve(findTrial);
    var variant = trial.variant();
    var workload = trial.workload();
    var settings =
        data.investigationConfiguration()
            .getAsJsonObject("consumption")
            .getAsJsonObject(workload.id());
    consumption = Consumption.valueOf(settings.get("mode").getAsString());
    group = settings.get("group").getAsInt();
    if (variant != RegexEngineVariant.SAFERE_STRING && variant != RegexEngineVariant.SAFERE_UTF8) {
      throw new IllegalArgumentException(
          "Find-bounds investigation requires String or UTF-8 SafeRE");
    }
    variant.validateScanProvider();
    input = variant.prepareInputs(data, workload.inputKeys()).getFirst();
    String regex = workload.patterns().getFirst();
    String text = data.getInputString(workload.inputKeys().getFirst());
    try (CompiledRegex validationPattern = variant.compile(regex)) {
      validate(
          trial,
          validationPattern.matcher(input),
          regex,
          text,
          variant == RegexEngineVariant.SAFERE_UTF8);
    }
    pattern = variant.compile(regex);
  }

  private void validate(
      CrossEngineBenchmarkPlan.Trial trial,
      MatchCursor actual,
      String regex,
      String text,
      boolean utf8) {
    var expected = java.util.regex.Pattern.compile(regex).matcher(text);
    boolean matched = expected.find();
    trial.validate(matched);
    if (actual.find() != matched) {
      throw new IllegalStateException("Find result differs from JDK for " + findTrial);
    }
    if (!matched) {
      return;
    }
    if (consumption == Consumption.BOUNDS || consumption == Consumption.MIXED) {
      int start = expected.start();
      int end = expected.end();
      if (utf8) {
        start = text.substring(0, start).getBytes(UTF_8).length;
        end = text.substring(0, end).getBytes(UTF_8).length;
      }
      if (actual.start(0) != start || actual.end(0) != end) {
        throw new IllegalStateException("Match bounds differ from JDK for " + findTrial);
      }
    }
    if (consumption == Consumption.GROUP
        && !Objects.equals(actual.group(group), expected.group(group))) {
      throw new IllegalStateException("Capture differs from JDK for " + findTrial);
    }
    if (consumption == Consumption.REPEATED) {
      while (expected.find()) {
        if (!actual.find()) {
          throw new IllegalStateException("Missing subsequent match for " + findTrial);
        }
      }
      if (actual.find()) {
        throw new IllegalStateException("Extra subsequent match for " + findTrial);
      }
    }
  }

  /** Runs one workload invocation. Mixed invokes both fresh-matcher callers on the same pattern. */
  @Benchmark
  public void run(Blackhole blackhole) {
    MatchCursor matcher = pattern.matcher(input);
    boolean found = matcher.find();
    blackhole.consume(found);
    if (found) {
      switch (consumption) {
        case BOOLEAN -> {}
        case BOUNDS -> consumeBounds(matcher, blackhole);
        case GROUP -> blackhole.consume(matcher.group(group));
        case REPEATED -> {
          int count = 1;
          while (matcher.find()) {
            count++;
          }
          blackhole.consume(count);
        }
        case MIXED -> {
          MatchCursor reader = pattern.matcher(input);
          boolean readerFound = reader.find();
          blackhole.consume(readerFound);
          if (readerFound) {
            consumeBounds(reader, blackhole);
          }
        }
      }
    }
  }

  private static void consumeBounds(MatchCursor matcher, Blackhole blackhole) {
    blackhole.consume(matcher.start(0));
    blackhole.consume(matcher.end(0));
  }
}
