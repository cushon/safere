# Nullable-loop Boolean searches

Controls for issue #1185, opportunity 9. Nested, reluctant, bounded, anchored,
noncapturing, and Unicode repetitions exercise the existence proof independently
of preferred bounds. Tiny and larger inputs check dispatch and scaling. Ordinary
nonnullable patterns use the existing pipeline; capture participation, dense
iteration, and replacement consume exact results.

The UTF-8 variant runs the matcher-free `Pattern.find(Utf8Input)` path.
String find trials call `Matcher.find()` and serve as controls.
Measuring the matcher-free String `asPredicate()` path requires a separate trial.

Run with the default provider and standard settings:

```bash
./run-java-benchmarks.sh --fastbuild --investigation nullable-existence \
  CrossEngineBenchmark.run -- \
  -p crossEngineTrial=NullableExistence.nested@safere-utf8
```

Use identical declarations on baseline and candidate. Focused profiles and raw
measurements remain local. Compilation and encoding occur outside timing.
