#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin

for _pf in /dev/hkg_tmp/carrotbridge_java.pid /dev/hkg_tmp/carrotbridge_svc.pid /data/local/tmp/carrotbridge_java.pid /data/local/tmp/carrotbridge_svc.pid; do
    if [ -f "$_pf" ]; then
        _pid="$(cat "$_pf" 2>/dev/null)"
        if [ -n "$_pid" ]; then
            kill "$_pid" >/dev/null 2>&1
        fi
    fi
done
/system/bin/busybox pkill -f "com.hkg.carrotbridge" >/dev/null 2>&1
/system/bin/busybox pkill -f "bridge-daemon.sh" >/dev/null 2>&1
rm -rf /dev/hkg_tmp/hkg_tbt_dump /dev/hkg_tmp/carrotbridge_* /data/local/tmp/hkg_tbt_dump /data/local/tmp/carrotbridge_* >/dev/null 2>&1
rm -f /data/nemesis/packages/carrotnavi.bridge >/dev/null 2>&1
exit 0
