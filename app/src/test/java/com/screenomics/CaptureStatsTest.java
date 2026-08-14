package com.screenomics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.time.Duration;

/**
 * Tests for active-time accounting.
 *
 * "Active" means capture was armed -- on and permitted -- not that screenshots
 * were produced. A locked phone, a sleeping participant and a participant who
 * barely uses their phone are all fully active, and every test here exists
 * because one of the ways to get that wrong would end up in a compliance report.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CaptureStatsTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .edit().clear().commit();
        CaptureStats.resetPendingForTest();
        A11yState.serviceConnected = true;
        A11yState.captureActive = true;
    }

    @After
    public void tearDown() {
        A11yState.serviceConnected = false;
        A11yState.captureActive = false;
    }

    private static void advance(Duration d) {
        ShadowSystemClock.advanceBy(d);
    }

    // ------------------------------------------------------------------
    // Armed time
    // ------------------------------------------------------------------

    @Test
    public void armedTimeAccruesWhileTheAppIsSimplyRunning() {
        CaptureStats.onServiceStarted(context);
        CaptureStats.onArmed(context);

        advance(Duration.ofMinutes(30));

        assertEquals(30 * 60_000L, CaptureStats.read(context).armedMs);
    }

    @Test
    public void aLockedScreenIsStillArmedTime() {
        // The whole point. No ticks are credited, no captures are written, and
        // the app is doing exactly what it should -- so the armed clock runs.
        CaptureStats.onServiceStarted(context);
        CaptureStats.onArmed(context);

        advance(Duration.ofHours(8));   // overnight, screen off the whole time

        CaptureStats.Totals totals = CaptureStats.read(context);
        assertEquals(8 * 3600_000L, totals.armedMs);
        assertEquals(0L, totals.unlockedMs);
        assertEquals(0L, totals.captures);
    }

    @Test
    public void armedTimeSurvivesStopAndStart() {
        CaptureStats.onServiceStarted(context);
        CaptureStats.onArmed(context);
        advance(Duration.ofMinutes(10));
        CaptureStats.onDisarmed(context);

        advance(Duration.ofMinutes(30));    // switched off; must not count

        CaptureStats.onArmed(context);
        advance(Duration.ofMinutes(5));

        assertEquals(15 * 60_000L, CaptureStats.read(context).armedMs);
    }

    @Test
    public void timeWhileDisarmedIsNotCredited() {
        CaptureStats.onServiceStarted(context);
        advance(Duration.ofHours(2));

        assertEquals(0L, CaptureStats.read(context).armedMs);
    }

    @Test
    public void aKilledServiceIsCreditedOnlyToItsLastProofOfLife() {
        // Killed without onDestroy: nothing closed the interval. We know it was
        // alive at the last flush and know nothing after that, so crediting to
        // the end would be claiming collection that may never have happened.
        CaptureStats.onServiceStarted(context);
        CaptureStats.onArmed(context);
        advance(Duration.ofMinutes(10));
        CaptureStats.flush(context);            // last checkpoint

        advance(Duration.ofHours(3));           // dead, but nobody wrote that down

        CaptureStats.onServiceStarted(context); // restart reconciles the gap

        assertEquals(10 * 60_000L, CaptureStats.read(context).armedMs);
    }

    @Test
    public void aStaleOpenIntervalDoesNotAccrueWhileTheServiceIsGone() {
        CaptureStats.onServiceStarted(context);
        CaptureStats.onArmed(context);
        advance(Duration.ofMinutes(10));

        // Service is gone; the open interval must stop growing even though
        // nothing closed it.
        A11yState.serviceConnected = false;
        A11yState.captureActive = false;
        advance(Duration.ofHours(5));

        assertEquals(0L, CaptureStats.read(context).armedMs);
    }

    @Test
    public void aRebootDoesNotInventTime() {
        // elapsedRealtime restarts at zero, so marks from the previous boot sit
        // in the future. Treating them as valid would produce a negative or
        // absurd interval.
        CaptureStats.onServiceStarted(context);
        CaptureStats.onArmed(context);
        advance(Duration.ofHours(6));
        CaptureStats.flush(context);

        long beforeReboot = CaptureStats.read(context).armedMs;
        assertEquals(6 * 3600_000L, beforeReboot);

        ShadowSystemClock.reset();              // reboot: clock back to zero
        CaptureStats.onServiceStarted(context);

        // The closed total survives -- it is a duration, not a timestamp -- and
        // nothing extra is conjured from the stale marks.
        assertEquals(beforeReboot, CaptureStats.read(context).armedMs);
    }

    // ------------------------------------------------------------------
    // Unlocked time and output
    // ------------------------------------------------------------------

    @Test
    public void unlockedTimeAndCapturesAccumulateSeparately() {
        CaptureStats.onServiceStarted(context);
        CaptureStats.onArmed(context);
        advance(Duration.ofHours(1));

        CaptureStats.addUnlockedMs(5_000L);
        CaptureStats.addUnlockedMs(5_000L);
        CaptureStats.addCapture();
        CaptureStats.addCapture();

        CaptureStats.Totals totals = CaptureStats.read(context);
        assertEquals(3600_000L, totals.armedMs);   // an hour armed...
        assertEquals(10_000L, totals.unlockedMs);  // ...10 seconds of it unlocked
        assertEquals(2L, totals.captures);
    }

    @Test
    public void countersAreVisibleBeforeTheyAreFlushed() {
        // The heartbeat reads on its own schedule; a flush-interval delay would
        // make every report up to a minute stale.
        CaptureStats.addCapture();
        CaptureStats.addUnlockedMs(1_000L);

        assertEquals(1L, CaptureStats.read(context).captures);

        CaptureStats.flush(context);

        assertEquals(1L, CaptureStats.read(context).captures);
        assertEquals(1_000L, CaptureStats.read(context).unlockedMs);
    }

    @Test
    public void blankFramesAreCountedWithoutBeingTreatedAsFailures() {
        CaptureStats.addCapture();
        CaptureStats.addBlankCapture();

        CaptureStats.Totals totals = CaptureStats.read(context);
        assertEquals(1L, totals.captures);
        assertEquals(1L, totals.blanks);
    }

    @Test
    public void serviceRestartsAreCounted() {
        CaptureStats.onServiceStarted(context);
        CaptureStats.onServiceStarted(context);
        CaptureStats.onServiceStarted(context);

        assertEquals(3, CaptureStats.read(context).serviceStarts);
    }

    @Test
    public void countersAreMonotonicSoAReaderCanTakeDifferences() {
        // Readers keep a watermark and send the difference. If a counter could
        // go backwards, an interval would be double-counted or lost.
        CaptureStats.onServiceStarted(context);
        CaptureStats.onArmed(context);

        long previousArmed = 0, previousCaptures = 0;
        for (int i = 0; i < 5; i++) {
            advance(Duration.ofMinutes(15));
            CaptureStats.addCapture();
            CaptureStats.flush(context);

            CaptureStats.Totals totals = CaptureStats.read(context);
            assertTrue("armed went backwards", totals.armedMs >= previousArmed);
            assertTrue("captures went backwards", totals.captures >= previousCaptures);
            previousArmed = totals.armedMs;
            previousCaptures = totals.captures;
        }
        assertEquals(75 * 60_000L, previousArmed);
        assertEquals(5L, previousCaptures);
    }
}
