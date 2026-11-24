// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Compiles one pattern in a fresh JVM for compiler initialization tests. */
public final class CompileInitializationProbe {
  static final String COMPILED = "SafeRE pattern compiled";

  private CompileInitializationProbe() {}

  /** Compiles the Base64-encoded UTF-8 pattern supplied as the first command-line argument. */
  public static void main(String[] args) throws Exception {
    String regex = new String(Base64.getDecoder().decode(args[0]), StandardCharsets.UTF_8);
    switch (args.length > 1 ? args[1] : "compile") {
      case "concurrent" -> compileAndSearchConcurrently(regex);
      case "search" -> {
        Pattern pattern = Pattern.compile(regex);
        System.out.println(COMPILED);
        checkFirstCaptures(pattern);
      }
      case "compile" -> Pattern.compile(regex);
      default -> throw new IllegalArgumentException(args[1]);
    }
  }

  private static void compileAndSearchConcurrently(String regex) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(8)) {
      List<Future<Void>> results = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        results.add(
            executor.submit(() -> {
              start.await();
              checkFirstCaptures(Pattern.compile(regex));
              return null;
            }));
      }
      start.countDown();
      for (Future<Void> result : results) {
        result.get();
      }
    }
  }

  private static void checkFirstCaptures(Pattern pattern) {
    Matcher matcher = pattern.matcher("aé");
    Utf8Input input = Utf8Input.validated("aé".getBytes(StandardCharsets.UTF_8));
    Utf8Matcher utf8Matcher = pattern.matcher(input);
    if (!matcher.find()
        || matcher.start(1) != 0
        || matcher.end(1) != 1
        || !utf8Matcher.find()
        || utf8Matcher.start(1) != 0
        || utf8Matcher.end(1) != 1) {
      throw new AssertionError("first search changed capture participation");
    }
  }
}
