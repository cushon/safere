// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.nio.charset.StandardCharsets;
import org.safere.Pattern.DisjointRequiredLiterals;
import org.safere.RejectDescriptorCompiler.EndAnchoredCharClassInfo;
import org.safere.RejectDescriptorCompiler.SuffixInfo;

/**
 * Whole-input rejection filter (Tier 0 acceleration).
 *
 * <p>Rejects match attempts in O(1) / fast linear scan before invoking automata when mandatory
 * tokens or character classes are absent anywhere in the input.
 */
sealed interface RejectPrefilter
    permits RejectPrefilter.InfixSequence,
        RejectPrefilter.CharClass,
        RejectPrefilter.DisjointLiterals,
        RejectPrefilter.EndAnchoredSuffix,
        RejectPrefilter.EndAnchoredCharClass,
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
    RejectPrefilter endCcFilter =
        descriptor.endAnchoredCharClass() != null
            ? EndAnchoredCharClass.create(descriptor.endAnchoredCharClass())
            : null;
    RejectPrefilter seqFilter =
        descriptor.infixSequence() != null
            ? InfixSequence.create(descriptor.infixSequence())
            : descriptor.requiredLiteral() != null
                ? InfixSequence.create(
                    new RejectDescriptor.InfixSequence(
                        new String[] {descriptor.requiredLiteral()}, new int[] {0}))
                : null;
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
    if (endCcFilter != null) count++;
    if (seqFilter != null) count++;
    if (ccFilter != null) count++;
    if (disjointFilter != null) count++;

    if (count == 0) {
      return null;
    }
    if (count == 1) {
      if (suffixFilter != null) return suffixFilter;
      if (endCcFilter != null) return endCcFilter;
      if (seqFilter != null) return seqFilter;
      if (ccFilter != null) return ccFilter;
      if (disjointFilter != null) return disjointFilter;
      return null;
    }
    RejectPrefilter[] filters = new RejectPrefilter[count];
    int idx = 0;
    if (suffixFilter != null) {
      filters[idx++] = suffixFilter;
    }
    if (endCcFilter != null) {
      filters[idx++] = endCcFilter;
    }
    if (ccFilter != null) {
      filters[idx++] = ccFilter;
    }
    if (seqFilter != null) {
      filters[idx++] = seqFilter;
    }
    if (disjointFilter != null) {
      filters[idx] = disjointFilter;
    }
    return new Composite(filters);
  }

  @SuppressWarnings("ArrayRecordComponent")
  record InfixSequence(
      String[] infixes, byte[][] utf8, int[][] failure, int[][] shifts, int[] checkOrder)
      implements RejectPrefilter {

    static InfixSequence create(RejectDescriptor.InfixSequence seq) {
      String[] infixes = seq.infixes();
      byte[][] utf8 = new byte[infixes.length][];
      int[][] failure = new int[infixes.length][];
      int[][] shifts = new int[infixes.length][];
      for (int i = 0; i < infixes.length; i++) {
        utf8[i] = infixes[i].getBytes(StandardCharsets.UTF_8);
        failure[i] = Pattern.literalFailure(utf8[i]);
        shifts[i] = Pattern.literalShifts(utf8[i]);
      }
      return new InfixSequence(infixes, utf8, failure, shifts, seq.checkOrder());
    }

    @Override
    public boolean canReject(
        InputScanner scanner, String text, int searchFrom, EnginePathOptions options) {
      if (!options.literalFastPaths()) {
        return false;
      }
      if (scanner instanceof Utf8InputScanner utf8Scanner) {
        return canRejectUtf8(utf8Scanner, searchFrom);
      }
      if (text != null) {
        return canRejectString(text, searchFrom);
      }
      return false;
    }

    @Override
    public boolean canReject(Utf8InputScanner scanner, int searchFrom, EnginePathOptions options) {
      if (!options.literalFastPaths()) {
        return false;
      }
      return canRejectUtf8(scanner, searchFrom);
    }

    private boolean canRejectString(String text, int searchFrom) {
      if (infixes.length == 1) {
        int pos = text.indexOf(infixes[0], searchFrom);
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(
              pos < 0
                  ? Math.max(0, text.length() - searchFrom)
                  : Math.max(0, pos - searchFrom + infixes[0].length()));
        }
        return pos < 0;
      }

      int k = infixes.length;
      int r0 = checkOrder[0];

      // 1. Locate rarest anchor token
      int posR0 = text.indexOf(infixes[r0], searchFrom);
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record(
            posR0 < 0
                ? Math.max(0, text.length() - searchFrom)
                : Math.max(0, posR0 - searchFrom + infixes[r0].length()));
      }
      if (posR0 < 0) {
        return true;
      }

      int[] positions = new int[k];
      positions[r0] = posR0;

      // 2. Range-bounded downstream chain (r0 + 1 ... k - 1)
      int cursor = posR0 + infixes[r0].length();
      for (int j = r0 + 1; j < k; j++) {
        int pos = text.indexOf(infixes[j], cursor);
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(
              pos < 0
                  ? Math.max(0, text.length() - cursor)
                  : Math.max(0, pos - cursor + infixes[j].length()));
        }
        if (pos < 0) {
          return true;
        }
        positions[j] = pos;
        cursor = pos + infixes[j].length();
      }

      // 3. Upstream chain (0 ... r0 - 1)
      cursor = searchFrom;
      for (int i = 0; i < r0; i++) {
        int pos = text.indexOf(infixes[i], cursor);
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(
              pos < 0
                  ? Math.max(0, text.length() - cursor)
                  : Math.max(0, pos - cursor + infixes[i].length()));
        }
        if (pos < 0) {
          return true;
        }
        positions[i] = pos;
        cursor = pos + infixes[i].length();
      }

      // 4. Validate ordering between upstream tail and rarest anchor
      if (r0 > 0 && cursor > positions[r0]) {
        // Advance rarest anchor to next occurrence after upstream tail
        posR0 = text.indexOf(infixes[r0], cursor);
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(
              posR0 < 0
                  ? Math.max(0, text.length() - cursor)
                  : Math.max(0, posR0 - cursor + infixes[r0].length()));
        }
        if (posR0 < 0) {
          return true;
        }
        positions[r0] = posR0;
        // Re-verify downstream chain from the advanced rarest anchor
        cursor = posR0 + infixes[r0].length();
        for (int j = r0 + 1; j < k; j++) {
          if (cursor <= positions[j]) {
            cursor = positions[j] + infixes[j].length();
            continue;
          }
          int pos = text.indexOf(infixes[j], cursor);
          if (WorkCounterConfig.ENABLED) {
            WorkCounter.record(
                pos < 0
                    ? Math.max(0, text.length() - cursor)
                    : Math.max(0, pos - cursor + infixes[j].length()));
          }
          if (pos < 0) {
            return true;
          }
          positions[j] = pos;
          cursor = pos + infixes[j].length();
        }
      }

      return false;
    }

    private boolean canRejectUtf8(Utf8InputScanner scanner, int searchFrom) {
      if (utf8.length == 1) {
        return scanner.indexOf(utf8[0], failure[0], shifts[0], searchFrom) < 0;
      }

      int k = utf8.length;
      int r0 = checkOrder[0];

      // 1. Locate rarest anchor token
      int posR0 = scanner.indexOf(utf8[r0], failure[r0], shifts[r0], searchFrom);
      if (posR0 < 0) {
        return true;
      }

      int[] positions = new int[k];
      positions[r0] = posR0;

      // 2. Range-bounded downstream chain (r0 + 1 ... k - 1)
      int cursor = posR0 + utf8[r0].length;
      for (int j = r0 + 1; j < k; j++) {
        int pos = scanner.indexOf(utf8[j], failure[j], shifts[j], cursor);
        if (pos < 0) {
          return true;
        }
        positions[j] = pos;
        cursor = pos + utf8[j].length;
      }

      // 3. Upstream chain (0 ... r0 - 1)
      cursor = searchFrom;
      for (int i = 0; i < r0; i++) {
        int pos = scanner.indexOf(utf8[i], failure[i], shifts[i], cursor);
        if (pos < 0) {
          return true;
        }
        positions[i] = pos;
        cursor = pos + utf8[i].length;
      }

      // 4. Validate ordering between upstream tail and rarest anchor
      if (r0 > 0 && cursor > positions[r0]) {
        posR0 = scanner.indexOf(utf8[r0], failure[r0], shifts[r0], cursor);
        if (posR0 < 0) {
          return true;
        }
        positions[r0] = posR0;
        cursor = posR0 + utf8[r0].length;
        for (int j = r0 + 1; j < k; j++) {
          if (cursor <= positions[j]) {
            cursor = positions[j] + utf8[j].length;
            continue;
          }
          int pos = scanner.indexOf(utf8[j], failure[j], shifts[j], cursor);
          if (pos < 0) {
            return true;
          }
          positions[j] = pos;
          cursor = pos + utf8[j].length;
        }
      }

      return false;
    }

    @Override
    public MatchStrategy strategy() {
      return MatchStrategy.LITERAL;
    }
  }

  @SuppressWarnings("ArrayRecordComponent")
  record CharClass(int[] ranges, long bitmap0, long bitmap1) implements RejectPrefilter {

    static CharClass create(CharClassScanInfo scanInfo) {
      return new CharClass(scanInfo.ranges(), scanInfo.bitmap0(), scanInfo.bitmap1());
    }

    @Override
    public boolean canReject(
        InputScanner scanner, String text, int searchFrom, EnginePathOptions options) {
      if (!options.charClassMatchFastPaths()) {
        return false;
      }
      if (scanner != null) {
        return scanner.indexOfCodePointClass(ranges, bitmap0, bitmap1, searchFrom, scanner.length())
            < 0;
      }
      if (text != null) {
        int position = Math.max(0, searchFrom);
        int bound = text.length();
        while (position < bound) {
          int codePoint = text.codePointAt(position);
          if (InputScanner.classContains(ranges, bitmap0, bitmap1, codePoint)) {
            return false;
          }
          position += Character.charCount(codePoint);
        }
        return true;
      }
      return false;
    }

    @Override
    public boolean canReject(Utf8InputScanner scanner, int searchFrom, EnginePathOptions options) {
      if (!options.charClassMatchFastPaths()) {
        return false;
      }
      return scanner.indexOfCodePointClass(ranges, bitmap0, bitmap1, searchFrom, scanner.length())
          < 0;
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
      if (!options.literalFastPaths() || text == null || searchFrom > 0) {
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
  record EndAnchoredSuffix(
      String suffix, byte[] suffixUtf8, boolean wasDollar, boolean unixLines, boolean foldCase)
      implements RejectPrefilter {

    static EndAnchoredSuffix create(SuffixInfo info) {
      if (info == null || info.suffix() == null || info.suffix().isEmpty()) {
        return null;
      }
      byte[] utf8 = info.suffix().getBytes(StandardCharsets.UTF_8);
      return new EndAnchoredSuffix(
          info.suffix(), utf8, info.wasDollar(), info.unixLines(), info.foldCase());
    }

    @Override
    public boolean canReject(
        InputScanner scanner, String text, int searchFrom, EnginePathOptions options) {
      if (!options.literalFastPaths()) {
        return false;
      }
      if (scanner instanceof Utf8InputScanner utf8Scanner) {
        return !utf8Scanner.endsWith(suffixUtf8, wasDollar, unixLines, foldCase);
      }
      if (text != null) {
        return !endsWith(text, suffix, wasDollar, unixLines, foldCase);
      }
      return false;
    }

    @Override
    public boolean canReject(Utf8InputScanner scanner, int searchFrom, EnginePathOptions options) {
      if (!options.literalFastPaths()) {
        return false;
      }
      return !scanner.endsWith(suffixUtf8, wasDollar, unixLines, foldCase);
    }

    @Override
    public MatchStrategy strategy() {
      return MatchStrategy.LITERAL;
    }

    private static boolean endsWith(
        String text, String suffix, boolean wasDollar, boolean unixLines, boolean foldCase) {
      int suffixLen = suffix.length();
      int textLen = text.length();
      if (textLen >= suffixLen
          && (foldCase
              ? Ascii.regionMatchesIgnoreCase(text, textLen - suffixLen, suffix, suffixLen)
              : text.endsWith(suffix))) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(suffixLen);
        }
        return true;
      }
      if (!wasDollar || text.isEmpty()) {
        return false;
      }
      int trailingStart = StringInputScanner.trailingLineTerminatorStart(text, unixLines, textLen);
      if (trailingStart >= suffixLen
          && (foldCase
              ? Ascii.regionMatchesIgnoreCase(text, trailingStart - suffixLen, suffix, suffixLen)
              : text.startsWith(suffix, trailingStart - suffixLen))) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(suffixLen);
        }
        return true;
      }
      return false;
    }
  }

  record EndAnchoredCharClass(AsciiBitmap bitmap, boolean wasDollar, boolean unixLines)
      implements RejectPrefilter {
    static EndAnchoredCharClass create(EndAnchoredCharClassInfo info) {
      if (info == null || info.bitmap() == null) {
        return null;
      }
      return new EndAnchoredCharClass(info.bitmap(), info.wasDollar(), info.unixLines());
    }

    @Override
    public boolean canReject(
        InputScanner scanner, String text, int searchFrom, EnginePathOptions options) {
      if (!options.charClassMatchFastPaths()) {
        return false;
      }
      if (scanner instanceof Utf8InputScanner utf8Scanner) {
        return canReject(utf8Scanner, searchFrom, options);
      }
      if (text != null) {
        return canReject(text);
      }
      return false;
    }

    @Override
    public boolean canReject(Utf8InputScanner scanner, int searchFrom, EnginePathOptions options) {
      if (!options.charClassMatchFastPaths()) {
        return false;
      }
      int len = scanner.length();
      if (len == 0) {
        return true;
      }
      int ascii = scanner.asciiAt(len - 1);
      if (bitmap.contains(ascii)) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(1);
        }
        return false;
      }
      if (!wasDollar) {
        return true;
      }
      int prevPos = scanner.trailingLineTerminatorStart(unixLines, len);
      if (prevPos > 0) {
        int prevAscii = scanner.asciiAt(prevPos - 1);
        if (bitmap.contains(prevAscii)) {
          if (WorkCounterConfig.ENABLED) {
            WorkCounter.record(1);
          }
          return false;
        }
      }
      return true;
    }

    private boolean canReject(String text) {
      int len = text.length();
      if (len == 0) {
        return true;
      }
      char last = text.charAt(len - 1);
      if (bitmap.contains(last)) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(1);
        }
        return false;
      }
      if (!wasDollar) {
        return true;
      }
      int prevPos = StringInputScanner.trailingLineTerminatorStart(text, unixLines, len);
      if (prevPos > 0) {
        char prev = text.charAt(prevPos - 1);
        if (bitmap.contains(prev)) {
          if (WorkCounterConfig.ENABLED) {
            WorkCounter.record(1);
          }
          return false;
        }
      }
      return true;
    }

    @Override
    public MatchStrategy strategy() {
      return MatchStrategy.CHARACTER_CLASS;
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
