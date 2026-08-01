// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.vector;

import org.safere.Pattern.CharClassScanInfo;

/** Interface for vector scanning implementations. */
public interface VectorScannerBridge {
  /**
   * Scans the string for the first character matching the character class. Returns the index, or -1
   * if not found.
   */
  int indexOfCharClass(String text, CharClassScanInfo scanInfo, int start);

  /**
   * Scans the string for the first code point matching the code point class. Returns the index, or
   * -1 if not found. Returns -2 if fallback is requested.
   */
  int indexOfCodePointClass(String text, int[] ranges, long bitmap0, long bitmap1, int start);
}
