// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.io.Serializable;
import java.util.Arrays;

/**
 * Metadata for small multi-literal alternation acceleration (2 &le; N &le; 6) using
 * rarest-character multi-vector SIMD matching.
 */
@SuppressWarnings("ArrayRecordComponent")
record MultiLiteralInfo(
    String[] literals,
    char[] anchorChars,
    int[] anchorOffsets,
    int minLength)
    implements Serializable {

  static MultiLiteralInfo create(String[] literals) {
    if (literals == null || literals.length < 2 || literals.length > 6) {
      return null;
    }
    int minLen = Integer.MAX_VALUE;
    char[] anchorChars = new char[literals.length];
    int[] anchorOffsets = new int[literals.length];

    for (int i = 0; i < literals.length; i++) {
      String lit = literals[i];
      if (lit == null || lit.isEmpty()) {
        return null;
      }
      for (int j = 0; j < lit.length(); j++) {
        if (lit.charAt(j) > 127) {
          return null;
        }
      }
      int offset = RarityOracle.rarestAsciiOffset(lit, lit.length());
      anchorOffsets[i] = offset;
      anchorChars[i] = lit.charAt(offset);
      minLen = Math.min(minLen, lit.length());
    }

    return new MultiLiteralInfo(
        Arrays.copyOf(literals, literals.length),
        anchorChars,
        anchorOffsets,
        minLen);
  }
}
