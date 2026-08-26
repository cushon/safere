// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Objects;
import org.safere.Pattern.DisjointRequiredLiterals;
import org.safere.RejectDescriptorCompiler.EndAnchoredCharClassInfo;
import org.safere.RejectDescriptorCompiler.SuffixInfo;

/**
 * Metadata extracted from a regular expression AST describing mandatory content that any match must
 * contain across the entire input (Tier 0 whole-input rejection).
 */
record RejectDescriptor(
    CharClassScanInfo requiredCharClass,
    DisjointRequiredLiterals disjointRequiredLiterals,
    SuffixInfo endAnchoredSuffix,
    EndAnchoredCharClassInfo endAnchoredCharClass,
    InfixSequence infixSequence) {

  /** An ordered sequence of mandatory literal substrings that any match must contain in order. */
  @SuppressWarnings("ArrayRecordComponent")
  record InfixSequence(String[] infixes, int[] checkOrder) {
    InfixSequence {
      Objects.requireNonNull(infixes, "infixes");
      Objects.requireNonNull(checkOrder, "checkOrder");
    }

    boolean isSingle() {
      return infixes.length == 1;
    }

    String primaryLiteral() {
      return infixes[checkOrder[0]];
    }

    static InfixSequence of(String literal) {
      return new InfixSequence(new String[] {literal}, new int[] {0});
    }
  }

  static final RejectDescriptor NONE = new RejectDescriptor(null, null, null, null, null);

  String requiredLiteral() {
    return infixSequence != null ? infixSequence.primaryLiteral() : null;
  }

  boolean hasRejectionFilter() {
    return requiredCharClass != null
        || disjointRequiredLiterals != null
        || endAnchoredSuffix != null
        || endAnchoredCharClass != null
        || infixSequence != null;
  }
}
