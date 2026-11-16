package com.hkg.carrotbridge;

import android.location.IGpsStatusListener;
import java.io.File;

/**
 * Binder callback stub passed to ILocationManager.addGpsStatusListener() to receive
 * live SV status (satellite count / usedInFix masks / SNR) and raw NMEA sentences.
 */
public final class GpsStatusBinder extends IGpsStatusListener.Stub {
    private final CarrotBridgeMain.NavState navState;
    private final File traceFile;

    public GpsStatusBinder(CarrotBridgeMain.NavState navState, File traceFile) {
        this.navState = navState;
        this.traceFile = traceFile;
    }

    @Override
    public void onGpsStarted() {
    }

    @Override
    public void onGpsStopped() {
    }

    @Override
    public void onFirstFix(int ttff) {
    }

    /** Standard 8-arg AOSP overload (fallback). */
    public void onSvStatusChanged(int svCount, int[] prns, float[] snrs,
            float[] elevations, float[] azimuths,
            int ephemerisMask, int almanacMask, int usedInFixMask) {
        CarrotBridgeMain.applySvStatus(navState, svCount, snrs, usedInFixMask, 0, System.currentTimeMillis());
    }

    /** HKMC 9-arg signature verified in Gen5W framework.odex (includes GLONASS fix bitmask). */
    @Override
    public void onSvStatusChanged(int svCount, int[] prns, float[] snrs,
            float[] elevations, float[] azimuths,
            int ephemerisMask, int almanacMask, int usedInFixMask, int glonassUsedInFixMask) {
        CarrotBridgeMain.applySvStatus(
                navState, svCount, snrs, usedInFixMask, glonassUsedInFixMask, System.currentTimeMillis());
    }

    @Override
    public void onNmeaReceived(long timestamp, String nmea) {
        CarrotBridgeMain.parseNmeaSentence(navState, nmea, System.currentTimeMillis(), traceFile);
    }
}
