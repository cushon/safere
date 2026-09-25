# SafeRE Unicode Table Generator

This module generates SafeRE's checked-in Unicode tables:

- General categories, scripts, blocks, and most binary properties come from
  the maintainer-selected JDK's public `java.lang.Character` API.
- `Grapheme_Cluster_Break`, `Indic_Conjunct_Break`, and `Extended_Pictographic`
  come directly from the pinned Unicode 17.0.0 files in
  [data/17.0.0](data/17.0.0/README.md). No private JDK APIs, reflection, or
  module-opening flags are used.

The generated source is checked in at:

```text
safere/src/main/java/org/safere/UnicodeGeneratedTables.java
```

Regeneration is separate from the default Maven build. Normal library builds
use the checked-in Java source and do not download Unicode data. Run from the
repository root:

```bash
./safere-unicode/generate-unicode-tables.sh
mvn -pl safere-unicode test
mvn -pl safere -Dtest=GraphemeBreakConformanceTest,UnassignedGraphemeTest test
```

The script verifies source checksums before generation. The grapheme parser
checks version headers, required properties, range bounds, and overlaps within
a property, and merges adjacent ranges into sorted inclusive intervals.
Unlisted code points use Unicode's defaults (`Other`, `None`, or false).
The generated Control table includes unassigned default-ignorable controls,
replacing the separate handwritten list. Surrogate boundary handling remains
in the matcher. The GB11 matching logic is unchanged; #936 tracks that issue.

The Unicode inputs are read locally; Maven may need its usual cached plugins.
Other properties still require the maintainer-selected JDK. The generated
source carries the Unicode license notice, also included in the library JAR
as `META-INF/LICENSE-Unicode.txt`.

To upgrade Unicode, update the versioned source files and `GraphemeBreakTest.txt`,
retain their notices and license, update the parser's version/header checks and
the generator/script source paths, and refresh `SHA256SUMS`. Select a JDK with
the same Unicode version for the remaining properties, regenerate, and review
and commit the inputs and output together. Run the parser and conformance tests;
JDK crosschecks supplement the Unicode fixture where versions agree.
