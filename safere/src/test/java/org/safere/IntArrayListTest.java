// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class IntArrayListTest {

  @Test
  void basicOperations() {
    IntArrayList list = new IntArrayList(4);
    assertThat(list.isEmpty()).isTrue();
    assertThat(list.size()).isEqualTo(0);

    list.add(10);
    list.add(20);
    list.add(30);

    assertThat(list.isEmpty()).isFalse();
    assertThat(list.size()).isEqualTo(3);
    assertThat(list.get(0)).isEqualTo(10);
    assertThat(list.get(1)).isEqualTo(20);
    assertThat(list.get(2)).isEqualTo(30);

    assertThat(list.removeLast()).isEqualTo(30);
    assertThat(list.size()).isEqualTo(2);

    assertThat(list.toArray()).containsExactly(10, 20);

    list.clear();
    assertThat(list.isEmpty()).isTrue();
    assertThat(list.size()).isEqualTo(0);
    assertThat(list.toArray()).isEmpty();
  }

  @Test
  void growthBeyondCapacity() {
    IntArrayList list = new IntArrayList(2);
    for (int i = 0; i < 100; i++) {
      list.add(i);
    }
    assertThat(list.size()).isEqualTo(100);
    for (int i = 0; i < 100; i++) {
      assertThat(list.get(i)).isEqualTo(i);
    }
  }

  @Test
  void toSortedUniqueArray() {
    IntArrayList list = new IntArrayList();
    assertThat(list.toSortedUniqueArray()).isEmpty();

    list.add(42);
    assertThat(list.toSortedUniqueArray()).containsExactly(42);

    list.clear();
    list.add(5);
    list.add(1);
    list.add(3);
    list.add(1);
    list.add(5);
    list.add(2);
    list.add(3);
    list.add(5);

    assertThat(list.toSortedUniqueArray()).containsExactly(1, 2, 3, 5);
  }
}
