// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.tools.unicode;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntPredicate;

/** Generates checked-in Unicode tables from the JDK {@link Character} implementation. */
public final class UnicodeTableGenerator {
  private static final int MAX_CODE_POINT = Character.MAX_CODE_POINT;
  private static final String DEFAULT_OUTPUT =
      "safere/src/main/java/org/safere/UnicodeGeneratedTables.java";
  private static final String JDK_REGEX_INTERNALS = "jdk.internal.util.regex";

  private static final String[] CATEGORY_ABBREVS = {
    "Cn", "Lu", "Ll", "Lt", "Lm", "Lo", "Mn", "Me", "Mc", "Nd", "Nl", "No", "Zs", "Zl", "Zp", "Cc",
    "Cf", null, "Co", "Cs", "Pd", "Ps", "Pe", "Pc", "Po", "Sm", "Sc", "Sk", "So", "Pi", "Pf"
  };

  private static final int[][] MAJOR_CATEGORY_TYPES = {
    {1, 2, 3, 4, 5},
    {6, 7, 8},
    {9, 10, 11},
    {20, 21, 22, 23, 24, 29, 30},
    {25, 26, 27, 28},
    {12, 13, 14},
    {0, 15, 16, 18, 19},
  };

  private static final String[] MAJOR_CATEGORY_NAMES = {"L", "M", "N", "P", "S", "Z", "C"};

  private UnicodeTableGenerator() {}

  public static void main(String[] args) throws IOException {
    Path output = args.length >= 1 ? Path.of(args[0]) : Path.of(DEFAULT_OUTPUT);
    if (args.length > 1) {
      throw new IllegalArgumentException("Usage: UnicodeTableGenerator [output-file]");
    }

    GeneratedTables tables = buildTables();
    Files.createDirectories(output.getParent());
    try (PrintWriter out =
        new PrintWriter(Files.newBufferedWriter(output, StandardCharsets.UTF_8))) {
      writeJava(out, tables);
    }
  }

  private static GeneratedTables buildTables() {
    int[][][] categoryTables = buildCategoryTables();
    Map<String, int[][]> categories = new LinkedHashMap<>();
    for (int i = 0; i < CATEGORY_ABBREVS.length; i++) {
      if (CATEGORY_ABBREVS[i] != null && categoryTables[i].length > 0) {
        categories.put(CATEGORY_ABBREVS[i], categoryTables[i]);
      }
    }
    for (int i = 0; i < MAJOR_CATEGORY_NAMES.length; i++) {
      categories.put(
          MAJOR_CATEGORY_NAMES[i], mergeSubcategories(categoryTables, MAJOR_CATEGORY_TYPES[i]));
    }

    return new GeneratedTables(
        categories,
        buildScriptTables(),
        buildBlockTables(),
        buildBinaryPropertyTables(),
        buildGraphemeTables());
  }

  private static int[][][] buildCategoryTables() {
    RangeBuilder[] builders = new RangeBuilder[CATEGORY_ABBREVS.length];
    for (int i = 0; i < builders.length; i++) {
      builders[i] = new RangeBuilder();
    }

    for (int cp = 0; cp <= MAX_CODE_POINT; cp++) {
      int type = Character.getType(cp);
      if (type >= 0 && type < builders.length) {
        builders[type].add(cp);
      }
    }

    int[][][] tables = new int[builders.length][][];
    for (int i = 0; i < builders.length; i++) {
      tables[i] = builders[i].build();
    }
    return tables;
  }

  private static Map<String, int[][]> buildScriptTables() {
    Map<Character.UnicodeScript, RangeBuilder> builders =
        new EnumMap<>(Character.UnicodeScript.class);
    for (int cp = 0; cp <= MAX_CODE_POINT; cp++) {
      Character.UnicodeScript script = Character.UnicodeScript.of(cp);
      builders.computeIfAbsent(script, unused -> new RangeBuilder()).add(cp);
    }

    Map<String, int[][]> tables = new LinkedHashMap<>();
    for (Character.UnicodeScript script : Character.UnicodeScript.values()) {
      RangeBuilder builder = builders.get(script);
      if (builder != null) {
        tables.put(scriptName(script), builder.build());
      }
    }
    return tables;
  }

  private static Map<String, int[][]> buildBlockTables() {
    Map<Character.UnicodeBlock, RangeBuilder> builders = new LinkedHashMap<>();
    for (int cp = 0; cp <= MAX_CODE_POINT; cp++) {
      Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
      if (block != null) {
        builders.computeIfAbsent(block, unused -> new RangeBuilder()).add(cp);
      }
    }

    Map<String, int[][]> tables = new LinkedHashMap<>();
    List<Character.UnicodeBlock> blocks = new ArrayList<>(builders.keySet());
    blocks.sort((a, b) -> blockName(a).compareTo(blockName(b)));
    for (Character.UnicodeBlock block : blocks) {
      tables.put(blockName(block), builders.get(block).build());
    }
    return tables;
  }

  private static Map<String, int[][]> buildBinaryPropertyTables() {
    Map<String, IntPredicate> predicates = new LinkedHashMap<>();
    predicates.put("Alphabetic", Character::isAlphabetic);
    predicates.put("Ideographic", Character::isIdeographic);
    predicates.put("Letter", Character::isLetter);
    predicates.put("Lowercase", Character::isLowerCase);
    predicates.put("Uppercase", Character::isUpperCase);
    predicates.put("Titlecase", Character::isTitleCase);
    predicates.put("Punctuation", UnicodeTableGenerator::isPunctuation);
    predicates.put("Control", cp -> Character.getType(cp) == Character.CONTROL);
    predicates.put("White_Space", cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp));
    predicates.put("Digit", Character::isDigit);
    predicates.put("Hex_Digit", UnicodeTableGenerator::isHexDigit);
    predicates.put("Join_Control", cp -> cp == 0x200C || cp == 0x200D);
    predicates.put("Noncharacter_Code_Point", UnicodeTableGenerator::isNoncharacterCodePoint);
    predicates.put("Assigned", Character::isDefined);
    predicates.put("Emoji", Character::isEmoji);
    predicates.put("Emoji_Presentation", Character::isEmojiPresentation);
    predicates.put("Emoji_Modifier", Character::isEmojiModifier);
    predicates.put("Emoji_Modifier_Base", Character::isEmojiModifierBase);
    predicates.put("Emoji_Component", Character::isEmojiComponent);
    predicates.put("Extended_Pictographic", Character::isExtendedPictographic);

    Map<String, int[][]> tables = new LinkedHashMap<>();
    for (Map.Entry<String, IntPredicate> entry : predicates.entrySet()) {
      tables.put(entry.getKey(), buildRanges(entry.getValue()));
    }
    return tables;
  }

  /**
   * Builds the UAX #29 Grapheme_Cluster_Break and Indic_Conjunct_Break classes used by {@code \X}
   * and {@code \b{g}}.
   *
   * <p>{@link Character} does not expose these properties, so read them from the JDK's own grapheme
   * classifier. That keeps SafeRE's grapheme segmentation on the same Unicode version as the other
   * generated tables, and in step with the generator JDK's {@code java.util.regex}. Requires {@code
   * --add-opens java.base/jdk.internal.util.regex=ALL-UNNAMED}; see generate-unicode-tables.sh.
   *
   * <p>Unassigned code points are omitted because SafeRE classifies them itself (see
   * INTENTIONAL_DIVERGENCES.md). Surrogates are omitted because SafeRE handles unpaired surrogates
   * separately.
   */
  private static Map<String, int[][]> buildGraphemeTables() {
    Class<?> grapheme = jdkRegexInternal("Grapheme");
    Method getType = accessibleMethod(grapheme, "getType", int.class);
    Class<?> indicConjunctBreak = jdkRegexInternal("IndicConjunctBreak");
    Method isLinker = accessibleMethod(indicConjunctBreak, "isLinker", int.class);
    Method isConsonant = accessibleMethod(indicConjunctBreak, "isConsonant", int.class);
    Method isExtend = accessibleMethod(indicConjunctBreak, "isExtend", int.class);

    int cr = intConstant(grapheme, "CR");
    int lf = intConstant(grapheme, "LF");
    int control = intConstant(grapheme, "CONTROL");
    Map<String, Integer> graphemeTypes = new LinkedHashMap<>();
    graphemeTypes.put("Extend", intConstant(grapheme, "EXTEND"));
    graphemeTypes.put("Prepend", intConstant(grapheme, "PREPEND"));
    graphemeTypes.put("SpacingMark", intConstant(grapheme, "SPACINGMARK"));
    graphemeTypes.put("L", intConstant(grapheme, "L"));
    graphemeTypes.put("V", intConstant(grapheme, "V"));
    graphemeTypes.put("T", intConstant(grapheme, "T"));
    graphemeTypes.put("LV", intConstant(grapheme, "LV"));
    graphemeTypes.put("LVT", intConstant(grapheme, "LVT"));

    Map<String, int[][]> tables = new LinkedHashMap<>();
    tables.put(
        "Control",
        buildAssignedRanges(
            cp -> {
              int type = invokeInt(getType, cp);
              return type == cr || type == lf || type == control;
            }));
    for (Map.Entry<String, Integer> entry : graphemeTypes.entrySet()) {
      int expected = entry.getValue();
      tables.put(entry.getKey(), buildAssignedRanges(cp -> invokeInt(getType, cp) == expected));
    }
    tables.put("InCB_Linker", buildAssignedRanges(cp -> invokeBoolean(isLinker, cp)));
    tables.put("InCB_Consonant", buildAssignedRanges(cp -> invokeBoolean(isConsonant, cp)));
    tables.put("InCB_Extend", buildAssignedRanges(cp -> invokeBoolean(isExtend, cp)));
    return tables;
  }

  private static int[][] buildAssignedRanges(IntPredicate predicate) {
    return buildRanges(
        cp -> {
          int type = Character.getType(cp);
          return type != Character.UNASSIGNED && type != Character.SURROGATE && predicate.test(cp);
        });
  }

  private static Class<?> jdkRegexInternal(String simpleName) {
    try {
      return Class.forName(JDK_REGEX_INTERNALS + "." + simpleName);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(
          "The generator JDK has no " + JDK_REGEX_INTERNALS + "." + simpleName, e);
    }
  }

  private static Method accessibleMethod(Class<?> owner, String name, Class<?>... parameterTypes) {
    try {
      Method method = owner.getDeclaredMethod(name, parameterTypes);
      method.setAccessible(true);
      return method;
    } catch (NoSuchMethodException | RuntimeException e) {
      throw inaccessible(owner.getName() + "." + name, e);
    }
  }

  private static int intConstant(Class<?> owner, String name) {
    try {
      Field field = owner.getDeclaredField(name);
      field.setAccessible(true);
      return field.getInt(null);
    } catch (NoSuchFieldException | IllegalAccessException | RuntimeException e) {
      throw inaccessible(owner.getName() + "." + name, e);
    }
  }

  private static IllegalStateException inaccessible(String member, Exception cause) {
    return new IllegalStateException(
        "Cannot read "
            + member
            + "; run the generator via generate-unicode-tables.sh, which opens "
            + JDK_REGEX_INTERNALS,
        cause);
  }

  private static int invokeInt(Method method, int cp) {
    return (Integer) invoke(method, cp);
  }

  private static boolean invokeBoolean(Method method, int cp) {
    return (Boolean) invoke(method, cp);
  }

  private static Object invoke(Method method, int cp) {
    try {
      return method.invoke(null, cp);
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new IllegalStateException("Cannot invoke " + method, e);
    }
  }

  private static void writeJava(PrintWriter out, GeneratedTables tables) {
    String header =
        """
        // This file is part of a Java port of RE2 (https://github.com/google/re2).
        // Original RE2 code is Copyright (c) 2009 The RE2 Authors.
        // Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
        // Licensed under the BSD 3-Clause License (see LICENSE file).

        // WARNING: This file is automatically generated. Do not edit by hand.
        // To regenerate, run:
        //   ./safere-unicode/generate-unicode-tables.sh

        package org.safere;

        import java.util.Collections;
        import java.util.LinkedHashMap;
        import java.util.Map;

        /** Checked-in Unicode tables generated from a maintainer-selected JDK. */
        final class UnicodeGeneratedTables {
          static final String GENERATOR_JAVA_VERSION = "%s";
        """
            .formatted(javaVersion());
    out.println(header);
    out.println();

    writeInlineMap(out, "CATEGORIES", tables.categories(), "category");
    writeInlineMap(out, "SCRIPTS", tables.scripts(), "script");
    writeInlineMap(out, "BLOCKS", tables.blocks(), "block");
    writeInlineMap(out, "BINARY_PROPERTIES", tables.binaryProperties(), "property");
    out.println(
        "  // Grapheme segmentation classes; internal only, not exposed as \\p{...} names.");
    writeInlineMap(out, "GRAPHEME_PROPERTIES", tables.graphemeProperties(), "grapheme");

    String middle =
        """
          private UnicodeGeneratedTables() {}

          static Map<String, int[][]> unicodeGroups() {
            Map<String, int[][]> groups = new LinkedHashMap<>();
            groups.putAll(CATEGORIES);
            groups.putAll(SCRIPTS);
            return Collections.unmodifiableMap(groups);
          }
        """;
    out.println(middle);
    out.println();

    writeTableMethods(out, "category", tables.categories());
    writeTableMethods(out, "script", tables.scripts());
    writeTableMethods(out, "property", tables.binaryProperties());
    writeTableMethods(out, "grapheme", tables.graphemeProperties());

    out.println("}");
  }

  private static void writeInlineMap(
      PrintWriter out, String name, Map<String, int[][]> tables, String prefix) {
    out.println("  // Include explicit type arguments to ofEntries to avoid JDK-8221301");
    out.println(
        "  static final Map<String, int[][]> " + name + " = Map.<String, int[][]>ofEntries(");
    int count = 0;
    for (Map.Entry<String, int[][]> entry : tables.entrySet()) {
      String key = entry.getKey();
      String entryStr;
      if (prefix.equals("block")) {
        int[][] ranges = entry.getValue();
        entryStr =
            "    Map.entry(\"%s\", new int[][] {{0x%x, 0x%x}})"
                .formatted(key, ranges[0][0], ranges[0][1]);
      } else {
        entryStr = "    Map.entry(\"%s\", %s_%s())".formatted(key, prefix, safeIdentifier(key));
      }
      out.print(entryStr);
      if (++count < tables.size()) {
        out.println(",");
      } else {
        out.println();
      }
    }
    out.println("  );");
    out.println();
  }

  private static void writeTableMethods(
      PrintWriter out, String prefix, Map<String, int[][]> tables) {
    for (Map.Entry<String, int[][]> entry : tables.entrySet()) {
      String name = entry.getKey();
      int[][] ranges = entry.getValue();
      out.println("  private static int[][] %s_%s() {".formatted(prefix, safeIdentifier(name)));
      out.println("    return new int[][] {");
      for (int[] range : ranges) {
        out.println("      {0x%x, 0x%x},".formatted(range[0], range[1]));
      }
      out.println("    };");
      out.println("  }");
      out.println();
    }
  }

  private static String safeIdentifier(String name) {
    return name.replaceAll("[^a-zA-Z0-9_]", "_");
  }

  private static int[][] buildRanges(IntPredicate predicate) {
    RangeBuilder builder = new RangeBuilder();
    for (int cp = 0; cp <= MAX_CODE_POINT; cp++) {
      if (predicate.test(cp)) {
        builder.add(cp);
      }
    }
    return builder.build();
  }

  private static int[][] mergeSubcategories(int[][][] allTables, int[] types) {
    List<int[]> ranges = new ArrayList<>();
    for (int type : types) {
      Collections.addAll(ranges, allTables[type]);
    }
    ranges.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
    RangeBuilder builder = new RangeBuilder();
    for (int[] range : ranges) {
      builder.addRange(range[0], range[1]);
    }
    return builder.build();
  }

  private static boolean isPunctuation(int cp) {
    int type = Character.getType(cp);
    return type == Character.CONNECTOR_PUNCTUATION
        || type == Character.DASH_PUNCTUATION
        || type == Character.START_PUNCTUATION
        || type == Character.END_PUNCTUATION
        || type == Character.OTHER_PUNCTUATION
        || type == Character.INITIAL_QUOTE_PUNCTUATION
        || type == Character.FINAL_QUOTE_PUNCTUATION;
  }

  private static boolean isHexDigit(int cp) {
    return (cp >= '0' && cp <= '9')
        || (cp >= 'A' && cp <= 'F')
        || (cp >= 'a' && cp <= 'f')
        || (cp >= 0xFF10 && cp <= 0xFF19)
        || (cp >= 0xFF21 && cp <= 0xFF26)
        || (cp >= 0xFF41 && cp <= 0xFF46);
  }

  private static boolean isNoncharacterCodePoint(int cp) {
    return (cp >= 0xFDD0 && cp <= 0xFDEF)
        || ((cp & 0xFFFE) == 0xFFFE && cp <= Character.MAX_CODE_POINT);
  }

  private static String scriptName(Character.UnicodeScript script) {
    if (script == Character.UnicodeScript.SIGNWRITING) {
      return "SignWriting";
    }
    return toTitleSnake(script.name());
  }

  private static String blockName(Character.UnicodeBlock block) {
    return toTitleSnake(block.toString());
  }

  private static String toTitleSnake(String upperSnake) {
    StringBuilder result = new StringBuilder(upperSnake.length());
    for (String part : upperSnake.split("_", -1)) {
      if (!result.isEmpty()) {
        result.append('_');
      }
      result.append(Character.toUpperCase(part.charAt(0)));
      if (part.length() > 1) {
        result.append(part.substring(1).toLowerCase(Locale.ROOT));
      }
    }
    return result.toString();
  }

  private static String javaVersion() {
    return Runtime.version() + " (" + System.getProperty("java.vendor") + ")";
  }

  private record GeneratedTables(
      Map<String, int[][]> categories,
      Map<String, int[][]> scripts,
      Map<String, int[][]> blocks,
      Map<String, int[][]> binaryProperties,
      Map<String, int[][]> graphemeProperties) {}

  private static final class RangeBuilder {
    private final List<int[]> ranges = new ArrayList<>();

    void add(int cp) {
      addRange(cp, cp);
    }

    void addRange(int lo, int hi) {
      if (!ranges.isEmpty()) {
        int[] last = ranges.get(ranges.size() - 1);
        if (lo <= last[1] + 1) {
          last[1] = Math.max(last[1], hi);
          return;
        }
      }
      ranges.add(new int[] {lo, hi});
    }

    int[][] build() {
      return ranges.toArray(new int[0][]);
    }
  }
}
