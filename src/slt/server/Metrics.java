package slt.server;

import slt.Json;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counts of the requests the server answered, for the moderators (GET /api/admin/metrics): since the start, and
 * over the last minute in 60 slots of one second (a request at the turn of a second may land in the next one: the
 * figures are close, not exact). A few atomic counters, no lock. Only numbers: never who asked.
 */
final class Metrics {

    private static final int SLOTS = 60;

    final long startedAt = System.currentTimeMillis();
    private final LongAdder total = new LongAdder();
    private final LongAdder clientErrors = new LongAdder();
    private final LongAdder serverErrors = new LongAdder();
    /** Per second of the last minute: which second a slot holds, its requests, their time and the longest one. */
    private final AtomicLongArray second = new AtomicLongArray(SLOTS);
    private final AtomicLongArray count = new AtomicLongArray(SLOTS);
    private final AtomicLongArray micros = new AtomicLongArray(SLOTS);
    private final AtomicLongArray longest = new AtomicLongArray(SLOTS);
    private final AtomicLongArray timedCount = new AtomicLongArray(SLOTS);

    /**
     * One answered request: its status, and how long it took ({@code nanos} below 0 for an event stream, which
     * lasts as long as the session and is counted without its time).
     */
    void request(int status, long nanos) {
        total.increment();
        if (status >= 500) {
            serverErrors.increment();
        } else if (status >= 400) {
            clientErrors.increment();
        }
        long now = System.currentTimeMillis() / 1000;
        int i = (int) (now % SLOTS);
        long held = second.get(i);
        if (held != now && second.compareAndSet(i, held, now)) {
            // a slot of a minute ago: emptied by the first request of its new second
            count.set(i, 0);
            micros.set(i, 0);
            longest.set(i, 0);
            timedCount.set(i, 0);
        }
        count.incrementAndGet(i);
        if (nanos >= 0) {
            long us = nanos / 1000;
            micros.addAndGet(i, us);
            longest.accumulateAndGet(i, us, Math::max);
            timedCount.incrementAndGet(i);
        }
    }

    /** The counts, as the route gives them (times in milliseconds). */
    Map<String, Object> requests() {
        long now = System.currentTimeMillis() / 1000;
        long n = 0;
        long t = 0;
        long us = 0;
        long max = 0;
        for (int i = 0; i < SLOTS; i++) {
            long s = second.get(i);
            // the 60 seconds before the current one, which is still being counted
            if (s < now && s >= now - SLOTS) {
                n += count.get(i);
                t += timedCount.get(i);
                us += micros.get(i);
                max = Math.max(max, longest.get(i));
            }
        }
        int seconds = (int) Math.min(SLOTS, Math.max(1, now - startedAt / 1000));
        return Json.map("total", total.sum(), "clientErrors", clientErrors.sum(), "serverErrors", serverErrors.sum(),
                "perSecond", Math.round(n * 10.0 / seconds) / 10.0,
                "meanMs", t == 0 ? 0.0 : Math.round(us / 100.0 / t) / 10.0,
                "maxMs", Math.round(max / 100.0) / 10.0);
    }
}
