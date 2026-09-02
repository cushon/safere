// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static jdk.incubator.vector.VectorOperators.LSHR;
import static jdk.incubator.vector.VectorOperators.NE;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorSpecies;

/** Stateless SIMD Teddy multi-keyword vector-shuffle scanning kernels using the Vector API. */
final class TeddyVectorScan {
  private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;

  static int indexOfTeddyUtf8(byte[] bytes, int offset, int length, TeddyModel model, int start) {
    int minLen = model.minLength();
    int pos = Math.max(0, start);
    int vectorLen = SPECIES.length();
    int limit = length - vectorLen;
    TeddyModel.Group[] groups = model.groups();
    int numGroups = groups.length;
    boolean is2Byte = model.is2Byte();
    boolean is3Byte = model.is3Byte();

    if (numGroups == 1) {
      TeddyModel.Group group = groups[0];
      ByteVector lutLo0 = ByteVector.fromArray(SPECIES, group.lutLo(), 0);
      ByteVector lutHi0 = ByteVector.fromArray(SPECIES, group.lutHi(), 0);
      ByteVector lutLo1 = is2Byte ? ByteVector.fromArray(SPECIES, group.lutLo1(), 0) : null;
      ByteVector lutHi1 = is2Byte ? ByteVector.fromArray(SPECIES, group.lutHi1(), 0) : null;
      ByteVector lutLo2 = is3Byte ? ByteVector.fromArray(SPECIES, group.lutLo2(), 0) : null;
      ByteVector lutHi2 = is3Byte ? ByteVector.fromArray(SPECIES, group.lutHi2(), 0) : null;
      String[] literals = group.literals();
      int[] buckets = group.literalBuckets();

      for (; pos <= limit; pos += vectorLen) {
        // Stage 1: 2-byte primary SIMD filter (discards ~85% of non-matching blocks)
        ByteVector input0 = ByteVector.fromArray(SPECIES, bytes, offset + pos);
        ByteVector lo0 = input0.and((byte) 0x0F);
        ByteVector hi0 = input0.lanewise(LSHR, 4).and((byte) 0x0F);
        ByteVector match0 = lo0.selectFrom(lutLo0).and(hi0.selectFrom(lutHi0));

        if (is2Byte && pos + 1 <= limit) {
          ByteVector input1 = ByteVector.fromArray(SPECIES, bytes, offset + pos + 1);
          ByteVector lo1 = input1.and((byte) 0x0F);
          ByteVector hi1 = input1.lanewise(LSHR, 4).and((byte) 0x0F);
          ByteVector match1 = lo1.selectFrom(lutLo1).and(hi1.selectFrom(lutHi1));
          match0 = match0.and(match1);
        }

        VectorMask<Byte> matchMask = match0.compare(NE, (byte) 0);
        if (matchMask.anyTrue()) {
          // Stage 2: 3-byte confirmation filter (evaluated only on ~15% candidate blocks)
          if (is3Byte && pos + 2 <= limit) {
            ByteVector input2 = ByteVector.fromArray(SPECIES, bytes, offset + pos + 2);
            ByteVector lo2 = input2.and((byte) 0x0F);
            ByteVector hi2 = input2.lanewise(LSHR, 4).and((byte) 0x0F);
            ByteVector match2 = lo2.selectFrom(lutLo2).and(hi2.selectFrom(lutHi2));
            match0 = match0.and(match2);
            matchMask = match0.compare(NE, (byte) 0);
          }

          if (matchMask.anyTrue()) {
            // Stage 3: Candidate extraction and verification (< 0.1% of blocks)
            long activeLanes = matchMask.toLong();
            while (activeLanes != 0) {
              int bit = Long.numberOfTrailingZeros(activeLanes);
              int candidatePos = pos + bit;
              byte bucketMask = match0.lane(bit);

              for (int litIdx = 0; litIdx < literals.length; litIdx++) {
                int b = buckets[litIdx];
                if ((bucketMask & (1 << b)) != 0) {
                  String lit = literals[litIdx];
                  if (candidatePos + lit.length() <= length
                      && Ascii.regionMatches(bytes, offset + candidatePos, lit, lit.length())) {
                    return candidatePos;
                  }
                }
              }
              activeLanes &= activeLanes - 1;
            }
          }
        }
      }
    } else {
      ByteVector[] lutLo0 = new ByteVector[numGroups];
      ByteVector[] lutHi0 = new ByteVector[numGroups];
      ByteVector[] lutLo1 = is2Byte ? new ByteVector[numGroups] : null;
      ByteVector[] lutHi1 = is2Byte ? new ByteVector[numGroups] : null;
      ByteVector[] lutLo2 = is3Byte ? new ByteVector[numGroups] : null;
      ByteVector[] lutHi2 = is3Byte ? new ByteVector[numGroups] : null;

      for (int g = 0; g < numGroups; g++) {
        lutLo0[g] = ByteVector.fromArray(SPECIES, groups[g].lutLo(), 0);
        lutHi0[g] = ByteVector.fromArray(SPECIES, groups[g].lutHi(), 0);
        if (is2Byte) {
          lutLo1[g] = ByteVector.fromArray(SPECIES, groups[g].lutLo1(), 0);
          lutHi1[g] = ByteVector.fromArray(SPECIES, groups[g].lutHi1(), 0);
        }
        if (is3Byte) {
          lutLo2[g] = ByteVector.fromArray(SPECIES, groups[g].lutLo2(), 0);
          lutHi2[g] = ByteVector.fromArray(SPECIES, groups[g].lutHi2(), 0);
        }
      }

      ByteVector[] matchVectors = new ByteVector[numGroups];

      for (; pos <= limit; pos += vectorLen) {
        ByteVector input0 = ByteVector.fromArray(SPECIES, bytes, offset + pos);
        ByteVector lo0 = input0.and((byte) 0x0F);
        ByteVector hi0 = input0.lanewise(LSHR, 4).and((byte) 0x0F);

        boolean has2 = is2Byte && pos + 1 <= limit;
        ByteVector lo1 = null;
        ByteVector hi1 = null;
        if (has2) {
          ByteVector input1 = ByteVector.fromArray(SPECIES, bytes, offset + pos + 1);
          lo1 = input1.and((byte) 0x0F);
          hi1 = input1.lanewise(LSHR, 4).and((byte) 0x0F);
        }

        boolean has3 = is3Byte && pos + 2 <= limit;
        ByteVector lo2 = null;
        ByteVector hi2 = null;
        if (has3) {
          ByteVector input2 = ByteVector.fromArray(SPECIES, bytes, offset + pos + 2);
          lo2 = input2.and((byte) 0x0F);
          hi2 = input2.lanewise(LSHR, 4).and((byte) 0x0F);
        }

        ByteVector combined = null;
        for (int g = 0; g < numGroups; g++) {
          ByteVector mg = lo0.selectFrom(lutLo0[g]).and(hi0.selectFrom(lutHi0[g]));
          if (has2) {
            mg = mg.and(lo1.selectFrom(lutLo1[g]).and(hi1.selectFrom(lutHi1[g])));
          }
          if (has3) {
            mg = mg.and(lo2.selectFrom(lutLo2[g]).and(hi2.selectFrom(lutHi2[g])));
          }
          matchVectors[g] = mg;
          combined = (combined == null) ? mg : combined.or(mg);
        }

        VectorMask<Byte> matchMask = combined.compare(NE, (byte) 0);
        if (matchMask.anyTrue()) {
          long activeLanes = matchMask.toLong();
          while (activeLanes != 0) {
            int bit = Long.numberOfTrailingZeros(activeLanes);
            int candidatePos = pos + bit;
            for (int g = 0; g < numGroups; g++) {
              byte bucketMask = matchVectors[g].lane(bit);
              if (bucketMask != 0) {
                TeddyModel.Group group = groups[g];
                String[] literals = group.literals();
                int[] buckets = group.literalBuckets();
                for (int litIdx = 0; litIdx < literals.length; litIdx++) {
                  int b = buckets[litIdx];
                  if ((bucketMask & (1 << b)) != 0) {
                    String lit = literals[litIdx];
                    if (candidatePos + lit.length() <= length
                        && Ascii.regionMatches(bytes, offset + candidatePos, lit, lit.length())) {
                      return candidatePos;
                    }
                  }
                }
              }
            }
            activeLanes &= activeLanes - 1;
          }
        }
      }
    }

    int scalarLimit = length - minLen;
    String[] allLiterals = model.literals();
    for (; pos <= scalarLimit; pos++) {
      for (String lit : allLiterals) {
        if (pos + lit.length() <= length
            && Ascii.regionMatches(bytes, offset + pos, lit, lit.length())) {
          return pos;
        }
      }
    }
    return -1;
  }

  private TeddyVectorScan() {}
}
