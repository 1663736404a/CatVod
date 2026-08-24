package com.github.catvod.spider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    private static final int ID_CUE_POINT = 0xBB;
    private static final long ID_CUES = 0x1C53BB6BL;
    private static final int ID_CUE_TIME = 0xB3;
    private static final int ID_CUE_TRACK_POSITIONS = 0xB7;
    private static final int ID_CUE_CLUSTER_POSITION = 0xF1;

    private static final class CuePoint {
        Long t;
        Long pos;
    }

    /**
     * Parses WebM {@code Cues} into a segment timeline.
     *
     * <p>Collects both {@code CueTime} (presentation time) and {@code CueClusterPosition} (the
     * Cluster's absolute byte offset in the direct URL); the byte offset is what the SegmentBase
     * bridge needs to map a player Range back to a timestamp.
     */
    static List<YTFormat.Seg> parseWebmCues(byte[] blob, long totalMs) {
        List<CuePoint> points = new ArrayList<>();
        walk(blob, 0, blob.length, points);
        List<CuePoint> valid = new ArrayList<>();
        for (CuePoint p : points) if (p.t != null) valid.add(p);
        valid.sort((a, b) -> Long.compare(a.t, b.t));
        // Dedupe: one CueTime may carry several CueTrackPositions.
        List<CuePoint> deduped = new ArrayList<>();
        for (CuePoint p : valid) {
            if (!deduped.isEmpty() && deduped.get(deduped.size() - 1).t.longValue() == p.t.longValue()) continue;
            deduped.add(p);
        }
        List<YTFormat.Seg> out = new ArrayList<>();
        if (deduped.isEmpty()) return out;
        for (int i = 0; i < deduped.size(); i++) {
            CuePoint p = deduped.get(i);
            long t = p.t;
            long next = i + 1 < deduped.size() ? deduped.get(i + 1).t : Math.max(0, totalMs);
            long d = next - t;
            if (d <= 0) d = out.isEmpty() ? 6000 : out.get(out.size() - 1).d;
            YTFormat.Seg seg = new YTFormat.Seg();
            seg.t = t;
            seg.d = d;
            if (p.pos != null) {
                seg.off = p.pos;
                if (i + 1 < deduped.size() && deduped.get(i + 1).pos != null) {
                    seg.sz = Math.max(1, deduped.get(i + 1).pos - p.pos);
                }
            }
            out.add(seg);
        }
        return out;
    }

    private static void walk(byte[] blob, int start, int end, List<CuePoint> points) {
        int pos = start;
        while (pos < end) {
            long[] id = readId(blob, pos);
            if (id[0] < 0) break;
            long[] size = readSize(blob, (int) id[1]);
            if (size[0] < 0) break;
            int childEnd = (int) (size[1] + size[0]);
            if (childEnd > blob.length) break;
            if (id[0] == ID_CUE_POINT) {
                CuePoint entry = new CuePoint();
                walkCuePoint(blob, (int) size[1], childEnd, entry);
                if (entry.t != null) points.add(entry);
            } else if (id[0] == ID_CUES) {
                walk(blob, (int) size[1], childEnd, points);
            }
            pos = childEnd;
        }
    }

    /** Reads {@code CueTime} and {@code CueClusterPosition} inside one CuePoint. */
    private static void walkCuePoint(byte[] blob, int start, int end, CuePoint entry) {
        int pos = start;
        while (pos < end) {
            long[] id = readId(blob, pos);
            if (id[0] < 0) break;
            long[] size = readSize(blob, (int) id[1]);
            if (size[0] < 0) break;
            int childEnd = (int) (size[1] + size[0]);
            if (childEnd > blob.length) break;
            if (id[0] == ID_CUE_TIME) {
                entry.t = ebmlUint(blob, (int) size[1], childEnd);
            } else if (id[0] == ID_CUE_TRACK_POSITIONS) {
                walkCuePoint(blob, (int) size[1], childEnd, entry);
            } else if (id[0] == ID_CUE_CLUSTER_POSITION) {
                entry.pos = ebmlUint(blob, (int) size[1], childEnd);
            }
            pos = childEnd;
        }
    }

    /* ------------------------------------------------------------------ */
    /* MP4 sidx                                                           */
    /* ------------------------------------------------------------------ */

    private static long u32(byte[] b, int pos) {
        if (pos + 4 > b.length) return 0;
        return ((long) (b[pos] & 0xFF) << 24) | ((long) (b[pos + 1] & 0xFF) << 16)
                | ((long) (b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
    }

    private static long u64(byte[] b, int pos) {
        long value = 0;
        for (int i = pos; i < pos + 8 && i < b.length; i++) value = (value << 8) | (b[i] & 0xFF);
        return value;
    }

    private static int u16(byte[] b, int pos) {
        if (pos + 2 > b.length) return 0;
        return ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
    }

    /**
     * Parses an MP4 {@code sidx} box into a segment timeline.
     *
     * <p>{@code referenced_size} is each segment's byte length in the direct URL, kept as
     * {@code sz} alongside a running {@code off} so byte ranges can be mapped back to time.
     */
    static List<YTFormat.Seg> parseMp4Sidx(byte[] blob, long totalMs) {
        List<YTFormat.Seg> out = new ArrayList<>();
        int pos = 0;
        while (pos + 8 <= blob.length) {
            long size = u32(blob, pos);
            String type = new String(blob, pos + 4, Math.min(4, blob.length - pos - 4)).trim();
            int head = 8;
            if (size == 1 && pos + 16 <= blob.length) {
                size = u64(blob, pos + 8);
                head = 16;
            }
            if (size < head || pos + size > blob.length) break;
            if ("sidx".equals(type)) {
                int bStart = pos + head;
                int bLen = (int) (size - head);
                if (bLen < 20) return out;
                byte[] b = new byte[bLen];
                System.arraycopy(blob, bStart, b, 0, bLen);
                int version = b[0] & 0xFF;
                long timescale = u32(b, 8);
                if (timescale == 0) return out;
                int off = 12;
                long earliest;
                if (version == 0) {
                    earliest = u32(b, off);
                    off += 8;
                } else {
                    earliest = u64(b, off);
                    off += 16;
                }
                off += 2;
                if (off + 2 > b.length) return out;
                int count = u16(b, off);
                off += 2;
                long tick = earliest;
                long byteOffset = 0;
                for (int i = 0; i < count; i++) {
                    if (off + 12 > b.length) break;
                    long ref = u32(b, off);
                    long dur = u32(b, off + 4);
                    off += 12;
                    long refSize = ref & 0x7fffffffL;
                    if ((ref & 0x80000000L) == 0) {
                        YTFormat.Seg seg = new YTFormat.Seg();
                        seg.t = Math.round(tick * 1000.0 / timescale);
                        seg.d = Math.max(1, Math.round(dur * 1000.0 / timescale));
                        seg.sz = refSize;
                        seg.off = byteOffset;
                        out.add(seg);
                        byteOffset += refSize;
                    }
                    tick += dur;
                }
                return out;
            }
            pos += (int) size;
        }
        return out;
    }

    /**
     * Converts a byte position in the direct URL into a timeline timestamp.
     *
     * <p>Exact because sidx/Cues give real byte lengths, unlike guessing {@code $Number$} from a
     * fixed segment duration.
     *
     * @return the segment start in ms, or {@code null} when the timeline is empty.
     */
    static Long timeForByte(List<YTFormat.Seg> timeline, Long bytePos, long indexEnd) {
        if (timeline == null || timeline.isEmpty() || bytePos == null) return null;
        long target = bytePos;
        // sidx offsets are relative to the start of media data, while WebM CueClusterPosition is
        // an absolute file position. The former needs the index end as its base.
        long base = 0;
        YTFormat.Seg first = timeline.get(0);
        if (first.off == 0 && indexEnd > 0) base = indexEnd + 1;
        for (YTFormat.Seg entry : timeline) {
            if (entry.off < 0) continue;
            long segStart = base + entry.off;
            if (entry.sz <= 0) {
                if (target >= segStart) continue;
                return entry.t;
            }
            if (segStart <= target && target < segStart + entry.sz) return entry.t;
        }
        // Past the last segment: return its start rather than failing the request.
        for (int i = timeline.size() - 1; i >= 0; i--) {
            if (timeline.get(i).off >= 0) return timeline.get(i).t;
        }
        return null;
    }

    static long totalBytes(List<YTFormat.Seg> timeline) {
        long total = 0;
        if (timeline == null) return 0;
        for (YTFormat.Seg entry : timeline) total += Math.max(0, entry.sz);
        return total;
    }

    /** Renders a SegmentTimeline, run-length encoding runs of equal durations. */
    static String segmentTimelineXml(List<YTFormat.Seg> timeline) {
        if (timeline == null || timeline.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < timeline.size()) {
            long t = timeline.get(i).t;
            long d = Math.max(1, timeline.get(i).d);
            int r = 0;
            while (i + r + 1 < timeline.size()) {
                YTFormat.Seg cur = timeline.get(i + r);
                YTFormat.Seg next = timeline.get(i + r + 1);
                if (next.d != d || next.t != cur.t + cur.d) break;
                r++;
            }
            sb.append(String.format(Locale.US, "<S t=\"%d\" d=\"%d\"", t, d));
            if (r > 0) sb.append(String.format(Locale.US, " r=\"%d\"", r));
            sb.append("/>");
            i += r + 1;
        }
        return sb.toString();
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
            long[] sizeParts = strictSize(blob, idParts[1]);
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
            long[] sizeParts = readSize(blob, idParts[1]);
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
}