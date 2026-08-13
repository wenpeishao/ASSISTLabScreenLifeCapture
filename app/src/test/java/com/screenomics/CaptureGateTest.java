package com.screenomics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The gate deciding when a screenshot is expected.
 *
 * This existed as "is the keyguard down", which is not the same question as "is
 * there anything on screen to capture". The third case below is the one that
 * cost us: a participant with no lock screen configured, whose phone spent
 * every night failing captures against a dark display until the failure counter
 * reached the stall threshold and the app told them capture was broken.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CaptureGateTest {

    @Test
    public void capturesWhenTheScreenIsOnAndUnlocked() {
        assertTrue(AccessibilityCaptureService.screenIsUsable(true, false));
    }

    @Test
    public void doesNotCaptureAtTheLockScreen() {
        assertFalse(AccessibilityCaptureService.screenIsUsable(true, true));
    }

    @Test
    public void doesNotCaptureWithTheScreenOffAndNoLockScreenConfigured() {
        // isKeyguardLocked() is false on such a phone even with the display off.
        // Under the old gate this was a capture attempt at a dark screen, every
        // five seconds, all night.
        assertFalse(AccessibilityCaptureService.screenIsUsable(false, false));
    }

    @Test
    public void doesNotCaptureWithTheScreenOffAndLocked() {
        assertFalse(AccessibilityCaptureService.screenIsUsable(false, true));
    }
}
