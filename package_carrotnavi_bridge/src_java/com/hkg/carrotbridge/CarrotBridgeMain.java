package com.hkg.carrotbridge;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * HKG Standard Gen5W (Android 4.4.2 API 19) -> CarrotPilot 7706 Bridge & GitHub Auto-Updater.
 *
 * Features:
 * 1. Real-time parsing of HKMC ClusterService / NavigationCluster / com.mnsoft.navi [Cluster] logs
 *    and periodic dumpsys snapshots of com.hkmc.micom.ClusterService.
 * 2. Full 7-item Speed Camera & Safety Zone (SDI) + Turn-By-Turn (TBT v1) mapping to CarrotPilot's
 *    UDP 7706 JSON schema (CarrotServ.update).
 * 3. Automatic discovery of CarrotPilot via UDP 7705 broadcast listener + /proc/net/arp scan on wlan0.
 * 4. Automatic live TBT trace logging (/data/local/tmp/hkg_tbt_dump/tbt_live_trace.log) whenever route
 *    guidance is active, enabling precision TBT reverse engineering via USB or local dump.
 * 5. Android 4.4.2 GitHub Auto-Updater with custom UDP DNS (8.8.8.8 / 1.1.1.1 to bypass rmnet1 private DNS),
 *    explicit TLSv1.2 + SNI via conscrypt.odex, HTTP redirect support, and SHA-256 verification.
 * 6. MAP Button 3-Press Status Dashboard Overlay (ivi_mode_broadcast KEYCODE_SHORTCUT_MAP / MAP_SHORTCUT
 *    listener + Android System WindowManager Overlay UI).
 */
public class CarrotBridgeMain {

    public static final String BRIDGE_VERSION = "1.2.0";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset UTF16LE = Charset.forName("UTF-16LE");
    private static final Charset UTF16BE = Charset.forName("UTF-16BE");
    private static final Charset UTF16 = Charset.forName("UTF-16");

    private static final int CARROT_BROADCAST_PORT = 7705;
    private static final int CARROT_TARGET_PORT = 7706;
    private static final long TRACE_MAX_BYTES = 4L * 1024L * 1024L; // 4 MB cap with rotation

    /**
     * Thread-safe navigation state model aggregated from logcat + dumpsys.
     */
    public static final class NavState {
        // Road & Vehicle state
        public int nRoadLimitSpeed = 30;
        public int slifSpeedLimit = 0;
        public int trapSpeedLimit = 0;
        public int segSpeedLimit = 0;
        public int roadcate = 4; // > 1 by default so CarrotPilot speed bump control (roadcate > 1) works on city roads
        public int curRoadClass = 4;
        public int numLanes = 0;

        // SDI (Speed camera / section control / school zone / speed bump)
        public int fixedSpeed = 0;
        public int mobileSpeed = 0;
        public int redlineSpeed = 0;
        public int overSpeedAlarm = 3;
        public int nonSpeed = 0;
        public int warningZone = 0;
        public int warningZone2 = 0;
        public int speedInfoType = 0;
        public int cameraSafetyAlert = 0;
        public int trapBarGraph = 0;
        public int camWarnDistM = 0;
        public long lastCamWarnDistMs = 0L;

        // ADAS / SCC_HU_Navi_P camera & section fields
        public int nextCamType = 0;
        public int nextCamDist = 0;
        public int nextCamSpdLimit = 0;
        public int rangeCamInfo = 0;
        public int rangeCamDist = 0;
        public int rangeCamSpdLimit = 0;
        public int rangeCamAvgMovingSpd = 0;
        public int stdPosRangeAvgSpeed = 0;

        // Tollgate
        public int displayTG = 0;
        public int tollGateFee = 0;
        public int tollGateCurrency = 0;

        // Route guidance & TBT state
        public boolean bGuid = false;
        public int operationStatus = 0;
        public int setDestStatus = 0;
        public int offRouteStatus = 0;
        public int naviValidity = 0;

        public String displayTypeName = "NO ROUTE GUIDANCE(COMPASS)";
        public int displayTypeCode = 8;
        public double tbtDirectionDeg = 0.0;
        public int tbtDistM = 0;
        public int remainTurnDistM = 0;
        public int tbtBarGraph = -1;

        public int sccNextTurnType1 = 0;
        public int sccNextTurnDist1 = 0;
        public int sccNextTurnType2 = 0;
        public int sccNextTurnDist2 = 0;

        public int subInfo2Dist1M = 0;
        public int subInfo2Dist2M = 0;

        public String crossroadName = "";
        public String destName = "";
        public String turnListName1 = "";
        public String turnListName2 = "";
        public int turnListType1 = -1;
        public int turnListType2 = -1;
        public int turnListDist1M = 0;
        public int turnListDist2M = 0;

        // Destination remaining distance & time
        public int remainDistM = 0;
        public int remainTimeSec = 0;

        // GPS & Current Road
        public double lat = 0.0;
        public double lon = 0.0;
        public double headingDeg = 0.0;
        public double posSpeedKph = 0.0;
        public String posRoadName = "";

        public int carrotIndex = 0;
        public long lastUpdateMs = System.currentTimeMillis();

        public synchronized boolean isGuidanceActive() {
            if (bGuid || setDestStatus == 1 || operationStatus == 1 || remainDistM > 0) {
                if (displayTypeCode != 8 && displayTypeCode != 0 && displayTypeCode != 1) {
                    return true;
                }
                return bGuid || setDestStatus == 1 || remainDistM > 0;
            }
            return displayTypeCode != 8 && displayTypeCode != 0 && displayTypeCode != 1
                    && displayTypeCode != 6 && displayTypeCode != 7;
        }
    }

    /**
     * Computed CarrotPilot 7706 packet fields.
     */
    public static final class CarrotPacket {
        public int carrotIndex;
        public long epochTime;
        public String timezone = "Asia/Seoul";
        public int nRoadLimitSpeed = 30;
        public int roadcate = 4;

        public int nSdiType = -1;
        public int nSdiSpeedLimit = 0;
        public int nSdiSection = -1;
        public int nSdiDist = -1;
        public int nSdiBlockType = -1;
        public int nSdiBlockSpeed = 0;
        public int nSdiBlockDist = 0;
        public int nSdiBlockAverageSpeed = 0;

        public int nSdiPlusType = -1;
        public int nSdiPlusSpeedLimit = 0;
        public int nSdiPlusDist = 0;
        public int nSdiPlusBlockType = -1;
        public int nSdiPlusBlockSpeed = 0;
        public int nSdiPlusBlockDist = 0;

        public int nTBTDist = 0;
        public int nTBTTurnType = -1;
        public String szTBTMainText = "";
        public String szNearDirName = "";
        public String szFarDirName = "";
        public int nTBTNextRoadWidth = 0;

        public int nTBTDistNext = 0;
        public int nTBTTurnTypeNext = -1;
        public String szTBTMainTextNext = "";

        public int nGoPosDist = 0;
        public int nGoPosTime = 0;
        public String szGoalName = "";
        public String szPosRoadName = "";

        public double vpPosPointLat = 0.0;
        public double vpPosPointLon = 0.0;
        public double nPosAngle = 0.0;
        public double nPosSpeed = 0.0;

        public String toJson() {
            StringBuilder sb = new StringBuilder(640);
            sb.append('{');
            appendInt(sb, "carrotIndex", carrotIndex, true);
            appendLong(sb, "epochTime", epochTime);
            appendStr(sb, "timezone", timezone);
            appendInt(sb, "nRoadLimitSpeed", nRoadLimitSpeed, false);
            appendInt(sb, "roadcate", roadcate, false);

            appendInt(sb, "nSdiType", nSdiType, false);
            appendInt(sb, "nSdiSpeedLimit", nSdiSpeedLimit, false);
            appendInt(sb, "nSdiSection", nSdiSection, false);
            appendInt(sb, "nSdiDist", nSdiDist, false);
            appendInt(sb, "nSdiBlockType", nSdiBlockType, false);
            appendInt(sb, "nSdiBlockSpeed", nSdiBlockSpeed, false);
            appendInt(sb, "nSdiBlockDist", nSdiBlockDist, false);
            appendInt(sb, "nSdiBlockAverageSpeed", nSdiBlockAverageSpeed, false);

            appendInt(sb, "nSdiPlusType", nSdiPlusType, false);
            appendInt(sb, "nSdiPlusSpeedLimit", nSdiPlusSpeedLimit, false);
            appendInt(sb, "nSdiPlusDist", nSdiPlusDist, false);
            appendInt(sb, "nSdiPlusBlockType", nSdiPlusBlockType, false);
            appendInt(sb, "nSdiPlusBlockSpeed", nSdiPlusBlockSpeed, false);
            appendInt(sb, "nSdiPlusBlockDist", nSdiPlusBlockDist, false);

            appendInt(sb, "nTBTDist", nTBTDist, false);
            appendInt(sb, "nTBTTurnType", nTBTTurnType, false);
            appendStr(sb, "szTBTMainText", szTBTMainText);
            appendStr(sb, "szNearDirName", szNearDirName);
            appendStr(sb, "szFarDirName", szFarDirName);
            appendInt(sb, "nTBTNextRoadWidth", nTBTNextRoadWidth, false);

            appendInt(sb, "nTBTDistNext", nTBTDistNext, false);
            appendInt(sb, "nTBTTurnTypeNext", nTBTTurnTypeNext, false);
            appendStr(sb, "szTBTMainTextNext", szTBTMainTextNext);

            appendInt(sb, "nGoPosDist", nGoPosDist, false);
            appendInt(sb, "nGoPosTime", nGoPosTime, false);
            appendStr(sb, "szGoalName", szGoalName);
            appendStr(sb, "szPosRoadName", szPosRoadName);

            appendDouble(sb, "vpPosPointLat", vpPosPointLat, 6);
            appendDouble(sb, "vpPosPointLon", vpPosPointLon, 6);
            appendDouble(sb, "nPosAngle", nPosAngle, 1);
            appendDouble(sb, "nPosSpeed", nPosSpeed, 1);
            sb.append('}');
            return sb.toString();
        }

        private static void appendInt(StringBuilder sb, String k, int v, boolean first) {
            if (!first) sb.append(',');
            sb.append('"').append(k).append("\":").append(v);
        }

        private static void appendLong(StringBuilder sb, String k, long v) {
            sb.append(",\"").append(k).append("\":").append(v);
        }

        private static void appendDouble(StringBuilder sb, String k, double v, int decimals) {
            sb.append(",\"").append(k).append("\":");
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                sb.append("0.0");
            } else {
                sb.append(String.format(Locale.US, "%." + decimals + "f", v));
            }
        }

        private static void appendStr(StringBuilder sb, String k, String v) {
            sb.append(",\"").append(k).append("\":\"").append(escapeJson(v == null ? "" : v)).append('"');
        }
    }

    public static String escapeJson(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c >= 0x20) {
                        out.append(c);
                    }
                    break;
            }
        }
        return out.toString();
    }

    // =========================================================================
    // PARSERS: LOGCAT (ClusterService, NavigationCluster, Java_Layer, BrdCast)
    // =========================================================================

    private static final Pattern KV_INT_PATTERN = Pattern.compile("([A-Za-z0-9_]+)\\s*:\\s*(-?\\d+)");
    private static final Pattern TAIL_CAM_ALERT_PATTERN = Pattern.compile("cameraSafetyAlert(\\d+)");

    public static void parseLogcatLine(String line, NavState st, File traceFile) {
        if (line == null || line.isEmpty()) return;
        // Ignore rear parking camera popup noise from ExFW
        if (line.contains("CameraPopupOntheScreen") || line.contains("cameraUpdateFlyerIcon")) {
            return;
        }

        boolean interestingForTrace = false;

        synchronized (st) {
            st.lastUpdateMs = System.currentTimeMillis();

            // 1. NaviCluster.updateTBTTrapInfo()
            if (line.contains("updateTBTTrapInfo()")) {
                interestingForTrace = true;
                Map<String, Integer> kv = extractIntMap(line);
                if (kv.containsKey("fixedSpeed")) st.fixedSpeed = normalizeTrapSpeed(kv.get("fixedSpeed"));
                if (kv.containsKey("mobileSpeed")) st.mobileSpeed = normalizeTrapSpeed(kv.get("mobileSpeed"));
                if (kv.containsKey("redlineSpeed")) st.redlineSpeed = normalizeTrapSpeed(kv.get("redlineSpeed"));
                if (kv.containsKey("overSpeedAlarm")) st.overSpeedAlarm = kv.get("overSpeedAlarm");
                if (kv.containsKey("nonSpeed")) st.nonSpeed = kv.get("nonSpeed");
                if (kv.containsKey("warningZone")) st.warningZone = kv.get("warningZone");
                if (kv.containsKey("warningZone2")) st.warningZone2 = kv.get("warningZone2");
                if (kv.containsKey("barGraphLevel")) st.trapBarGraph = kv.get("barGraphLevel");
                if (kv.containsKey("speedLimit")) st.trapSpeedLimit = normalizeTrapSpeed(kv.get("speedLimit"));
                if (kv.containsKey("speedInfoType")) st.speedInfoType = kv.get("speedInfoType");
                if (kv.containsKey("tollGate")) st.tollGateFee = kv.get("tollGate");
                if (kv.containsKey("tollGateCurrency")) st.tollGateCurrency = kv.get("tollGateCurrency");
                if (kv.containsKey("displayTG")) st.displayTG = kv.get("displayTG");

                Matcher mTail = TAIL_CAM_ALERT_PATTERN.matcher(line);
                if (mTail.find()) {
                    st.cameraSafetyAlert = parseIntSafe(mTail.group(1), 0);
                }
                if (st.fixedSpeed == 0 && st.mobileSpeed == 0 && st.redlineSpeed == 0
                        && st.speedInfoType == 0 && st.cameraSafetyAlert == 0
                        && st.warningZone == 0 && st.nonSpeed == 0) {
                    st.camWarnDistM = 0;
                }
                refreshRoadLimitLocked(st);
            }
            // 2. NaviCluster.updateSCC_HU_Navi_P()
            else if (line.contains("updateSCC_HU_Navi_P()")) {
                interestingForTrace = true;
                Map<String, Integer> kv = extractIntMap(line);
                if (kv.containsKey("setDestStatus")) st.setDestStatus = kv.get("setDestStatus");
                if (kv.containsKey("offRouteStatus")) st.offRouteStatus = kv.get("offRouteStatus");
                if (kv.containsKey("naviValidity")) st.naviValidity = kv.get("naviValidity");
                if (kv.containsKey("nextTurnType1")) st.sccNextTurnType1 = kv.get("nextTurnType1");
                if (kv.containsKey("nextTurnDist1")) {
                    int d1 = kv.get("nextTurnDist1");
                    st.sccNextTurnDist1 = (d1 < 0 || d1 >= 4095) ? 0 : d1;
                }
                if (kv.containsKey("nextTurnType2")) st.sccNextTurnType2 = kv.get("nextTurnType2");
                if (kv.containsKey("nextTurnDist2")) {
                    int d2 = kv.get("nextTurnDist2");
                    st.sccNextTurnDist2 = (d2 < 0 || d2 >= 4095) ? 0 : d2;
                }
                if (kv.containsKey("curLinkCntLane")) st.numLanes = kv.get("curLinkCntLane");
                if (kv.containsKey("curRoadClass")) {
                    st.curRoadClass = kv.get("curRoadClass");
                    updateRoadCateFromClassLocked(st, st.curRoadClass);
                }
                // Filter out all-1s bitfield invalid sentinels (nextCamType=15, nextCamDist=1023, nextCamSpdLimit=15, rangeCamInfo=7, rangeCamDist=65535)
                if (kv.containsKey("nextCamType")) {
                    int ct = kv.get("nextCamType");
                    st.nextCamType = (ct < 0 || ct >= 15) ? 0 : ct;
                }
                if (kv.containsKey("nextCamDist")) {
                    int cd = kv.get("nextCamDist");
                    st.nextCamDist = (cd < 0 || cd >= 1023) ? 0 : cd;
                }
                if (kv.containsKey("nextCamSpdLimit")) {
                    int cs = kv.get("nextCamSpdLimit");
                    st.nextCamSpdLimit = (cs == 15) ? 0 : normalizeTrapSpeed(cs);
                }
                if (kv.containsKey("rangeCamInfo")) {
                    int ri = kv.get("rangeCamInfo");
                    st.rangeCamInfo = (ri < 0 || ri >= 7) ? 0 : ri;
                }
                if (kv.containsKey("rangeCamDist")) {
                    int rd = kv.get("rangeCamDist");
                    st.rangeCamDist = (rd < 0 || rd >= 65535) ? 0 : rd;
                }
                if (kv.containsKey("rangeCamSpdLimit")) {
                    int rs = kv.get("rangeCamSpdLimit");
                    st.rangeCamSpdLimit = (rs == 15) ? 0 : normalizeTrapSpeed(rs);
                }
                if (kv.containsKey("rangeCamAvgMovingSpd")) st.rangeCamAvgMovingSpd = kv.get("rangeCamAvgMovingSpd");
            }
            // 3. NaviCluster.updateSTD_POS_P_Info()
            else if (line.contains("updateSTD_POS_P_Info()")) {
                Map<String, Integer> kv = extractIntMap(line);
                if (kv.containsKey("rangeAvgSpeed")) {
                    int avg = kv.get("rangeAvgSpeed");
                    if (avg != st.stdPosRangeAvgSpeed) interestingForTrace = true;
                    st.stdPosRangeAvgSpeed = avg;
                }
            }
            // 4. NaviCluster.updateNaviWarnDist()
            // Note: 2-arg updateNaviWarnDist logs 4 separate lines (warnDistOverDec / unit1 / popupInfo / popupDistScale).
            // Only parse the first line containing warnDistOverDec or overDec so trailing lines don't overwrite camWarnDistM with 0.
            else if (line.contains("updateNaviWarnDist()")) {
                Map<String, Integer> kv = extractIntMap(line);
                if (kv.containsKey("warnDistOverDec") || kv.containsKey("overDec")) {
                    interestingForTrace = true;
                    int overDec = kv.containsKey("warnDistOverDec") ? kv.get("warnDistOverDec") : kv.get("overDec");
                    int underDec = kv.containsKey("warnDistUnderDec") ? kv.get("warnDistUnderDec")
                            : (kv.containsKey("underDec") ? kv.get("underDec") : 0);
                    int scale = kv.containsKey("warnDistScale") ? kv.get("warnDistScale")
                            : (kv.containsKey("scale") ? kv.get("scale") : 0);
                    if (overDec < 0 || overDec >= 65535 || scale < 0 || scale >= 15) {
                        st.camWarnDistM = 0;
                    } else {
                        st.camWarnDistM = convertDistToMeters(overDec, underDec, scale, false);
                        st.lastCamWarnDistMs = System.currentTimeMillis();
                    }
                }
            }
            // 5. NaviCluster.updateRemainDis() (Bar-graph countdown distance to next turn: scale_unit 0=m, 1=km)
            else if (line.contains("updateRemainDis()")) {
                interestingForTrace = true;
                Map<String, Integer> kv = extractIntMap(line);
                int rem = kv.containsKey("remained_distance") ? kv.get("remained_distance") : 0;
                int under = kv.containsKey("under_decimal") ? kv.get("under_decimal") : 0;
                int unit = kv.containsKey("scale_unit") ? kv.get("scale_unit") : 0;
                if (rem >= 0 && rem < 65535 && unit >= 0 && unit < 15) {
                    st.remainTurnDistM = convertDistToMeters(rem, under, unit, false);
                }
            }
            // 6. NaviCluster.updateTBTSubInfo2C() (unit: 0=m, 1=km, 15=invalid)
            else if (line.contains("updateTBTSubInfo2C()")) {
                interestingForTrace = true;
                Map<String, Integer> kv = extractIntMap(line);
                int u1 = kv.containsKey("distToPoint1_unit") ? kv.get("distToPoint1_unit") : 15;
                int i1 = kv.containsKey("distToPoint1_I") ? kv.get("distToPoint1_I") : 0;
                int f1 = kv.containsKey("distToPoint1_F") ? kv.get("distToPoint1_F") : 0;
                st.subInfo2Dist1M = (u1 < 0 || u1 >= 15 || i1 >= 65535) ? 0 : convertDistToMeters(i1, f1, u1, false);

                int u2 = kv.containsKey("distToPoint2_unit") ? kv.get("distToPoint2_unit") : 15;
                int i2 = kv.containsKey("distToPoint2_I") ? kv.get("distToPoint2_I") : 0;
                int f2 = kv.containsKey("distToPoint2_F") ? kv.get("distToPoint2_F") : 0;
                st.subInfo2Dist2M = (u2 < 0 || u2 >= 15 || i2 >= 65535) ? 0 : convertDistToMeters(i2, f2, u2, false);
            }
            // 7. NaviCluster.updateSLIFNaviInformation()
            else if (line.contains("updateSLIFNaviInformation()")) {
                interestingForTrace = true;
                Map<String, Integer> kv = extractIntMap(line);
                if (kv.containsKey("speedLimit")) {
                    int spd = normalizeTrapSpeed(kv.get("speedLimit"));
                    if (spd > 0 && spd <= 150) st.slifSpeedLimit = spd;
                }
                if (kv.containsKey("timeSpd") && kv.get("timeSpd") == 30 && st.warningZone == 0) {
                    st.warningZone = 2; // School zone 30km/h timed speed
                }
                if (kv.containsKey("linkClass")) {
                    updateRoadCateFromClassLocked(st, kv.get("linkClass"));
                }
                refreshRoadLimitLocked(st);
            }
            // 8. NaviCluster.updateSTD_SEG_E_SPEED5_Info() / updateSTD_SEG_E_Info()
            else if (line.contains("updateSTD_SEG_E")) {
                Map<String, Integer> kv = extractIntMap(line);
                if (kv.containsKey("directionLanes") && kv.get("directionLanes") > 0 && kv.get("directionLanes") <= 12) {
                    st.numLanes = kv.get("directionLanes");
                }
                if (kv.containsKey("speedLimit")) {
                    int spd = normalizeTrapSpeed(kv.get("speedLimit"));
                    if (spd > 0 && spd <= 150) {
                        st.segSpeedLimit = spd;
                        refreshRoadLimitLocked(st);
                    }
                }
            }
            // 9. NavigationCluster: updateTBTSubInfo() distance: 2.7 unit: 1 (0=m, 1=km, 15=invalid)
            else if (line.contains("updateTBTSubInfo()")) {
                interestingForTrace = true;
                double distVal = extractDoubleAfter(line, "distance:");
                int unit = extractIntAfter(line, "unit:", 15);
                if (distVal >= 0.0 && unit >= 0 && unit < 15) {
                    st.remainDistM = convertFloatDistToMeters(distVal, unit, false);
                } else if (unit == 15) {
                    st.remainDistM = 0;
                    st.remainTimeSec = 0;
                }
            }
            // 10. ClusterService: updateTPCrossroadName()::crossroadname is ...
            else if (line.contains("updateTPCrossroadName()::crossroadname is ")) {
                interestingForTrace = true;
                int idx = line.indexOf("crossroadname is ");
                if (idx >= 0) {
                    String rawName = line.substring(idx + "crossroadname is ".length()).trim();
                    String decoded = decodeMnsoftTpString(rawName);
                    if (!decoded.isEmpty() && (!decoded.contains("\uFFFD") || st.crossroadName.isEmpty())) {
                        st.crossroadName = decoded;
                    }
                }
            }
            else if (line.contains("updateTPCrossroadName::sendClearTP()")) {
                st.crossroadName = "";
            }
            // 11. ClusterService: updateTPFlexibleDestinationName : ...
            else if (line.contains("updateTPFlexibleDestinationName : ")) {
                interestingForTrace = true;
                int idx = line.indexOf("updateTPFlexibleDestinationName : ");
                if (idx >= 0) {
                    String rawName = line.substring(idx + "updateTPFlexibleDestinationName : ".length()).trim();
                    st.destName = decodeMnsoftTpString(rawName);
                }
            }
            // 12. BrdCast: com.mnsoft.navi.GuidStatus
            else if (line.contains("com.mnsoft.navi.GuidStatus")) {
                interestingForTrace = true;
                if (line.contains("bGuid=true")) {
                    st.bGuid = true;
                } else if (line.contains("bGuid=false")) {
                    st.bGuid = false;
                    st.tbtDistM = 0;
                    st.remainTurnDistM = 0;
                    st.sccNextTurnDist1 = 0;
                    st.sccNextTurnType1 = 0;
                    st.subInfo2Dist1M = 0;
                    st.crossroadName = "";
                }
            }
            // 13. BrdCast: com.hkmc.telematics.gis.action.NAVI_GPS_CHANGED
            else if (line.contains("NAVI_GPS_CHANGED")) {
                double lat = extractDoubleAfter(line, "LAT=");
                double lon = extractDoubleAfter(line, "LON=");
                double hdg = extractDoubleAfter(line, "HEADING=");
                if (lat > 30.0 && lat < 45.0 && lon > 120.0 && lon < 135.0) {
                    st.lat = lat;
                    st.lon = lon;
                }
                if (hdg >= 0.0 && hdg <= 360.0) {
                    st.headingDeg = hdg;
                }
                String admin = extractBundleStr(line, "ADMINNAME=");
                if (!admin.isEmpty()) {
                    st.posRoadName = admin;
                }
            }
            // 14. I/Java_Layer: [Cluster] plain-text logs from com.mnsoft.navi (enabled via Eng.LogSetting)
            else if (line.contains("[Cluster]") || line.contains("[IL TBT") || line.contains("[TP ")) {
                interestingForTrace = true;
                parseMnsoftClusterLogLocked(line, st);
            }
        }

        if (interestingForTrace && traceFile != null) {
            appendTraceLine(traceFile, "LOG: " + line);
        }
    }

    private static void parseMnsoftClusterLogLocked(String line, NavState st) {
        if (line.contains("[IL TBT Info - 0x4E] DisplayType =")) {
            String val = substringAfter(line, "DisplayType =").trim();
            applyDisplayTypeStringLocked(st, val);
        } else if (line.contains("[IL TBT Info - 0x4E] Direction =")) {
            String val = substringAfter(line, "Direction =").trim();
            st.tbtDirectionDeg = parseLeadingDouble(val, st.tbtDirectionDeg);
        } else if (line.contains("[IL TBT Info - 0x4E] Distance =")) {
            String val = substringAfter(line, "Distance =").trim();
            st.tbtDistM = parseDistanceWithUnitText(val);
        } else if (line.contains("[IL TBT Info - 0x4E] BarGraphLevel =")) {
            String val = substringAfter(line, "BarGraphLevel =").trim();
            if (val.contains("Not") || val.contains("Invalid")) {
                st.tbtBarGraph = -1;
            } else {
                st.tbtBarGraph = (int) parseLeadingDouble(val.replace("%", ""), -1);
            }
        } else if (line.contains("sendCluster_H_U_Navi_T_TurnName() UTF-16LI =")) {
            String raw = substringAfter(line, "UTF-16LI =").trim();
            String cleaned = cleanDecodedString(raw);
            if (!cleaned.isEmpty()) {
                st.crossroadName = cleaned;
            }
        } else if (line.contains("[TP Crossroad Name - 0x4F(0xF0)] Crossroad Name =")) {
            String raw = substringAfter(line, "Crossroad Name =").trim();
            String decoded = decodeMnsoftTpString(raw);
            if (!decoded.isEmpty() && (!decoded.contains("\uFFFD") || st.crossroadName.isEmpty())) {
                st.crossroadName = decoded;
            }
        } else if (line.contains("[IL TBT Sub Info - 0x4D] Distance To Destination =")) {
            String val = substringAfter(line, "Distance To Destination =").trim();
            if (val.contains("Invalid") || val.contains("None")) {
                st.remainDistM = 0;
                st.remainTimeSec = 0;
            } else {
                int d = parseDistanceWithUnitText(val);
                if (d >= 0) st.remainDistM = d;
            }
        } else if (line.contains("[IL TBT Sub Info - 0x4D] Compass =")) {
            String val = substringAfter(line, "Compass =").trim();
            double deg = parseLeadingDouble(val, -1.0);
            if (deg >= 0.0 && deg <= 360.0) st.headingDeg = deg;
        } else if (line.contains("[IL TBT Sub Info 2 - 0x4C] Distance To Next Point 1 =")) {
            String val = substringAfter(line, "Distance To Next Point 1 =").trim();
            st.subInfo2Dist1M = parseDistanceWithUnitText(val);
        } else if (line.contains("[IL TBT Sub Info 2 - 0x4C] Distance To Next Point 2 =")) {
            String val = substringAfter(line, "Distance To Next Point 2 =").trim();
            st.subInfo2Dist2M = parseDistanceWithUnitText(val);
        } else if (line.contains("[IL TBT CamDistance - 0x4F] overDec :")) {
            int over = parseIntSafe(substringAfter(line, "overDec :").trim(), 0);
            st.camWarnDistM = over;
            st.lastCamWarnDistMs = System.currentTimeMillis();
        } else if (line.contains("[IL TBT Trap Info - 0x4B] speed Limit(int) =")) {
            int spd = normalizeTrapSpeed(parseIntSafe(substringAfter(line, "speed Limit(int) =").trim(), 0));
            if (spd > 0) {
                st.trapSpeedLimit = spd;
                refreshRoadLimitLocked(st);
            }
        }
    }

    // =========================================================================
    // PARSER: DUMPSYS (dumpsys activity service com.hkmc.micom.ClusterService)
    // =========================================================================

    public static void parseDumpsysCluster(String dumpText, NavState st) {
        if (dumpText == null || dumpText.isEmpty()) return;
        String section = "";
        String[] lines = dumpText.split("\n");

        synchronized (st) {
            st.lastUpdateMs = System.currentTimeMillis();
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("TBT Trap Info:")) {
                    section = "TRAP";
                    continue;
                } else if (line.startsWith("TBT Info:")) {
                    section = "TBT";
                    continue;
                } else if (line.startsWith("TBT SubInfo:")) {
                    section = "SUB1";
                    continue;
                } else if (line.startsWith("TBT SubInfo2:")) {
                    section = "SUB2";
                    continue;
                } else if (line.startsWith("STD POS P Info:")) {
                    section = "STDPOS";
                    continue;
                } else if (line.startsWith("SCC HU P Info:")) {
                    section = "SCC";
                    continue;
                } else if (line.startsWith("OperationStatus")) {
                    section = "ROOT";
                }

                if ("TRAP".equals(section)) {
                    if (line.contains("Mobile") && line.contains("Speed")) {
                        st.mobileSpeed = normalizeTrapSpeed(extractKeyInt(line, "Mobile\\s+Speed\\s*=\\s*(-?\\d+)", st.mobileSpeed));
                    }
                    if (line.contains("Fixed") && line.contains("Speed")) {
                        st.fixedSpeed = normalizeTrapSpeed(extractKeyInt(line, "Fixed\\s+Speed\\s*=\\s*(-?\\d+)", st.fixedSpeed));
                    }
                    if (line.contains("Redline") && line.contains("Speed")) {
                        st.redlineSpeed = normalizeTrapSpeed(extractKeyInt(line, "Redline\\s+Speed\\s*=\\s*(-?\\d+)", st.redlineSpeed));
                    }
                    if (line.contains("OverspeedAlarm")) {
                        st.overSpeedAlarm = extractKeyInt(line, "OverspeedAlarm\\s*=\\s*(-?\\d+)", st.overSpeedAlarm);
                    }
                    if (line.startsWith("Warning Zone")) {
                        String wz = substringAfter(line, "=").trim();
                        if (wz.contains("SCHOOL ZONE")) st.warningZone = 2;
                        else if (wz.contains("SPEED BUMP")) st.warningZone = 8;
                        else if (wz.contains("NONE") || wz.equals("0")) st.warningZone = 0;
                    }
                    if (line.startsWith("NonSpeed Trap")) {
                        String ns = substringAfter(line, "=").trim();
                        if (ns.contains("PLATE RECOGN")) st.nonSpeed = 7;
                        else if (ns.contains("RED LIGHT")) st.nonSpeed = 1;
                        else if (ns.contains("BUS ONLY")) st.nonSpeed = 2;
                    }
                    if (line.startsWith("BarGraphLevel")) {
                        // Note: firmware bug appends TollgateUnit (e.g. "0 OFF" or "62 KRW")
                        String bg = substringAfter(line, "=").trim();
                        st.trapBarGraph = (int) parseLeadingDouble(bg, st.trapBarGraph);
                    }
                } else if ("TBT".equals(section)) {
                    if (line.startsWith("Display Type")) {
                        String dt = substringAfter(line, "=").trim();
                        applyDisplayTypeStringLocked(st, dt);
                    } else if (line.startsWith("Direction")) {
                        String dir = substringAfter(line, "=").trim();
                        st.tbtDirectionDeg = parseLeadingDouble(dir, st.tbtDirectionDeg);
                    } else if (line.startsWith("Distance")) {
                        String dist = substringAfter(line, "=").trim();
                        st.tbtDistM = parseDistanceWithUnitText(dist);
                    } else if (line.startsWith("BarGraph Level")) {
                        String bg = substringAfter(line, "=").trim();
                        if (bg.contains("None") || bg.contains("Not") || bg.contains("Invalid")) {
                            st.tbtBarGraph = -1;
                        } else {
                            st.tbtBarGraph = (int) parseLeadingDouble(bg, -1);
                        }
                    }
                } else if ("SUB1".equals(section)) {
                    if (line.startsWith("distance")) {
                        String d = substringAfter(line, "=").split("\\|")[0].trim();
                        int distM = parseDistanceWithUnitText(d);
                        if (distM >= 0) st.remainDistM = distM;
                    }
                    if (line.contains("minutes")) {
                        int mins = extractKeyInt(line, "minutes\\s*=\\s*(-?\\d+)", -1);
                        if (mins >= 0 && mins < 10000) st.remainTimeSec = mins * 60;
                    }
                    if (line.startsWith("CompassDegree")) {
                        double deg = parseLeadingDouble(substringAfter(line, "=").trim(), -1.0);
                        if (deg >= 0.0 && deg <= 360.0 && st.lat == 0.0) {
                            st.headingDeg = deg;
                        }
                    }
                } else if ("SUB2".equals(section)) {
                    if (line.startsWith("next 1")) {
                        st.subInfo2Dist1M = parseDistanceWithUnitText(substringAfter(line, "=").trim());
                    } else if (line.startsWith("next 2")) {
                        st.subInfo2Dist2M = parseDistanceWithUnitText(substringAfter(line, "=").trim());
                    }
                } else if ("STDPOS".equals(section)) {
                    if (line.contains("RangeAvgSpeed")) {
                        st.stdPosRangeAvgSpeed = extractKeyInt(line, "RangeAvgSpeed\\s*=\\s*(-?\\d+)", st.stdPosRangeAvgSpeed);
                    }
                } else if ("SCC".equals(section)) {
                    if (line.contains("RangeCamInfo")) {
                        int ri = extractKeyInt(line, "RangeCamInfo\\s*=\\s*(-?\\d+)", st.rangeCamInfo);
                        st.rangeCamInfo = (ri < 0 || ri >= 7) ? 0 : ri;
                    }
                    if (line.contains("RangeCamDist")) {
                        int rd = extractKeyInt(line, "RangeCamDist\\s*=\\s*(-?\\d+)", st.rangeCamDist);
                        st.rangeCamDist = (rd < 0 || rd >= 65535) ? 0 : rd;
                    }
                    if (line.contains("RangeCamSpdLimit")) {
                        int rs = extractKeyInt(line, "RangeCamSpdLimit\\s*=\\s*(-?\\d+)", st.rangeCamSpdLimit);
                        st.rangeCamSpdLimit = (rs == 15) ? 0 : normalizeTrapSpeed(rs);
                    }
                    if (line.contains("RangeCamAvgMovingSpd")) {
                        st.rangeCamAvgMovingSpd = extractKeyInt(line, "RangeCamAvgMovingSpd\\s*=\\s*(-?\\d+)", st.rangeCamAvgMovingSpd);
                    }
                    if (line.contains("SetDestStatus")) {
                        st.setDestStatus = extractKeyInt(line, "SetDestStatus\\s*=\\s*(-?\\d+)", st.setDestStatus);
                    }
                    if (line.contains("OffRouteStatus")) {
                        st.offRouteStatus = extractKeyInt(line, "OffRouteStatus\\s*=\\s*(-?\\d+)", st.offRouteStatus);
                    }
                }

                // Root TP fields
                if (line.startsWith("OperationStatus")) {
                    st.operationStatus = extractKeyInt(line, "OperationStatus\\s*=\\s*(-?\\d+)", st.operationStatus);
                } else if (line.startsWith("CrossName =")) {
                    String hex = substringAfter(line, "CrossName =").trim();
                    String decoded = decodeTpHexCrossName(hex);
                    if (!decoded.isEmpty()) st.crossroadName = decoded;
                } else if (line.startsWith("DestName =")) {
                    String hex = substringAfter(line, "DestName =").trim();
                    String decoded = decodeTpHexDestName(hex);
                    if (!decoded.isEmpty()) st.destName = decoded;
                } else if (line.startsWith("TurnList =")) {
                    String hex = substringAfter(line, "TurnList =").trim();
                    decodeTpHexTurnListLocked(hex, st);
                }
            }
            refreshRoadLimitLocked(st);
        }
    }

    // =========================================================================
    // MAPPING ENGINE: NavState -> CarrotPacket (CarrotServ.update 7706 format)
    // =========================================================================

    public static CarrotPacket buildCarrotPacket(NavState st) {
        CarrotPacket pkt = new CarrotPacket();
        synchronized (st) {
            st.carrotIndex++;
            pkt.carrotIndex = st.carrotIndex;
            pkt.epochTime = System.currentTimeMillis() / 1000L;
            pkt.nRoadLimitSpeed = st.nRoadLimitSpeed > 0 ? st.nRoadLimitSpeed : 30;
            pkt.roadcate = st.roadcate;

            // 1. Determine camera distance (prefer updateNaviWarnDist warnDistOverDec/overDec, fallback to nextCamDist)
            // Note: trapBarGraph (barGraphLevel in updateTBTTrapInfo) is TBT_BarGraph100Level for turns, NOT camera distance.
            int camDist = st.camWarnDistM;
            if (camDist <= 0 && st.nextCamDist > 0 && st.nextCamDist < 1023) {
                camDist = st.nextCamDist;
            }

            int gen5wCamLimit = st.trapSpeedLimit > 0 ? st.trapSpeedLimit
                    : (st.slifSpeedLimit > 0 ? st.slifSpeedLimit : pkt.nRoadLimitSpeed);
            boolean isSchoolZone = (st.warningZone == 2 || st.warningZone2 == 2);
            boolean isSpeedBump = (st.warningZone == 8 || st.warningZone == 15 || st.warningZone == 128
                    || st.warningZone2 == 8 || st.warningZone2 == 15 || st.warningZone2 == 128);
            boolean isSectionActive = (st.rangeCamInfo > 0 && st.rangeCamInfo < 7 && st.rangeCamDist > 0 && st.rangeCamDist < 65535)
                    || ((st.speedInfoType == 4 || st.cameraSafetyAlert == 4) && (st.fixedSpeed > 0 || st.trapSpeedLimit > 0));
            boolean isRearCamera = (st.nonSpeed == 7 || st.nonSpeed == 64 || st.nonSpeed == 31);

            int avgSpd = st.rangeCamAvgMovingSpd > 0 ? st.rangeCamAvgMovingSpd : st.stdPosRangeAvgSpeed;
            if (avgSpd >= 255) avgSpd = 0;

            // 2. Primary SDI mapping (7 items, supporting both legacy fixedSpeed/redlineSpeed/mobileSpeed and Gen5W speedInfoType/cameraSafetyAlert/speedLimit)
            if (isSectionActive) {
                // Item 4 & 5: Section speed enforcement + average vehicle speed
                int secLimit = st.rangeCamSpdLimit > 0 ? st.rangeCamSpdLimit
                        : (st.fixedSpeed > 0 ? st.fixedSpeed : gen5wCamLimit);
                int secDist = (st.rangeCamDist > 0 && st.rangeCamDist < 65535) ? st.rangeCamDist : Math.max(camDist, 100);
                pkt.nSdiType = 4;
                pkt.nSdiSpeedLimit = secLimit;
                pkt.nSdiSection = 1;
                pkt.nSdiDist = secDist;
                pkt.nSdiBlockType = 2; // inOSEPS
                pkt.nSdiBlockSpeed = secLimit;
                pkt.nSdiBlockDist = secDist;
                pkt.nSdiBlockAverageSpeed = avgSpd;
            } else if (st.redlineSpeed > 0) {
                // Item 1 / 3: Signal + speed camera (or rear signal+speed camera if PLATE_RECOGN)
                pkt.nSdiType = isRearCamera ? 76 : 0;
                pkt.nSdiSpeedLimit = st.redlineSpeed;
                pkt.nSdiDist = Math.max(camDist, 50);
            } else if (st.fixedSpeed > 0) {
                // Item 1 / 3: Fixed speed camera (or rear speed camera if PLATE_RECOGN)
                pkt.nSdiType = isRearCamera ? 75 : 1;
                pkt.nSdiSpeedLimit = st.fixedSpeed;
                pkt.nSdiDist = Math.max(camDist, 50);
            } else if (st.mobileSpeed > 0) {
                // Item 2: Mobile speed camera
                pkt.nSdiType = 7;
                pkt.nSdiSpeedLimit = st.mobileSpeed;
                pkt.nSdiDist = Math.max(camDist, 50);
            } else if ((st.speedInfoType == 5 || st.cameraSafetyAlert == 3) && gen5wCamLimit > 0) {
                // Gen5W Signal + Speed Camera (speedInfoType: 5, cameraSafetyAlert3, speedLimit: 50/60/70...)
                pkt.nSdiType = isRearCamera ? 76 : 0;
                pkt.nSdiSpeedLimit = gen5wCamLimit;
                pkt.nSdiDist = camDist > 0 ? camDist : 50;
            } else if ((st.speedInfoType == 1 || st.cameraSafetyAlert == 1) && gen5wCamLimit > 0) {
                // Gen5W Fixed Speed Camera (speedInfoType: 1, cameraSafetyAlert1, speedLimit: 30/50/60...)
                pkt.nSdiType = isRearCamera ? 75 : 1;
                pkt.nSdiSpeedLimit = gen5wCamLimit;
                pkt.nSdiDist = camDist > 0 ? camDist : 50;
            } else if ((st.speedInfoType == 2 || st.speedInfoType == 3 || st.cameraSafetyAlert == 2) && gen5wCamLimit > 0) {
                // Gen5W Mobile / Box Speed Camera (speedInfoType: 2/3, cameraSafetyAlert2)
                pkt.nSdiType = 7;
                pkt.nSdiSpeedLimit = gen5wCamLimit;
                pkt.nSdiDist = camDist > 0 ? camDist : 50;
            } else if (st.nextCamType > 0 && st.nextCamType < 15 && st.nextCamSpdLimit > 0 && st.nextCamDist > 0 && st.nextCamDist < 1023) {
                // Fallback from ADAS SCC_HU_Navi_P
                pkt.nSdiType = (st.nextCamType == 2) ? 7 : 1;
                pkt.nSdiSpeedLimit = st.nextCamSpdLimit;
                pkt.nSdiDist = st.nextCamDist;
            } else if (isSchoolZone) {
                // Item 6: School Zone without fixed camera
                pkt.nSdiType = 20;
                pkt.nSdiSpeedLimit = 30;
                pkt.nSdiDist = Math.max(camDist, 100);
                pkt.nSdiPlusType = 20;
                pkt.nSdiPlusSpeedLimit = 30;
                pkt.nSdiPlusDist = Math.max(camDist, 100);
                pkt.nRoadLimitSpeed = 30;
            } else if (isSpeedBump) {
                // Item 7: Speed Bump
                int bumpDist = Math.max(camDist, 60);
                pkt.nSdiType = 22;
                pkt.nSdiDist = bumpDist;
                pkt.nSdiPlusType = 22;
                pkt.nSdiPlusDist = bumpDist;
                if (pkt.roadcate <= 1) pkt.roadcate = 4; // Ensure CarrotPilot bump logic (roadcate > 1) activates
            }

            // 3. Secondary SDI (nSdiPlus*) when a camera AND School Zone / Speed Bump are active simultaneously
            if (pkt.nSdiType >= 0 && pkt.nSdiType != 20 && isSchoolZone) {
                pkt.nSdiPlusType = 20;
                pkt.nSdiPlusSpeedLimit = 30;
                pkt.nSdiPlusDist = Math.max(camDist, 100);
            } else if (pkt.nSdiType >= 0 && pkt.nSdiType != 22 && isSpeedBump) {
                pkt.nSdiPlusType = 22;
                pkt.nSdiPlusDist = Math.max(camDist, 60);
                if (pkt.roadcate <= 1) pkt.roadcate = 4;
            }

            // 4. TBT (Route Guidance) mapping
            boolean guiding = st.isGuidanceActive();
            if (guiding && st.offRouteStatus == 0) {
                int primaryTurn = mapHkmcToCarrotTurnType(st.displayTypeCode, st.tbtDirectionDeg, st.sccNextTurnType1, st.displayTG, st.tollGateFee);
                int primaryDist = st.tbtDistM > 0 ? st.tbtDistM
                        : (st.remainTurnDistM > 0 ? st.remainTurnDistM
                        : (st.sccNextTurnDist1 > 0 && st.sccNextTurnDist1 < 4095 ? st.sccNextTurnDist1 : st.turnListDist1M));

                pkt.nTBTTurnType = primaryTurn;
                pkt.nTBTDist = Math.max(0, primaryDist);
                pkt.szTBTMainText = !st.crossroadName.isEmpty() ? st.crossroadName
                        : (!st.turnListName1.isEmpty() ? st.turnListName1 : st.displayTypeName);

                // Next (2nd) turn
                int nextTurn = st.sccNextTurnType2 > 0
                        ? mapSccTurnTypeToCarrot(st.sccNextTurnType2)
                        : (st.turnListType2 > 0 ? mapHkmcToCarrotTurnType(st.turnListType2, 0.0, 0, 0, 0) : -1);
                int nextDist = st.subInfo2Dist1M > 0 ? st.subInfo2Dist1M
                        : (st.sccNextTurnDist2 > 0 && st.sccNextTurnDist2 < 4095 ? st.sccNextTurnDist2 : st.turnListDist2M);
                if (nextDist > 0 && nextTurn < 0) {
                    nextTurn = 51; // straight/notification if distance is known
                }
                pkt.nTBTTurnTypeNext = nextTurn;
                pkt.nTBTDistNext = Math.max(0, nextDist);
                pkt.szTBTMainTextNext = !st.turnListName2.isEmpty() ? st.turnListName2 : pkt.szTBTMainText;

                pkt.nTBTNextRoadWidth = st.numLanes > 0 ? Math.max(5, st.numLanes * 3) : 9;
                pkt.nGoPosDist = Math.max(0, st.remainDistM);
                pkt.nGoPosTime = Math.max(0, st.remainTimeSec);
                pkt.szGoalName = st.destName;
            } else {
                pkt.nTBTTurnType = -1;
                pkt.nTBTDist = 0;
                pkt.nTBTTurnTypeNext = -1;
                pkt.nTBTDistNext = 0;
                pkt.nGoPosDist = 0;
                pkt.nGoPosTime = 0;
            }

            pkt.szPosRoadName = !st.posRoadName.isEmpty() ? st.posRoadName : st.crossroadName;
            pkt.vpPosPointLat = st.lat;
            pkt.vpPosPointLon = st.lon;
            pkt.nPosAngle = st.headingDeg;
            pkt.nPosSpeed = st.posSpeedKph;
        }
        return pkt;
    }

    /**
     * Maps HKMC Navigation$DisplayType (0..46) + Direction angle (0..360) + SCC nextTurnType1
     * to CarrotPilot's turn_type_mapping codes in carrot_serv.py.
     */
    public static int mapHkmcToCarrotTurnType(int displayType, double directionDeg, int sccTurnType1, int displayTG, int tollFee) {
        if (displayTG == 1 && tollFee > 0 && (displayType == 11 || displayType == 2)) {
            return 153; // TollGate (xTurnInfo = 6)
        }
        switch (displayType) {
            case 0:
            case 1:
            case 6:
            case 7:
            case 8:
            case 9:
            case 10:
                return -1; // No active turn guidance
            case 3:
            case 4:
            case 5:
                return 201; // ARRIVED / NEAR DESTINATION
            case 2:
            case 11:
                if (sccTurnType1 > 0 && sccTurnType1 != 11) {
                    int sccMapped = mapSccTurnTypeToCarrot(sccTurnType1);
                    if (sccMapped > 0) return sccMapped;
                }
                return 51; // FOLLOW STREET (straight)
            case 12:
                return directionDeg > 180.0 ? 7 : 6; // CHANGE LANE left/right
            case 13:
            case 14:
            case 15:
            case 16:
            case 34:
                // TURN: Use Direction angle (0~360 deg, 90=Right, 270=Left, 180=U-turn)
                if (directionDeg >= 20.0 && directionDeg <= 115.0) return 13; // Turn Right
                if (directionDeg > 115.0 && directionDeg <= 165.0) return 19; // Sharp Right
                if (directionDeg > 165.0 && directionDeg < 195.0) return 14;  // U-Turn
                if (directionDeg >= 195.0 && directionDeg < 245.0) return 16; // Sharp Left
                if (directionDeg >= 245.0 && directionDeg <= 340.0) return 12; // Turn Left
                if (sccTurnType1 > 0) {
                    int sccMapped = mapSccTurnTypeToCarrot(sccTurnType1);
                    if (sccMapped > 0) return sccMapped;
                }
                return 13;
            case 17:
            case 19:
            case 20:
            case 23:
            case 36:
            case 39:
            case 40:
                return 101; // EXIT RIGHT / SERVICE ROAD RIGHT / RAMP RIGHT
            case 18:
            case 21:
            case 22:
            case 24:
            case 41:
            case 42:
                return 102; // EXIT LEFT / SERVICE ROAD LEFT / RAMP LEFT
            case 25:
            case 26:
                return directionDeg > 180.0 ? 7 : 6; // FORK-2 / FORK-3
            case 27:
            case 28:
            case 29:
            case 30:
            case 32:
            case 33:
            case 35:
            case 37:
                return 131; // ROUNDABOUT / SQUARE -> rotary
            case 31:
            case 38:
                return 14;  // U TURN
            case 43:
            case 45:
                return 43;  // OVERPASS RIGHT / UNDERPASS RIGHT -> fork right
            case 44:
            case 46:
                return 44;  // OVERPASS LEFT / UNDERPASS LEFT -> fork left
            default:
                return 51;
        }
    }

    public static int mapSccTurnTypeToCarrot(int sccTurnType) {
        switch (sccTurnType) {
            case 12: return 12;  // Left
            case 13: return 13;  // Right
            case 14: return 14;  // U-Turn
            case 16: return 16;  // Sharp left
            case 17: return 101; // Exit right
            case 18: return 102; // Exit left
            case 19: return 19;  // Sharp right
            case 31: return 14;  // U-turn
            default: return -1;
        }
    }

    private static void applyDisplayTypeStringLocked(NavState st, String raw) {
        if (raw == null || raw.isEmpty()) return;
        st.displayTypeName = raw;
        String u = raw.toUpperCase(Locale.US);
        // Check if raw is an integer code
        int numeric = parseIntSafe(u, -1);
        if (numeric >= 0 && numeric <= 46) {
            st.displayTypeCode = numeric;
            return;
        }
        if (u.contains("NO ROUTE GUIDANCE") || u.contains("COMPASS")) st.displayTypeCode = 8;
        else if (u.contains("RECALC ROUTE")) st.displayTypeCode = 10;
        else if (u.contains("CALC ROUTE")) st.displayTypeCode = 9;
        else if (u.contains("ARRIVED")) st.displayTypeCode = 3;
        else if (u.contains("NEAR DESTINATION")) st.displayTypeCode = 4;
        else if (u.contains("FOLLOW STREET")) st.displayTypeCode = 11;
        else if (u.contains("PREPARE U TURN") || u.equals("U TURN") || u.contains("U-TURN")) st.displayTypeCode = 31;
        else if (u.contains("EXIT RIGHT RAMP UP")) st.displayTypeCode = 39;
        else if (u.contains("EXIT RIGHT RAMP DOWN")) st.displayTypeCode = 40;
        else if (u.contains("EXIT LEFT RAMP UP")) st.displayTypeCode = 41;
        else if (u.contains("EXIT LEFT RAMP DOWN")) st.displayTypeCode = 42;
        else if (u.contains("EXIT RIGHT")) st.displayTypeCode = 17;
        else if (u.contains("EXIT LEFT")) st.displayTypeCode = 18;
        else if (u.contains("SERVICE ROAD RIGHT")) st.displayTypeCode = 23;
        else if (u.contains("SERVICE ROAD LEFT")) st.displayTypeCode = 24;
        else if (u.contains("OVERPASS RIGHT")) st.displayTypeCode = 43;
        else if (u.contains("OVERPASS LEFT")) st.displayTypeCode = 44;
        else if (u.contains("UNDERPASS RIGHT")) st.displayTypeCode = 45;
        else if (u.contains("UNDERPASS LEFT")) st.displayTypeCode = 46;
        else if (u.contains("ROUNDABOUT") || u.contains("ROUNABOUT")) st.displayTypeCode = 27;
        else if (u.contains("FORK")) st.displayTypeCode = 25;
        else if (u.contains("CHANGE LANE")) st.displayTypeCode = 12;
        else if (u.contains("TURN")) st.displayTypeCode = 13;
        else if (u.contains("NO SYMBOL")) st.displayTypeCode = 0;
        else if (u.contains("NO INFO")) st.displayTypeCode = 1;
    }

    // =========================================================================
    // STRING & HEX DECODERS FOR HKMC TP PACKETS (CrossName, DestName, TurnList)
    // =========================================================================

    /**
     * com.mnsoft.navi's sendCluster_H_U_Navi_T_TurnName() encodes strings via:
     *   new String(orig.getBytes("UTF-16"), "UnicodeLittle")
     * which prepends \uFFFE and byte-swaps every UTF-16 code unit so that
     * ClusterService's Utils.convertString(..., "UTF-16LE") outputs FE FF + UTF-16BE!
     * This method transparently reverses that byte-swap when present.
     */
    public static String decodeMnsoftTpString(String s) {
        if (s == null || s.isEmpty()) return "";
        if (s.charAt(0) == '\uFFFE') {
            byte[] leBytes = s.getBytes(UTF16LE);
            return cleanDecodedString(new String(leBytes, UTF16));
        }
        if (s.charAt(0) == '\uFEFF') {
            return cleanDecodedString(s.substring(1));
        }
        // Check if characters are byte-swapped Hangul (e.g. 0x00AC instead of 0xAC00)
        int swappedHangul = 0;
        int normalHangul = 0;
        for (int i = 0; i < s.length(); i++) {
            int c = s.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7A3) normalHangul++;
            int swapped = ((c & 0xFF) << 8) | ((c >> 8) & 0xFF);
            if (swapped >= 0xAC00 && swapped <= 0xD7A3) swappedHangul++;
        }
        if (swappedHangul > normalHangul) {
            byte[] leBytes = s.getBytes(UTF16LE);
            return cleanDecodedString(new String(leBytes, UTF16BE));
        }
        return cleanDecodedString(s);
    }

    public static String decodeTpHexCrossName(String hexLine) {
        byte[] raw = parseHexBytes(hexLine);
        if (raw.length < 4) return "";
        // Skip 0xF0 TP header byte if present
        int start = ((raw[0] & 0xFF) == 0xF0) ? 1 : 0;
        return decodeUtf16FromTpBytes(raw, start, raw.length);
    }

    public static String decodeTpHexDestName(String hexLine) {
        byte[] raw = parseHexBytes(hexLine);
        if (raw.length < 4) return "";
        // In updateTPFlexibleDestinationName, byte 0 is 0xF4, byte 1 is dest type (0..3), string starts at offset 2 (or 1)
        int start = 0;
        if ((raw[0] & 0xFF) == 0xF4) {
            start = (raw.length > 4 && ((raw[2] & 0xFF) == 0xFE || (raw[2] & 0xFF) == 0xFF)) ? 2 : 1;
        }
        return decodeUtf16FromTpBytes(raw, start, raw.length);
    }

    private static void decodeTpHexTurnListLocked(String hexLine, NavState st) {
        byte[] raw = parseHexBytes(hexLine);
        if (raw.length < 12) return;
        // TurnList starts with 0xF2; each entry in updateTPTurnList is 40 bytes (or variable in New)
        // Extract any embedded UTF-16 strings (marked by FE FF BOM)
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < raw.length - 3; i++) {
            if ((raw[i] & 0xFF) == 0xFE && (raw[i + 1] & 0xFF) == 0xFF) {
                String s = decodeUtf16FromTpBytes(raw, i, Math.min(raw.length, i + 40));
                if (!s.isEmpty()) {
                    names.add(s);
                }
            }
        }
        if (names.size() >= 1 && !names.get(0).isEmpty()) {
            st.turnListName1 = names.get(0);
        }
        if (names.size() >= 2 && !names.get(1).isEmpty()) {
            st.turnListName2 = names.get(1);
        }
    }

    public static String decodeUtf16FromTpBytes(byte[] buf, int start, int limit) {
        if (buf == null || start >= limit || start >= buf.length) return "";
        int end = Math.min(limit, buf.length);
        boolean bigEndian = false;
        if (start + 1 < end) {
            int b0 = buf[start] & 0xFF;
            int b1 = buf[start + 1] & 0xFF;
            if (b0 == 0xFE && b1 == 0xFF) {
                bigEndian = true;
                start += 2;
            } else if (b0 == 0xFF && b1 == 0xFE) {
                bigEndian = false;
                start += 2;
            }
        }
        int validEnd = start;
        while (validEnd + 1 < end) {
            int b0 = buf[validEnd] & 0xFF;
            int b1 = buf[validEnd + 1] & 0xFF;
            if (b0 == 0 && b1 == 0) break;
            validEnd += 2;
        }
        if (validEnd <= start) return "";
        byte[] slice = new byte[validEnd - start];
        System.arraycopy(buf, start, slice, 0, slice.length);
        String decoded = new String(slice, bigEndian ? UTF16BE : UTF16LE);
        return cleanDecodedString(decoded);
    }

    private static String cleanDecodedString(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u0000' || c == '\uFEFF' || c == '\uFFFE') continue;
            if (c >= 0x20) sb.append(c);
        }
        return sb.toString().trim();
    }

    public static byte[] parseHexBytes(String hex) {
        if (hex == null || hex.isEmpty()) return new byte[0];
        String[] tokens = hex.trim().split("\\s+");
        ByteArrayOutputStream out = new ByteArrayOutputStream(tokens.length);
        for (String tok : tokens) {
            if (tok.length() == 2) {
                try {
                    out.write(Integer.parseInt(tok, 16));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return out.toByteArray();
    }

    // =========================================================================
    // UNIT & SPEED HELPERS
    // =========================================================================

    private static int normalizeTrapSpeed(int val) {
        if (val <= 0 || val == 15 || val >= 254) return 0;
        // In dumpsys / SCC_HU_Navi_P, Fixed Speed = 6 means 60 km/h (1..14 -> 10..140 km/h; 15 is 0xF invalid)
        if (val >= 1 && val <= 14) return val * 10;
        return val;
    }

    private static void refreshRoadLimitLocked(NavState st) {
        if (st.warningZone == 2 || st.warningZone2 == 2) {
            st.nRoadLimitSpeed = 30;
            return;
        }
        if (st.trapSpeedLimit >= 20 && st.trapSpeedLimit <= 140) {
            st.nRoadLimitSpeed = st.trapSpeedLimit;
        } else if (st.slifSpeedLimit >= 20 && st.slifSpeedLimit <= 140) {
            st.nRoadLimitSpeed = st.slifSpeedLimit;
        } else if (st.segSpeedLimit >= 20 && st.segSpeedLimit <= 140) {
            st.nRoadLimitSpeed = st.segSpeedLimit;
        } else if (st.fixedSpeed >= 20 && st.fixedSpeed <= 140) {
            st.nRoadLimitSpeed = st.fixedSpeed;
        } else if (st.redlineSpeed >= 20 && st.redlineSpeed <= 140) {
            st.nRoadLimitSpeed = st.redlineSpeed;
        }
        if (st.nRoadLimitSpeed >= 90 && st.curRoadClass <= 1) {
            st.roadcate = 1;
        } else if (st.roadcate <= 1 && st.nRoadLimitSpeed < 90 && st.curRoadClass > 1) {
            st.roadcate = 4;
        }
    }

    private static void updateRoadCateFromClassLocked(NavState st, int cls) {
        // HKMC road class: 0/1 = Freeway/Motorway, 2..7 = National/Local/City roads
        if (cls == 0 || cls == 1) {
            st.roadcate = 1;
        } else {
            st.roadcate = 4;
        }
    }

    /**
     * Converts HKMC distance + scale/unit to meters.
     * For updateNaviWarnDist (isDistanceUnit=false): 0=METER, 1=KILO_METER, 2=MILE
     * For DistanceUnit (isDistanceUnit=true): 1=METER, 2=KILO-METER, 3=MILE, 4=FEET
     */
    private static int convertDistToMeters(int overDec, int underDec, int unit, boolean isDistanceUnit) {
        if (overDec < 0 || overDec >= 65535) return 0;
        double val = overDec + (underDec > 0 && underDec < 10 ? underDec * 0.1 : 0.0);
        return convertFloatDistToMeters(val, unit, isDistanceUnit);
    }

    private static int convertFloatDistToMeters(double val, int unit, boolean isDistanceUnit) {
        if (val <= 0.0 || Double.isNaN(val) || Double.isInfinite(val)) return 0;
        if (isDistanceUnit) {
            switch (unit) {
                case 1: return (int) Math.round(val);          // METER
                case 2: return (int) Math.round(val * 1000.0); // KILO-METER
                case 3: return (int) Math.round(val * 1609.34);// MILE
                case 4: return (int) Math.round(val * 0.3048); // FEET
                default: return 0;
            }
        } else {
            switch (unit) {
                case 0: return (int) Math.round(val);          // METER
                case 1: return (int) Math.round(val * 1000.0); // KILO_METER
                case 2: return (int) Math.round(val * 1609.34);// MILE
                default: return (int) Math.round(val);
            }
        }
    }

    private static int parseDistanceWithUnitText(String text) {
        if (text == null || text.isEmpty()) return 0;
        String u = text.toUpperCase(Locale.US);
        if (u.contains("NONE") || u.contains("INVALID") || u.contains("ERROR")) return 0;
        double val = parseLeadingDouble(u, 0.0);
        if (val <= 0.0) return 0;
        if (u.contains("KILO") || u.endsWith("KM") || u.contains(" KM")) {
            return (int) Math.round(val * 1000.0);
        }
        if (u.contains("MILE") || u.endsWith("MI")) {
            return (int) Math.round(val * 1609.34);
        }
        if (u.contains("FEET") || u.endsWith("FT")) {
            return (int) Math.round(val * 0.3048);
        }
        return (int) Math.round(val);
    }

    private static Map<String, Integer> extractIntMap(String line) {
        Map<String, Integer> map = new LinkedHashMap<String, Integer>();
        Matcher m = KV_INT_PATTERN.matcher(line);
        while (m.find()) {
            map.put(m.group(1), parseIntSafe(m.group(2), 0));
        }
        return map;
    }

    private static int extractKeyInt(String line, String regex, int def) {
        Matcher m = Pattern.compile(regex).matcher(line);
        if (m.find()) {
            return parseIntSafe(m.group(1), def);
        }
        return def;
    }

    private static int extractIntAfter(String line, String token, int def) {
        int idx = line.indexOf(token);
        if (idx < 0) return def;
        return (int) parseLeadingDouble(line.substring(idx + token.length()).trim(), def);
    }

    private static double extractDoubleAfter(String line, String token) {
        int idx = line.indexOf(token);
        if (idx < 0) return -1.0;
        return parseLeadingDouble(line.substring(idx + token.length()).trim(), -1.0);
    }

    private static String extractBundleStr(String line, String key) {
        int idx = line.indexOf(key);
        if (idx < 0) return "";
        String sub = line.substring(idx + key.length());
        int end = sub.indexOf(',');
        int endBrace = sub.indexOf('}');
        if (end < 0 || (endBrace >= 0 && endBrace < end)) end = endBrace;
        return (end >= 0 ? sub.substring(0, end) : sub).trim();
    }

    private static String substringAfter(String s, String token) {
        int idx = s.indexOf(token);
        return idx >= 0 ? s.substring(idx + token.length()) : "";
    }

    private static int parseIntSafe(String s, int def) {
        if (s == null) return def;
        try {
            String t = s.trim();
            if (t.startsWith("0x") || t.startsWith("0X")) {
                return Integer.parseInt(t.substring(2), 16);
            }
            return Integer.parseInt(t);
        } catch (Exception e) {
            return def;
        }
    }

    private static double parseLeadingDouble(String s, double def) {
        if (s == null || s.isEmpty()) return def;
        Matcher m = Pattern.compile("(-?\\d+(?:\\.\\d+)?)").matcher(s);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (Exception e) {
                return def;
            }
        }
        return def;
    }

    // =========================================================================
    // TRACE FILE & SHELL HELPERS
    // =========================================================================

    private static synchronized void appendTraceLine(File traceFile, String line) {
        if (traceFile == null) return;
        try {
            File parent = traceFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (traceFile.exists() && traceFile.length() > TRACE_MAX_BYTES) {
                File old = new File(traceFile.getAbsolutePath() + ".old");
                if (old.exists()) old.delete();
                traceFile.renameTo(old);
            }
            FileOutputStream fos = new FileOutputStream(traceFile, true);
            try {
                String stamped = System.currentTimeMillis() + " " + line + "\n";
                fos.write(stamped.getBytes(UTF8));
            } finally {
                fos.close();
            }
        } catch (Exception ignored) {
        }
    }

    public static String execCommandCapture(String[] cmd, int maxLines) {
        Process proc = null;
        try {
            proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream(), UTF8));
            StringBuilder sb = new StringBuilder();
            String line;
            int count = 0;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
                count++;
                if (maxLines > 0 && count >= maxLines) break;
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        } finally {
            if (proc != null) {
                try { proc.destroy(); } catch (Exception ignored) {}
            }
        }
    }

    public static void enableMnsoftClusterLogging() {
        execCommandCapture(new String[]{
                "/system/bin/am", "broadcast",
                "-a", "com.android.Eng.LogSetting",
                "--eia", "intArray", "0,0,0,0,0,0,0,0,0,0,0,0,0,0,0"
        }, 10);
    }

    // =========================================================================
    // DAEMON MODE: 7705 DISCOVERY + LOGCAT + DUMPSYS + 7706 5HZ TRANSMITTER
    // =========================================================================

    private static volatile String discoveredCarrotIp = "";
    private static volatile long discoveredCarrotTimeMs = 0L;
    private static volatile double carrotVEgoKph = 0.0;
    private static volatile CarrotPacket latestSentPacket = null;
    private static volatile String latestTargetsStr = "[]";
    private static volatile String lastAutoUpdateStatus = "내비 켜질 때 1회 자동 확인 (콜드/웜부팅 모두 지원)";
    private static volatile long lastWarmWakeUpdateMs = System.currentTimeMillis();

    public static boolean isWarmWakeNaviOnLine(String line) {
        if (line == null || line.isEmpty()) return false;
        return line.contains("ACC Status Changed : 0 -> 1")
                || line.contains("Broadcasting ACC Status (not buffering) : 1")
                || line.contains("SCREEN_ON broadcast has arrived")
                || line.contains("ACTION_POST_QUICKBOOT")
                || line.contains("com.mobis.intent.ACTION_POWER_ON");
    }

    // MAP button 3-press detector state
    private static final Object MAP_KEY_LOCK = new Object();
    private static long lastMapKeySignalMs = 0L;
    private static long firstMapPressWindowMs = 0L;
    private static int mapPressCounter = 0;

    /**
     * Checks if a logcat line from events (ivi_mode_broadcast) or main/system (ModeKeyDispatcher)
     * indicates a physical MAP button press, and returns true when 3 presses occur within 2.5 seconds.
     */
    public static boolean checkMapShortcutLine(String line, long nowMs) {
        if (line == null || line.isEmpty()) return false;
        // Ignore key-release lines so a single press (DOWN + UP) never counts twice
        if (line.contains("ACTION_UP")) return false;
        boolean matched = line.contains("KEYCODE_SHORTCUT_MAP")
                || line.contains("com.hkmc.intent.action.MAP_SHORTCUT")
                || line.contains("Map shortcut received");
        if (!matched) return false;

        synchronized (MAP_KEY_LOCK) {
            // Single physical press emits both KEYCODE_SHORTCUT_MAP and MAP_SHORTCUT ~40-80ms apart.
            // Debounce signals within 160ms so 1 physical press = 1 count.
            if (nowMs - lastMapKeySignalMs < 160L) {
                return false;
            }
            lastMapKeySignalMs = nowMs;

            if (nowMs - firstMapPressWindowMs > 2500L) {
                firstMapPressWindowMs = nowMs;
                mapPressCounter = 1;
            } else {
                mapPressCounter++;
            }

            if (mapPressCounter >= 3) {
                mapPressCounter = 0;
                firstMapPressWindowMs = 0L;
                return true;
            }
            return false;
        }
    }

    public static void runDaemon(final String installDir) throws Exception {
        final NavState navState = new NavState();
        final File dumpDir = new File("/data/local/tmp/hkg_tbt_dump");
        if (!dumpDir.exists()) dumpDir.mkdirs();
        final File traceFile = new File(dumpDir, "tbt_live_trace.log");
        final File statusFile = new File("/data/local/tmp/carrotbridge_status.json");
        final File showUiTriggerFile = new File("/data/local/tmp/carrotbridge_show_ui");

        System.out.println("[CarrotBridge] Starting daemon v" + BRIDGE_VERSION + " (installDir=" + installDir + ")");
        enableMnsoftClusterLogging();
        StatusDashboardOverlay.init(installDir, navState);

        // 1. Thread: Listen on UDP 7705 for CarrotPilot broadcasts
        Thread udp7705Thread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[4096];
                while (true) {
                    DatagramSocket sock = null;
                    try {
                        sock = new DatagramSocket(null);
                        sock.setReuseAddress(true);
                        sock.bind(new InetSocketAddress("0.0.0.0", CARROT_BROADCAST_PORT));
                        while (true) {
                            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                            sock.receive(pkt);
                            String msg = new String(pkt.getData(), pkt.getOffset(), pkt.getLength(), UTF8);
                            String senderIp = pkt.getAddress().getHostAddress();
                            if (senderIp != null && !senderIp.startsWith("127.") && !senderIp.equals("192.168.0.1")) {
                                discoveredCarrotIp = senderIp;
                                discoveredCarrotTimeMs = System.currentTimeMillis();
                            }
                            double vEgo = extractDoubleAfter(msg, "\"v_ego_kph\":");
                            if (vEgo >= 0.0 && vEgo < 300.0) {
                                carrotVEgoKph = vEgo;
                                synchronized (navState) {
                                    navState.posSpeedKph = vEgo;
                                }
                            }
                        }
                    } catch (Exception e) {
                        try { Thread.sleep(2000L); } catch (InterruptedException ignored) {}
                    } finally {
                        if (sock != null) try { sock.close(); } catch (Exception ignored) {}
                    }
                }
            }
        }, "Carrot7705Listener");
        udp7705Thread.setDaemon(true);
        udp7705Thread.start();

        // 2. Thread: Stream main/system logcat
        Thread logcatThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    Process proc = null;
                    try {
                        long streamStartMs = System.currentTimeMillis();
                        proc = new ProcessBuilder(
                                "/system/bin/logcat", "-v", "brief",
                                "ClusterService:D", "NavigationCluster:D", "Java_Layer:I", "BrdCast:D", "ModeKeyDispatcher:V", "MicomPowerService:D", "*:S"
                        ).redirectErrorStream(true).start();
                        BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream(), UTF8));
                        String line;
                        while ((line = br.readLine()) != null) {
                            long nowMs = System.currentTimeMillis();
                            if (nowMs - streamStartMs > 600L) {
                                if (checkMapShortcutLine(line, nowMs)) {
                                    System.out.println("[CarrotBridge] MAP 3-press detected via ModeKeyDispatcher -> toggling Status Dashboard");
                                    StatusDashboardOverlay.toggle();
                                } else if (isWarmWakeNaviOnLine(line) && (nowMs - lastWarmWakeUpdateMs) > 60000L && !navState.isGuidanceActive()) {
                                    lastWarmWakeUpdateMs = nowMs;
                                    System.out.println("[CarrotBridge] Warm Wakeup / ACC ON detected -> running one-time GitHub Auto-Update check");
                                    new Thread(new Runnable() {
                                        @Override
                                        public void run() {
                                            try { Thread.sleep(8000L); } catch (InterruptedException ignored) {}
                                            String nowStr = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
                                            int rc = runAutoUpdate("GRT47", "HKG_5W_CarrotNavi", "main", installDir, false);
                                            if (rc == 0) {
                                                lastAutoUpdateStatus = "시동/내비 ON 확인 완료 (최신 v" + BRIDGE_VERSION + ", " + nowStr + ")";
                                            } else if (rc == 10) {
                                                lastAutoUpdateStatus = "시동/내비 ON 새 버전 적용 -> 재시작 (" + nowStr + ")";
                                                try { Thread.sleep(1000L); } catch (InterruptedException ignored) {}
                                                System.exit(0);
                                            }
                                        }
                                    }, "WarmWakeAutoUpdater").start();
                                }
                            }
                            parseLogcatLine(line, navState, traceFile);
                        }
                    } catch (Exception e) {
                        try { Thread.sleep(1000L); } catch (InterruptedException ignored) {}
                    } finally {
                        if (proc != null) try { proc.destroy(); } catch (Exception ignored) {}
                    }
                }
            }
        }, "LogcatReader");
        logcatThread.setDaemon(true);
        logcatThread.start();

        // 2b. Thread: Stream events logcat (ivi_mode_broadcast) for instant MAP button 3-press detection
        Thread eventsLogcatThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    Process proc = null;
                    try {
                        long streamStartMs = System.currentTimeMillis();
                        proc = new ProcessBuilder(
                                "/system/bin/logcat", "-b", "events", "-v", "brief"
                        ).redirectErrorStream(true).start();
                        BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream(), UTF8));
                        String line;
                        while ((line = br.readLine()) != null) {
                            long nowMs = System.currentTimeMillis();
                            // Ignore historical buffered lines dumped during the first 600ms of stream start
                            if (nowMs - streamStartMs < 600L) {
                                continue;
                            }
                            if (checkMapShortcutLine(line, nowMs)) {
                                System.out.println("[CarrotBridge] MAP 3-press detected via ivi_mode_broadcast -> toggling Status Dashboard");
                                StatusDashboardOverlay.toggle();
                            }
                        }
                    } catch (Exception e) {
                        try { Thread.sleep(1500L); } catch (InterruptedException ignored) {}
                    } finally {
                        if (proc != null) try { proc.destroy(); } catch (Exception ignored) {}
                    }
                }
            }
        }, "MapButtonEventsReader");
        eventsLogcatThread.setDaemon(true);
        eventsLogcatThread.start();

        // 3. Thread: Poll dumpsys activity service com.hkmc.micom.ClusterService
        Thread dumpsysThread = new Thread(new Runnable() {
            @Override
            public void run() {
                long lastEngWakeMs = System.currentTimeMillis();
                String lastSavedDump = "";
                while (true) {
                    try {
                        long now = System.currentTimeMillis();
                        if (now - lastEngWakeMs > 60000L) {
                            enableMnsoftClusterLogging();
                            lastEngWakeMs = now;
                        }
                        String out = execCommandCapture(new String[]{
                                "/system/bin/dumpsys", "cluster"
                        }, 150);
                        if (!out.isEmpty()) {
                            parseDumpsysCluster(out, navState);
                            boolean guiding = navState.isGuidanceActive();
                            if (guiding && !out.equals(lastSavedDump)) {
                                lastSavedDump = out;
                                appendTraceLine(traceFile, "DUMPSYS_SNAPSHOT:\n" + out);
                            }
                        }
                        long sleepMs = navState.isGuidanceActive() ? 500L : 1500L;
                        Thread.sleep(sleepMs);
                    } catch (Exception e) {
                        try { Thread.sleep(1500L); } catch (InterruptedException ignored) {}
                    }
                }
            }
        }, "DumpsysPoller");
        dumpsysThread.setDaemon(true);
        dumpsysThread.start();

        // 4. Main Loop: Send UDP 7706 packets at 5Hz (every 200ms)
        DatagramSocket txSock = new DatagramSocket();
        txSock.setBroadcast(true);
        Set<String> arpPeers = new LinkedHashSet<String>();
        long lastArpScanMs = 0L;
        long lastStatusWriteMs = 0L;

        while (true) {
            long now = System.currentTimeMillis();
            if (showUiTriggerFile.exists()) {
                showUiTriggerFile.delete();
                StatusDashboardOverlay.show();
            }

            if (now - lastArpScanMs > 5000L) {
                arpPeers = scanWlanArpPeers();
                lastArpScanMs = now;
            }

            CarrotPacket pkt = buildCarrotPacket(navState);
            latestSentPacket = pkt;
            String json = pkt.toJson();
            byte[] payload = json.getBytes(UTF8);

            Set<String> targets = new LinkedHashSet<String>();
            if (!discoveredCarrotIp.isEmpty() && (now - discoveredCarrotTimeMs) < 30000L) {
                targets.add(discoveredCarrotIp);
            }
            targets.addAll(arpPeers);
            if (targets.isEmpty() || (pkt.carrotIndex % 5 == 0)) {
                targets.add("192.168.0.255");
            }
            latestTargetsStr = targets.toString();

            for (String ip : targets) {
                try {
                    InetAddress addr = InetAddress.getByName(ip);
                    DatagramPacket dp = new DatagramPacket(payload, payload.length, addr, CARROT_TARGET_PORT);
                    txSock.send(dp);
                } catch (Exception ignored) {
                }
            }

            if (now - lastStatusWriteMs > 2000L) {
                lastStatusWriteMs = now;
                writeStatusFile(statusFile, json, targets);
                if (navState.isGuidanceActive()) {
                    appendTraceLine(traceFile, "TX7706: " + json);
                }
            }

            Thread.sleep(200L);
        }
    }

    private static Set<String> scanWlanArpPeers() {
        Set<String> peers = new LinkedHashSet<String>();
        File arp = new File("/proc/net/arp");
        if (!arp.exists()) return peers;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(arp), UTF8));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("IP address")) continue;
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 6) {
                    String ip = parts[0];
                    String flags = parts[2];
                    String dev = parts[5];
                    if ("wlan0".equals(dev) && !"0x0".equals(flags) && !ip.equals("192.168.0.1")) {
                        peers.add(ip);
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (br != null) try { br.close(); } catch (Exception ignored) {}
        }
        return peers;
    }

    private static void writeStatusFile(File statusFile, String lastPacketJson, Set<String> targets) {
        try {
            StringBuilder sb = new StringBuilder(1024);
            sb.append("{\n");
            sb.append("  \"bridgeVersion\": \"").append(BRIDGE_VERSION).append("\",\n");
            sb.append("  \"timestampMs\": ").append(System.currentTimeMillis()).append(",\n");
            sb.append("  \"discoveredCarrotIp\": \"").append(discoveredCarrotIp).append("\",\n");
            sb.append("  \"targets\": \"").append(targets.toString()).append("\",\n");
            sb.append("  \"lastPacket\": ").append(lastPacketJson).append("\n");
            sb.append("}\n");
            writeBytesAtomic(statusFile, sb.toString().getBytes(UTF8));
        } catch (Exception ignored) {
        }
    }

    // =========================================================================
    // GITHUB AUTO-UPDATER (UDP DNS 8.8.8.8 + TLS 1.2 SNI + SHA-256 VERIFICATION)
    // =========================================================================

    private static final String[] PUBLIC_DNS_SERVERS = new String[]{"8.8.8.8", "1.1.1.1"};

    /**
     * Resolves a hostname via direct UDP DNS query to 8.8.8.8 / 1.1.1.1 (routed over rmnet0 Public LTE),
     * bypassing Android's system DNS which is trapped on rmnet1 (172.19.140.37 SKT internal DNS).
     */
    public static String resolveHostViaPublicDns(String host) {
        for (String dnsServer : PUBLIC_DNS_SERVERS) {
            DatagramSocket sock = null;
            try {
                byte[] query = buildDnsQueryPacket(host);
                sock = new DatagramSocket();
                sock.setSoTimeout(4000);
                DatagramPacket req = new DatagramPacket(query, query.length, InetAddress.getByName(dnsServer), 53);
                sock.send(req);

                byte[] respBuf = new byte[1024];
                DatagramPacket resp = new DatagramPacket(respBuf, respBuf.length);
                sock.receive(resp);
                String ip = parseDnsResponseA(respBuf, resp.getLength());
                if (ip != null && !ip.isEmpty()) {
                    return ip;
                }
            } catch (Exception ignored) {
            } finally {
                if (sock != null) try { sock.close(); } catch (Exception ignored) {}
            }
        }
        // Hardcoded CDN fallbacks if UDP 53 is transiently blocked
        if (host.contains("githubusercontent.com")) {
            return "185.199.108.133";
        }
        if (host.equals("github.com") || host.endsWith(".github.com")) {
            return "140.82.112.4";
        }
        return null;
    }

    public static byte[] buildDnsQueryPacket(String domain) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        // Transaction ID (0x1337), Flags (0x0100 standard query, recursion desired), QDCOUNT=1
        out.write(new byte[]{0x13, 0x37, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
        String[] labels = domain.trim().split("\\.");
        for (String label : labels) {
            byte[] lb = label.getBytes(UTF8);
            out.write(lb.length & 0xFF);
            out.write(lb);
        }
        out.write(0x00); // end of QNAME
        out.write(new byte[]{0x00, 0x01, 0x00, 0x01}); // QTYPE=A (1), QCLASS=IN (1)
        return out.toByteArray();
    }

    public static String parseDnsResponseA(byte[] buf, int len) {
        if (len < 12) return null;
        int qdCount = ((buf[4] & 0xFF) << 8) | (buf[5] & 0xFF);
        int anCount = ((buf[6] & 0xFF) << 8) | (buf[7] & 0xFF);
        int pos = 12;
        for (int q = 0; q < qdCount; q++) {
            pos = skipDnsName(buf, len, pos);
            if (pos < 0 || pos + 4 > len) return null;
            pos += 4;
        }
        for (int a = 0; a < anCount; a++) {
            pos = skipDnsName(buf, len, pos);
            if (pos < 0 || pos + 10 > len) return null;
            int rtype = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
            int rdlen = ((buf[pos + 8] & 0xFF) << 8) | (buf[pos + 9] & 0xFF);
            pos += 10;
            if (pos + rdlen > len) return null;
            if (rtype == 1 && rdlen == 4) {
                return (buf[pos] & 0xFF) + "." + (buf[pos + 1] & 0xFF) + "."
                        + (buf[pos + 2] & 0xFF) + "." + (buf[pos + 3] & 0xFF);
            }
            pos += rdlen;
        }
        return null;
    }

    private static int skipDnsName(byte[] buf, int len, int pos) {
        while (pos < len) {
            int b = buf[pos] & 0xFF;
            if (b == 0) return pos + 1;
            if ((b & 0xC0) == 0xC0) return pos + 2;
            pos += 1 + b;
        }
        return -1;
    }

    public static byte[] httpsDownloadWithTls12(String urlStr, int maxRedirects) throws Exception {
        String currentUrl = urlStr;
        for (int hop = 0; hop <= maxRedirects; hop++) {
            if (!currentUrl.startsWith("https://")) {
                throw new IOException("Only https:// URLs are supported: " + currentUrl);
            }
            String withoutScheme = currentUrl.substring("https://".length());
            int slashIdx = withoutScheme.indexOf('/');
            String host = slashIdx >= 0 ? withoutScheme.substring(0, slashIdx) : withoutScheme;
            String path = slashIdx >= 0 ? withoutScheme.substring(slashIdx) : "/";
            int port = 443;
            int colonIdx = host.indexOf(':');
            if (colonIdx >= 0) {
                port = Integer.parseInt(host.substring(colonIdx + 1));
                host = host.substring(0, colonIdx);
            }

            String ip = resolveHostViaPublicDns(host);
            if (ip == null || ip.isEmpty()) {
                throw new IOException("Failed to resolve host via Public DNS: " + host);
            }

            SSLContext sc = SSLContext.getInstance("TLSv1.2");
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
                        @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
                        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    }
            };
            sc.init(null, trustAll, new SecureRandom());
            SSLSocketFactory factory = sc.getSocketFactory();

            Socket rawSock = new Socket();
            SSLSocket sslSock = null;
            try {
                rawSock.connect(new InetSocketAddress(ip, port), 10000);
                rawSock.setSoTimeout(15000);
                sslSock = (SSLSocket) factory.createSocket(rawSock, host, port, true);
                sslSock.setEnabledProtocols(new String[]{"TLSv1.2"});
                try {
                    Method setHostname = sslSock.getClass().getMethod("setHostname", String.class);
                    setHostname.invoke(sslSock, host);
                } catch (Exception ignored) {
                }
                sslSock.startHandshake();

                OutputStream out = sslSock.getOutputStream();
                String req = "GET " + path + " HTTP/1.1\r\n"
                        + "Host: " + host + "\r\n"
                        + "User-Agent: HKG-CarrotBridge/" + BRIDGE_VERSION + "\r\n"
                        + "Accept: */*\r\n"
                        + "Connection: close\r\n\r\n";
                out.write(req.getBytes(UTF8));
                out.flush();

                InputStream in = sslSock.getInputStream();
                String statusLine = readAsciiLine(in);
                if (statusLine == null) throw new IOException("Empty HTTP response from " + host);
                int statusCode = parseHttpStatus(statusLine);

                Map<String, String> headers = new LinkedHashMap<String, String>();
                while (true) {
                    String hline = readAsciiLine(in);
                    if (hline == null || hline.isEmpty()) break;
                    int c = hline.indexOf(':');
                    if (c > 0) {
                        headers.put(hline.substring(0, c).trim().toLowerCase(Locale.US), hline.substring(c + 1).trim());
                    }
                }

                if (statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307 || statusCode == 308) {
                    String loc = headers.get("location");
                    if (loc == null || loc.isEmpty()) {
                        throw new IOException("HTTP redirect without Location header: " + statusLine);
                    }
                    if (loc.startsWith("/")) {
                        currentUrl = "https://" + host + loc;
                    } else {
                        currentUrl = loc;
                    }
                    continue;
                }
                if (statusCode != 200) {
                    throw new IOException("HTTP " + statusCode + " for " + currentUrl);
                }

                String te = headers.get("transfer-encoding");
                if (te != null && te.toLowerCase(Locale.US).contains("chunked")) {
                    return readChunkedBody(in);
                }
                return readAllBytes(in);
            } finally {
                if (sslSock != null) try { sslSock.close(); } catch (Exception ignored) {}
                try { rawSock.close(); } catch (Exception ignored) {}
            }
        }
        throw new IOException("Too many redirects for " + urlStr);
    }

    private static String readAsciiLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int b;
        boolean gotAny = false;
        while ((b = in.read()) != -1) {
            gotAny = true;
            if (b == '\n') break;
            if (b != '\r') buf.write(b);
        }
        if (!gotAny && buf.size() == 0) return null;
        return new String(buf.toByteArray(), UTF8);
    }

    private static int parseHttpStatus(String statusLine) {
        String[] parts = statusLine.trim().split("\\s+");
        if (parts.length >= 2) {
            return parseIntSafe(parts[1], -1);
        }
        return -1;
    }

    private static byte[] readChunkedBody(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readAsciiLine(in);
            if (sizeLine == null) break;
            int semi = sizeLine.indexOf(';');
            if (semi >= 0) sizeLine = sizeLine.substring(0, semi);
            int chunkSize = Integer.parseInt(sizeLine.trim(), 16);
            if (chunkSize == 0) {
                readAsciiLine(in);
                break;
            }
            byte[] buf = new byte[chunkSize];
            int readTotal = 0;
            while (readTotal < chunkSize) {
                int r = in.read(buf, readTotal, chunkSize - readTotal);
                if (r < 0) throw new IOException("Unexpected EOF in chunked body");
                readTotal += r;
            }
            out.write(buf);
            readAsciiLine(in); // trailing CRLF
        }
        return out.toByteArray();
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) != -1) {
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }

    public static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(data);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format(Locale.US, "%02x", b & 0xFF));
        }
        return sb.toString();
    }

    public static int runAutoUpdate(String owner, String repo, String branch, String installDir, boolean force) {
        try {
            String baseUrl = "https://raw.githubusercontent.com/" + owner + "/" + repo + "/" + branch;
            String verUrl = baseUrl + "/version.json";
            System.out.println("[AutoUpdate] Checking " + verUrl);

            byte[] verBytes = httpsDownloadWithTls12(verUrl, 5);
            String verJson = new String(verBytes, UTF8);

            String remoteVersion = extractJsonString(verJson, "version");
            if (remoteVersion.isEmpty()) {
                System.out.println("[AutoUpdate] Invalid version.json");
                return 2;
            }

            File localVerFile = new File(installDir, "version.json");
            String localVersion = "";
            if (localVerFile.exists()) {
                byte[] localBytes = readAllBytes(new FileInputStream(localVerFile));
                localVersion = extractJsonString(new String(localBytes, UTF8), "version");
            }
            File jarFile = new File(installDir, "bin/carrotbridge.jar");
            boolean jarNeedsUpdate = !jarFile.exists() || !remoteVersion.equals(BRIDGE_VERSION);

            System.out.println("[AutoUpdate] Local version=" + localVersion + " (jar=" + BRIDGE_VERSION + "), Remote version=" + remoteVersion);
            if (!force && remoteVersion.equals(localVersion) && !jarNeedsUpdate) {
                System.out.println("[AutoUpdate] Already up to date.");
                return 0;
            }

            // 1. Update scripts/configs listed in version.json "files" block
            Pattern fileEntryPattern = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\\{\\s*\"remote\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"sha256\"\\s*:\\s*\"([0-9a-fA-F]{64})\"");
            Matcher m = fileEntryPattern.matcher(verJson);
            int updatedCount = 0;
            while (m.find()) {
                String relTarget = m.group(1);
                String remotePath = m.group(2);
                String expectedSha = m.group(3).toLowerCase(Locale.US);

                File targetFile = new File(installDir, relTarget);
                String currentSha = targetFile.exists() ? sha256Hex(readAllBytes(new FileInputStream(targetFile))) : "";
                if (!force && expectedSha.equals(currentSha)) {
                    continue;
                }

                String fileUrl = remotePath.startsWith("https://") ? remotePath : (baseUrl + "/" + remotePath);
                byte[] fileBytes = httpsDownloadWithTls12(fileUrl, 5);
                String actualSha = sha256Hex(fileBytes);
                if (!actualSha.equals(expectedSha)) {
                    throw new IOException("SHA-256 mismatch for " + relTarget + ": expected=" + expectedSha + " actual=" + actualSha);
                }

                backupAndWriteAtomic(targetFile, fileBytes);
                updatedCount++;
                System.out.println("[AutoUpdate] Updated " + relTarget + " (sha256=" + actualSha.substring(0, 12) + "...)");
            }

            // 2. Check release JAR (carrotbridge.jar + carrotbridge.jar.sha256)
            String releaseJarUrl = extractJsonString(verJson, "release_jar_url");
            String releaseShaUrl = extractJsonString(verJson, "release_jar_sha256_url");
            if (!releaseJarUrl.isEmpty() && !releaseShaUrl.isEmpty()) {
                try {
                    byte[] shaBytes = httpsDownloadWithTls12(releaseShaUrl, 5);
                    String expectedJarSha = new String(shaBytes, UTF8).trim().split("\\s+")[0].toLowerCase(Locale.US);
                    if (expectedJarSha.length() == 64) {
                        String currentJarSha = jarFile.exists() ? sha256Hex(readAllBytes(new FileInputStream(jarFile))) : "";
                        if (force || !expectedJarSha.equals(currentJarSha)) {
                            byte[] jarBytes = httpsDownloadWithTls12(releaseJarUrl, 5);
                            String actualJarSha = sha256Hex(jarBytes);
                            if (!actualJarSha.equals(expectedJarSha)) {
                                throw new IOException("JAR SHA-256 mismatch: expected=" + expectedJarSha + " actual=" + actualJarSha);
                            }
                            backupAndWriteAtomic(jarFile, jarBytes);
                            updatedCount++;
                            System.out.println("[AutoUpdate] Updated bin/carrotbridge.jar (sha256=" + actualJarSha.substring(0, 12) + "...)");
                        }
                    }
                } catch (Exception jarEx) {
                    System.out.println("[AutoUpdate] Release JAR check failed: " + jarEx.getMessage());
                    if (jarNeedsUpdate) {
                        // Do not mark local version.json as up-to-date if the required JAR update failed
                        return 1;
                    }
                }
            }

            writeBytesAtomic(localVerFile, verBytes);
            System.out.println("[AutoUpdate] Update completed successfully (" + updatedCount + " files updated to v" + remoteVersion + ").");
            return updatedCount > 0 ? 10 : 0; // 10 signals caller to restart daemon
        } catch (Exception e) {
            System.out.println("[AutoUpdate] Error: " + e.getMessage());
            return 1;
        }
    }

    private static String extractJsonString(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1).trim() : "";
    }

    private static void backupAndWriteAtomic(File target, byte[] content) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        if (target.exists()) {
            File bak = new File(target.getAbsolutePath() + ".bak");
            copyFile(target, bak);
        }
        writeBytesAtomic(target, content);
        if (target.getName().endsWith(".sh")) {
            target.setExecutable(true, false);
        }
    }

    private static void writeBytesAtomic(File target, byte[] content) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            fos.write(content);
            fos.getFD().sync();
        } finally {
            fos.close();
        }
        if (target.exists() && !tmp.renameTo(target)) {
            target.delete();
            tmp.renameTo(target);
        } else {
            tmp.renameTo(target);
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        FileInputStream in = new FileInputStream(src);
        try {
            byte[] data = readAllBytes(in);
            writeBytesAtomic(dst, data);
        } finally {
            in.close();
        }
    }

    // =========================================================================
    // MAP BUTTON 3-PRESS STATUS DASHBOARD OVERLAY UI (Android 4.4.2 System UI)
    // =========================================================================

    public static final class StatusDashboardOverlay {
        private static volatile String installDir = "/data/nemesis/apps/carrotnavi.bridge";
        private static volatile NavState navStateRef = null;
        private static volatile Object uiHandler = null;
        private static volatile Object windowManager = null;
        private static volatile Object sysContext = null;
        private static volatile Object rootOverlayView = null;
        private static volatile Object headerSysInfoTextView = null;
        private static volatile Object leftColTextView = null;
        private static volatile Object autoHotspotCheckTextView = null;
        private static volatile Object rightColTextView = null;
        private static volatile Object footerStatusTextView = null;
        private static volatile boolean isVisible = false;
        private static volatile long shownAtMs = 0L;
        private static volatile String actionBannerMsg = "MAP 버튼 3연타로 열기/닫기 | 60초 후 자동 닫힘";
        private static volatile long lastSysBadgeMs = 0L;
        private static volatile String cachedSysBadgeText = "🌡️ CPU: --°C  |  💾 RAM: --/--MB";
        private static volatile long lastCpuTotalTicks = 0L;
        private static volatile long lastCpuIdleTicks = 0L;
        private static volatile int lastCpuLoadPct = 0;
        private static volatile File cachedThermalFile = null;
        private static volatile boolean thermalSearchDone = false;

        private static final Runnable REFRESH_TASK = new Runnable() {
            @Override
            public void run() {
                refreshOnUiThread();
            }
        };

        public static void init(final String dir, final NavState navState) {
            installDir = dir;
            navStateRef = navState;
            Thread uiThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Class<?> looperCls = Class.forName("android.os.Looper");
                        try {
                            looperCls.getMethod("prepareMainLooper").invoke(null);
                        } catch (Throwable ignored) {
                            looperCls.getMethod("prepare").invoke(null);
                        }
                        Object myLooper = looperCls.getMethod("myLooper").invoke(null);
                        Class<?> handlerCls = Class.forName("android.os.Handler");
                        uiHandler = handlerCls.getConstructor(looperCls).newInstance(myLooper);

                        System.out.println("[CarrotBridge] StatusDashboardOverlay UI Looper initialized.");
                        Method loopMethod = looperCls.getMethod("loop");
                        // Immortal Looper recovery loop: if any framework callback inside Looper.loop() throws an
                        // uncaught exception, log the cause and immediately resume pumping the MessageQueue so the
                        // UI thread never terminates and the overlay never freezes on screen.
                        while (true) {
                            try {
                                loopMethod.invoke(null);
                                break;
                            } catch (Throwable loopErr) {
                                Throwable cause = (loopErr.getCause() != null) ? loopErr.getCause() : loopErr;
                                System.out.println("[CarrotBridge] UI Looper caught exception (resuming loop): " + cause);
                            }
                        }
                    } catch (Throwable t) {
                        Throwable cause = (t.getCause() != null) ? t.getCause() : t;
                        System.out.println("[CarrotBridge] StatusDashboardOverlay init skipped: " + cause);
                    }
                }
            }, "CarrotStatusUiThread");
            uiThread.setDaemon(true);
            uiThread.start();
        }

        private static boolean ensureUiContextOnUiThread() {
            if (sysContext != null && windowManager != null) {
                return true;
            }
            try {
                // Verify Android system_server services (window, display, activity) are ready before touching ActivityThread.
                // Calling ActivityThread.systemMain() before "display" service is registered leaves ActivityThread.mSystemContext
                // half-initialized with mPackageInfo=null.
                Class<?> smCls = Class.forName("android.os.ServiceManager");
                Method getSvc = smCls.getMethod("getService", String.class);
                if (getSvc.invoke(null, "window") == null
                        || getSvc.invoke(null, "display") == null
                        || getSvc.invoke(null, "activity") == null) {
                    System.out.println("[CarrotBridge] ensureUiContextOnUiThread deferred: system services not ready yet");
                    return false;
                }

                Class<?> atCls = Class.forName("android.app.ActivityThread");
                Object at = null;
                try {
                    at = atCls.getMethod("currentActivityThread").invoke(null);
                } catch (Throwable ignored) {
                }
                if (at == null) {
                    at = atCls.getMethod("systemMain").invoke(null);
                }
                if (at != null && sysContext == null) {
                    sysContext = atCls.getMethod("getSystemContext").invoke(at);
                }
                if (sysContext != null && windowManager == null) {
                    Method getSysSvc = sysContext.getClass().getMethod("getSystemService", String.class);
                    windowManager = getSysSvc.invoke(sysContext, "window");
                }
            } catch (Throwable t) {
                Throwable cause = (t.getCause() != null) ? t.getCause() : t;
                System.out.println("[CarrotBridge] ensureUiContextOnUiThread failed: " + cause);
                sysContext = null;
                windowManager = null;
                try {
                    Class<?> atCls = Class.forName("android.app.ActivityThread");
                    java.lang.reflect.Field fSysCtx = atCls.getDeclaredField("mSystemContext");
                    fSysCtx.setAccessible(true);
                    fSysCtx.set(null, null);
                    java.lang.reflect.Field fCurAt = atCls.getDeclaredField("sCurrentActivityThread");
                    fCurAt.setAccessible(true);
                    fCurAt.set(null, null);
                } catch (Throwable ignored) {
                }
            }
            return sysContext != null && windowManager != null;
        }

        public static void toggle() {
            postToUi(new Runnable() {
                @Override
                public void run() {
                    if (isVisible) {
                        hideOnUiThread();
                    } else {
                        showOnUiThread();
                    }
                }
            });
        }

        public static void show() {
            postToUi(new Runnable() {
                @Override
                public void run() {
                    showOnUiThread();
                }
            });
        }

        public static void hide() {
            postToUi(new Runnable() {
                @Override
                public void run() {
                    hideOnUiThread();
                }
            });
        }

        private static void postToUi(Runnable r) {
            Object h = uiHandler;
            if (h == null) return;
            try {
                h.getClass().getMethod("post", Runnable.class).invoke(h, r);
            } catch (Throwable ignored) {
            }
        }

        private static void postDelayedToUi(Runnable r, long delayMs) {
            Object h = uiHandler;
            if (h == null) return;
            try {
                h.getClass().getMethod("postDelayed", Runnable.class, long.class).invoke(h, r, delayMs);
            } catch (Throwable ignored) {
            }
        }

        private static void removeCallbacksFromUi(Runnable r) {
            Object h = uiHandler;
            if (h == null) return;
            try {
                h.getClass().getMethod("removeCallbacks", Runnable.class).invoke(h, r);
            } catch (Throwable ignored) {
            }
        }

        private static Object[] buildDashboardViewTree() throws Exception {
            Class<?> ctxCls = Class.forName("android.content.Context");
            Class<?> viewCls = Class.forName("android.view.View");
            Class<?> vgCls = Class.forName("android.view.ViewGroup");
            Class<?> vgLpCls = Class.forName("android.view.ViewGroup$LayoutParams");
            Class<?> llCls = Class.forName("android.widget.LinearLayout");
            Class<?> llLpCls = Class.forName("android.widget.LinearLayout$LayoutParams");
            Class<?> tvCls = Class.forName("android.widget.TextView");

            Constructor<?> llCtor = llCls.getConstructor(ctxCls);
            Constructor<?> tvCtor = tvCls.getConstructor(ctxCls);
            Constructor<?> llLpCtor2 = llLpCls.getConstructor(int.class, int.class);
            Constructor<?> llLpCtor3 = llLpCls.getConstructor(int.class, int.class, float.class);
            Method tvSetGravity = tvCls.getMethod("setGravity", int.class);

            final int MATCH_PARENT = -1;
            final int WRAP_CONTENT = -2;

            // Root card container (vertical LinearLayout)
            Object root = llCtor.newInstance(sysContext);
            llCls.getMethod("setOrientation", int.class).invoke(root, 1); // VERTICAL
            viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class).invoke(root, 26, 20, 26, 20);
            setRoundedBg(root, 0xF20F172A, 0xFF38BDF8, 3, 18f);
            disableViewEffects(root, true);

            // 1. Header Row (Horizontal)
            Object headerRow = llCtor.newInstance(sysContext);
            llCls.getMethod("setOrientation", int.class).invoke(headerRow, 0); // HORIZONTAL
            llCls.getMethod("setGravity", int.class).invoke(headerRow, 16);    // CENTER_VERTICAL

            Object titleTv = tvCtor.newInstance(sysContext);
            setTextViewStyle(titleTv, "🥕 HKG 5W CarrotNavi — 상태 대시보드 (v" + BRIDGE_VERSION + ")", 20.5f, 0xFF38BDF8, true);
            Object titleLp = llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f);
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(headerRow, titleTv, titleLp);

            // System CPU Temp & RAM Usage Badge (between Header Title and Close Button)
            Object sysInfoTv = tvCtor.newInstance(sysContext);
            setTextViewStyle(sysInfoTv, updateSystemResourceBadge(System.currentTimeMillis()), 14.5f, 0xFFA7F3D0, true);
            tvSetGravity.invoke(sysInfoTv, 17); // CENTER
            setRoundedBg(sysInfoTv, 0xFF1E293B, 0xFF475569, 1, 10f);
            viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class).invoke(sysInfoTv, 14, 8, 14, 8);
            Object sysInfoLp = llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT);
            setMargins(sysInfoLp, 8, 0, 12, 0);
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(headerRow, sysInfoTv, sysInfoLp);

            // Note: Use TextView instead of Button because HKMC firmware removed Button's default drawable (#0x1080100 popup_btn_default.xml)
            Object closeBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(closeBtn, "  ✖ 닫기 (MAP 3연타)  ", 15.5f, 0xFFFFFFFF, true);
            tvSetGravity.invoke(closeBtn, 17); // CENTER
            setRoundedBg(closeBtn, 0xFFDC2626, 0xFFF87171, 2, 10f);
            viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class).invoke(closeBtn, 18, 9, 18, 9);
            attachClickListener(closeBtn, new Runnable() {
                @Override
                public void run() {
                    hide();
                }
            });
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(headerRow, closeBtn, llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT));
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(root, headerRow, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            // Divider
            Object div1 = viewCls.getConstructor(ctxCls).newInstance(sysContext);
            viewCls.getMethod("setBackgroundColor", int.class).invoke(div1, 0xFF334155);
            Object divLp1 = llLpCtor2.newInstance(MATCH_PARENT, 2);
            setMargins(divLp1, 0, 10, 0, 12);
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(root, div1, divLp1);

            // 2. Two-Column Body Row
            Object bodyRow = llCtor.newInstance(sysContext);
            llCls.getMethod("setOrientation", int.class).invoke(bodyRow, 0); // HORIZONTAL

            // Left Column Card
            Object leftCard = llCtor.newInstance(sysContext);
            llCls.getMethod("setOrientation", int.class).invoke(leftCard, 1);
            viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class).invoke(leftCard, 16, 12, 16, 12);
            setRoundedBg(leftCard, 0xFF1E293B, 0xFF475569, 1, 12f);

            Object leftHdr = tvCtor.newInstance(sysContext);
            setTextViewStyle(leftHdr, "[ 패키지 버전 및 통신/핫스팟 상태 ]", 16.5f, 0xFF34D399, true);
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(leftCard, leftHdr, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            Object leftTv = tvCtor.newInstance(sysContext);
            setTextViewStyle(leftTv, "", 15f, 0xFFE2E8F0, false);
            viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class).invoke(leftTv, 0, 8, 0, 0);
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(leftCard, leftTv, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            // Interactive Checkbox for Boot Auto-Hotspot (ON/OFF)
            Object autoHsCheckTv = tvCtor.newInstance(sysContext);
            styleAutoHotspotCheckbox(autoHsCheckTv, isBootAutoHotspotEnabled());
            tvSetGravity.invoke(autoHsCheckTv, 17); // CENTER
            viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class).invoke(autoHsCheckTv, 12, 8, 12, 8);
            attachClickListener(autoHsCheckTv, new Runnable() {
                @Override
                public void run() {
                    toggleBootAutoHotspotFromUi();
                }
            });
            Object autoHsCheckLp = llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT);
            setMargins(autoHsCheckLp, 0, 10, 0, 0);
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(leftCard, autoHsCheckTv, autoHsCheckLp);

            Object leftCardLp = llLpCtor3.newInstance(0, MATCH_PARENT, 1.0f);
            setMargins(leftCardLp, 0, 0, 8, 0);
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(bodyRow, leftCard, leftCardLp);

            // Right Column Card
            Object rightCard = llCtor.newInstance(sysContext);
            llCls.getMethod("setOrientation", int.class).invoke(rightCard, 1);
            viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class).invoke(rightCard, 16, 12, 16, 12);
            setRoundedBg(rightCard, 0xFF1E293B, 0xFF475569, 1, 12f);

            Object rightHdr = tvCtor.newInstance(sysContext);
            setTextViewStyle(rightHdr, "[ 실시간 순정 내비 SDI / TBT 변환 데이터 ]", 16.5f, 0xFFFBBF24, true);
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(rightCard, rightHdr, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            Object rightTv = tvCtor.newInstance(sysContext);
            setTextViewStyle(rightTv, "", 15f, 0xFFE2E8F0, false);
            viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class).invoke(rightTv, 0, 8, 0, 0);
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(rightCard, rightTv, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            Object rightCardLp = llLpCtor3.newInstance(0, MATCH_PARENT, 1.15f);
            setMargins(rightCardLp, 8, 0, 0, 0);
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(bodyRow, rightCard, rightCardLp);

            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(root, bodyRow, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            // 3. Action Buttons Row
            Object btnRow = llCtor.newInstance(sysContext);
            llCls.getMethod("setOrientation", int.class).invoke(btnRow, 0); // HORIZONTAL
            llCls.getMethod("setGravity", int.class).invoke(btnRow, 17);    // CENTER
            Object btnRowLp = llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT);
            setMargins(btnRowLp, 0, 12, 0, 6);

            // Button A: Open Hotspot Settings Screen Directly (com.hkmc.wifisettings/.WifiHotspot)
            Object hsUiBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(hsUiBtn, "📶 핫스팟 설정 화면", 14.5f, 0xFFFFFFFF, true);
            tvSetGravity.invoke(hsUiBtn, 17);
            setRoundedBg(hsUiBtn, 0xFF7C3AED, 0xFFA78BFA, 2, 10f);
            viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class).invoke(hsUiBtn, 10, 10, 10, 10);
            attachClickListener(hsUiBtn, new Runnable() {
                @Override
                public void run() {
                    openHotspotSettingsFromUi();
                }
            });
            Object hsUiBtnLp = llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f);
            setMargins(hsUiBtnLp, 0, 0, 6, 0);
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(btnRow, hsUiBtn, hsUiBtnLp);

            // Button B: Open Engineering Mode WiFi Info Screen Directly (com.hkmc.system.app.engineering/.module.WifiInfo)
            Object engUiBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(engUiBtn, "⚙️ 엔지니어링 WiFi", 14.5f, 0xFFFFFFFF, true);
            tvSetGravity.invoke(engUiBtn, 17);
            setRoundedBg(engUiBtn, 0xFF475569, 0xFF94A3B8, 2, 10f);
            viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class).invoke(engUiBtn, 10, 10, 10, 10);
            attachClickListener(engUiBtn, new Runnable() {
                @Override
                public void run() {
                    openEngineeringWifiFromUi();
                }
            });
            Object engUiBtnLp = llLpCtor3.newInstance(0, WRAP_CONTENT, 0.95f);
            setMargins(engUiBtnLp, 6, 0, 6, 0);
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(btnRow, engUiBtn, engUiBtnLp);

            // Button C: Re-assert Hotspot in Background
            Object apBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(apBtn, "📡 핫스팟 즉시 켜기", 14.5f, 0xFFFFFFFF, true);
            tvSetGravity.invoke(apBtn, 17);
            setRoundedBg(apBtn, 0xFF0284C7, 0xFF38BDF8, 2, 10f);
            viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class).invoke(apBtn, 10, 10, 10, 10);
            attachClickListener(apBtn, new Runnable() {
                @Override
                public void run() {
                    triggerHotspotCheckFromUi();
                }
            });
            Object apBtnLp = llLpCtor3.newInstance(0, WRAP_CONTENT, 0.95f);
            setMargins(apBtnLp, 6, 0, 6, 0);
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(btnRow, apBtn, apBtnLp);

            // Button D: Check GitHub Update Now
            Object updBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(updBtn, "🔄 GitHub 업데이트", 14.5f, 0xFFFFFFFF, true);
            tvSetGravity.invoke(updBtn, 17);
            setRoundedBg(updBtn, 0xFF2563EB, 0xFF60A5FA, 2, 10f);
            viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class).invoke(updBtn, 10, 10, 10, 10);
            attachClickListener(updBtn, new Runnable() {
                @Override
                public void run() {
                    triggerManualUpdateFromUi();
                }
            });
            Object updBtnLp = llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f);
            setMargins(updBtnLp, 6, 0, 6, 0);
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(btnRow, updBtn, updBtnLp);

            // Button E: Dump TBT to USB/Local Now
            Object dumpBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(dumpBtn, "💾 TBT 정밀 덤프", 14.5f, 0xFFFFFFFF, true);
            tvSetGravity.invoke(dumpBtn, 17);
            setRoundedBg(dumpBtn, 0xFF059669, 0xFF34D399, 2, 10f);
            viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class).invoke(dumpBtn, 10, 10, 10, 10);
            attachClickListener(dumpBtn, new Runnable() {
                @Override
                public void run() {
                    triggerManualDumpFromUi();
                }
            });
            Object dumpBtnLp = llLpCtor3.newInstance(0, WRAP_CONTENT, 0.95f);
            setMargins(dumpBtnLp, 6, 0, 0, 0);
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(btnRow, dumpBtn, dumpBtnLp);

            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(root, btnRow, btnRowLp);

            // 4. Footer Status Line
            Object footerTv = tvCtor.newInstance(sysContext);
            setTextViewStyle(footerTv, actionBannerMsg, 14.5f, 0xFF93C5FD, false);
            tvCls.getMethod("setGravity", int.class).invoke(footerTv, 17); // CENTER
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(root, footerTv, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            return new Object[]{root, leftTv, rightTv, footerTv, sysInfoTv, autoHsCheckTv};
        }

        private static void showOnUiThread() {
            try {
                if (!ensureUiContextOnUiThread()) {
                    System.out.println("[CarrotBridge] StatusDashboardOverlay show skipped: UI context unavailable");
                    return;
                }
                if (isVisible && rootOverlayView != null) {
                    shownAtMs = System.currentTimeMillis();
                    refreshOnUiThread();
                    return;
                }

                Class<?> viewCls = Class.forName("android.view.View");
                Class<?> vgLpCls = Class.forName("android.view.ViewGroup$LayoutParams");
                Class<?> wmLpCls = Class.forName("android.view.WindowManager$LayoutParams");
                Class<?> vmCls = Class.forName("android.view.ViewManager");

                // Window types verified in android.policy.odex (PhoneWindowManager.checkAddPermission @ 0x1bce4):
                // 2020 = TYPE_VOLUME_OVERLAY (layer 18, outAppOp = -1, INTERNAL_SYSTEM_WINDOW granted to uid=0, touchable)
                // 2008 = TYPE_SYSTEM_DIALOG  (layer 17, outAppOp = -1, INTERNAL_SYSTEM_WINDOW granted to uid=0, touchable)
                // 2009 = TYPE_KEYGUARD_DIALOG(layer 13, outAppOp = -1, INTERNAL_SYSTEM_WINDOW granted to uid=0, touchable)
                // 2005 = TYPE_TOAST          (layer 6,  outAppOp = -1, no permission required, touchable)
                // Note: 2010/2003/2002 set outAppOp=24 (OP_SYSTEM_ALERT_WINDOW), which causes AppOpsService
                // to throw SecurityException("Bad call: specified package android under uid 0 but it is really 1000").
                int[] candidateTypes = new int[]{2020, 2008, 2009, 2005};
                int flags = 0x00000008 | 0x00000020 | 0x00000080; // FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_KEEP_SCREEN_ON
                Constructor<?> wmLpCtor = wmLpCls.getConstructor(int.class, int.class, int.class, int.class, int.class);
                Method addViewMethod = vmCls.getMethod("addView", viewCls, vgLpCls);

                for (int wtype : candidateTypes) {
                    Object[] tree = null;
                    try {
                        // Build a fresh view tree per candidate so a failed addView never leaves a poisoned View in WindowManagerGlobal.mViews
                        tree = buildDashboardViewTree();
                        Object candidateRoot = tree[0];
                        Object wmLp = wmLpCtor.newInstance(1220, -2, wtype, flags, -3); // -2=WRAP_CONTENT, -3=PixelFormat.TRANSLUCENT
                        wmLpCls.getField("gravity").setInt(wmLp, 17); // Gravity.CENTER
                        wmLpCls.getMethod("setTitle", CharSequence.class).invoke(wmLp, "CarrotNaviStatusOverlay");
                        addViewMethod.invoke(windowManager, candidateRoot, wmLp);

                        // Disable ViewRootImpl sound effects (mMediaDisabled = true) so ViewRootImpl.playSoundEffect()
                        // never queries AudioManager -> Settings.System.getInt -> ActivityManagerService.getContentProvider("settings"),
                        // which throws SecurityException("Unable to find app for caller ...") in standalone app_process.
                        try {
                            Object vri = viewCls.getMethod("getViewRootImpl").invoke(candidateRoot);
                            if (vri != null) {
                                java.lang.reflect.Field fMedia = vri.getClass().getDeclaredField("mMediaDisabled");
                                fMedia.setAccessible(true);
                                fMedia.setBoolean(vri, true);
                            }
                        } catch (Throwable ignored) {
                        }

                        rootOverlayView = candidateRoot;
                        leftColTextView = tree[1];
                        rightColTextView = tree[2];
                        footerStatusTextView = tree[3];
                        headerSysInfoTextView = tree.length > 4 ? tree[4] : null;
                        autoHotspotCheckTextView = tree.length > 5 ? tree[5] : null;
                        isVisible = true;
                        shownAtMs = System.currentTimeMillis();
                        System.out.println("[CarrotBridge] StatusDashboardOverlay displayed (windowType=" + wtype + ")");
                        refreshOnUiThread();
                        break;
                    } catch (Throwable t) {
                        Throwable cause = (t.getCause() != null) ? t.getCause() : t;
                        System.out.println("[CarrotBridge] addView(wtype=" + wtype + ") failed: " + cause);
                        if (tree != null && tree[0] != null) {
                            try {
                                windowManager.getClass().getMethod("removeViewImmediate", viewCls).invoke(windowManager, tree[0]);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                System.out.println("[CarrotBridge] StatusDashboardOverlay show error: " + t.getMessage());
            }
        }

        private static void hideOnUiThread() {
            Object oldRoot = rootOverlayView;
            rootOverlayView = null;
            headerSysInfoTextView = null;
            leftColTextView = null;
            autoHotspotCheckTextView = null;
            rightColTextView = null;
            footerStatusTextView = null;
            isVisible = false;
            try {
                removeCallbacksFromUi(REFRESH_TASK);
                if (oldRoot != null && windowManager != null) {
                    Class<?> viewCls = Class.forName("android.view.View");
                    Class<?> vmCls = Class.forName("android.view.ViewManager");
                    try {
                        viewCls.getMethod("setVisibility", int.class).invoke(oldRoot, 8); // View.GONE
                    } catch (Throwable ignored) {
                    }
                    try {
                        vmCls.getMethod("removeView", viewCls).invoke(windowManager, oldRoot);
                    } catch (Throwable t) {
                        windowManager.getClass().getMethod("removeViewImmediate", viewCls).invoke(windowManager, oldRoot);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        private static void refreshOnUiThread() {
            if (!isVisible || rootOverlayView == null) return;
            long now = System.currentTimeMillis();
            long elapsedSec = (now - shownAtMs) / 1000L;
            if (elapsedSec >= 60L) {
                hideOnUiThread();
                return;
            }

            try {
                CarrotPacket pkt = latestSentPacket;
                NavState st = navStateRef;
                if (pkt == null && st != null) {
                    pkt = buildCarrotPacket(st);
                }

                String subProp = getSystemProperty("wifi.hotspot.subscription", "true");
                String apnProp = getSystemProperty("wifi.hotspot.apn", "Public");
                String autoProp = getSystemProperty("persist.sys.hotspot.auto", "1");
                String hotspotStr = (!"0".equals(autoProp) && "true".equals(subProp))
                        ? "활성 (Auto=ON, APN=" + apnProp + ")"
                        : "수동/비활성 (Auto=" + autoProp + ")";

                boolean commaAlive = !discoveredCarrotIp.isEmpty() && (now - discoveredCarrotTimeMs) < 30000L;
                String commaStr = commaAlive
                        ? ("연결됨 (" + discoveredCarrotIp + " | 차속: " + String.format(Locale.US, "%.1f", carrotVEgoKph) + " km/h)")
                        : (discoveredCarrotIp.isEmpty() ? "브로드캐스트 송출 중 (192.168.0.255:7706)" : "최근 IP: " + discoveredCarrotIp + " (대기 중)");

                long pktIdx = pkt != null ? pkt.carrotIndex : 0L;
                StringBuilder leftSb = new StringBuilder(512);
                leftSb.append("• 패키지 버전 : v").append(BRIDGE_VERSION).append(" (HKG_5W_CarrotNavi)\n");
                leftSb.append("• 브릿지 데몬 : 정상 가동 중 (UDP 7706 @ 5Hz, #").append(pktIdx).append(")\n");
                leftSb.append("• Wi-Fi 핫스팟 : ").append(hotspotStr).append("\n");
                leftSb.append("• 콤마(캐럿) 기기 : ").append(commaStr).append("\n");
                leftSb.append("• 패킷 타겟 IP : ").append(latestTargetsStr).append("\n");
                leftSb.append("• 자동 업데이트 : ").append(lastAutoUpdateStatus);

                StringBuilder rightSb = new StringBuilder(512);
                if (pkt != null) {
                    String roadKind = pkt.roadcate == 1 ? "고속/자동차전용(1)" : "일반도로(" + pkt.roadcate + ")";
                    rightSb.append("• 도로 제한속도 : ").append(pkt.nRoadLimitSpeed).append(" km/h  [종별: ").append(roadKind)
                            .append(" | 차속: ").append(String.format(Locale.US, "%.0f", pkt.nPosSpeed)).append(" km/h]\n");
                    rightSb.append("• 1차 SDI(카메라) : ").append(describeSdiType(pkt.nSdiType))
                            .append(" | 거리: ").append(pkt.nSdiDist).append("m | 단속: ").append(pkt.nSdiSpeedLimit).append(" km/h\n");
                    if (pkt.nSdiBlockType > 0) {
                        rightSb.append("• 구간단속/보조 : 구간단속 중 (남은거리: ").append(pkt.nSdiBlockDist)
                                .append("m, 평균: ").append(pkt.nSdiBlockAverageSpeed).append(" km/h)\n");
                    } else {
                        rightSb.append("• 보조 SDI(방지턱) : ").append(describeSdiType(pkt.nSdiPlusType))
                                .append(" | 거리: ").append(pkt.nSdiPlusDist).append("m\n");
                    }

                    boolean guiding = st != null && st.isGuidanceActive();
                    rightSb.append("• 순정 내비 경로 : ").append(guiding ? "경로 안내 중 (Active)" : "미안내 (카메라/제한속도만 전송 중)").append("\n");
                    rightSb.append("• 1차 TBT 회전 : ").append(describeTurnType(pkt.nTBTTurnType))
                            .append(" (코드:").append(pkt.nTBTTurnType).append(") | 남은거리: ").append(pkt.nTBTDist).append("m\n");
                    String mainTxt = (pkt.szTBTMainText == null || pkt.szTBTMainText.isEmpty()) ? "(교차로명 없음)" : pkt.szTBTMainText;
                    rightSb.append("• 교차로/안내명 : ").append(mainTxt).append("\n");
                    String remKm = String.format(Locale.US, "%.1f", pkt.nGoPosDist / 1000.0);
                    int remMin = pkt.nGoPosTime / 60;
                    rightSb.append("• 2차 TBT / 목적지 : ").append(describeTurnType(pkt.nTBTTurnTypeNext))
                            .append(" (").append(pkt.nTBTDistNext).append("m) | 잔여: ").append(remKm).append("km (").append(remMin).append("분)");
                }

                Class<?> tvCls = Class.forName("android.widget.TextView");
                Method setText = tvCls.getMethod("setText", CharSequence.class);
                if (headerSysInfoTextView != null) {
                    setText.invoke(headerSysInfoTextView, updateSystemResourceBadge(now));
                }
                if (autoHotspotCheckTextView != null) {
                    styleAutoHotspotCheckbox(autoHotspotCheckTextView, isBootAutoHotspotEnabled());
                }
                setText.invoke(leftColTextView, leftSb.toString());
                setText.invoke(rightColTextView, rightSb.toString());
                long remainClose = Math.max(1L, 60L - elapsedSec);
                setText.invoke(footerStatusTextView, actionBannerMsg + "  (" + remainClose + "초 후 자동 닫힘)");
            } catch (Throwable ignored) {
            }

            postDelayedToUi(REFRESH_TASK, 500L);
        }

        private static boolean isBootAutoHotspotEnabled() {
            try {
                File conf = new File(installDir, "config/hotspot_auto.conf");
                if (conf.isFile()) {
                    String val = readFirstLineQuick(conf);
                    if ("0".equals(val)) return false;
                    if ("1".equals(val)) return true;
                }
            } catch (Throwable ignored) {
            }
            String autoProp = getSystemProperty("persist.sys.hotspot.auto", "1");
            return !"0".equals(autoProp);
        }

        private static void styleAutoHotspotCheckbox(Object view, boolean enabled) throws Exception {
            if (enabled) {
                setTextViewStyle(view, "☑ 부팅 시 핫스팟 자동 활성화 : ON (터치하여 변경)", 14.5f, 0xFFFFFFFF, true);
                setRoundedBg(view, 0xFF065F46, 0xFF34D399, 2, 10f);
            } else {
                setTextViewStyle(view, "☐ 부팅 시 핫스팟 자동 활성화 : OFF (터치하여 변경)", 14.5f, 0xFFCBD5E1, true);
                setRoundedBg(view, 0xFF334155, 0xFF64748B, 2, 10f);
            }
        }

        private static void toggleBootAutoHotspotFromUi() {
            final boolean nextState = !isBootAutoHotspotEnabled();
            try {
                File conf = new File(installDir, "config/hotspot_auto.conf");
                writeBytesAtomic(conf, (nextState ? "1\n" : "0\n").getBytes(UTF8));
            } catch (Throwable ignored) {
            }
            actionBannerMsg = nextState
                    ? "✅ 부팅 시 핫스팟 자동 활성화가 [ON]으로 설정되었습니다."
                    : "ℹ️ 부팅 시 핫스팟 자동 활성화가 [OFF]로 해제되었습니다.";
            postToUi(REFRESH_TASK);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (nextState) {
                            execCommandCapture(new String[]{"/system/bin/setprop", "persist.sys.hotspot.auto", "1"}, 10);
                            execCommandCapture(new String[]{"/system/bin/setprop", "wifi.hotspot.subscription", "true"}, 10);
                            execCommandCapture(new String[]{"/system/bin/setprop", "wifi.hotspot.apn", "Public"}, 10);
                            execCommandCapture(new String[]{"/system/bin/service", "call", "wifi", "29", "i32", "0", "i32", "1"}, 10);
                        } else {
                            execCommandCapture(new String[]{"/system/bin/setprop", "persist.sys.hotspot.auto", "0"}, 10);
                            execCommandCapture(new String[]{"/system/bin/setprop", "wifi.hotspot.subscription", "false"}, 10);
                        }
                    } catch (Throwable ignored) {
                    }
                    postToUi(REFRESH_TASK);
                }
            }, "ToggleBootAutoHotspot").start();
        }

        private static String updateSystemResourceBadge(long nowMs) {
            if (nowMs - lastSysBadgeMs < 950L && cachedSysBadgeText != null) {
                return cachedSysBadgeText;
            }
            lastSysBadgeMs = nowMs;
            try {
                int cpuPct = sampleCpuUsagePercent();
                Double tempC = readCpuTemperatureCelsius();
                String cpuPart;
                if (tempC != null) {
                    cpuPart = String.format(Locale.US, "🌡️ CPU: %.0f°C (%d%%)", tempC, cpuPct);
                } else {
                    int freqMhz = readCpuFreqMhz();
                    cpuPart = freqMhz > 0
                            ? ("🌡️ CPU: " + cpuPct + "% (" + freqMhz + "MHz)")
                            : ("🌡️ CPU: " + cpuPct + "%");
                }
                String ramPart = readRamUsageSummary();
                cachedSysBadgeText = "  " + cpuPart + "   |   " + ramPart + "  ";
            } catch (Throwable ignored) {
            }
            return cachedSysBadgeText;
        }

        private static Double readCpuTemperatureCelsius() {
            try {
                if (!thermalSearchDone) {
                    thermalSearchDone = true;
                    String[] candidates = new String[]{
                            "/sys/class/thermal/thermal_zone0/temp",
                            "/sys/class/thermal/thermal_zone1/temp",
                            "/sys/devices/virtual/thermal/thermal_zone0/temp",
                            "/sys/class/hwmon/hwmon0/temp1_input",
                            "/sys/class/hwmon/hwmon0/device/temp1_input",
                            "/sys/class/hwmon/hwmon1/temp1_input",
                            "/sys/devices/platform/tcc-tsensor/temp",
                            "/sys/bus/platform/devices/tcc-tsensor/temp",
                            "/proc/tcc_tsensor"
                    };
                    for (String p : candidates) {
                        File f = new File(p);
                        if (f.isFile() && f.canRead()) {
                            cachedThermalFile = f;
                            break;
                        }
                    }
                    if (cachedThermalFile == null) {
                        String[] dirs = new String[]{"/sys/class/thermal", "/sys/class/hwmon"};
                        for (String d : dirs) {
                            File dir = new File(d);
                            File[] children = dir.listFiles();
                            if (children == null) continue;
                            for (File sub : children) {
                                for (String leaf : new String[]{"temp", "temp1_input", "device/temp1_input"}) {
                                    File cand = new File(sub, leaf);
                                    if (cand.isFile() && cand.canRead()) {
                                        cachedThermalFile = cand;
                                        break;
                                    }
                                }
                                if (cachedThermalFile != null) break;
                            }
                            if (cachedThermalFile != null) break;
                        }
                    }
                }
                if (cachedThermalFile != null) {
                    String raw = readFirstLineQuick(cachedThermalFile);
                    double v = parseLeadingDouble(raw, -999.0);
                    if (v > -900.0) {
                        if (v >= 1000.0) v = v / 1000.0;
                        else if (v >= 150.0) v = v / 10.0;
                        if (v >= -30.0 && v <= 135.0) {
                            return Double.valueOf(v);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        private static int readCpuFreqMhz() {
            try {
                File f = new File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq");
                if (f.isFile() && f.canRead()) {
                    int khz = parseIntSafe(readFirstLineQuick(f), 0);
                    if (khz > 1000) return khz / 1000;
                }
            } catch (Throwable ignored) {
            }
            return 0;
        }

        private static int sampleCpuUsagePercent() {
            try {
                String line = readFirstLineQuick(new File("/proc/stat"));
                if (line != null && line.startsWith("cpu")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 5) {
                        long user = Long.parseLong(parts[1]);
                        long nice = Long.parseLong(parts[2]);
                        long sys = Long.parseLong(parts[3]);
                        long idle = Long.parseLong(parts[4]);
                        long iowait = parts.length >= 6 ? Long.parseLong(parts[5]) : 0L;
                        long irq = parts.length >= 7 ? Long.parseLong(parts[6]) : 0L;
                        long softirq = parts.length >= 8 ? Long.parseLong(parts[7]) : 0L;
                        long idleAll = idle + iowait;
                        long total = user + nice + sys + idleAll + irq + softirq;
                        long dTotal = total - lastCpuTotalTicks;
                        long dIdle = idleAll - lastCpuIdleTicks;
                        lastCpuTotalTicks = total;
                        lastCpuIdleTicks = idleAll;
                        if (dTotal > 0L) {
                            int pct = (int) Math.round(((dTotal - dIdle) * 100.0) / dTotal);
                            lastCpuLoadPct = Math.max(0, Math.min(100, pct));
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return lastCpuLoadPct;
        }

        private static String readRamUsageSummary() {
            BufferedReader br = null;
            try {
                File memFile = new File("/proc/meminfo");
                if (!memFile.exists()) return "💾 RAM: --";
                br = new BufferedReader(new InputStreamReader(new FileInputStream(memFile), UTF8));
                long totalKb = 0L;
                long freeKb = 0L;
                long buffersKb = 0L;
                long cachedKb = 0L;
                String line;
                int found = 0;
                while ((line = br.readLine()) != null && found < 4) {
                    if (line.startsWith("MemTotal:")) {
                        totalKb = (long) parseLeadingDouble(line.substring(9), 0.0);
                        found++;
                    } else if (line.startsWith("MemFree:")) {
                        freeKb = (long) parseLeadingDouble(line.substring(8), 0.0);
                        found++;
                    } else if (line.startsWith("Buffers:")) {
                        buffersKb = (long) parseLeadingDouble(line.substring(8), 0.0);
                        found++;
                    } else if (line.startsWith("Cached:")) {
                        cachedKb = (long) parseLeadingDouble(line.substring(7), 0.0);
                        found++;
                    }
                }
                if (totalKb > 0L) {
                    long usedKb = Math.max(0L, totalKb - freeKb - buffersKb - cachedKb);
                    long totalMb = totalKb / 1024L;
                    long usedMb = usedKb / 1024L;
                    int pct = (int) Math.round((usedKb * 100.0) / totalKb);
                    return "💾 RAM: " + usedMb + "/" + totalMb + "MB (" + pct + "%)";
                }
            } catch (Throwable ignored) {
            } finally {
                if (br != null) try { br.close(); } catch (Exception ignored) {}
            }
            return "💾 RAM: --";
        }

        private static String readFirstLineQuick(File f) {
            BufferedReader br = null;
            try {
                br = new BufferedReader(new InputStreamReader(new FileInputStream(f), UTF8));
                String s = br.readLine();
                return s != null ? s.trim() : "";
            } catch (Throwable e) {
                return "";
            } finally {
                if (br != null) try { br.close(); } catch (Exception ignored) {}
            }
        }

        private static void triggerManualUpdateFromUi() {
            actionBannerMsg = "⏳ GitHub 최신 버전(GRT47/HKG_5W_CarrotNavi) 확인 중...";
            postToUi(REFRESH_TASK);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String nowStr = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
                    int rc = runAutoUpdate("GRT47", "HKG_5W_CarrotNavi", "main", installDir, false);
                    if (rc == 0) {
                        lastAutoUpdateStatus = "최신 버전 유지 중 (v" + BRIDGE_VERSION + ", 확인: " + nowStr + ")";
                        actionBannerMsg = "✅ 현재 최신 버전(v" + BRIDGE_VERSION + ")입니다. (" + nowStr + ")";
                        postToUi(REFRESH_TASK);
                    } else if (rc == 10) {
                        lastAutoUpdateStatus = "새 버전 적용 완료 -> 데몬 재시작 중 (" + nowStr + ")";
                        actionBannerMsg = "🎉 업데이트 적용 완료! 브릿지 데몬을 재시작합니다...";
                        postToUi(REFRESH_TASK);
                        try { Thread.sleep(1500L); } catch (InterruptedException ignored) {}
                        System.exit(0); // Watchdog in bridge-daemon.sh immediately restarts updated JAR
                    } else {
                        lastAutoUpdateStatus = "업데이트 확인 실패 (LTE 연결 확인 필요, " + nowStr + ")";
                        actionBannerMsg = "⚠️ GitHub 연결 실패 (LTE/데이터 연결 상태를 확인해주세요)";
                        postToUi(REFRESH_TASK);
                    }
                }
            }, "ManualUpdateTrigger").start();
        }

        private static void triggerManualDumpFromUi() {
            actionBannerMsg = "⏳ USB 및 로컬 경로로 TBT 정밀 덤프 저장 중...";
            postToUi(REFRESH_TASK);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        enableMnsoftClusterLogging();
                        File script = new File(installDir, "scripts/tbt-dump.sh");
                        if (script.exists()) {
                            execCommandCapture(new String[]{"/system/bin/sh", script.getAbsolutePath()}, 200);
                        }
                        String nowStr = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
                        File usbDir = new File("/storage/usb0/HKG_TBT_Dump");
                        if (usbDir.exists()) {
                            actionBannerMsg = "✅ USB(/storage/usb0/HKG_TBT_Dump) 및 로컬 덤프 저장 완료! (" + nowStr + ")";
                        } else {
                            actionBannerMsg = "✅ 로컬(/data/local/tmp/hkg_tbt_dump) 덤프 완료! (USB 꽂으면 자동 복사됨)";
                        }
                    } catch (Throwable t) {
                        actionBannerMsg = "⚠️ 덤프 실행 중 오류: " + t.getMessage();
                    }
                    postToUi(REFRESH_TASK);
                }
            }, "ManualDumpTrigger").start();
        }

        private static void openHotspotSettingsFromUi() {
            actionBannerMsg = "⏳ 순정 Wi-Fi 핫스팟 설정 화면을 여는 중...";
            hideOnUiThread();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        execCommandCapture(new String[]{"/system/bin/setprop", "wifi.hotspot.subscription", "true"}, 10);
                        execCommandCapture(new String[]{"/system/bin/setprop", "wifi.hotspot.apn", "Public"}, 10);
                        String out = execCommandCapture(new String[]{
                                "/system/bin/am", "start",
                                "-a", "android.intent.action.MAIN",
                                "-c", "android.intent.category.LAUNCHER",
                                "-f", "0x10010000",
                                "-n", "com.hkmc.wifisettings/com.hkmc.wifisettings.WifiHotspot"
                        }, 20);
                        if (out.contains("Error") || out.contains("Exception") || out.contains("does not exist")) {
                            execCommandCapture(new String[]{
                                    "/system/bin/am", "start",
                                    "-f", "0x10000000",
                                    "-n", "com.hkmc.system.app.engineering/.module.WifiInfo"
                            }, 20);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }, "HotspotSettingsLauncher").start();
        }

        private static void openEngineeringWifiFromUi() {
            actionBannerMsg = "⏳ 엔지니어링 WiFi 설정 화면을 여는 중...";
            hideOnUiThread();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        execCommandCapture(new String[]{
                                "/system/bin/am", "start",
                                "-f", "0x10000000",
                                "-n", "com.hkmc.system.app.engineering/.module.WifiInfo"
                        }, 20);
                    } catch (Throwable ignored) {
                    }
                }
            }, "EngineeringWifiLauncher").start();
        }

        private static void triggerHotspotCheckFromUi() {
            actionBannerMsg = "⏳ Wi-Fi 핫스팟(Public APN) 상태 점검 및 활성화 중...";
            postToUi(REFRESH_TASK);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        execCommandCapture(new String[]{"/system/bin/setprop", "wifi.hotspot.subscription", "true"}, 10);
                        execCommandCapture(new String[]{"/system/bin/setprop", "wifi.hotspot.apn", "Public"}, 10);
                        execCommandCapture(new String[]{"/system/bin/service", "call", "wifi", "29", "i32", "0", "i32", "1"}, 10);
                        actionBannerMsg = "✅ Wi-Fi 핫스팟(APN=Public) 활성화 명령 전송 완료!";
                    } catch (Throwable ignored) {
                    }
                    postToUi(REFRESH_TASK);
                }
            }, "ManualHotspotTrigger").start();
        }

        private static void disableViewEffects(Object view, boolean hideAccessibility) {
            try {
                Class<?> viewCls = Class.forName("android.view.View");
                viewCls.getMethod("setSoundEffectsEnabled", boolean.class).invoke(view, false);
                viewCls.getMethod("setHapticFeedbackEnabled", boolean.class).invoke(view, false);
                if (hideAccessibility) {
                    // 4 = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    viewCls.getMethod("setImportantForAccessibility", int.class).invoke(view, 4);
                }
            } catch (Throwable ignored) {
            }
        }

        private static void setTextViewStyle(Object tv, String text, float sizeSp, int color, boolean bold) throws Exception {
            Class<?> tvCls = Class.forName("android.widget.TextView");
            tvCls.getMethod("setText", CharSequence.class).invoke(tv, text);
            tvCls.getMethod("setTextSize", int.class, float.class).invoke(tv, 2, sizeSp); // 2 = COMPLEX_UNIT_SP
            tvCls.getMethod("setTextColor", int.class).invoke(tv, color);
            if (bold) {
                Class<?> tfCls = Class.forName("android.graphics.Typeface");
                Object defaultBold = tfCls.getField("DEFAULT_BOLD").get(null);
                tvCls.getMethod("setTypeface", tfCls, int.class).invoke(tv, defaultBold, 1);
            }
        }

        private static void setRoundedBg(Object view, int fillColor, int strokeColor, int strokeWidthPx, float radiusPx) throws Exception {
            Class<?> gdCls = Class.forName("android.graphics.drawable.GradientDrawable");
            Class<?> drawableCls = Class.forName("android.graphics.drawable.Drawable");
            Class<?> viewCls = Class.forName("android.view.View");
            Object gd = gdCls.getConstructor().newInstance();
            gdCls.getMethod("setColor", int.class).invoke(gd, fillColor);
            gdCls.getMethod("setCornerRadius", float.class).invoke(gd, radiusPx);
            gdCls.getMethod("setStroke", int.class, int.class).invoke(gd, strokeWidthPx, strokeColor);
            viewCls.getMethod("setBackgroundDrawable", drawableCls).invoke(view, gd);
        }

        private static void setMargins(Object marginLp, int l, int t, int r, int b) throws Exception {
            Class<?> mlpCls = Class.forName("android.view.ViewGroup$MarginLayoutParams");
            mlpCls.getMethod("setMargins", int.class, int.class, int.class, int.class).invoke(marginLp, l, t, r, b);
        }

        /**
         * Uses View.OnTouchListener returning Boolean.TRUE so View.dispatchTouchEvent() returns immediately
         * without ever entering View.onTouchEvent() -> View.performClick() -> View.playSoundEffect(0) ->
         * AudioManager.querySoundEffectsEnabled() -> Settings.System.getInt() -> ActivityManagerService.getContentProvider("settings"),
         * which throws SecurityException("Unable to find app for caller ...") in a standalone app_process.
         */
        private static void attachClickListener(Object view, final Runnable action) throws Exception {
            disableViewEffects(view, false);
            Class<?> touchListenerCls = Class.forName("android.view.View$OnTouchListener");
            Class<?> viewCls = Class.forName("android.view.View");
            viewCls.getMethod("setClickable", boolean.class).invoke(view, true);
            Object proxy = Proxy.newProxyInstance(
                    touchListenerCls.getClassLoader(),
                    new Class<?>[]{touchListenerCls},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxyObj, Method method, Object[] args) {
                            String mName = method.getName();
                            if ("equals".equals(mName)) {
                                return Boolean.valueOf(args != null && args.length == 1 && proxyObj == args[0]);
                            }
                            if ("hashCode".equals(mName)) {
                                return Integer.valueOf(System.identityHashCode(proxyObj));
                            }
                            if ("toString".equals(mName)) {
                                return "CarrotTouchListener";
                            }
                            if ("onTouch".equals(mName) && args != null && args.length >= 2 && args[1] != null) {
                                try {
                                    Object motionEvent = args[1];
                                    int act = ((Integer) motionEvent.getClass().getMethod("getAction").invoke(motionEvent)).intValue() & 0xFF;
                                    if (act == 1) { // MotionEvent.ACTION_UP = 1
                                        shownAtMs = System.currentTimeMillis();
                                        action.run();
                                    }
                                } catch (Throwable t) {
                                    System.out.println("[CarrotBridge] Button touch error: " + t);
                                }
                                return Boolean.TRUE;
                            }
                            return Boolean.TRUE;
                        }
                    }
            );
            viewCls.getMethod("setOnTouchListener", touchListenerCls).invoke(view, proxy);
        }
    }

    private static String getSystemProperty(String key, String def) {
        try {
            Class<?> spCls = Class.forName("android.os.SystemProperties");
            String val = (String) spCls.getMethod("get", String.class, String.class).invoke(null, key, def);
            return (val == null || val.isEmpty()) ? def : val;
        } catch (Throwable ignored) {
            return def;
        }
    }

    public static String describeSdiType(int sdiType) {
        switch (sdiType) {
            case -1: return "없음";
            case 0: return "신호+과속 카메라(0)";
            case 1: return "고정식 과속카메라(1)";
            case 2: return "가변형 단속카메라(2)";
            case 3: return "신호+과속 카메라(3)";
            case 4: return "구간단속 카메라(4)";
            case 7: return "이동식 단속카메라(7)";
            case 8: return "고정식(박스) 카메라(8)";
            case 9: return "버스전용차로 단속(9)";
            case 15: return "혼잡통행료/톨게이트(15)";
            case 20: return "어린이보호구역/스쿨존(20)";
            case 21: return "급커브 주의구간(21)";
            case 22: return "과속방지턱(22)";
            case 66: return "합류도로 주의(66)";
            case 75: return "후면 과속카메라(75)";
            case 76: return "후면 신호+과속카메라(76)";
            default: return "기타안전구간(" + sdiType + ")";
        }
    }

    public static String describeTurnType(int turnType) {
        switch (turnType) {
            case 0: return "안내없음";
            case 11: return "직진";
            case 12: return "좌회전";
            case 13: return "우회전";
            case 14: return "유턴(U-Turn)";
            case 16: return "8시방향 급좌회전";
            case 17: return "10시방향 좌측방면";
            case 18: return "2시방향 우측방면";
            case 19: return "4시방향 급우회전";
            case 43: return "우측 고가/지하차도 진입";
            case 44: return "좌측 고가/지하차도 진입";
            case 51: return "전방 주의/안내";
            case 75: return "고속도로 첫 출구";
            case 101: return "우측 출구(IC/JC)";
            case 102: return "좌측 출구(IC/JC)";
            case 104: return "고속도로 분기(우)";
            case 105: return "고속도로 분기(좌)";
            case 131: return "회전교차로(로터리)";
            case 153: return "톨게이트";
            case 201: return "목적지 도착";
            default: return "회전(" + turnType + ")";
        }
    }

    // =========================================================================
    // SELF-TEST SUITE (Runs on Host JVM or Target Android)
    // =========================================================================

    public static int runSelfTest() throws Exception {
        NavState st = new NavState();

        // 1. Test School Zone + Fixed Camera + Section Control + TBT Right Turn
        parseLogcatLine("D/ BrdCast( 1168): action:Intent { act=com.mnsoft.navi.GuidStatus flg=0x10 } [BrdCast]Bundle[{bGuid=true}]", st, null);
        parseLogcatLine("D/ClusterService(  612): NaviCluster.updateTBTTrapInfo() fixedSpeed: 60 mobileSpeed: 0 overSpeedAlarm: 1 redlineSpeed: 0 nonSpeed: 0 warningZone: 2 curveWarning: 0 mergeWarning: 0 tollGateCurrency: 0 tollGate: 0 displayTG: 0 barGraphLevel: 62 speedLimit: 60 busSpeed: 0 speedLimitType: 1 speedLimitUnit: 1 speedInfoType: 1 warningZone2: 0 warningZone3: 0 warningZone4: 0 displayCountry: 1cameraSafetyAlert1", st, null);
        parseLogcatLine("D/ClusterService(  612): NaviCluster.updateNaviWarnDist() overDec: 320 underDec: 0 scale: 0", st, null);
        parseLogcatLine("D/ClusterService(  612): NaviCluster.updateSCC_HU_Navi_P() msgGroupNum1: 1 setDestStatus: 1 offRouteStatus: 0 nextTurnType1: 13 naviValidity: 1 nextTurnDist1: 250 curLinkCntLane: 6 curRoadClass: 4 nextTurnType2: 18 nextTurnDist2: 120 rangeCamInfo: 0 rangeCamDist: 0 rangeCamSpdLimit: 0 rangeCamAvgMovingSpd: 0", st, null);
        parseLogcatLine("D/ClusterService(  612): NaviCluster.updateTBTSubInfo2C() distToPoint1_unit: 0 distToPoint1_F: 0 distToPoint1_I: 120 distToPoint2_unit: 15 distToPoint2_F: 0 distToPoint2_I: 0", st, null);

        // Simulate com.mnsoft.navi's byte-swapped UTF-16LE string for "강남역사거리"
        String origCross = "강남역사거리";
        String mnsoftSwapped = new String(origCross.getBytes(UTF16), UTF16LE);
        parseLogcatLine("D/ClusterService(  612): updateTPCrossroadName()::crossroadname is " + mnsoftSwapped, st, null);

        String sampleDump = "Navigation Cluster:\n"
                + "  TBT Info:\n"
                + "    Display Type  = TURN\n"
                + "    Direction     = 90.0\n"
                + "    Distance      = 250.0 METER\n"
                + "    BarGraph Level= 65\n"
                + "  TBT SubInfo:\n"
                + "    distance      = 12.4 KILO-METER | minutes = 24\n";
        parseDumpsysCluster(sampleDump, st);

        CarrotPacket pkt = buildCarrotPacket(st);
        if (pkt.nSdiType != 1 || pkt.nSdiSpeedLimit != 60 || pkt.nSdiDist != 320) {
            throw new AssertionError("Fixed camera SDI mismatch: " + pkt.toJson());
        }
        if (pkt.nSdiPlusType != 20 || pkt.nSdiPlusSpeedLimit != 30) {
            throw new AssertionError("School zone secondary SDI mismatch: " + pkt.toJson());
        }
        if (pkt.nTBTTurnType != 13 || pkt.nTBTDist != 250 || !origCross.equals(pkt.szTBTMainText)) {
            throw new AssertionError("TBT 1st turn mismatch: " + pkt.toJson());
        }
        if (pkt.nTBTTurnTypeNext != 102 || pkt.nTBTDistNext != 120) {
            throw new AssertionError("TBT 2nd turn mismatch: " + pkt.toJson());
        }
        if (pkt.nGoPosDist != 12400 || pkt.nGoPosTime != 1440) {
            throw new AssertionError("Destination remain mismatch: " + pkt.toJson());
        }

        // 1b. Test all-1s bitfield invalid sentinels in updateSCC_HU_Navi_P and hex DisplayType=0xd + UTF-16LI crossroad name
        NavState stReal = new NavState();
        parseLogcatLine("D/ClusterService(  594): NaviCluster.updateSCC_HU_Navi_P() msgGroupNum1: 2 setDestStatus: 1 offRouteStatus: 0 nextTurnType1: 12 naviValidity: 0 nextTurnDist1: 120 curLinkCntLane: 4 curRoadClass: 4 nextCamType: 15 nextCamDist: 1023 nextCamSpdLimit: 15 rangeCamInfo: 7 rangeCamDist: 65535 rangeCamSpdLimit: 15 rangeCamAvgMovingSpd: 0", stReal, null);
        parseLogcatLine("I/Java_Layer( 1235): [Cluster] [IL TBT Info - 0x4E] DisplayType = 0xd", stReal, null);
        parseLogcatLine("I/Java_Layer( 1235): [Cluster] [IL TBT Info - 0x4E] Direction = 270.0", stReal, null);
        parseLogcatLine("I/Java_Layer( 1235): [Cluster] sendCluster_H_U_Navi_T_TurnName() UTF-16LI = 평촌지북로", stReal, null);
        parseLogcatLine("D/NavigationCluster( 1235): updateTBTSubInfo() distance: 2.7 unit: 1 arrivalTimeType: 2 hour: 5 min: 45", stReal, null);
        parseLogcatLine("D/ClusterService(  594): NaviCluster.updateRemainDis() remained_distance: 110 under_decimal: 0 scale_unit: 0 bar_graph_level: 85", stReal, null);
        CarrotPacket realPkt = buildCarrotPacket(stReal);
        if (realPkt.nSdiType != -1 || realPkt.nSdiSpeedLimit != 0) {
            throw new AssertionError("All-1s SCC sentinel should not produce phantom camera: " + realPkt.toJson());
        }
        if (realPkt.nTBTTurnType != 12 || realPkt.nTBTDist != 110 || !"평촌지북로".equals(realPkt.szTBTMainText) || realPkt.nGoPosDist != 2700) {
            throw new AssertionError("Real-vehicle TBT hex DisplayType/UTF-16LI/remainDis mismatch: " + realPkt.toJson());
        }

        // 1c. Test Gen5W real-vehicle speed camera encoding (fixedSpeed=0, speedInfoType=1/5, cameraSafetyAlert1/3, 4-line updateNaviWarnDist)
        parseLogcatLine("D/ClusterService(  554): NaviCluster.updateTBTTrapInfo() fixedSpeed: 0 mobileSpeed: 0 overSpeedAlarm: 0 redlineSpeed: 0 nonSpeed: 0 warningZone: 0 curveWarning: 0 mergeWarning: 0 tollGateCurrency: 0 tollGate: 0 displayTG: 0 barGraphLevel: 0 speedLimit: 50 busSpeed: 0 speedLimitType: 0 speedLimitUnit: 1 speedInfoType: 1 warningZone2: 0 warningZone3: 0 warningZone4: 0 displayCountry: 1cameraSafetyAlert1", stReal, null);
        parseLogcatLine("D/ClusterService(  554): NaviCluster.updateNaviWarnDist() warnDistOverDec: 290 warnDistUnderDec: 0 warnDistScale: 0", stReal, null);
        parseLogcatLine("D/ClusterService(  554): NaviCluster.updateNaviWarnDist() unit1: 15 unit2: 15 unit3: 15", stReal, null);
        parseLogcatLine("D/ClusterService(  554): NaviCluster.updateNaviWarnDist() popupInfo: 0 popupDistOverDec: 65535 popupDistUnderDec: 15", stReal, null);
        parseLogcatLine("D/ClusterService(  554): NaviCluster.updateNaviWarnDist() popupDistScale: 15", stReal, null);
        // Subsequent updateTBTTrapInfo must not wipe camWarnDistM while speedInfoType/cameraSafetyAlert is active
        parseLogcatLine("D/ClusterService(  554): NaviCluster.updateTBTTrapInfo() fixedSpeed: 0 mobileSpeed: 0 overSpeedAlarm: 0 redlineSpeed: 0 nonSpeed: 0 warningZone: 0 curveWarning: 0 mergeWarning: 0 tollGateCurrency: 0 tollGate: 0 displayTG: 0 barGraphLevel: 0 speedLimit: 50 busSpeed: 0 speedLimitType: 0 speedLimitUnit: 1 speedInfoType: 1 warningZone2: 0 warningZone3: 0 warningZone4: 0 displayCountry: 1cameraSafetyAlert1", stReal, null);
        CarrotPacket cam1Pkt = buildCarrotPacket(stReal);
        if (cam1Pkt.nSdiType != 1 || cam1Pkt.nSdiSpeedLimit != 50 || cam1Pkt.nSdiDist != 290) {
            throw new AssertionError("Gen5W 50km/h fixed camera mismatch: " + cam1Pkt.toJson());
        }

        // Gen5W 70km/h Signal + Speed camera (speedInfoType=5, cameraSafetyAlert3)
        parseLogcatLine("D/ClusterService(  555): NaviCluster.updateTBTTrapInfo() fixedSpeed: 0 mobileSpeed: 0 overSpeedAlarm: 1 redlineSpeed: 0 nonSpeed: 0 warningZone: 0 curveWarning: 0 mergeWarning: 0 tollGateCurrency: 0 tollGate: 0 displayTG: 0 barGraphLevel: 0 speedLimit: 70 busSpeed: 0 speedLimitType: 0 speedLimitUnit: 1 speedInfoType: 5 warningZone2: 0 warningZone3: 0 warningZone4: 0 displayCountry: 1cameraSafetyAlert3", stReal, null);
        parseLogcatLine("D/ClusterService(  555): NaviCluster.updateNaviWarnDist() warnDistOverDec: 160 warnDistUnderDec: 0 warnDistScale: 0", stReal, null);
        parseLogcatLine("D/ClusterService(  555): NaviCluster.updateNaviWarnDist() unit1: 15 unit2: 15 unit3: 15", stReal, null);
        CarrotPacket cam4Pkt = buildCarrotPacket(stReal);
        if (cam4Pkt.nSdiType != 0 || cam4Pkt.nSdiSpeedLimit != 70 || cam4Pkt.nSdiDist != 160) {
            throw new AssertionError("Gen5W 70km/h signal+speed camera mismatch: " + cam4Pkt.toJson());
        }

        // Passing the camera (speedInfoType=0, cameraSafetyAlert0, warnDistOverDec=65535)
        parseLogcatLine("D/ClusterService(  555): NaviCluster.updateTBTTrapInfo() fixedSpeed: 0 mobileSpeed: 0 overSpeedAlarm: 3 redlineSpeed: 0 nonSpeed: 0 warningZone: 0 curveWarning: 0 mergeWarning: 0 tollGateCurrency: 0 tollGate: 0 displayTG: 0 barGraphLevel: 0 speedLimit: 0 busSpeed: 0 speedLimitType: 3 speedLimitUnit: 3 speedInfoType: 0 warningZone2: 0 warningZone3: 0 warningZone4: 0 displayCountry: 1cameraSafetyAlert0", stReal, null);
        parseLogcatLine("D/ClusterService(  555): NaviCluster.updateNaviWarnDist() warnDistOverDec: 65535 warnDistUnderDec: 15 warnDistScale: 15", stReal, null);
        CarrotPacket camClearPkt = buildCarrotPacket(stReal);
        if (camClearPkt.nSdiType != -1 || camClearPkt.nSdiSpeedLimit != 0 || camClearPkt.nSdiDist != -1) {
            throw new AssertionError("Gen5W camera clear mismatch: " + camClearPkt.toJson());
        }

        // 2. Test Section Control + Average Speed
        parseLogcatLine("D/ClusterService(  612): NaviCluster.updateSCC_HU_Navi_P() msgGroupNum1: 1 setDestStatus: 1 rangeCamInfo: 1 rangeCamDist: 6840 rangeCamSpdLimit: 10 rangeCamAvgMovingSpd: 94", st, null);
        CarrotPacket secPkt = buildCarrotPacket(st);
        if (secPkt.nSdiType != 4 || secPkt.nSdiBlockType != 2 || secPkt.nSdiBlockDist != 6840
                || secPkt.nSdiBlockSpeed != 100 || secPkt.nSdiBlockAverageSpeed != 94) {
            throw new AssertionError("Section control SDI mismatch: " + secPkt.toJson());
        }

        // 3. Test DNS query builder
        byte[] dnsPkt = buildDnsQueryPacket("raw.githubusercontent.com");
        if (dnsPkt.length < 30) {
            throw new AssertionError("DNS query packet too short");
        }

        // 4. Test MAP button 3-press detector with realistic dual-line ivi_mode_broadcast & ModeKeyDispatcher events
        long t0 = 100000L;
        if (checkMapShortcutLine("I/ivi_mode_broadcast(  524): [1004,KEYCODE_SHORTCUT_MAP]", t0)) {
            throw new AssertionError("Should not trigger on 1st press");
        }
        if (checkMapShortcutLine("I/ivi_mode_broadcast(  524): [com.hkmc.intent.action.MAP_SHORTCUT,2,3]", t0 + 50L)) {
            throw new AssertionError("Should debounce 2nd line of 1st press");
        }
        if (checkMapShortcutLine("V/ModeKeyDispatcher(  524): KeyEvent { action=ACTION_UP, keyCode=KEYCODE_SHORTCUT_MAP }", t0 + 210L)) {
            throw new AssertionError("Should ignore ACTION_UP line");
        }
        if (checkMapShortcutLine("I/ivi_mode_broadcast(  524): [1004,KEYCODE_SHORTCUT_MAP]", t0 + 380L)) {
            throw new AssertionError("Should not trigger on 2nd press");
        }
        if (checkMapShortcutLine("I/ivi_mode_broadcast(  524): [com.hkmc.intent.action.MAP_SHORTCUT,2,3]", t0 + 430L)) {
            throw new AssertionError("Should debounce 2nd line of 2nd press");
        }
        if (!checkMapShortcutLine("I/ivi_mode_broadcast(  524): [1004,KEYCODE_SHORTCUT_MAP]", t0 + 760L)) {
            throw new AssertionError("Should trigger on 3rd MAP press!");
        }

        System.out.println("SELF-TEST PASSED! Sample 7706 JSON:\n" + pkt.toJson());
        return 0;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "daemon".equals(args[0])) {
            String installDir = args.length >= 2 ? args[1] : "/data/nemesis/apps/carrotnavi.bridge";
            runDaemon(installDir);
        } else if ("update".equals(args[0])) {
            String owner = args.length >= 2 ? args[1] : "GRT47";
            String repo = args.length >= 3 ? args[2] : "HKG_5W_CarrotNavi";
            String branch = args.length >= 4 ? args[3] : "main";
            String installDir = args.length >= 5 ? args[4] : "/data/nemesis/apps/carrotnavi.bridge";
            boolean force = args.length >= 6 && "--force".equals(args[5]);
            int code = runAutoUpdate(owner, repo, branch, installDir, force);
            System.exit(code);
        } else if ("show-ui".equals(args[0])) {
            writeBytesAtomic(new File("/data/local/tmp/carrotbridge_show_ui"), "1".getBytes(UTF8));
        } else if ("test-self".equals(args[0])) {
            System.exit(runSelfTest());
        } else {
            System.err.println("Usage: CarrotBridgeMain [daemon|update|show-ui|test-self]");
            System.exit(2);
        }
    }
}

