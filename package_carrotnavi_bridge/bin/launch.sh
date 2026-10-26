#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
export ANDROID_ROOT=/system
export ANDROID_DATA=/data
export BOOTCLASSPATH=/system/framework/core.jar:/system/framework/conscrypt.jar:/system/framework/okhttp.jar:/system/framework/core-junit.jar:/system/framework/bouncycastle.jar:/system/framework/ext.jar:/system/framework/framework.jar:/system/framework/framework2.jar:/system/framework/telephony-common.jar:/system/framework/voip-common.jar:/system/framework/mms-common.jar:/system/framework/android.policy.jar:/system/framework/services.jar:/system/framework/apache-xml.jar:/system/framework/webviewchromium.jar:/system/framework/automotive.jar

INSTALL_DIR="/data/nemesis/packages/carrotnavi.bridge"

# 1. Immediately signal the Status Dashboard Overlay to appear on screen
echo 1 > /data/local/tmp/carrotbridge_show_ui 2>/dev/null

# 2. Ensure carrot-7706-bridge background service is running
if ! ps 2>/dev/null | grep -q "[b]ridge-daemon.sh"; then
    /system/bin/sh "$INSTALL_DIR/svc/bridge-daemon.sh" </dev/null >>/data/local/tmp/carrotbridge_svc.log 2>&1 &
elif ! ps 2>/dev/null | grep -q "[C]arrotBridgeMain"; then
    CLASSPATH="$INSTALL_DIR/bin/carrotbridge.jar" /system/bin/app_process /system/bin com.hkg.carrotbridge.CarrotBridgeMain daemon "$INSTALL_DIR" </dev/null >>/data/local/tmp/carrotbridge_daemon.log 2>&1 &
fi

# 3. Run Hotspot assertion, TBT logger wakeup, and GitHub Auto-Update check asynchronously in background
(
    if [ "$(getprop persist.sys.hotspot.auto)" != "0" ]; then
        setprop wifi.hotspot.subscription true
        setprop wifi.hotspot.apn Public
        ap_state="$(service call wifi 30 2>/dev/null)"
        case "$ap_state" in
            *0000000d*|*0000000c*)
                ;;
            *)
                wifi_state="$(service call wifi 14 2>/dev/null)"
                case "$wifi_state" in
                    *00000002*|*00000003*)
                        service call wifi 13 i32 0 >/dev/null 2>&1
                        sleep 1
                        ;;
                esac
                service call wifi 29 i32 0 i32 1 >/dev/null 2>&1
                settings put global wifi_ap_on 1 >/dev/null 2>&1
                ;;
        esac
    fi

    am broadcast -a com.android.Eng.LogSetting --eia intArray 0,0,0,0,0,0,0,0,0,0,0,0,0,0,0 >/dev/null 2>&1
    if [ -x "$INSTALL_DIR/scripts/tbt-dump.sh" ]; then
        /system/bin/sh "$INSTALL_DIR/scripts/tbt-dump.sh" >/dev/null 2>&1 &
    fi

    if [ -f "$INSTALL_DIR/bin/carrotbridge.jar" ]; then
        CLASSPATH="$INSTALL_DIR/bin/carrotbridge.jar" /system/bin/app_process /system/bin com.hkg.carrotbridge.CarrotBridgeMain update GRT47 HKG_5W_CarrotNavi main "$INSTALL_DIR" >> /data/local/tmp/carrotbridge_updater.log 2>&1
        if [ "$?" = "10" ]; then
            /system/bin/busybox pkill -f "com.hkg.carrotbridge.CarrotBridgeMain daemon" >/dev/null 2>&1
        fi
    fi
) </dev/null >/dev/null 2>&1 &

exit 0

