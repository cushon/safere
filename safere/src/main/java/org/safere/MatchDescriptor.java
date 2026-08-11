// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import org.safere.Pattern.CharClassMatchInfo;
import org.safere.Pattern.CharClassScanInfo;
import org.safere.Pattern.KeywordAlternation;

/**
 * Immutable descriptor capturing pre-computed match-execution fast-path metadata extracted from a
 * regular expression AST (Tier 2/3 execution acceleration).
 */
record MatchDescriptor(
    String literalMatch,
    CharClassScanInfo singleCharClass,
    KeywordAlternation keywordAlternation,
    CharClassMatchInfo charClassMatch) {

  static final MatchDescriptor NONE = new MatchDescriptor(null, null, null, null);

  boolean hasFastPath() {
    return literalMatch != null
        || singleCharClass != null
        || keywordAlternation != null
        || charClassMatch != null;
  }
}
