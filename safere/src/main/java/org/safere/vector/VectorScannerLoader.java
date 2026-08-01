// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.vector;

import java.util.Objects;

/** Loads the vector scanner implementation via reflection if available on the classpath. */
public final class VectorScannerLoader {
  private static final VectorScannerBridge INSTANCE = loadInstance();

  private static VectorScannerBridge loadInstance() {
    if (Boolean.getBoolean("safere.vector.disabled")) {
      return null;
    }
    String mode = System.getProperty("safere.vector.mode", "unsafe");
    String className;
    if (Objects.equals(mode, "copy")) {
      className = "org.safere.vector.VectorScannerCopyImpl";
    } else if (Objects.equals(mode, "unsafe-byte")) {
      className = "org.safere.vector.VectorScannerUnsafeByteImpl";
    } else {
      className = "org.safere.vector.VectorScannerImpl";
    }
    try {
      Class<? extends VectorScannerBridge> implClass =
          Class.forName(className).asSubclass(VectorScannerBridge.class);
      return implClass.getDeclaredConstructor().newInstance();
    } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
      System.err.println("CRITICAL: Failed to load " + className + "!");
      e.printStackTrace();
      return null;
    }
  }

  private VectorScannerLoader() {}

  /** Returns the vector scanner instance, or {@code null} if not supported at runtime. */
  public static VectorScannerBridge getInstance() {
    return INSTANCE;
  }
}
