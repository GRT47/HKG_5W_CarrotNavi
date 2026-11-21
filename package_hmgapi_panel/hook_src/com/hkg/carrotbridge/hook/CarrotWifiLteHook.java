package com.hkg.carrotbridge.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;

import hmgapi.hook.Hooks;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.ModEntry;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.Collection;

/**
 * Keeps cellular LTE (TYPE_MOBILE, rmnet0) alive as the active default network while
 * Wi-Fi client (TYPE_WIFI, wlan0) is connected on Gen5W (Android 4.4.2).
 *
 * Without this hook, ConnectivityService enforces a single default network and tears
 * down rmnet0 whenever wlan0 connects (priority 1 > 0), breaking HKMC Music Streaming
 * and stock connected services when using WiFi(Client) mode for CarrotPilot.
 */
public final class CarrotWifiLteHook extends ModEntry {

    static final int TYPE_MOBILE = 0;
    static final int TYPE_WIFI = 1;

    private static final String MOD_ID = "carrotbridge-panel";
    private static final String SETTING_KEY = "keep_lte_on_wifi";
    private static final String ACTION_HMGAPI_STATE_CHANGED = "hmgapi.intent.action.STATE_CHANGED";
    private static final String PUBLIC_STATE_PATH = "/data/hmgapi/public/state.json";

    private static volatile ModContext modRef;
    private static volatile boolean hookEnabled = true;
    private static volatile boolean keepLteOnWifi = true;
    private static volatile boolean receiverRegistered = false;

    private static final ThreadLocal<Integer> SUPPRESS_WIFI_DEFAULT_DEPTH = new ThreadLocal<Integer>();
    private static final ThreadLocal<Boolean> IN_ENFORCE_PREF = new ThreadLocal<Boolean>();
    private static final ThreadLocal<Boolean> IN_MOBILE_TAKEOVER = new ThreadLocal<Boolean>();

    private static boolean isActive() {
        return hookEnabled && keepLteOnWifi;
    }

    private static void pushSuppressWifiDefault() {
        Integer cur = SUPPRESS_WIFI_DEFAULT_DEPTH.get();
        int next = (cur == null ? 0 : cur.intValue()) + 1;
        SUPPRESS_WIFI_DEFAULT_DEPTH.set(Integer.valueOf(next));
    }

    private static void popSuppressWifiDefault() {
        Integer cur = SUPPRESS_WIFI_DEFAULT_DEPTH.get();
        if (cur == null || cur.intValue() <= 1) {
            SUPPRESS_WIFI_DEFAULT_DEPTH.remove();
        } else {
            SUPPRESS_WIFI_DEFAULT_DEPTH.set(Integer.valueOf(cur.intValue() - 1));
        }
    }

    private static boolean isSuppressingWifiDefault() {
        Integer cur = SUPPRESS_WIFI_DEFAULT_DEPTH.get();
        return cur != null && cur.intValue() > 0;
    }

    static boolean shouldSuppressWifiDefaultForConnect(
            boolean active, int netType, int activeDefaultNet, boolean mobileConnected) {
        return active && netType == TYPE_WIFI && (activeDefaultNet == TYPE_MOBILE || mobileConnected);
    }

    static boolean shouldSuppressWifiDefaultForDisconnect(
            boolean active, int netType, int activeDefaultNet) {
        return active && netType == TYPE_WIFI && activeDefaultNet == TYPE_MOBILE;
    }

    static boolean shouldPreferMobileOverWifi(
            boolean active, int newNetType, int activeDefaultNet) {
        return active && newNetType == TYPE_MOBILE && activeDefaultNet == TYPE_WIFI;
    }

    static boolean shouldBlockMobileTeardown(
            boolean active, int trackerType, boolean inEnforcePref, boolean wifiConnected) {
        return active && trackerType == TYPE_MOBILE && (inEnforcePref || wifiConnected);
    }

    static boolean shouldConvertWifiTeardownToDemote(
            boolean active, int trackerType, boolean inMobileTakeover) {
        return active && trackerType == TYPE_WIFI && inMobileTakeover;
    }

    @Override
    public void zygoteInit(ModContext mod) {
        modRef = mod;
        try {
            hookEnabled = mod.enabled();
            keepLteOnWifi = mod.settingBoolean(SETTING_KEY, true);

            Class<?> csCls = Hooks.findClass("com.android.server.ConnectivityService", null);
            Class<?> ncCls = Hooks.findClass("android.net.NetworkConfig", null);

            // 1. Hide TYPE_WIFI default flag while handling Wi-Fi as a secondary link alongside LTE
            Hooks.findAndHookMethod(ncCls, "isDefault", new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param param) {
                    try {
                        if (!isSuppressingWifiDefault() || param.thisObject == null) return;
                        Object t = Hooks.getObjectField(param.thisObject, "type");
                        if (t instanceof Integer && ((Integer) t).intValue() == TYPE_WIFI) {
                            param.setResult(Boolean.FALSE);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });

            // 2. Sync live settings in system_server via HMG-API public state broadcasts
            Hooks.findAndHookMethod(csCls, "systemReady", new MethodHook() {
                @Override
                protected void afterHookedMethod(Param param) {
                    try {
                        if (receiverRegistered || param.thisObject == null) return;
                        Object ctxObj = Hooks.getObjectField(param.thisObject, "mContext");
                        if (!(ctxObj instanceof Context)) return;
                        Context ctx = (Context) ctxObj;
                        receiverRegistered = true;
                        reloadStateFromPublicJson();
                        ctx.registerReceiver(new BroadcastReceiver() {
                            @Override
                            public void onReceive(Context context, Intent intent) {
                                reloadStateFromPublicJson();
                            }
                        }, new IntentFilter(ACTION_HMGAPI_STATE_CHANGED));
                        logInfo("ConnectivityService hooked in system_server (keep_lte_on_wifi=" + keepLteOnWifi + ")");
                    } catch (Throwable t) {
                        logError("systemReady hook failed", t);
                    }
                }
            });

            // 3. Allow MOBILE to reclaim default priority even if WIFI came up earlier during boot
            Hooks.findAndHookMethod(csCls, "isNewNetTypePreferredOverCurrentNetType", int.class, new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param param) {
                    try {
                        if (!isActive() || param.args == null || param.args.length < 1) return;
                        int newType = ((Integer) param.args[0]).intValue();
                        int activeDefault = getActiveDefaultNetwork(param.thisObject);
                        if (shouldPreferMobileOverWifi(true, newType, activeDefault)) {
                            param.setResult(Boolean.TRUE);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });

            // 4. Track enforcePreference() so preference changes never tear down LTE
            final ThreadLocal<Boolean> enforcePushed = new ThreadLocal<Boolean>();
            Hooks.findAndHookMethod(csCls, "enforcePreference", new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param param) {
                    enforcePushed.set(Boolean.TRUE);
                    IN_ENFORCE_PREF.set(Boolean.TRUE);
                }

                @Override
                protected void afterHookedMethod(Param param) {
                    if (Boolean.TRUE.equals(enforcePushed.get())) {
                        enforcePushed.remove();
                        IN_ENFORCE_PREF.remove();
                    }
                }
            });

            // 5. Block MOBILE teardown while WIFI is active; demote WIFI default routes instead of tearing WIFI down
            Hooks.findAndHookMethod(csCls, "teardown", "android.net.NetworkStateTracker", new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param param) {
                    try {
                        if (!isActive() || param.args == null || param.args.length < 1 || param.args[0] == null) {
                            return;
                        }
                        Object niObj = Hooks.callMethod(param.args[0], "getNetworkInfo");
                        if (!(niObj instanceof NetworkInfo)) return;
                        int trackerType = ((NetworkInfo) niObj).getType();
                        boolean inEnforce = Boolean.TRUE.equals(IN_ENFORCE_PREF.get());
                        boolean inTakeover = Boolean.TRUE.equals(IN_MOBILE_TAKEOVER.get());
                        boolean wifiConnected = isNetworkConnected(param.thisObject, TYPE_WIFI);

                        if (shouldBlockMobileTeardown(true, trackerType, inEnforce, wifiConnected)) {
                            logInfo("blocked teardown of MOBILE while WIFI stays secondary");
                            param.setResult(Boolean.FALSE);
                        } else if (shouldConvertWifiTeardownToDemote(true, trackerType, inTakeover)) {
                            demoteWifiDefaultRoutes(param.thisObject);
                            logInfo("demoted WIFI to secondary link on MOBILE takeover (kept wlan0 up)");
                            param.setResult(Boolean.TRUE);
                        }
                    } catch (Throwable t) {
                        logError("teardown hook failed", t);
                    }
                }
            });

            // 6. Captive portal check: keep WIFI non-default when LTE is up
            final ThreadLocal<Boolean> cpPushed = new ThreadLocal<Boolean>();
            Hooks.findAndHookMethod(csCls, "handleCaptivePortalTrackerCheck", "android.net.NetworkInfo", new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param param) {
                    try {
                        if (param.args == null || param.args.length < 1 || !(param.args[0] instanceof NetworkInfo)) return;
                        NetworkInfo info = (NetworkInfo) param.args[0];
                        int activeDefault = getActiveDefaultNetwork(param.thisObject);
                        boolean mobileConn = isNetworkConnected(param.thisObject, TYPE_MOBILE);
                        if (shouldSuppressWifiDefaultForConnect(isActive(), info.getType(), activeDefault, mobileConn)) {
                            pushSuppressWifiDefault();
                            cpPushed.set(Boolean.TRUE);
                        }
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                protected void afterHookedMethod(Param param) {
                    if (Boolean.TRUE.equals(cpPushed.get())) {
                        cpPushed.remove();
                        popSuppressWifiDefault();
                    }
                }
            });

            // 7. handleConnect: connect WIFI as secondary while LTE stays default, or promote MOBILE over WIFI
            final ThreadLocal<Boolean> hcSuppressPushed = new ThreadLocal<Boolean>();
            final ThreadLocal<Boolean> hcTakeoverPushed = new ThreadLocal<Boolean>();
            Hooks.findAndHookMethod(csCls, "handleConnect", "android.net.NetworkInfo", new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param param) {
                    try {
                        if (param.args == null || param.args.length < 1 || !(param.args[0] instanceof NetworkInfo)) return;
                        NetworkInfo info = (NetworkInfo) param.args[0];
                        int netType = info.getType();
                        int activeDefault = getActiveDefaultNetwork(param.thisObject);
                        boolean mobileConn = isNetworkConnected(param.thisObject, TYPE_MOBILE);
                        if (shouldSuppressWifiDefaultForConnect(isActive(), netType, activeDefault, mobileConn)) {
                            pushSuppressWifiDefault();
                            hcSuppressPushed.set(Boolean.TRUE);
                            logInfo("kept MOBILE default while connecting WIFI as secondary");
                        } else if (shouldPreferMobileOverWifi(isActive(), netType, activeDefault)) {
                            IN_MOBILE_TAKEOVER.set(Boolean.TRUE);
                            hcTakeoverPushed.set(Boolean.TRUE);
                        }
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                protected void afterHookedMethod(Param param) {
                    if (Boolean.TRUE.equals(hcSuppressPushed.get())) {
                        hcSuppressPushed.remove();
                        popSuppressWifiDefault();
                    }
                    if (Boolean.TRUE.equals(hcTakeoverPushed.get())) {
                        hcTakeoverPushed.remove();
                        IN_MOBILE_TAKEOVER.remove();
                    }
                }
            });

            // 8a. handleConnectivityChange: write WIFI default routes to secondary table when LTE is up
            final ThreadLocal<Boolean> hccPushed = new ThreadLocal<Boolean>();
            Hooks.findAndHookMethod(csCls, "handleConnectivityChange", int.class, boolean.class, new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param param) {
                    try {
                        if (param.args == null || param.args.length < 1) return;
                        int netType = ((Integer) param.args[0]).intValue();
                        int activeDefault = getActiveDefaultNetwork(param.thisObject);
                        boolean mobileConn = isNetworkConnected(param.thisObject, TYPE_MOBILE);
                        if (shouldSuppressWifiDefaultForConnect(isActive(), netType, activeDefault, mobileConn)) {
                            pushSuppressWifiDefault();
                            hccPushed.set(Boolean.TRUE);
                        }
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                protected void afterHookedMethod(Param param) {
                    if (Boolean.TRUE.equals(hccPushed.get())) {
                        hccPushed.remove();
                        popSuppressWifiDefault();
                    }
                }
            });

            // 8b. handleDnsConfigurationChange: avoid overwriting global default DNS with wlan0 DNS
            final ThreadLocal<Boolean> hdccPushed = new ThreadLocal<Boolean>();
            Hooks.findAndHookMethod(csCls, "handleDnsConfigurationChange", int.class, new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param param) {
                    try {
                        if (param.args == null || param.args.length < 1) return;
                        int netType = ((Integer) param.args[0]).intValue();
                        int activeDefault = getActiveDefaultNetwork(param.thisObject);
                        boolean mobileConn = isNetworkConnected(param.thisObject, TYPE_MOBILE);
                        if (shouldSuppressWifiDefaultForConnect(isActive(), netType, activeDefault, mobileConn)) {
                            pushSuppressWifiDefault();
                            hdccPushed.set(Boolean.TRUE);
                        }
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                protected void afterHookedMethod(Param param) {
                    if (Boolean.TRUE.equals(hdccPushed.get())) {
                        hdccPushed.remove();
                        popSuppressWifiDefault();
                    }
                }
            });

            // 9. handleDisconnect: keep WIFI disconnect from disturbing MOBILE default network state
            final ThreadLocal<Boolean> hdPushed = new ThreadLocal<Boolean>();
            Hooks.findAndHookMethod(csCls, "handleDisconnect", "android.net.NetworkInfo", new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param param) {
                    try {
                        if (param.args == null || param.args.length < 1 || !(param.args[0] instanceof NetworkInfo)) return;
                        NetworkInfo info = (NetworkInfo) param.args[0];
                        int activeDefault = getActiveDefaultNetwork(param.thisObject);
                        if (shouldSuppressWifiDefaultForDisconnect(isActive(), info.getType(), activeDefault)) {
                            pushSuppressWifiDefault();
                            hdPushed.set(Boolean.TRUE);
                        }
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                protected void afterHookedMethod(Param param) {
                    if (Boolean.TRUE.equals(hdPushed.get())) {
                        hdPushed.remove();
                        popSuppressWifiDefault();
                    }
                }
            });

            logInfo("zygoteInit armed ConnectivityService hooks (keep_lte_on_wifi=" + keepLteOnWifi + ")");
        } catch (Throwable t) {
            logError("zygoteInit failed", t);
        }
    }

    @Override
    public void onEnabled(ModContext mod, boolean enabled) {
        modRef = mod;
        hookEnabled = enabled;
        logInfo("onEnabled=" + enabled);
    }

    @Override
    public void onSettingsChanged(ModContext mod) {
        modRef = mod;
        keepLteOnWifi = mod.settingBoolean(SETTING_KEY, true);
        logInfo("onSettingsChanged keep_lte_on_wifi=" + keepLteOnWifi);
    }

    private static void reloadStateFromPublicJson() {
        try {
            File f = new File(PUBLIC_STATE_PATH);
            if (!f.isFile() || !f.canRead()) return;
            String raw = readUtf8(f);
            JSONObject root = new JSONObject(raw);
            JSONObject mods = root.optJSONObject("mods");
            if (mods == null) return;
            JSONObject self = mods.optJSONObject(MOD_ID);
            if (self == null) {
                hookEnabled = false;
                return;
            }
            hookEnabled = self.optBoolean("enabled", true);
            JSONObject settings = self.optJSONObject("settings");
            keepLteOnWifi = settings == null || settings.optBoolean(SETTING_KEY, true);
        } catch (Throwable t) {
            logError("reloadStateFromPublicJson failed", t);
        }
    }

    private static int getActiveDefaultNetwork(Object cs) {
        if (cs == null) return -1;
        try {
            Object val = Hooks.getObjectField(cs, "mActiveDefaultNetwork");
            return val instanceof Integer ? ((Integer) val).intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static boolean isNetworkConnected(Object cs, int netType) {
        if (cs == null || netType < 0) return false;
        try {
            Object arrObj = Hooks.getObjectField(cs, "mNetTrackers");
            if (!(arrObj instanceof Object[])) return false;
            Object[] trackers = (Object[]) arrObj;
            if (netType >= trackers.length || trackers[netType] == null) return false;
            Object niObj = Hooks.callMethod(trackers[netType], "getNetworkInfo");
            return niObj instanceof NetworkInfo && ((NetworkInfo) niObj).isConnected();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void demoteWifiDefaultRoutes(Object cs) {
        if (cs == null) return;
        try {
            Object lpsObj = Hooks.getObjectField(cs, "mCurrentLinkProperties");
            if (!(lpsObj instanceof Object[])) return;
            Object[] lps = (Object[]) lpsObj;
            if (TYPE_WIFI >= lps.length || lps[TYPE_WIFI] == null) return;
            Object wifiLp = lps[TYPE_WIFI];
            Object routesObj = Hooks.callMethod(wifiLp, "getAllRoutes");
            if (!(routesObj instanceof Collection)) return;
            for (Object route : (Collection<?>) routesObj) {
                if (route == null) continue;
                Object isDef = Hooks.callMethod(route, "isDefaultRoute");
                if (Boolean.TRUE.equals(isDef)) {
                    Hooks.callMethod(cs, "removeRoute", wifiLp, route, Boolean.TRUE);
                    Hooks.callMethod(cs, "addRoute", wifiLp, route, Boolean.FALSE, Boolean.FALSE);
                }
            }
        } catch (Throwable t) {
            logError("demoteWifiDefaultRoutes failed", t);
        }
    }

    private static String readUtf8(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    private static void logInfo(String msg) {
        ModContext m = modRef;
        if (m != null) m.log(msg);
    }

    private static void logError(String msg, Throwable t) {
        ModContext m = modRef;
        if (m != null) m.error(msg, t);
    }

    public static void runSelfTest() {
        if (!shouldSuppressWifiDefaultForConnect(true, TYPE_WIFI, TYPE_MOBILE, true)
                || !shouldSuppressWifiDefaultForConnect(true, TYPE_WIFI, -1, true)
                || shouldSuppressWifiDefaultForConnect(true, TYPE_WIFI, -1, false)
                || shouldSuppressWifiDefaultForConnect(true, TYPE_MOBILE, TYPE_WIFI, false)
                || shouldSuppressWifiDefaultForConnect(false, TYPE_WIFI, TYPE_MOBILE, true)) {
            throw new AssertionError("shouldSuppressWifiDefaultForConnect matrix failed");
        }
        if (!shouldSuppressWifiDefaultForDisconnect(true, TYPE_WIFI, TYPE_MOBILE)
                || shouldSuppressWifiDefaultForDisconnect(true, TYPE_WIFI, TYPE_WIFI)
                || shouldSuppressWifiDefaultForDisconnect(true, TYPE_MOBILE, TYPE_MOBILE)
                || shouldSuppressWifiDefaultForDisconnect(false, TYPE_WIFI, TYPE_MOBILE)) {
            throw new AssertionError("shouldSuppressWifiDefaultForDisconnect matrix failed");
        }
        if (!shouldPreferMobileOverWifi(true, TYPE_MOBILE, TYPE_WIFI)
                || shouldPreferMobileOverWifi(true, TYPE_WIFI, TYPE_MOBILE)
                || shouldPreferMobileOverWifi(true, TYPE_MOBILE, TYPE_MOBILE)
                || shouldPreferMobileOverWifi(false, TYPE_MOBILE, TYPE_WIFI)) {
            throw new AssertionError("shouldPreferMobileOverWifi matrix failed");
        }
        if (!shouldBlockMobileTeardown(true, TYPE_MOBILE, true, false)
                || !shouldBlockMobileTeardown(true, TYPE_MOBILE, false, true)
                || shouldBlockMobileTeardown(true, TYPE_MOBILE, false, false)
                || shouldBlockMobileTeardown(true, TYPE_WIFI, true, true)
                || shouldBlockMobileTeardown(false, TYPE_MOBILE, true, true)) {
            throw new AssertionError("shouldBlockMobileTeardown matrix failed");
        }
        if (!shouldConvertWifiTeardownToDemote(true, TYPE_WIFI, true)
                || shouldConvertWifiTeardownToDemote(true, TYPE_WIFI, false)
                || shouldConvertWifiTeardownToDemote(true, TYPE_MOBILE, true)
                || shouldConvertWifiTeardownToDemote(false, TYPE_WIFI, true)) {
            throw new AssertionError("shouldConvertWifiTeardownToDemote matrix failed");
        }
        System.out.println("HOOK SELF-TEST PASSED!");
    }

    public static void main(String[] args) {
        runSelfTest();
    }
}
