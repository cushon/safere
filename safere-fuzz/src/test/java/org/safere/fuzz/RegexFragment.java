// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

/** Syntax and its ability to consume input. A nullable consuming fragment is still consuming. */
record RegexFragment(String regex, boolean canConsume) {
  static RegexFragment consuming(String regex) {
    return new RegexFragment(regex, true);
  }

  static RegexFragment zeroWidth(String regex) {
    return new RegexFragment(regex, false);
  }

  RegexFragment group(String opener) {
    return new RegexFragment(opener + regex + ")", canConsume);
  }

  RegexFragment repeat(String quantifier, int maximum) {
    return new RegexFragment(regex + quantifier, canConsume && maximum != 0);
  }

  RegexFragment then(RegexFragment right) {
    return new RegexFragment(regex + right.regex, canConsume || right.canConsume);
  }

  RegexFragment or(RegexFragment right) {
    return new RegexFragment(regex + "|" + right.regex, canConsume || right.canConsume);
  }

  String modifier(String modifier) {
    // Callers supply either an ordinary modifier or the possessive "+".
    return canConsume && modifier.equals("+") ? "" : modifier;
  }
}
