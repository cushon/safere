// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Test-only bridge to SafeRE's UAX #29 grapheme cluster boundary rules. */
public final class FuzzGraphemeBoundaries {
  private FuzzGraphemeBoundaries() {}

  /** Returns whether SafeRE places an extended grapheme cluster boundary at {@code pos}. */
  public static boolean isBoundary(String text, int pos) {
    return GraphemeSupport.isGraphemeClusterBoundary(text, pos);
  }
}
