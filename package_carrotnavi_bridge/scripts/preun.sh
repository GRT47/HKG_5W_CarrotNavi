#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin

for _pf in /data/local/tmp/carrotbridge_java.pid /data/local/tmp/carrotbridge_svc.pid; do
    if [ -f "$_pf" ]; then
        _pid="$(cat "$_pf" 2>/dev/null)"
        if [ -n "$_pid" ]; then
            kill "$_pid" >/dev/null 2>&1
        fi
    fi
done
/system/bin/busybox pkill -f "com.hkg.carrotbridge.CarrotBridgeMain" >/dev/null 2>&1
/system/bin/busybox pkill -f "bridge-daemon.sh" >/dev/null 2>&1
rm -f /data/local/tmp/carrotbridge_svc.pid /data/local/tmp/carrotbridge_java.pid /data/local/tmp/carrotbridge_show_ui /data/local/tmp/carrotbridge_check_update >/dev/null 2>&1
rm -f /data/nemesis/packages/carrotnavi.bridge >/dev/null 2>&1
exit 0
