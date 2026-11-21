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

  private StringVectorScan() {}

  /** Returns the mode to use for {@code requested}, or the default when it is not available. */
  static Mode select(Mode requested) {
    if (requested == Mode.CHUNKED) {
      return Mode.CHUNKED;
    }
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
      case CHUNKED -> true;
    };
  }

  /**
   * Returns the first index at or after {@code start} of a code unit in {@code ranges}, {@code -1}
   * when there is none, or {@link VectorScanProvider#UNSUPPORTED}. See {@link
   * StringVectorLoads#indexOfCharClass}. Tests call this directly to force a mode.
   */
  static int indexOfCharClass(
      Mode mode, String text, int[] ranges, int start, StringInputScanner chunkOwner) {
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
      case CHUNKED ->
          chunkOwner == null
              ? VectorScanProvider.UNSUPPORTED
              : indexOfCharClassChunked(text, ranges, from, chunkOwner.chunkBuffer());
    };
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

  private static int indexOfCharClassChunked(String text, int[] ranges, int start, char[] buffer) {
    int length = text.length();
    // Start with a few vectors and double up to the buffer length, so that a match near the start
    // does not pay for copying a whole buffer.
    int chunk = Math.min(4 * LANES, buffer.length);
    int position = start;
    while (true) {
      int count = Math.min(chunk, length - position);
      count -= count % LANES;
      if (count == 0) {
        break;
      }
      text.getChars(position, position + count, buffer, 0);
      for (int i = 0; i < count; i += LANES) {
        VectorMask<Short> matches =
            ShortVectorScan.matches(ShortVector.fromCharArray(SPECIES, buffer, i), ranges);
        if (matches.anyTrue()) {
          return position + i + matches.firstTrue();
        }
      }
      position += count;
      chunk = Math.min(chunk << 1, buffer.length);
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
      VarHandle value = null;
      VarHandle coder = null;
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
