package com.github.catvod.spider;

import java.util.ArrayList;
import java.util.List;


/**
 * Container index parsers: WebM {@code Cues} and MP4 {@code sidx}.
 *
 * <p>These give exact per-segment start times and byte sizes, which is what lets the SegmentBase
 * bridge convert a player's byte range back into a SABR timestamp without cumulative drift.
 */
final class YTIndex {

    private YTIndex() {
    }

    /* ------------------------------------------------------------------ */
    /* EBML primitives                                                    */
    /* ------------------------------------------------------------------ */

    private static long ebmlUint(byte[] data, int start, int end) {
        long value = 0;
        for (int i = start; i < end && i < data.length; i++) {
            value = (value << 8) | (data[i] & 0xFF);
        }
        return value;
    }

    /** @return {@code {id, nextPos}}; id is -1 when unreadable. */
    private static long[] readId(byte[] data, int pos) {
        if (pos >= data.length) return new long[]{-1, pos};
        int first = data[pos] & 0xFF;
        int mask = 0x80;
        int length = 1;
        while (length <= 4 && (first & mask) == 0) {
            mask >>= 1;
            length++;
        }
        if (length > 4 || pos + length > data.length) return new long[]{-1, data.length};
        long id = 0;
        for (int i = pos; i < pos + length; i++) id = (id << 8) | (data[i] & 0xFF);
        return new long[]{id, pos + length};
    }

    /** @return {@code {size, nextPos}}; size is -1 when unreadable. */
    private static long[] readSize(byte[] data, int pos) {
        if (pos >= data.length) return new long[]{-1, pos};
        int first = data[pos] & 0xFF;
        int mask = 0x80;
        int length = 1;
        while (length <= 8 && (first & mask) == 0) {
            mask >>= 1;
            length++;
        }
        if (length > 8 || pos + length > data.length) return new long[]{-1, data.length};
        long value = first & (mask - 1);
        for (int i = pos + 1; i < pos + length; i++) value = (value << 8) | (data[i] & 0xFF);
        // An all-ones value means "unknown size": treat it as running to the end.
        if (value == (1L << (7 * length)) - 1) value = data.length - (pos + length);
        return new long[]{value, pos + length};
    }

    /* ------------------------------------------------------------------ */
    /* SABR payload cluster split                                         */
    /* ------------------------------------------------------------------ */

    /** One top-level WebM {@code Cluster} copied out of a SABR media payload. */
    static class Cluster {
        final long ptsMs;
        final byte[] data;

        Cluster(long ptsMs, byte[] data) {
            this.ptsMs = ptsMs;
            this.data = data;
        }
    }

    private static final long ID_SEGMENT = 0x18538067L;
    private static final long ID_CLUSTER = 0x1F43B675L;
    private static final int ID_TIMECODE = 0xE7;

    /**
     * Splits a SABR media payload into its top-level WebM Clusters, each with its start time.
     *
     * <p>The micro-segment bridge needs this because one native SABR segment spans several declared
     * MPD windows: each window must receive exactly the clusters whose Timecode falls inside it, so
     * the union over all windows reproduces the payload once — no holes, no duplicates. Timestamps
     * use the default TimecodeScale (1 ms per unit); YouTube streams keep the default.
     *
     * @return the clusters in stream order, or {@code null} when the payload is not a cleanly
     *         parseable cluster sequence (caller then falls back to serving it whole).
     */
    static List<Cluster> splitWebmClusters(byte[] blob) {
        if (blob == null || blob.length == 0) return null;
        List<Cluster> out = new ArrayList<>();
        int pos = walkClusters(blob, 0, blob.length, out, 0);
        if (pos != blob.length || out.isEmpty()) return null;
        return out;
    }

    /**
     * Walks master-element children collecting Clusters; descends into {@code Segment} wrappers.
     *
     * @return the end position on success, -1 on any structural surprise.
     */
    private static int walkClusters(byte[] blob, int start, int end, List<Cluster> out, int depth) {
        if (depth > 2) return -1;
        int pos = start;
        while (pos < end) {
            long[] idParts = readId(blob, pos);
            if (idParts[0] < 0) return -1;
            int idStart = pos;
            long id = idParts[0];
            long[] sizeParts = strictSize(blob, (int) idParts[1]);
            if (sizeParts[0] < 0) return -1;
            int bodyStart = (int) sizeParts[1];
            int bodyEnd = (int) (bodyStart + sizeParts[0]);
            if (bodyEnd > end) return -1;
            if (id == ID_SEGMENT) {
                // Descend instead of skipping: SABR payloads may arrive wrapped in one Segment.
                int inner = walkClusters(blob, bodyStart, bodyEnd, out, depth + 1);
                if (inner != bodyEnd) return -1;
            } else if (id == ID_CLUSTER) {
                Long pts = clusterTimecode(blob, bodyStart, bodyEnd);
                if (pts == null) return -1;
                byte[] copy = new byte[bodyEnd - idStart];
                System.arraycopy(blob, idStart, copy, 0, copy.length);
                out.add(new Cluster(pts, copy));
            }
            pos = bodyEnd;
        }
        return pos;
    }

    /**
     * Like {@link #readSize} but rejects EBML "unknown size" (all-ones): a mid-stream element with
     * no declared length would silently swallow the rest of the payload, so the splitter must bail
     * and let the caller serve the segment whole instead of truncating it.
     */
    private static long[] strictSize(byte[] blob, int pos) {
        if (pos >= blob.length) return new long[]{-1, pos};
        int first = blob[pos] & 0xFF;
        int mask = 0x80;
        int length = 1;
        while (length <= 8 && (first & mask) == 0) {
            mask >>= 1;
            length++;
        }
        if (length > 8 || pos + length > blob.length) return new long[]{-1, pos};
        long value = first & (mask - 1);
        for (int i = pos + 1; i < pos + length; i++) value = (value << 8) | (blob[i] & 0xFF);
        // All-ones means "unknown size" — reject before it can swallow the payload tail.
        if (value == (1L << (7 * length)) - 1) return new long[]{-1, pos};
        return new long[]{value, pos + length};
    }

    /** Finds the Cluster's {@code Timecode} child; {@code null} when absent or malformed. */
    private static Long clusterTimecode(byte[] blob, int start, int end) {
        int pos = start;
        while (pos < end) {
            long[] idParts = readId(blob, pos);
            if (idParts[0] < 0) return null;
            long[] sizeParts = readSize(blob, (int) idParts[1]);
            if (sizeParts[0] < 0) return null;
            int bodyStart = (int) sizeParts[1];
            int bodyEnd = (int) (bodyStart + sizeParts[0]);
            if (bodyEnd > end) return null;
            if (idParts[0] == ID_TIMECODE) {
                if (sizeParts[0] <= 0 || sizeParts[0] > 8) return null;
                return ebmlUint(blob, bodyStart, bodyEnd);
            }
            pos = bodyEnd;
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* SABR payload fragment split (fMP4)                                 */
    /* ------------------------------------------------------------------ */

    private static final int BOX_MOOF = 0x6D6F6F66; // 'moof'
    private static final int BOX_MDAT = 0x6D617464; // 'mdat'
    private static final int BOX_TFDT = 0x74666474; // 'tfdt'
    private static final int BOX_MVHD = 0x6D766864; // 'mvhd'
    private static final int BOX_MDHD = 0x6D646864; // 'mdhd'

    /**
     * Splits an fMP4 SABR media payload into movie fragments, each with its decode start time.
     *
     * <p>MP4 counterpart of {@link #splitWebmClusters}: one fragment group is a contiguous
     * {@code [moof][mdat]} pair (stray {@code free}/{@code sidx}/{@code styp} boxes between
     * fragments are dropped). The fragment's timestamp comes from {@code tfdt.baseMediaDecodeTime}
     * scaled by the track timescale parsed out of the init segment.
     *
     * @return fragments in stream order, or {@code null} when anything is structurally off.
     */
    static List<Cluster> splitMp4Fragments(byte[] init, byte[] blob) {
        long timescale = mp4Timescale(init);
        if (timescale <= 0) return null;
        if (blob == null || blob.length < 16) return null;
        List<Cluster> out = new ArrayList<>();
        int pos = 0;
        while (pos < blob.length) {
            long[] head = mp4Box(blob, pos);
            if (head == null) return null;
            int id = (int) head[0];
            int body = (int) head[1];
            int end = (int) head[2];
            if (id == BOX_MOOF) {
                // Fragment group: moof (+ its mdat, plus anything up to the next moof).
                int groupEnd = end;
                int scan = end;
                while (scan < blob.length) {
                    long[] next = mp4Box(blob, scan);
                    if (next == null) return null;
                    if ((int) next[0] == BOX_MOOF) break;
                    scan = (int) next[2];
                }
                if (scan != groupEnd && scan <= groupEnd) return null;
                groupEnd = Math.max(groupEnd, scan);
                Long pts = tfdtTime(blob, body, end);
                if (pts == null) return null;
                byte[] copy = new byte[groupEnd - pos];
                System.arraycopy(blob, pos, copy, 0, copy.length);
                out.add(new Cluster(pts * 1000 / timescale, copy));
                pos = groupEnd;
                continue;
            }
            pos = end;
        }
        return out.isEmpty() ? null : out;
    }

    /** Reads one ISO-BMFF box header; {@code {id, bodyStart, boxEnd}} or {@code null}. */
    private static long[] mp4Box(byte[] blob, int pos) {
        if (pos < 0 || pos + 8 > blob.length) return null;
        long size = ebmlUint(blob, pos, pos + 4);
        int id = (int) ebmlUint(blob, pos + 4, pos + 8);
        int body = pos + 8;
        if (size == 1) {
            // 64-bit largesize.
            if (pos + 16 > blob.length) return null;
            size = ebmlUint(blob, pos + 8, pos + 16);
            body = pos + 16;
        } else if (size == 0) {
            return null; // "to end" boxes cannot be sliced deterministically.
        }
        if (size < 8 || pos + size > blob.length) return null;
        return new long[]{id, body, pos + size};
    }

    /** Walks {@code moof -> traf -> tfdt} for the fragment's baseMediaDecodeTime. */
    private static Long tfdtTime(byte[] blob, int start, int end) {
        int pos = start;
        while (pos + 8 <= end) {
            long[] head = mp4Box(blob, pos);
            if (head == null || head[2] > end) return null;
            int id = (int) head[0];
            int body = (int) head[1];
            int boxEnd = (int) head[2];
            if (id == BOX_TFDT) {
                if (boxEnd - body < 4) return null;
                int version = blob[body] & 0xFF;
                int timeStart = body + 4;
                int width = version == 1 ? 8 : 4;
                if (timeStart + width > boxEnd) return null;
                return ebmlUint(blob, timeStart, timeStart + width);
            }
            // Descend into child containers of moof/traf; otherwise skip.
            boolean descend = id == BOX_MOOF || id == 0x74726166; // 'traf'
            pos = descend ? body : boxEnd;
        }
        return null;
    }

    /** Track/media timescale from the init segment: mdhd preferred, mvhd fallback. */
    private static long mp4Timescale(byte[] init) {
        if (init == null || init.length < 16) return -1;
        Long found = findTimescale(init, 0, init.length, 0);
        return found == null ? -1 : found;
    }

    private static Long findTimescale(byte[] blob, int start, int end, int depth) {
        if (depth > 4) return null;
        int pos = start;
        while (pos + 8 <= end) {
            long[] head = mp4Box(blob, pos);
            if (head == null || head[2] > end) return null;
            int id = (int) head[0];
            int body = (int) head[1];
            int boxEnd = (int) head[2];
            if (id == BOX_MDHD || id == BOX_MVHD) {
                if (boxEnd - body < 20) return null;
                int version = blob[body] & 0xFF;
                int tsOff = body + 4 + (version == 1 ? 16 : 8);
                if (tsOff + 4 > boxEnd) return null;
                return ebmlUint(blob, tsOff, tsOff + 4);
            }
            Long nested = findTimescale(blob, body, boxEnd, depth + 1);
            if (nested != null) return nested;
            pos = boxEnd;
        }
        return null;
    }
}