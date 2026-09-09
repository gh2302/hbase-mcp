package com.github.gh2302.hbasemcp;

import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.ClusterMetrics;
import org.apache.hadoop.hbase.CompareOperator;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptor;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptorBuilder;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.TableDescriptor;
import org.apache.hadoop.hbase.client.TableDescriptorBuilder;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.FirstKeyOnlyFilter;
import org.apache.hadoop.hbase.filter.KeyOnlyFilter;
import org.apache.hadoop.hbase.filter.RegexStringComparator;
import org.apache.hadoop.hbase.filter.RowFilter;
import org.apache.hadoop.hbase.io.compress.Compression;
import org.apache.hadoop.hbase.util.Bytes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * All HBase access lives here. Every method returns plain JSON-shaped values
 * (maps, lists, strings, numbers) so the tool layer only has to serialise them.
 *
 * <p>The connection is created lazily on first use: an MCP server that refuses to start
 * because HBase happens to be down is far more annoying than a tool call that reports the
 * error. {@link #clusterInfo()} doubles as a connectivity check.
 */
final class HBaseService implements AutoCloseable {

    /** A table can have hundreds of regions; listing them all buries the useful output. */
    private static final int MAX_REGIONS_LISTED = 25;

    private final Config config;
    private volatile Connection connection;

    HBaseService(Config config) {
        this.config = config;
    }

    private Connection connection() throws IOException {
        Connection local = connection;
        if (local == null || local.isClosed()) {
            synchronized (this) {
                if (connection == null || connection.isClosed()) {
                    connection = ConnectionFactory.createConnection(config.hadoop());
                }
                local = connection;
            }
        }
        return local;
    }

    // ------------------------------------------------------------------ reads

    Map<String, Object> clusterInfo() throws IOException {
        try (Admin admin = connection().getAdmin()) {
            ClusterMetrics metrics = admin.getClusterMetrics();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("zookeeper", config.describeTarget());
            out.put("hbase_version", metrics.getHBaseVersion());
            out.put("cluster_id", metrics.getClusterId());
            ServerName master = metrics.getMasterName();
            out.put("master", master == null ? null : master.getServerName());
            out.put("live_servers", metrics.getLiveServerMetrics().keySet().stream()
                    .map(ServerName::getServerName).sorted().toList());
            out.put("dead_servers", metrics.getDeadServerNames().stream()
                    .map(ServerName::getServerName).sorted().toList());
            out.put("region_count", metrics.getRegionCount());
            out.put("average_load", metrics.getAverageLoad());
            out.put("read_only", config.readOnly());
            out.put("destructive_tools_enabled", config.allowDestructive());
            return out;
        }
    }

    List<String> listNamespaces() throws IOException {
        try (Admin admin = connection().getAdmin()) {
            return Arrays.stream(admin.listNamespaces()).sorted().toList();
        }
    }

    List<Map<String, Object>> listTables(String namespace, String regex) throws IOException {
        try (Admin admin = connection().getAdmin()) {
            List<TableDescriptor> descriptors;
            if (namespace != null) {
                descriptors = admin.listTableDescriptorsByNamespace(Bytes.toBytes(namespace));
            } else if (regex != null) {
                descriptors = admin.listTableDescriptors(Pattern.compile(regex));
            } else {
                descriptors = admin.listTableDescriptors();
            }
            List<Map<String, Object>> out = new ArrayList<>(descriptors.size());
            for (TableDescriptor td : descriptors) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("table", td.getTableName().getNameAsString());
                row.put("namespace", td.getTableName().getNamespaceAsString());
                row.put("column_families", td.getColumnFamilyNames().stream()
                        .map(Bytes::toString).sorted().toList());
                out.add(row);
            }
            out.sort((a, b) -> String.valueOf(a.get("table")).compareTo(String.valueOf(b.get("table"))));
            return out;
        }
    }

    Map<String, Object> describeTable(String table) throws IOException {
        TableName tableName = TableName.valueOf(table);
        try (Admin admin = connection().getAdmin()) {
            TableDescriptor td = admin.getDescriptor(tableName);
            List<RegionInfo> regions = admin.getRegions(tableName);

            List<Map<String, Object>> families = new ArrayList<>();
            for (ColumnFamilyDescriptor cf : td.getColumnFamilies()) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("name", cf.getNameAsString());
                f.put("max_versions", cf.getMaxVersions());
                f.put("min_versions", cf.getMinVersions());
                f.put("ttl_seconds", cf.getTimeToLive() == HConstants.FOREVER ? null : cf.getTimeToLive());
                f.put("compression", cf.getCompressionType().getName());
                f.put("bloom_filter", cf.getBloomFilterType().name());
                f.put("block_size", cf.getBlocksize());
                f.put("data_block_encoding", cf.getDataBlockEncoding().name());
                f.put("in_memory", cf.isInMemory());
                families.add(f);
            }

            List<Map<String, Object>> regionInfo = new ArrayList<>();
            for (RegionInfo r : regions.subList(0, Math.min(regions.size(), MAX_REGIONS_LISTED))) {
                Map<String, Object> ri = new LinkedHashMap<>();
                ri.put("encoded_name", r.getEncodedName());
                ri.put("start_key", Codec.toText(r.getStartKey()));
                ri.put("end_key", Codec.toText(r.getEndKey()));
                regionInfo.add(ri);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("table", tableName.getNameAsString());
            out.put("enabled", admin.isTableEnabled(tableName));
            out.put("column_families", families);
            out.put("region_count", regions.size());
            out.put("regions", regionInfo);
            if (regions.size() > MAX_REGIONS_LISTED) {
                out.put("regions_note", "showing the first " + MAX_REGIONS_LISTED
                        + " regions of " + regions.size());
            }
            return out;
        }
    }

    Map<String, Object> getRow(String table, String row, List<String> columns, VersionSpec versions)
            throws IOException {
        Get get = new Get(Codec.toBytes(row));
        versions.applyTo(get::readVersions, get::setTimeRange);
        applyColumns(columns, get::addFamily, get::addColumn);

        try (Table t = connection().getTable(TableName.valueOf(table))) {
            Result result = t.get(get);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("table", table);
            out.put("row", row);
            out.put("found", !result.isEmpty());
            out.put("cells", cellsOf(result, true, versions.multiVersion()));
            return out;
        }
    }

    Map<String, Object> scan(String table, String startRow, String stopRow, String prefix,
                             List<String> columns, String rowRegex, int limit,
                             boolean reversed, boolean includeValues, VersionSpec versions)
            throws IOException {
        int effectiveLimit = Math.max(1, Math.min(limit, config.maxRows()));
        Scan scan = new Scan();
        // setLimit bounds rows, not cells: a multi-version scan returns the same number of rows,
        // each carrying up to versions x columns cells.
        scan.setLimit(effectiveLimit);
        scan.setCaching(Math.min(effectiveLimit, 500));
        scan.setReversed(reversed);
        versions.applyTo(scan::readVersions, scan::setTimeRange);

        // Prefix first: it sets a start/stop range that explicit bounds may then narrow.
        if (prefix != null) {
            scan.setStartStopRowForPrefixScan(Codec.toBytes(prefix));
        }
        if (startRow != null) {
            scan.withStartRow(Codec.toBytes(startRow));
        }
        if (stopRow != null) {
            scan.withStopRow(Codec.toBytes(stopRow));
        }
        applyColumns(columns, scan::addFamily, scan::addColumn);

        List<Filter> filters = new ArrayList<>();
        if (rowRegex != null) {
            filters.add(new RowFilter(CompareOperator.EQUAL, new RegexStringComparator(rowRegex)));
        }
        if (!includeValues) {
            filters.add(new KeyOnlyFilter());
        }
        if (!filters.isEmpty()) {
            scan.setFilter(filters.size() == 1 ? filters.get(0)
                    : new FilterList(FilterList.Operator.MUST_PASS_ALL, filters));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        try (Table t = connection().getTable(TableName.valueOf(table));
             ResultScanner scanner = t.getScanner(scan)) {
            for (Result result : scanner) {
                if (result.isEmpty()) {
                    continue;
                }
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("row", Codec.toText(result.getRow()));
                r.put("cells", cellsOf(result, includeValues, versions.multiVersion()));
                rows.add(r);
                if (rows.size() >= effectiveLimit) {
                    break;
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", table);
        out.put("limit", effectiveLimit);
        out.put("row_count", rows.size());
        out.put("truncated", rows.size() >= effectiveLimit);
        out.put("rows", rows);
        return out;
    }

    Map<String, Object> countRows(String table, String prefix, int max) throws IOException {
        int cap = Math.max(1, max);
        Scan scan = new Scan();
        scan.setCaching(1000);
        // Ship back only the first key of each row; the payload never leaves the region server.
        scan.setFilter(new FilterList(FilterList.Operator.MUST_PASS_ALL,
                new FirstKeyOnlyFilter(), new KeyOnlyFilter()));
        if (prefix != null) {
            scan.setStartStopRowForPrefixScan(Codec.toBytes(prefix));
        }

        long count = 0;
        boolean hitCap = false;
        try (Table t = connection().getTable(TableName.valueOf(table));
             ResultScanner scanner = t.getScanner(scan)) {
            while (scanner.next() != null) {
                count++;
                if (count >= cap) {
                    hitCap = true;
                    break;
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", table);
        out.put("count", count);
        out.put("stopped_at_max", hitCap);
        out.put("max", cap);
        return out;
    }

    // ----------------------------------------------------------------- writes

    Map<String, Object> putRow(String table, String row, Map<String, String> cells, Long timestamp)
            throws IOException {
        Put put = new Put(Codec.toBytes(row));
        for (Map.Entry<String, String> entry : cells.entrySet()) {
            String[] parts = splitColumn(entry.getKey(), true);
            byte[] family = Bytes.toBytes(parts[0]);
            byte[] qualifier = parts[1] == null ? new byte[0] : Codec.toBytes(parts[1]);
            byte[] value = Codec.toBytes(entry.getValue() == null ? "" : entry.getValue());
            if (timestamp == null) {
                put.addColumn(family, qualifier, value);
            } else {
                put.addColumn(family, qualifier, timestamp, value);
            }
        }

        try (Table t = connection().getTable(TableName.valueOf(table))) {
            t.put(put);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", table);
        out.put("row", row);
        out.put("cells_written", cells.size());
        return out;
    }

    /** Which versions of the selected columns {@link #deleteRow} removes. */
    enum DeleteVersions {
        /** Every version, or every version at or before a given timestamp. */
        ALL,
        /** Only the newest version of each named column, leaving the ones it replaced. */
        LATEST,
        /** Only the version whose timestamp matches exactly. */
        EXACT;

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }

        static DeleteVersions of(String raw) {
            if (raw == null) {
                return ALL;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "versions must be one of all, latest, exact; got: " + raw);
            }
        }
    }

    Map<String, Object> deleteRow(String table, String row, List<String> columns,
                                  DeleteVersions versions, Long timestamp) throws IOException {
        Delete delete = buildDelete(row, columns, versions, timestamp);
        try (Table t = connection().getTable(TableName.valueOf(table))) {
            t.delete(delete);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", table);
        out.put("row", row);
        out.put("deleted", columns == null || columns.isEmpty() ? "entire row" : columns);
        out.put("versions", versions.label());
        if (timestamp != null) {
            out.put("timestamp", timestamp);
        }
        return out;
    }

    /**
     * Validates a delete request and turns it into the matching HBase {@link Delete}. Separate
     * from issuing it so the mapping from the tool's vocabulary onto HBase's four different
     * delete markers can be read -- and checked -- on its own.
     */
    static Delete buildDelete(String row, List<String> columns,
                              DeleteVersions versions, Long timestamp) {
        boolean wholeRow = columns == null || columns.isEmpty();
        if (versions != DeleteVersions.ALL && wholeRow) {
            throw new IllegalArgumentException("versions \"" + versions.label() + "\" needs an "
                    + "explicit columns list; deleting a whole row always removes every version");
        }
        if (versions == DeleteVersions.EXACT && timestamp == null) {
            throw new IllegalArgumentException("versions \"exact\" needs the timestamp of the "
                    + "version to delete; read the row first to find it");
        }
        if (versions == DeleteVersions.LATEST && timestamp != null) {
            throw new IllegalArgumentException("versions \"latest\" takes no timestamp; use "
                    + "\"exact\" to delete one known version, or \"all\" with a timestamp to "
                    + "delete everything at or before it");
        }

        // A Delete carrying a timestamp applies it to every family and column added afterwards,
        // so this is what "all versions at or before that moment" is built from.
        Delete delete = timestamp != null && versions == DeleteVersions.ALL
                ? new Delete(Codec.toBytes(row), timestamp)
                : new Delete(Codec.toBytes(row));

        if (!wholeRow) {
            for (String column : columns) {
                String[] parts = splitColumn(column, true);
                byte[] family = Bytes.toBytes(parts[0]);
                byte[] qualifier = parts[1] == null ? null : Codec.toBytes(parts[1]);
                switch (versions) {
                    case ALL -> {
                        if (qualifier == null) {
                            delete.addFamily(family);
                        } else {
                            delete.addColumns(family, qualifier);
                        }
                    }
                    case LATEST -> {
                        if (qualifier == null) {
                            throw new IllegalArgumentException("versions \"latest\" needs a "
                                    + "\"family:qualifier\" column rather than a whole family: "
                                    + column);
                        }
                        delete.addColumn(family, qualifier);
                    }
                    case EXACT -> {
                        if (qualifier == null) {
                            delete.addFamilyVersion(family, timestamp);
                        } else {
                            delete.addColumn(family, qualifier, timestamp);
                        }
                    }
                }
            }
        }

        return delete;
    }

    Map<String, Object> createTable(String table, List<Map<String, Object>> families) throws IOException {
        TableName tableName = TableName.valueOf(table);
        TableDescriptorBuilder builder = TableDescriptorBuilder.newBuilder(tableName);
        for (Map<String, Object> family : families) {
            Object rawName = family.get("name");
            if (rawName == null) {
                throw new IllegalArgumentException("Each column family needs a \"name\"");
            }
            ColumnFamilyDescriptorBuilder cf =
                    ColumnFamilyDescriptorBuilder.newBuilder(Bytes.toBytes(String.valueOf(rawName)));
            if (family.get("max_versions") instanceof Number n) {
                cf.setMaxVersions(n.intValue());
            }
            if (family.get("min_versions") instanceof Number n) {
                cf.setMinVersions(n.intValue());
            }
            if (family.get("ttl_seconds") instanceof Number n) {
                cf.setTimeToLive(n.intValue());
            }
            Object compression = family.get("compression");
            if (compression != null) {
                cf.setCompressionType(Compression.Algorithm.valueOf(
                        String.valueOf(compression).toUpperCase()));
            }
            builder.setColumnFamily(cf.build());
        }

        try (Admin admin = connection().getAdmin()) {
            admin.createTable(builder.build());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", tableName.getNameAsString());
        out.put("created", true);
        out.put("column_families", families.stream().map(f -> f.get("name")).toList());
        return out;
    }

    Map<String, Object> dropTable(String table) throws IOException {
        TableName tableName = TableName.valueOf(table);
        try (Admin admin = connection().getAdmin()) {
            if (admin.isTableEnabled(tableName)) {
                admin.disableTable(tableName);
            }
            admin.deleteTable(tableName);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", tableName.getNameAsString());
        out.put("dropped", true);
        return out;
    }

    Map<String, Object> truncateTable(String table, boolean preserveSplits) throws IOException {
        TableName tableName = TableName.valueOf(table);
        try (Admin admin = connection().getAdmin()) {
            if (admin.isTableEnabled(tableName)) {
                admin.disableTable(tableName);
            }
            admin.truncateTable(tableName, preserveSplits);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", tableName.getNameAsString());
        out.put("truncated", true);
        out.put("preserve_splits", preserveSplits);
        return out;
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Flattens a Result into JSON-shaped cells.
     *
     * @param numberVersions add a "version" index per column. Cells arrive grouped by column and
     *                       ordered newest first, so 0 is the current value, 1 the one it
     *                       replaced, and so on. Only meaningful when several versions were
     *                       asked for, so single-version reads leave the field off.
     */
    private static List<Map<String, Object>> cellsOf(Result result, boolean includeValues,
                                                     boolean numberVersions) {
        List<Map<String, Object>> cells = new ArrayList<>();
        byte[] lastFamily = null;
        byte[] lastQualifier = null;
        int version = 0;
        for (Cell cell : result.rawCells()) {
            byte[] family = CellUtil.cloneFamily(cell);
            byte[] qualifier = CellUtil.cloneQualifier(cell);
            version = Arrays.equals(family, lastFamily) && Arrays.equals(qualifier, lastQualifier)
                    ? version + 1 : 0;
            lastFamily = family;
            lastQualifier = qualifier;

            Map<String, Object> c = new LinkedHashMap<>();
            c.put("family", Bytes.toString(family));
            c.put("qualifier", Codec.toText(qualifier));
            c.put("timestamp", cell.getTimestamp());
            if (numberVersions) {
                c.put("version", version);
            }
            // With KeyOnlyFilter the region server strips values, so reporting "" for every
            // cell would be a lie. Omit the field instead.
            if (includeValues) {
                c.put("value", Codec.toText(CellUtil.cloneValue(cell)));
            }
            cells.add(c);
        }
        return cells;
    }

    /** Applies "cf" / "cf:qualifier" selectors to a Get or Scan. */
    private static void applyColumns(List<String> columns,
                                     Consumer<byte[]> addFamily,
                                     BiConsumer<byte[], byte[]> addColumn) {
        if (columns == null) {
            return;
        }
        for (String column : columns) {
            String[] parts = splitColumn(column, false);
            if (parts[1] == null) {
                addFamily.accept(Bytes.toBytes(parts[0]));
            } else {
                addColumn.accept(Bytes.toBytes(parts[0]), Codec.toBytes(parts[1]));
            }
        }
    }

    /**
     * Splits "family" or "family:qualifier"; the qualifier is null when absent. Only the first
     * colon separates, so qualifiers containing colons survive intact.
     *
     * @param qualifierMayBeEmpty when true, a trailing colon means "the empty qualifier"
     *                            (a real thing in HBase) rather than "whole family"
     */
    private static String[] splitColumn(String column, boolean qualifierMayBeEmpty) {
        if (column == null || column.isEmpty()) {
            throw new IllegalArgumentException("Column selector must not be empty");
        }
        int idx = column.indexOf(':');
        if (idx < 0) {
            return new String[] {column, null};
        }
        String family = column.substring(0, idx);
        String qualifier = column.substring(idx + 1);
        if (family.isEmpty()) {
            throw new IllegalArgumentException("Column selector is missing a family: " + column);
        }
        if (qualifier.isEmpty() && !qualifierMayBeEmpty) {
            return new String[] {family, null};
        }
        return new String[] {family, qualifier};
    }

    @Override
    public void close() {
        Connection local = connection;
        if (local != null) {
            try {
                local.close();
            } catch (IOException e) {
                // Nothing useful to do while shutting down.
            }
        }
    }
}
