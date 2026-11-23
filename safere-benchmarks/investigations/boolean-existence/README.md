# Boolean existence searches

Controls for issue #1185, opportunity 3. Patterns with greedy tails exercise early
acceptance, late acceptance, rejection, line boundaries, Unicode, and a second
field-delimiter family. Bounds, capture, and replacement consumers serve as controls for
span-returning matcher operations. Tiny inputs check dispatch overhead.

The UTF-8 variant runs the matcher-free `Pattern.find(Utf8Input)` path.
String find trials call `Matcher.find()` and serve as controls.
Measuring the matcher-free String `asPredicate()` path requires a separate trial.

Run with the default provider and standard settings:

```bash
./run-java-benchmarks.sh --fastbuild --investigation boolean-existence \
  CrossEngineBenchmark.run -- \
  -p crossEngineTrial=BooleanExistence.early.find@safere-utf8
```

Apply identical declarations to the baseline before comparing revisions. Raw
focused measurements stay local.
