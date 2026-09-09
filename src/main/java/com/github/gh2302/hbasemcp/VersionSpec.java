package com.github.gh2302.hbasemcp;

import org.apache.hadoop.hbase.HConstants;

import java.io.IOException;

/**
 * Which versions of each cell a read should return.
 *
 * <p>HBase keeps every write of a column as a separate timestamped version, up to the family's
 * {@code VERSIONS} setting, and by default a read sees only the newest one. This carries the
 * three things that change that: how many versions to return, and either an exact timestamp or a
 * half-open time window to restrict them to.
 *
 * <p>Validation happens in the constructor, so an impossible combination is reported to the
 * caller as a bad argument rather than surfacing later as an opaque HBase error.
 *
 * @param versions  how many versions of each cell to return, newest first; at least 1
 * @param timestamp exact cell timestamp, or null; mutually exclusive with the window
 * @param startTime start of the window in epoch milliseconds, inclusive, or null for no bound
 * @param endTime   end of the window in epoch milliseconds, exclusive, or null for no bound
 */
record VersionSpec(int versions, Long timestamp, Long startTime, Long endTime) {

    /** The newest version of each cell and nothing else -- HBase's own default. */
    static final VersionSpec LATEST = new VersionSpec(1, null, null, null);

    /**
     * {@code setTimeRange} as declared on both Get and Scan. They share no supertype that
     * declares it, so the call sites pass a method reference instead.
     */
    @FunctionalInterface
    interface TimeRangeSetter {
        void set(long minStampInclusive, long maxStampExclusive) throws IOException;
    }

    /** {@code readVersions}, likewise. Get's overload throws; Scan's does not. */
    @FunctionalInterface
    interface VersionCountSetter {
        void set(int versions) throws IOException;
    }

    VersionSpec {
        if (versions < 1) {
            throw new IllegalArgumentException("versions must be at least 1, got: " + versions);
        }
        if (timestamp != null && (startTime != null || endTime != null)) {
            throw new IllegalArgumentException(
                    "Use either timestamp or start_time/end_time, not both");
        }
        // A timestamp is applied as the one-millisecond window [ts, ts+1), which is what
        // Get.setTimestamp does internally; reject the value that would overflow it.
        if (timestamp != null && timestamp == HConstants.LATEST_TIMESTAMP) {
            throw new IllegalArgumentException("timestamp must be below " + HConstants.LATEST_TIMESTAMP);
        }
        requireNonNegative("timestamp", timestamp);
        requireNonNegative("start_time", startTime);
        requireNonNegative("end_time", endTime);
        if (startTime != null && endTime != null && startTime >= endTime) {
            throw new IllegalArgumentException("start_time (" + startTime + ") must be before "
                    + "end_time (" + endTime + "), which is exclusive");
        }
    }

    /** True when a read may return more than one version of the same column. */
    boolean multiVersion() {
        return versions > 1;
    }

    /**
     * Applies this spec to a Get or Scan, e.g. {@code spec.applyTo(get::readVersions, get::setTimeRange)}.
     * A request for the default single latest version touches neither setter.
     */
    void applyTo(VersionCountSetter readVersions, TimeRangeSetter setTimeRange) throws IOException {
        if (versions > 1) {
            readVersions.set(versions);
        }
        if (timestamp != null) {
            setTimeRange.set(timestamp, timestamp + 1);
        } else if (startTime != null || endTime != null) {
            setTimeRange.set(startTime == null ? 0L : startTime,
                    endTime == null ? HConstants.LATEST_TIMESTAMP : endTime);
        }
    }

    private static void requireNonNegative(String name, Long value) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(
                    name + " must be a non-negative epoch-millisecond timestamp, got: " + value);
        }
    }
}
