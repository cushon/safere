// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("implementation test uses package-private Vector API internals")
class VectorSpeciesSimulationTest {

  private static final boolean VECTOR_AVAILABLE = isVectorApiAvailable();
  private static final List<SpeciesHandle> BYTE_SPECIES =
      VECTOR_AVAILABLE ? getSpeciesList("jdk.incubator.vector.ByteVector") : List.of();
  private static final List<SpeciesHandle> SHORT_SPECIES =
      VECTOR_AVAILABLE ? getSpeciesList("jdk.incubator.vector.ShortVector") : List.of();
  private static final Method BYTE_INDEX_OF_ASCII_CLASS =
      VECTOR_AVAILABLE ? resolveByteIndexOfAsciiClass() : null;
  private static final Method BYTE_INDEX_OF_IGNORE_CASE =
      VECTOR_AVAILABLE ? resolveByteIndexOfIgnoreCase() : null;
  private static final Method SHORT_INDEX_OF_CHAR_CLASS =
      VECTOR_AVAILABLE ? resolveShortIndexOfCharClass() : null;

  private record SpeciesHandle(Object value) {}

  private static boolean isVectorApiAvailable() {
    try {
      Class.forName("jdk.incubator.vector.ByteVector");
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  private static List<SpeciesHandle> getSpeciesList(String vectorClassName) {
    try {
      Class<?> vectorClass = Class.forName(vectorClassName);
      return List.of(
          new SpeciesHandle(vectorClass.getField("SPECIES_64").get(null)),
          new SpeciesHandle(vectorClass.getField("SPECIES_128").get(null)),
          new SpeciesHandle(vectorClass.getField("SPECIES_256").get(null)),
          new SpeciesHandle(vectorClass.getField("SPECIES_512").get(null)));
    } catch (ReflectiveOperationException | LinkageError e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static Method resolveByteIndexOfAsciiClass() {
    try {
      Class<?> speciesClass = Class.forName("jdk.incubator.vector.VectorSpecies");
      Method m =
          ByteVectorScan.class.getDeclaredMethod(
              "indexOfAsciiClass",
              speciesClass,
              byte[].class,
              int.class,
              int.class,
              int[].class,
              int.class);
      m.setAccessible(true);
      return m;
    } catch (ReflectiveOperationException | LinkageError e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static Method resolveByteIndexOfIgnoreCase() {
    try {
      Class<?> speciesClass = Class.forName("jdk.incubator.vector.VectorSpecies");
      Method m =
          ByteVectorScan.class.getDeclaredMethod(
              "indexOfIgnoreCase",
              speciesClass,
              byte[].class,
              int.class,
              int.class,
              String.class,
              int.class,
              int.class,
              byte.class,
              byte.class,
              int.class);
      m.setAccessible(true);
      return m;
    } catch (ReflectiveOperationException | LinkageError e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static Method resolveShortIndexOfCharClass() {
    try {
      Class<?> speciesClass = Class.forName("jdk.incubator.vector.VectorSpecies");
      Method m =
          ShortVectorScan.class.getDeclaredMethod(
              "indexOfCharClass",
              speciesClass,
              char[].class,
              int.class,
              int.class,
              int[].class,
              int.class);
      m.setAccessible(true);
      return m;
    } catch (ReflectiveOperationException | LinkageError e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static int invokeByteIndexOfAsciiClass(
      SpeciesHandle species, byte[] bytes, int offset, int length, int[] ranges, int start) {
    try {
      return (int)
          BYTE_INDEX_OF_ASCII_CLASS.invoke(
              null, species.value(), bytes, offset, length, ranges, start);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static int invokeByteIndexOfIgnoreCase(
      SpeciesHandle species,
      byte[] bytes,
      int offset,
      int length,
      String prefix,
      int prefixLen,
      int anchorOffset,
      byte low,
      byte high,
      int start) {
    try {
      return (int)
          BYTE_INDEX_OF_IGNORE_CASE.invoke(
              null,
              species.value(),
              bytes,
              offset,
              length,
              prefix,
              prefixLen,
              anchorOffset,
              low,
              high,
              start);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static int invokeShortIndexOfCharClass(
      SpeciesHandle species, char[] chars, int offset, int length, int[] ranges, int start) {
    try {
      return (int)
          SHORT_INDEX_OF_CHAR_CLASS.invoke(
              null, species.value(), chars, offset, length, ranges, start);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void asciiClassMatchingAcrossAllByteVectorSpecies() {
    assumeTrue(VECTOR_AVAILABLE, "Vector API not available on module path");
    int[] ranges = {'0', '9', 'a', 'z'};
    for (SpeciesHandle species : BYTE_SPECIES) {
      for (int len = 0; len <= 256; len++) {
        byte[] absent = "A".repeat(len).getBytes(UTF_8);
        for (int start = 0; start <= len; start++) {
          assertThat(invokeByteIndexOfAsciiClass(species, absent, 0, len, ranges, start))
              .as("Absent for species %s, len %d, start %d", species, len, start)
              .isEqualTo(-1);
        }

        if (len > 0) {
          // Match at start
          byte[] matchStart = absent.clone();
          matchStart[0] = '5';
          assertThat(invokeByteIndexOfAsciiClass(species, matchStart, 0, len, ranges, 0))
              .as("Match start for species %s, len %d", species, len)
              .isEqualTo(0);

          // Match in middle
          int mid = len / 2;
          byte[] matchMid = absent.clone();
          matchMid[mid] = 'b';
          assertThat(invokeByteIndexOfAsciiClass(species, matchMid, 0, len, ranges, 0))
              .as("Match mid for species %s, len %d, mid %d", species, len, mid)
              .isEqualTo(mid);

          // Match at end
          byte[] matchEnd = absent.clone();
          matchEnd[len - 1] = 'z';
          assertThat(invokeByteIndexOfAsciiClass(species, matchEnd, 0, len, ranges, 0))
              .as("Match end for species %s, len %d", species, len)
              .isEqualTo(len - 1);
        }
      }
    }
  }

  @Test
  void ignoreCaseMatchingAcrossAllByteVectorSpecies() {
    assumeTrue(VECTOR_AVAILABLE, "Vector API not available on module path");
    String prefix = "needle";
    int prefixLen = prefix.length();
    int anchorOffset = 0;
    byte low = 'n';
    byte high = 'N';

    for (SpeciesHandle species : BYTE_SPECIES) {
      for (int pad = 0; pad <= 128; pad++) {
        String base = "x".repeat(pad) + "NeEdLe" + "y".repeat(64);
        byte[] bytes = base.getBytes(UTF_8);

        assertThat(
                invokeByteIndexOfIgnoreCase(
                    species, bytes, 0, bytes.length, prefix, prefixLen, anchorOffset, low, high, 0))
            .as("IgnoreCase match for species %s, pad %d", species, pad)
            .isEqualTo(pad);

        byte[] absent = "x".repeat(pad + 64).getBytes(UTF_8);
        assertThat(
                invokeByteIndexOfIgnoreCase(
                    species,
                    absent,
                    0,
                    absent.length,
                    prefix,
                    prefixLen,
                    anchorOffset,
                    low,
                    high,
                    0))
            .as("IgnoreCase absent for species %s, pad %d", species, pad)
            .isEqualTo(-1);
      }
    }
  }

  @Test
  void charClassMatchingAcrossAllShortVectorSpecies() {
    assumeTrue(VECTOR_AVAILABLE, "Vector API not available on module path");
    int[] ranges = {'0', '9', 'A', 'Z'};
    for (SpeciesHandle species : SHORT_SPECIES) {
      for (int len = 0; len <= 128; len++) {
        char[] chars = "a".repeat(len).toCharArray();
        for (int start = 0; start <= len; start++) {
          assertThat(invokeShortIndexOfCharClass(species, chars, 0, len, ranges, start))
              .as("Short absent for species %s, len %d, start %d", species, len, start)
              .isEqualTo(-1);
        }

        if (len > 0) {
          char[] matchEnd = chars.clone();
          matchEnd[len - 1] = '9';
          assertThat(invokeShortIndexOfCharClass(species, matchEnd, 0, len, ranges, 0))
              .as("Short end match for species %s, len %d", species, len)
              .isEqualTo(len - 1);
        }
      }
    }
  }
}
