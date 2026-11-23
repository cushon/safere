# Running benchmarks

Run commands from the repository root. Read the
[performance guide](PERFORMANCE_GUIDE.md) before collecting or interpreting results.


SafeRE includes a [JMH](https://github.com/openjdk/jmh) benchmark suite in the
`safere-benchmarks` module, comparing SafeRE against `java.util.regex` (JDK),
[RE2/J](https://github.com/google/re2j), RE2-FFM (C++ RE2 via Java
 [FFM API](https://openjdk.org/jeps/454)), C++ RE2, PCRE2 JIT, Go `regexp`, and
 Rust [`regex`](https://crates.io/crates/regex), and .NET's non-backtracking
 regex engine.
The suite includes focused microbenchmarks, data-driven application workloads,
scaling/pathological cases, replacement, memory, and `PatternSet` benchmarks.
RE2-FFM matcher coverage and conversion timing boundaries are documented in the
[wrapper guide](../safere-ffm-re2/README.md). Competitor coverage is best effort;
incompatible semantics remain explicit trial exclusions.

Benchmark recipes and configuration live in
`safere-benchmarks/benchmark-data.json`. Before a benchmark starts, its runner
materializes that file into a resolved manifest and exact UTF-8 inputs under
`safere-benchmarks/target/benchmark-corpus`. Java, C++, Go, Rust, and .NET
consume only those generated artifacts instead of independently interpreting
input recipes.
See
[`safere-benchmarks/BENCHMARK_INPUTS.md`](BENCHMARK_INPUTS.md)
for details.

Workloads using existing generic operations and input recipes can be added
through data declarations: these select engine capabilities, inputs, timing
modes, and result consumption. New operation or recipe kinds also require
harness implementation and validation. See
[`safere-benchmarks/DECLARATIVE_BENCHMARK_PLAN.md`](DECLARATIVE_BENCHMARK_PLAN.md)
and
[`safere-benchmarks/DECLARATIVE_COLLECTION.md`](DECLARATIVE_COLLECTION.md).

UTF-8 capture-text operations decode only the requested byte range during timing.
The generic adapter retains the original input storage and its logical-view offset;
input encoding remains outside timing. Capture-participation operations use bounds
without decoding. This enables UTF-8 variants of existing text-extraction workloads
as well as the focused `CaptureRecoveryBenchmark` standing cases. See the
[capture-recovery investigation](investigations/capture-recovery/README.md) for the
larger opt-in cutoff study.

`ReverseDfaFindBenchmark.wordProse.32768` counts `\b\w+\b` matches in 32 KiB of
ASCII prose, about one match every five characters. The pattern has no captures, so
the workload measures repeated match-start recovery without inner-capture
extraction. In SafeRE each successful `find()` is one forward pass of the
start-register search, which tracks the match start along with the match end, for
String and direct UTF-8 input. (Before it, each `find()` ran the forward DFA to the
match end and the reverse DFA back to the start, hence the workload's name.) A
harness test checks this with operation diagnostics, so the workload must be
retargeted if a different path takes over. Plain `\w+` would not do: UTF-8 counts it
with a character-class scan that skips both DFAs. Compilation and UTF-8 encoding are
outside timing.

`ReverseDfaFindBenchmark.proseLines.32768` keeps a capture-free workload on the
reverse DFA. It counts `(?m)^.+$` matches in the same prose with a line break
after each sentence (768 lines). The start-register search doesn't handle line
anchors, so each successful `find()` runs the reverse DFA from the line end back
to its start, for String and direct UTF-8 input. A harness test checks this with
operation diagnostics.

SafeRE also maintains a separate
[OpenJDK-derived regex benchmark suite](https://github.com/safere/openjdk-regex-benchmarks).
It compares SafeRE and `java.util.regex` on compatible workloads adapted from
OpenJDK's regex microbenchmarks. That suite is GPL-2.0-only, so its source is
not vendored here or included in the `safere-benchmarks` Maven module.

### Cold compilation and first-use latency

Regular `singleShotColdStart` declarations run in fresh JVM forks with no JMH warmup iterations
and one measured invocation. They cover compilation, compile-plus-first-use, and first-use after
untimed compilation.
The cold runner never executes the measured operation for validation during setup. It retains the
actual measured first-use result and validates it after timing, without replaying the operation;
protocol tests also check the fixtures and preparation boundary separately. A second invocation in
the same fork is rejected. Inputs are materialized and encoded before timing, and process launch
is excluded.

`InitializationLatencyBenchmark.firstFind.*` compiles during setup, then measures Matcher creation,
the first `find()`, and capture participation. `InitializationLatencyBenchmark.compileAndFirstFind.*`
includes compilation in that same timed operation. Both read capture group 1 through the matcher
API, including for UTF-8; they do not use matcher-free Boolean search. Initial cases cover ASCII
word classes, Unicode word/property classes, and ASCII classes combined with grapheme consumption
or grapheme boundaries. The long first-search controls place a capture near the end of mixed
text or exercise a miss through the same pattern. The compile-plus-first-use cases include
a Java-derived character class. These workloads are part of the regular collection plan.

`InitializationLatencyBenchmark.compileAndFirstFindAfterAscii.*` compiles an unrelated ASCII
literal during setup in each fresh fork. The timed operation still compiles the target pattern,
then searches and checks capture participation. The ASCII control and Java lower-case property
use the same inputs and patterns as their unprimed `compileAndFirstFind` counterparts. Report
this timing boundary separately when comparing first-use results.

The standard cold run uses ten forks with one sample per fork. Compare the per-fork samples
and medians alongside the JMH mean and confidence interval. Interleave A/B runs or repeat them
in both orders to check for drift. The runner fails on errors, including an unexpected measured
result reported by the cold validation step.

```bash
./run-java-benchmarks.sh --fastbuild --first-compile CrossEngineColdStartBenchmark.run -- \
  -p crossEngineColdStartTrial=InitializationLatencyBenchmark.firstFind.graphemeCluster@safere-string
```

Run the paired `compileAndFirstFind` trial separately. Use `singleShotColdStart` only for supported
single-operation workloads. Native cold-start runners currently cover compilation alone; their
execution plans explicitly exclude these first-use timing boundaries. Fresh-process first-use
latency and warmed compilation-plus-first-use measure different JVM states and must be reported
separately.

### Quantified capture workloads

`QuantifiedCaptureBenchmark.{family}.{observation}.{outcome}.{size}` compares
Boolean search, group-zero bounds, inner-capture participation, inner-capture
text, and numbered, named, and functional replacement. All observation modes
use identical patterns and prematerialized inputs. Matching measurements exclude
compilation and input materialization. Java String cases create fresh matchers;
native variants retain their existing API and ownership boundaries. The RE2-FFM
variant explicitly includes its API-time UTF-8 conversion.

The families include the nested unbounded repeat from the retained-repeat repair
regression (`.*x(?:(a){1,}){2}y`), bounded inner repetition with greedy/lazy outer
repetition, and anchored greedy/lazy optional captures that must retain a value
when later iterations skip the group. The nested family holds the repeated
suffix at a fixed length while growing the preceding nonmatching prefix: the old
repair searched candidate partitions across that prefix. The geometric size sweep
includes short inputs and successful/rejected controls. Group zero observes
`start(0)` participation rather than copying the whole match; inner text copies
`group(1)`. This distinguishes capture extraction from text materialization.
The .NET group-zero adapter uses `Regex.Match()`, which also extracts captures
and can add more work than the bounds-only Go and Rust APIs.

Workload eligibility follows adapter capabilities and concrete capture semantics;
focused measurement runs select engines through runner filters. Choosing a smaller
comparison cohort does not exclude other adapters from the shared corpus.
Nested families follow SafeRE's current accepted-path policy; the JDK can return
an earlier capture boundary (the approved
[earlier-capture divergence](../INTENTIONAL_DIVERGENCES.md#earlier-captures-in-nested-repetitions)).
The subjects produce the same captured text under either boundary policy. JDK
bounds are pinned separately rather than treated as an unconditional oracle.
The long unanchored missing-suffix cases omit the expensive JDK backtracking
rejection reference; anchored controls remain eligible at every size.

C++ RE2 lacks named-template and callback replacement APIs. RE2-FFM's wrapper
supports numbered replacement but does not expose named or callback replacement.
Go's replacement callback exposes only the whole matched text, while this operation
requires inner captures. PCRE2 JIT matching is eligible; its replacement rows retain
the suite-wide registry restriction pending the broader audit in
[#1207](https://github.com/safere/safere/issues/1207). PCRE2 itself supports
substitution with JIT dispatch. See
[cross-runtime coverage](CROSS_RUNTIME_ENGINES.md#workload-coverage).
Native capture-aware functional replacement is implemented for SafeRE String,
JDK String, Rust, and .NET. The SafeRE and JDK String callbacks quote captured
text because the Java APIs interpret callback results as replacement templates.
The captured fixture text contains no replacement-template metacharacters.
RE2/J and SafeRE UTF-8 support named replacement. SafeRE UTF-8 capture-text
trials include decoding the requested capture during timing.

Numbered RE2 replacement syntax is declared in the data. Expected successful
captures and replacements are checked before timing; Java harness tests also check
exact bounds and rejected outputs. Native smoke validation must establish agreement
for the concrete subjects before capture-observing rows are compared. Agreement on
this corpus does not establish equivalent quantified-capture semantics for all
patterns.

These workloads assess current capture costs and scaling. They do not assume
historical NFA routing or simulate an older implementation by disabling engines.
Cross-runtime results provide context alongside differences in runtime, input
representation, allocation, and harness overhead; they are separate from
controlled same-runtime comparisons.

### Investigative benchmarks

Preserved diagnostic experiments live under [investigations/](investigations/README.md).
They have their own sources, workload definitions, protocols, and historical evidence.
They require explicit invocation and are excluded from the regular benchmark corpus,
collection plan, and recurring reports. See the
[capture-demand policy investigation](investigations/capture-demand-policy/README.md)
for an example.

### Benchmark Collection

To collect a full set of benchmark data for updating
[BENCHMARKS.md](../BENCHMARKS.md), run the collection script from the repository
root:

```bash
./collect-benchmark-results.sh
```

The default collection includes SafeRE's Java suite—SafeRE,
`java.util.regex`, RE2/J, and RE2-FFM—and the external OpenJDK-derived
SafeRE/JDK suite. Clone the external repository beside SafeRE before the first
collection:

```bash
git clone https://github.com/safere/openjdk-regex-benchmarks.git \
  ../safere-openjdk-regex-benchmarks
```

These are the normal engineering comparisons because the engines run in the
same JVM environment. Use `--openjdk-regex-repo PATH` when the external
checkout is elsewhere.

Use the longer Java mode when confirming close, surprising, or especially
important comparisons:

```bash
./collect-benchmark-results.sh --long
```

Use the cross-language mode only when you need broader ecosystem context from
C++ RE2, PCRE2 JIT, Go `regexp`, Rust `regex`, and .NET non-backtracking:

```bash
./collect-benchmark-results.sh --cross-language
```

To verify the collection pipeline without doing a full run:

```bash
./collect-benchmark-results.sh --smoke
```

The script runs benchmark batches sequentially, captures raw output, and
generates markdown tables. The declared Java matrix includes default String,
default UTF-8, and UTF-8 Vector SafeRE variants. Default UTF-8 and Vector use
identical workloads and sampling settings, with separate JVM launches to isolate
provider selection. The report plan records `requestedScanProvider` for each trial;
Vector measurements retain the `safere-utf8-vector` execution identity and
`safere_utf8_vector` report column.

For a focused Vector run, select the provider explicitly:

```bash
./run-java-benchmarks.sh --provider vector CrossEngineBenchmark.run -- \
  -p crossEngineTrial=RegexBenchmark.emailFind@safere-utf8-vector
```

Both timing and allocation declared collections include both providers by default.
This adds a second pass over the UTF-8-supported workloads, increasing collection
time and output size.
`--provider default` or `--provider vector` selects one provider. Declared runs reject
JMH `-p` overrides; use a focused run without `--declared` to select explicit trials.
Default runs leave the experimental provider property unset and follow the library
default, regardless of its implementation. Vector runs pass
`--add-modules=jdk.incubator.vector` and
`-Dorg.safere.experimental.vectorScanProvider=vector` to launcher and measurement
JVMs and check provider availability before running. String matching continues to
use its default implementation.

The collection script installs the SafeRE version from the current checkout,
builds the external suite against that exact version, and runs both engines.
Use `--skip-openjdk-regex` only for a deliberately incomplete local collection,
such as when the separate checkout is unavailable. A comprehensive
cross-runtime collection uses `--cross-language`; the OpenJDK-derived suite
remains included by default.

The OpenJDK-derived results remain a separate result set: they are not folded
into `merged-tables.md` because the external workloads and their upstream JMH
schedules differ from SafeRE's native suite.

By default, results are written to a timestamped directory under
`benchmark-results/`, and `benchmark-results/latest` is updated to point to
the newest run.

When the run finishes, inspect the result directory before updating `BENCHMARKS.md`:

```text
benchmark-results/latest
```

The important files in that directory are:

```text
jmh-output.txt
normalized-results.jsonl
declared-report-plan.json
merged-tables.md
java-memory.txt
java-pattern-memory.txt
```

Cross-language runs also include:

```text
cpp-results.jsonl
go-results.jsonl
rust-results.jsonl
dotnet-results.jsonl
cross-runtime-tables.md
```

`normalized-results.jsonl` combines the parsed Java and selected native
measurements into the common engine/benchmark/score/error/unit schema used by
the comparison tooling. Reviewed result sets supporting published claims are
retained under `benchmark-results/published/<full-SafeRE-commit>/`; other
timestamped result directories remain local and ignored by Git.

Default runs also include:

```text
openjdk-regex-output.txt
openjdk-regex-results.json
```

### Targeted Benchmark Runs

Always use the wrapper scripts — they run `mvn install` first to ensure
the benchmark module picks up the latest SafeRE code. These are useful for
development iteration or focused investigation; use
`./collect-benchmark-results.sh` for a full collection.

```bash
# Java benchmarks (time per operation)
./run-java-benchmarks.sh                        # standard benchmarks
./run-java-benchmarks.sh --declared             # all declared execution profiles
./run-java-benchmarks.sh --long --declared

# Java memory profiling (allocation rates via JMH GC profiler)
./run-java-memory-benchmarks.sh --declared
```

For a controlled before/after comparison of two commits that share the same workload and benchmark
harness definitions, use:

```bash
./safere-benchmarks/scripts/compare-branch.sh \
  --baseline origin/main \
  --current HEAD \
  'RegexBenchmark\.emailFind@safere-string'
```

The command resolves both refs before switching revisions, rebuilds each revision, and prints a
normalized comparison table. Each invocation must select exactly one SafeRE execution variant;
run String and UTF-8 comparisons separately. Use `--vector` when both revisions should enable the
experimental Vector provider, and use `--long` to confirm close, surprising, or important results.
The command deliberately refuses comparisons when workload data, runner settings, harness code, or
relevant build definitions differ. In those cases, construct a controlled baseline that uses the
same benchmark definitions, or use the full collection workflow when preparing a published report.

Use `BenchmarkCollectionPlan trials` to discover trial IDs by mode, timing
unit, workload prefix, or execution variant. Benchmark regexes select generic
JMH entry points, and arguments after `--` can select a specific trial or pass
other JMH options. See
[`safere-benchmarks/CROSS_ENGINE_EXECUTION.md`](CROSS_ENGINE_EXECUTION.md)
for workload IDs, execution variants, and timing boundaries.

Run a targeted workload from the external OpenJDK-derived suite with:

```bash
./run-openjdk-regex-benchmarks.sh \
  'org.safere.bench.openjdk.FindPatternComparison.*'
```

The wrapper defaults to a sibling `safere-openjdk-regex-benchmarks` checkout.
Use `--repo PATH` or `SAFERE_OPENJDK_REGEX_BENCHMARKS_REPO` to select another
location. Standard runs preserve the JMH schedules defined by the external
suite; `--smoke` provides a short compile-and-execute check.

`CrosscheckOverheadBenchmark` is excluded from the no-argument Java benchmark
run. It measures overhead in the `safere-crosscheck` facade and should be run
explicitly only when optimizing crosscheck:

```bash
./run-java-benchmarks.sh '^org\.safere\.benchmark\.CrosscheckOverheadBenchmark\.'
```

### Cross-Runtime Benchmarks

The benchmark suite includes C++ RE2, PCRE2 JIT, Go `regexp`, Rust `regex`, and
.NET non-backtracking harnesses for cross-language comparison. Each runner
executes every workload implemented by its adapter when no filter is supplied.
The C++ engines share one workload harness, so PCRE2 does not use a narrower
workload allowlist than RE2.

Toolchain requirements, installation links, per-engine commands, JIT
requirements, memory-platform limits, and smoke-test instructions are in
[`safere-benchmarks/CROSS_RUNTIME_ENGINES.md`](CROSS_RUNTIME_ENGINES.md).

Benchmark patterns and replacement templates are written in Java syntax. A
value that needs different syntax in another regex dialect declares exact
alternatives beside its Java-canonical definition in
`safere-benchmarks/benchmark-data.json`; a missing alternate means that the Java
value is used unchanged. Harnesses do not translate syntax or infer replacement
templates from operation names. See the
[syntax-profile schema](DECLARATIVE_BENCHMARK_PLAN.md#pattern-profiles)
for profile mappings and validation rules.

```bash
# All C++ engines, or one engine
./run-cpp-benchmarks.sh                    # all native C++ benchmarks
./run-cpp-benchmarks.sh --engine re2
./run-cpp-benchmarks.sh --engine pcre2-jit
./run-cpp-benchmarks.sh Regex Application  # specific benchmark groups

# Go regexp benchmarks
./run-go-benchmarks.sh                     # all Go benchmarks
./run-go-benchmarks.sh RegexBenchmark.literalMatch # smoke test
./run-go-benchmarks.sh Regex Application   # specific benchmark groups

# Rust regex benchmarks
./run-rust-benchmarks.sh                   # all Rust benchmarks
./run-rust-benchmarks.sh RegexBenchmark.literalMatch # smoke test
./run-rust-benchmarks.sh Regex Application # specific benchmark groups

# .NET non-backtracking benchmarks
./run-dotnet-benchmarks.sh                    # all supported .NET workloads
./run-dotnet-benchmarks.sh --smoke            # exercise each supported workload once
./run-dotnet-benchmarks.sh --list-exclusions  # explain every unsupported workload
./run-dotnet-benchmarks.sh Regex Application  # specific benchmark groups
```

The .NET harness uses `RegexOptions.NonBacktracking` and
`RegexOptions.CultureInvariant`. It decodes the shared UTF-8 corpus and
selects exact `dotnet` pattern- and replacement-profile alternatives before
timing. Unicode scalar ranges use equivalent UTF-16 regex expressions,
including surrogate-pair alternatives where needed. This setup work is
excluded from execution and compilation measurements. The runner consumes the
fully expanded workload plan and executes every compatible workload.
`--list-exclusions` emits a reason for every excluded workload.

### Comparing Results Manually

A comparison script turns JMH output into side-by-side markdown:

```bash
python3 safere-benchmarks/scripts/compare-benchmarks.py \
  --jmh jmh-output.txt
```

Add the cross-runtime JSON-lines files when comparing cross-language results.
The C++ file contains both `re2_cpp` and `pcre2_jit` records:

```bash
python3 safere-benchmarks/scripts/compare-benchmarks.py \
  --jmh jmh-output.txt \
  --json cpp-results.jsonl go-results.jsonl rust-results.jsonl dotnet-results.jsonl \
  --engines safere,jdk,re2j,re2_ffm,re2_cpp,pcre2_jit,go,rust,dotnet_nonbacktracking
```

To distinguish absent results from declared exclusions, include the report plan:

```bash
python3 safere-benchmarks/scripts/compare-benchmarks.py \
  --jmh jmh-output.txt \
  --declared-plan declared-report-plan.json
```
