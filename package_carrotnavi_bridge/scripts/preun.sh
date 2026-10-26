#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin

pkill -f "com.hkg.carrotbridge.CarrotBridgeMain" >/dev/null 2>&1
exit 0
