// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.nio.charset.StandardCharsets;
import org.safere.Pattern.CharClassScanInfo;
import org.safere.Pattern.DisjointRequiredLiterals;
import org.safere.Pattern.SuffixInfo;

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
        RejectPrefilter.EndAnchoredSuffix,
        RejectPrefilter.Composite {

  /** Returns whether the input starting from {@code searchFrom} can be rejected. */
  boolean canReject(InputScanner scanner, String text, int searchFrom, EnginePathOptions options);

  /** Returns the strategy that rejected the input, or {@code null} if it cannot be rejected. */
  default MatchStrategy rejectionStrategy(
      InputScanner scanner, String text, int searchFrom, EnginePathOptions options) {
    return canReject(scanner, text, searchFrom, options) ? strategy() : null;
  }

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
    if (descriptor == null) {
      return null;
    }
    RejectPrefilter suffixFilter =
        descriptor.endAnchoredSuffix() != null
            ? EndAnchoredSuffix.create(descriptor.endAnchoredSuffix())
            : null;
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

    int count = 0;
    if (suffixFilter != null) count++;
    if (litFilter != null) count++;
    if (ccFilter != null) count++;
    if (disjointFilter != null) count++;

    if (count == 0) {
      return null;
    }
    if (count == 1) {
      if (suffixFilter != null) return suffixFilter;
      if (litFilter != null) return litFilter;
      if (ccFilter != null) return ccFilter;
      return disjointFilter;
    }
    RejectPrefilter[] filters = new RejectPrefilter[count];
    int idx = 0;
    if (suffixFilter != null) {
      filters[idx++] = suffixFilter;
    }
    if (litFilter != null) {
      filters[idx++] = litFilter;
    }
    if (ccFilter != null) {
      filters[idx++] = ccFilter;
    }
    if (disjointFilter != null) {
      filters[idx] = disjointFilter;
    }
    return new Composite(filters);
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
  record DisjointLiterals(String[] literals) implements RejectPrefilter {

    static DisjointLiterals create(DisjointRequiredLiterals disjoint) {
      if (disjoint == null || disjoint.literals() == null || disjoint.literals().length == 0) {
        return null;
      }
      return new DisjointLiterals(disjoint.literals());
    }

    @Override
    public boolean canReject(
        InputScanner scanner, String text, int searchFrom, EnginePathOptions options) {
      if (!options.literalFastPaths() || text == null) {
        return false;
      }
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

    @Override
    public boolean canReject(Utf8InputScanner scanner, int searchFrom, EnginePathOptions options) {
      return false;
    }

    @Override
    public MatchStrategy strategy() {
      return MatchStrategy.LITERAL;
    }
  }

  @SuppressWarnings("ArrayRecordComponent")
  record EndAnchoredSuffix(String suffix, byte[] suffixUtf8, boolean wasDollar)
      implements RejectPrefilter {

    static EndAnchoredSuffix create(SuffixInfo info) {
      if (info == null || info.suffix() == null || info.suffix().isEmpty()) {
        return null;
      }
      byte[] utf8 = info.suffix().getBytes(StandardCharsets.UTF_8);
      return new EndAnchoredSuffix(info.suffix(), utf8, info.wasDollar());
    }

    @Override
    public boolean canReject(
        InputScanner scanner, String text, int searchFrom, EnginePathOptions options) {
      if (!options.literalFastPaths()) {
        return false;
      }
      if (scanner instanceof Utf8InputScanner utf8Scanner) {
        return !utf8Scanner.endsWith(suffixUtf8, wasDollar);
      }
      if (text != null) {
        return !endsWith(text, suffix, wasDollar);
      }
      return false;
    }

    @Override
    public boolean canReject(Utf8InputScanner scanner, int searchFrom, EnginePathOptions options) {
      if (!options.literalFastPaths()) {
        return false;
      }
      return !scanner.endsWith(suffixUtf8, wasDollar);
    }

    @Override
    public MatchStrategy strategy() {
      return MatchStrategy.LITERAL;
    }

    private static boolean endsWith(String text, String suffix, boolean wasDollar) {
      int suffixLen = suffix.length();
      if (text.endsWith(suffix)) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(suffixLen);
        }
        return true;
      }
      if (!wasDollar || text.isEmpty()) {
        return false;
      }
      char last = text.charAt(text.length() - 1);
      if (last == '\n') {
        int effectiveLen =
            (text.length() >= 2 && text.charAt(text.length() - 2) == '\r')
                ? text.length() - 2
                : text.length() - 1;
        if (effectiveLen >= suffixLen && text.startsWith(suffix, effectiveLen - suffixLen)) {
          if (WorkCounterConfig.ENABLED) {
            WorkCounter.record(suffixLen);
          }
          return true;
        }
      } else if (last == '\r') {
        int effectiveLen = text.length() - 1;
        if (effectiveLen >= suffixLen && text.startsWith(suffix, effectiveLen - suffixLen)) {
          if (WorkCounterConfig.ENABLED) {
            WorkCounter.record(suffixLen);
          }
          return true;
        }
      }
      return false;
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
    public MatchStrategy rejectionStrategy(
        InputScanner scanner, String text, int searchFrom, EnginePathOptions options) {
      for (RejectPrefilter filter : filters) {
        MatchStrategy strategy = filter.rejectionStrategy(scanner, text, searchFrom, options);
        if (strategy != null) {
          return strategy;
        }
      }
      return null;
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
