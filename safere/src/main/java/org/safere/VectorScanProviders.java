// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

final class VectorScanProviders {
  static final String PROVIDER_PROPERTY = "org.safere.experimental.vectorScanProvider";
  private static final VectorScanProvider SELECTED = loadSelected();

  private VectorScanProviders() {}

  static VectorScanProvider providerForLength(int length) {
    return SELECTED != null && length >= SELECTED.minimumInputLength() ? SELECTED : null;
  }

  static VectorScanProvider providerForTeddyLength(int length) {
    return SELECTED != null && length >= SELECTED.minimumTeddyInputLength() ? SELECTED : null;
  }

  static boolean teddyProviderAvailable() {
    return SELECTED != null;
  }

  static VectorScanProvider providerForMultiLiteralLength(int length) {
    return SELECTED != null && length >= SELECTED.minimumMultiLiteralInputLength()
        ? SELECTED
        : null;
  }

  static boolean multiLiteralProviderAvailable() {
    return SELECTED != null;
  }

  private static VectorScanProvider loadSelected() {
    String requested = System.getProperty(PROVIDER_PROPERTY, "").trim();
    if (requested.isEmpty()) {
      return null;
    }
    if ("incubator".equals(requested)) {
      try {
        return (VectorScanProvider)
            Class.forName("org.safere.IncubatorVectorScanProvider")
                .getDeclaredConstructor()
                .newInstance();
      } catch (ReflectiveOperationException | LinkageError e) {
        return null;
      }
    }
    try {
      return (VectorScanProvider)
          Class.forName(requested).getDeclaredConstructor().newInstance();
    } catch (ReflectiveOperationException | LinkageError e) {
      return null;
    }
  }
}
