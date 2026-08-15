// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Portions derived from RE2/J (https://github.com/google/re2j),
// Copyright (c) 2009 The Go Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.util.Arrays;

/**
 * A resizable array of primitive {@code int} values to avoid boxing and object allocation
 * overhead.
 */
final class IntArrayList {

  private int[] data;
  private int size;

  IntArrayList() {
    this(16);
  }

  IntArrayList(int capacity) {
    data = new int[Math.max(4, capacity)];
  }

  void add(int value) {
    if (size == data.length) {
      data = Arrays.copyOf(data, data.length * 2);
    }
    data[size++] = value;
  }

  int size() {
    return size;
  }

  int get(int index) {
    return data[index];
  }

  void clear() {
    size = 0;
  }

  boolean isEmpty() {
    return size == 0;
  }

  int removeLast() {
    return data[--size];
  }

  int[] toArray() {
    return Arrays.copyOf(data, size);
  }

  int[] toSortedUniqueArray() {
    if (size == 0) {
      return new int[0];
    }
    Arrays.sort(data, 0, size);
    int unique = 1;
    for (int i = 1; i < size; i++) {
      if (data[i] != data[unique - 1]) {
        data[unique++] = data[i];
      }
    }
    return Arrays.copyOf(data, unique);
  }
}
