package com.screenomics;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.preference.PreferenceManager;

import java.util.concurrent.atomic.AtomicLong;

/**
 * How long capture was actually armed and running, and how much it produced.
 *
 * "Armed" means the capture engine was on and permitted -- not that screenshots
 * were being taken. A locked or dark screen is armed time: the app is doing
 * exactly what it should, there is simply nothing to capture. Defining activity
 * by output instead would score a night's sleep, or a participant who barely
 * touches their phone, as downtime.
 *
 * Four counters, and the reason each is separate:
 *
 *   armed     the app was ready to capture
 *   unlocked  the screen was on and usable, so capture was expected
 *   captures  a frame was actually written
 *   blank     the frame was a single flat colour
 *
 * The gap between any two of them is a different failure. armed vs unlocked is
 * ordinary phone use and means nothing is wrong; unlocked vs captures is capture
 * silently failing while the app believes it is healthy, which nothing else can
 * see.
 *
 * All counters are cumulative and monotonic. Readers (the heartbeat) keep their
 * own watermark and send the difference, so a reader never writes to a counter
 * and cannot race the capture service into double-counting or losing an
 * interval.
 *
 * Timing uses {@link SystemClock#elapsedRealtime()}, never the wall clock: it is
 * monotonic, it keeps counting through deep sleep -- which is most of the armed
 * time we care about -- and it cannot be shifted by an NTP correction or by the
 * participant changing the date. These numbers end up in a compliance report.
 *
 * API-safe on purpose, like {@link A11yState}: AccessibilityCaptureService
 * carries a class-level {@code @RequiresApi(R)}, so anything the rest of the app
 * needs to read lives outside it.
 */
public final class CaptureStats {

    static final String PREF_ARMED_SINCE = "cap_armed_since_elapsed";
    static final String PREF_ARMED_CHECKPOINT = "cap_armed_checkpoint_elapsed";
    static final String PREF_ARMED_TOTAL_MS = "cap_armed_total_ms";
    static final String PREF_UNLOCKED_TOTAL_MS = "cap_unlocked_total_ms";
    static final String PREF_CAPTURES_TOTAL = "cap_captures_total";
    static final String PREF_BLANK_TOTAL = "cap_blank_total";
    static final String PREF_SERVICE_STARTS = "cap_service_starts";

    // Accumulated between flushes. Held in memory so the capture loop does not
    // write SharedPreferences every five seconds.
    private static final AtomicLong pendingUnlockedMs = new AtomicLong();
    private static final AtomicLong pendingCaptures = new AtomicLong();
    private static final AtomicLong pendingBlanks = new AtomicLong();

    private CaptureStats() {}

    /** Snapshot of the cumulative counters. */
    public static final class Totals {
        public final long armedMs;
        public final long unlockedMs;
        public final long captures;
        public final long blanks;
        public final int serviceStarts;

        Totals(long armedMs, long unlockedMs, long captures, long blanks, int serviceStarts) {
            this.armedMs = armedMs;
            this.unlockedMs = unlockedMs;
            this.captures = captures;
            this.blanks = blanks;
            this.serviceStarts = serviceStarts;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    /**
     * Whether the stored elapsed-time marks predate a reboot.
     *
     * elapsedRealtime() restarts at zero on reboot, so a stored mark from a
     * previous boot is meaningless -- and, being larger than the current
     * reading, would produce a nonsense interval. Detecting that by comparing
     * against the wall clock would work until the first NTP correction, which
     * can jump the wall clock by minutes and would then read as a reboot. So
     * the test stays inside the monotonic clock: a mark from the future is a
     * mark from a previous boot.
     *
     * The blind spot is a reboot that happened while the previous boot was
     * younger than the current one, where the stale mark still looks valid. The
     * error it can produce is bounded by that previous uptime, i.e. seconds.
     */
    private static boolean marksArePreReboot(SharedPreferences p, long now) {
        return now < p.getLong(PREF_ARMED_SINCE, -1)
                || now < p.getLong(PREF_ARMED_CHECKPOINT, -1);
    }

    /**
     * Called when the accessibility service connects.
     *
     * If an armed interval is still open, the previous service instance died
     * without onDestroy -- killed by the system, most likely. We cannot know
     * when it stopped working, so we credit only up to the last checkpoint,
     * which is the most recent moment the capture loop was demonstrably alive.
     * Everything after that is dropped: undercounting armed time is the safe
     * direction, since the alternative is claiming the app was collecting while
     * it was dead.
     */
    public static void onServiceStarted(Context context) {
        SharedPreferences p = prefs(context);
        SharedPreferences.Editor e = p.edit();

        if (!marksArePreReboot(p, SystemClock.elapsedRealtime())) {
            long since = p.getLong(PREF_ARMED_SINCE, -1);
            long checkpoint = p.getLong(PREF_ARMED_CHECKPOINT, -1);
            if (since >= 0 && checkpoint > since) {
                e.putLong(PREF_ARMED_TOTAL_MS, p.getLong(PREF_ARMED_TOTAL_MS, 0) + (checkpoint - since));
            }
        }

        e.putLong(PREF_ARMED_SINCE, -1)
                .putLong(PREF_ARMED_CHECKPOINT, -1)
                .putInt(PREF_SERVICE_STARTS, p.getInt(PREF_SERVICE_STARTS, 0) + 1)
                .apply();
    }

    /** Capture started. Opens an armed interval; a second call is a no-op. */
    public static void onArmed(Context context) {
        SharedPreferences p = prefs(context);
        long now = SystemClock.elapsedRealtime();
        if (p.getLong(PREF_ARMED_SINCE, -1) >= 0 && !marksArePreReboot(p, now)) return;
        p.edit()
                .putLong(PREF_ARMED_SINCE, now)
                .putLong(PREF_ARMED_CHECKPOINT, now)
                .apply();
    }

    /** Capture stopped cleanly. Closes the armed interval into the total. */
    public static void onDisarmed(Context context) {
        flush(context);
        SharedPreferences p = prefs(context);
        long since = p.getLong(PREF_ARMED_SINCE, -1);
        if (since < 0) return;
        long now = SystemClock.elapsedRealtime();
        long add = (now > since && !marksArePreReboot(p, now)) ? now - since : 0;
        p.edit()
                .putLong(PREF_ARMED_TOTAL_MS, p.getLong(PREF_ARMED_TOTAL_MS, 0) + add)
                .putLong(PREF_ARMED_SINCE, -1)
                .putLong(PREF_ARMED_CHECKPOINT, -1)
                .apply();
    }

    /** Screen was on and unlocked for this long, so capture was expected. */
    public static void addUnlockedMs(long ms) {
        if (ms > 0) pendingUnlockedMs.addAndGet(ms);
    }

    /** A frame was written to the upload queue. */
    public static void addCapture() {
        pendingCaptures.incrementAndGet();
    }

    /** A frame was a single flat colour. */
    public static void addBlankCapture() {
        pendingBlanks.incrementAndGet();
    }

    /**
     * Persist what has accumulated in memory, and stamp the checkpoint.
     *
     * The checkpoint is the evidence used by {@link #onServiceStarted} to close
     * an interval left open by a kill, so it is only meaningful while an
     * interval is open.
     */
    public static void flush(Context context) {
        long unlocked = pendingUnlockedMs.getAndSet(0);
        long captures = pendingCaptures.getAndSet(0);
        long blanks = pendingBlanks.getAndSet(0);

        SharedPreferences p = prefs(context);
        SharedPreferences.Editor e = p.edit();
        if (unlocked > 0) e.putLong(PREF_UNLOCKED_TOTAL_MS, p.getLong(PREF_UNLOCKED_TOTAL_MS, 0) + unlocked);
        if (captures > 0) e.putLong(PREF_CAPTURES_TOTAL, p.getLong(PREF_CAPTURES_TOTAL, 0) + captures);
        if (blanks > 0) e.putLong(PREF_BLANK_TOTAL, p.getLong(PREF_BLANK_TOTAL, 0) + blanks);
        if (p.getLong(PREF_ARMED_SINCE, -1) >= 0) {
            e.putLong(PREF_ARMED_CHECKPOINT, SystemClock.elapsedRealtime());
        }
        e.apply();
    }

    /**
     * Current cumulative totals, including the interval still open and anything
     * not yet flushed, so a reader never sees a value up to a minute stale.
     *
     * The open interval is credited only while the service is actually running.
     * A stale PREF_ARMED_SINCE left behind by a killed service would otherwise
     * keep accruing armed time for an app that is not there.
     */
    public static Totals read(Context context) {
        SharedPreferences p = prefs(context);
        long armed = p.getLong(PREF_ARMED_TOTAL_MS, 0);
        long since = p.getLong(PREF_ARMED_SINCE, -1);
        long now = SystemClock.elapsedRealtime();
        if (since >= 0 && now > since && !marksArePreReboot(p, now) && A11yState.isCaptureRunning()) {
            armed += now - since;
        }
        return new Totals(
                armed,
                p.getLong(PREF_UNLOCKED_TOTAL_MS, 0) + pendingUnlockedMs.get(),
                p.getLong(PREF_CAPTURES_TOTAL, 0) + pendingCaptures.get(),
                p.getLong(PREF_BLANK_TOTAL, 0) + pendingBlanks.get(),
                p.getInt(PREF_SERVICE_STARTS, 0));
    }

    /** Test seam: drop the in-memory accumulators. */
    static void resetPendingForTest() {
        pendingUnlockedMs.set(0);
        pendingCaptures.set(0);
        pendingBlanks.set(0);
    }
}
