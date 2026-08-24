// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.nio.charset.StandardCharsets;
import org.safere.Pattern.FixedOffsetLiteral;
import org.safere.Pattern.StartAcceleration;

/**
 * Immutable descriptor capturing pre-computed start-position metadata extracted from a regular
 * expression AST.
 */
sealed interface StartDescriptor
    permits StartDescriptor.Literal,
        StartDescriptor.FixedOffset,
        StartDescriptor.CharClass,
        StartDescriptor.LineAnchor,
        StartDescriptor.MultiLiteral,
        StartDescriptor.Teddy,
        StartDescriptor.LeadingExpansion,
        StartDescriptor.ReverseAnchor,
        StartDescriptor.None {

  interface HasCharClassPrefix {
    CharClassScanInfo charClassPrefix();
  }

  interface HasTeddyModel {
    TeddyModel teddyModel();
  }

  @SuppressWarnings("ArrayRecordComponent")
  record Literal(
      String prefix,
      boolean prefixFoldCase,
      String anchoredPrefix,
      byte[] prefixUtf8,
      byte[] anchoredPrefixUtf8)
      implements StartDescriptor {
    Literal(String prefix, boolean prefixFoldCase, String anchoredPrefix) {
      this(
          prefix,
          prefixFoldCase,
          anchoredPrefix,
          prefix == null || prefix.isEmpty() ? null : prefix.getBytes(StandardCharsets.UTF_8),
          anchoredPrefix == null || anchoredPrefix.isEmpty()
              ? null
              : anchoredPrefix.getBytes(StandardCharsets.UTF_8));
    }
  }

  record FixedOffset(FixedOffsetLiteral fixedOffsetLiteral, CharClassScanInfo charClassPrefix)
      implements StartDescriptor, HasCharClassPrefix {}

  record CharClass(CharClassScanInfo charClassPrefix, CharClassScanInfo anchoredCharClassPrefix)
      implements StartDescriptor, HasCharClassPrefix {}

  record LineAnchor(StartAcceleration lineAnchor) implements StartDescriptor {}

  record MultiLiteral(MultiLiteralInfo multiLiteral, TeddyModel teddyModel)
      implements StartDescriptor, HasTeddyModel {}

  record Teddy(TeddyModel teddyModel) implements StartDescriptor, HasTeddyModel {}

  record LeadingExpansion(
      CharClassScanInfo leadingClass,
      int minRepetition,
      int maxRepetition,
      StartDescriptor innerDescriptor)
      implements StartDescriptor {}

  record ReverseAnchor(
      StartDescriptor anchorDescriptor, Prog reversePrefixProg, int minLength)
      implements StartDescriptor {}

  enum None implements StartDescriptor {
    INSTANCE
  }

  default boolean hasStartAcceleration() {
    return !(this instanceof None);
  }
}
