#!/bin/bash
# Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.
#
# Compare JMH benchmarks between two git branches or commits.
#
# Usage:
#   ./safere-benchmarks/scripts/compare-branch.sh [OPTIONS] [FILTER]
#
# Options:
#   --baseline <ref>    Baseline git ref to compare against (default: main)
#   --current <ref>     Target git ref to evaluate (default: current branch)
#   --grouped-tables    Emit separate tables grouped by benchmark class
#   --no-speedup        Do not include speedup ratio column
#   --vector            Force enabling vector JVM flags (default: true if branch has vector)
#
# Examples:
#   ./safere-benchmarks/scripts/compare-branch.sh --baseline main
#   ./safere-benchmarks/scripts/compare-branch.sh '(jsonBlock|templateTagMatch)'

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

cd "$REPO_ROOT"

ORIGINAL_BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || git rev-parse HEAD)"
COMPARE_PY="$(mktemp /tmp/compare_benchmarks_XXXXXX.py)"
cp "$SCRIPT_DIR/compare-benchmarks.py" "$COMPARE_PY"

cleanup() {
  rm -f "$COMPARE_PY"
  current_head="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || git rev-parse HEAD)"
  if [ "$current_head" != "$ORIGINAL_BRANCH" ]; then
    git checkout -q "$ORIGINAL_BRANCH" || true
  fi
}
trap cleanup EXIT

BASELINE_REF="main"
CURRENT_REF="$ORIGINAL_BRANCH"
SINGLE_TABLE=true
SHOW_SPEEDUP=true
FORCE_VECTOR=false
FILTER=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --baseline)
      BASELINE_REF="$2"
      shift 2
      ;;
    --current)
      CURRENT_REF="$2"
      shift 2
      ;;
    --single-table)
      SINGLE_TABLE=true
      shift
      ;;
    --grouped-tables|--no-single-table)
      SINGLE_TABLE=false
      shift
      ;;
    --no-speedup)
      SHOW_SPEEDUP=false
      shift
      ;;
    --vector)
      FORCE_VECTOR=true
      shift
      ;;
    -*)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
    *)
      FILTER="$1"
      shift
      ;;
  esac
done

if [ -z "$FILTER" ]; then
  FILTER='(SingleCharClassBenchmark\.findDigitAbsent|RealWorldRegexBenchmark\.runBenchmark\.(bracketCitation\.noMatch|mapFieldPath\.(noMatch|match)|caseInsensitiveKeyword\.noMatch|customProtocolLink\.noMatch|metadataBlock\.(noMatch|match)|jsonBlock\.match|templateTagMatch\.match)|RegexBenchmark\.(literalMatch|charClassMatch|captureGroups|emailFind)|ApplicationBenchmark\.(uuidValidation|secretRedaction)).*@safere-string'
fi

VECTOR_JVM_ARGS="--add-modules=jdk.incubator.vector --add-opens=java.base/java.lang=ALL-UNNAMED -Dorg.safere.experimental.vectorScanProvider=vector"

# 1. Checkout baseline and discover trials
echo "=== Discovering benchmark trials on $BASELINE_REF / $CURRENT_REF ==="
git checkout -q "$BASELINE_REF"
if [ ! -f "safere-benchmarks/target/benchmarks.jar" ] || [ ! -f "safere-benchmarks/target/benchmark-corpus/manifest.json" ]; then
  mvn -pl safere-benchmarks -am package \
    -DskipTests \
    -Dpmd.skip=true \
    -Dspotless.check.skip=true \
    -Dcheckstyle.skip=true \
    -Dmaven.javadoc.skip=true \
    -Dexec.skip=true \
    -q
  ./materialize-benchmark-inputs.sh --no-build
fi

TRIALS="$(java \
  -Dsafere.benchmark.corpus=safere-benchmarks/target/benchmark-corpus \
  -cp safere-benchmarks/target/benchmarks.jar \
  org.safere.benchmark.CrossEngineBenchmarkPlan nanoseconds \
  | tr ',' '\n' \
  | grep -E "$FILTER" \
  | paste -sd, - || true)"

if [ -z "$TRIALS" ]; then
  # Try discovering trials on the current branch (e.g. if the branch added new benchmarks)
  git checkout -q "$CURRENT_REF"
  mvn -pl safere-benchmarks -am package \
    -DskipTests \
    -Dpmd.skip=true \
    -Dspotless.check.skip=true \
    -Dcheckstyle.skip=true \
    -Dmaven.javadoc.skip=true \
    -Dexec.skip=true \
    -q
  ./materialize-benchmark-inputs.sh --no-build
  TRIALS="$(java \
    -Dsafere.benchmark.corpus=safere-benchmarks/target/benchmark-corpus \
    -cp safere-benchmarks/target/benchmarks.jar \
    org.safere.benchmark.CrossEngineBenchmarkPlan nanoseconds \
    | tr ',' '\n' \
    | grep -E "$FILTER" \
    | paste -sd, - || true)"
fi

if [ -z "$TRIALS" ]; then
  echo "Error: No trials matched filter: $FILTER" >&2
  exit 1
fi

BASELINE_TXT="$(mktemp /tmp/baseline_XXXXXX.txt)"
CURRENT_TXT="$(mktemp /tmp/current_XXXXXX.txt)"
BASELINE_JSONL="$(mktemp /tmp/baseline_XXXXXX.jsonl)"
CURRENT_JSONL="$(mktemp /tmp/current_XXXXXX.jsonl)"

# 2. Run Baseline
echo "=== Running Baseline: $BASELINE_REF ==="
git checkout -q "$BASELINE_REF"
./run-java-benchmarks.sh CrossEngineBenchmark.run --fastbuild -- \
  -p crossEngineTrial="$TRIALS" \
  -jvmArgsPrepend "$VECTOR_JVM_ARGS" | tee "$BASELINE_TXT"
python3 "$COMPARE_PY" --jmh "$BASELINE_TXT" --output-jsonl "$BASELINE_JSONL"
sed -i -E 's/"engine"[[:space:]]*:[[:space:]]*"[^"]+"/"engine":"baseline"/' "$BASELINE_JSONL"

# 3. Run Current
echo "=== Running Current: $CURRENT_REF ==="
git checkout -q "$CURRENT_REF"
./run-java-benchmarks.sh CrossEngineBenchmark.run --fastbuild -- \
  -p crossEngineTrial="$TRIALS" \
  -jvmArgsPrepend "$VECTOR_JVM_ARGS" | tee "$CURRENT_TXT"
python3 "$COMPARE_PY" --jmh "$CURRENT_TXT" --output-jsonl "$CURRENT_JSONL"
sed -i -E 's/"engine"[[:space:]]*:[[:space:]]*"[^"]+"/"engine":"current"/' "$CURRENT_JSONL"

# 4. Render Table
echo ""
echo "=== Benchmark Comparison Results ==="
EXTRA_ARGS=()
if [ "$SHOW_SPEEDUP" = true ]; then
  EXTRA_ARGS+=(--speedup)
fi
if [ "$SINGLE_TABLE" = true ]; then
  EXTRA_ARGS+=(--single-table)
fi

python3 "$COMPARE_PY" \
  --json "$BASELINE_JSONL" "$CURRENT_JSONL" \
  --engines baseline,current \
  "${EXTRA_ARGS[@]}"

rm -f "$BASELINE_TXT" "$CURRENT_TXT" "$BASELINE_JSONL" "$CURRENT_JSONL"
