// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import org.safere.Pattern.CharClassScanInfo;
import org.safere.Pattern.DisjointRequiredLiterals;

/**
 * Metadata extracted from a regular expression AST describing mandatory content that any match
 * must contain across the entire input (Tier 0 whole-input rejection).
 */
record RejectDescriptor(
    String requiredLiteral,
    CharClassScanInfo requiredCharClass,
    DisjointRequiredLiterals disjointRequiredLiterals) {

  static final RejectDescriptor NONE = new RejectDescriptor(null, null, null);

  boolean hasRejectionFilter() {
    return requiredLiteral != null
        || requiredCharClass != null
        || disjointRequiredLiterals != null;
  }
}
