package com.github.gh2302.hbasemcp;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HConstants;

import java.io.File;
import java.net.MalformedURLException;

/**
 * Server configuration, resolved from the environment.
 *
 * <p>There are two ways to point at a cluster, and they compose:
 * <ol>
 *   <li>{@code HBASE_CONF_DIR} -- a directory of Hadoop XML config files, exactly as shipped by
 *       the cluster. This is the path of least surprise for an existing cluster.</li>
 *   <li>{@code HBASE_ZOOKEEPER_QUORUM} and friends -- individual overrides, applied on top.</li>
 * </ol>
 *
 * <p>The connection is the plain one either way: a ZooKeeper quorum and no authentication.
 * Nothing here configures Kerberos, SASL or TLS.
 */
final class Config {

    private final Configuration hadoop;
    private final boolean readOnly;
    private final boolean allowDestructive;
    private final int maxRows;
    private final int countCaching;
    private final int operationTimeoutMs;

    private Config(Configuration hadoop, boolean readOnly, boolean allowDestructive,
                   int maxRows, int countCaching, int operationTimeoutMs) {
        this.hadoop = hadoop;
        this.readOnly = readOnly;
        this.allowDestructive = allowDestructive;
        this.maxRows = maxRows;
        this.countCaching = countCaching;
        this.operationTimeoutMs = operationTimeoutMs;
    }

    static Config fromEnv() {
        Configuration hadoop = HBaseConfiguration.create();

        String confDir = env("HBASE_CONF_DIR");
        if (confDir != null) {
            loadConfDir(hadoop, confDir);
        }

        // Explicit overrides win over whatever the XML said.
        String quorum = env("HBASE_ZOOKEEPER_QUORUM");
        if (quorum != null) {
            hadoop.set(HConstants.ZOOKEEPER_QUORUM, quorum);
        }
        String port = env("HBASE_ZOOKEEPER_PORT");
        if (port != null) {
            hadoop.setInt(HConstants.ZOOKEEPER_CLIENT_PORT, parseInt("HBASE_ZOOKEEPER_PORT", port));
        }
        String znode = env("HBASE_ZNODE_PARENT");
        if (znode != null) {
            hadoop.set(HConstants.ZOOKEEPER_ZNODE_PARENT, znode);
        }

        // Timeouts are deliberately imposed on top of any cluster config. Cluster defaults are
        // tuned for batch jobs (hbase.rpc.timeout of 5 minutes and 35 retries is common), which
        // would leave an interactive tool call hanging for the better part of an hour. Set the
        // HBASE_MCP_* variables explicitly to restore long waits.
        int rpcTimeoutMs = envInt("HBASE_MCP_RPC_TIMEOUT_MS", 30_000);
        int operationTimeoutMs = envInt("HBASE_MCP_OPERATION_TIMEOUT_MS", 60_000);
        int retries = envInt("HBASE_MCP_RETRIES", 3);

        hadoop.setInt(HConstants.HBASE_RPC_TIMEOUT_KEY, rpcTimeoutMs);
        hadoop.setInt(HConstants.HBASE_CLIENT_OPERATION_TIMEOUT, operationTimeoutMs);
        hadoop.setInt(HConstants.HBASE_CLIENT_META_OPERATION_TIMEOUT, operationTimeoutMs);
        hadoop.setInt(HConstants.HBASE_CLIENT_RETRIES_NUMBER, retries);
        hadoop.setInt(HConstants.HBASE_CLIENT_SCANNER_TIMEOUT_PERIOD, operationTimeoutMs);
        hadoop.setInt("hbase.client.pause", 200);
        hadoop.setInt("zookeeper.recovery.retry", 1);

        // Cell blocks are nearly all of a scan response, and they compress well: the row keys in
        // a batch are sorted and share long prefixes. The codec is named in the connection header
        // and the region server honours it, so this needs nothing on the cluster beyond the class
        // being on its classpath -- but a codec it cannot build kills the connection, which is
        // why it is off unless asked for.
        String compression = env("HBASE_MCP_RPC_COMPRESSION");
        if (compression != null && !compression.equalsIgnoreCase("none")) {
            hadoop.set("hbase.client.rpc.compressor", compressorClass(compression));
        }

        return new Config(
                hadoop,
                envBool("HBASE_MCP_READ_ONLY", false),
                envBool("HBASE_MCP_ALLOW_DESTRUCTIVE", false),
                envInt("HBASE_MCP_MAX_ROWS", 1000),
                envInt("HBASE_MCP_COUNT_CACHING", 10_000),
                operationTimeoutMs);
    }

    private static void loadConfDir(Configuration hadoop, String confDir) {
        File dir = new File(confDir);
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("HBASE_CONF_DIR is not a directory: " + confDir);
        }
        boolean loadedAny = false;
        // core-site first, then hbase-site, so hbase-site wins on any overlap.
        for (String name : new String[] {"core-site.xml", "hdfs-site.xml", "hbase-site.xml"}) {
            File file = new File(dir, name);
            if (!file.isFile()) {
                continue;
            }
            try {
                hadoop.addResource(file.toURI().toURL());
                loadedAny = true;
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("Could not read " + file + ": " + e.getMessage(), e);
            }
        }
        if (!loadedAny) {
            throw new IllegalArgumentException(
                    "HBASE_CONF_DIR contains no hbase-site.xml or core-site.xml: " + confDir);
        }
    }

    Configuration hadoop() {
        return hadoop;
    }

    boolean readOnly() {
        return readOnly;
    }

    boolean allowDestructive() {
        return allowDestructive;
    }

    int maxRows() {
        return maxRows;
    }

    /** How many rows a count asks for per RPC. Its rows are key-only, so a large batch is small. */
    int countCaching() {
        return Math.max(1, countCaching);
    }

    int operationTimeoutMs() {
        return operationTimeoutMs;
    }

    /** The ZooKeeper target actually in effect, for logging and for cluster_info. */
    String describeTarget() {
        String quorum = hadoop.get(HConstants.ZOOKEEPER_QUORUM, "localhost");
        int port = hadoop.getInt(HConstants.ZOOKEEPER_CLIENT_PORT, 2181);
        String znode = hadoop.get(HConstants.ZOOKEEPER_ZNODE_PARENT, "/hbase");
        return quorum + ":" + port + znode;
    }

    /** Maps the short name of a compression codec to its class, and passes anything else through. */
    private static String compressorClass(String name) {
        return switch (name.toLowerCase()) {
            case "gzip" -> "org.apache.hadoop.io.compress.GzipCodec";
            case "snappy" -> "org.apache.hadoop.io.compress.SnappyCodec";
            case "lz4" -> "org.apache.hadoop.io.compress.Lz4Codec";
            case "zstd" -> "org.apache.hadoop.io.compress.ZStandardCodec";
            default -> name;
        };
    }

    private static String env(String key) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int envInt(String key, int fallback) {
        String value = env(key);
        return value == null ? fallback : parseInt(key, value);
    }

    private static int parseInt(String key, String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer, got: " + value, e);
        }
    }

    private static boolean envBool(String key, boolean fallback) {
        String value = env(key);
        if (value == null) {
            return fallback;
        }
        return switch (value.toLowerCase()) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> throw new IllegalArgumentException(key + " must be true or false, got: " + value);
        };
    }
}
