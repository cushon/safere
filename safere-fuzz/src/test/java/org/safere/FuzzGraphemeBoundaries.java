// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.BitSet;

/** Test-only bridge to SafeRE's UAX #29 grapheme cluster boundary rules. */
public final class FuzzGraphemeBoundaries {
  private FuzzGraphemeBoundaries() {}

  /** Returns whether the compiled program uses active grapheme matching constructs. */
  public static boolean hasGraphemeSemantics(Pattern pattern) {
    return pattern.prog().hasGraphemeSemantics();
  }

  /** Returns all grapheme boundaries, sharing one linear segmentation context for the input. */
  public static BitSet boundaries(String text) {
    BitSet boundaries = new BitSet(text.length() + 1);
    GraphemeSupport.Context context = GraphemeSupport.Context.create(text, true);
    for (int pos = 0; pos < text.length(); pos += Character.charCount(text.codePointAt(pos))) {
      if (GraphemeSupport.isGraphemeClusterBoundary(text, pos, context)) {
        boundaries.set(pos);
      }
    }
    boundaries.set(text.length());
    return boundaries;
  }
}
