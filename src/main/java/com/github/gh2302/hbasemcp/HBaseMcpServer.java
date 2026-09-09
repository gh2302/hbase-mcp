package com.github.gh2302.hbasemcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * An MCP server exposing Apache HBase over stdio.
 *
 * <p>Configuration is entirely environmental (see {@link Config}); the HBase connection is the
 * plain ZooKeeper one, with no security of any kind.
 */
public final class HBaseMcpServer {

    private static final String VERSION = "1.0.0";

    private HBaseMcpServer() {}

    public static void main(String[] args) throws Exception {
        // stdout is the JSON-RPC channel. Grab the real file descriptor for the protocol, then
        // point System.out at stderr so that a stray print from HBase, Hadoop or ZooKeeper
        // cannot corrupt the message framing. This must happen before anything else runs.
        OutputStream protocolOut = new FileOutputStream(FileDescriptor.out);
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));

        // slf4j-simple reads these once, on first logger initialisation.
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.err");
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel",
                System.getenv().getOrDefault("HBASE_MCP_LOG_LEVEL", "warn"));

        warnIfUnsupportedJava();

        Config config = Config.fromEnv();
        HBaseService service = new HBaseService(config);
        McpJsonMapper mapper = McpJsonDefaults.getMapper();

        CountDownLatch shutdown = new CountDownLatch(1);
        InputStream protocolIn = stdinClosingOn(shutdown);

        McpServer.SyncSpecification<?> spec = McpServer.sync(
                        new StdioServerTransportProvider(mapper, protocolIn, protocolOut))
                .serverInfo("hbase-mcp", VERSION)
                .instructions(instructions(config))
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .requestTimeout(Duration.ofMillis(config.operationTimeoutMs() + 30_000L))
                .validateToolInputs(true);

        Tools.register(spec, mapper, service, config);

        McpSyncServer server = spec.build();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.closeGracefully();
            } catch (RuntimeException ignored) {
                // Best effort during JVM shutdown.
            }
            service.close();
            shutdown.countDown();
        }, "hbase-mcp-shutdown"));

        System.err.println("hbase-mcp " + VERSION + " ready on stdio; ZooKeeper "
                + config.describeTarget() + (config.readOnly() ? " (read-only)" : ""));

        // Park until the client closes stdin (or the JVM is terminated).
        shutdown.await();
        service.close();
    }

    /**
     * HBase 2.6 supports Java 8 through 17. On Java 24+ the first HBase call fails deep inside
     * Hadoop with "getSubject is not supported", because the Security Manager -- which Hadoop's
     * UserGroupInformation relies on -- was permanently disabled by JEP 486. That error says
     * nothing about the real cause, so name it here instead.
     */
    private static void warnIfUnsupportedJava() {
        int feature = Runtime.version().feature();
        if (feature > 17) {
            System.err.println("WARNING: running on Java " + feature
                    + ", but HBase 2.6 supports Java 17 and below. On Java 24+ every HBase call"
                    + " fails with \"getSubject is not supported\". Run this server on Java 17.");
        }
    }

    /** Wraps stdin so that EOF -- the client hanging up -- releases the main thread. */
    private static InputStream stdinClosingOn(CountDownLatch latch) {
        return new FilterInputStream(System.in) {
            @Override
            public int read() throws IOException {
                int read = super.read();
                if (read < 0) {
                    latch.countDown();
                }
                return read;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int read = super.read(buffer, offset, length);
                if (read < 0) {
                    latch.countDown();
                }
                return read;
            }
        };
    }

    private static String instructions(Config config) {
        List<String> lines = new ArrayList<>();
        lines.add("Tools for reading and writing Apache HBase at ZooKeeper " + config.describeTarget() + ".");
        lines.add("");
        lines.add("Conventions:");
        lines.add("- Table names may be namespaced, e.g. \"ns:table\"; the default namespace needs no prefix.");
        lines.add("- Columns are written \"family\" (whole family) or \"family:qualifier\" (one column).");
        lines.add("- Values and row keys are UTF-8 strings. Binary data is represented as "
                + "\"base64:<payload>\" and that same prefix is accepted on input, so binary "
                + "row keys and values round-trip exactly.");
        lines.add("- scan is capped at " + config.maxRows() + " rows (HBASE_MCP_MAX_ROWS); a "
                + "result with \"truncated\": true means more rows exist past the last one shown.");
        lines.add("- Cells are versioned by timestamp. get_row and scan return the newest version "
                + "of each cell unless \"versions\" asks for more, and \"timestamp\" or "
                + "\"start_time\"/\"end_time\" restrict which versions are visible; "
                + "\"end_time\" alone reads the data as it stood at that moment. How much "
                + "history exists is the column family's max_versions, from describe_table.");
        lines.add("");
        if (config.readOnly()) {
            lines.add("This server is READ-ONLY: no write or schema tools are available.");
        } else if (!config.allowDestructive()) {
            lines.add("Writes are available. drop_table and truncate_table are disabled "
                    + "(set HBASE_MCP_ALLOW_DESTRUCTIVE=true to enable them).");
        } else {
            lines.add("Writes and destructive schema changes (drop_table, truncate_table) are both enabled.");
        }
        return String.join("\n", lines);
    }

    /** Serialises a tool result as pretty JSON text content. */
    static McpSchema.CallToolResult json(McpJsonMapper mapper, Object payload) {
        try {
            return McpSchema.CallToolResult.builder()
                    .addTextContent(mapper.writeValueAsString(payload))
                    .build();
        } catch (IOException e) {
            return error("Could not serialise result: " + e.getMessage());
        }
    }

    /** Reports a failure to the model as an error result rather than a protocol-level fault. */
    static McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder()
                .isError(true)
                .addTextContent(message)
                .build();
    }

    static McpSchema.CallToolResult error(Throwable t) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("error", t.getClass().getSimpleName());
        detail.put("message", t.getMessage());
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root != t) {
            detail.put("caused_by", root.getClass().getSimpleName() + ": " + root.getMessage());
        }
        return error(detail.toString());
    }
}
