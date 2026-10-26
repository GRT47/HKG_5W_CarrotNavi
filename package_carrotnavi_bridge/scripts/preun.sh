#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin

/system/bin/busybox pkill -f "com.hkg.carrotbridge.CarrotBridgeMain" >/dev/null 2>&1
pkill -f "com.hkg.carrotbridge.CarrotBridgeMain" >/dev/null 2>&1
rm -f /data/nemesis/packages/carrotnavi.bridge >/dev/null 2>&1
exit 0
