// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import org.safere.Pattern.FixedOffsetLiteral;
import org.safere.Pattern.StartAcceleration;

/**
 * Immutable descriptor capturing pre-computed start-position metadata extracted from a regular
 * expression AST.
 */
@SuppressWarnings("ArrayRecordComponent")
record StartDescriptor(
    String prefix,
    boolean prefixFoldCase,
    FixedOffsetLiteral fixedOffsetLiteral,
    boolean[] charClassPrefixAscii,
    StartAcceleration lineAnchor,
    AhoCorasickSearcher ahoCorasick,
    boolean isPureLiteralAlternation) {

  StartDescriptor(
      String prefix,
      boolean prefixFoldCase,
      FixedOffsetLiteral fixedOffsetLiteral,
      boolean[] charClassPrefixAscii,
      StartAcceleration lineAnchor) {
    this(prefix, prefixFoldCase, fixedOffsetLiteral, charClassPrefixAscii, lineAnchor, null, false);
  }

  static final StartDescriptor NONE =
      new StartDescriptor(null, false, null, null, null, null, false);

  boolean hasStartAcceleration() {
    return prefix != null
        || fixedOffsetLiteral != null
        || charClassPrefixAscii != null
        || lineAnchor != null
        || ahoCorasick != null;
  }
}
