#!/usr/bin/env python3
"""Build NEMESIS install package and USB distribution bundle for Engineering WiFi Info."""
from __future__ import annotations

import shutil
import zipfile
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
PKG_SRC_DIR = BASE_DIR / "src" / "engineering-wifiinfo"
USB_ROOT_DIR = BASE_DIR / "usb_root"
OUTER_ZIP_PATH = BASE_DIR.parent / "Engineering WiFi Info.zip"


def write_lf(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content.replace("\r\n", "\n").encode("utf-8"))


def build() -> None:
    if PKG_SRC_DIR.exists():
        shutil.rmtree(PKG_SRC_DIR)

    # 1. Package metadata (NEMESIS.data)
    pkg_meta = (
        "[nemesis]\n"
        "version=1.4\n"
        "id=engineering.wifiinfo\n"
        "name=Engineering WiFi Info\n"
        "types=application,service\n"
        "postinstall=scripts/post.sh\n"
        "preuninstall=scripts/preun.sh\n"
        "\n"
        "[app.0]\n"
        "kind=sh\n"
        "path=bin/launch.sh\n"
        "uid=root\n"
        "\n"
        "[service.0]\n"
        "name=hotspot-enabler\n"
        "kind=sh\n"
        "path=svc/hotspot-daemon.sh\n"
        "uid=root\n"
        "mode=background\n"
    )

    bootclasspath = (
        "/system/framework/core.jar:/system/framework/conscrypt.jar:"
        "/system/framework/okhttp.jar:/system/framework/core-junit.jar:"
        "/system/framework/bouncycastle.jar:/system/framework/ext.jar:"
        "/system/framework/framework.jar:/system/framework/framework2.jar:"
        "/system/framework/telephony-common.jar:/system/framework/voip-common.jar:"
        "/system/framework/mms-common.jar:/system/framework/android.policy.jar:"
        "/system/framework/services.jar:/system/framework/apache-xml.jar:"
        "/system/framework/webviewchromium.jar:/system/framework/automotive.jar"
    )

    # 2. Post-install script (scripts/post.sh)
    # Enables auto-hotspot, immediately activates SoftAP, and disables ADB TCP (eth0 192.168.0.123 conflict).
    post_sh = (
        "#!/system/bin/sh\n"
        "export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin\n"
        "export LD_LIBRARY_PATH=/vendor/lib:/system/lib\n"
        "export ANDROID_ROOT=/system\n"
        "export ANDROID_DATA=/data\n"
        f"export BOOTCLASSPATH={bootclasspath}\n"
        "\n"
        "# Disable ADB TCP to prevent eth0 (192.168.0.123/24) routing conflict with wlan0 (192.168.0.1/24)\n"
        "setprop persist.service.adb.tcp.enable 0\n"
        "if [ \"$(getprop persist.sys.usb.config)\" != \"adb\" ]; then\n"
        "    stop adbd >/dev/null 2>&1\n"
        "fi\n"
        "ENG_PREF=\"/data/data/com.hkmc.system.app.engineering/shared_prefs/com.hkmc.system.app.engineering_preferences.xml\"\n"
        "if [ -f \"$ENG_PREF\" ]; then\n"
        "    sed -i 's/<boolean name=\"adb_tcp_setting\" value=\"true\" \\/>/<boolean name=\"adb_tcp_setting\" value=\"false\" \\/>/g' \"$ENG_PREF\" 2>/dev/null\n"
        "fi\n"
        "ip addr flush dev eth0 >/dev/null 2>&1\n"
        "ip link set eth0 down >/dev/null 2>&1\n"
        "ifconfig eth0 0.0.0.0 down >/dev/null 2>&1\n"
        "\n"
        "setprop persist.sys.hotspot.auto 1\n"
        "setprop wifi.hotspot.subscription true\n"
        "setprop wifi.hotspot.apn Public\n"
        "\n"
        "# Disable STA client Wi-Fi if active (tx 14 = getWifiEnabledState, tx 13 = setWifiEnabled)\n"
        "wifi_state=\"$(service call wifi 14 2>/dev/null)\"\n"
        "case \"$wifi_state\" in\n"
        "    *00000002*|*00000003*)\n"
        "        service call wifi 13 i32 0 >/dev/null 2>&1\n"
        "        sleep 1\n"
        "        ;;\n"
        "esac\n"
        "\n"
        "# Immediately enable SoftAP (tx 29 = setWifiApEnabled(null, true))\n"
        "service call wifi 29 i32 0 i32 1 >/dev/null 2>&1\n"
        "settings put global wifi_ap_on 1 >/dev/null 2>&1\n"
        "am startservice -n com.hkmc.wifisettings/.service.HotspotService >/dev/null 2>&1\n"
        "exit 0\n"
    )

    # 3. App launcher script (bin/launch.sh)
    # Opens Engineering WiFi Info; if auto mode is enabled, ensures props AND SoftAP are active.
    launch_sh = (
        "#!/system/bin/sh\n"
        "export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin\n"
        "export LD_LIBRARY_PATH=/vendor/lib:/system/lib\n"
        "export ANDROID_ROOT=/system\n"
        "export ANDROID_DATA=/data\n"
        f"export BOOTCLASSPATH={bootclasspath}\n"
        "\n"
        "if [ \"$(getprop persist.sys.hotspot.auto)\" != \"0\" ]; then\n"
        "    setprop wifi.hotspot.subscription true\n"
        "    setprop wifi.hotspot.apn Public\n"
        "    ap_state=\"$(service call wifi 30 2>/dev/null)\"\n"
        "    case \"$ap_state\" in\n"
        "        *0000000d*|*0000000c*)\n"
        "            ;;\n"
        "        *)\n"
        "            wifi_state=\"$(service call wifi 14 2>/dev/null)\"\n"
        "            case \"$wifi_state\" in\n"
        "                *00000002*|*00000003*)\n"
        "                    service call wifi 13 i32 0 >/dev/null 2>&1\n"
        "                    sleep 1\n"
        "                    ;;\n"
        "            esac\n"
        "            service call wifi 29 i32 0 i32 1 >/dev/null 2>&1\n"
        "            settings put global wifi_ap_on 1 >/dev/null 2>&1\n"
        "            ;;\n"
        "    esac\n"
        "fi\n"
        "am start -n com.hkmc.system.app.engineering/.module.WifiInfo\n"
        "exit 0\n"
    )

    # 4. Boot/background service script (svc/hotspot-daemon.sh)
    # Automatically enables and maintains:
    #   - wifi.hotspot.subscription = true (Hotspot Activation = on)
    #   - wifi.hotspot.apn = Public (Hotspot APN = Public)
    #   - Wi-Fi Hotspot SoftAP (IWifiManager.setWifiApEnabled(null, true) via native service call wifi 29)
    # Note: HKMC WifiService resets Settings.Global.wifi_ap_on = 0 on every ACTION_SHUTDOWN /
    # ACTION_ACC_STATUS_CHANGED (ACC OFF), so the daemon must NOT gate SoftAP activation on wifi_ap_on.
    hotspot_daemon_sh = (
        "#!/system/bin/sh\n"
        "export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin\n"
        "export LD_LIBRARY_PATH=/vendor/lib:/system/lib\n"
        "export ANDROID_ROOT=/system\n"
        "export ANDROID_DATA=/data\n"
        f"export BOOTCLASSPATH={bootclasspath}\n"
        "\n"
        "ENG_PREF=\"/data/data/com.hkmc.system.app.engineering/shared_prefs/com.hkmc.system.app.engineering_preferences.xml\"\n"
        "\n"
        "log_info() {\n"
        "    echo \"[hotspot-enabler] $*\"\n"
        "    log -t HotspotEnabler \"$*\" 2>/dev/null\n"
        "}\n"
        "\n"
        "disable_adb_tcp() {\n"
        "    if [ \"$(getprop persist.service.adb.tcp.enable)\" = \"1\" ]; then\n"
        "        log_info \"ADB TCP is enabled (persist.service.adb.tcp.enable=1) -> disabling to prevent eth0 conflict\"\n"
        "        setprop persist.service.adb.tcp.enable 0\n"
        "        if [ \"$(getprop persist.sys.usb.config)\" != \"adb\" ]; then\n"
        "            stop adbd >/dev/null 2>&1\n"
        "        fi\n"
        "    fi\n"
        "    if [ -f \"$ENG_PREF\" ] && grep -q 'name=\"adb_tcp_setting\" value=\"true\"' \"$ENG_PREF\" 2>/dev/null; then\n"
        "        sed -i 's/<boolean name=\"adb_tcp_setting\" value=\"true\" \\/>/<boolean name=\"adb_tcp_setting\" value=\"false\" \\/>/g' \"$ENG_PREF\" 2>/dev/null\n"
        "    fi\n"
        "    if ip addr show dev eth0 2>/dev/null | grep -q \"192.168.0.\"; then\n"
        "        log_info \"Clearing conflicting 192.168.0.x subnet from eth0\"\n"
        "        ip addr flush dev eth0 >/dev/null 2>&1\n"
        "        ip link set eth0 down >/dev/null 2>&1\n"
        "        ifconfig eth0 0.0.0.0 down >/dev/null 2>&1\n"
        "    fi\n"
        "}\n"
        "\n"
        "on_stop() {\n"
        "    log_info \"Service stopped via NEMESIS Manager -> disabling auto-hotspot\"\n"
        "    setprop persist.sys.hotspot.auto 0\n"
        "    setprop wifi.hotspot.subscription false\n"
        "    service call wifi 29 i32 0 i32 0 >/dev/null 2>&1\n"
        "    settings put global wifi_ap_on 0 >/dev/null 2>&1 &\n"
        "    exit 0\n"
        "}\n"
        "trap 'on_stop' TERM INT\n"
        "\n"
        "apply_hotspot_props() {\n"
        "    disable_adb_tcp\n"
        "    if [ \"$(getprop wifi.hotspot.subscription)\" != \"true\" ]; then\n"
        "        setprop wifi.hotspot.subscription true\n"
        "    fi\n"
        "    if [ \"$(getprop wifi.hotspot.apn)\" != \"Public\" ]; then\n"
        "        setprop wifi.hotspot.apn Public\n"
        "    fi\n"
        "}\n"
        "\n"
        "is_softap_active() {\n"
        "    # TRANSACTION_getWifiApEnabledState = 30 (13/0xd = ENABLED, 12/0xc = ENABLING)\n"
        "    ap_state=\"$(service call wifi 30 2>/dev/null)\"\n"
        "    case \"$ap_state\" in\n"
        "        *0000000d*|*0000000c*)\n"
        "            return 0\n"
        "            ;;\n"
        "        *Parcel*)\n"
        "            return 1\n"
        "            ;;\n"
        "    esac\n"
        "    if [ \"$(getprop tcc.wifi.firmware.mode)\" = \"softap\" ] && [ \"$(getprop wlan.driver.status)\" = \"ok\" ]; then\n"
        "        return 0\n"
        "    fi\n"
        "    return 1\n"
        "}\n"
        "\n"
        "enable_hotspot() {\n"
        "    apply_hotspot_props\n"
        "\n"
        "    # TRANSACTION_getWifiEnabledState = 14 (2 = ENABLING, 3 = ENABLED)\n"
        "    # Disable STA client mode first if active via native Binder call (tx 13 = setWifiEnabled)\n"
        "    wifi_state=\"$(service call wifi 14 2>/dev/null)\"\n"
        "    case \"$wifi_state\" in\n"
        "        *00000002*|*00000003*)\n"
        "            service call wifi 13 i32 0 >/dev/null 2>&1\n"
        "            sleep 1\n"
        "            ;;\n"
        "    esac\n"
        "\n"
        "    # Call IWifiManager.setWifiApEnabled(null, true) (tx 29)\n"
        "    # WifiService.setWifiApEnabled also calls persistWifiApState(true) -> wifi_ap_on=1\n"
        "    service call wifi 29 i32 0 i32 1 >/dev/null 2>&1\n"
        "    log_info \"Triggered setWifiApEnabled(null, true) [subscription=true, apn=Public]\"\n"
        "}\n"
        "\n"
        "disable_hotspot() {\n"
        "    service call wifi 29 i32 0 i32 0 >/dev/null 2>&1\n"
        "    log_info \"Triggered setWifiApEnabled(null, false)\"\n"
        "}\n"
        "\n"
        "log_info \"Service started (pid=$$)\"\n"
        "\n"
        "# If user manually clicked 'Start' in NEMESIS Manager -> Services while already booted\n"
        "if [ \"$(getprop sys.boot_completed)\" = \"1\" ] && [ \"$(getprop persist.sys.hotspot.auto)\" = \"0\" ] && [ \"$(getprop wifi.hotspot.subscription)\" = \"false\" ]; then\n"
        "    log_info \"Manual service start detected -> re-enabling auto-hotspot\"\n"
        "    setprop persist.sys.hotspot.auto 1\n"
        "fi\n"
        "\n"
        "# Default persist.sys.hotspot.auto to 1 if unset\n"
        "if [ -z \"$(getprop persist.sys.hotspot.auto)\" ]; then\n"
        "    setprop persist.sys.hotspot.auto 1\n"
        "fi\n"
        "\n"
        "if [ \"$(getprop persist.sys.hotspot.auto)\" != \"0\" ]; then\n"
        "    apply_hotspot_props\n"
        "fi\n"
        "\n"
        "# Wait for Android boot completion\n"
        "while [ \"$(getprop sys.boot_completed)\" != \"1\" ]; do\n"
        "    if [ \"$(getprop persist.sys.hotspot.auto)\" != \"0\" ]; then\n"
        "        apply_hotspot_props\n"
        "    fi\n"
        "    sleep 2 &\n"
        "    wait $!\n"
        "done\n"
        "\n"
        "# Give system_server WifiService / Tethering a moment to finish BOOT_COMPLETED init\n"
        "sleep 3 &\n"
        "wait $!\n"
        "\n"
        "if [ \"$(getprop persist.sys.hotspot.auto)\" != \"0\" ]; then\n"
        "    apply_hotspot_props\n"
        "    if ! is_softap_active; then\n"
        "        log_info \"Boot completed: auto-enabling Wi-Fi Hotspot\"\n"
        "        enable_hotspot\n"
        "    fi\n"
        "fi\n"
        "\n"
        "# Continuous monitor loop (retries automatically if WifiInitThread or ACC state resets SoftAP)\n"
        "while true; do\n"
        "    auto_mode=\"$(getprop persist.sys.hotspot.auto)\"\n"
        "    sub_prop=\"$(getprop wifi.hotspot.subscription)\"\n"
        "\n"
        "    # Detect if user toggled 'Hotspot Activation' OFF in WiFi Test\n"
        "    if [ \"$auto_mode\" != \"0\" ] && [ \"$sub_prop\" = \"false\" ]; then\n"
        "        log_info \"Hotspot Activation turned OFF in WiFi Test -> disabling auto-hotspot\"\n"
        "        setprop persist.sys.hotspot.auto 0\n"
        "        disable_hotspot\n"
        "        auto_mode=\"0\"\n"
        "    # Detect if user toggled 'Hotspot Activation' back ON in WiFi Test\n"
        "    elif [ \"$auto_mode\" = \"0\" ] && [ \"$sub_prop\" = \"true\" ]; then\n"
        "        log_info \"Hotspot Activation turned ON in WiFi Test -> enabling auto-hotspot\"\n"
        "        setprop persist.sys.hotspot.auto 1\n"
        "        setprop wifi.hotspot.apn Public\n"
        "        enable_hotspot\n"
        "        auto_mode=\"1\"\n"
        "    fi\n"
        "\n"
        "    if [ \"$auto_mode\" != \"0\" ]; then\n"
        "        apply_hotspot_props\n"
        "        if ! is_softap_active; then\n"
        "            log_info \"SoftAP inactive while auto_mode=1; enabling Wi-Fi Hotspot\"\n"
        "            enable_hotspot\n"
        "        fi\n"
        "    fi\n"
        "\n"
        "    sleep 5 &\n"
        "    wait $!\n"
        "done\n"
    )

    # 5. Pre-uninstall cleanup script (scripts/preun.sh)
    preun_sh = (
        "#!/system/bin/sh\n"
        "export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin\n"
        "\n"
        "setprop persist.sys.hotspot.auto 0\n"
        "service call wifi 29 i32 0 i32 0 >/dev/null 2>&1\n"
        "settings put global wifi_ap_on 0 >/dev/null 2>&1\n"
        "setprop wifi.hotspot.subscription false\n"
        "setprop wifi.hotspot.apn Default\n"
        "exit 0\n"
    )

    write_lf(PKG_SRC_DIR / "NEMESIS.data", pkg_meta)
    write_lf(PKG_SRC_DIR / "scripts" / "post.sh", post_sh)
    write_lf(PKG_SRC_DIR / "bin" / "launch.sh", launch_sh)
    write_lf(PKG_SRC_DIR / "svc" / "hotspot-daemon.sh", hotspot_daemon_sh)
    write_lf(PKG_SRC_DIR / "scripts" / "preun.sh", preun_sh)

    # 6. Inner NEMESIS package zip (usb_root/packages/engineering.wifiinfo.zip)
    inner_zip_path = USB_ROOT_DIR / "packages" / "engineering.wifiinfo.zip"
    inner_zip_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(inner_zip_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for file_path in sorted(PKG_SRC_DIR.rglob("*")):
            if file_path.is_file():
                arcname = file_path.relative_to(PKG_SRC_DIR).as_posix()
                zf.write(file_path, arcname)

    # 7. USB root descriptor (usb_root/NEMESIS.data)
    usb_meta = (
        "[nemesis]\n"
        "version=1.0\n"
        "type=install\n"
        "path=packages/engineering.wifiinfo.zip\n"
    )
    write_lf(USB_ROOT_DIR / "NEMESIS.data", usb_meta)

    # 8. Outer USB bundle zip (Engineering WiFi Info.zip)
    with zipfile.ZipFile(OUTER_ZIP_PATH, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for file_path in sorted(USB_ROOT_DIR.rglob("*")):
            if file_path.is_file():
                arcname = file_path.relative_to(USB_ROOT_DIR).as_posix()
                zf.write(file_path, arcname)

    print(f"Built inner package: {inner_zip_path} ({inner_zip_path.stat().st_size} bytes)")
    print(f"Built USB bundle:    {OUTER_ZIP_PATH} ({OUTER_ZIP_PATH.stat().st_size} bytes)")


if __name__ == "__main__":
    build()
