// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import org.safere.internal.Ascii;

/** Shared linear-time searches over foreign-memory inputs. */
final class SegmentAsciiSearch {

  private static final ValueLayout.OfShort UTF16_SHORT =
      ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

  static int indexOfIgnoreCase(
      MemorySegment segment, long offset, int length, String pattern, int start) {
    if (pattern.isEmpty()) {
      return Math.min(Math.max(0, start), length);
    }
    int[] failure = Ascii.ignoreCaseFailure(pattern);
    int matched = 0;
    for (int i = Math.max(0, start); i < length; i++) {
      char ch = (char) (segment.get(ValueLayout.JAVA_BYTE, offset + i) & 0xFF);
      while (matched > 0 && !Ascii.equalsIgnoreCase(ch, pattern.charAt(matched))) {
        matched = failure[matched - 1];
      }
      if (Ascii.equalsIgnoreCase(ch, pattern.charAt(matched))) {
        matched++;
        if (matched == pattern.length()) {
          return i - pattern.length() + 1;
        }
      }
    }
    return -1;
  }

  static int indexOfIgnoreCaseUtf16(
      MemorySegment segment, long byteOffset, int length, String pattern, int start) {
    if (pattern.isEmpty()) {
      return Math.min(Math.max(0, start), length);
    }
    int[] failure = Ascii.ignoreCaseFailure(pattern);
    int matched = 0;
    for (int i = Math.max(0, start); i < length; i++) {
      char ch = (char) (segment.get(UTF16_SHORT, byteOffset + ((long) i << 1)) & 0xFFFF);
      while (matched > 0 && !Ascii.equalsIgnoreCase(ch, pattern.charAt(matched))) {
        matched = failure[matched - 1];
      }
      if (Ascii.equalsIgnoreCase(ch, pattern.charAt(matched))) {
        matched++;
        if (matched == pattern.length()) {
          return i - pattern.length() + 1;
        }
      }
    }
    return -1;
  }

  private SegmentAsciiSearch() {}
}
