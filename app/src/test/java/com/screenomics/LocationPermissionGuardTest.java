package com.screenomics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Application;
import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

/**
 * Screen capture must survive a revoked location permission.
 *
 * Found on a real device, not by reading the code: installing the app with
 * recordingState already true and location denied crash-looped it. From Android
 * 14, starting a foreground service typed {@code location} without the runtime
 * permission throws SecurityException out of the service start, which kills the
 * process -- and it is thrown at start, not at startForegroundService(), so the
 * caller's try/catch never sees it.
 *
 * The consequence is out of proportion to the cause: a participant who revokes
 * location loses screenshots too, on every boot and every app update, and
 * screen capture does not need location at all.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LocationPermissionGuardTest {

    private Context context;
    private ShadowApplication shadowApplication;

    @Before
    public void setUp() {
        Application application = RuntimeEnvironment.getApplication();
        context = application;
        shadowApplication = Shadows.shadowOf(application);
        shadowApplication.denyPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    @Test
    public void refusesToStartWithNoLocationPermission() {
        assertFalse(LocationService.canStartLocationForegroundService(context));
    }

    @Test
    public void fineLocationIsEnough() {
        shadowApplication.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);

        assertTrue(LocationService.canStartLocationForegroundService(context));
    }

    @Test
    public void coarseLocationAloneIsEnough() {
        // The platform requires *any* of fine/coarse, so demanding fine would
        // disable location for participants who granted only approximate.
        shadowApplication.grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION);

        assertTrue(LocationService.canStartLocationForegroundService(context));
    }
}
