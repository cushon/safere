#!/usr/bin/env python3
#
# This file is part of a Java port of RE2 (https://github.com/google/re2).
# Original RE2 code is Copyright (c) 2009 The RE2 Authors.
# Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
# Licensed under the BSD 3-Clause License (see LICENSE file).

"""Generates org.safere.TwoWay containing specialized Crochemore-Perrin Two-Way search kernels."""

from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional


@dataclass
class Variant:
  section_num: int
  description: str
  method_name: str
  text_params: str
  needle_param: str
  needle_len_expr: str
  text_len_expr: str
  single_char_body: str
  needle_char_type: str
  needle_char_get: Callable[[str], str]
  period_equals: Callable[[str, str], str]
  search_equals: Callable[[str, str], str]
  has_work_counter: bool


VARIANTS = [
    Variant(
        section_num=1,
        description="Exact UTF-8 byte[] search",
        method_name="indexOf",
        text_params="byte[] bytes, int offset, int length",
        needle_param="byte[] literal",
        needle_len_expr="literal.length",
        text_len_expr="length",
        single_char_body="""    if (literalLen == 1) {
      byte target = literal[0];
      int startIdx = Math.max(0, start);
      for (int i = startIdx; i < length; i++) {
        if (bytes[offset + i] == target) {
          if (WorkCounterConfig.ENABLED) {
            WorkCounter.record(i - startIdx + 1);
          }
          return i;
        }
      }
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record(length - startIdx);
      }
      return -1;
    }""",
        needle_char_type="int",
        needle_char_get=lambda expr: f"literal[{expr}] & 0xFF",
        period_equals=lambda a, b: f"literal[{a}] == literal[{b}]",
        search_equals=lambda n, t: f"literal[{n}] == bytes[offset + {t}]",
        has_work_counter=True,
    ),
    Variant(
        section_num=2,
        description="ASCII Case-Insensitive String search",
        method_name="indexOfIgnoreCase",
        text_params="String text",
        needle_param="String prefix",
        needle_len_expr="prefix.length()",
        text_len_expr="text.length()",
        single_char_body="""    if (literalLen == 1) {
      return Ascii.indexOfIgnoreCase(text, prefix.charAt(0), start);
    }""",
        needle_char_type="char",
        needle_char_get=lambda expr: f"toLowerCase(prefix.charAt({expr}))",
        period_equals=lambda a, b: (
            f"toLowerCase(prefix.charAt({a})) =="
            f" toLowerCase(prefix.charAt({b}))"
        ),
        search_equals=lambda n, t: (
            f"toLowerCase(prefix.charAt({n})) == toLowerCase(text.charAt({t}))"
        ),
        has_work_counter=True,
    ),
    Variant(
        section_num=3,
        description="ASCII Case-Insensitive byte[] search with String prefix",
        method_name="indexOfIgnoreCase",
        text_params="byte[] bytes, int offset, int length",
        needle_param="String prefix",
        needle_len_expr="prefix.length()",
        text_len_expr="length",
        single_char_body="""    if (literalLen == 1) {
      byte low = (byte) toLowerCase(prefix.charAt(0));
      byte high = (byte) Ascii.toUpperCase(prefix.charAt(0));
      for (int i = Math.max(0, start); i < length; i++) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record();
        }
        byte b = bytes[offset + i];
        if (b == low || b == high) {
          return i;
        }
      }
      return -1;
    }""",
        needle_char_type="char",
        needle_char_get=lambda expr: f"toLowerCase(prefix.charAt({expr}))",
        period_equals=lambda a, b: (
            f"toLowerCase(prefix.charAt({a})) =="
            f" toLowerCase(prefix.charAt({b}))"
        ),
        search_equals=lambda n, t: (
            f"toLowerCase(prefix.charAt({n})) =="
            f" toLowerCase(bytes[offset + {t}] & 0xFF)"
        ),
        has_work_counter=True,
    ),
    Variant(
        section_num=4,
        description="ASCII Case-Insensitive char[] search with String pattern",
        method_name="indexOfIgnoreCase",
        text_params="char[] input, int offset, int length",
        needle_param="String pattern",
        needle_len_expr="pattern.length()",
        text_len_expr="length",
        single_char_body="""    if (literalLen == 1) {
      char target = pattern.charAt(0);
      for (int i = Math.max(0, start); i < length; i++) {
        if (equalsIgnoreCase(input[offset + i], target)) {
          return i;
        }
      }
      return -1;
    }""",
        needle_char_type="char",
        needle_char_get=lambda expr: f"toLowerCase(pattern.charAt({expr}))",
        period_equals=lambda a, b: (
            f"toLowerCase(pattern.charAt({a})) =="
            f" toLowerCase(pattern.charAt({b}))"
        ),
        search_equals=lambda n, t: (
            f"equalsIgnoreCase(pattern.charAt({n}), input[offset + {t}])"
        ),
        has_work_counter=False,
    ),
    Variant(
        section_num=5,
        description=(
            "ASCII Case-Insensitive byte[] UTF-16 LE search with String pattern"
        ),
        method_name="indexOfIgnoreCaseUtf16",
        text_params="byte[] input, int offset, int length",
        needle_param="String pattern",
        needle_len_expr="pattern.length()",
        text_len_expr="length",
        single_char_body="""    if (literalLen == 1) {
      char target = pattern.charAt(0);
      for (int i = Math.max(0, start); i < length; i++) {
        if (equalsIgnoreCase(Utf16.getChar(input, offset, i), target)) {
          return i;
        }
      }
      return -1;
    }""",
        needle_char_type="char",
        needle_char_get=lambda expr: f"toLowerCase(pattern.charAt({expr}))",
        period_equals=lambda a, b: (
            f"toLowerCase(pattern.charAt({a})) =="
            f" toLowerCase(pattern.charAt({b}))"
        ),
        search_equals=lambda n, t: (
            f"equalsIgnoreCase(pattern.charAt({n}), Utf16.getChar(input, offset,"
            f" {t}))"
        ),
        has_work_counter=False,
    ),
]


def generate_method(v: Variant) -> str:
  wc_fwd = (
      """        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(i - startI + (i < literalLen ? 1 : 0));
        }
"""
      if v.has_work_counter
      else ""
  )

  wc_bwd_periodic = (
      """        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(startJj - jj + (jj >= memory ? 1 : 0));
        }
"""
      if v.has_work_counter
      else ""
  )

  wc_bwd_aperiodic = (
      """        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(startJj - jj + (jj >= 0 ? 1 : 0));
        }
"""
      if v.has_work_counter
      else ""
  )

  start_i_init = "        int startI = i;\n" if v.has_work_counter else ""
  start_jj_init = "        int startJj = jj;\n" if v.has_work_counter else ""

  text_len_var_def = (
      f"    int textLen = {v.text_len_expr};\n"
      if v.text_len_expr != "length"
      else ""
  )
  text_len_ref = "textLen" if v.text_len_expr != "length" else "length"

  return f"""  // =============================================================================================
  // {v.section_num}. {v.description}
  // =============================================================================================

  static int {v.method_name}({v.text_params}, {v.needle_param}, int start) {{
    int literalLen = {v.needle_len_expr};
    if (literalLen == 0) {{
      return Math.min(Math.max(0, start), {v.text_len_expr});
    }}
{v.single_char_body}
    int s = Math.max(0, start);
{text_len_var_def}    if (s >= {text_len_ref} || literalLen > {text_len_ref} - s) {{
      return -1;
    }}

    int ms1 = -1;
    int j = 0;
    int k = 1;
    int p1 = 1;
    while (j + k < literalLen) {{
      {v.needle_char_type} a = {v.needle_char_get('ms1 + k')};
      {v.needle_char_type} b = {v.needle_char_get('j + k')};
      if (b < a) {{
        j += k;
        k = 1;
        p1 = j - ms1;
      }} else if (b == a) {{
        if (k == p1) {{
          j += p1;
          k = 1;
        }} else {{
          k++;
        }}
      }} else {{
        ms1 = j++;
        k = 1;
        p1 = 1;
      }}
    }}

    int ms2 = -1;
    j = 0;
    k = 1;
    int p2 = 1;
    while (j + k < literalLen) {{
      {v.needle_char_type} a = {v.needle_char_get('ms2 + k')};
      {v.needle_char_type} b = {v.needle_char_get('j + k')};
      if (b > a) {{
        j += k;
        k = 1;
        p2 = j - ms2;
      }} else if (b == a) {{
        if (k == p2) {{
          j += p2;
          k = 1;
        }} else {{
          k++;
        }}
      }} else {{
        ms2 = j++;
        k = 1;
        p2 = 1;
      }}
    }}

    int ell = ms1 + 1 >= ms2 + 1 ? ms1 + 1 : ms2 + 1;
    int period = ms1 + 1 >= ms2 + 1 ? p1 : p2;

    boolean isPeriodic = true;
    for (int i = 0; i < ell; i++) {{
      if (!({v.period_equals('i', 'i + period')})) {{
        isPeriodic = false;
        break;
      }}
    }}

    int memory = 0;
    if (isPeriodic) {{
      while (s <= {text_len_ref} - literalLen) {{
        int i = Math.max(ell, memory);
{start_i_init}        while (i < literalLen && {v.search_equals('i', 's + i')}) {{
          i++;
        }}
{wc_fwd}        if (i < literalLen) {{
          s += (i - ell + 1);
          memory = 0;
          continue;
        }}
        int jj = ell - 1;
{start_jj_init}        while (jj >= memory && {v.search_equals('jj', 's + jj')}) {{
          jj--;
        }}
{wc_bwd_periodic}        if (jj < memory) {{
          return s;
        }}
        s += period;
        memory = literalLen - period;
      }}
    }} else {{
      int periodJump = Math.max(ell, literalLen - ell) + 1;
      while (s <= {text_len_ref} - literalLen) {{
        int i = ell;
{start_i_init}        while (i < literalLen && {v.search_equals('i', 's + i')}) {{
          i++;
        }}
{wc_fwd}        if (i < literalLen) {{
          s += (i - ell + 1);
          continue;
        }}
        int jj = ell - 1;
{start_jj_init}        while (jj >= 0 && {v.search_equals('jj', 's + jj')}) {{
          jj--;
        }}
{wc_bwd_aperiodic}        if (jj < 0) {{
          return s;
        }}
        s += periodJump;
      }}
    }}
    return -1;
  }}"""


def generate_all() -> str:
  header = """// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.safere.Ascii.equalsIgnoreCase;
import static org.safere.Ascii.toLowerCase;

/**
 * Crochemore-Perrin Two-Way exact string matching algorithm.
 *
 * <p>Guarantees strictly linear worst-case time (at most {@code 2 * N} character comparisons) while
 * using strict {@code O(1)} auxiliary memory (zero heap array allocations, primitive local
 * registers on the thread stack).
 *
 * <p>Reference: Crochemore, M., &amp; Perrin, D. (1991). "Two-way string-matching." Journal of the
 * ACM (JACM), 38(3), 651-675.
 *
 * <p>NOTE: This class is generated by {@code scripts/generate_twoway.py}. Do not edit directly.
 */
final class TwoWay {

  private TwoWay() {}
"""

  methods = "\n\n".join(generate_method(v) for v in VARIANTS)
  footer = "\n}\n"
  return header + "\n" + methods + footer


def main():
  root = Path(__file__).resolve().parent.parent
  out_path = root / "safere/src/main/java/org/safere/TwoWay.java"
  code = generate_all()
  out_path.write_text(code, encoding="utf-8")
  print(f"Generated {out_path}")


if __name__ == "__main__":
  main()
