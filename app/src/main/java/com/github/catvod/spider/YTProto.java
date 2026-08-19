package com.github.catvod.spider;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal protobuf writer/reader plus UMP (YouTube Universal Media Playback) framing.
 *
 * <p>Only the field types used by {@code VideoPlaybackAbrRequest} and the SABR response
 * stream are implemented. This is deliberately not a general protobuf runtime.
 */
final class YTProto {

    static final byte[] EMPTY = new byte[0];

    private YTProto() {
    }

    /* ------------------------------------------------------------------ */
    /* writers                                                            */
    /* ------------------------------------------------------------------ */

    static byte[] varint(long value) {
        if (value < 0) value = 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream(10);
        while (true) {
            int b = (int) (value & 0x7f);
            value >>>= 7;
            if (value != 0) {
                out.write(b | 0x80);
            } else {
                out.write(b);
                break;
            }
        }
        return out.toByteArray();
    }

    static byte[] key(int field, int wireType) {
        return varint(((long) field << 3) | wireType);
    }

    static byte[] pbInt(int field, Long value) {
        if (value == null) return EMPTY;
        return concat(key(field, 0), varint(value));
    }

    static byte[] pbInt(int field, long value) {
        return concat(key(field, 0), varint(value));
    }

    static byte[] pbBool(int field, boolean value) {
        return concat(key(field, 0), new byte[]{(byte) (value ? 1 : 0)});
    }

    static byte[] pbBytes(int field, byte[] value) {
        if (value == null) return EMPTY;
        return concat(key(field, 2), varint(value.length), value);
    }

    static byte[] pbStr(int field, String value) {
        if (value == null || value.isEmpty()) return EMPTY;
        return pbBytes(field, value.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] pbMsg(int field, byte[] payload) {
        if (payload == null || payload.length == 0) return EMPTY;
        return concat(key(field, 2), varint(payload.length), payload);
    }

    static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) if (part != null) total += part.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] part : parts) {
            if (part == null) continue;
            System.arraycopy(part, 0, out, pos, part.length);
            pos += part.length;
        }
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* readers                                                            */
    /* ------------------------------------------------------------------ */

    /** @return {@code {value, nextPos}}, or {@code null} once the buffer is exhausted. */
    static long[] readVarint(byte[] data, int pos) {
        int shift = 0;
        long result = 0;
        while (pos < data.length) {
            int b = data[pos] & 0xFF;
            pos++;
            result |= ((long) (b & 0x7f)) << shift;
            if ((b & 0x80) == 0) return new long[]{result, pos};
            shift += 7;
        }
        return null;
    }

    static int skipValue(byte[] data, int pos, int wireType) {
        if (wireType == 0) {
            long[] read = readVarint(data, pos);
            return read == null ? data.length : (int) read[1];
        }
        if (wireType == 1) return Math.min(data.length, pos + 8);
        if (wireType == 2) {
            long[] read = readVarint(data, pos);
            if (read == null) return data.length;
            return (int) Math.min(data.length, read[1] + Math.max(0, read[0]));
        }
        if (wireType == 5) return Math.min(data.length, pos + 4);
        return data.length;
    }

    static byte[] getBytes(byte[] data, int field) {
        if (data == null) return null;
        int pos = 0;
        while (pos < data.length) {
            long[] k = readVarint(data, pos);
            if (k == null) break;
            pos = (int) k[1];
            int fn = (int) (k[0] >> 3);
            int wt = (int) (k[0] & 7);
            if (fn == field && wt == 2) {
                long[] size = readVarint(data, pos);
                if (size == null) break;
                int start = (int) size[1];
                int end = (int) Math.min(data.length, start + Math.max(0, size[0]));
                byte[] out = new byte[Math.max(0, end - start)];
                if (out.length > 0) System.arraycopy(data, start, out, 0, out.length);
                return out;
            }
            pos = skipValue(data, pos, wt);
        }
        return null;
    }

    static Long getInt(byte[] data, int field) {
        if (data == null) return null;
        int pos = 0;
        while (pos < data.length) {
            long[] k = readVarint(data, pos);
            if (k == null) break;
            pos = (int) k[1];
            int fn = (int) (k[0] >> 3);
            int wt = (int) (k[0] & 7);
            if (fn == field && wt == 0) {
                long[] value = readVarint(data, pos);
                return value == null ? null : value[0];
            }
            pos = skipValue(data, pos, wt);
        }
        return null;
    }

    static String getStr(byte[] data, int field) {
        byte[] value = getBytes(data, field);
        if (value == null) return null;
        try {
            return new String(value, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Reads a repeated integer field, accepting both packed and unpacked encodings. */
    static List<Long> getIntList(byte[] data, int field) {
        List<Long> out = new ArrayList<>();
        if (data == null || data.length == 0) return out;
        int pos = 0;
        while (pos < data.length) {
            long[] k = readVarint(data, pos);
            if (k == null) break;
            pos = (int) k[1];
            int fn = (int) (k[0] >> 3);
            int wt = (int) (k[0] & 7);
            if (fn == field && wt == 0) {
                long[] value = readVarint(data, pos);
                if (value == null) break;
                out.add(value[0]);
                pos = (int) value[1];
                continue;
            }
            if (fn == field && wt == 2) {
                long[] size = readVarint(data, pos);
                if (size == null) break;
                int start = (int) size[1];
                int end = (int) Math.min(data.length, start + Math.max(0, size[0]));
                int inner = start;
                while (inner < end) {
                    long[] value = readVarint(data, inner);
                    if (value == null) break;
                    out.add(value[0]);
                    inner = (int) value[1];
                }
                pos = end;
                continue;
            }
            pos = skipValue(data, pos, wt);
        }
        return out;
    }

    static byte[] b64urlDecode(String value) {
        if (value == null || value.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(value);
        int pad = (4 - sb.length() % 4) % 4;
        for (int i = 0; i < pad; i++) sb.append('=');
        String padded = sb.toString();
        try {
            return Base64.decode(padded, Base64.URL_SAFE | Base64.NO_WRAP);
        } catch (Throwable e) {
            try {
                return Base64.decode(padded, Base64.DEFAULT);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* UMP framing                                                        */
    /* ------------------------------------------------------------------ */

    private static int umpSize(int prefix) {
        return prefix < 128 ? 1 : prefix < 192 ? 2 : prefix < 224 ? 3 : prefix < 240 ? 4 : 5;
    }

    /**
     * Reads one UMP varint from a stream, mirroring yt-dlp {@code _streaming/ump.py::read_varint}.
     *
     * <p>A UMP varint is neither a protobuf varint nor a big-endian concatenation. Parsing it
     * with big-endian leading bits decodes SABR responses into a flood of {@code part_id=0}
     * records with zero-length media.
     *
     * @return the value, or -1 at end of stream.
     */
    static long readUmpVarint(InputStream in) throws IOException {
        int first = in.read();
        if (first < 0) return -1;
        int prefix = first & 0xFF;
        int size = umpSize(prefix);
        long result = 0;
        int shift = 0;
        if (size != 5) {
            shift = 8 - size;
            long mask = (1L << shift) - 1;
            result |= prefix & mask;
        }
        for (int i = 1; i < size; i++) {
            int b = in.read();
            if (b < 0) return -1;
            result |= ((long) (b & 0xFF)) << shift;
            shift += 8;
        }
        return result;
    }

    /** @return {@code {value, nextPos}}; value is -1 when the buffer is too short. */
    static long[] readUmpVarint(byte[] data, int pos) {
        if (data == null || pos >= data.length) return new long[]{-1, pos};
        int prefix = data[pos] & 0xFF;
        pos++;
        int size = umpSize(prefix);
        long result = 0;
        int shift = 0;
        if (size != 5) {
            shift = 8 - size;
            result = prefix & ((1L << shift) - 1);
        }
        for (int i = 1; i < size; i++) {
            if (pos >= data.length) return new long[]{-1, pos};
            result |= ((long) (data[pos] & 0xFF)) << shift;
            shift += 8;
            pos++;
        }
        return new long[]{result, pos};
    }

    static final class UmpPart {
        final int id;
        final byte[] data;

        UmpPart(int id, byte[] data) {
            this.id = id;
            this.data = data;
        }
    }

    /** Sequentially reads {@code (part_id, payload)} records off a SABR response body. */
    static final class UmpReader {

        private final InputStream in;
        private final int maxParts;
        private int count;

        UmpReader(InputStream in, int maxParts) {
            this.in = in;
            this.maxParts = maxParts;
        }

        /** @return the next part, or {@code null} when the stream or part budget ends. */
        UmpPart next() throws IOException {
            if (count >= maxParts) return null;
            long partId = readUmpVarint(in);
            if (partId < 0) return null;
            long size = readUmpVarint(in);
            if (size < 0) return null;
            byte[] data = readFully(in, (int) size);
            if (data == null) return null;
            count++;
            return new UmpPart((int) partId, data);
        }
    }

    /** @return exactly {@code size} bytes, or {@code null} if the stream ended early. */
    static byte[] readFully(InputStream in, int size) throws IOException {
        if (size < 0) return null;
        byte[] out = new byte[size];
        int read = 0;
        while (read < size) {
            int n = in.read(out, read, size - read);
            if (n < 0) return null;
            read += n;
        }
        return out;
    }
}
