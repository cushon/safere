// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Benchmark for medium-sized keyword alternations (16 <= K <= 128), representative of HTTP headers,
 * SQL keywords, and MIME types. Targets Multi-Group Teddy SIMD acceleration.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Thread)
public class MediumAlternationBenchmark {

  @Param({"64", "1024", "65536"})
  public int haystackLength;

  @Param({"HTTP_HEADERS_36", "SQL_KEYWORDS_72", "MIME_TYPES_110"})
  public String workload;

  private Pattern saferePattern;
  private java.util.regex.Pattern jdkPattern;
  private String stringInput;
  private Utf8Input utf8Input;

  private static final String[] HTTP_HEADERS = {
    "Accept",
    "Accept-Charset",
    "Accept-Encoding",
    "Accept-Language",
    "Accept-Ranges",
    "Age",
    "Allow",
    "Authorization",
    "Cache-Control",
    "Connection",
    "Content-Disposition",
    "Content-Encoding",
    "Content-Language",
    "Content-Length",
    "Content-Location",
    "Content-Range",
    "Content-Type",
    "Cookie",
    "Date",
    "ETag",
    "Expect",
    "Expires",
    "From",
    "Host",
    "If-Match",
    "If-Modified-Since",
    "If-None-Match",
    "If-Range",
    "If-Unmodified-Since",
    "Last-Modified",
    "Location",
    "Origin",
    "Pragma",
    "Proxy-Authenticate",
    "Proxy-Authorization",
    "Range",
    "Referer",
    "Retry-After",
    "Server",
    "Set-Cookie",
    "Trailer",
    "Transfer-Encoding",
    "Upgrade",
    "User-Agent",
    "Vary",
    "Via",
    "Warning",
    "WWW-Authenticate"
  };

  private static final String[] SQL_KEYWORDS = {
    "SELECT", "FROM", "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET",
    "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "ALTER",
    "DROP", "TABLE", "INDEX", "VIEW", "TRIGGER", "PROCEDURE", "FUNCTION", "DATABASE",
    "SCHEMA", "GRANT", "REVOKE", "COMMIT", "ROLLBACK", "SAVEPOINT", "TRANSACTION", "JOIN",
    "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS", "NATURAL", "UNION",
    "INTERSECT", "EXCEPT", "EXISTS", "BETWEEN", "LIKE", "ILIKE", "SIMILAR", "DISTINCT",
    "CASCADE", "RESTRICT", "PRIMARY", "FOREIGN", "KEY", "REFERENCES", "CHECK", "UNIQUE",
    "DEFAULT", "NULL", "NOT", "AND", "OR", "XOR", "CASE", "WHEN",
    "THEN", "ELSE", "END", "AS", "CAST", "COLLATE", "OVER", "PARTITION"
  };

  private static final String[] MIME_TYPES = {
    "application/atom+xml",
    "application/epub+zip",
    "application/gzip",
    "application/json",
    "application/ld+json",
    "application/msword",
    "application/octet-stream",
    "application/ogg",
    "application/pdf",
    "application/rtf",
    "application/vnd.amazon.ebook",
    "application/vnd.apple.installer+xml",
    "application/vnd.mozilla.xul+xml",
    "application/vnd.ms-excel",
    "application/vnd.ms-fontobject",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.rar",
    "application/vnd.visio",
    "application/x-7z-compressed",
    "application/x-abiword",
    "application/x-bzip",
    "application/x-bzip2",
    "application/x-csh",
    "application/x-freearc",
    "application/x-sh",
    "application/x-tar",
    "application/xhtml+xml",
    "application/xml",
    "application/zip",
    "audio/3gpp",
    "audio/3gpp2",
    "audio/aac",
    "audio/midi",
    "audio/mp3",
    "audio/mp4",
    "audio/mpeg",
    "audio/ogg",
    "audio/opus",
    "audio/wav",
    "audio/webm",
    "font/otf",
    "font/ttf",
    "font/woff",
    "font/woff2",
    "image/avif",
    "image/bmp",
    "image/gif",
    "image/jpeg",
    "image/png",
    "image/svg+xml",
    "image/tiff",
    "image/vnd.microsoft.icon",
    "image/webp",
    "model/gltf-binary",
    "model/gltf+json",
    "model/stl",
    "text/calendar",
    "text/css",
    "text/csv",
    "text/html",
    "text/javascript",
    "text/markdown",
    "text/plain",
    "text/xml",
    "video/3gpp",
    "video/3gpp2",
    "video/mp2t",
    "video/mp4",
    "video/mpeg",
    "video/ogg",
    "video/quicktime",
    "video/webm",
    "video/x-msvideo"
  };

  @Setup
  public void setup() {
    String[] keywords =
        switch (workload) {
          case "HTTP_HEADERS_36" -> HTTP_HEADERS;
          case "SQL_KEYWORDS_72" -> SQL_KEYWORDS;
          case "MIME_TYPES_110" -> MIME_TYPES;
          default -> throw new IllegalArgumentException("Unknown workload: " + workload);
        };

    StringBuilder regexBuilder = new StringBuilder();
    for (int i = 0; i < keywords.length; i++) {
      if (i > 0) {
        regexBuilder.append("|");
      }
      regexBuilder.append(java.util.regex.Pattern.quote(keywords[i]));
    }
    String regex = regexBuilder.toString();

    saferePattern = Pattern.compile(regex);
    jdkPattern = java.util.regex.Pattern.compile(regex);

    Random rng = new Random(42);
    byte[] chars = new byte[haystackLength];
    for (int i = 0; i < haystackLength; i++) {
      chars[i] = (byte) ('a' + rng.nextInt(26));
    }
    stringInput = new String(chars, StandardCharsets.UTF_8);
    utf8Input = Utf8Input.trusted(chars);
  }

  @Benchmark
  public int safereString() {
    Matcher m = saferePattern.matcher(stringInput);
    return m.find() ? m.start() : -1;
  }

  @Benchmark
  public int safereUtf8() {
    Utf8Matcher m = saferePattern.matcher(utf8Input);
    return m.find() ? m.start() : -1;
  }

  @Benchmark
  public int jdkString() {
    java.util.regex.Matcher m = jdkPattern.matcher(stringInput);
    return m.find() ? m.start() : -1;
  }
}
