// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Support helper to obtain a {@link SegmentAndCharset} view of a {@link String}. Uses {@code
 * String.asSegment()} if present on newer JDKs, or falls back to reflection on String internals.
 */
final class StringSegmentSupport {
  static final Charset NATIVE_UTF16 =
      ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
          ? StandardCharsets.UTF_16LE
          : StandardCharsets.UTF_16BE;

  private static final MethodHandle AS_SEGMENT_HANDLE;
  private static final VarHandle VALUE_HANDLE;
  private static final VarHandle CODER_HANDLE;

  static {
    MethodHandle handle = null;
    try {
      handle =
          MethodHandles.publicLookup()
              .findVirtual(
                  String.class, "asSegment", MethodType.methodType(SegmentAndCharset.class));
    } catch (NoSuchMethodException | IllegalAccessException ignored) {
      // Upstream API not yet present; use fallback
    }
    AS_SEGMENT_HANDLE = handle;

    VarHandle valHandle = null;
    VarHandle coderHandle = null;
    if (AS_SEGMENT_HANDLE == null) {
      try {
        MethodHandles.Lookup lookup =
            MethodHandles.privateLookupIn(String.class, MethodHandles.lookup());
        valHandle = lookup.findVarHandle(String.class, "value", byte[].class);
        coderHandle = lookup.findVarHandle(String.class, "coder", byte.class);
      } catch (Exception e) {
        throw new RuntimeException("Failed to access String internals via reflection", e);
      }
    }
    VALUE_HANDLE = valHandle;
    CODER_HANDLE = coderHandle;
  }

  public static SegmentAndCharset stringAsSegment(String str) {
    if (AS_SEGMENT_HANDLE != null) {
      try {
        return (SegmentAndCharset) AS_SEGMENT_HANDLE.invokeExact(str);
      } catch (Throwable ignored) {
        // Fall back to reflection
      }
    }
    byte[] value = (byte[]) VALUE_HANDLE.get(str);
    byte coder = (byte) CODER_HANDLE.get(str);
    MemorySegment segment = MemorySegment.ofArray(value);
    return new SegmentAndCharset(segment, coder == 0 ? StandardCharsets.ISO_8859_1 : NATIVE_UTF16);
  }

  private StringSegmentSupport() {}
}
