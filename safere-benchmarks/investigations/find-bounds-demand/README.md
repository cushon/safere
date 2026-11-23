# Bounds demand in fresh matchers

[Issue #1231](https://github.com/safere/safere/issues/1231) asks whether
`Matcher.find()` can defer selecting the preferred match until the caller needs
bounds, captures, or a continuation position. The fixtures cover ordinary digit
runs, nullable loops, a greedy Unicode scan, and a literal control.

Every timed matcher is fresh. Each trial uses one compiled pattern and one
prematerialized input per JMH thread. The mixed trials run a Boolean caller and a
bounds reader sequentially on the same pattern. On a pattern with captures,
calls such as `results()` and `toMatchResult()` can record capture demand for
the whole pattern. That can disable deferral for callers that only need a
Boolean result. Report mixed time per pair. Repeated trials count all
matches with one fresh matcher per invocation. UTF-8 trials use pre-existing
bytes and the default scan provider.

Setup checks Boolean results, requested captures and bounds, or find counts
against the JDK using a separate compiled pattern. This keeps those reads from
reaching the timed pattern before the trial begins.

Run a selection using the standard Java wrapper:

```bash
./run-java-benchmarks.sh --fastbuild --investigation find-bounds-demand \
  '^org\.safere\.benchmark\.FindBoundsBenchmark\.run$' -- \
  -p 'findTrial=FindBounds.digits.early.boolean@safere-string,FindBounds.digits.early.boolean@safere-utf8'
```
