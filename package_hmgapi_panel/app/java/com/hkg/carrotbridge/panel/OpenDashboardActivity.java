package com.hkg.carrotbridge.panel;

import android.app.Activity;
import android.os.Bundle;

/**
 * Transparent launcher / steering-wheel Star (★) shortcut activity.
 * Signals the CarrotBridge daemon to show the full-screen status dashboard and finishes immediately.
 */
public class OpenDashboardActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CarrotPanelWidget.triggerDashboardOverlay();
        finish();
        overridePendingTransition(0, 0);
    }
}
