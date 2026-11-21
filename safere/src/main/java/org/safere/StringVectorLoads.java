// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/**
 * Chooses, once per JVM, how {@link String} character scans load {@code ShortVector}s.
 *
 * <p>The choice is a {@code static final} field so that C2 folds the dispatch at every call site.
 * This class has no references to {@code jdk.incubator.vector}; the kernels live in {@link
 * StringVectorScan}, which is only loaded when a vector mode is selected.
 *
 * <p>The experimental {@code org.safere.experimental.stringVectorLoads} system property selects the
 * mode:
 *
 * <ul>
 *   <li>{@code jdk}: {@code ShortVector.fromString}, when the runtime has it. This is also the
 *       default when the property is unset.
 *   <li>{@code reflective}: reads {@code String.value} and {@code String.coder} directly. Needs
 *       {@code --add-opens java.base/java.lang=<SafeRE's module>}, and falls back to the default
 *       when that access is not available.
 *   <li>{@code chunked}: copies chunks with {@link String#getChars} into a per-scanner buffer and
 *       loads them with {@code ShortVector.fromCharArray}.
 *   <li>{@code none}: scalar scans.
 * </ul>
 *
 * <p>Every vector mode needs {@code --add-modules jdk.incubator.vector}; without it, or when the
 * selected mode is not available, scans stay scalar.
 */
final class StringVectorLoads {
  static final String PROPERTY = "org.safere.experimental.stringVectorLoads";

  /** How String scans load vectors. */
  enum Mode {
    NONE,
    JDK,
    REFLECTIVE,
    CHUNKED
  }

  /**
   * The shortest remaining input, in chars, for which a String scan takes the vector path. Below
   * this, setting up the vector loop costs more than the scalar scan it replaces.
   */
  static final int MINIMUM_REMAINING_LENGTH = 64;

  /** The length, in chars, of each scanner's buffer in {@link Mode#CHUNKED} mode. */
  static final int CHUNK_LENGTH = 1024;

  static final Mode MODE = select(System.getProperty(PROPERTY, ""));

  /**
   * Whether String scans may take a vector path. Work-counter builds stay scalar so that their
   * per-character accounting is unchanged.
   */
  static final boolean ENABLED = MODE != Mode.NONE && !WorkCounterConfig.ENABLED;

  private StringVectorLoads() {}

  /**
   * Returns the first index at or after {@code start} of a code unit in {@code ranges}, {@code -1}
   * when there is none, or {@link VectorScanProvider#UNSUPPORTED} when the vector path does not
   * apply and the caller should scan with its scalar loop.
   *
   * <p>{@code ranges} must describe a class whose members are exactly the code points it matches as
   * code units: BMP ranges that do not overlap the surrogates. A surrogate pair can then never
   * produce a false match, and every match is a code point boundary.
   *
   * @param chunkOwner the scanner whose buffer {@link Mode#CHUNKED} may use, or {@code null}
   */
  static int indexOfCharClass(String text, int[] ranges, int start, StringInputScanner chunkOwner) {
    int from = Math.max(0, start);
    if (text.length() - from < MINIMUM_REMAINING_LENGTH) {
      return VectorScanProvider.UNSUPPORTED;
    }
    return StringVectorScan.indexOfCharClass(MODE, text, ranges, from, chunkOwner);
  }

  static Mode select(String property) {
    Mode requested =
        switch (property.trim()) {
          case "" -> null;
          case "jdk" -> Mode.JDK;
          case "reflective" -> Mode.REFLECTIVE;
          case "chunked" -> Mode.CHUNKED;
          case "none" -> Mode.NONE;
          default -> throw new IllegalStateException("Unknown String vector load mode " + property);
        };
    if (requested == Mode.NONE || !VectorScanProviderFactory.vectorModuleReadable()) {
      return Mode.NONE;
    }
    try {
      return StringVectorScan.select(requested);
    } catch (RuntimeException | LinkageError e) {
      return Mode.NONE;
    }
  }
}
