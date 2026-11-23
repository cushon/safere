// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import java.util.List;
import java.util.function.IntUnaryOperator;
import org.safere.Pattern;

/** Small shared grammar for the general-purpose semantic targets. */
final class SemanticDomain {
  private static final List<Atom> ATOMS =
      List.of(
          new Atom(RegexFragment.zeroWidth(""), ""),
          Atom.consuming("a", "a"),
          Atom.consuming("b", "b"),
          Atom.consuming(",", ","),
          Atom.consuming("[a-z]", "b"),
          Atom.consuming("[^a]", "b"),
          Atom.consuming("\\d", "1"),
          Atom.consuming("\\s", " "),
          Atom.consuming(".", "é"),
          Atom.consuming("😀", "😀"),
          Atom.consuming("\\R", "\r\n"),
          Atom.consuming("\\X", "e\u0301"),
          Atom.consuming("\\p{Lu}", "A"));

  private static final List<String> GROUP_OPENERS = List.of("(?:", "(", "(?i:");

  private record Atom(RegexFragment fragment, String witness) {
    static Atom consuming(String regex, String witness) {
      return new Atom(RegexFragment.consuming(regex), witness);
    }
  }

  record Sample(String regex, String witness, boolean usesPreviousMatchBoundary) {
    Sample(String regex, String witness) {
      this(regex, witness, false);
    }

    String input(FuzzedDataProvider data) {
      return switch (data.consumeInt(0, 3)) {
        case 0 -> witness;
        case 1 -> witness + "!" + witness;
        case 2 -> data.consumeString(2048);
        default -> FuzzSupport.consumeUnicodeHeavyString(data, 64);
      };
    }
  }

  private SemanticDomain() {}

  static Sample generate(FuzzedDataProvider data, int flags) {
    if ((flags & Pattern.LITERAL) != 0) {
      String literal = data.consumeString(64);
      return new Sample(literal, literal);
    }
    return generate(bound -> data.consumeInt(0, bound - 1));
  }

  static Sample generate(IntUnaryOperator choice) {
    Sample left = part(choice);
    return switch (choice.applyAsInt(7)) {
      case 0 -> left;
      case 1 -> concatenate(left, part(choice), "");
      case 2 -> {
        Sample right = part(choice);
        yield new Sample(
            StrictDomain.alternate(left.regex, right.regex),
            choice.applyAsInt(2) == 0 ? left.witness : right.witness);
      }
      case 3 -> new Sample("^" + left.regex + "$", left.witness);
      case 4 -> new Sample("\\G" + left.regex, left.witness, true);
      case 5 -> concatenate(left, part(choice), "\\b");
      default -> {
        Atom nested = repeatedComposite(choice, 1 + choice.applyAsInt(2));
        yield new Sample(nested.fragment.regex(), nested.witness);
      }
    };
  }

  private static Sample concatenate(Sample left, Sample right, String separator) {
    return new Sample(left.regex + separator + right.regex, left.witness + right.witness);
  }

  private static Sample part(IntUnaryOperator choice) {
    Atom atom = pick(choice, ATOMS);
    String opener = pick(choice, GROUP_OPENERS);
    RegexFragment fragment = atom.fragment.group(opener);
    return switch (choice.applyAsInt(6)) {
      case 0 -> new Sample(fragment.regex(), atom.witness);
      case 1 -> repeat(choice, fragment, atom.witness, "?", 1, 0);
      case 2 -> repeat(choice, fragment, atom.witness, "*", 2, 0);
      case 3 -> repeat(choice, fragment, atom.witness, "+", 2, 1);
      case 4 -> repeat(choice, fragment, atom.witness, "{1,2}", 2, 1);
      default -> repeat(choice, fragment.repeat("{0}", 0).group("(?:"), "", "{0,2}", 2, 0);
    };
  }

  private static Atom repeatedComposite(IntUnaryOperator choice, int depth) {
    if (depth == 0) {
      Atom atom = pick(choice, ATOMS);
      return new Atom(atom.fragment.group(pick(choice, GROUP_OPENERS)), atom.witness);
    }

    Atom left = repeatedComposite(choice, depth - 1);
    Atom right = repeatedComposite(choice, depth - 1);
    boolean alternate = choice.applyAsInt(2) == 1;
    RegexFragment operand =
        (alternate ? left.fragment.or(right.fragment) : left.fragment.then(right.fragment))
            .group("(?:");
    String witness =
        alternate
            ? (choice.applyAsInt(2) == 0 ? left.witness : right.witness)
            : left.witness + right.witness;

    // Bound each composite repetition so ambiguous nesting stays cheap in the JDK oracle.
    int minimum = choice.applyAsInt(2);
    String quantifier = minimum == 0 ? "{0,2}" : "{1,2}";
    String modifier = operand.modifier(pick(choice, List.of("", "?", "+")));
    int copies = minimum + choice.applyAsInt(3 - minimum);
    return new Atom(operand.repeat(quantifier + modifier, 2), witness.repeat(copies));
  }

  private static Sample repeat(
      IntUnaryOperator choice,
      RegexFragment operand,
      String witness,
      String quantifier,
      int sampleMaximum,
      int minimum) {
    String mode = operand.modifier(pick(choice, List.of("", "?", "+")));
    int copies = minimum + choice.applyAsInt(sampleMaximum - minimum + 1);
    return new Sample(operand.regex() + quantifier + mode, witness.repeat(copies));
  }

  private static <T> T pick(IntUnaryOperator choice, List<T> values) {
    return values.get(choice.applyAsInt(values.size()));
  }
}
