# SafeRE Unicode Table Generator

This module generates SafeRE's checked-in Unicode tables from the
maintainer-selected JDK:

- general categories, scripts, blocks, and binary properties come from
  `java.lang.Character`;
- the UAX #29 `Grapheme_Cluster_Break` and `Indic_Conjunct_Break` classes used
  by `\X` and `\b{g}` come from the JDK's own grapheme classifier in
  `jdk.internal.util.regex`, excluding unassigned code points and surrogates so
  SafeRE can apply its documented policy for those itself.

The generated source is checked in at:

```text
safere/src/main/java/org/safere/UnicodeGeneratedTables.java
```

Regeneration is intentionally separate from the default Maven build lifecycle.
To regenerate and format the checked-in source, run from the repository root:

```bash
./safere-unicode/generate-unicode-tables.sh
```
