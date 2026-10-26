#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
export ANDROID_ROOT=/system
export ANDROID_DATA=/data
export BOOTCLASSPATH=/system/framework/core.jar:/system/framework/conscrypt.jar:/system/framework/okhttp.jar:/system/framework/core-junit.jar:/system/framework/bouncycastle.jar:/system/framework/ext.jar:/system/framework/framework.jar:/system/framework/framework2.jar:/system/framework/telephony-common.jar:/system/framework/voip-common.jar:/system/framework/mms-common.jar:/system/framework/android.policy.jar:/system/framework/services.jar:/system/framework/apache-xml.jar:/system/framework/webviewchromium.jar:/system/framework/automotive.jar

if [ -d "/data/nemesis/apps/carrotnavi.bridge" ]; then
    INSTALL_DIR="/data/nemesis/apps/carrotnavi.bridge"
else
    INSTALL_DIR="/data/nemesis/packages/carrotnavi.bridge"
fi
mkdir -p /data/dalvik-cache 2>/dev/null
SVC_PID_FILE="/data/local/tmp/carrotbridge_svc.pid"
JAVA_PID_FILE="/data/local/tmp/carrotbridge_java.pid"

if [ -f "$SVC_PID_FILE" ]; then
    old_svc_pid="$(cat "$SVC_PID_FILE" 2>/dev/null)"
    if [ -n "$old_svc_pid" ] && [ "$old_svc_pid" != "$$" ] && kill -0 "$old_svc_pid" 2>/dev/null; then
        exit 0
    fi
fi
echo "$$" > "$SVC_PID_FILE" 2>/dev/null
ENG_PREF="/data/data/com.hkmc.system.app.engineering/shared_prefs/com.hkmc.system.app.engineering_preferences.xml"
BRIDGE_PID=""
LOOP_COUNT=0
BRIDGE_START_LOOP=0

log_info() {
    echo "[CarrotBridge] $*"
    log -t CarrotBridge "$*" 2>/dev/null
}

kill_bridge_java() {
    if [ -n "$BRIDGE_PID" ]; then
        kill "$BRIDGE_PID" >/dev/null 2>&1
        BRIDGE_PID=""
    fi
    /system/bin/busybox pkill -f "com.hkg.carrotbridge.CarrotBridgeMain daemon" >/dev/null 2>&1
    pkill -f "com.hkg.carrotbridge.CarrotBridgeMain daemon" >/dev/null 2>&1
}

disable_adb_tcp() {
    if [ "$(getprop persist.service.adb.tcp.enable)" = "1" ]; then
        setprop persist.service.adb.tcp.enable 0
        if [ "$(getprop persist.sys.usb.config)" != "adb" ]; then
            stop adbd >/dev/null 2>&1
        fi
    fi
    if [ -f "$ENG_PREF" ] && grep -q 'name="adb_tcp_setting" value="true"' "$ENG_PREF" 2>/dev/null; then
        sed -i 's/<boolean name="adb_tcp_setting" value="true" \/>/<boolean name="adb_tcp_setting" value="false" \/>/g' "$ENG_PREF" 2>/dev/null
    fi
    if ip addr show dev eth0 2>/dev/null | grep -q "192.168.0."; then
        ip addr flush dev eth0 >/dev/null 2>&1
        ip link set eth0 down >/dev/null 2>&1
        ifconfig eth0 0.0.0.0 down >/dev/null 2>&1
    fi
}

apply_hotspot_props() {
    disable_adb_tcp
    if [ "$(getprop wifi.hotspot.subscription)" != "true" ]; then
        setprop wifi.hotspot.subscription true
    fi
    if [ "$(getprop wifi.hotspot.apn)" != "Public" ]; then
        setprop wifi.hotspot.apn Public
    fi
}

is_softap_active() {
    ap_state="$(service call wifi 30 2>/dev/null)"
    case "$ap_state" in
        *0000000d*|*0000000c*)
            return 0
            ;;
        *Parcel*)
            return 1
            ;;
    esac
    if [ "$(getprop tcc.wifi.firmware.mode)" = "softap" ] && [ "$(getprop wlan.driver.status)" = "ok" ]; then
        return 0
    fi
    return 1
}

enable_hotspot() {
    apply_hotspot_props
    wifi_state="$(service call wifi 14 2>/dev/null)"
    case "$wifi_state" in
        *00000002*|*00000003*)
            service call wifi 13 i32 0 >/dev/null 2>&1
            sleep 1
            ;;
    esac
    service call wifi 29 i32 0 i32 1 >/dev/null 2>&1
    log_info "Triggered setWifiApEnabled(null, true) [subscription=true, apn=Public]"
}

disable_hotspot() {
    service call wifi 29 i32 0 i32 0 >/dev/null 2>&1
    log_info "Triggered setWifiApEnabled(null, false)"
}

start_bridge_java() {
    if [ -n "$BRIDGE_PID" ] && kill -0 "$BRIDGE_PID" 2>/dev/null; then
        return 0
    fi
    if [ -f "$JAVA_PID_FILE" ]; then
        saved_jpid="$(cat "$JAVA_PID_FILE" 2>/dev/null)"
        if [ -n "$saved_jpid" ] && kill -0 "$saved_jpid" 2>/dev/null; then
            BRIDGE_PID="$saved_jpid"
            return 0
        fi
    fi
    kill_bridge_java
    CLASSPATH="$INSTALL_DIR/bin/carrotbridge.jar" /system/bin/app_process /system/bin com.hkg.carrotbridge.CarrotBridgeMain daemon "$INSTALL_DIR" 2>&1 &
    BRIDGE_PID="$!"
    echo "$BRIDGE_PID" > "$JAVA_PID_FILE" 2>/dev/null
    BRIDGE_START_LOOP="$LOOP_COUNT"
    log_info "Started CarrotBridgeMain daemon (pid=$BRIDGE_PID, dir=$INSTALL_DIR)"
}

check_bridge_java_watchdog() {
    if [ -z "$BRIDGE_PID" ] || ! kill -0 "$BRIDGE_PID" 2>/dev/null; then
        elapsed_loops=$((LOOP_COUNT - ${BRIDGE_START_LOOP:-0}))
        if [ "$elapsed_loops" -ge 0 ] && [ "$elapsed_loops" -le 3 ] && [ -f "$INSTALL_DIR/bin/carrotbridge.jar.bak" ]; then
            log_info "Daemon exited within ${elapsed_loops} loops! Rolling back carrotbridge.jar.bak -> carrotbridge.jar"
            cp "$INSTALL_DIR/bin/carrotbridge.jar.bak" "$INSTALL_DIR/bin/carrotbridge.jar" 2>/dev/null
            rm -f "$INSTALL_DIR/bin/carrotbridge.jar.bak" 2>/dev/null
        fi
        start_bridge_java
    fi
}

run_github_update_check() {
    log_info "Running GitHub Auto-Update check (GRT47/HKG_5W_CarrotNavi)..."
    CLASSPATH="$INSTALL_DIR/bin/carrotbridge.jar" /system/bin/app_process /system/bin com.hkg.carrotbridge.CarrotBridgeMain update GRT47 HKG_5W_CarrotNavi main "$INSTALL_DIR" 2>&1
    rc="$?"
    if [ "$rc" = "10" ]; then
        log_info "GitHub update applied -> restarting CarrotBridgeMain daemon"
        kill_bridge_java
        start_bridge_java
    fi
}

on_stop() {
    log_info "Service stopped via NEMESIS Manager"
    rm -f "$SVC_PID_FILE" "$JAVA_PID_FILE" 2>/dev/null
    kill_bridge_java
    exit 0
}
trap 'on_stop' TERM INT

log_info "Service started (pid=$$)"

if [ -z "$(getprop persist.sys.hotspot.auto)" ]; then
    setprop persist.sys.hotspot.auto 1
fi

if [ "$(getprop persist.sys.hotspot.auto)" != "0" ]; then
    apply_hotspot_props
fi

while [ "$(getprop sys.boot_completed)" != "1" ]; do
    if [ "$(getprop persist.sys.hotspot.auto)" != "0" ]; then
        apply_hotspot_props
    fi
    sleep 2 &
    wait $!
done

sleep 2 &
wait $!

if [ "$(getprop persist.sys.hotspot.auto)" != "0" ]; then
    apply_hotspot_props
    if ! is_softap_active; then
        log_info "Boot completed: auto-enabling Wi-Fi Hotspot"
        enable_hotspot
    fi
fi

# Wake up com.mnsoft.navi [Cluster] plain-text TBT logger
am broadcast -a com.android.Eng.LogSetting --eia intArray 0,0,0,0,0,0,0,0,0,0,0,0,0,0,0 >/dev/null 2>&1

# Start the 7706 Java Bridge Daemon & MAP 3-Press UI Overlay once Android system services are up
start_bridge_java

LOOP_COUNT=0
USB_DUMPED_SESSION=0

while true; do
    LOOP_COUNT=$((LOOP_COUNT + 1))

    # 1. Auto-Hotspot maintenance
    auto_mode="$(getprop persist.sys.hotspot.auto)"
    sub_prop="$(getprop wifi.hotspot.subscription)"

    if [ "$auto_mode" != "0" ] && [ "$sub_prop" = "false" ]; then
        log_info "Hotspot Activation turned OFF in WiFi Test -> disabling auto-hotspot"
        setprop persist.sys.hotspot.auto 0
        disable_hotspot
        auto_mode="0"
    elif [ "$auto_mode" = "0" ] && [ "$sub_prop" = "true" ]; then
        log_info "Hotspot Activation turned ON in WiFi Test -> enabling auto-hotspot"
        setprop persist.sys.hotspot.auto 1
        setprop wifi.hotspot.apn Public
        enable_hotspot
        auto_mode="1"
    fi

    if [ "$auto_mode" != "0" ]; then
        apply_hotspot_props
        if ! is_softap_active; then
            enable_hotspot
        fi
    fi

    # 2. Supervise Java 7706 Bridge Daemon
    check_bridge_java_watchdog

    # 3. Automatic USB TBT Dump when USB stick is mounted
    if grep -qE "/storage/usb0|/mnt/media_rw/usb0" /proc/mounts 2>/dev/null; then
        if [ "$USB_DUMPED_SESSION" = "0" ] || [ $((LOOP_COUNT % 12)) -eq 0 ]; then
            /system/bin/sh "$INSTALL_DIR/scripts/tbt-dump.sh" >/dev/null 2>&1 &
            USB_DUMPED_SESSION=1
        fi
    else
        USB_DUMPED_SESSION=0
    fi

    # 4. GitHub Auto-Update check (~35s after boot = loop 7, and every 30 min = 360 loops)
    if [ "$LOOP_COUNT" -eq 7 ] || [ $((LOOP_COUNT % 360)) -eq 0 ]; then
        run_github_update_check &
    fi

    sleep 5 &
    wait $!
done
