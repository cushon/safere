// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("implementation test uses package-private Teddy and Vector provider APIs")
class TeddyModelTest {

  @Test
  void compilationFollowsVectorProviderAvailability() {
    TeddyModel model = TeddyModel.compileForSelectedProvider(new String[] {"INFO", "WARN"});

    if (!VectorScanProviders.teddyProviderAvailable()) {
      assertThat(model).isNull();
    } else {
      assertThat(model).isNotNull();
      assertThat(VectorScanProviders.providerForLength(64)).isNull();
      assertThat(VectorScanProviders.providerForTeddyLength(64)).isNull();
      assertThat(VectorScanProviders.providerForTeddyLength(256)).isNull();
      assertThat(VectorScanProviders.providerForLength(1024)).isNotNull();
      assertThat(VectorScanProviders.providerForTeddyLength(1024)).isNotNull();
    }
  }

  @Test
  void multiGroupCompilationPartitionsCorrectly() {
    String[] lits36 = new String[36];
    for (int i = 0; i < 36; i++) {
      lits36[i] = String.format("%c%c_%02d", 'a' + (i % 26), 'a' + ((i / 26) % 26), i);
    }
    TeddyModel model36 = TeddyModel.compile(lits36, 64);
    assertThat(model36).isNotNull();
    assertThat(model36.numGroups()).isEqualTo(2);
    assertThat(model36.groups()[0].literals()).hasSize(32);
    assertThat(model36.groups()[1].literals()).hasSize(4);

    String[] lits72 = new String[72];
    for (int i = 0; i < 72; i++) {
      lits72[i] = String.format("%c%c_%02d", 'a' + (i % 26), 'a' + ((i / 26) % 26), i);
    }
    TeddyModel model72 = TeddyModel.compile(lits72, 64);
    assertThat(model72).isNotNull();
    assertThat(model72.numGroups()).isEqualTo(3);

    String[] lits110 = new String[110];
    for (int i = 0; i < 110; i++) {
      lits110[i] = String.format("%c%c_%02d", 'a' + (i % 26), 'a' + ((i / 26) % 26), i);
    }
    TeddyModel model110 = TeddyModel.compile(lits110, 64);
    assertThat(model110).isNotNull();
    assertThat(model110.numGroups()).isEqualTo(4);

    String[] lits129 = new String[129];
    for (int i = 0; i < 129; i++) {
      lits129[i] = String.format("%c%c_%03d", 'a' + (i % 26), 'a' + ((i / 26) % 26), i);
    }
    assertThat(TeddyModel.compile(lits129, 64)).isNull();
  }

  @Test
  void prefixDiversityRejectsClusteredSets() {
    // 36 patterns all sharing the same prefix "Accept-*"
    String[] clustered36 = new String[36];
    for (int i = 0; i < 36; i++) {
      clustered36[i] = String.format("Accept-%02d", i);
    }
    assertThat(TeddyModel.compile(clustered36, 64)).isNull();

    // 110 patterns clustered across 3 MIME prefixes
    String[] clustered110 = new String[110];
    for (int i = 0; i < 110; i++) {
      String prefix = i < 50 ? "application/" : (i < 80 ? "image/" : "text/");
      clustered110[i] = prefix + "type_" + i;
    }
    assertThat(TeddyModel.compile(clustered110, 64)).isNull();
  }

  @Test
  void multiGroupTeddyVectorScanMatchesAcrossGroups() {
    if (!VectorScanProviders.teddyProviderAvailable()) {
      return;
    }
    String[] lits = new String[100];
    for (int i = 0; i < 100; i++) {
      lits[i] = String.format("%c%c_%03d", 'a' + (i % 26), 'a' + ((i / 26) % 26), i);
    }
    TeddyModel model = TeddyModel.compile(lits, 64);
    assertThat(model).isNotNull();
    assertThat(model.numGroups()).isEqualTo(4);

    // Test match in Group 0 (index 5)
    String target0 = lits[5];
    String text0 = "padding_noise ".repeat(100) + target0 + " trailing_padding".repeat(100);
    byte[] bytes0 = text0.getBytes(StandardCharsets.UTF_8);
    int expected0 = text0.indexOf(target0);
    int found0 = TeddyVectorScan.indexOfTeddyUtf8(bytes0, 0, bytes0.length, model, 0);
    assertThat(found0).isEqualTo(expected0);

    // Test match in Group 1 (index 40)
    String target1 = lits[40];
    String text1 = "padding_noise ".repeat(100) + target1 + " trailing_padding".repeat(100);
    byte[] bytes1 = text1.getBytes(StandardCharsets.UTF_8);
    int expected1 = text1.indexOf(target1);
    int found1 = TeddyVectorScan.indexOfTeddyUtf8(bytes1, 0, bytes1.length, model, 0);
    assertThat(found1).isEqualTo(expected1);

    // Test match in Group 3 (index 98)
    String target3 = lits[98];
    String text3 = "padding_noise ".repeat(100) + target3 + " trailing_padding".repeat(100);
    byte[] bytes3 = text3.getBytes(StandardCharsets.UTF_8);
    int expected3 = text3.indexOf(target3);
    int found3 = TeddyVectorScan.indexOfTeddyUtf8(bytes3, 0, bytes3.length, model, 0);
    assertThat(found3).isEqualTo(expected3);

    // Test no match
    String textNone = "padding_noise ".repeat(200);
    byte[] bytesNone = textNone.getBytes(StandardCharsets.UTF_8);
    int foundNone = TeddyVectorScan.indexOfTeddyUtf8(bytesNone, 0, bytesNone.length, model, 0);
    assertThat(foundNone).isEqualTo(-1);
  }
}
