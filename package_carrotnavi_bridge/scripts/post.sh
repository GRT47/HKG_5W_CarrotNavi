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

# Create compatibility symlink so both /data/nemesis/apps/carrotnavi.bridge and /data/nemesis/packages/carrotnavi.bridge resolve
mkdir -p /data/nemesis/packages 2>/dev/null
if [ "$INSTALL_DIR" = "/data/nemesis/apps/carrotnavi.bridge" ]; then
    ln -s "$INSTALL_DIR" /data/nemesis/packages/carrotnavi.bridge 2>/dev/null
fi

chmod 755 "$INSTALL_DIR/bin/launch.sh" "$INSTALL_DIR/svc/bridge-daemon.sh" "$INSTALL_DIR/scripts/tbt-dump.sh" 2>/dev/null
chmod 644 "$INSTALL_DIR/bin/carrotbridge.jar" "$INSTALL_DIR/version.json" "$INSTALL_DIR/NEMESIS.data" 2>/dev/null
mkdir -p /data/dalvik-cache 2>/dev/null
mkdir -p /data/local/tmp/dalvik-cache 2>/dev/null

# 1. Disable ADB TCP to prevent eth0 (192.168.0.123/24) routing conflict with wlan0 (192.168.0.1/24)
setprop persist.service.adb.tcp.enable 0
if [ "$(getprop persist.sys.usb.config)" != "adb" ]; then
    stop adbd >/dev/null 2>&1
fi
ENG_PREF="/data/data/com.hkmc.system.app.engineering/shared_prefs/com.hkmc.system.app.engineering_preferences.xml"
if [ -f "$ENG_PREF" ]; then
    sed -i 's/<boolean name="adb_tcp_setting" value="true" \/>/<boolean name="adb_tcp_setting" value="false" \/>/g' "$ENG_PREF" 2>/dev/null
fi
ip addr flush dev eth0 >/dev/null 2>&1
ip link set eth0 down >/dev/null 2>&1
ifconfig eth0 0.0.0.0 down >/dev/null 2>&1

# 2. Enable Auto-Hotspot properties (Hotspot Activation = on, Hotspot APN = Public)
setprop persist.sys.hotspot.auto 1
setprop wifi.hotspot.subscription true
setprop wifi.hotspot.apn Public

# 3. Disable STA Wi-Fi client if active, then enable SoftAP (tx 29 = setWifiApEnabled(null, true))
wifi_state="$(service call wifi 14 2>/dev/null)"
case "$wifi_state" in
    *00000002*|*00000003*)
        service call wifi 13 i32 0 >/dev/null 2>&1
        sleep 1
        ;;
esac
service call wifi 29 i32 0 i32 1 >/dev/null 2>&1
settings put global wifi_ap_on 1 >/dev/null 2>&1
am startservice -n com.hkmc.wifisettings/.service.HotspotService >/dev/null 2>&1

# 4. Wake up com.mnsoft.navi [Cluster] plain-text TBT logger (intArray[14] == 0 -> gq=false)
am broadcast -a com.android.Eng.LogSetting --eia intArray 0,0,0,0,0,0,0,0,0,0,0,0,0,0,0 >/dev/null 2>&1

# 5. Request Status Dashboard UI to open as soon as NEMESIS svcmgr starts bridge-daemon.sh
echo 1 > /data/local/tmp/carrotbridge_show_ui 2>/dev/null

exit 0
