// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Access gateway to inspect internal backing storage of a {@link String} when {@code java.base} is
 * open.
 */
final class StringSupport {
  private static final VarHandle VALUE_HANDLE;
  private static final VarHandle CODER_HANDLE;
  private static final boolean HAS_ACCESS;

  static {
    VarHandle valueHandle = null;
    VarHandle coderHandle = null;
    boolean accessible = false;
    try {
      Module baseModule = String.class.getModule();
      Module ourModule = StringSupport.class.getModule();
      if (baseModule.isOpen("java.lang", ourModule)) {
        MethodHandles.Lookup lookup =
            MethodHandles.privateLookupIn(String.class, MethodHandles.lookup());
        valueHandle = lookup.findVarHandle(String.class, "value", byte[].class);
        coderHandle = lookup.findVarHandle(String.class, "coder", byte.class);
        accessible = true;
      }
    } catch (Throwable ignored) {
      // java.base is not open or reflection failed
    }
    VALUE_HANDLE = valueHandle;
    CODER_HANDLE = coderHandle;
    HAS_ACCESS = accessible;
  }

  public static boolean hasAccess() {
    return HAS_ACCESS;
  }

  public static byte[] value(String str) {
    if (!HAS_ACCESS) {
      throw new UnsupportedOperationException(
          "String internal array access not available; open java.base/java.lang to "
              + StringSupport.class.getModule().getName());
    }
    return (byte[]) VALUE_HANDLE.get(str);
  }

  public static byte coder(String str) {
    if (!HAS_ACCESS) {
      throw new UnsupportedOperationException(
          "String internal array access not available; open java.base/java.lang to "
              + StringSupport.class.getModule().getName());
    }
    return (byte) CODER_HANDLE.get(str);
  }

  public static boolean isLatin1(String str) {
    return coder(str) == 0;
  }

  private StringSupport() {}
}
