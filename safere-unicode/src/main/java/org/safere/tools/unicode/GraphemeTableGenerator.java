// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.tools.unicode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds grapheme ranges from the checked-in Unicode Character Database, without JDK data. */
public final class GraphemeTableGenerator {
  private static final String VERSION = "17.0.0";
  private static final List<String> TABLE_NAMES =
      List.of(
          "GCB_CONTROL",
          "GCB_EXTEND",
          "GCB_PREPEND",
          "GCB_SPACINGMARK",
          "GCB_L",
          "GCB_V",
          "GCB_T",
          "GCB_LV",
          "GCB_LVT",
          "GCB_ZWJ",
          "GCB_REGIONAL_INDICATOR",
          "INCB_LINKER",
          "INCB_CONSONANT",
          "INCB_EXTEND",
          "EXTENDED_PICTOGRAPHIC");

  private GraphemeTableGenerator() {}

  static Map<String, int[][]> generate(Path data) throws IOException {
    Map<String, List<Range>> tables = new LinkedHashMap<>();
    for (String name : TABLE_NAMES) {
      tables.put(name, new ArrayList<>());
    }
    read(
        data.resolve("GraphemeBreakProperty.txt"),
        "# GraphemeBreakProperty-" + VERSION + ".txt",
        tables);
    read(
        data.resolve("DerivedCoreProperties.txt"),
        "# DerivedCoreProperties-" + VERSION + ".txt",
        tables);
    read(data.resolve("emoji-data.txt"), "# Version: 17.0", tables);
    Map<String, int[][]> result = new LinkedHashMap<>();
    for (Map.Entry<String, List<Range>> entry : tables.entrySet()) {
      List<Range> ranges = merge(entry.getValue());
      if (ranges.isEmpty()) {
        throw new IllegalArgumentException("Missing Unicode property " + entry.getKey());
      }
      result.put(
          entry.getKey(),
          ranges.stream()
              .map(range -> new int[] {range.first(), range.last()})
              .toArray(int[][]::new));
    }
    return result;
  }

  private static void read(Path path, String versionHeader, Map<String, List<Range>> tables)
      throws IOException {
    List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
    if (!lines.contains(versionHeader)) {
      throw new IllegalArgumentException("Wrong Unicode version in " + path);
    }
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i).split("#", 2)[0].trim();
      if (line.isEmpty()) {
        continue;
      }
      String[] fields = line.split(";", -1);
      if (fields.length < 2) {
        throw new IllegalArgumentException(path + ":" + (i + 1) + ": missing property");
      }
      String property = fields[1].trim();
      String name;
      switch (path.getFileName().toString()) {
        case "GraphemeBreakProperty.txt" -> {
          name =
              switch (property) {
                case "CR", "LF", "Control" -> "GCB_CONTROL";
                default -> "GCB_" + property.toUpperCase(Locale.ROOT);
              };
          if (!tables.containsKey(name)) {
            throw new IllegalArgumentException("Unknown grapheme property: " + property);
          }
        }
        case "DerivedCoreProperties.txt" -> {
          if (!property.equals("InCB")) {
            continue;
          }
          if (fields.length != 3) {
            throw new IllegalArgumentException("Missing InCB value at " + path + ":" + (i + 1));
          }
          name = "INCB_" + fields[2].trim().toUpperCase(Locale.ROOT);
          if (!tables.containsKey(name)) {
            throw new IllegalArgumentException("Unknown InCB value: " + fields[2]);
          }
        }
        case "emoji-data.txt" -> {
          if (!property.equals("Extended_Pictographic")) {
            continue;
          }
          name = "EXTENDED_PICTOGRAPHIC";
        }
        default -> throw new IllegalArgumentException("Unknown Unicode file: " + path);
      }
      String[] bounds = fields[0].trim().split("\\.\\.", -1);
      if (bounds.length > 2) {
        throw new IllegalArgumentException("Invalid range at " + path + ":" + (i + 1));
      }
      int first = Integer.parseInt(bounds[0], 16);
      int last = Integer.parseInt(bounds[bounds.length - 1], 16);
      if (first < 0 || last < first || last > 0x10FFFF) {
        throw new IllegalArgumentException("Invalid range at " + path + ":" + (i + 1));
      }
      tables.get(name).add(new Range(first, last));
    }
  }

  private static List<Range> merge(List<Range> ranges) {
    ranges.sort(Comparator.comparingInt(Range::first));
    List<Range> result = new ArrayList<>();
    for (Range range : ranges) {
      if (!result.isEmpty()) {
        Range previous = result.getLast();
        if (range.first() <= previous.last()) {
          throw new IllegalArgumentException(
              "Overlapping Unicode ranges: " + previous + " and " + range);
        }
        if (range.first() == previous.last() + 1) {
          result.set(result.size() - 1, new Range(previous.first(), range.last()));
          continue;
        }
      }
      result.add(range);
    }
    return result;
  }

  private record Range(int first, int last) {}
}
