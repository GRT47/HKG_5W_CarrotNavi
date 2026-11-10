#!/system/bin/sh
# Precision TBT + SDI + ClusterService Dumper (Local & USB)
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
export ANDROID_ROOT=/system
export ANDROID_DATA=/data

LOCAL_DUMP_DIR="/data/local/tmp/hkg_tbt_dump"
mkdir -p "$LOCAL_DUMP_DIR" 2>/dev/null

# Wake up com.mnsoft.navi [Cluster] logger
am broadcast -a com.android.Eng.LogSetting --eia intArray 0,0,0,0,0,0,0,0,0,0,0,0,0,0,0 >/dev/null 2>&1

# 1. Capture live snapshots to local dump dir
dumpsys com.hkmc.micom.cluster > "$LOCAL_DUMP_DIR/dumpsys_cluster_latest.txt" 2>&1
logcat -d -v time ClusterService:D NavigationCluster:D Java_Layer:I BrdCast:D CarrotBridge:I *:S > "$LOCAL_DUMP_DIR/logcat_navi_recent.txt" 2>&1
getprop > "$LOCAL_DUMP_DIR/getprop.txt" 2>&1
ip addr > "$LOCAL_DUMP_DIR/ip_addr.txt" 2>&1
ip route show table all > "$LOCAL_DUMP_DIR/ip_route_all.txt" 2>&1
cat /proc/net/arp > "$LOCAL_DUMP_DIR/arp_table.txt" 2>&1

if [ -f "/data/local/tmp/carrotbridge_status.json" ]; then
    cp "/data/local/tmp/carrotbridge_status.json" "$LOCAL_DUMP_DIR/carrotbridge_status.json" 2>/dev/null
fi
if [ -f "/data/local/tmp/carrotbridge_updater.log" ]; then
    cp "/data/local/tmp/carrotbridge_updater.log" "$LOCAL_DUMP_DIR/carrotbridge_updater.log" 2>/dev/null
fi

# 1b. Boot wireless auto-activation diagnostics (핫스팟(AP) / WiFi(Client) / 끄기(수동))
if [ -d "/data/nemesis/apps/carrotnavi.bridge" ]; then
    INSTALL_DIR="/data/nemesis/apps/carrotnavi.bridge"
else
    INSTALL_DIR="/data/nemesis/packages/carrotnavi.bridge"
fi
{
    echo "== captured: $(date)"
    for f in hotspot_auto.conf wifi_auto.conf comma_tx_enabled.conf; do
        echo "config/$f: $(cat "$INSTALL_DIR/config/$f" 2>/dev/null)"
    done
    if [ -f /data/local/tmp/carrotbridge_hotspot_pause ]; then
        echo "hotspot_pause_file: present"
    else
        echo "hotspot_pause_file: absent"
    fi
    # IWifiManager: 14 = getWifiEnabledState (3 = STA on), 30 = getWifiApEnabledState (13 = AP on)
    echo "getWifiEnabledState(14): $(service call wifi 14 2>&1)"
    echo "getWifiApEnabledState(30): $(service call wifi 30 2>&1)"
    for p in persist.sys.hotspot.auto wifi.hotspot.subscription wifi.hotspot.apn tcc.wifi.firmware.mode \
             wlan.driver.status persist.wifi.state sys.boot_completed sys.hkmc.navistatus; do
        echo "$p=$(getprop "$p")"
    done
    echo "== wireless processes"
    ps | grep -E "hostapd|wpa_supplicant|dnsmasq|dhcpcd"
} > "$LOCAL_DUMP_DIR/wireless_state.txt" 2>&1
logcat -d -v time WifiService:V WifiStateMachine:V WifiController:V WifiSettings:V SoftapController:V \
    Tethering:V PhoneProjection:I CarrotBridge:I *:S > "$LOCAL_DUMP_DIR/logcat_wifi_recent.txt" 2>&1
if [ -f "/data/local/tmp/carrotbridge_svc.log" ]; then
    cp "/data/local/tmp/carrotbridge_svc.log" "$LOCAL_DUMP_DIR/carrotbridge_svc.log" 2>/dev/null
fi
for nlog in /data/nemesis/logs/carrotnavi.bridge/*.log /data/nemesis/log/carrotnavi.bridge/*.log; do
    [ -f "$nlog" ] && cp "$nlog" "$LOCAL_DUMP_DIR/nemesis_$(basename "$nlog")" 2>/dev/null
done

# 2. Detect mounted USB storage
USB_DIR=""
for candidate in /storage/usb0 /mnt/media_rw/usb0 /storage/usb1 /mnt/media_rw/usb1 /mnt/usb0; do
    if [ -d "$candidate" ] && grep -q "$candidate" /proc/mounts 2>/dev/null; then
        USB_DIR="$candidate"
        break
    fi
done

if [ -n "$USB_DIR" ]; then
    OUT_DIR="$USB_DIR/HKG_TBT_Dump"
    mkdir -p "$OUT_DIR" 2>/dev/null
    mkdir -p "$OUT_DIR/system_app" 2>/dev/null

    cp "$LOCAL_DUMP_DIR/"* "$OUT_DIR/" 2>/dev/null

    # Copy HKMC_Navi_ExSL.apk & .odex once if not already on USB
    if [ ! -f "$OUT_DIR/system_app/HKMC_Navi_ExSL.odex" ] && [ -f "/system/app/HKMC_Navi_ExSL.odex" ]; then
        cp "/system/app/HKMC_Navi_ExSL.odex" "$OUT_DIR/system_app/HKMC_Navi_ExSL.odex" 2>/dev/null
    fi
    if [ ! -f "$OUT_DIR/system_app/HKMC_Navi_ExSL.apk" ] && [ -f "/system/app/HKMC_Navi_ExSL.apk" ]; then
        cp "/system/app/HKMC_Navi_ExSL.apk" "$OUT_DIR/system_app/HKMC_Navi_ExSL.apk" 2>/dev/null
    fi
    if [ ! -f "$OUT_DIR/system_app/automotive.odex" ] && [ -f "/system/framework/automotive.odex" ]; then
        cp "/system/framework/automotive.odex" "$OUT_DIR/system_app/automotive.odex" 2>/dev/null
    fi
    if [ ! -f "$OUT_DIR/system_app/automotive-service.odex" ] && [ -f "/system/framework/automotive-service.odex" ]; then
        cp "/system/framework/automotive-service.odex" "$OUT_DIR/system_app/automotive-service.odex" 2>/dev/null
    fi

    sync
    log -t CarrotBridge "Exported TBT precision dump to $OUT_DIR" 2>/dev/null
fi

exit 0
