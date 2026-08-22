package com.github.catvod.spider;

import com.github.catvod.crawler.SpiderDebug;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A SABR playback session: negotiates with the server and caches completed native segments.
 *
 * <p>Mirrors yt-dlp's {@code SabrStream}/{@code SabrFD} contract: {@code MEDIA_HEADER} opens a
 * segment, {@code MEDIA} payloads are routed by their leading header id varint, and
 * {@code MEDIA_END} atomically publishes the finished segment.
 */
class YTSabrSession {

    /** One completed segment's timing metadata. */
    static class Meta {
        long startMs;
        long durationMs;
        int size;
    }

    /** A segment being accumulated across MEDIA parts. */
    private static class Partial {
        long headerId;
        Long itag;
        byte[] formatId;
        boolean isInit;
        Long seq;
        long startMs;
        long durationMs;
        Long expected;
        java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
    }

    /** Contiguous buffered range reported back to the server. */
    private static class Buffered {
        byte[] formatId;
        long startMs;
        long durationMs;
        Long startSeq;
        Long endSeq;
    }

    /** Result of a segment lookup. */
    static class Found {
        byte[] media;
        Integer nativeSeq;
        Meta meta;
        boolean relaxed;
        boolean stallRecovered;
        String error;
        long targetMs;
        long requestCount;
    }

    final ReentrantLock lock = new ReentrantLock(true);
    private volatile boolean canceled;
    private final Object producerMonitor = new Object();
    private Thread producerThread;
    private YTFormat producerVideoItem;
    private YTFormat producerAudioItem;
    private long producerTargetMs;
    private long producerDurationMs;

    private byte[] playbackCookie;
    private String url;
    private long requestCount;
    private long playerTimeMs;
    private Integer lastStatus;
    private Long lastRequestMs;
    private boolean stallRecovering;
    private String stallRecoverKey;

    private final Map<String, byte[]> initialized = new LinkedHashMap<>();
    private final Map<String, Buffered> buffered = new LinkedHashMap<>();
    private final Map<Long, Partial> partial = new HashMap<>();
    private final Map<Integer, byte[]> initSegments = new HashMap<>();
    private final Map<Integer, Map<Integer, byte[]>> segments = new HashMap<>();
    private final Map<Integer, Map<Integer, Meta>> segmentMeta = new HashMap<>();
    private final Map<Integer, List<Integer>> segmentOrder = new HashMap<>();
    private final Map<Integer, Map<Integer, Integer>> localSegmentMap = new HashMap<>();
    private final Map<Integer, YTSabr.SabrContext> sabrContexts = new HashMap<>();
    private final Set<Integer> activeContexts = new HashSet<>();

    private final YTHttp http;
    private final int maxParts;
    private final long videoCacheBytes;
    private final long audioCacheBytes;
    private final int fetchRequests;

    YTSabrSession(YTHttp http, int maxParts, long videoCacheBytes, long audioCacheBytes, int fetchRequests) {
        this.http = http;
        this.maxParts = maxParts;
        this.videoCacheBytes = videoCacheBytes;
        this.audioCacheBytes = audioCacheBytes;
        this.fetchRequests = fetchRequests;
    }

    Integer lastStatus() {
        return lastStatus;
    }

    void cancel() {
        // Never wait for the SABR lock during Spider.destroy(): the lock may be held while an
        // in-flight UMP response is being parsed. Closing OkHttp cancels that call and the
        // canceled flag makes the parser/pump stop without blocking app shutdown.
        canceled = true;
        try {
            http.close();
        } catch (Throwable ignored) {
        }
    }

    long requestCount() {
        return requestCount;
    }

    /** Starts one background producer for B's real-time index and media cache. */
    void startProducer(YTFormat videoItem, YTFormat audioItem, long durationMs) {
        synchronized (producerMonitor) {
            producerVideoItem = videoItem;
            producerAudioItem = audioItem;
            producerDurationMs = Math.max(0L, durationMs);
            if (producerThread != null && producerThread.isAlive()) {
                producerMonitor.notifyAll();
                return;
            }
            canceled = false;
            producerThread = new Thread(() -> producerLoop(), "youtube-sabr-b-producer");
            producerThread.setDaemon(true);
            producerThread.start();
        }
    }

    /**
     * Returns a real cached segment, waiting only for the background producer to index it.
     * This method never performs SABR network I/O on the player's HTTP request thread.
     */
    Found awaitProducedSegment(YTFormat videoItem, YTFormat audioItem, String track,
                               String segment, long timeoutMs) throws Exception {
        if (canceled) throw new IOException("Canceled: SABR session closed");
        boolean init = "init".equals(segment);
        long targetMs = 0L;
        if (!init) {
            if (segment == null || !segment.startsWith("t=")) {
                Found bad = new Found();
                bad.error = "invalid producer time segment";
                return bad;
            }
            targetMs = Math.max(0L, (long) Double.parseDouble(segment.substring(2)));
        }
        boolean rewind = false;
        synchronized (producerMonitor) {
            if (!init) {
                rewind = targetMs + 15000L < producerTargetMs;
                producerTargetMs = rewind ? targetMs : Math.max(producerTargetMs, targetMs);
            }
            producerMonitor.notifyAll();
        }
        if (rewind) {
            lock.lock();
            try {
                if (!canceled) {
                    playbackCookie = null;
                    seek(targetMs);
                }
            } finally {
                lock.unlock();
            }
        }
        long deadline = System.currentTimeMillis() + Math.max(1000L, timeoutMs);
        while (!canceled && System.currentTimeMillis() < deadline) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;
            if (!lock.tryLock(Math.min(100L, remaining), TimeUnit.MILLISECONDS)) continue;
            try {
                Integer itag = "video".equals(track)
                        ? (videoItem == null ? null : videoItem.itag)
                        : (audioItem == null ? null : audioItem.itag);
                Found found = init ? initLookup(itag) : findSegmentAtTime(itag, targetMs);
                if (found != null && found.media != null && found.media.length > 0) {
                    found.targetMs = targetMs;
                    found.requestCount = requestCount;
                    return found;
                }
            } finally {
                lock.unlock();
            }
            synchronized (producerMonitor) {
                if (!canceled) producerMonitor.wait(Math.min(200L,
                        Math.max(1L, deadline - System.currentTimeMillis())));
            }
        }
        Found timeout = new Found();
        timeout.error = canceled ? "producer canceled" : "producer target timeout";
        timeout.targetMs = targetMs;
        return timeout;
    }

    private void producerLoop() {
        while (!canceled) {
            YTFormat videoItem;
            YTFormat audioItem;
            long goal;
            boolean covered = false;
            boolean locked = false;
            synchronized (producerMonitor) {
                videoItem = producerVideoItem;
                audioItem = producerAudioItem;
                long target = producerTargetMs;
                goal = producerDurationMs > 0
                        ? Math.min(producerDurationMs, target + 30000L) : target + 30000L;
            }
            if (videoItem == null || audioItem == null) return;
            try {
                if (!lock.tryLock(250L, TimeUnit.MILLISECONDS)) continue;
                locked = true;
                if (canceled) return;
                covered = cacheCovers(videoItem.itag, goal) && cacheCovers(audioItem.itag, goal);
                if (!covered) {
                    YTSabr.Config cfg = videoItem.sabrConfig != null
                            ? videoItem.sabrConfig : audioItem.sabrConfig;
                    pumpOnce(cfg, videoItem, audioItem);
                }
            } catch (Throwable error) {
                if (canceled) return;
                SpiderDebug.log("YouTube SABR-B producer: " + String.valueOf(error));
                try {
                    Thread.sleep(120L);
                } catch (InterruptedException ignored) {
                    if (canceled) return;
                }
            } finally {
                if (locked) lock.unlock();
            }
            synchronized (producerMonitor) {
                producerMonitor.notifyAll();
                if (covered && !canceled) {
                    try {
                        producerMonitor.wait(120L);
                    } catch (InterruptedException ignored) {
                        if (canceled) return;
                    }
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* public entry point                                                 */
    /* ------------------------------------------------------------------ */

    /**
     * Fetches one init or media segment for the local DASH bridge.
     *
     * @param segment {@code "init"}, a DASH {@code $Number$}, or {@code "t=<ms>"} for the
     *                SegmentBase bridge, which has no local numbering and therefore no
     *                number-to-native-sequence mapping error.
     */
    Found getSegment(YTFormat videoItem, YTFormat audioItem, String track, String segment) throws Exception {
        if (canceled) throw new IOException("Canceled: SABR session closed");
        YTSabr.Config cfg = videoItem != null && videoItem.sabrConfig != null
                ? videoItem.sabrConfig
                : audioItem != null ? audioItem.sabrConfig : null;
        if (cfg == null) throw new Exception("missing sabr config");

        Integer videoItag = videoItem == null || videoItem.itag == 0 ? null : videoItem.itag;
        Integer audioItag = audioItem == null || audioItem.itag == 0 ? null : audioItem.itag;
        Integer targetItag = "video".equals(track) ? videoItag : audioItag;
        Found result = new Found();
        if (targetItag == null) {
            result.error = "track not selected";
            return result;
        }

        boolean wantInit = "init".equals(segment);
        Long wantTime = null;
        Integer wantSeq = null;
        if (!wantInit && segment != null && segment.startsWith("t=")) {
            try {
                String raw = segment.substring(2);
                int amp = raw.indexOf('&');
                String timeText = amp < 0 ? raw : raw.substring(0, amp);
                wantTime = Math.max(0, (long) Double.parseDouble(timeText));
                // B supplies both the target presentation time and the local DASH number. The
                // time selects the desired region; the number makes retries one-to-one with a
                // native sequence when a virtual grid lands inside the same variable-length
                // segment twice.
                if (amp >= 0) {
                    String extra = raw.substring(amp + 1);
                    if (extra.startsWith("n=")) wantSeq = Integer.parseInt(extra.substring(2));
                }
            } catch (Throwable e) {
                result.error = "invalid time segment";
                return result;
            }
        }
        if (!wantInit && wantTime == null) {
            try {
                wantSeq = Integer.parseInt(segment);
            } catch (Throwable e) {
                result.error = "invalid segment";
                return result;
            }
        }

        YTFormat trackItem = "video".equals(track) ? videoItem : audioItem;
        YTSabr.Config trackCfg = trackItem == null || trackItem.sabrConfig == null ? cfg : trackItem.sabrConfig;
        double target = trackCfg.targetDurationSec > 0
                ? trackCfg.targetDurationSec
                : ("video".equals(track) ? 6 : 10);
        long dashSegMs = (long) (target * 1000);
        if ("audio".equals(track) && dashSegMs < 8000) dashSegMs = 10000;
        if (dashSegMs <= 0) dashSegMs = "video".equals(track) ? 6000 : 10000;

        List<YTFormat.Seg> timeline = trackItem == null ? Collections.emptyList() : trackItem.timeline;
        Long targetMs;
        if (wantInit) {
            targetMs = null;
        } else if (wantTime != null) {
            targetMs = wantTime;
            // Use the real duration of the segment covering this instant as the tolerance basis.
            for (YTFormat.Seg entry : timeline) {
                if (entry.t <= targetMs && targetMs < entry.t + Math.max(1, entry.d)) {
                    dashSegMs = Math.max(1, entry.d);
                    break;
                }
            }
        } else if (wantSeq >= 1 && wantSeq <= timeline.size()) {
            targetMs = timeline.get(wantSeq - 1).t;
            dashSegMs = Math.max(1, timeline.get(wantSeq - 1).d);
        } else {
            // Without a real timeline we can only assume equal segments. This disagrees with the
            // MPD's SegmentTimeline and drifts linearly with playback position.
            targetMs = Math.max(0, (wantSeq - 1) * dashSegMs);
        }

        // A B time request may start behind the server's current playback window. Give it more
        // pump opportunities than the normal numbered bridge, otherwise a missing boundary turns
        // into HTTP 503 and DASH restarts from zero (the observed tiny-picture loop).
        int maxPumps = wantInit ? Math.min(3, fetchRequests)
                : wantTime != null ? Math.max(fetchRequests, 14) : fetchRequests;

        lock.lock();
        try {
            // Record where the player actually is, so cache trimming can centre its window there.
            if (!wantInit && targetMs != null) lastRequestMs = targetMs;
            if (wantInit) {
                byte[] found = initSegments.get(targetItag);
                if (found != null) {
                    result.media = found;
                    result.requestCount = requestCount;
                    return result;
                }
            }

            boolean seeked = false;
            int seekAttempts = 0;
            int transportRetries = 0;
            for (int pump = 0; pump < maxPumps; pump++) {
                if (canceled) throw new IOException("Canceled: SABR session closed");
                Found found = wantInit ? initLookup(targetItag)
                        : wantTime != null && wantSeq != null ? findSegmentByTime(targetItag, targetMs, dashSegMs, wantSeq)
                        : wantTime != null ? findSegmentAtTime(targetItag, targetMs)
                        : findSegmentByTime(targetItag, targetMs, dashSegMs, wantSeq);
                if (found != null && found.media != null) {
                    found.targetMs = targetMs == null ? 0 : targetMs;
                    found.requestCount = requestCount;
                    return found;
                }
                boolean gapSeek = !wantInit && wantTime == null && !seeked
                        && targetIsGap(targetItag, targetMs, dashSegMs, wantSeq);
                // Time mode has no local numbering, so ask directly whether the instant is cached.
                boolean timeSeek = wantTime != null && !seeked && !cacheCovers(targetItag, targetMs);
                if (!wantInit && !seeked && (gapSeek || timeSeek || (wantTime == null
                        && !hasPriorLocalMapping(targetItag, wantSeq)
                        && shouldSeekTime(targetItag, targetMs, dashSegMs)))) {
                    seek(targetMs);
                    seeked = true;
                    seekAttempts++;
                } else if (!wantInit && seeked && seekAttempts < 3 && !cacheCovers(targetItag, targetMs)) {
                    // Already sought but the server still has not sent this segment. Pumping in
                    // place only yields duplicate later segments (observed: the same payload sent
                    // 340 times, responses stuck on the same three segments). Re-seek and drop the
                    // playback cookie to force a fresh decision from the new player_time_ms.
                    playbackCookie = null;
                    seek(targetMs);
                    seekAttempts++;
                }
                try {
                    pumpOnce(cfg, videoItem, audioItem);
                    transportRetries = 0;
                } catch (Exception e) {
                    if (!YTHttp.isRetryable(e) || transportRetries >= 2) throw e;
                    transportRetries++;
                    partial.clear();
                }
                // Do not let a 4K video request monopolize the fair session lock across all
                // pumps. Audio gets a turn between pump requests, preventing video-only stalls.
                if (pump + 1 < maxPumps && !canceled) {
                    lock.unlock();
                    try {
                        Thread.yield();
                    } finally {
                        lock.lock();
                    }
                }
            }

            // When the cached timeline already covers the target, the segment is local and only the
            // mapping missed (large segments plus duration jitter defeat a strict tolerance).
            // Resetting the session here would drag player_time_ms back from the buffered end and
            // make the server resend everything, which is the direct cause of the 30-40s stalls.
            // Relax the tolerance and take the nearest unused segment instead.
            // Time mode has no numbering, so the "unused" rule does not apply; skip it.
            if (!wantInit && wantTime == null) {
                Found relaxed = relaxedLookup(targetItag, targetMs, dashSegMs, wantSeq);
                if (relaxed != null && relaxed.media != null) {
                    relaxed.relaxed = true;
                    relaxed.targetMs = targetMs;
                    relaxed.requestCount = requestCount;
                    return relaxed;
                }
            }

            // A cached segment can still be the wrong segment for this local number: the server
            // may have skipped a native sequence while the broad cache interval still covers the
            // requested time. Recover even when cacheCovers() is true, otherwise A can pump many
            // HTTP-200 control responses and end in a 500 while audio continues.
            if (!wantInit) {
                byte[] recovered = stallRecover(cfg, videoItem, audioItem, targetItag, targetMs, dashSegMs, wantSeq);
                if (recovered != null) {
                    result.media = recovered;
                    result.stallRecovered = true;
                    result.targetMs = targetMs;
                    result.requestCount = requestCount;
                    return result;
                }
                if (wantTime != null) {
                    Found nearby = nearestSegmentAtTime(targetItag, targetMs, 12000L);
                    if (nearby != null && nearby.media != null) {
                        nearby.relaxed = true;
                        nearby.targetMs = targetMs;
                        nearby.requestCount = requestCount;
                        return nearby;
                    }
                }
            }
            result.error = "segment not produced by SABR server";
            result.targetMs = targetMs == null ? 0 : targetMs;
            result.requestCount = requestCount;
            return result;
        } finally {
            lock.unlock();
        }
    }

    private Found initLookup(Integer targetItag) {
        byte[] media = initSegments.get(targetItag);
        if (media == null) return null;
        Found found = new Found();
        found.media = media;
        return found;
    }

    /**
     * Returns the real SABR media boundaries currently present in the session cache.
     * The result is a copy, sorted by presentation time, so callers can safely use it while
     * another request later extends the cache.
     */
    List<YTFormat.Seg> snapshotTimeline(Integer targetItag) {
        lock.lock();
        try {
            Map<Integer, byte[]> media = segments.get(targetItag);
            Map<Integer, Meta> metas = segmentMeta.get(targetItag);
            List<YTFormat.Seg> out = new ArrayList<>();
            if (media == null || metas == null) return out;
            for (Map.Entry<Integer, Meta> entry : metas.entrySet()) {
                if (!media.containsKey(entry.getKey())) continue;
                Meta meta = entry.getValue();
                if (meta == null || meta.durationMs <= 0) continue;
                YTFormat.Seg seg = new YTFormat.Seg();
                seg.t = Math.max(0, meta.startMs);
                seg.d = Math.max(1, meta.durationMs);
                seg.sz = Math.max(0, meta.size);
                out.add(seg);
            }
            out.sort((a, b) -> Long.compare(a.t, b.t));
            return out;
        } finally {
            lock.unlock();
        }
    }

    /* ------------------------------------------------------------------ */
    /* segment lookup                                                     */
    /* ------------------------------------------------------------------ */

    /**
     * Looks a segment up purely by time, with no numbering or occupancy semantics.
     *
     * <p>Used by the SegmentBase bridge. Repeated requests for the same instant necessarily return
     * the same segment, which the {@code $Number$} scheme cannot guarantee, since each number must
     * claim a distinct native segment and duration jitter accumulates misalignment.
     */
    private Found findSegmentAtTime(Integer targetItag, long targetMs) {
        Map<Integer, byte[]> media = segments.get(targetItag);
        Map<Integer, Meta> metas = segmentMeta.get(targetItag);
        if (media == null || metas == null || media.isEmpty() || metas.isEmpty()) return null;
        // The fallback only accepts the earliest segment that starts after the target.
        //
        // The old implementation took the nearest segment by min(|start-t|, |start+duration-t|),
        // but a segment's end equals the next segment's start, so a distance of 0 always won and a
        // target at segment N's start matched N-1 (observed: target 12233 matched native 2's end
        // 6833+5400=12233). The player then received the same Cluster at a new byte position,
        // shorter than requested, judged it a short read and re-sent the same Range: playback
        // looped over the first 10s.
        Integer forwardSeq = null;
        long forwardStart = Long.MAX_VALUE;
        Meta forwardMeta = null;
        for (Map.Entry<Integer, Meta> entry : metas.entrySet()) {
            Integer seq = entry.getKey();
            if (!media.containsKey(seq)) continue;
            Meta meta = entry.getValue();
            long start = meta.startMs;
            long duration = Math.max(1, meta.durationMs);
            if (start <= targetMs && targetMs < start + duration) {
                Found found = new Found();
                found.media = media.get(seq);
                found.nativeSeq = seq;
                found.meta = meta;
                return found;
            }
            // Never return an already-finished segment; only allow a slightly-ahead fallback.
            if (start > targetMs && (start - targetMs) <= 6000 && start < forwardStart) {
                forwardSeq = seq;
                forwardStart = start;
                forwardMeta = meta;
            }
        }
        if (forwardSeq != null) {
            Found found = new Found();
            found.media = media.get(forwardSeq);
            found.nativeSeq = forwardSeq;
            found.meta = forwardMeta;
            return found;
        }
        return null;
    }

    /**
     * Last-resort time lookup for B. A temporary SABR hole must not become HTTP 503, because DASH
     * interprets that as a fatal source error and restarts the period from zero. Only return a
     * nearby complete native segment; exact coverage remains the preferred path above.
     */
    private Found nearestSegmentAtTime(Integer targetItag, long targetMs, long toleranceMs) {
        Map<Integer, byte[]> media = segments.get(targetItag);
        Map<Integer, Meta> metas = segmentMeta.get(targetItag);
        if (media == null || metas == null || media.isEmpty() || metas.isEmpty()) return null;
        Integer bestSeq = null;
        Meta bestMeta = null;
        long bestDistance = Long.MAX_VALUE;
        for (Map.Entry<Integer, Meta> entry : metas.entrySet()) {
            if (!media.containsKey(entry.getKey())) continue;
            Meta meta = entry.getValue();
            long start = meta.startMs;
            long end = start + Math.max(1L, meta.durationMs);
            long distance = targetMs < start ? start - targetMs : targetMs >= end ? targetMs - end : 0;
            if (distance <= toleranceMs && distance < bestDistance) {
                bestSeq = entry.getKey();
                bestMeta = meta;
                bestDistance = distance;
            }
        }
        if (bestSeq == null) return null;
        Found found = new Found();
        found.media = media.get(bestSeq);
        found.nativeSeq = bestSeq;
        found.meta = bestMeta;
        return found;
    }

    private boolean hasPriorLocalMapping(Integer targetItag, Integer localNumber) {
        if (localNumber == null) return false;
        Map<Integer, Integer> mapping = localSegmentMap.get(targetItag);
        if (mapping == null) return false;
        return mapping.containsKey(localNumber) || mapping.containsKey(localNumber - 1);
    }

    /**
     * Stable one-to-one mapping from local DASH {@code $Number$} to native SABR sequence.
     *
     * <p>SABR segment durations vary. Independent fixed-time lookups used to hand the same WebM
     * Cluster to adjacent DASH URLs, producing duplicate PTS and freezes.
     */
    private Found findSegmentByTime(Integer targetItag, long targetMs, long dashSegMs, Integer localNumber) {
        Map<Integer, byte[]> media = segments.get(targetItag);
        Map<Integer, Meta> metas = segmentMeta.get(targetItag);
        if (media == null) media = new HashMap<>();
        if (metas == null) metas = new HashMap<>();
        Map<Integer, Integer> mapping = localSegmentMap.computeIfAbsent(targetItag, k -> new HashMap<>());

        // Stable result for HTTP retries.
        if (localNumber != null && mapping.containsKey(localNumber)) {
            Integer nativeSeq = mapping.get(localNumber);
            if (media.containsKey(nativeSeq) && metas.containsKey(nativeSeq)) {
                Found found = new Found();
                found.media = media.get(nativeSeq);
                found.nativeSeq = nativeSeq;
                found.meta = metas.get(nativeSeq);
                return found;
            }
        }

        Set<Integer> used = new HashSet<>(mapping.values());
        List<Integer> ordered = new ArrayList<>();
        for (Integer seq : metas.keySet()) if (media.containsKey(seq)) ordered.add(seq);
        Map<Integer, Meta> finalMetas = metas;
        ordered.sort((a, b) -> {
            int cmp = Long.compare(finalMetas.get(a).startMs, finalMetas.get(b).startMs);
            return cmp != 0 ? cmp : Integer.compare(a, b);
        });

        // Linear playback advances by native sequence/time and never reuses a Cluster. The anchor
        // is the latest already-mapped smaller number rather than a fixed localNumber-1, because
        // players skip numbers and a fixed anchor would break the linearity constraint.
        Integer previousLocal = null;
        if (localNumber != null) {
            for (Integer key : mapping.keySet()) {
                if (key < localNumber && (previousLocal == null || key > previousLocal)) previousLocal = key;
            }
        }
        if (previousLocal != null) {
            Integer previous = mapping.get(previousLocal);
            Meta pm = metas.get(previous);
            long previousStart = pm == null ? 0 : pm.startMs;
            for (Integer nativeSeq : ordered) {
                Meta meta = metas.get(nativeSeq);
                long start = meta.startMs;
                boolean after = start > previousStart || (start == previousStart && nativeSeq > previous);
                if (!after || used.contains(nativeSeq)) continue;
                // Do not silently bridge an omitted native sequence with a later Cluster: its
                // timestamps contain a real playback gap. Returning null lets targetIsGap issue a
                // targeted seek.
                long tolerance = Math.min(3000, Math.max(750, dashSegMs / 2));
                // The anchor is the nearest mapped number; when numbers are skipped there are
                // (localNumber - previousLocal) local segments in between, so a strict tolerance
                // would always trip and be misread as a hole. Scale it by the skipped count.
                int skipped = Math.max(1, localNumber - previousLocal);
                if (skipped > 1) tolerance = Math.max(tolerance, (long) skipped * Math.max(1, dashSegMs));
                if (Math.abs(start - targetMs) > tolerance) return null;
                mapping.put(localNumber, nativeSeq);
                Found found = new Found();
                found.media = media.get(nativeSeq);
                found.nativeSeq = nativeSeq;
                found.meta = meta;
                return found;
            }
            return null;
        }

        List<Integer> candidates = new ArrayList<>();
        for (Integer nativeSeq : ordered) {
            if (used.contains(nativeSeq)) continue;
            Meta meta = metas.get(nativeSeq);
            if (meta.startMs <= targetMs && targetMs < meta.startMs + Math.max(1, meta.durationMs)) {
                candidates.add(nativeSeq);
            }
        }
        if (candidates.isEmpty()) {
            long tolerance = Math.min(3000, Math.max(750, dashSegMs / 2));
            for (Integer nativeSeq : ordered) {
                if (used.contains(nativeSeq)) continue;
                if (Math.abs(metas.get(nativeSeq).startMs - targetMs) <= tolerance) candidates.add(nativeSeq);
            }
        }
        if (candidates.isEmpty()) return null;
        Integer best = candidates.get(0);
        long bestDistance = Math.abs(metas.get(best).startMs - targetMs);
        for (Integer nativeSeq : candidates) {
            long distance = Math.abs(metas.get(nativeSeq).startMs - targetMs);
            if (distance < bestDistance) {
                best = nativeSeq;
                bestDistance = distance;
            }
        }
        if (localNumber != null) mapping.put(localNumber, best);
        Found found = new Found();
        found.media = media.get(best);
        found.nativeSeq = best;
        found.meta = metas.get(best);
        return found;
    }

    /** @return {@code {minStart, maxEnd}} across cached segments, or {@code null} when empty. */
    private long[] cachedTimeRange(Integer targetItag) {
        Map<Integer, Meta> metas = segmentMeta.get(targetItag);
        if (metas == null || metas.isEmpty()) return null;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (Meta meta : metas.values()) {
            min = Math.min(min, meta.startMs);
            max = Math.max(max, meta.startMs + meta.durationMs);
        }
        return new long[]{min, max};
    }

    /**
     * Detects a missing native segment inside the broad cached time range.
     *
     * <p>A SABR response may omit a requested video sequence. Min/max cache checks classify that
     * hole as buffered, so the bridge keeps pumping newer media while the player waits. Seek only
     * when the immediately preceding local mapping exists and the requested time is genuinely
     * uncovered; this keeps the linear mapping stable while repairing holes promptly.
     */
    private boolean targetIsGap(Integer targetItag, long targetMs, long dashSegMs, Integer localNumber) {
        if (localNumber == null || localNumber <= 1) return false;
        Map<Integer, Integer> mapping = localSegmentMap.get(targetItag);
        if (mapping == null || !mapping.containsKey(localNumber - 1)) return false;
        Map<Integer, Meta> metas = segmentMeta.get(targetItag);
        if (metas == null) return false;
        long tolerance = Math.min(750, Math.max(150, dashSegMs / 10));
        for (Meta meta : metas.values()) {
            long start = meta.startMs;
            long duration = Math.max(1, meta.durationMs);
            // Strict half-open interval: no tolerance at the segment tail.
            //
            // A segment's tail start+duration is the next segment's start, so padding the tail
            // would treat "the next segment's start" as already covered. Observed: native 8 covers
            // 37500-43000; with 750ms padding the interval became [36750,43750), so target 43000
            // (native 9's start, dropped by the server) was judged covered. No seek, no repair,
            // and relaxed lookup eventually handed over native 10; the player decoded a timestamp
            // jump and the picture froze at 43-46s.
            if (start - tolerance <= targetMs && targetMs < start + duration) return false;
        }
        long[] cached = cachedTimeRange(targetItag);
        return cached != null && cached[0] <= targetMs && targetMs <= cached[1];
    }

    private boolean shouldSeekTime(Integer targetItag, long targetMs, long dashSegMs) {
        // A missing segment (target covered by no completed segment) must trigger a seek, otherwise
        // the server keeps prefetching forward from player_time_ms and never backfills it.
        if (!cacheCovers(targetItag, targetMs)) return true;
        long[] cached = cachedTimeRange(targetItag);
        if (cached == null) {
            long current = playerTimeMs;
            return current > targetMs + dashSegMs || targetMs > current + (2 * dashSegMs);
        }
        long tolerance = Math.max(12000, 2 * dashSegMs);
        return targetMs < cached[0] - tolerance || targetMs > cached[1] + tolerance;
    }

    /**
     * Whether some completed native segment really covers the target time.
     *
     * <p>The old implementation treated segment_meta's min(start)/max(end) as one continuous range,
     * which hides holes: with native 9 missing, 43000 still fell inside [12233,134433], so no seek
     * fired and stall recovery never triggered, leaving the gap permanently unfilled. Now each
     * segment is checked individually.
     */
    private boolean cacheCovers(Integer targetItag, long targetMs) {
        Map<Integer, Meta> metas = segmentMeta.get(targetItag);
        if (metas == null || metas.isEmpty()) return false;
        Map<Integer, byte[]> media = segments.get(targetItag);
        if (media == null) return false;
        for (Map.Entry<Integer, Meta> entry : metas.entrySet()) {
            if (!media.containsKey(entry.getKey())) continue;
            Meta meta = entry.getValue();
            long start = meta.startMs;
            long duration = Math.max(1, meta.durationMs);
            // Allow 2s of slack before the first segment for players probing slightly below 0.
            long low = start == 0 ? start - 2000 : start;
            if (low <= targetMs && targetMs < start + duration) return true;
        }
        return false;
    }

    /**
     * Fallback match after a strict lookup fails, tolerating only millisecond-level jitter.
     *
     * <p>This used to allow max(4000, dashSegMs*2) to absorb duration jitter. But the MPD's t
     * values come from the direct URL's Cues/sidx, the same boundaries as the native SABR segments
     * (verified: dash 1-8 and native 1-8 starts match exactly), so a mismatched start has only one
     * cause: the server dropped an intermediate segment. Relaxing the match then promotes the
     * following segment (dash 9 receiving native 10, start 43000 vs 46900); the player decodes a
     * timestamp jump, the picture stalls, and the error accumulates with playback (two segments of
     * drift by the 2-minute mark). The tolerance is therefore tightened to 100ms: a mismatch is a
     * genuine gap, handed to the seek/repair path, and a mistimed segment is never returned.
     */
    private Found relaxedLookup(Integer targetItag, long targetMs, long dashSegMs, Integer localNumber) {
        Map<Integer, byte[]> media = segments.get(targetItag);
        Map<Integer, Meta> metas = segmentMeta.get(targetItag);
        if (media == null || metas == null || media.isEmpty() || metas.isEmpty()) return null;
        Map<Integer, Integer> mapping = localSegmentMap.computeIfAbsent(targetItag, k -> new HashMap<>());
        if (localNumber != null && mapping.containsKey(localNumber)) {
            Integer nativeSeq = mapping.get(localNumber);
            if (media.containsKey(nativeSeq) && metas.containsKey(nativeSeq)) {
                Found found = new Found();
                found.media = media.get(nativeSeq);
                found.nativeSeq = nativeSeq;
                found.meta = metas.get(nativeSeq);
                return found;
            }
        }

        Set<Integer> used = new HashSet<>(mapping.values());
        long tolerance = 100;

        // Anti-rewind floor: the latest-in-time among all mapped smaller numbers.
        //
        // Players skip numbers (observed: numbers 29/31 missing, 30/32 requested directly). Only
        // comparing against localNumber-1 disables the check entirely, letting a larger number take
        // an earlier native segment (number 30 -> native 32, number 32 -> native 31); the player
        // then decodes going-backwards timestamps and the picture stalls.
        Long floorMs = null;
        if (localNumber != null) {
            Integer previousLocal = null;
            for (Integer key : mapping.keySet()) {
                if (key < localNumber && (previousLocal == null || key > previousLocal)) previousLocal = key;
            }
            if (previousLocal != null) {
                Meta previousMeta = metas.get(mapping.get(previousLocal));
                floorMs = previousMeta == null ? 0 : previousMeta.startMs;
            }
        }

        Integer bestSeq = null;
        long bestDistance = Long.MAX_VALUE;
        long bestStart = Long.MAX_VALUE;
        for (Map.Entry<Integer, Meta> entry : metas.entrySet()) {
            Integer seq = entry.getKey();
            if (!media.containsKey(seq) || used.contains(seq)) continue;
            Meta meta = entry.getValue();
            long start = meta.startMs;
            long duration = Math.max(1, meta.durationMs);
            long distance;
            if (start <= targetMs && targetMs < start + duration) {
                distance = 0;
            } else {
                distance = Math.min(Math.abs(start - targetMs), Math.abs((start + duration) - targetMs));
                if (distance > tolerance) continue;
            }
            // Linear playback must not pick a segment before the mapped region.
            if (floorMs != null && start < floorMs) continue;
            if (distance < bestDistance || (distance == bestDistance && start < bestStart)) {
                bestSeq = seq;
                bestDistance = distance;
                bestStart = start;
            }
        }
        if (bestSeq == null) return null;
        if (localNumber != null) mapping.put(localNumber, bestSeq);
        Found found = new Found();
        found.media = media.get(bestSeq);
        found.nativeSeq = bestSeq;
        found.meta = metas.get(bestSeq);
        return found;
    }

    /**
     * Stall recovery: reset negotiation state and renegotiate once from {@code targetMs}.
     *
     * <p>Only called after the regular pumps are exhausted. Clears buffered/initialized and the
     * playback cookie so the server stops skipping this segment based on stale buffered info, and
     * drops this itag's local mapping so newly delivered segments can rebind to the current DASH
     * number.
     */
    private byte[] stallRecover(YTSabr.Config cfg, YTFormat videoItem, YTFormat audioItem,
                                Integer targetItag, long targetMs, long dashSegMs, Integer wantSeq) {
        if (stallRecovering) return null;
        // The SegmentBase bridge has no local numbering (wantSeq is null), so dedupe by target time.
        String recoverKey = wantSeq != null ? "n:" + wantSeq : "t:" + targetMs;
        // Recover a given segment only once to avoid a reset loop.
        if (recoverKey.equals(stallRecoverKey)) return null;
        stallRecovering = true;
        stallRecoverKey = recoverKey;
        try {
            buffered.clear();
            initialized.clear();
            partial.clear();
            playbackCookie = null;
            playerTimeMs = targetMs;
            localSegmentMap.remove(targetItag);
            for (int i = 0; i < 3; i++) {
                try {
                    pumpOnce(cfg, videoItem, audioItem);
                } catch (Exception e) {
                    return null;
                }
                Found found = wantSeq == null
                        ? findSegmentAtTime(targetItag, targetMs)
                        : findSegmentByTime(targetItag, targetMs, dashSegMs, wantSeq);
                if (found != null && found.media != null) return found.media;
            }
            return null;
        } finally {
            stallRecovering = false;
        }
    }

    /**
     * Discards segments that never received MEDIA_END and clears any residue they left.
     *
     * <p>A truncated connection leaves only partial bytes, which must never enter {@code segments}.
     * The same sequence's stale cache and local mapping are also cleared so the next pump refetches
     * it instead of hitting a truncated segment.
     */
    private void discardPartials() {
        for (Partial item : partial.values()) {
            if (item.itag == null || item.seq == null || item.isInit) continue;
            int itag = item.itag.intValue();
            int seq = item.seq.intValue();
            Map<Integer, byte[]> media = segments.get(itag);
            if (media != null) media.remove(seq);
            Map<Integer, Meta> metas = segmentMeta.get(itag);
            if (metas != null) metas.remove(seq);
            List<Integer> order = segmentOrder.get(itag);
            if (order != null) order.remove(Integer.valueOf(seq));
            Map<Integer, Integer> mapping = localSegmentMap.get(itag);
            if (mapping != null) mapping.values().removeIf(value -> value == seq);
        }
        partial.clear();
    }

    /** Replays only the contexts the server asked us to send. */
    private Map<Integer, YTSabr.SabrContext> activeContexts() {
        if (sabrContexts.isEmpty()) return null;
        Map<Integer, YTSabr.SabrContext> out = new TreeMap<>();
        for (Map.Entry<Integer, YTSabr.SabrContext> entry : sabrContexts.entrySet()) {
            if (activeContexts.contains(entry.getKey())) out.put(entry.getKey(), entry.getValue());
        }
        return out;
    }

    /**
     * Resets to a target time: clears buffered ranges and initialized ids so the server resends
     * from {@code seekMs}. Already-downloaded segments stay cached; a seek only changes where the
     * next request starts.
     */
    private void seek(long seekMs) {
        playerTimeMs = seekMs;
        buffered.clear();
        initialized.clear();
        partial.clear();
    }

    private List<byte[]> bufferedRanges() {
        List<byte[]> out = new ArrayList<>();
        for (Buffered br : buffered.values()) {
            byte[] packed = YTSabr.buildBufferedRange(br.formatId, br.startMs, br.durationMs, br.startSeq, br.endSeq);
            if (packed.length > 0) out.add(packed);
        }
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* transport                                                          */
    /* ------------------------------------------------------------------ */

    /** Issues one SABR request and commits every segment it completes. */
    private void pumpOnce(YTSabr.Config cfg, YTFormat videoItem, YTFormat audioItem) throws Exception {
        if (canceled) throw new IOException("Canceled: SABR session closed");
        Integer videoItag = videoItem == null || videoItem.itag == 0 ? null : videoItem.itag;
        Integer audioItag = audioItem == null || audioItem.itag == 0 ? null : audioItem.itag;
        List<byte[]> initializedIds = new ArrayList<>(initialized.values());
        List<byte[]> ranges = bufferedRanges();
        byte[] payload = YTSabr.buildVpabrRequest(cfg, videoItag, audioItag, playerTimeMs,
                playbackCookie, initializedIds, ranges, activeContexts());
        String target = url != null ? url
                : cfg.serverAbrStreamingUrl != null ? cfg.serverAbrStreamingUrl
                : videoItem != null ? videoItem.url : null;
        if (target == null || target.indexOf("n=") < 0) {
            throw new Exception("SABR missing solved server URL");
        }
        SpiderDebug.log("YouTube SABR 请求准备: client=" + cfg.clientName + ", videoItag="
                + videoItag + ", audioItag=" + audioItag + ", rn=" + (requestCount + 1));
        Map<String, String> headers = new HashMap<>();
        if (videoItem != null && !videoItem.headers.isEmpty()) headers.putAll(videoItem.headers);
        else if (audioItem != null) headers.putAll(audioItem.headers);

        Set<Integer> targetItags = new HashSet<>();
        if (videoItag != null) targetItags.add(videoItag);
        if (audioItag != null) targetItags.add(audioItag);

        for (int redirectAttempt = 0; redirectAttempt < 4; redirectAttempt++) {
            long rn = requestCount + 1;
            YTHttp.Result response = http.postSabr(target, payload, headers, rn);
            requestCount = rn;
            lastStatus = response.code;
            SpiderDebug.log("YouTube SABR 响应: http=" + response.code + ", rn=" + rn);
            String redirectUrl = null;
            int completed = 0;
            try {
                if (response.code != 200) {
                    throw new Exception("SABR HTTP " + response.code + " client=" + cfg.clientName);
                }
                InputStream in = response.raw.body() == null ? null : response.raw.body().byteStream();
                if (in == null) throw new Exception("SABR empty body");
                YTProto.UmpReader reader = new YTProto.UmpReader(in, maxParts);
                YTProto.UmpPart part;
                int seenParts = 0;
                while ((part = reader.next()) != null) {
                    seenParts++;
                    if (part.id != YTSabr.MEDIA && part.id != YTSabr.MEDIA_HEADER && part.id != YTSabr.MEDIA_END) {
                        SpiderDebug.log("YouTube UMP 控制 part=" + part.id + ", bytes=" + part.data.length);
                    }
                    if (part.id == YTSabr.MEDIA_HEADER) {
                        Long headerId = YTProto.getInt(part.data, 1);
                        if (headerId == null) continue;
                        Long itag = YTProto.getInt(part.data, 3);
                        Partial item = new Partial();
                        item.headerId = headerId;
                        item.itag = itag;
                        item.formatId = YTSabr.headerFormatId(part.data, itag);
                        Long isInit = YTProto.getInt(part.data, 8);
                        item.isInit = isInit != null && isInit != 0;
                        item.seq = YTProto.getInt(part.data, 9);
                        Long startMs = YTProto.getInt(part.data, 11);
                        Long durationMs = YTProto.getInt(part.data, 12);
                        item.startMs = startMs == null ? 0 : startMs;
                        item.durationMs = durationMs == null ? 0 : durationMs;
                        if (item.startMs == 0 && item.durationMs == 0) {
                            long[] tr = YTSabr.timeRangeMs(part.data);
                            item.startMs = tr[0];
                            item.durationMs = tr[1];
                        }
                        item.expected = YTProto.getInt(part.data, 14);
                        partial.put(headerId, item);
                    } else if (part.id == YTSabr.MEDIA) {
                        long[] read = YTProto.readUmpVarint(part.data, 0);
                        Partial item = partial.get(read[0]);
                        if (item != null && item.itag != null && targetItags.contains(item.itag.intValue())) {
                            // The leading UMP varint is routing metadata, never media bytes.
                            int offset = (int) read[1];
                            item.data.write(part.data, offset, part.data.length - offset);
                        }
                    } else if (part.id == YTSabr.MEDIA_END) {
                        long[] read = YTProto.readUmpVarint(part.data, 0);
                        Partial item = partial.remove(read[0]);
                        if (item == null || item.itag == null
                                || !targetItags.contains(item.itag.intValue())) continue;
                        byte[] media = item.data.toByteArray();
                        if (item.expected != null && item.expected != media.length) continue;
                        int itag = item.itag.intValue();
                        if (item.isInit) {
                            initSegments.put(itag, media);
                            if (item.formatId != null && item.formatId.length > 0) {
                                initialized.put(String.valueOf(itag), item.formatId);
                            }
                        } else if (item.seq != null) {
                            int seq = item.seq.intValue();
                            segments.computeIfAbsent(itag, k -> new HashMap<>()).put(seq, media);
                            Meta meta = new Meta();
                            meta.startMs = item.startMs;
                            meta.durationMs = item.durationMs;
                            meta.size = media.length;
                            segmentMeta.computeIfAbsent(itag, k -> new HashMap<>()).put(seq, meta);
                            List<Integer> order = segmentOrder.computeIfAbsent(itag, k -> new ArrayList<>());
                            order.remove(Integer.valueOf(seq));
                            order.add(seq);
                            commitBuffered(item);
                            boolean isVideo = videoItag != null && itag == videoItag;
                            trimCache(itag, isVideo ? videoCacheBytes : audioCacheBytes);
                        }
                        completed++;
                    } else if (part.id == YTSabr.NEXT_REQUEST_POLICY) {
                        byte[] cookie = YTProto.getBytes(part.data, 7);
                        if (cookie != null && cookie.length > 0) playbackCookie = cookie;
                    } else if (part.id == YTSabr.SABR_REDIRECT) {
                        redirectUrl = YTProto.getStr(part.data, 1);
                        if (redirectUrl != null && !redirectUrl.isEmpty()) url = redirectUrl;
                    } else if (part.id == YTSabr.SABR_ERROR) {
                        String errType = YTProto.getStr(part.data, 1);
                        Long errAction = YTProto.getInt(part.data, 2);
                        // Per googlevideo's handleSabrError this must abort the request and let the
                        // caller retry; merely logging leaves the caller with empty data.
                        throw new Exception("SABR error type=" + errType + " action=" + errAction);
                    } else if (part.id == YTSabr.SABR_CONTEXT_UPDATE) {
                        // SabrContextUpdate: type=1, scope=2, value=3, send_by_default=4
                        Long ctxType = YTProto.getInt(part.data, 1);
                        byte[] ctxValue = YTProto.getBytes(part.data, 3);
                        if (ctxType != null && ctxValue != null && ctxValue.length > 0) {
                            YTSabr.SabrContext ctx = new YTSabr.SabrContext();
                            ctx.scope = YTProto.getInt(part.data, 2);
                            ctx.value = ctxValue;
                            sabrContexts.put(ctxType.intValue(), ctx);
                            Long sendByDefault = YTProto.getInt(part.data, 4);
                            if (sendByDefault != null && sendByDefault != 0) {
                                activeContexts.add(ctxType.intValue());
                            }
                        }
                    } else if (part.id == YTSabr.SABR_CONTEXT_SENDING_POLICY) {
                        // SabrContextSendingPolicy: start=1, stop=2, discard=3
                        for (Long ctxType : YTProto.getIntList(part.data, 1)) {
                            activeContexts.add(ctxType.intValue());
                        }
                        for (Long ctxType : YTProto.getIntList(part.data, 2)) {
                            activeContexts.remove(ctxType.intValue());
                        }
                        for (Long ctxType : YTProto.getIntList(part.data, 3)) {
                            activeContexts.remove(ctxType.intValue());
                            sabrContexts.remove(ctxType.intValue());
                        }
                    }
                }
            } catch (Exception e) {
                // GoogleVideo occasionally closes a chunked UMP response after one or more
                // MEDIA_END records. Those are complete, so only unfinished fragments are dropped.
                // SABR_ERROR is not a transport truncation and must propagate to trigger a retry.
                //
                // Note: a truncated partial may already have left bytes in segments (size-mismatched
                // segments are rejected at MEDIA_END, but a partial with no MEDIA_END lingers).
                // Those must be discarded explicitly or the player will decode an incomplete
                // segment and glitch or stall there.
                if (completed > 0 && YTHttp.isRetryable(e)) {
                    discardPartials();
                } else {
                    partial.clear();
                    throw e;
                }
            } finally {
                response.close();
            }
            SpiderDebug.log("YouTube SABR 处理完成: rn=" + requestCount + ", completed=" + completed
                    + ", initialized=" + initialized.size() + ", videoCached=" + initSegments.containsKey(videoItag)
                    + ", audioCached=" + initSegments.containsKey(audioItag));
            if (redirectUrl != null && completed == 0) {
                target = redirectUrl;
                // Redirect parts can update the playback cookie/policy. Rebuild from the latest
                // state instead of resending a stale payload to the new CDN.
                initializedIds = new ArrayList<>(initialized.values());
                ranges = bufferedRanges();
                payload = YTSabr.buildVpabrRequest(cfg, videoItag, audioItag, playerTimeMs,
                        playbackCookie, initializedIds, ranges, activeContexts());
                continue;
            }
            return;
        }
    }

    /**
     * Trims completed segments by both a time window and a byte ceiling.
     *
     * <p>Byte-only trimming barely applies to small-segment tracks (itag 248 is under 1MB per
     * segment, never reaching a 512MB cap), so the server prefetches far past the playback position
     * (observed: cache end at 2266s while playback was at 240s), wasting memory and parse time.
     * Segments clearly outside the playback window are dropped first, then the byte cap applies.
     */
    private void trimCache(int itag, long maxBytes) {
        long keepAheadMs = 120000;
        long keepBehindMs = 45000;
        Map<Integer, byte[]> media = segments.get(itag);
        Map<Integer, Meta> metas = segmentMeta.get(itag);
        List<Integer> order = segmentOrder.get(itag);
        if (media == null || metas == null || order == null) return;

        // Centre the window on the player's most recent actual request. player_time_ms is the
        // negotiated prefetch position and can run far ahead of real playback (observed: 2000s
        // ahead); using it would trim the segment currently being played.
        long centerMs = lastRequestMs == null ? playerTimeMs : lastRequestMs;
        long low = centerMs - keepBehindMs;
        long high = centerMs + keepAheadMs;
        for (Integer seq : new ArrayList<>(order)) {
            Meta meta = metas.get(seq);
            if (meta == null) continue;
            long start = meta.startMs;
            long end = start + meta.durationMs;
            if (end < low || start > high) {
                media.remove(seq);
                metas.remove(seq);
                order.remove(seq);
                Map<Integer, Integer> mapping = localSegmentMap.get(itag);
                if (mapping != null) mapping.values().removeIf(value -> value.equals(seq));
            }
        }

        long total = 0;
        for (byte[] value : media.values()) total += value.length;
        // Retain enough native segments for retries and small timeline corrections.
        while (total > maxBytes && order.size() > 8) {
            Integer oldSeq = order.remove(0);
            byte[] oldMedia = media.remove(oldSeq);
            metas.remove(oldSeq);
            if (oldMedia != null) total -= oldMedia.length;
        }
    }

    /**
     * Reports buffered ranges as contiguous, hole-free intervals.
     *
     * <p>The old implementation expanded a single min/max range unconditionally: once the server
     * dropped a sequence or one was discarded locally, the hole was merged into the buffered range.
     * The server trusts buffered_ranges, skips that span and never resends it, so the video track
     * loses a segment permanently (audio plays in order and rarely has holes, hence frozen picture
     * with sound, appearing only after several segments accumulate, around 20-40s).
     *
     * <p>Now only the contiguous run starting at the smallest sequence is reported; segments after
     * a hole are withheld so the server resends the missing part. player_time_ms likewise follows
     * the contiguous end, so it is not pushed into the future past skipped content.
     */
    private void commitBuffered(Partial segment) {
        if (segment.formatId == null || segment.formatId.length == 0) return;
        Integer itag = segment.itag == null ? null : segment.itag.intValue();
        String key = itag != null ? String.valueOf(itag) : String.valueOf(segment.formatId.length);
        Map<Integer, Meta> metas = itag == null ? null : segmentMeta.get(itag);
        if (metas == null || metas.isEmpty()) {
            // With no metadata to work from, report the single segment and never merge across.
            Buffered br = new Buffered();
            br.formatId = segment.formatId;
            br.startMs = segment.startMs;
            br.durationMs = segment.durationMs;
            br.startSeq = segment.seq;
            br.endSeq = segment.seq;
            buffered.put(key, br);
            playerTimeMs = Math.max(playerTimeMs, segment.startMs + segment.durationMs);
            return;
        }

        List<Integer> seqs = new ArrayList<>(metas.keySet());
        Collections.sort(seqs);
        int first = seqs.get(0);
        Meta firstMeta = metas.get(first);
        long startMs = firstMeta.startMs;
        long endMs = startMs + firstMeta.durationMs;
        int endSeq = first;
        for (int i = 1; i < seqs.size(); i++) {
            int seq = seqs.get(i);
            if (seq != endSeq + 1) break; // A sequence hole: stop immediately.
            Meta meta = metas.get(seq);
            long segStart = meta.startMs;
            long segDuration = meta.durationMs;
            // The timeline must be contiguous too; tolerate one segment of jitter, beyond that
            // treat it as a hole.
            if (segStart > endMs + Math.max(1000, segDuration == 0 ? 1000 : segDuration)) break;
            endMs = Math.max(endMs, segStart + segDuration);
            endSeq = seq;
        }

        Buffered br = new Buffered();
        br.formatId = segment.formatId;
        br.startMs = startMs;
        br.durationMs = Math.max(0, endMs - startMs);
        br.startSeq = (long) first;
        br.endSeq = (long) endSeq;
        buffered.put(key, br);
        // Monotonic so a seek never rewinds it, but it only follows the contiguous end.
        playerTimeMs = Math.max(playerTimeMs, endMs);
    }
}