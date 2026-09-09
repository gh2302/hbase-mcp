# hbase-mcp

An MCP server for Apache HBase, over the simplest connection HBase offers: a ZooKeeper quorum,
no security. It speaks the native HBase RPC protocol, so it needs no Thrift or REST gateway —
just the ZooKeeper addresses your cluster already publishes.

Ships as a single self-contained jar and talks MCP over stdio.

## Requirements

- **Java 17.** HBase 2.6 supports Java 8 through 17 and no higher. On Java 24+ every HBase call
  fails with `getSubject is not supported`, because Hadoop's `UserGroupInformation` depends on
  the Security Manager that [JEP 486](https://openjdk.org/jeps/486) permanently disabled. The
  server prints a warning at startup if it detects a too-new JVM.
- Maven 3.9+ to build.
- Network reach to the ZooKeeper quorum **and** to the HBase master and region servers. Clients
  connect to region servers directly, using the hostnames ZooKeeper hands back, so those names
  have to resolve from wherever this runs.

## Build

```bash
mvn package
```

Produces `target/hbase-mcp.jar` (~65 MB; it bundles the shaded HBase client).

## Configure

Everything comes from the environment. There are two ways to point at a cluster and they compose.

**A directory of Hadoop config files** — the path of least surprise for an existing cluster.
Point at the directory your cluster's `hbase-site.xml` lives in:

```bash
HBASE_CONF_DIR=/path/to/hbase/conf
```

It reads `core-site.xml`, `hdfs-site.xml` and `hbase-site.xml` if present, in that order.

**Individual settings**, applied on top of whatever the XML said:

| Variable | Default | Meaning |
|---|---|---|
| `HBASE_ZOOKEEPER_QUORUM` | `localhost` | Comma-separated ZooKeeper hosts |
| `HBASE_ZOOKEEPER_PORT` | `2181` | ZooKeeper client port |
| `HBASE_ZNODE_PARENT` | `/hbase` | HBase znode parent |

**Server behaviour:**

| Variable | Default | Meaning |
|---|---|---|
| `HBASE_MCP_READ_ONLY` | `false` | When true, only the read tools are registered |
| `HBASE_MCP_ALLOW_DESTRUCTIVE` | `false` | When true, adds `drop_table` and `truncate_table` |
| `HBASE_MCP_MAX_ROWS` | `1000` | Hard ceiling on rows returned by `scan` |
| `HBASE_MCP_RPC_TIMEOUT_MS` | `30000` | Per-RPC timeout |
| `HBASE_MCP_OPERATION_TIMEOUT_MS` | `60000` | Whole-operation timeout |
| `HBASE_MCP_RETRIES` | `3` | Client retry count |
| `HBASE_MCP_LOG_LEVEL` | `warn` | `trace`/`debug`/`info`/`warn`/`error`, logged to stderr |

The timeout and retry settings are imposed on top of any `hbase-site.xml`, deliberately. Cluster
defaults are tuned for batch jobs — `hbase.rpc.timeout` of 5 minutes with 35 retries is common —
which would leave an interactive tool call hanging for the better part of an hour. Set the
`HBASE_MCP_*` variables explicitly if you want the cluster's own values.

## Register with an MCP client

```json
{
  "mcpServers": {
    "hbase": {
      "command": "/path/to/jdk17/bin/java",
      "args": ["-jar", "/path/to/hbase-mcp/target/hbase-mcp.jar"],
      "env": {
        "HBASE_CONF_DIR": "/path/to/hbase/conf",
        "HBASE_MCP_READ_ONLY": "true"
      }
    }
  }
}
```

Name the Java 17 binary explicitly unless it is already first on `PATH`. `bin/hbase-mcp.sh` and
`bin/hbase-mcp.cmd` do the same thing and honour `JAVA_HOME_17`.

For Claude Code, put that JSON in `.mcp.json` at the project root. It is deliberately not
tracked here: the paths are absolute and the conf dir is whichever cluster you point at, so
it is yours to write. Starting read-only is the sensible default against a shared cluster.

## Tools

Read-only, always available:

| Tool | What it does |
|---|---|
| `cluster_info` | Version, master, live and dead region servers, region count. The quickest connectivity check. |
| `list_namespaces` | The cluster's namespaces. |
| `list_tables` | Tables and their column families; filter by `namespace` or `regex`. |
| `describe_table` | Enabled state, column family settings, region count and split points. |
| `get_row` | One row by exact key, optionally limited to certain columns, at any point in its version history. |
| `scan` | A row range, bounded by `prefix` or `start_row`/`stop_row`, with an optional `row_regex`. Takes the same version arguments as `get_row`. Set `include_values: false` for a much cheaper keys-only scan. |
| `count_rows` | Counts rows server-side under an optional prefix, stopping at `max`. |

Registered unless `HBASE_MCP_READ_ONLY=true`:

| Tool | What it does |
|---|---|
| `put_row` | Writes cells into a row, adding a version rather than replacing what was there. |
| `delete_row` | Deletes a whole row, or just named columns or families, or just certain versions of them. |
| `create_table` | Creates a table with column families, optionally with version retention, TTL and compression. |

Registered only when `HBASE_MCP_ALLOW_DESTRUCTIVE=true`:

| Tool | What it does |
|---|---|
| `drop_table` | Disables and permanently deletes a table. |
| `truncate_table` | Deletes every row, keeping the schema. |

Gating happens at registration, not at call time: in read-only mode the write tools are never
advertised, so a model cannot try one and be refused.

## Conventions

**Tables** may be namespaced, e.g. `ems:MailEvents`. The default namespace needs no prefix.

**Columns** are written `family` for a whole family or `family:qualifier` for one column. Only
the first colon separates, so qualifiers containing colons work.

**Values and row keys** are UTF-8 strings. Anything that is not valid UTF-8 text is returned as
`base64:<payload>`, and that same prefix is accepted on input — so binary row keys and values
round-trip exactly. In practice a text cell reads as text:

```json
{"family": "d", "qualifier": "item.id", "timestamp": 1773134756580, "value": "EC000527905GR"}
```

while a packed `long` reads as:

```json
{"family": "d", "qualifier": ">fdt", "timestamp": 1773134756580, "value": "base64:AAABnNb7KDA="}
```

**Scans** are capped at `HBASE_MCP_MAX_ROWS`. A result with `"truncated": true` means more rows
exist past the last one shown; continue with `start_row` set past that key.

## Versions

HBase keeps every write of a column as a separate timestamped version, up to the column family's
`VERSIONS` setting — `describe_table` reports it as `max_versions`. New families default to 1,
which keeps no history at all, so that is the first thing to check when a row looks like it has
none.

Reads return the newest version of each cell unless asked otherwise. `get_row` and `scan` both
take:

| Argument | Effect |
|---|---|
| `versions` | How many versions of each cell to return, newest first. |
| `timestamp` | Only the version written at exactly this epoch-millisecond timestamp. |
| `start_time` | Only versions written at or after this timestamp, inclusive. |
| `end_time` | Only versions written before this timestamp, exclusive. |

`timestamp` cannot be combined with `start_time`/`end_time`. A family never retains more than its
`max_versions`, so asking for more simply returns what exists.

A multi-version read numbers each cell within its own column, `0` being the current value:

```json
{"family": "d", "qualifier": "mc_e", "timestamp": 1699423827563, "version": 0, "value": "base64:AAAAAQ=="}
{"family": "d", "qualifier": "mc_e", "timestamp": 1699409825468, "version": 1, "value": "base64:AAAAAQ=="}
```

The `version` field is left off ordinary single-version reads, where it would only be noise.

Two idioms are worth knowing. `end_time` on its own reads the data as it stood at that moment —
one version per cell, the one current then. And `include_values: false` together with `versions`
surveys how often each cell changed, and when, without shipping a single value.

### Writing versions

`put_row` does not erase the previous value; HBase keeps it as an older version. Two writes to
the same column within the same millisecond collapse into one, so pass an explicit `timestamp`
when placing versions deliberately.

`delete_row` removes every version by default. `versions` narrows that:

| `versions` | Without `timestamp` | With `timestamp` |
|---|---|---|
| `all` (default) | Every version | Every version at or before it |
| `latest` | Only the newest version of each named column | rejected — use `exact` |
| `exact` | rejected | Only the version with exactly that timestamp |

`latest` and `exact` need an explicit `columns` list, since neither has a meaning for a whole
row; `latest` further needs `family:qualifier` rather than a bare family. Deletes are not
reversible, and the marker hides every matching version until the next major compaction removes
them.

## Notes on the implementation

`stdout` is the JSON-RPC channel and nothing else may touch it. At startup the server takes the
real stdout file descriptor for the protocol and repoints `System.out` at stderr, so a stray
print from HBase, Hadoop or ZooKeeper cannot corrupt the message framing. All logging goes to
stderr via slf4j-simple.

The HBase connection is created lazily on first use rather than at startup, so the server still
registers and lists its tools when the cluster is down; the failure surfaces as a tool error
with the underlying cause instead of a server that will not start.
