// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.openjdk.jmh.infra.Blackhole;
import org.safere.MatchStrategy;
import org.safere.OperationDiagnostics;
import org.safere.Pattern;
import org.safere.PatternCompiledEvent;
import org.safere.PatternFeature;
import org.safere.SafeReMatchDiagnostics;

class CrossEngineBenchmarkPlanTest {
  // This is a historical floor, not a snapshot. Additions do not raise it.
  private static final int MINIMUM_CROSS_ENGINE_WORKLOADS = 606;
  private static final int MINIMUM_RUNNABLE_CROSS_ENGINE_TRIALS = 2_448;
  private static final int MINIMUM_SPECIALIZED_AVERAGE_TIME_TRIALS = 63;
  private static final int MINIMUM_SPECIALIZED_RETAINED_MEMORY_TRIALS = 34;
  private static final int MINIMUM_COLLECTION_RUNNER_TRIALS = 2_487;

  @TempDir static Path temporaryDirectory;

  @BeforeAll
  static void materializeCorpus() throws Exception {
    Path benchmarkDirectory =
        Files.exists(Path.of("benchmark-data.json")) ? Path.of(".") : Path.of("safere-benchmarks");
    BenchmarkInputMaterializer.main(
        new String[] {benchmarkDirectory.toString(), temporaryDirectory.toString()});
    System.setProperty("safere.benchmark.corpus", temporaryDirectory.toString());
  }

  @Test
  void observationMatrixUsesIdenticalPatternsAndInputsWithExplicitExclusions() {
    CrossEngineBenchmarkPlan plan = CrossEngineBenchmarkPlan.load();
    var workloads =
        plan.workloads().stream()
            .filter(workload -> workload.id().startsWith("QuantifiedCaptureBenchmark."))
            .toList();
    assertThat(workloads).isNotEmpty();
    for (String family :
        List.of("nestedGreedy", "boundedGreedy", "boundedLazy", "optionalGreedy", "optionalLazy")) {
      for (String outcome : List.of("match", "reject")) {
        for (int size : List.of(128, 512, 2048, 8192, 32768)) {
          String prefix = "QuantifiedCaptureBenchmark." + family + ".";
          String suffix = "." + outcome + "." + size;
          var observations =
              workloads.stream()
                  .filter(workload ->
                      workload.id().startsWith(prefix) && workload.id().endsWith(suffix))
                  .toList();
          assertThat(observations)
              .extracting(workload ->
                  workload
                      .id()
                      .substring(prefix.length(), workload.id().length() - suffix.length()))
              .containsExactlyInAnyOrder(
                  "boolean", "groupZero", "participation", "text", "numbered", "named", "function");
          assertThat(observations.stream().map(CrossEngineWorkload::patterns).distinct())
              .hasSize(1);
          assertThat(observations.stream().map(CrossEngineWorkload::inputKeys).distinct())
              .hasSize(1);
          for (CrossEngineWorkload workload : observations) {
            List<String> expectedVariants = new ArrayList<>(List.of("safere-string"));
            String observation =
                workload.id().substring(prefix.length(), workload.id().length() - suffix.length());
            if (!observation.equals("function")) {
              expectedVariants.addAll(List.of("safere-utf8", "safere-utf8-vector"));
            }
            if (!outcome.equals("reject") || size == 128 || family.startsWith("optional")) {
              expectedVariants.add("jdk-string");
            }
            if (!observation.equals("function")) {
              expectedVariants.add("re2j-string");
            }
            if (!observation.equals("function") && !observation.equals("named")) {
              expectedVariants.add("re2-ffm-string-conversion");
            }
            assertThat(
                    plan.trials(CrossEngineWorkload.TimingGroup.NANOSECONDS).stream()
                        .filter(trial -> trial.workload().id().equals(workload.id()))
                        .map(trial -> trial.variant().id()))
                .containsExactlyInAnyOrderElementsOf(expectedVariants);
          }
        }
      }
    }
  }

  @Test
  void everyTrialValidatesExactCapturesAndReplacementOutputsAndRunsBoundTask() {
    BenchmarkData data = BenchmarkData.get();
    Blackhole blackhole =
        new Blackhole(
            "Today's password is swordfish. I understand instantiating Blackholes directly is"
                + " dangerous.");
    var trials =
        CrossEngineBenchmarkPlan.load().trials(CrossEngineWorkload.TimingGroup.NANOSECONDS);
    for (var trial : trials) {
      CrossEngineWorkload workload = trial.workload();
      // Vector-provider trials run in their dedicated JVM configuration; this pass uses default.
      if (!workload.id().startsWith("QuantifiedCaptureBenchmark.")
          || trial.variant() == RegexEngineVariant.SAFERE_UTF8_VECTOR) {
        continue;
      }
      String input = data.getInputString(workload.inputKeys().getFirst());
      boolean matched = workload.id().contains(".match.");
      var inputs = trial.variant().prepareInputs(data, workload.inputKeys());
      try (var compiled = trial.variant().compile(workload.patterns().getFirst())) {
        var matcher = compiled.matcher(inputs.getFirst());
        assertThat(matcher.find()).as(trial.id()).isEqualTo(matched);
        if (matched) {
          assertThat(matcher.start(0)).as(trial.id()).isZero();
          assertThat(matcher.end(0)).as(trial.id()).isEqualTo(input.length());
          if (trial.variant().capabilities().contains(EngineCapability.GROUP_TEXT)) {
            assertThat(matcher.group(0)).as(trial.id()).isEqualTo(input);
            assertThat(matcher.group(1)).as(trial.id()).isEqualTo("a");
          }
          int captureStart = input.lastIndexOf('a');
          // Nested repeats intentionally differ from the JDK's earlier-copy bounds (#1079).
          // The captured text is identical on these subjects; pin each engine's actual policy.
          if (trial.variant() == RegexEngineVariant.JDK_STRING
              && !workload.id().contains(".optional")) {
            captureStart--;
          }
          assertThat(matcher.start(1)).as(trial.id()).isEqualTo(captureStart);
          assertThat(matcher.end(1)).as(trial.id()).isEqualTo(captureStart + 1);
        }
        Object actual =
            workload
                .operation()
                .execute(
                    trial.variant(),
                    workload.patterns(),
                    List.of(compiled),
                    inputs,
                    workload.groups(),
                    trial.variant().prepareReplacement(workload.replacement()),
                    workload.limit(),
                    workload.lifecycle(),
                    workload.flagSet(),
                    workload.seed(),
                    workload.count(),
                    workload.mode());
        Object expected =
            switch (workload.operation()) {
              case FIND, FIND_GROUP_PRESENT -> matched;
              case FIND_GROUP -> matched ? "a" : null;
              case REPLACE_ALL, REPLACE_ALL_FUNCTION -> matched ? "a" : input;
              default -> throw new AssertionError(workload.operation());
            };
        assertThat(actual).as(trial.id()).isEqualTo(expected);
        // Reuse the expanded plan and compiled pattern while checking every bound operation.
        // Runner preparation itself is covered by the representative entry-point tests below.
        workload
            .operation()
            .bind(
                trial.variant(),
                workload.patterns(),
                List.of(compiled),
                inputs,
                workload.groups(),
                trial.variant().prepareReplacement(workload.replacement()),
                workload.limit(),
                workload.lifecycle(),
                workload.flagSet(),
                workload.seed(),
                workload.count(),
                workload.mode())
            .run(blackhole);
      }
    }
  }

  @Test
  void functionalReplacementCapabilityRequiresTheNativeApi() {
    for (RegexEngineVariant variant : RegexEngineVariant.values()) {
      assertThat(BenchmarkOperation.REPLACE_ALL_FUNCTION.isSupportedBy(variant))
          .as(variant.id())
          .isEqualTo(
              variant == RegexEngineVariant.SAFERE_STRING
                  || variant == RegexEngineVariant.JDK_STRING);
    }
  }

  @Test
  void everyRunnableRe2jPatternCompiles() {
    List<MaterializedExecutionPlan.Entry> entries =
        MaterializedExecutionPlan.load().entries().stream()
            .filter(entry -> entry.engineId().equals("re2j-string") && entry.runnable())
            .toList();
    assertThat(entries).isNotEmpty();
    for (MaterializedExecutionPlan.Entry entry : entries) {
      for (String pattern : entry.workload().patterns()) {
        assertThatCode(() -> com.google.re2j.Pattern.compile(pattern))
            .as("%s pattern %s", entry.id(), pattern)
            .doesNotThrowAnyException();
      }
    }
  }

  @Test
  void workloadIdsAreUniqueStableReportIdentities() {
    List<String> ids =
        CrossEngineBenchmarkPlan.load().workloads().stream().map(CrossEngineWorkload::id).toList();

    assertThat(ids).doesNotHaveDuplicates();
    assertThat(ids)
        .contains(
            "RegexBenchmark.literalMatch",
            "ApplicationBenchmark.uuidValidation",
            "RealWorldRegexBenchmark.runBenchmark.mapFieldPath.match.1000",
            "HttpBenchmark.httpFull",
            "SearchScalingBenchmark.searchEasyFail.1024",
            "FanoutBenchmark.fanoutUnicode.1024");
    assertThat(ids).hasSizeGreaterThanOrEqualTo(MINIMUM_CROSS_ENGINE_WORKLOADS);
  }

  @Test
  void everyWorkloadEnginePairIsScheduledOrExplicitlyExcluded() {
    CrossEngineBenchmarkPlan plan = CrossEngineBenchmarkPlan.load();
    List<CrossEngineBenchmarkPlan.Trial> allTrials =
        List.of(
                plan.trials(CrossEngineWorkload.TimingGroup.NANOSECONDS),
                plan.trials(CrossEngineWorkload.TimingGroup.MICROSECONDS),
                plan.trials(CrossEngineWorkload.TimingGroup.MILLISECONDS))
            .stream()
            .flatMap(List::stream)
            .toList();
    Set<String> accounted = new HashSet<>();
    allTrials.forEach(trial -> assertThat(accounted.add(trial.id())).isTrue());
    plan.exclusions()
        .forEach(exclusion ->
            assertThat(accounted.add(exclusion.workloadId() + "@" + exclusion.engineId()))
                .isTrue());

    for (CrossEngineWorkload workload : plan.workloads()) {
      assertThat(
              allTrials.stream()
                  .filter(trial -> trial.workload().id().equals(workload.id()))
                  .map(CrossEngineBenchmarkPlan.Trial::variant))
          .containsAnyOf(RegexEngineVariant.SAFERE_STRING, RegexEngineVariant.SAFERE_UTF8);
    }
    assertThat(allTrials).hasSizeGreaterThanOrEqualTo(MINIMUM_RUNNABLE_CROSS_ENGINE_TRIALS);
    Set<String> expected = new HashSet<>();
    for (CrossEngineWorkload workload : plan.workloads()) {
      for (RegexEngineVariant variant : RegexEngineVariant.values()) {
        expected.add(workload.id() + "@" + variant.id());
      }
    }
    assertThat(accounted).containsExactlyInAnyOrderElementsOf(expected);
  }

  @Test
  void utf8TrialPreparationRejectsTheWrongProviderBeforeMeasurement() {
    String property = "org.safere.experimental.vectorScanProvider";
    String previous = System.getProperty(property);
    try {
      System.clearProperty(property);
      assertThatThrownBy(() ->
              CrossEngineTrialRunner.prepare(
                  "RegexBenchmark.emailFind@safere-utf8-vector",
                  CrossEngineWorkload.TimingGroup.NANOSECONDS))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("requires -D" + property + "=vector");
      assertThatThrownBy(() ->
              SpecializedTrialRunner.prepare(
                  "Utf8MatchingBenchmark.captureBounds.numbered@safere-utf8-vector"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("requires -D" + property + "=vector");
      System.setProperty(property, "vector");
      assertThatThrownBy(() ->
              CrossEngineTrialRunner.prepare(
                  "RegexBenchmark.emailFind@safere-utf8",
                  CrossEngineWorkload.TimingGroup.NANOSECONDS))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("requires default provider selection");
    } finally {
      if (previous == null) {
        System.clearProperty(property);
      } else {
        System.setProperty(property, previous);
      }
    }
  }

  @Test
  void vectorUtf8PreservesWorkloadsExclusionsAndProviderMetadata() {
    BenchmarkCollectionPlan plan = BenchmarkCollectionPlan.load();
    List<BenchmarkCollectionPlan.CollectionTrial> defaults =
        plan.reportPlan().trials().stream()
            .filter(trial -> trial.executionVariant().equals("safere-utf8"))
            .toList();
    List<BenchmarkCollectionPlan.CollectionTrial> vector =
        plan.reportPlan().trials().stream()
            .filter(trial -> trial.executionVariant().equals("safere-utf8-vector"))
            .toList();
    assertThat(vector)
        .isNotEmpty()
        .allSatisfy(trial -> assertThat(trial.requestedScanProvider()).isEqualTo("vector"));
    assertThat(vector)
        .extracting(BenchmarkCollectionPlan.CollectionTrial::workloadId)
        .containsExactlyElementsOf(
            defaults.stream().map(BenchmarkCollectionPlan.CollectionTrial::workloadId).toList());
    assertThat(vector)
        .extracting(BenchmarkCollectionPlan.CollectionTrial::measurement)
        .containsExactlyElementsOf(
            defaults.stream().map(BenchmarkCollectionPlan.CollectionTrial::measurement).toList());
    assertThat(
            plan.reportPlan().exclusions().stream()
                .filter(row -> row.executionVariant().equals("safere-utf8-vector"))
                .map(row -> row.workloadId() + ":" + row.kind() + ":" + row.reason()))
        .containsExactlyElementsOf(
            plan.reportPlan().exclusions().stream()
                .filter(row -> row.executionVariant().equals("safere-utf8"))
                .map(row -> row.workloadId() + ":" + row.kind() + ":" + row.reason())
                .toList());
  }

  @Test
  void executionGroupsIsolateProvidersAndPreserveFullAndSmokeMembership() {
    BenchmarkCollectionPlan plan = BenchmarkCollectionPlan.load();
    for (boolean smoke : List.of(false, true)) {
      List<BenchmarkCollectionPlan.Runner> groups = plan.executionRunners(false, smoke);
      assertThat(groups)
          .allSatisfy(group -> {
            String provider =
                RegexEngineVariant.fromId(
                        group
                            .trialIds()
                            .getFirst()
                            .substring(group.trialIds().getFirst().lastIndexOf('@') + 1))
                    .scanProvider();
            assertThat(group.trialIds())
                .allMatch(id -> BenchmarkCollectionPlan.matchesScanProvider(id, provider));
          });
      assertThat(groups.stream().flatMap(group -> group.trialIds().stream()))
          .containsExactlyInAnyOrderElementsOf(
              plan.reportPlan(smoke).trials().stream()
                  .map(BenchmarkCollectionPlan.CollectionTrial::id)
                  .toList());
    }
    assertThat(plan.forScanProvider("vector").runners())
        .flatExtracting(BenchmarkCollectionPlan.Runner::trialIds)
        .isNotEmpty()
        .allMatch(id -> id.endsWith("@safere-utf8-vector"));
    assertThat(plan.forScanProvider("default").allocationRunners())
        .flatExtracting(BenchmarkCollectionPlan.Runner::trialIds)
        .noneMatch(id -> id.endsWith("@safere-utf8-vector"));
    assertThat(
            plan.executionRunners(true, false).stream().flatMap(group -> group.trialIds().stream()))
        .containsExactlyInAnyOrderElementsOf(
            plan.allocationRunners().stream().flatMap(group -> group.trialIds().stream()).toList());
  }

  @Test
  void longRecitationListsExcludeOnlyJdkTrialsThatOverflowItsStack() {
    List<MaterializedExecutionPlan.Entry> exclusions =
        CrossEngineBenchmarkPlan.load().exclusions().stream()
            .filter(exclusion ->
                exclusion
                        .workloadId()
                        .startsWith("RealWorldRegexBenchmark.runBenchmark.recitation.")
                    && exclusion.engineId().equals("jdk-string"))
            .toList();

    assertThat(exclusions)
        .extracting(MaterializedExecutionPlan.Entry::id)
        .containsExactly(
            "RealWorldRegexBenchmark.runBenchmark.recitation.match.10000@jdk-string",
            "RealWorldRegexBenchmark.runBenchmark.recitation.match.100000@jdk-string",
            "RealWorldRegexBenchmark.runBenchmark.recitation.noMatch.10000@jdk-string",
            "RealWorldRegexBenchmark.runBenchmark.recitation.noMatch.100000@jdk-string");
    assertThat(exclusions)
        .allSatisfy(exclusion -> {
          assertThat(exclusion.exclusion().kind()).isEqualTo("explicitTrialExclusion");
          assertThat(exclusion.exclusion().reason())
              .isEqualTo(
                  "JDK's backtracking matcher throws StackOverflowError for the nested "
                      + "quantified recitation pattern at these input sizes");
        });
  }

  @Test
  void trialExpansionIsDeterministicAndPreservesTimingGroups() {
    CrossEngineBenchmarkPlan first = CrossEngineBenchmarkPlan.load();
    CrossEngineBenchmarkPlan second = CrossEngineBenchmarkPlan.load();

    Set<String> trialIds = new HashSet<>();
    for (CrossEngineWorkload.TimingGroup timingGroup : CrossEngineWorkload.TimingGroup.values()) {
      List<CrossEngineBenchmarkPlan.Trial> firstTrials = first.trials(timingGroup);
      assertThat(firstTrials)
          .as(timingGroup.name())
          .isNotEmpty()
          .allMatch(trial -> trial.workload().timingGroup() == timingGroup)
          .extracting(CrossEngineBenchmarkPlan.Trial::id)
          .containsExactlyElementsOf(
              second.trials(timingGroup).stream().map(CrossEngineBenchmarkPlan.Trial::id).toList());
      firstTrials.forEach(trial -> assertThat(trialIds.add(trial.id())).as(trial.id()).isTrue());
    }
  }

  @Test
  void utf8ParticipatesInEveryOperationSupportedByItsNativeApi() {
    CrossEngineBenchmarkPlan plan = CrossEngineBenchmarkPlan.load();
    List<CrossEngineBenchmarkPlan.Trial> utf8Trials =
        List.of(
                plan.trials(CrossEngineWorkload.TimingGroup.NANOSECONDS),
                plan.trials(CrossEngineWorkload.TimingGroup.MICROSECONDS))
            .stream()
            .flatMap(List::stream)
            .filter(trial -> trial.variant() == RegexEngineVariant.SAFERE_UTF8)
            .toList();

    assertThat(utf8Trials).isNotEmpty();
    assertThat(utf8Trials)
        .extracting(trial -> trial.workload().operation())
        .contains(
            BenchmarkOperation.FIND,
            BenchmarkOperation.FIND_ALL_COUNT,
            BenchmarkOperation.MATCHES,
            BenchmarkOperation.MATCHES_CORPUS,
            BenchmarkOperation.LOOKING_AT,
            BenchmarkOperation.MATCHER_RESET_FIND,
            BenchmarkOperation.MATCHER_REGION_FIND,
            BenchmarkOperation.FIND_GROUP_PRESENT,
            BenchmarkOperation.REPLACE_FIRST,
            BenchmarkOperation.REPLACE_ALL,
            BenchmarkOperation.REPLACE_ALL_LENGTH_SUM,
            BenchmarkOperation.MANUAL_REPLACE_ALL,
            BenchmarkOperation.CAPTURE_GROUPS,
            BenchmarkOperation.FIND_GROUP)
        .doesNotContain(BenchmarkOperation.SPLIT_LENGTH_SUM);
  }

  @Test
  void utf8ReplacementTrialsCoverGenericBenchmarkFamilies() {
    CrossEngineBenchmarkPlan plan = CrossEngineBenchmarkPlan.load();

    assertThat(
            List.of(
                    plan.trials(CrossEngineWorkload.TimingGroup.NANOSECONDS),
                    plan.trials(CrossEngineWorkload.TimingGroup.MICROSECONDS))
                .stream()
                .flatMap(List::stream)
                .filter(trial -> trial.variant() == RegexEngineVariant.SAFERE_UTF8)
                .map(CrossEngineBenchmarkPlan.Trial::id))
        .contains(
            "ApplicationBenchmark.secretRedaction@safere-utf8",
            "RealWorldRegexBenchmark.runBenchmark.markupImageLink.match.1000@safere-utf8",
            "ReplaceBenchmark.literalReplaceFirst@safere-utf8",
            "ReplaceBenchmark.literalReplaceFirstNoMatch@safere-utf8",
            "ReplaceBenchmark.startAnchoredReplaceFirstNoMatch.literal@safere-utf8",
            "ReplaceBenchmark.startAnchoredReplaceFirstNoMatch.charClass@safere-utf8",
            "ReplaceBenchmark.startAnchoredReplaceAllNoMatch.literal@safere-utf8",
            "ReplaceBenchmark.startAnchoredReplaceAllNoMatch.charClass@safere-utf8",
            "ReplaceBenchmark.pigLatinReplaceAll@safere-utf8",
            "ReplaceBenchmark.anchoredReplace@safere-utf8",
            "ReplaceBenchmark.manualReplaceAll@safere-utf8");
  }

  @Test
  void unsupportedCombinationIsDistinctFromMissingImplementation() {
    CrossEngineBenchmarkPlan plan = CrossEngineBenchmarkPlan.load();

    assertThat(plan.resolve("RegexBenchmark.literalMatch@safere-utf8")).isNotNull();
    assertThat(plan.resolve("HttpBenchmark.httpExtract@safere-utf8")).isNotNull();
    assertThatThrownBy(() -> plan.resolve("Issue481ScalingBenchmark.splitWords.128@safere-utf8"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported cross-engine combination")
        .hasMessageContaining("split");
    assertThatThrownBy(() -> plan.resolve("missing.workload@safere-string"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unknown cross-engine workload ID: missing.workload");
  }

  @Test
  void inputRepresentationsExposeTheirTimingBoundaries() {
    assertThat(RegexEngineVariant.SAFERE_STRING.inputRepresentation())
        .isEqualTo(RegexEngineVariant.InputRepresentation.JAVA_STRING);
    assertThat(RegexEngineVariant.SAFERE_UTF8.inputRepresentation())
        .isEqualTo(RegexEngineVariant.InputRepresentation.PREEXISTING_UTF8);
    assertThat(RegexEngineVariant.RE2_FFM_STRING_CONVERSION.inputRepresentation())
        .isEqualTo(RegexEngineVariant.InputRepresentation.JAVA_STRING_WITH_TIMED_UTF8_CONVERSION);
  }

  @Test
  void re2BasedAdaptersShareARegexSyntaxProfile() {
    assertThat(RegexEngineVariant.RE2J_STRING.patternProfile()).isEqualTo("re2");
    assertThat(RegexEngineVariant.RE2_FFM_STRING_CONVERSION.patternProfile()).isEqualTo("re2");
  }

  @ParameterizedTest
  @CsvSource({
    "0,15",
    "CASE_INSENSITIVE,8",
    "CASE_INSENSITIVE_UNICODE_CASE,7",
    "CASE_INSENSITIVE_UNICODE_CHARACTER_CLASS,5"
  })
  void unicodeCompileRowsAdmitOnlyEquivalentRe2jOptions(String flagSet, int expectedRunnable) {
    MaterializedExecutionPlan plan = MaterializedExecutionPlan.load();
    List<MaterializedExecutionPlan.Entry> rows =
        plan.entries().stream()
            .filter(entry -> entry.engineId().equals("re2j-string"))
            .filter(entry -> entry.workloadId().startsWith("UnicodeCompileBenchmark.compile."))
            .filter(entry -> entry.workloadId().endsWith("." + flagSet))
            .toList();
    assertThat(rows).hasSize(19);
    assertThat(rows.stream().filter(MaterializedExecutionPlan.Entry::runnable))
        .hasSize(expectedRunnable);
    for (MaterializedExecutionPlan.Entry row : rows) {
      if (!row.runnable()) {
        assertThat(row.exclusion().kind()).isIn("unsupportedSyntax", "explicitTrialExclusion");
        assertThat(row.exclusion().reason()).isNotBlank();
      }
    }
    for (String property : List.of("alphabetic", "ideographic", "emoji", "extendedPictographic")) {
      MaterializedExecutionPlan.Entry row =
          plan.resolve(
              "UnicodeCompileBenchmark.compile." + property + "." + flagSet + "@re2j-string");
      assertThat(row.runnable()).isFalse();
      assertThat(row.exclusion().kind()).isEqualTo("unsupportedSyntax");
    }
  }

  @Test
  void admittedUnicodeCompileRowsPreserveDeclaredSemantics() {
    MaterializedExecutionPlan plan = MaterializedExecutionPlan.load();
    // Stable characters exercise folding, categories, scripts, blocks and predefined classes.
    // Engine Unicode database versions differ; this is not a Unicode-version conformance test.
    List<String> inputs =
        List.of(
            "", "a", "A", "I", "i", "İ", "ı", "ſ", "K", "ß", "ẞ", "ǅ", "ǆ", "Ǆ", "α", "Α", "Σ", "ς",
            "σ", "中", "𠀀", "𐐀", "𐐨", "1", "١", "_", " ", "\t", "\n", "\r", "\f", "\u000B",
            "\u00A0", "\u2003", "!", "abc_12");
    for (MaterializedExecutionPlan.Entry row : plan.entries()) {
      if (!row.engineId().equals("re2j-string")
          || !row.runnable()
          || !(row.workloadId().startsWith("UnicodeCompileBenchmark.compile.")
              || row.workloadId().startsWith("UnicodeFirstCompileBenchmark.firstCompile."))) {
        continue;
      }
      String flagSet =
          ((DeclarativeBenchmarkPlan.RecipeString) row.workload().arguments().get("flagSet"))
              .value();
      String canonical =
          plan.resolve(row.workloadId() + "@jdk-string").workload().patterns().getFirst();
      java.util.regex.Pattern jdk =
          java.util.regex.Pattern.compile(canonical, BenchmarkFlags.parse(flagSet));
      com.google.re2j.Pattern re2j =
          (com.google.re2j.Pattern)
              RegexEngineVariant.RE2J_STRING.compileForBenchmark(
                  row.workload().patterns().getFirst(), flagSet);
      for (String input : inputs) {
        assertThat(re2j.matcher(input).matches())
            .as("%s input %s", row.id(), input)
            .isEqualTo(jdk.matcher(input).matches());
      }
      if (!flagSet.equals("0")) {
        java.util.regex.Pattern unflaggedJdk = java.util.regex.Pattern.compile(canonical);
        com.google.re2j.Pattern unflaggedRe2j =
            com.google.re2j.Pattern.compile(row.workload().patterns().getFirst());
        for (int codePoint = 0; codePoint <= Character.MAX_VALUE; codePoint++) {
          if (Character.isSurrogate((char) codePoint)) {
            continue;
          }
          String input = Character.toString(codePoint);
          // Ignore existing Unicode database differences; isolate the admitted flag mapping.
          if (unflaggedJdk.matcher(input).matches() != unflaggedRe2j.matcher(input).matches()) {
            continue;
          }
          assertThat(re2j.matcher(input).matches())
              .as("%s U+%04X", row.id(), codePoint)
              .isEqualTo(jdk.matcher(input).matches());
        }
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"0,9", "CASE_INSENSITIVE_UNICODE_CASE,4", "UNICODE_CHARACTER_CLASS,0"})
  void coldUnicodeCompileRowsRetainEquivalentRe2jOptions(String flagSet, int expectedRunnable) {
    assertThat(
            MaterializedExecutionPlan.load().entries().stream()
                .filter(entry -> entry.engineId().equals("re2j-string") && entry.runnable())
                .filter(entry ->
                    entry.workloadId().startsWith("UnicodeFirstCompileBenchmark.firstCompile."))
                .filter(entry -> entry.workloadId().endsWith("." + flagSet)))
        .hasSize(expectedRunnable);
  }

  @ParameterizedTest
  @CsvSource({
    "uppercaseLetter,CASE_INSENSITIVE_UNICODE_CASE,ı",
    "lowercaseLetter,CASE_INSENSITIVE,A",
    "titlecaseLetter,CASE_INSENSITIVE,A",
    "letterIntersection,CASE_INSENSITIVE,a",
    "blockBasicLatin,CASE_INSENSITIVE,K",
    "asciiLower,CASE_INSENSITIVE,ſ",
    "asciiRange,CASE_INSENSITIVE_UNICODE_CASE,İ",
    "word,CASE_INSENSITIVE_UNICODE_CASE,K",
    "word,CASE_INSENSITIVE_UNICODE_CHARACTER_CLASS,α",
    "digit,CASE_INSENSITIVE_UNICODE_CHARACTER_CLASS,١",
    "space,CASE_INSENSITIVE_UNICODE_CHARACTER_CLASS,'\u2003'"
  })
  void excludedUnicodeCompileRowsHaveDemonstrablyDifferentFlagSemantics(
      String regex, String flagSet, String input) {
    MaterializedExecutionPlan plan = MaterializedExecutionPlan.load();
    String prefix = "UnicodeCompileBenchmark.compile." + regex + ".";
    assertThat(plan.resolve(prefix + flagSet + "@re2j-string").exclusion().kind())
        .isEqualTo("explicitTrialExclusion");
    String canonical =
        plan.resolve(prefix + flagSet + "@jdk-string").workload().patterns().getFirst();
    String alternate = plan.resolve(prefix + "0@re2j-string").workload().patterns().getFirst();
    assertThat(
            com.google.re2j.Pattern.compile(alternate, com.google.re2j.Pattern.CASE_INSENSITIVE)
                .matcher(input)
                .matches())
        .isNotEqualTo(
            java.util.regex.Pattern.compile(canonical, BenchmarkFlags.parse(flagSet))
                .matcher(input)
                .matches());
  }

  @ParameterizedTest
  @CsvSource({
    "0,0",
    "CASE_INSENSITIVE,1",
    "CASE_INSENSITIVE_UNICODE_CASE,1",
    "CASE_INSENSITIVE_UNICODE_CHARACTER_CLASS,1"
  })
  void re2jCompileAdapterMapsFlagIdentitiesExplicitly(String flagSet, int expectedFlags) {
    com.google.re2j.Pattern pattern =
        (com.google.re2j.Pattern) RegexEngineVariant.RE2J_STRING.compileForBenchmark("a", flagSet);
    assertThat(pattern.flags()).isEqualTo(expectedFlags);
    assertThat(pattern.matcher("A").matches()).isEqualTo(expectedFlags != 0);
    assertThat(pattern.matcher("\n").matches()).isFalse();
  }

  @Test
  void re2jCompileAdapterRejectsUnsupportedFlagIdentities() {
    assertThatThrownBy(() ->
            RegexEngineVariant.RE2J_STRING.compileForBenchmark("a", "UNICODE_CHARACTER_CLASS"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("UNICODE_CHARACTER_CLASS");
    assertThatThrownBy(() -> RegexEngineVariant.RE2J_STRING.compileForBenchmark("a", "unknown"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @CsvSource({
    "newPerInvocation,capture,5,3",
    "retained,capture,1,3",
    "newPerInvocation,boolean,5,0",
    "retained,boolean,1,0"
  })
  void sampledCaptureWorkloadHonorsMatcherLifetimeAndReadFrequency(
      String reuse, String mode, int expectedMatchers, int expectedReads) {
    int[] creations = {0};
    int[] resets = {0};
    int[] reads = {0};
    RegexEngineVariant.CompiledRegex pattern =
        new RegexEngineVariant.CompiledRegex() {
          @Override
          public RegexEngineVariant.MatchCursor matcher(RegexEngineVariant.RegexInput input) {
            creations[0]++;
            return new RegexEngineVariant.MatchCursor() {
              @Override
              public boolean find() {
                return true;
              }

              @Override
              public int start(int group) {
                assertThat(group).isEqualTo(1);
                reads[0]++;
                return 0;
              }

              @Override
              public void reset() {
                resets[0]++;
              }
            };
          }
        };
    BenchmarkOperation.BenchmarkTask task =
        BenchmarkOperation.FIND_SAMPLED_GROUP.bind(
            RegexEngineVariant.SAFERE_STRING,
            List.of("(a)"),
            List.of(pattern),
            List.of(new RegexEngineVariant.StringRegexInput("a")),
            new int[] {1},
            null,
            0,
            new DeclarativeBenchmarkPlan.MatcherLifecycle(
                DeclarativeBenchmarkPlan.MatcherReuse.fromJson(reuse), List.of()),
            null,
            0,
            2,
            mode);
    Blackhole blackhole =
        new Blackhole(
            "Today's password is swordfish. I understand instantiating Blackholes directly is"
                + " dangerous.");
    for (int i = 0; i < 5; i++) {
      task.run(blackhole);
    }
    assertThat(creations[0]).isEqualTo(expectedMatchers);
    assertThat(reads[0]).isEqualTo(expectedReads);
    assertThat(resets[0]).isEqualTo(reuse.equals("retained") ? 5 : 0);
  }

  @Test
  void matcherLifecycleControlsCursorConstruction() {
    Blackhole blackhole =
        new Blackhole(
            "Today's password is swordfish. I understand instantiating Blackholes directly is"
                + " dangerous.");
    for (BenchmarkOperation operation :
        List.of(BenchmarkOperation.MATCHER_REGION_FIND, BenchmarkOperation.MATCHER_RESET_FIND)) {
      for (DeclarativeBenchmarkPlan.MatcherReuse reuse :
          List.of(
              DeclarativeBenchmarkPlan.MatcherReuse.RETAINED,
              DeclarativeBenchmarkPlan.MatcherReuse.NEW_PER_INVOCATION)) {
        List<RegexEngineVariant.MatchCursor> created = new ArrayList<>();
        RegexEngineVariant.CompiledRegex pattern =
            new RegexEngineVariant.CompiledRegex() {
              @Override
              public RegexEngineVariant.MatchCursor matcher(RegexEngineVariant.RegexInput input) {
                RegexEngineVariant.MatchCursor cursor =
                    new RegexEngineVariant.MatchCursor() {
                      private boolean found;

                      @Override
                      public boolean find() {
                        boolean result = !found;
                        found = true;
                        return result;
                      }

                      @Override
                      public void reset() {
                        found = false;
                      }

                      @Override
                      public void region(int start, int end) {
                        assertThat(start).isEqualTo(1);
                        assertThat(end).isEqualTo(3);
                        found = false;
                      }
                    };
                created.add(cursor);
                return cursor;
              }
            };
        List<DeclarativeBenchmarkPlan.LifecycleStep> steps =
            operation == BenchmarkOperation.MATCHER_REGION_FIND
                ? List.of(
                    new DeclarativeBenchmarkPlan.LifecycleStep(
                        DeclarativeBenchmarkPlan.LifecycleStepKind.REGION, 1, 3, null))
                : List.of(
                    new DeclarativeBenchmarkPlan.LifecycleStep(
                        DeclarativeBenchmarkPlan.LifecycleStepKind.RESET, null, null, null));
        BenchmarkOperation.BenchmarkTask task =
            operation.bind(
                RegexEngineVariant.SAFERE_STRING,
                List.of("x"),
                List.of(pattern),
                List.of(new RegexEngineVariant.StringRegexInput("abcd")),
                new int[0],
                null,
                0,
                new DeclarativeBenchmarkPlan.MatcherLifecycle(reuse, steps),
                null,
                0,
                0,
                "boolean");
        boolean retained = reuse == DeclarativeBenchmarkPlan.MatcherReuse.RETAINED;
        assertThat(created).as("%s %s binding", operation, reuse).hasSize(retained ? 1 : 0);
        task.run(blackhole);
        task.run(blackhole);
        assertThat(created).as("%s %s invocations", operation, reuse).hasSize(retained ? 1 : 2);
      }
    }
  }

  @Test
  void boundTaskReusesInputPreparedBeforeTimedInvocation() {
    RegexEngineVariant.RegexInput preparedInput =
        new RegexEngineVariant.StringRegexInput("materialized before binding");
    List<RegexEngineVariant.RegexInput> observedInputs = new ArrayList<>();
    RegexEngineVariant.CompiledRegex pattern =
        new RegexEngineVariant.CompiledRegex() {
          @Override
          public boolean find(RegexEngineVariant.RegexInput input) {
            observedInputs.add(input);
            return true;
          }
        };
    BenchmarkOperation.BenchmarkTask task =
        BenchmarkOperation.FIND.bind(
            RegexEngineVariant.SAFERE_STRING,
            List.of("x"),
            List.of(pattern),
            List.of(preparedInput),
            new int[0],
            null,
            0,
            DeclarativeBenchmarkPlan.MatcherLifecycle.NONE,
            null,
            0,
            0,
            null);
    Blackhole blackhole =
        new Blackhole(
            "Today's password is swordfish. I understand instantiating Blackholes directly is"
                + " dangerous.");

    task.run(blackhole);
    task.run(blackhole);

    assertThat(observedInputs)
        .hasSize(2)
        .allSatisfy(input -> assertThat(input).isSameAs(preparedInput));
  }

  @Test
  void preparationValidatesDeclaredExpectedResult() {
    try (CrossEngineTrialRunner ignored =
        CrossEngineTrialRunner.prepare(
            "ApplicationBenchmark.uuidValidation@safere-string",
            CrossEngineWorkload.TimingGroup.NANOSECONDS)) {
      assertThat(ignored).isNotNull();
    }
  }

  @Test
  void expandedUtf8OperationsPrepareAndRunThroughTheGenericAdapter() {
    CrossEngineBenchmarkPlan plan = CrossEngineBenchmarkPlan.load();
    Blackhole blackhole =
        new Blackhole(
            "Today's password is swordfish. I understand instantiating Blackholes directly is"
                + " dangerous.");
    for (String id :
        List.of(
            "RegexBenchmark.literalMatch@safere-utf8",
            "ApplicationBenchmark.uuidValidation@safere-utf8",
            "HttpBenchmark.httpFull@safere-utf8",
            "HttpBenchmark.httpExtract@safere-utf8",
            "ApplicationBenchmark.stackTraceExtract@safere-utf8",
            "CaptureScalingBenchmark.capture1@safere-utf8",
            "CaptureRecoveryBenchmark.stack.text@safere-utf8",
            "CaptureRecoveryBenchmark.stack.mixed@safere-utf8",
            "CaptureRecoveryBenchmark.scheme.rejection@safere-utf8",
            "CaptureRecoveryBenchmark.stack.matches.512@safere-utf8",
            "CaptureRecoveryBenchmark.stack.anchoredFind.513@safere-utf8",
            "MatcherApiBenchmark.lookingAt@safere-utf8",
            "MatcherApiBenchmark.regionFind@safere-utf8",
            "MatcherApiBenchmark.resetAndFind@safere-utf8",
            "Issue481ScalingBenchmark.blockFind.128@safere-utf8",
            "PathologicalBenchmark.pathological.10@safere-utf8")) {
      CrossEngineBenchmarkPlan.Trial trial = plan.resolve(id);
      try (CrossEngineTrialRunner runner =
          CrossEngineTrialRunner.prepare(id, trial.workload().timingGroup())) {
        runner.run(blackhole);
      }
    }
    assertThatThrownBy(
            () -> plan.resolve("MatcherApiBenchmark.resetAndFind@re2-ffm-string-conversion"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupportedTimingBoundary");
  }

  @Test
  void ffmPublicMatcherOperationsPrepareAndRunThroughTheGenericAdapter() {
    CrossEngineBenchmarkPlan plan = CrossEngineBenchmarkPlan.load();
    Blackhole blackhole =
        new Blackhole(
            "Today's password is swordfish. I understand instantiating Blackholes directly is"
                + " dangerous.");
    for (String workload :
        List.of(
            "MatcherApiBenchmark.lookingAt",
            "MatcherApiBenchmark.newMatcherRegionFind",
            "MatcherApiBenchmark.newMatcherRegionMatches")) {
      String id = workload + "@re2-ffm-string-conversion";
      CrossEngineBenchmarkPlan.Trial trial = plan.resolve(id);
      try (CrossEngineTrialRunner runner =
          CrossEngineTrialRunner.prepare(id, trial.workload().timingGroup())) {
        runner.run(blackhole);
        runner.run(blackhole);
      }
    }
    assertThat(RegexEngineVariant.RE2_FFM_STRING_CONVERSION.capabilities())
        .doesNotContain(EngineCapability.APPEND_REPLACEMENT);
  }

  @Test
  void utf8ReplacementOperationsPrepareAndRunThroughTheGenericAdapter() {
    CrossEngineBenchmarkPlan plan = CrossEngineBenchmarkPlan.load();
    Blackhole blackhole =
        new Blackhole(
            "Today's password is swordfish. I understand instantiating Blackholes directly is"
                + " dangerous.");
    for (String id :
        List.of(
            "ReplaceBenchmark.literalReplaceFirst@safere-utf8",
            "ReplaceBenchmark.literalReplaceFirstNoMatch@safere-utf8",
            "ReplaceBenchmark.startAnchoredReplaceFirstNoMatch.literal@safere-utf8",
            "ReplaceBenchmark.startAnchoredReplaceFirstNoMatch.charClass@safere-utf8",
            "ReplaceBenchmark.startAnchoredReplaceAllNoMatch.literal@safere-utf8",
            "ReplaceBenchmark.startAnchoredReplaceAllNoMatch.charClass@safere-utf8",
            "ReplaceBenchmark.emptyReplaceAll@safere-utf8",
            "ReplaceBenchmark.pigLatinReplaceAll@safere-utf8",
            "ReplaceBenchmark.manualReplaceAll@safere-utf8",
            "ApplicationBenchmark.secretRedaction@safere-utf8",
            "RealWorldRegexBenchmark.runBenchmark.markupImageLink.match.1000@safere-utf8")) {
      CrossEngineBenchmarkPlan.Trial trial = plan.resolve(id);
      try (CrossEngineTrialRunner runner =
          CrossEngineTrialRunner.prepare(id, trial.workload().timingGroup())) {
        runner.run(blackhole);
      }
    }
  }

  @Test
  void utf8ReplacementAdapterExpandsLiteralNumberedAndNamedTemplates() {
    RegexEngineVariant variant = RegexEngineVariant.SAFERE_UTF8;
    RegexEngineVariant.RegexInput input =
        RegexEngineVariant.utf8Input("alpha=one&beta=two".getBytes(StandardCharsets.UTF_8));

    try (RegexEngineVariant.CompiledRegex literal = variant.compile("&");
        RegexEngineVariant.CompiledRegex numbered = variant.compile("([a-z]+)=([a-z]+)");
        RegexEngineVariant.CompiledRegex named =
            variant.compile("(?<key>[a-z]+)=(?<value>[a-z]+)")) {
      assertThat(literal.replaceFirst(input, variant.prepareReplacement(";")).validationValue())
          .isEqualTo("alpha=one;beta=two");
      assertThat(numbered.replaceAll(input, variant.prepareReplacement("$2:$1")).validationValue())
          .isEqualTo("one:alpha&two:beta");
      assertThat(
              named
                  .replaceAll(input, variant.prepareReplacement("${key}=[${value}]"))
                  .validationValue())
          .isEqualTo("alpha=[one]&beta=[two]");
    }
  }

  @Test
  void groupParticipationUsesBoundsWithoutMaterializingCaptureText() {
    RegexEngineVariant.MatchCursor cursor =
        new RegexEngineVariant.MatchCursor() {
          @Override
          public int start(int group) {
            return group == 1 ? 4 : -1;
          }

          @Override
          public String group(int group) {
            throw new AssertionError("group participation must not materialize capture text");
          }
        };

    assertThat(cursor.groupParticipated(1)).isTrue();
    assertThat(cursor.groupParticipated(2)).isFalse();
  }

  @ParameterizedTest
  @EnumSource(
      value = BenchmarkOperation.class,
      names = {
        "COMPILE",
        "COMPILE_AND_FIND",
        "COMPILE_AND_MATCHES",
        "COMPILE_AND_FIND_GROUP",
        "COMPILE_AND_REPLACE_ALL"
      })
  void coldStartPreparationDefersCompilationUntilTheMeasuredTask(BenchmarkOperation operation) {
    CrossEngineWorkload workload =
        new CrossEngineWorkload(
            "Synthetic.coldCompile",
            operation,
            List.of("("),
            operation == BenchmarkOperation.COMPILE
                ? List.of()
                : CrossEngineBenchmarkPlan.load()
                    .resolve("RegexBenchmark.literalMatch@safere-string")
                    .workload()
                    .inputKeys(),
            new int[] {0},
            "$0",
            null,
            0,
            DeclarativeBenchmarkPlan.MatcherLifecycle.NONE,
            "",
            0,
            1,
            null,
            new DeclarativeBenchmarkPlan.Measurement(
                DeclarativeBenchmarkPlan.MeasurementMode.SINGLE_SHOT_COLD_START,
                DeclarativeBenchmarkPlan.TimingUnit.MILLISECONDS,
                EnumSet.of(
                    DeclarativeBenchmarkPlan.ExecutionConstraint.FRESH_PROCESS_PER_INVOCATION)),
            CrossEngineWorkload.TimingGroup.MILLISECONDS);
    CrossEngineBenchmarkPlan.Trial trial =
        new CrossEngineBenchmarkPlan.Trial(workload, RegexEngineVariant.SAFERE_STRING);
    Blackhole blackhole =
        new Blackhole(
            "Today's password is swordfish. I understand instantiating Blackholes directly is"
                + " dangerous.");

    try (CrossEngineTrialRunner runner =
        CrossEngineTrialRunner.prepareColdStart(
            BenchmarkData.get(), trial, CrossEngineWorkload.TimingGroup.MILLISECONDS)) {
      assertThatThrownBy(() -> runner.run(blackhole)).isInstanceOf(IllegalArgumentException.class);
      // A failed invocation has no result to validate. Validation must not recompile the pattern.
      assertThatCode(runner::validateMeasuredResult).doesNotThrowAnyException();
    }
  }

  @ParameterizedTest
  @CsvSource({
    "COMPILE_AND_FIND,safere-string",
    "COMPILE_AND_MATCHES,safere-string",
    "COMPILE_AND_FIND_GROUP_PRESENT,safere-string",
    "COMPILE_AND_FIND_GROUP_PRESENT,safere-utf8"
  })
  void coldCombinedPreparationDoesNotCompileBeforeMeasurement(String operation, String engine) {
    CrossEngineWorkload workload = coldFirstUseWorkload(BenchmarkOperation.valueOf(operation), "(");
    CrossEngineBenchmarkPlan.Trial trial =
        new CrossEngineBenchmarkPlan.Trial(workload, RegexEngineVariant.fromId(engine));
    try (CrossEngineTrialRunner runner =
        CrossEngineTrialRunner.prepareColdStart(
            BenchmarkData.get(), trial, CrossEngineWorkload.TimingGroup.MILLISECONDS)) {
      assertThatThrownBy(() -> runner.run(testBlackhole()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @ParameterizedTest
  @CsvSource({
    "FIND_GROUP_PRESENT,safere-string",
    "FIND_GROUP_PRESENT,safere-utf8",
    "COMPILE_AND_FIND_GROUP_PRESENT,safere-string",
    "COMPILE_AND_FIND_GROUP_PRESENT,safere-utf8"
  })
  void coldFirstUsePreparationDoesNotSearchOrReadCaptures(String operation, String engine) {
    // The input matches, but this pattern has no group 1. Setup must not touch that group.
    CrossEngineWorkload workload = coldFirstUseWorkload(BenchmarkOperation.valueOf(operation), "a");
    CrossEngineBenchmarkPlan.Trial trial =
        new CrossEngineBenchmarkPlan.Trial(workload, RegexEngineVariant.fromId(engine));
    try (CrossEngineTrialRunner runner =
        CrossEngineTrialRunner.prepareColdStart(
            BenchmarkData.get(), trial, CrossEngineWorkload.TimingGroup.MILLISECONDS)) {
      assertThatThrownBy(() -> runner.run(testBlackhole()))
          .isInstanceOf(IndexOutOfBoundsException.class);
    }
  }

  @Test
  void coldFirstUseFixturesMatchJdkCaptureBoundsInBothRepresentations() {
    BenchmarkData data = BenchmarkData.get();
    for (CrossEngineWorkload workload : CrossEngineBenchmarkPlan.load().workloads()) {
      if (!workload.id().startsWith("InitializationLatencyBenchmark.")) {
        continue;
      }
      String text = data.getInputString(workload.inputKeys().getFirst());
      java.util.regex.Matcher expected =
          java.util.regex.Pattern.compile(workload.patterns().getFirst()).matcher(text);
      boolean found = expected.find();
      assertThat(found).as(workload.id()).isEqualTo(workload.expected());
      if (found) {
        assertThat(expected.start(1)).isNotNegative();
      }
      for (RegexEngineVariant variant :
          List.of(RegexEngineVariant.SAFERE_STRING, RegexEngineVariant.SAFERE_UTF8)) {
        try (RegexEngineVariant.CompiledRegex pattern =
            variant.compile(workload.patterns().getFirst())) {
          RegexEngineVariant.MatchCursor actual =
              pattern.matcher(variant.prepareInputs(data, workload.inputKeys()).getFirst());
          assertThat(actual.find()).as("%s, %s", workload.id(), variant).isEqualTo(found);
          if (!found) {
            continue;
          }
          for (int group = 0; group <= 1; group++) {
            int start = expected.start(group);
            int end = expected.end(group);
            if (variant == RegexEngineVariant.SAFERE_UTF8) {
              start = text.substring(0, start).getBytes(StandardCharsets.UTF_8).length;
              end = text.substring(0, end).getBytes(StandardCharsets.UTF_8).length;
            }
            assertThat(actual.start(group)).isEqualTo(start);
            assertThat(actual.end(group)).isEqualTo(end);
          }
        }
      }
    }
  }

  @Test
  void coldBenchmarkRejectsRepeatedInvocationsInOneFork() {
    CrossEngineColdStartBenchmark benchmark = new CrossEngineColdStartBenchmark();
    benchmark.crossEngineColdStartTrial =
        "InitializationLatencyBenchmark.firstFind.graphemeCluster@safere-string";
    benchmark.setup();
    try {
      benchmark.run(testBlackhole());
      assertThatThrownBy(() -> benchmark.run(testBlackhole()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("one invocation per fresh fork");
    } finally {
      benchmark.tearDown();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"asciiWord", "javaLowerCase"})
  void coldAsciiPrimerCompilesOnlyAnUnrelatedLiteralDuringSetup(String fixture) {
    CrossEngineBenchmarkPlan plan = CrossEngineBenchmarkPlan.load();
    String prefix = "InitializationLatencyBenchmark.";
    String suffix = "." + fixture + "@safere-string";
    CrossEngineWorkload unprimed = plan.resolve(prefix + "compileAndFirstFind" + suffix).workload();
    CrossEngineBenchmarkPlan.Trial primed =
        plan.resolve(prefix + "compileAndFirstFindAfterAscii" + suffix);
    assertThat(primed.workload().patterns()).isEqualTo(unprimed.patterns());
    assertThat(primed.workload().inputKeys()).isEqualTo(unprimed.inputKeys());

    List<PatternCompiledEvent> compilations = new ArrayList<>();
    SafeReMatchDiagnostics previous = Pattern.diagnostics();
    Pattern.setDiagnostics(
        new SafeReMatchDiagnostics() {
          @Override
          public void onPatternCompiled(PatternCompiledEvent event) {
            compilations.add(event);
          }
        });
    try (CrossEngineTrialRunner runner =
        CrossEngineTrialRunner.prepareColdStart(
            BenchmarkData.get(), primed, CrossEngineWorkload.TimingGroup.MILLISECONDS)) {
      assertThat(compilations).hasSize(1);
      assertThat(compilations.getFirst().pattern().analysis().features())
          .contains(PatternFeature.LITERAL)
          .doesNotContain(PatternFeature.CAPTURES);
      runner.run(testBlackhole());
      runner.validateMeasuredResult();
      assertThat(compilations).hasSize(2);
      assertThat(compilations.getLast().pattern().analysis().features())
          .contains(PatternFeature.CAPTURES);
    } finally {
      Pattern.setDiagnostics(previous);
    }
  }

  @Test
  void existsUsesEachEnginesMatcherFreeExistencePredicate() {
    List<String> safereFrames = new ArrayList<>();
    SafeReMatchDiagnostics previous = Pattern.diagnostics();
    // With diagnostics enabled, SafeRE's predicate and Pattern.find(Utf8Input) still report an
    // operation, so the listener sees which entry point the adapter called.
    Pattern.setDiagnostics(
        new SafeReMatchDiagnostics() {
          @Override
          public void onOperationCompleted(OperationDiagnostics event) {
            StackWalker.getInstance()
                .forEach(
                    frame -> safereFrames.add(frame.getClassName() + "." + frame.getMethodName()));
          }
        });
    try {
      for (RegexEngineVariant variant :
          List.of(
              RegexEngineVariant.SAFERE_STRING,
              RegexEngineVariant.SAFERE_UTF8,
              RegexEngineVariant.JDK_STRING)) {
        try (RegexEngineVariant.CompiledRegex pattern = variant.compile(".*error: \\w+")) {
          for (String text : List.of("2026-11-20 WARN error: disk", "2026-11-20 INFO ok")) {
            RegexEngineVariant.RegexInput input =
                variant == RegexEngineVariant.SAFERE_UTF8
                    ? RegexEngineVariant.utf8Input(text.getBytes(StandardCharsets.UTF_8))
                    : new RegexEngineVariant.StringRegexInput(text);
            boolean expected = text.contains("error");
            safereFrames.clear();
            assertThat(pattern.exists(input)).as("%s on %s", variant, text).isEqualTo(expected);
            switch (variant) {
              case SAFERE_STRING ->
                  assertThat(safereFrames).contains("org.safere.Pattern.findInString");
              case SAFERE_UTF8 ->
                  assertThat(safereFrames)
                      .contains("org.safere.Pattern.find")
                      .doesNotContain("org.safere.Utf8Matcher.find");
              default -> assertThat(safereFrames).isEmpty();
            }
            safereFrames.clear();
            assertThat(pattern.find(input)).isEqualTo(expected);
            if (variant == RegexEngineVariant.SAFERE_STRING) {
              assertThat(safereFrames).doesNotContain("org.safere.Pattern.findInString");
            }
          }
        }
      }
    } finally {
      Pattern.setDiagnostics(previous);
    }
  }

  @Test
  void existsRunsOnlyOnEnginesWithAnExistencePredicate() {
    CrossEngineBenchmarkPlan plan = CrossEngineBenchmarkPlan.load();
    String id = "ExistenceStartBenchmark.wide.singleLine.768";
    assertThat(
            plan.trials(CrossEngineWorkload.TimingGroup.NANOSECONDS).stream()
                .filter(trial -> trial.workload().id().equals(id))
                .map(trial -> trial.variant().id()))
        .containsExactlyInAnyOrder(
            "safere-string", "safere-utf8", "safere-utf8-vector", "jdk-string");
    assertThat(plan.resolve(id + "@safere-string").workload().operation())
        .isEqualTo(BenchmarkOperation.EXISTS);
  }

  @Test
  void coldValidationChecksTheMeasuredResultAfterTheOperationReturns() {
    CrossEngineWorkload workload =
        coldFirstUseWorkload(BenchmarkOperation.FIND_GROUP_PRESENT, "(z)");
    CrossEngineBenchmarkPlan.Trial trial =
        new CrossEngineBenchmarkPlan.Trial(workload, RegexEngineVariant.SAFERE_STRING);
    try (CrossEngineTrialRunner runner =
        CrossEngineTrialRunner.prepareColdStart(
            BenchmarkData.get(), trial, CrossEngineWorkload.TimingGroup.MILLISECONDS)) {
      // The measured search returns false, whereas the declaration expects true.
      assertThatCode(() -> runner.run(testBlackhole())).doesNotThrowAnyException();
      assertThatThrownBy(runner::validateMeasuredResult)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("expected true but was false");
    }
  }

  private static CrossEngineWorkload coldFirstUseWorkload(
      BenchmarkOperation operation, String regex) {
    CrossEngineWorkload base =
        CrossEngineBenchmarkPlan.load()
            .resolve("InitializationLatencyBenchmark.firstFind.asciiWord@safere-string")
            .workload();
    return new CrossEngineWorkload(
        base.id(),
        operation,
        List.of(regex),
        base.inputKeys(),
        base.groups(),
        base.replacement(),
        base.expected(),
        base.limit(),
        base.lifecycle(),
        base.flagSet(),
        base.seed(),
        base.count(),
        base.mode(),
        base.measurement(),
        base.timingGroup());
  }

  private static Blackhole testBlackhole() {
    return new Blackhole(
        "Today's password is swordfish. I understand instantiating Blackholes directly is"
            + " dangerous.");
  }

  @Test
  void migratedOperationsReplayCorrectnessForEveryCompatibleEngine() {
    List<String> trials =
        List.of(
            "CompileBenchmark.compileSimple@safere-string",
            "CompileBenchmark.compileSimple@jdk-string",
            "CompileBenchmark.compileSimple@re2j-string",
            "CaptureScalingBenchmark.capture3@safere-string",
            "CaptureScalingBenchmark.capture3@jdk-string",
            "CaptureScalingBenchmark.capture3@re2j-string",
            "ReplaceBenchmark.literalReplaceFirst@safere-string",
            "ReplaceBenchmark.literalReplaceFirst@jdk-string",
            "ReplaceBenchmark.literalReplaceFirst@re2j-string",
            "ReplaceBenchmark.anchoredReplace@safere-string",
            "ReplaceBenchmark.anchoredReplace@jdk-string",
            "ReplaceBenchmark.anchoredReplace@re2j-string",
            "ReplaceBenchmark.manualReplaceAll@safere-string",
            "ReplaceBenchmark.manualReplaceAll@jdk-string",
            "ReplaceBenchmark.manualReplaceAll@re2j-string",
            "Issue481ScalingBenchmark.splitWords.128@safere-string",
            "Issue481ScalingBenchmark.splitWords.128@jdk-string",
            "Issue481ScalingBenchmark.splitWords.128@re2j-string",
            "Issue481ScalingBenchmark.schemeExtract.128@safere-string",
            "Issue481ScalingBenchmark.schemeExtract.128@jdk-string",
            "Issue481ScalingBenchmark.schemeExtract.128@re2j-string",
            "ScrubberBenchmark.scrubWithDirectives@safere-string",
            "ScrubberBenchmark.scrubWithDirectives@jdk-string",
            "ScrubberBenchmark.scrubWithDirectives@re2j-string",
            "MatcherApiBenchmark.lookingAt@safere-string",
            "MatcherApiBenchmark.lookingAt@jdk-string",
            "MatcherApiBenchmark.lookingAt@re2j-string",
            "MatcherApiBenchmark.regionFind@safere-string",
            "MatcherApiBenchmark.regionFind@jdk-string",
            "MatcherApiBenchmark.resetAndFind@safere-string",
            "MatcherApiBenchmark.resetAndFind@jdk-string",
            "MatcherApiBenchmark.resetAndFind@re2j-string",
            "JavaCharacterClassBenchmark.compileAndFindJavaLetter@safere-string",
            "JavaCharacterClassBenchmark.compileAndFindJavaLetter@jdk-string",
            "JavaCharacterClassBenchmark.findJavaLetter@safere-string",
            "JavaCharacterClassBenchmark.findJavaLetter@jdk-string");
    CrossEngineBenchmarkPlan plan = CrossEngineBenchmarkPlan.load();

    for (String trialId : trials) {
      CrossEngineWorkload.TimingGroup timingGroup = plan.resolve(trialId).workload().timingGroup();
      try (CrossEngineTrialRunner ignored = CrossEngineTrialRunner.prepare(trialId, timingGroup)) {
        assertThat(ignored).as(trialId).isNotNull();
      }
    }
  }

  @Test
  void specializedMeasurementModesSelectStableGenericTrials() {
    SpecializedBenchmarkPlan first = SpecializedBenchmarkPlan.load();
    SpecializedBenchmarkPlan second = SpecializedBenchmarkPlan.load();

    assertThat(first.averageTimeTrials())
        .hasSizeGreaterThanOrEqualTo(MINIMUM_SPECIALIZED_AVERAGE_TIME_TRIALS)
        .allMatch(trial ->
            trial.workload().measurement().mode()
                == DeclarativeBenchmarkPlan.MeasurementMode.AVERAGE_TIME)
        .extracting(SpecializedBenchmarkPlan.Trial::id)
        .containsExactlyElementsOf(
            second.averageTimeTrials().stream().map(SpecializedBenchmarkPlan.Trial::id).toList());
    assertThat(first.retainedMemoryTrials())
        .hasSizeGreaterThanOrEqualTo(MINIMUM_SPECIALIZED_RETAINED_MEMORY_TRIALS)
        .allMatch(trial ->
            trial.workload().measurement().mode()
                == DeclarativeBenchmarkPlan.MeasurementMode.RETAINED_MEMORY)
        .extracting(SpecializedBenchmarkPlan.Trial::id)
        .containsExactlyElementsOf(
            second.retainedMemoryTrials().stream()
                .map(SpecializedBenchmarkPlan.Trial::id)
                .toList());
  }

  @Test
  void collectionPlanDiscoversGenericRunnersAndStableReportRows() {
    BenchmarkCollectionPlan plan = BenchmarkCollectionPlan.load();

    assertThat(plan.runners())
        .extracting(BenchmarkCollectionPlan.Runner::benchmark)
        .containsExactly(
            "org.safere.benchmark.CrossEngineBenchmark.run",
            "org.safere.benchmark.CrossEngineScalingBenchmark.run",
            "org.safere.benchmark.CrossEngineNoForkBenchmark.run",
            "org.safere.benchmark.CrossEngineColdStartBenchmark.run",
            "org.safere.benchmark.SpecializedBenchmark.run");
    List<String> runnerTrialIds =
        plan.runners().stream().flatMap(runner -> runner.trialIds().stream()).toList();
    assertThat(plan.runners()).allMatch(runner -> !runner.trialIds().isEmpty());
    assertThat(runnerTrialIds)
        .hasSizeGreaterThanOrEqualTo(MINIMUM_COLLECTION_RUNNER_TRIALS)
        .doesNotHaveDuplicates();
    assertThat(plan.reportPlan().trials())
        .extracting(BenchmarkCollectionPlan.CollectionTrial::id)
        .containsExactlyInAnyOrderElementsOf(runnerTrialIds);
    assertThat(plan.reportPlan().exclusions()).isNotEmpty().doesNotHaveDuplicates();
    Set<String> expectedSmokeTrialIds = new HashSet<>();
    for (BenchmarkCollectionPlan.Runner runner : plan.runners()) {
      String firstTrial = runner.trialIds().getFirst();
      String firstWorkload = firstTrial.substring(0, firstTrial.lastIndexOf('@'));
      runner.trialIds().stream()
          .filter(trialId -> trialId.startsWith(firstWorkload + "@"))
          .forEach(expectedSmokeTrialIds::add);
    }
    assertThat(plan.reportPlan(true).trials())
        .extracting(BenchmarkCollectionPlan.CollectionTrial::id)
        .containsExactlyInAnyOrderElementsOf(expectedSmokeTrialIds);
  }

  @Test
  void collectionPlanSelectsOnlyRunnersMatchedByJmhFilter() {
    BenchmarkCollectionPlan plan = BenchmarkCollectionPlan.load();

    assertThat(
            plan.runnersMatching("^org\\.safere\\.benchmark\\.CrossEngineScalingBenchmark\\.run$"))
        .extracting(BenchmarkCollectionPlan.Runner::parameter)
        .containsExactly("crossEngineScalingTrial");
    assertThat(plan.runnersMatching("CrossEngine(?:Scaling|NoFork)Benchmark"))
        .extracting(BenchmarkCollectionPlan.Runner::parameter)
        .containsExactly("crossEngineScalingTrial", "crossEngineNoForkTrial");
    assertThat(plan.runnersMatching("UnparameterizedBenchmark")).isEmpty();
    assertThat(
            plan.launcherArguments(
                "CrossEngineScalingBenchmark", "/tmp/benchmark jar.jar", Set.of()))
        .startsWith("-jar", "\"/tmp/benchmark jar.jar\"", "-p")
        .element(3)
        .asString()
        .startsWith("\"crossEngineScalingTrial=");
    assertThat(
            plan.launcherArguments(
                "CrossEngineScalingBenchmark",
                "/tmp/benchmark.jar",
                Set.of("crossEngineScalingTrial")))
        .containsExactly("-jar", "\"/tmp/benchmark.jar\"");
  }

  @ParameterizedTest
  @CsvSource({
    "nanoseconds, RegexBenchmark.literalMatch, crossEngineTrial",
    "microseconds, SearchScalingBenchmark.searchEasyFail.1024, crossEngineScalingTrial"
  })
  @ResourceLock(Resources.SYSTEM_OUT)
  void prefixSelectionsCanBeWrittenDirectlyToArgumentFiles(
      String timingGroup, String prefix, String parameter) {
    CrossEngineWorkload.TimingGroup group =
        timingGroup.equals("nanoseconds")
            ? CrossEngineWorkload.TimingGroup.NANOSECONDS
            : CrossEngineWorkload.TimingGroup.MICROSECONDS;
    List<String> expected =
        CrossEngineBenchmarkPlan.load().trials(group).stream()
            .filter(trial -> trial.workload().id().startsWith(prefix))
            .map(CrossEngineBenchmarkPlan.Trial::id)
            .toList();
    assertThat(expected).isNotEmpty();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    PrintStream original = System.out;
    try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
      System.setOut(capture);
      CrossEngineBenchmarkPlan.main(new String[] {"--argument-file", timingGroup, prefix});
    } finally {
      System.setOut(original);
    }
    assertThat(output.toString(StandardCharsets.UTF_8).lines().toList())
        .containsExactly("-p", "\"" + parameter + "=" + String.join(",", expected) + "\"");
  }

  @Test
  void declaredRunnerArgumentsUseTheSelectedTimingOrAllocationTrials() {
    BenchmarkCollectionPlan plan = BenchmarkCollectionPlan.load();
    String benchmark = "org.safere.benchmark.CrossEngineBenchmark.run";
    String jar = "/tmp/benchmark jar.jar";
    BenchmarkCollectionPlan.Runner timing =
        plan.runners().stream()
            .filter(runner -> runner.benchmark().equals(benchmark))
            .findFirst()
            .orElseThrow();
    BenchmarkCollectionPlan.Runner allocation =
        plan.allocationRunners().stream()
            .filter(runner -> runner.benchmark().equals(benchmark))
            .findFirst()
            .orElseThrow();

    assertThat(plan.declaredLauncherArguments(benchmark, jar, false, false, null))
        .containsExactly(
            "-jar",
            "\"/tmp/benchmark jar.jar\"",
            "-p",
            "\"crossEngineTrial=" + String.join(",", timing.trialIds()) + "\"");
    assertThat(plan.declaredLauncherArguments(benchmark, jar, true, false, null))
        .containsExactly(
            "-jar",
            "\"/tmp/benchmark jar.jar\"",
            "-p",
            "\"crossEngineTrial=" + String.join(",", allocation.trialIds()) + "\"");
    String firstWorkload = allocation.trialIds().getFirst().split("@", 2)[0];
    List<String> smokeTrials =
        allocation.trialIds().stream()
            .filter(trialId -> trialId.startsWith(firstWorkload + "@"))
            .toList();
    assertThat(plan.declaredLauncherArguments(benchmark, jar, true, true, null))
        .containsExactly(
            "-jar",
            "\"/tmp/benchmark jar.jar\"",
            "-p",
            "\"crossEngineTrial=" + String.join(",", smokeTrials) + "\"");
    assertThatThrownBy(() -> plan.declaredLauncherArguments("missing", jar, false, false, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unknown declared benchmark runner: missing");
  }

  @Test
  void declaredArgumentFilesPreserveProviderGroupsAndSmokeSelection() {
    BenchmarkCollectionPlan plan = BenchmarkCollectionPlan.load();
    String jar = "/tmp/benchmark \"quoted\" \\ jar.jar";
    for (boolean allocation : List.of(false, true)) {
      for (boolean smoke : List.of(false, true)) {
        for (BenchmarkCollectionPlan.Runner runner : plan.executionRunners(allocation, smoke)) {
          String provider =
              RegexEngineVariant.fromId(
                      runner
                          .trialIds()
                          .getFirst()
                          .substring(runner.trialIds().getFirst().lastIndexOf('@') + 1))
                  .scanProvider();
          assertThat(
                  plan.declaredLauncherArguments(
                      runner.benchmark(), jar, allocation, smoke, provider))
              .containsExactly(
                  "-jar",
                  BenchmarkCollectionPlan.argumentFileToken(jar),
                  "-p",
                  BenchmarkCollectionPlan.argumentFileToken(
                      runner.parameter() + "=" + String.join(",", runner.trialIds())));
        }
      }
    }
  }

  @Test
  @ResourceLock(Resources.SYSTEM_OUT)
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void declaredArgumentFileCommandFiltersProvidersAfterSmokeSelection() {
    BenchmarkCollectionPlan plan = BenchmarkCollectionPlan.load();
    String property = "safere.benchmark.scanProvider";
    String previous = System.getProperty(property);
    PrintStream original = System.out;
    try {
      for (BenchmarkCollectionPlan.Runner runner : plan.executionRunners(false, true)) {
        String provider =
            RegexEngineVariant.fromId(
                    runner
                        .trialIds()
                        .getFirst()
                        .substring(runner.trialIds().getFirst().lastIndexOf('@') + 1))
                .scanProvider();
        System.setProperty(property, provider);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
          System.setOut(capture);
          BenchmarkCollectionPlan.main(
              new String[] {
                "declared-runner-arguments",
                "runners",
                runner.benchmark(),
                "/tmp/benchmark jar.jar",
                "--smoke"
              });
        } finally {
          System.setOut(original);
        }
        assertThat(output.toString(StandardCharsets.UTF_8).lines().toList())
            .containsExactly(
                "-jar",
                "\"/tmp/benchmark jar.jar\"",
                "-p",
                BenchmarkCollectionPlan.argumentFileToken(
                    runner.parameter() + "=" + String.join(",", runner.trialIds())));
      }
    } finally {
      System.setOut(original);
      if (previous == null) {
        System.clearProperty(property);
      } else {
        System.setProperty(property, previous);
      }
    }
  }

  @Test
  void allocationCollectionPreservesMemoryScalingWithoutAddingTimingTrials() {
    BenchmarkCollectionPlan plan = BenchmarkCollectionPlan.load();

    assertThat(plan.allocationRunners())
        .flatExtracting(BenchmarkCollectionPlan.Runner::trialIds)
        .contains(
            "MemoryScalingBenchmark.searchEasy.1024@safere-string",
            "MemoryScalingBenchmark.searchEasy.1024@jdk-string",
            "MemoryScalingBenchmark.searchEasy.1024@re2j-string",
            "MemoryScalingBenchmark.searchMedium.1048576@safere-string",
            "MemoryScalingBenchmark.searchMedium.1048576@jdk-string",
            "MemoryScalingBenchmark.searchMedium.1048576@re2j-string")
        .noneMatch(trial -> trial.startsWith("MemoryScalingBenchmark.") && trial.contains("utf8"));
    assertThat(plan.runners())
        .flatExtracting(BenchmarkCollectionPlan.Runner::trialIds)
        .noneMatch(trial -> trial.startsWith("MemoryScalingBenchmark."));
  }

  @Test
  void collectionPlanQueriesModeTimingPrefixAndExecutionVariant() {
    BenchmarkCollectionPlan plan = BenchmarkCollectionPlan.load();
    BenchmarkCollectionPlan.Query query =
        new BenchmarkCollectionPlan.Query(
            "averageTime", "nanoseconds", "RegexBenchmark.", "safere-utf8");

    assertThat(plan.trials(query))
        .isNotEmpty()
        .allMatch(trial ->
            trial.workloadId().startsWith("RegexBenchmark.")
                && trial.executionVariant().equals("safere-utf8")
                && trial.measurement().timingUnit()
                    == DeclarativeBenchmarkPlan.TimingUnit.NANOSECONDS);
  }

  @Test
  void everyJmhMethodIsGenericOrExplicitMeasurementInfrastructure() throws Exception {
    Path sourceDirectory =
        Files.exists(Path.of("src/main/java/org/safere/benchmark"))
            ? Path.of("src/main/java/org/safere/benchmark")
            : Path.of("safere-benchmarks/src/main/java/org/safere/benchmark");

    try (Stream<Path> files = Files.list(sourceDirectory)) {
      assertThat(
              files
                  .filter(path -> path.toString().endsWith(".java"))
                  .filter(path -> {
                    try {
                      return Files.readString(path).contains("@Benchmark");
                    } catch (Exception exception) {
                      throw new IllegalStateException(exception);
                    }
                  })
                  .map(path -> path.getFileName().toString())
                  .sorted())
          .containsExactly(
              "CrossEngineBenchmark.java",
              "CrossEngineColdStartBenchmark.java",
              "CrossEngineNoForkBenchmark.java",
              "CrossEngineScalingBenchmark.java",
              "CrosscheckOverheadBenchmark.java",
              "SpecializedBenchmark.java");
    }
  }

  @Test
  void everySpecializedOperationPreparesAndRunsThroughOneEntryPoint() {
    List<String> trials =
        List.of(
            "PatternSetBenchmark.unanchoredMatch.4@safere-string",
            "Utf8MatchingBenchmark.repeatedFind.ascii@safere-utf8",
            "Utf8MatchingBenchmark.repeatedFind.multibyte@safere-utf8",
            "Utf8MatchingBenchmark.captureBounds.numbered@safere-utf8",
            "Utf8MatchingBenchmark.captureBounds.named@safere-utf8",
            "Utf8MatchingBenchmark.captureBounds.nonparticipating@safere-utf8",
            "Utf8MatchingBenchmark.emptyMatchIteration@safere-utf8",
            "Utf8MatchingBenchmark.captureFreeDecode.asciiEarly@safere-utf8",
            "ByteReplacementBenchmark.numbered@safere-utf8",
            "Utf8MatchingBenchmark.window@safere-utf8",
            "Utf8MatchingBenchmark.construction.validated@safere-utf8",
            "PatternAnalysisBenchmark.cachedAnalysis@safere-string",
            "PatternAnalysisBenchmark.compileOnly@safere-string",
            "PatternAnalysisBenchmark.compileAndAnalyze@safere-string",
            "DiagnosticsDisabledBenchmark.tinyLiteralFindHit@safere-string",
            "DiagnosticsEnabledBenchmark.charClassDenseReplaceAll.longAdder@safere-string");
    Blackhole blackhole =
        new Blackhole(
            "Today's password is swordfish. I understand instantiating Blackholes directly is"
                + " dangerous.");

    for (String trial : trials) {
      try (SpecializedTrialRunner runner = SpecializedTrialRunner.prepare(trial)) {
        runner.run(blackhole);
      }
    }
  }

  @Test
  void reverseDfaProseWorkloadRecoversEveryMatchStartWithTheReverseDfa() {
    // The workload exists to measure repeated reverse-DFA start recovery. If a faster path takes
    // over, this fails so the workload can be retargeted instead of silently measuring that path.
    String workloadId = "ReverseDfaFindBenchmark.wordProse.32768";
    CrossEngineBenchmarkPlan plan = CrossEngineBenchmarkPlan.load();
    BenchmarkData data = BenchmarkData.get();
    List<OperationDiagnostics> events = new ArrayList<>();
    Pattern.setDiagnostics(
        new SafeReMatchDiagnostics() {
          @Override
          public void onOperationCompleted(OperationDiagnostics event) {
            events.add(event);
          }
        });
    try {
      for (RegexEngineVariant variant :
          List.of(RegexEngineVariant.SAFERE_STRING, RegexEngineVariant.SAFERE_UTF8)) {
        CrossEngineWorkload workload = plan.resolve(workloadId + "@" + variant.id()).workload();
        var inputs = variant.prepareInputs(data, workload.inputKeys());
        try (var compiled = variant.compile(workload.patterns().getFirst())) {
          events.clear();
          var matcher = compiled.matcher(inputs.getFirst());
          int matches = 0;
          while (matcher.find()) {
            matches++;
          }
          assertThat(matches).as(variant.id()).isEqualTo(6144);
          assertThat(events).as(variant.id()).hasSize(matches + 1);
          assertThat(events.stream().mapToInt(OperationDiagnostics::reverseDfaSearchCount).sum())
              .as(variant.id())
              .isEqualTo(matches);
          assertThat(events.subList(0, matches))
              .as(variant.id())
              .allSatisfy(event -> {
                assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.DFA);
                assertThat(event.reverseDfaSearchCount()).isEqualTo(1);
              });
        }
      }
    } finally {
      Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
    }
  }
}
