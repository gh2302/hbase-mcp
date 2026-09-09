package com.github.gh2302.hbasemcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool definitions and their bindings to {@link HBaseService}.
 *
 * <p>Access control is registration-time, not call-time: in read-only mode the write tools are
 * never advertised at all, so a model cannot attempt them and then be refused.
 */
final class Tools {

    private Tools() {}

    /** A tool body. Returns the JSON-shaped payload; failures are turned into error results. */
    @FunctionalInterface
    private interface Handler {
        Object handle(Map<String, Object> args) throws Exception;
    }

    static void register(McpServer.SyncSpecification<?> spec, McpJsonMapper mapper,
                         HBaseService hbase, Config config) {

        // ------------------------------------------------------------- reads

        add(spec, mapper, "cluster_info",
                "Report HBase cluster status: version, master, live and dead region servers, "
                        + "region count. Also the quickest way to verify connectivity.",
                readOnly(), """
                {"type":"object","properties":{},"additionalProperties":false}
                """,
                args -> hbase.clusterInfo());

        add(spec, mapper, "list_namespaces",
                "List the HBase namespaces.",
                readOnly(), """
                {"type":"object","properties":{},"additionalProperties":false}
                """,
                args -> hbase.listNamespaces());

        add(spec, mapper, "list_tables",
                "List tables with their column families. Optionally restrict to one namespace "
                        + "or to table names matching a regular expression.",
                readOnly(), """
                {
                  "type": "object",
                  "properties": {
                    "namespace": {"type": "string", "description": "Only tables in this namespace."},
                    "regex": {"type": "string", "description": "Only tables whose name matches this regex. Ignored when namespace is set."}
                  },
                  "additionalProperties": false
                }
                """,
                args -> hbase.listTables(optString(args, "namespace"), optString(args, "regex")));

        add(spec, mapper, "describe_table",
                "Describe one table: whether it is enabled, its column families and their "
                        + "settings (versions, TTL, compression, bloom filter), and its regions.",
                readOnly(), """
                {
                  "type": "object",
                  "properties": {
                    "table": {"type": "string", "description": "Table name, optionally namespaced as ns:table."}
                  },
                  "required": ["table"],
                  "additionalProperties": false
                }
                """,
                args -> hbase.describeTable(requireString(args, "table")));

        add(spec, mapper, "get_row",
                "Fetch a single row by its exact row key. Returns the newest version of each cell "
                        + "unless versions, or a timestamp / start_time / end_time window, asks "
                        + "for more of the history HBase keeps.",
                readOnly(), """
                {
                  "type": "object",
                  "properties": {
                    "table": {"type": "string", "description": "Table name, optionally namespaced as ns:table."},
                    "row": {"type": "string", "description": "Exact row key. Use a base64: prefix for binary keys."},
                    "columns": {
                      "type": "array",
                      "items": {"type": "string"},
                      "description": "Restrict to these columns: \\"family\\" for a whole family or \\"family:qualifier\\" for one column. Omit for the whole row."
                    },
                    "versions": {
                      "type": "integer", "minimum": 1, "default": 1,
                      "description": "How many versions of each cell to return, newest first. A column family only retains up to its VERSIONS setting (describe_table reports it as max_versions), so asking for more simply returns what exists. Each returned cell carries a \\"version\\" index, 0 being the current value."
                    },
                    "timestamp": {
                      "type": "integer", "minimum": 0,
                      "description": "Return only the version written at exactly this epoch-millisecond timestamp. Cannot be combined with start_time or end_time."
                    },
                    "start_time": {
                      "type": "integer", "minimum": 0,
                      "description": "Only versions written at or after this epoch-millisecond timestamp, inclusive."
                    },
                    "end_time": {
                      "type": "integer", "minimum": 0,
                      "description": "Only versions written before this epoch-millisecond timestamp, exclusive. On its own it reads the data as it stood at that moment."
                    }
                  },
                  "required": ["table", "row"],
                  "additionalProperties": false
                }
                """,
                args -> hbase.getRow(
                        requireString(args, "table"),
                        requireString(args, "row"),
                        optStringList(args, "columns"),
                        versionSpec(args)));

        add(spec, mapper, "scan",
                "Scan a range of rows. Bound the scan with prefix, or with start_row/stop_row "
                        + "(start inclusive, stop exclusive). Always returns at most "
                        + config.maxRows() + " rows. Returns the newest version of each cell "
                        + "unless versions, or a timestamp / start_time / end_time window, asks "
                        + "for more.",
                readOnly(), """
                {
                  "type": "object",
                  "properties": {
                    "table": {"type": "string", "description": "Table name, optionally namespaced as ns:table."},
                    "prefix": {"type": "string", "description": "Only rows whose key starts with this."},
                    "start_row": {"type": "string", "description": "First row key to read, inclusive."},
                    "stop_row": {"type": "string", "description": "Stop before this row key, exclusive."},
                    "row_regex": {"type": "string", "description": "Only rows whose key matches this regular expression."},
                    "columns": {
                      "type": "array",
                      "items": {"type": "string"},
                      "description": "Restrict to these columns: \\"family\\" or \\"family:qualifier\\"."
                    },
                    "limit": {"type": "integer", "minimum": 1, "default": 50, "description": "Maximum rows to return."},
                    "reversed": {"type": "boolean", "default": false, "description": "Scan descending by row key."},
                    "include_values": {"type": "boolean", "default": true, "description": "Set false to return keys only, which is much cheaper. Combined with versions it surveys each cell's history of timestamps without shipping the values."},
                    "versions": {
                      "type": "integer", "minimum": 1, "default": 1,
                      "description": "How many versions of each cell to return, newest first. A column family only retains up to its VERSIONS setting (describe_table reports it as max_versions), so asking for more simply returns what exists. Each returned cell carries a \\"version\\" index, 0 being the current value."
                    },
                    "timestamp": {
                      "type": "integer", "minimum": 0,
                      "description": "Return only the version written at exactly this epoch-millisecond timestamp. Cannot be combined with start_time or end_time."
                    },
                    "start_time": {
                      "type": "integer", "minimum": 0,
                      "description": "Only versions written at or after this epoch-millisecond timestamp, inclusive."
                    },
                    "end_time": {
                      "type": "integer", "minimum": 0,
                      "description": "Only versions written before this epoch-millisecond timestamp, exclusive. On its own it reads the data as it stood at that moment."
                    }
                  },
                  "required": ["table"],
                  "additionalProperties": false
                }
                """,
                args -> hbase.scan(
                        requireString(args, "table"),
                        optString(args, "start_row"),
                        optString(args, "stop_row"),
                        optString(args, "prefix"),
                        optStringList(args, "columns"),
                        optString(args, "row_regex"),
                        optInt(args, "limit", 50),
                        optBoolean(args, "reversed", false),
                        optBoolean(args, "include_values", true),
                        versionSpec(args)));

        add(spec, mapper, "count_rows",
                "Count rows, optionally under a row-key prefix. This is a full scan of the range "
                        + "on the server side, so it stops at max rows and says whether it did.",
                readOnly(), """
                {
                  "type": "object",
                  "properties": {
                    "table": {"type": "string", "description": "Table name, optionally namespaced as ns:table."},
                    "prefix": {"type": "string", "description": "Only count rows whose key starts with this."},
                    "max": {"type": "integer", "minimum": 1, "default": 100000, "description": "Stop counting here to bound the cost."}
                  },
                  "required": ["table"],
                  "additionalProperties": false
                }
                """,
                args -> hbase.countRows(
                        requireString(args, "table"),
                        optString(args, "prefix"),
                        optInt(args, "max", 100_000)));

        if (config.readOnly()) {
            return;
        }

        // ------------------------------------------------------------ writes

        add(spec, mapper, "put_row",
                "Write cells into one row, creating the row if it does not exist. A write does "
                        + "not erase the previous value: HBase keeps it as an older version, up "
                        + "to the family's VERSIONS setting. Pass an explicit timestamp to place "
                        + "a version deliberately -- two writes within the same millisecond "
                        + "otherwise collapse into one.",
                mutating(false), """
                {
                  "type": "object",
                  "properties": {
                    "table": {"type": "string", "description": "Table name, optionally namespaced as ns:table."},
                    "row": {"type": "string", "description": "Row key. Use a base64: prefix for binary keys."},
                    "cells": {
                      "type": "object",
                      "additionalProperties": {"type": "string"},
                      "minProperties": 1,
                      "description": "Map of \\"family:qualifier\\" to value, e.g. {\\"cf:name\\": \\"alice\\"}. Use a base64: prefix for binary values."
                    },
                    "timestamp": {"type": "integer", "minimum": 0, "description": "Version timestamp for every cell written, in epoch milliseconds. Defaults to now. Writing the same column twice at the same timestamp replaces it rather than adding a version."}
                  },
                  "required": ["table", "row", "cells"],
                  "additionalProperties": false
                }
                """,
                args -> hbase.putRow(
                        requireString(args, "table"),
                        requireString(args, "row"),
                        requireStringMap(args, "cells"),
                        optLong(args, "timestamp")));

        add(spec, mapper, "delete_row",
                "Delete an entire row, or just the named columns or families within it. Removes "
                        + "every version by default; use versions and timestamp to drop only part "
                        + "of a cell's history. Deletes are not reversible: the marker hides "
                        + "every matching version until the next major compaction removes them.",
                mutating(true), """
                {
                  "type": "object",
                  "properties": {
                    "table": {"type": "string", "description": "Table name, optionally namespaced as ns:table."},
                    "row": {"type": "string", "description": "Row key. Use a base64: prefix for binary keys."},
                    "columns": {
                      "type": "array",
                      "items": {"type": "string"},
                      "description": "Delete only these: \\"family\\" or \\"family:qualifier\\". Omit to delete the whole row."
                    },
                    "versions": {
                      "type": "string", "enum": ["all", "latest", "exact"], "default": "all",
                      "description": "Which versions to remove. \\"all\\" removes every version, or with a timestamp every version at or before it. \\"latest\\" removes only the newest version of each named column, exposing the value it replaced. \\"exact\\" removes only the version whose timestamp matches. \\"latest\\" and \\"exact\\" require an explicit columns list."
                    },
                    "timestamp": {
                      "type": "integer", "minimum": 0,
                      "description": "Cell version timestamp in epoch milliseconds. Required for \\"exact\\"; with \\"all\\" it limits the delete to versions at or before it. Read the row first to find the timestamp you want."
                    }
                  },
                  "required": ["table", "row"],
                  "additionalProperties": false
                }
                """,
                args -> hbase.deleteRow(
                        requireString(args, "table"),
                        requireString(args, "row"),
                        optStringList(args, "columns"),
                        HBaseService.DeleteVersions.of(optString(args, "versions")),
                        optLong(args, "timestamp")));

        add(spec, mapper, "create_table",
                "Create a table with one or more column families. Note that max_versions "
                        + "defaults to 1: a family has to be created with a higher value for "
                        + "HBase to retain any history of a cell.",
                mutating(false), """
                {
                  "type": "object",
                  "properties": {
                    "table": {"type": "string", "description": "Table name, optionally namespaced as ns:table. The namespace must already exist."},
                    "families": {
                      "type": "array",
                      "minItems": 1,
                      "items": {
                        "type": "object",
                        "properties": {
                          "name": {"type": "string"},
                          "max_versions": {"type": "integer", "minimum": 1, "description": "Versions of each cell to retain. Defaults to 1, which keeps only the current value."},
                          "min_versions": {"type": "integer", "minimum": 0, "description": "Versions to keep even once they are older than ttl_seconds. Only meaningful alongside a TTL, and must not exceed max_versions."},
                          "ttl_seconds": {"type": "integer", "minimum": 1},
                          "compression": {"type": "string", "enum": ["NONE", "GZ", "SNAPPY", "LZ4", "ZSTD"]}
                        },
                        "required": ["name"],
                        "additionalProperties": false
                      }
                    }
                  },
                  "required": ["table", "families"],
                  "additionalProperties": false
                }
                """,
                args -> hbase.createTable(
                        requireString(args, "table"),
                        requireObjectList(args, "families")));

        if (!config.allowDestructive()) {
            return;
        }

        // ------------------------------------------------------- destructive

        add(spec, mapper, "drop_table",
                "Disable and permanently delete a table and all of its data. This cannot be undone.",
                mutating(true), """
                {
                  "type": "object",
                  "properties": {
                    "table": {"type": "string", "description": "Table name, optionally namespaced as ns:table."}
                  },
                  "required": ["table"],
                  "additionalProperties": false
                }
                """,
                args -> hbase.dropTable(requireString(args, "table")));

        add(spec, mapper, "truncate_table",
                "Delete every row in a table, keeping the table and its schema. This cannot be undone.",
                mutating(true), """
                {
                  "type": "object",
                  "properties": {
                    "table": {"type": "string", "description": "Table name, optionally namespaced as ns:table."},
                    "preserve_splits": {"type": "boolean", "default": true, "description": "Keep the existing region split points."}
                  },
                  "required": ["table"],
                  "additionalProperties": false
                }
                """,
                args -> hbase.truncateTable(
                        requireString(args, "table"),
                        optBoolean(args, "preserve_splits", true)));
    }

    // ------------------------------------------------------------ plumbing

    private static void add(McpServer.SyncSpecification<?> spec, McpJsonMapper mapper, String name,
                            String description, McpSchema.ToolAnnotations annotations,
                            String inputSchema, Handler handler) {
        McpSchema.Tool tool = McpSchema.Tool.builder(name, mapper, inputSchema)
                .description(description)
                .annotations(annotations)
                .build();

        spec.toolCall(tool, (exchange, request) -> {
            try {
                return HBaseMcpServer.json(mapper, handler.handle(
                        request.arguments() == null ? Map.of() : request.arguments()));
            } catch (IllegalArgumentException e) {
                return HBaseMcpServer.error(e.getMessage());
            } catch (Exception e) {
                return HBaseMcpServer.error(e);
            }
        });
    }

    private static McpSchema.ToolAnnotations readOnly() {
        return McpSchema.ToolAnnotations.builder()
                .readOnlyHint(true)
                .destructiveHint(false)
                .idempotentHint(true)
                .openWorldHint(true)
                .build();
    }

    private static McpSchema.ToolAnnotations mutating(boolean destructive) {
        return McpSchema.ToolAnnotations.builder()
                .readOnlyHint(false)
                .destructiveHint(destructive)
                .idempotentHint(true)
                .openWorldHint(true)
                .build();
    }

    // ----------------------------------------------------- argument reading

    /** Reads the versioning arguments shared by get_row and scan. */
    private static VersionSpec versionSpec(Map<String, Object> args) {
        return new VersionSpec(
                optInt(args, "versions", 1),
                optLong(args, "timestamp"),
                optLong(args, "start_time"),
                optLong(args, "end_time"));
    }

    private static String requireString(Map<String, Object> args, String key) {
        String value = optString(args, key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        return value;
    }

    private static String optString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? null : text;
    }

    private static int optInt(Map<String, Object> args, String key, int fallback) {
        Object value = args.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer, got: " + value);
        }
    }

    private static Long optLong(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer, got: " + value);
        }
    }

    private static boolean optBoolean(Map<String, Object> args, String key, boolean fallback) {
        Object value = args.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value).trim());
    }

    private static List<String> optStringList(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        } else {
            out.add(String.valueOf(value));
        }
        return out.isEmpty() ? null : out;
    }

    private static Map<String, String> requireStringMap(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            throw new IllegalArgumentException(key + " must be a non-empty object of column to value");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            out.put(String.valueOf(entry.getKey()),
                    entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
        }
        return out;
    }

    /** Accepts a list of objects, or of bare strings treated as {"name": ...}. */
    private static List<Map<String, Object>> requireObjectList(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException(key + " must be a non-empty array");
        }
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> entry = new LinkedHashMap<>();
                map.forEach((k, v) -> entry.put(String.valueOf(k), v));
                out.add(entry);
            } else if (item != null) {
                out.add(new LinkedHashMap<>(Map.of("name", String.valueOf(item))));
            }
        }
        return out;
    }
}
