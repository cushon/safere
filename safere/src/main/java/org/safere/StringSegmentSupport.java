// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Support helper to access the backing storage of a {@link String}.
 *
 * <p><b>Future MemorySegment Support:</b> When SafeRE adopts a minimum baseline of Java 22+ (where
 * the Foreign Function &amp; Memory API is finalized without incubator/preview flags), this layer
 * can be adapted to return {@code MemorySegment}. This will enable unified SIMD/SWAR scanning
 * across in-heap Strings, off-heap memory, and memory-mapped buffers using {@code
 * ByteVector.fromMemorySegment} and {@code ShortVector.fromMemorySegment}.
 */
final class StringSegmentSupport {
  private static final VarHandle VALUE_HANDLE;
  private static final VarHandle CODER_HANDLE;

  static {
    try {
      MethodHandles.Lookup lookup =
          MethodHandles.privateLookupIn(String.class, MethodHandles.lookup());
      VALUE_HANDLE = lookup.findVarHandle(String.class, "value", byte[].class);
      CODER_HANDLE = lookup.findVarHandle(String.class, "coder", byte.class);
    } catch (Exception e) {
      throw new RuntimeException("Failed to access String internals via reflection", e);
    }
  }

  public static SegmentAndCharset stringAsSegment(String str) {
    byte[] value = (byte[]) VALUE_HANDLE.get(str);
    byte coder = (byte) CODER_HANDLE.get(str);
    return new SegmentAndCharset(value, coder);
  }

  private StringSegmentSupport() {}
}
