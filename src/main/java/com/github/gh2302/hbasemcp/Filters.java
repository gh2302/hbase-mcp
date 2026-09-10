package com.github.gh2302.hbasemcp;

import org.apache.hadoop.hbase.CompareOperator;
import org.apache.hadoop.hbase.filter.BinaryComparator;
import org.apache.hadoop.hbase.filter.BinaryPrefixComparator;
import org.apache.hadoop.hbase.filter.ByteArrayComparable;
import org.apache.hadoop.hbase.filter.ColumnPaginationFilter;
import org.apache.hadoop.hbase.filter.ColumnPrefixFilter;
import org.apache.hadoop.hbase.filter.ColumnRangeFilter;
import org.apache.hadoop.hbase.filter.FamilyFilter;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.FirstKeyOnlyFilter;
import org.apache.hadoop.hbase.filter.KeyOnlyFilter;
import org.apache.hadoop.hbase.filter.MultipleColumnPrefixFilter;
import org.apache.hadoop.hbase.filter.ParseFilter;
import org.apache.hadoop.hbase.filter.PrefixFilter;
import org.apache.hadoop.hbase.filter.QualifierFilter;
import org.apache.hadoop.hbase.filter.RandomRowFilter;
import org.apache.hadoop.hbase.filter.RegexStringComparator;
import org.apache.hadoop.hbase.filter.RowFilter;
import org.apache.hadoop.hbase.filter.SingleColumnValueExcludeFilter;
import org.apache.hadoop.hbase.filter.SingleColumnValueFilter;
import org.apache.hadoop.hbase.filter.SubstringComparator;
import org.apache.hadoop.hbase.filter.TimestampsFilter;
import org.apache.hadoop.hbase.filter.ValueFilter;
import org.apache.hadoop.hbase.util.Bytes;

import java.nio.charset.CharacterCodingException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds HBase server-side filters from tool arguments.
 *
 * <p>Two ways in, because they suit different callers. A structured object describes one filter
 * per JSON node, which a model can assemble from the tool schema alone. A filter string is the
 * expression the HBase shell takes, which someone who already knows HBase can paste unchanged.
 * Both may be given at once; everything is combined with AND.
 *
 * <p>Filters run on the region server, so rows and cells that fail them are never shipped.
 */
final class Filters {

    /** How deeply a structured filter may nest. A guard against pathological input, nothing more. */
    private static final int MAX_DEPTH = 8;

    private Filters() {}

    /**
     * The filter arguments one read carries. Every part is optional; several are ANDed together.
     *
     * @param structured a filter node, as described by the tool schema
     * @param expression an HBase shell filter expression
     * @param rowRegex   the row_regex argument, a regular expression over the row key
     */
    record Spec(Object structured, String expression, String rowRegex) {

        static final Spec NONE = new Spec(null, null, null);

        /** True when this asks for no filtering at all. */
        boolean isEmpty() {
            return structured == null && expression == null && rowRegex == null;
        }

        /** The single filter these arguments amount to, or null when there is nothing to apply. */
        Filter build() {
            List<Filter> parts = new ArrayList<>(3);
            if (rowRegex != null) {
                parts.add(new RowFilter(CompareOperator.EQUAL, new RegexStringComparator(rowRegex)));
            }
            if (structured != null) {
                parts.add(node(structured, 0));
            }
            if (expression != null) {
                parts.add(parse(expression));
            }
            return all(parts);
        }
    }

    /** ANDs filters together, collapsing the trivial cases. */
    static Filter all(List<Filter> parts) {
        return switch (parts.size()) {
            case 0 -> null;
            case 1 -> parts.get(0);
            default -> new FilterList(FilterList.Operator.MUST_PASS_ALL, parts);
        };
    }

    // ------------------------------------------------- what a filter has to read

    /**
     * True when every filter in the tree decides on the row key alone.
     *
     * <p>Such a filter never looks at a cell, so a count under it can stop each row at its first
     * one -- the rest would be shipped only to be thrown away. Anything unrecognised is treated
     * as cell-inspecting, which is the answer that cannot be wrong.
     */
    static boolean rowKeyOnly(Filter filter) {
        if (filter == null) {
            return true;
        }
        if (filter instanceof FilterList list) {
            for (Filter child : list.getFilters()) {
                if (!rowKeyOnly(child)) {
                    return false;
                }
            }
            return true;
        }
        return filter instanceof RowFilter
                || filter instanceof PrefixFilter
                || filter instanceof RandomRowFilter
                || filter instanceof FirstKeyOnlyFilter
                || filter instanceof KeyOnlyFilter;
    }

    /**
     * The columns a cell-inspecting filter actually needs, or null when that cannot be settled.
     *
     * <p>Restricting the scan to them makes the region server open fewer stores and ship one cell
     * per row instead of every column the row happens to have. It is only sound when each part of
     * the filter either ignores cells entirely or is a column test that drops rows missing the
     * column, and only under AND: under OR a row could pass on its key alone while carrying none
     * of these columns, and a scan narrowed to them would never surface it.
     */
    static List<byte[][]> requiredColumns(Filter filter) {
        Map<String, byte[][]> columns = new LinkedHashMap<>();
        if (!collectColumns(filter, columns) || columns.isEmpty()) {
            return null;
        }
        return new ArrayList<>(columns.values());
    }

    private static boolean collectColumns(Filter filter, Map<String, byte[][]> into) {
        if (rowKeyOnly(filter)) {
            return true;
        }
        if (filter instanceof FilterList list) {
            if (list.getOperator() != FilterList.Operator.MUST_PASS_ALL) {
                return false;
            }
            for (Filter child : list.getFilters()) {
                if (!collectColumns(child, into)) {
                    return false;
                }
            }
            return true;
        }
        // The exclude variant strips the very column it tests from the row it keeps, so a scan
        // narrowed to that column would leave the row with no cells at all and lose it.
        if (filter instanceof SingleColumnValueExcludeFilter) {
            return false;
        }
        // getFilterIfMissing is the whole question: with it off the filter keeps rows that lack
        // the column, and a scan restricted to that column returns nothing for them at all.
        if (filter instanceof SingleColumnValueFilter column && column.getFilterIfMissing()) {
            byte[] family = column.getFamily();
            byte[] qualifier = column.getQualifier();
            into.put(Bytes.toStringBinary(family) + ":" + Bytes.toStringBinary(qualifier),
                    new byte[][] {family, qualifier});
            return true;
        }
        return false;
    }

    /** Parses an HBase shell filter expression with HBase's own parser. */
    private static Filter parse(String expression) {
        try {
            return new ParseFilter().parseFilterString(expression);
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("filter_string is not valid text: " + e.getMessage());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "filter_string could not be parsed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------- structured nodes

    private static Filter node(Object raw, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("filter nests deeper than " + MAX_DEPTH + " levels");
        }
        Map<String, Object> f = asMap(raw);
        String type = requireString(f, "type").trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "and" -> combine(FilterList.Operator.MUST_PASS_ALL, f, depth);
            case "or" -> combine(FilterList.Operator.MUST_PASS_ONE, f, depth);
            case "row" -> new RowFilter(compare(f), comparand(f));
            case "row_prefix" -> new PrefixFilter(Codec.toBytes(requireString(f, "prefix")));
            case "family" -> new FamilyFilter(compare(f), comparand(f));
            case "qualifier" -> new QualifierFilter(compare(f), comparand(f));
            case "qualifier_prefix" -> qualifierPrefix(f);
            case "qualifier_range" -> new ColumnRangeFilter(
                    optBytes(f, "start"), bool(f, "start_inclusive", true),
                    optBytes(f, "stop"), bool(f, "stop_inclusive", false));
            case "value" -> new ValueFilter(compare(f), comparand(f));
            case "column_value" -> columnValue(f);
            case "first_key_only" -> new FirstKeyOnlyFilter();
            case "key_only" -> new KeyOnlyFilter();
            case "column_count" -> new ColumnPaginationFilter(
                    positiveInt(f, "limit"), nonNegativeInt(f, "offset"));
            case "timestamps" -> new TimestampsFilter(timestamps(f));
            case "random_row" -> new RandomRowFilter(chance(f));
            default -> throw new IllegalArgumentException("Unknown filter type: " + type);
        };
    }

    private static Filter combine(FilterList.Operator operator, Map<String, Object> f, int depth) {
        Object raw = f.get("filters");
        if (!(raw instanceof List<?> items) || items.isEmpty()) {
            throw new IllegalArgumentException(
                    "An and/or filter needs a non-empty \"filters\" array");
        }
        List<Filter> children = new ArrayList<>(items.size());
        for (Object item : items) {
            children.add(node(item, depth + 1));
        }
        return children.size() == 1 ? children.get(0) : new FilterList(operator, children);
    }

    /**
     * SingleColumnValueFilter tests one column and keeps or drops the whole row on the result.
     *
     * <p>Two of its defaults are inverted here. HBase keeps rows that do not have the column at
     * all, which is rarely what someone asking for "status = SENT" means, so if_missing defaults
     * to dropping them. And the test looks at the newest version only, so an old value cannot
     * bring a row back.
     */
    private static Filter columnValue(Map<String, Object> f) {
        String column = requireString(f, "column");
        String[] parts = HBaseService.splitColumn(column, true);
        if (parts[1] == null) {
            throw new IllegalArgumentException(
                    "column_value needs a \"family:qualifier\" column, got: " + column);
        }
        byte[] family = Bytes.toBytes(parts[0]);
        byte[] qualifier = Codec.toBytes(parts[1]);
        CompareOperator operator = compare(f);
        ByteArrayComparable comparand = comparand(f);

        SingleColumnValueFilter filter = bool(f, "exclude_column", false)
                ? new SingleColumnValueExcludeFilter(family, qualifier, operator, comparand)
                : new SingleColumnValueFilter(family, qualifier, operator, comparand);
        filter.setFilterIfMissing(bool(f, "if_missing", true));
        filter.setLatestVersionOnly(bool(f, "latest_version_only", true));
        return filter;
    }

    private static Filter qualifierPrefix(Map<String, Object> f) {
        List<String> prefixes = new ArrayList<>();
        String single = optString(f, "prefix");
        if (single != null) {
            prefixes.add(single);
        }
        List<String> many = stringList(f, "prefixes");
        if (many != null) {
            prefixes.addAll(many);
        }
        if (prefixes.isEmpty()) {
            throw new IllegalArgumentException("qualifier_prefix needs prefix or prefixes");
        }
        if (prefixes.size() == 1) {
            return new ColumnPrefixFilter(Codec.toBytes(prefixes.get(0)));
        }
        byte[][] out = new byte[prefixes.size()][];
        for (int i = 0; i < prefixes.size(); i++) {
            out[i] = Codec.toBytes(prefixes.get(i));
        }
        return new MultipleColumnPrefixFilter(out);
    }

    /** Exactly one comparand, which also decides how the comparison is made. */
    private static ByteArrayComparable comparand(Map<String, Object> f) {
        String value = optString(f, "value");
        String prefix = optString(f, "prefix");
        String substring = optString(f, "substring");
        String regex = optString(f, "regex");

        int given = (value == null ? 0 : 1) + (prefix == null ? 0 : 1)
                + (substring == null ? 0 : 1) + (regex == null ? 0 : 1);
        if (given != 1) {
            throw new IllegalArgumentException(
                    "A comparison needs exactly one of value, prefix, substring or regex, got "
                            + given);
        }
        if (value != null) {
            return new BinaryComparator(Codec.toBytes(value));
        }
        if (prefix != null) {
            return new BinaryPrefixComparator(Codec.toBytes(prefix));
        }
        if (substring != null) {
            return new SubstringComparator(substring);
        }
        return new RegexStringComparator(regex);
    }

    private static CompareOperator compare(Map<String, Object> f) {
        String raw = optString(f, "compare");
        if (raw == null) {
            return CompareOperator.EQUAL;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "eq" -> CompareOperator.EQUAL;
            case "ne" -> CompareOperator.NOT_EQUAL;
            case "lt" -> CompareOperator.LESS;
            case "lte" -> CompareOperator.LESS_OR_EQUAL;
            case "gt" -> CompareOperator.GREATER;
            case "gte" -> CompareOperator.GREATER_OR_EQUAL;
            default -> throw new IllegalArgumentException(
                    "compare must be one of eq, ne, lt, lte, gt, gte; got: " + raw);
        };
    }

    private static List<Long> timestamps(Map<String, Object> f) {
        Object raw = f.get("timestamps");
        if (!(raw instanceof List<?> items) || items.isEmpty()) {
            throw new IllegalArgumentException(
                    "A timestamps filter needs a non-empty \"timestamps\" array");
        }
        List<Long> out = new ArrayList<>(items.size());
        for (Object item : items) {
            if (item instanceof Number n) {
                out.add(n.longValue());
            } else {
                try {
                    out.add(Long.parseLong(String.valueOf(item).trim()));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "timestamps must be epoch milliseconds, got: " + item);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------- argument reading

    private static Map<String, Object> asMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("A filter must be an object, got: " + raw);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static String requireString(Map<String, Object> f, String key) {
        String value = optString(f, key);
        if (value == null) {
            throw new IllegalArgumentException("Filter is missing required field: " + key);
        }
        return value;
    }

    private static String optString(Map<String, Object> f, String key) {
        Object value = f.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? null : text;
    }

    private static byte[] optBytes(Map<String, Object> f, String key) {
        return Codec.toBytes(optString(f, key));
    }

    private static List<String> stringList(Map<String, Object> f, String key) {
        Object value = f.get(key);
        if (!(value instanceof List<?> items) || items.isEmpty()) {
            return null;
        }
        List<String> out = new ArrayList<>(items.size());
        for (Object item : items) {
            if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static boolean bool(Map<String, Object> f, String key, boolean fallback) {
        Object value = f.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value).trim());
    }

    private static int positiveInt(Map<String, Object> f, String key) {
        int value = intOf(f, key, -1);
        if (value < 1) {
            throw new IllegalArgumentException(key + " must be at least 1");
        }
        return value;
    }

    private static int nonNegativeInt(Map<String, Object> f, String key) {
        int value = intOf(f, key, 0);
        if (value < 0) {
            throw new IllegalArgumentException(key + " must not be negative");
        }
        return value;
    }

    private static int intOf(Map<String, Object> f, String key, int fallback) {
        Object value = f.get(key);
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

    private static float chance(Map<String, Object> f) {
        Object value = f.get("chance");
        if (value == null) {
            throw new IllegalArgumentException("random_row needs a chance between 0 and 1");
        }
        float chance;
        if (value instanceof Number n) {
            chance = n.floatValue();
        } else {
            try {
                chance = Float.parseFloat(String.valueOf(value).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("chance must be a number, got: " + value);
            }
        }
        if (chance < 0f || chance > 1f) {
            throw new IllegalArgumentException("chance must be between 0 and 1, got: " + chance);
        }
        return chance;
    }
}
