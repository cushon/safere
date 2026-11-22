// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.nativeOrder;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;
import org.safere.StringVectorLoads.Mode;

/**
 * {@link ShortVector} scans over {@link String} contents, with one loop per way of loading the
 * vectors and one shared match kernel.
 *
 * <p>Only {@link StringVectorLoads} should call into this class in production, after it has checked
 * that the incubator module is readable. The mode argument is {@link StringVectorLoads#MODE}, a
 * constant, so C2 removes the dispatch.
 */
final class StringVectorScan {
  // The loops read these constants directly rather than taking species parameters. The Vector API
  // only intrinsifies operations on a constant species, and a loop that C2 compiles on its own,
  // rather than inlined into a caller that passes a constant, would box every vector.
  private static final VectorSpecies<Short> SPECIES = ShortVector.SPECIES_PREFERRED;
  private static final int LANES = SPECIES.length();
  private static final VectorSpecies<Byte> LATIN1_SPECIES = latin1Species(SPECIES);
  private static final VectorSpecies<Byte> UTF16_SPECIES = SPECIES.withLanes(byte.class);
  private static final boolean NATIVE_BIG_ENDIAN = nativeOrder() == BIG_ENDIAN;

  /**
   * {@code ShortVector.fromString(VectorSpecies<Short>, String, int)}, or {@code null} when the
   * runtime does not have it. SafeRE targets Java 21, so it is looked up rather than linked.
   */
  private static final MethodHandle FROM_STRING = findFromString();

  /**
   * The scan entry point, behind a field that C2 does not treat as a constant. Calls through it
   * can't be inlined, so C2 compiles {@link #indexOfCharClass} as its own root. Inlined into a deep
   * caller instead, the Vector API calls below it can exceed {@code MaxInlineLevel} and stop being
   * intrinsified, which made the scans slower than scalar ones.
   */
  private static final OutOfLine OUT_OF_LINE = new OutOfLine();

  private StringVectorScan() {}

  /** Like {@link #indexOfCharClass}, but never inlined into the caller. */
  static int indexOfCharClassOutOfLine(Mode mode, String text, int[] ranges, int start) {
    try {
      return (int) OUT_OF_LINE.indexOfCharClass.invokeExact(mode, text, ranges, start);
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable t) {
      throw new AssertionError(t);
    }
  }

  /** Returns the mode to use for {@code requested}, or the default when it is not available. */
  static Mode select(Mode requested) {
    if (requested == Mode.REFLECTIVE && Reflective.AVAILABLE) {
      return Mode.REFLECTIVE;
    }
    return FROM_STRING != null ? Mode.JDK : Mode.NONE;
  }

  /** Returns whether {@code mode} can load vectors in this JVM. For tests. */
  static boolean available(Mode mode) {
    return switch (mode) {
      case NONE -> false;
      case JDK -> FROM_STRING != null;
      case REFLECTIVE -> Reflective.AVAILABLE;
    };
  }

  /**
   * Returns the first index at or after {@code start} of a code unit in {@code ranges}, {@code -1}
   * when there is none, or {@link VectorScanProvider#UNSUPPORTED}. See {@link
   * StringVectorLoads#indexOfCharClass}. Tests call this directly to force a mode.
   */
  static int indexOfCharClass(Mode mode, String text, int[] ranges, int start) {
    if (!Swar.supportsBmpCodeUnitRanges(ranges, ShortVectorScan.MAXIMUM_RANGES)) {
      return VectorScanProvider.UNSUPPORTED;
    }
    int from = Math.max(0, start);
    return switch (mode) {
      case NONE -> VectorScanProvider.UNSUPPORTED;
      case JDK ->
          FROM_STRING == null
              ? VectorScanProvider.UNSUPPORTED
              : indexOfCharClassJdk(text, ranges, from);
      case REFLECTIVE ->
          Reflective.AVAILABLE
              ? indexOfCharClassReflective(text, ranges, from)
              : VectorScanProvider.UNSUPPORTED;
    };
  }

  /**
   * Runs the same match kernel over {@code chars}, loaded with {@code ShortVector.fromCharArray}.
   * For tests, so that the kernel is covered on JVMs where no String load is available.
   */
  static int indexOfCharClass(char[] chars, int[] ranges, int start) {
    if (!Swar.supportsBmpCodeUnitRanges(ranges, ShortVectorScan.MAXIMUM_RANGES)) {
      return VectorScanProvider.UNSUPPORTED;
    }
    int position = Math.max(0, start);
    for (; position <= chars.length - LANES; position += LANES) {
      VectorMask<Short> matches =
          ShortVectorScan.matches(ShortVector.fromCharArray(SPECIES, chars, position), ranges);
      if (matches.anyTrue()) {
        return position + matches.firstTrue();
      }
    }
    for (; position < chars.length; position++) {
      if (ShortVectorScan.matches(chars[position], ranges)) {
        return position;
      }
    }
    return -1;
  }

  private static int indexOfCharClassJdk(String text, int[] ranges, int start) {
    int length = text.length();
    int position = start;
    for (; position <= length - LANES; position += LANES) {
      VectorMask<Short> matches = ShortVectorScan.matches(fromString(text, position), ranges);
      if (matches.anyTrue()) {
        return position + matches.firstTrue();
      }
    }
    return indexOfCharClassScalar(text, ranges, position);
  }

  private static ShortVector fromString(String text, int offset) {
    try {
      return (ShortVector) FROM_STRING.invokeExact(SPECIES, text, offset);
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable t) {
      throw new AssertionError(t);
    }
  }

  private static int indexOfCharClassReflective(String text, int[] ranges, int start) {
    byte[] value = (byte[]) Reflective.VALUE.get(text);
    if ((byte) Reflective.CODER.get(text) == Reflective.LATIN1) {
      return indexOfCharClassLatin1(text, value, ranges, start);
    }
    return indexOfCharClassUtf16(text, value, ranges, start);
  }

  private static int indexOfCharClassLatin1(String text, byte[] value, int[] ranges, int start) {
    // A 64-bit ShortVector has no half-width ByteVector shape, so it loads a wider one and uses
    // only its low lanes. Bound the loop by the wider load so that it never reads past the end.
    int limit = value.length - Math.max(LANES, LATIN1_SPECIES.length());
    int position = start;
    for (; position <= limit; position += LANES) {
      ShortVector values =
          (ShortVector)
              ByteVector.fromArray(LATIN1_SPECIES, value, position)
                  .convertShape(VectorOperators.ZERO_EXTEND_B2S, SPECIES, 0);
      VectorMask<Short> matches = ShortVectorScan.matches(values, ranges);
      if (matches.anyTrue()) {
        return position + matches.firstTrue();
      }
    }
    return indexOfCharClassScalar(text, ranges, position);
  }

  private static int indexOfCharClassUtf16(String text, byte[] value, int[] ranges, int start) {
    int limit = (value.length >> 1) - LANES;
    int position = start;
    for (; position <= limit; position += LANES) {
      // UTF-16 Strings hold their chars in native byte order.
      ShortVector values =
          ByteVector.fromArray(UTF16_SPECIES, value, position << 1).reinterpretAsShorts();
      if (NATIVE_BIG_ENDIAN) {
        values = values.lanewise(VectorOperators.REVERSE_BYTES);
      }
      VectorMask<Short> matches = ShortVectorScan.matches(values, ranges);
      if (matches.anyTrue()) {
        return position + matches.firstTrue();
      }
    }
    return indexOfCharClassScalar(text, ranges, position);
  }

  private static int indexOfCharClassScalar(String text, int[] ranges, int start) {
    for (int position = start; position < text.length(); position++) {
      if (ShortVectorScan.matches(text.charAt(position), ranges)) {
        return position;
      }
    }
    return -1;
  }

  /**
   * Returns a byte species with as many lanes as {@code species}, or the narrowest byte species
   * when no shape is half as wide (for 64-bit species), like {@code ShortVector.fromString} does
   * for Latin-1 Strings.
   */
  private static VectorSpecies<Byte> latin1Species(VectorSpecies<Short> species) {
    int bits = species.vectorBitSize() / 2;
    for (VectorShape shape : VectorShape.values()) {
      if (shape != VectorShape.S_Max_BIT && shape.vectorBitSize() == bits) {
        return VectorSpecies.of(byte.class, shape);
      }
    }
    return ByteVector.SPECIES_64;
  }

  private static MethodHandle findFromString() {
    MethodHandle fromString;
    try {
      fromString =
          MethodHandles.publicLookup()
              .findStatic(
                  ShortVector.class,
                  "fromString",
                  MethodType.methodType(
                      ShortVector.class, VectorSpecies.class, String.class, int.class));
    } catch (ReflectiveOperationException e) {
      return null;
    }
    String probe = "fromString ShortVector probe \u0100 0123456789 abcdefghijklmnopqrstuvwxyz";
    try {
      for (String text : new String[] {probe.substring(0, probe.indexOf('\u0100')), probe}) {
        for (int offset = 0; offset + SPECIES.length() <= text.length(); offset++) {
          ShortVector values = (ShortVector) fromString.invokeExact(SPECIES, text, offset);
          for (int lane = 0; lane < SPECIES.length(); lane++) {
            if (values.lane(lane) != (short) text.charAt(offset + lane)) {
              return null;
            }
          }
        }
      }
    } catch (Throwable t) {
      return null;
    }
    return fromString;
  }

  /**
   * Holds a {@link MethodHandle} in a final instance field, which C2 does not trust as a constant
   * (unlike static finals and record components).
   */
  private static final class OutOfLine {
    final MethodHandle indexOfCharClass;

    OutOfLine() {
      try {
        indexOfCharClass =
            MethodHandles.lookup()
                .findStatic(
                    StringVectorScan.class,
                    "indexOfCharClass",
                    MethodType.methodType(
                        int.class, Mode.class, String.class, int[].class, int.class));
      } catch (ReflectiveOperationException e) {
        throw new LinkageError("StringVectorScan.indexOfCharClass", e);
      }
    }
  }

  /**
   * Reflective access to {@code String.value} and {@code String.coder}. Initialized only when the
   * reflective mode is requested (or by tests), because it needs {@code --add-opens}.
   */
  private static final class Reflective {
    static final byte LATIN1 = 0;
    static final byte UTF16 = 1;

    static final VarHandle VALUE;
    static final VarHandle CODER;
    static final boolean AVAILABLE;

    static {
      VarHandle value;
      VarHandle coder;
      try {
        MethodHandles.Lookup lookup =
            MethodHandles.privateLookupIn(String.class, MethodHandles.lookup());
        value = lookup.findVarHandle(String.class, "value", byte[].class);
        coder = lookup.findVarHandle(String.class, "coder", byte.class);
        if (!layoutMatches(value, coder, "Latin-1 or compact")
            || !layoutMatches(value, coder, "UTF-16 \u0100\ud83d\ude00 string")) {
          value = null;
          coder = null;
        }
      } catch (ReflectiveOperationException | RuntimeException e) {
        value = null;
        coder = null;
      }
      VALUE = value;
      CODER = coder;
      AVAILABLE = value != null;
    }

    /** Checks that {@code text}'s fields hold its chars the way the scans read them. */
    private static boolean layoutMatches(
        VarHandle valueHandle, VarHandle coderHandle, String text) {
      byte[] value = (byte[]) valueHandle.get(text);
      byte coder = (byte) coderHandle.get(text);
      if ((coder != LATIN1 && coder != UTF16) || value.length != text.length() << coder) {
        return false;
      }
      for (int i = 0; i < text.length(); i++) {
        char ch;
        if (coder == LATIN1) {
          ch = (char) (value[i] & 0xFF);
        } else {
          int first = value[2 * i] & 0xFF;
          int second = value[2 * i + 1] & 0xFF;
          ch = (char) (NATIVE_BIG_ENDIAN ? (first << 8) | second : first | (second << 8));
        }
        if (ch != text.charAt(i)) {
          return false;
        }
      }
      return true;
    }
  }
}
