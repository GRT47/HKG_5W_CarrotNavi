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
RAM_TMP="/dev/hkg_tmp"
mkdir -p "$RAM_TMP" 2>/dev/null && chmod 777 "$RAM_TMP" 2>/dev/null
SVC_PID_FILE="$RAM_TMP/carrotbridge_svc.pid"
SVC_LOG_FILE="$RAM_TMP/carrotbridge_svc.log"

# 1. Immediately signal the Status Dashboard Overlay to appear on screen
echo 1 > "$RAM_TMP/carrotbridge_show_ui" 2>/dev/null

# 2. Ensure carrot-7706-bridge background service is running (use busybox tr to handle NUL bytes in /proc/<pid>/cmdline)
svc_alive=0
for _chk_pid_file in "$SVC_PID_FILE" "/data/local/tmp/carrotbridge_svc.pid"; do
    if [ -f "$_chk_pid_file" ]; then
        svc_pid="$(cat "$_chk_pid_file" 2>/dev/null)"
        if [ -n "$svc_pid" ] && kill -0 "$svc_pid" 2>/dev/null; then
            svc_cmdline="$(/system/bin/busybox tr '\0' ' ' < "/proc/$svc_pid/cmdline" 2>/dev/null)"
            case "$svc_cmdline" in
                *bridge-daemon.sh*)
                    svc_alive=1
                    break
                    ;;
            esac
        fi
    fi
done
if [ "$svc_alive" = "0" ]; then
    rm -f "$SVC_PID_FILE" 2>/dev/null
    if [ -f "$SVC_LOG_FILE" ]; then
        svc_log_sz="$(/system/bin/busybox wc -c < "$SVC_LOG_FILE" 2>/dev/null)"
        if [ -n "$svc_log_sz" ] && [ "$svc_log_sz" -ge 262144 ] 2>/dev/null; then
            cp "$SVC_LOG_FILE" "${SVC_LOG_FILE}.old" 2>/dev/null
            : > "$SVC_LOG_FILE" 2>/dev/null
        fi
    fi
    /system/bin/sh "$INSTALL_DIR/svc/bridge-daemon.sh" </dev/null >>"$SVC_LOG_FILE" 2>&1 &
fi

# 3. Run Hotspot assertion, TBT logger wakeup, and signal the running daemon to check GitHub Auto-Update (avoids concurrent dexopt SIGABRT)
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

    echo 1 > "$RAM_TMP/carrotbridge_check_update" 2>/dev/null
) </dev/null >/dev/null 2>&1 &

exit 0

