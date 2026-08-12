# Developer Guide

This guide covers contributor workflows and implementation conventions that are too detailed for
the user-facing README. For matching-engine architecture, see [DESIGN.md](DESIGN.md). For testing
strategy and test-suite organization, see [TESTING.md](TESTING.md).

## Building and Testing

SafeRE requires Java 21 or later. Production artifacts are built with JDK 26 while the baseline
sources are compiled with `--release 21`.

Run the main library tests:

```bash
mvn -pl safere test -q
```

Run formatting checks:

```bash
mvn spotless:check
```

Build the release artifacts without publishing them:

```bash
mvn -pl safere package -Prelease -DskipTests -q
```

## JDK-Specific Implementations

SafeRE is distributed as one multi-release JAR (MR-JAR). It does not publish a separate artifact
for implementations that require newer JDK APIs.

The source and binary layouts correspond as follows:

```text
safere/src/main/java/                         safere-<version>.jar
  module-info.java                              module-info.class
  org/safere/...                                org/safere/...

safere/src/main/java-jdk22/                     META-INF/versions/22/
  org/safere/SegmentByteSwarScan.java             org/safere/SegmentByteSwarScan.class
  org/safere/SegmentByteVectorScan.java           org/safere/SegmentByteVectorScan.class
  org/safere/SegmentShortSwarScan.java            org/safere/SegmentShortSwarScan.class
  org/safere/SegmentShortVectorScan.java          org/safere/SegmentShortVectorScan.class
```

The JDK 21 sources compile into the root of the JAR. When Maven runs on JDK 22 or later, the
`jdk22-foreign-memory` profile compiles `src/main/java-jdk22` with `--release 22` and
`multiReleaseOutput`, which writes its classes beneath `META-INF/versions/22`. The manifest marks
the artifact with `Multi-Release: true`.

At runtime, JDK 21 ignores the versioned directory. JDK 22 and later use eligible entries from the
versioned directory automatically. No application-level Java-version check is necessary.

The versioned source tree is also included under `META-INF/versions/22` in the published source
JAR. Do not configure it as a normal Maven source root: doing so would mix JDK 22 APIs into the
baseline JDK 21 compilation.

### Public API boundary

Version-specific classes are implementation details. Keep them package-private unless a separate
public API has been intentionally designed. In particular:

- do not expose `MemorySegment` or another post-JDK-21 type from the baseline API;
- do not export `org.safere.internal` from `module-info.java`;
- do not make an internal kernel public merely to call it from a separate module or test;
- keep interfaces shared between baseline and versioned code expressible using JDK 21 types.

The current JDK 22 MemorySegment kernels are compiled and tested groundwork. `Matcher` does not
select them yet, and their presence does not change SafeRE's user-facing JVM flags.

### Connecting a versioned implementation

Prefer MR-JAR class selection over reflection. Define a baseline interface using only JDK 21
types, then provide a factory class in both the baseline and versioned source trees. Both factory
classes must have the same fully qualified name and binary-compatible methods.

For example, a baseline interface might be:

```java
package org.safere;

interface StringScanner {
  int indexOfCharClass(String input, int[] ranges, int start);
}
```

The JDK 21 source tree would contain the baseline factory:

```java
package org.safere;

final class StringScannerFactory {
  static StringScanner create() {
    return new BaselineStringScanner();
  }

  private StringScannerFactory() {}
}
```

The JDK 22 source tree would contain a replacement with the same class name and method descriptor:

```java
package org.safere;

final class StringScannerFactory {
  static StringScanner create() {
    return new ForeignMemoryStringScanner();
  }

  private StringScannerFactory() {}
}
```

After packaging, these implementations occupy:

```text
org/safere/StringScannerFactory.class
META-INF/versions/22/org/safere/StringScannerFactory.class
```

Code in `Matcher` calls `StringScannerFactory.create()` normally. JDK 21 loads the root factory;
JDK 22 and later load the versioned factory. The compiler checks both implementations, and the JVM
performs selection without `Class.forName`, reflective invocation, or an explicit version branch.

This example describes the intended integration pattern; these factory classes do not exist yet.

### Optional modules and the Vector API

MR-JAR selection answers which JDK implementation is eligible. It does not resolve optional
modules or grant module readability.

Do not eagerly initialize or load a versioned class that links to `jdk.incubator.vector`. Keep a
non-Vector path available and load the Vector implementation only after the existing experimental
Vector provider has been enabled. The intended selection shape is:

```text
versioned StringScannerFactory
  ├── default: MemorySegment SWAR implementation
  └── Vector enabled: MemorySegment Vector implementation
```

Ordinary SafeRE use requires no additional flags. The experimental Vector provider continues to
use the flags documented in README.md:

```text
--add-modules=jdk.incubator.vector
-Dorg.safere.experimental.vectorScanProvider=vector
```

Build and test executions may additionally use `--add-reads org.safere=jdk.incubator.vector` as
internal module-path plumbing. Do not introduce it as a user-facing requirement without first
designing and documenting the complete activation path.

### Testing the packaged artifact

Tests for version-specific code belong in the corresponding test source tree:

```text
safere/src/test/java-jdk22/
```

The `compile-jdk22-foreign-memory-tests` execution compiles those tests separately with
`--release 22`. This separation is required: placing their bytecode in the ordinary test output
would prevent the prebuilt test artifact from running on JDK 21.

Version-specific tests must run against a packaged MR-JAR, not only `target/classes`. Testing the
archive catches missing manifests, misplaced versioned entries, and incorrect runtime selection.
CI runs the baseline packaged artifact on JDK 21 through 26 and the JDK 22 tests on JDK 22 through
26.

Inspect a local artifact with:

```bash
jar --list --file safere/target/safere-<version>.jar \
  | grep '^META-INF/versions/'
jar --describe-module --file safere/target/safere-<version>.jar
jar --validate --file safere/target/safere-<version>.jar
```

Confirm that the module descriptor exports only intended public packages and that the binary JAR
does not contain `.java` files. With `-Prelease`, also confirm that the source JAR contains the
versioned sources under `META-INF/versions/22`.
