// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.safere.Pattern.CharClassScanInfo;
import org.safere.Pattern.DisjointRequiredLiterals;

/**
 * Whole-input rejection filter (Tier 0 acceleration).
 *
 * <p>Rejects match attempts in O(1) / fast linear scan before invoking automata when mandatory
 * tokens or character classes are absent anywhere in the input.
 */
sealed interface RejectPrefilter
    permits RejectPrefilter.Literal,
        RejectPrefilter.CharClass,
        RejectPrefilter.DisjointLiterals,
        RejectPrefilter.Composite {

  /** Returns whether the input starting from {@code searchFrom} can be rejected. */
  boolean canReject(InputScanner scanner, String text, int searchFrom, EnginePathOptions options);

  /** Returns whether the UTF-8 input starting from {@code searchFrom} can be rejected. */
  boolean canReject(Utf8InputScanner scanner, int searchFrom, EnginePathOptions options);

  default boolean canRejectWithDiagnostics(
      Utf8InputScanner scanner,
      int searchFrom,
      EnginePathOptions options,
      DiagnosticAccumulator diagnostics) {
    if (canReject(scanner, searchFrom, options)) {
      diagnostics.participate(strategy(), StrategyRole.REJECT_PREFILTER);
      diagnostics.boundary(strategy());
      return true;
    }
    return false;
  }

  MatchStrategy strategy();

  static RejectPrefilter create(RejectDescriptor descriptor) {
    if (descriptor == null || !descriptor.hasRejectionFilter()) {
      return null;
    }
    RejectPrefilter litFilter =
        descriptor.requiredLiteral() != null ? Literal.create(descriptor.requiredLiteral()) : null;
    RejectPrefilter ccFilter =
        descriptor.requiredCharClass() != null
            ? CharClass.create(descriptor.requiredCharClass())
            : null;
    RejectPrefilter disjointFilter =
        descriptor.disjointRequiredLiterals() != null
            ? DisjointLiterals.create(descriptor.disjointRequiredLiterals())
            : null;

    List<RejectPrefilter> active = new ArrayList<>(3);
    if (litFilter != null) {
      active.add(litFilter);
    }
    if (ccFilter != null) {
      active.add(ccFilter);
    }
    if (disjointFilter != null) {
      active.add(disjointFilter);
    }

    if (active.isEmpty()) {
      return null;
    }
    if (active.size() == 1) {
      return active.get(0);
    }
    return new Composite(active.toArray(new RejectPrefilter[0]));
  }

  @SuppressWarnings("ArrayRecordComponent")
  record Literal(String literal, byte[] utf8, int[] failure, int[] shifts)
      implements RejectPrefilter {

    static Literal create(String literal) {
      byte[] utf8 = literal.getBytes(StandardCharsets.UTF_8);
      int[] failure = Pattern.literalFailure(utf8);
      int[] shifts = Pattern.literalShifts(utf8);
      return new Literal(literal, utf8, failure, shifts);
    }

    @Override
    public boolean canReject(
        InputScanner scanner, String text, int searchFrom, EnginePathOptions options) {
      if (!options.literalFastPaths()) {
        return false;
      }
      if (scanner instanceof Utf8InputScanner utf8Scanner) {
        return utf8Scanner.indexOf(utf8, failure, shifts, searchFrom) < 0;
      }
      if (text != null) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(Math.max(0, text.length() - searchFrom));
        }
        return text.indexOf(literal, searchFrom) < 0;
      }
      return false;
    }

    @Override
    public boolean canReject(Utf8InputScanner scanner, int searchFrom, EnginePathOptions options) {
      if (!options.literalFastPaths()) {
        return false;
      }
      return scanner.indexOf(utf8, failure, shifts, searchFrom) < 0;
    }

    @Override
    public MatchStrategy strategy() {
      return MatchStrategy.LITERAL;
    }
  }

  @SuppressWarnings("ArrayRecordComponent")
  record CharClass(int[] ranges, long bitmap0, long bitmap1) implements RejectPrefilter {

    static CharClass create(CharClassScanInfo scanInfo) {
      return new CharClass(scanInfo.ranges, scanInfo.bitmap0, scanInfo.bitmap1);
    }

    @Override
    public boolean canReject(
        InputScanner scanner, String text, int searchFrom, EnginePathOptions options) {
      if (!options.charClassMatchFastPaths()) {
        return false;
      }
      return scanner.indexOfCodePointClass(ranges, bitmap0, bitmap1, searchFrom) < 0;
    }

    @Override
    public boolean canReject(Utf8InputScanner scanner, int searchFrom, EnginePathOptions options) {
      if (!options.charClassMatchFastPaths()) {
        return false;
      }
      return scanner.indexOfCodePointClass(ranges, bitmap0, bitmap1, searchFrom) < 0;
    }

    @Override
    public MatchStrategy strategy() {
      return MatchStrategy.CHARACTER_CLASS;
    }
  }

  @SuppressWarnings("ArrayRecordComponent")
  record DisjointLiterals(
      String[] literals, byte[][] utf8Literals, int[][] failures, int[][] shifts)
      implements RejectPrefilter {

    static DisjointLiterals create(DisjointRequiredLiterals disjoint) {
      String[] literals = disjoint.literals();
      byte[][] utf8Literals = new byte[literals.length][];
      int[][] failures = new int[literals.length][];
      int[][] shifts = new int[literals.length][];
      for (int i = 0; i < literals.length; i++) {
        utf8Literals[i] = literals[i].getBytes(StandardCharsets.UTF_8);
        failures[i] = Pattern.literalFailure(utf8Literals[i]);
        shifts[i] = Pattern.literalShifts(utf8Literals[i]);
      }
      return new DisjointLiterals(literals, utf8Literals, failures, shifts);
    }

    @Override
    public boolean canReject(
        InputScanner scanner, String text, int searchFrom, EnginePathOptions options) {
      if (!options.literalFastPaths()) {
        return false;
      }
      if (scanner instanceof Utf8InputScanner utf8Scanner) {
        for (int i = 0; i < utf8Literals.length; i++) {
          if (utf8Scanner.indexOf(utf8Literals[i], failures[i], shifts[i], searchFrom) >= 0) {
            return false;
          }
        }
        return true;
      }
      if (text != null) {
        for (String literal : literals) {
          if (WorkCounterConfig.ENABLED) {
            WorkCounter.record(Math.max(0, text.length() - searchFrom));
          }
          if (text.indexOf(literal, searchFrom) >= 0) {
            return false;
          }
        }
        return true;
      }
      return false;
    }

    @Override
    public boolean canReject(Utf8InputScanner scanner, int searchFrom, EnginePathOptions options) {
      if (!options.literalFastPaths()) {
        return false;
      }
      for (int i = 0; i < utf8Literals.length; i++) {
        if (scanner.indexOf(utf8Literals[i], failures[i], shifts[i], searchFrom) >= 0) {
          return false;
        }
      }
      return true;
    }

    @Override
    public MatchStrategy strategy() {
      return MatchStrategy.LITERAL;
    }
  }

  @SuppressWarnings("ArrayRecordComponent")
  record Composite(RejectPrefilter[] filters) implements RejectPrefilter {
    @Override
    public boolean canReject(
        InputScanner scanner, String text, int searchFrom, EnginePathOptions options) {
      for (RejectPrefilter filter : filters) {
        if (filter.canReject(scanner, text, searchFrom, options)) {
          return true;
        }
      }
      return false;
    }

    @Override
    public boolean canReject(Utf8InputScanner scanner, int searchFrom, EnginePathOptions options) {
      for (RejectPrefilter filter : filters) {
        if (filter.canReject(scanner, searchFrom, options)) {
          return true;
        }
      }
      return false;
    }

    @Override
    public boolean canRejectWithDiagnostics(
        Utf8InputScanner scanner,
        int searchFrom,
        EnginePathOptions options,
        DiagnosticAccumulator diagnostics) {
      for (RejectPrefilter filter : filters) {
        if (filter.canRejectWithDiagnostics(scanner, searchFrom, options, diagnostics)) {
          return true;
        }
      }
      return false;
    }

    @Override
    public MatchStrategy strategy() {
      return filters[0].strategy();
    }
  }
}
