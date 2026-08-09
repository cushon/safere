// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Encapsulates access to a String's backing byte array and its coder byte (Latin-1 vs UTF-16). */
final class SegmentAndCharset {
  private final byte[] value;
  private final byte coder;

  SegmentAndCharset(byte[] value, byte coder) {
    this.value = value;
    this.coder = coder;
  }

  byte[] value() {
    return value;
  }

  byte coder() {
    return coder;
  }

  boolean isLatin1() {
    return coder == 0;
  }
}
