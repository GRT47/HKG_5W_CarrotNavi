package com.hkg.carrotbridge.panel;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Right-hand split-screen AppWidget for Open-HMG/HMG-API (slot 22).
 * Renders the "연결" Connection Card (콤마 기기 / 차량 GPS / 무선) matching the
 * CarrotBridge full-screen dashboard and opens the dashboard overlay when tapped.
 */
public class CarrotPanelWidget extends AppWidgetProvider {

    public static final String ACTION_UPDATE = "com.hkg.carrotbridge.panel.ACTION_UPDATE";
    public static final String ACTION_OPEN_DASHBOARD = "com.hkg.carrotbridge.panel.ACTION_OPEN_DASHBOARD";

    static final String STATUS_PATH_PRIMARY = "/dev/hkg_tmp/carrotbridge_status.json";
    static final String STATUS_PATH_FALLBACK = "/data/local/tmp/carrotbridge_status.json";
    static final String SHOW_UI_TRIGGER_PATH = "/dev/hkg_tmp/carrotbridge_show_ui";
    private static final long STALE_TIMEOUT_MS = 30000L;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    // Same palette tokens as CarrotBridgeMain.StatusDashboardOverlay
    static final int TONE_OK = 0;
    static final int TONE_INFO = 1;
    static final int TONE_WARN = 2;
    static final int TONE_OFF = 3;

    private static final int C_TEXT = 0xFFE6EDF7;
    private static final int C_MUTED = 0xFF94A3B8;
    private static final int C_FAINT = 0xFF64748B;
    private static final int C_TRACK = 0xFF1F2A40;
    private static final int C_OK = 0xFF34D399;
    private static final int C_INFO = 0xFF38BDF8;
    private static final int C_WARN = 0xFFFBBF24;

    private static int toneColor(int tone) {
        if (tone == TONE_OK) return C_OK;
        if (tone == TONE_INFO) return C_INFO;
        if (tone == TONE_WARN) return C_WARN;
        return C_FAINT;
    }

    public static final class PanelState {
        public String bridgeVersion = "";
        public int commaTone = TONE_WARN;
        public String commaValue = "대기 중";
        public String commaSub = "브릿지 서비스 연결 대기 중";
        public int gpsTone = TONE_WARN;
        public String gpsValue = "대기 중";
        public String gpsSub = "위성 신호를 기다리는 중";
        public int gpsMeterPct = 0;
        public int radioTone = TONE_OFF;
        public String radioIcon = "hotspot";
        public String radioValue = "확인 중";
        public String radioSub = "-";
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (ACTION_UPDATE.equals(action)) {
            PanelState state = fromUpdateIntent(intent);
            if (state == null) {
                state = readStatusFromDisk(System.currentTimeMillis());
            }
            renderAll(context, state);
        } else if (ACTION_OPEN_DASHBOARD.equals(action)) {
            triggerDashboardOverlay();
            renderAll(context, readStatusFromDisk(System.currentTimeMillis()));
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        if (context == null || appWidgetManager == null || appWidgetIds == null || appWidgetIds.length == 0) {
            return;
        }
        RemoteViews views = buildRemoteViews(context, readStatusFromDisk(System.currentTimeMillis()));
        appWidgetManager.updateAppWidget(appWidgetIds, views);
    }

    private static void renderAll(Context context, PanelState state) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            if (mgr == null) return;
            ComponentName cn = new ComponentName(context, CarrotPanelWidget.class);
            int[] ids = mgr.getAppWidgetIds(cn);
            if (ids != null && ids.length > 0) {
                mgr.updateAppWidget(ids, buildRemoteViews(context, state));
            } else {
                mgr.updateAppWidget(cn, buildRemoteViews(context, state));
            }
        } catch (Throwable ignored) {
        }
    }

    private static RemoteViews buildRemoteViews(Context context, PanelState s) {
        RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.carrot_panel_widget);

        String hint = (s.bridgeVersion != null && !s.bridgeVersion.isEmpty())
                ? ("v" + s.bridgeVersion + " · 탭하여 대시보드")
                : "탭하여 대시보드";
        rv.setTextViewText(R.id.panel_hint, hint);

        int commaColor = toneColor(s.commaTone);
        rv.setImageViewBitmap(R.id.comma_icon, renderIconBitmap("comma", commaColor, 48));
        rv.setTextViewText(R.id.comma_value, s.commaValue);
        rv.setTextColor(R.id.comma_value, commaColor);
        rv.setTextViewText(R.id.comma_sub, s.commaSub);

        int gpsColor = toneColor(s.gpsTone);
        rv.setImageViewBitmap(R.id.gps_icon, renderIconBitmap("satellite", gpsColor, 48));
        rv.setTextViewText(R.id.gps_value, s.gpsValue);
        rv.setTextColor(R.id.gps_value, gpsColor);
        rv.setTextViewText(R.id.gps_sub, s.gpsSub);
        rv.setImageViewBitmap(R.id.gps_meter, renderMeterBitmap(s.gpsMeterPct, gpsColor, 360, 12));

        int radioColor = toneColor(s.radioTone);
        boolean radioOn = s.radioTone != TONE_OFF;
        rv.setImageViewBitmap(R.id.radio_icon, renderIconBitmap(s.radioIcon, radioColor, 48));
        rv.setTextViewText(R.id.radio_value, s.radioValue);
        rv.setTextColor(R.id.radio_value, radioOn ? C_TEXT : C_FAINT);
        rv.setTextViewText(R.id.radio_sub, s.radioSub);

        Intent openIntent = new Intent(ACTION_OPEN_DASHBOARD);
        openIntent.setComponent(new ComponentName(context, CarrotPanelWidget.class));
        PendingIntent pi = PendingIntent.getBroadcast(
                context, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        rv.setOnClickPendingIntent(R.id.panel_card, pi);
        return rv;
    }

    private static PanelState fromUpdateIntent(Intent i) {
        if (!i.hasExtra("commaValue")) return null;
        PanelState s = new PanelState();
        s.bridgeVersion = nonNull(i.getStringExtra("bridgeVersion"), "");
        s.commaTone = i.getIntExtra("commaTone", TONE_WARN);
        s.commaValue = nonNull(i.getStringExtra("commaValue"), "대기 중");
        s.commaSub = nonNull(i.getStringExtra("commaSub"), "");
        s.gpsTone = i.getIntExtra("gpsTone", TONE_WARN);
        s.gpsValue = nonNull(i.getStringExtra("gpsValue"), "대기 중");
        s.gpsSub = nonNull(i.getStringExtra("gpsSub"), "");
        s.gpsMeterPct = clampPct(i.getIntExtra("gpsMeterPct", 0));
        s.radioTone = i.getIntExtra("radioTone", TONE_OFF);
        s.radioIcon = nonNull(i.getStringExtra("radioIcon"), "hotspot");
        s.radioValue = nonNull(i.getStringExtra("radioValue"), "확인 중");
        s.radioSub = nonNull(i.getStringExtra("radioSub"), "-");
        return s;
    }

    private static PanelState readStatusFromDisk(long nowMs) {
        try {
            File f = new File(STATUS_PATH_PRIMARY);
            if (!f.isFile()) {
                f = new File(STATUS_PATH_FALLBACK);
            }
            if (!f.isFile() || !f.canRead()) {
                return offlineState("CarrotNavi 서비스 연결 대기 중");
            }
            String text = readFileUtf8(f, 65536);
            if (text == null || text.trim().isEmpty()) {
                return offlineState("상태 파일 읽는 중");
            }
            return parseStatusJson(text, nowMs);
        } catch (Throwable t) {
            return offlineState("상태 읽기 실패");
        }
    }

    public static PanelState parseStatusJson(String json, long nowMs) {
        try {
            JSONObject obj = new JSONObject(json);
            long ts = obj.optLong("timestampMs", 0L);
            if (ts > 0L && nowMs > ts && (nowMs - ts) > STALE_TIMEOUT_MS) {
                PanelState stale = offlineState("브릿지 응답 대기 (" + ((nowMs - ts) / 1000L) + "초 전)");
                stale.bridgeVersion = obj.optString("bridgeVersion", "");
                return stale;
            }
            PanelState s = new PanelState();
            s.bridgeVersion = obj.optString("bridgeVersion", "");

            if (obj.has("commaValue") && obj.has("gpsTileValue")) {
                s.commaTone = obj.optInt("commaTone", TONE_WARN);
                s.commaValue = obj.optString("commaValue", "대기 중");
                s.commaSub = obj.optString("commaSub", "");
                s.gpsTone = obj.optInt("gpsTone", TONE_WARN);
                s.gpsValue = obj.optString("gpsTileValue", "대기 중");
                s.gpsSub = obj.optString("gpsTileSub", "");
                s.gpsMeterPct = clampPct(obj.optInt("gpsMeterPct", 0));
                s.radioTone = obj.optInt("radioTone", TONE_OFF);
                s.radioIcon = obj.optString("radioIcon", "hotspot");
                s.radioValue = obj.optString("radioValue", "확인 중");
                s.radioSub = obj.optString("radioSub", "-");
                return s;
            }

            // Fallback compatibility with v1.3.5 JSON schema
            String commaIp = obj.optString("discoveredCarrotIp", "");
            int routePts = obj.optInt("routePoints", 0);
            if (!commaIp.isEmpty()) {
                s.commaTone = TONE_OK;
                s.commaValue = "연결됨";
                s.commaSub = commaIp + (routePts > 0 ? " · 경로 " + routePts + "pt" : "");
            } else {
                s.commaTone = TONE_WARN;
                s.commaValue = "찾는 중";
                s.commaSub = "같은 네트워크에 연결되면 자동으로 찾습니다";
            }

            String gpsStatus = obj.optString("gpsStatus", "GPS 대기 중");
            int satUsed = obj.optInt("gpsSatUsed", -1);
            int satInView = obj.optInt("gpsSatInView", -1);
            if (gpsStatus.contains("위성")) s.gpsTone = TONE_OK;
            else if (gpsStatus.contains("DR")) s.gpsTone = TONE_INFO;
            else s.gpsTone = TONE_WARN;
            s.gpsValue = gpsStatus;
            if (satUsed >= 0 && satInView > 0) {
                int view = Math.max(satUsed, satInView);
                s.gpsSub = "사용 " + satUsed + " / 관측 " + view + "기";
                s.gpsMeterPct = clampPct(satUsed * 100 / view);
            } else {
                s.gpsSub = "브릿지 업데이트 후 상세 표시";
                s.gpsMeterPct = 0;
            }
            s.radioTone = TONE_INFO;
            s.radioIcon = "hotspot";
            s.radioValue = "활성";
            s.radioSub = "상태 업데이트 권장";
            return s;
        } catch (Throwable t) {
            return offlineState("상태 JSON 파싱 대기");
        }
    }

    private static PanelState offlineState(String reason) {
        PanelState s = new PanelState();
        s.commaTone = TONE_WARN;
        s.commaValue = "대기 중";
        s.commaSub = reason;
        s.gpsTone = TONE_WARN;
        s.gpsValue = "대기 중";
        s.gpsSub = "위성 신호를 기다리는 중";
        s.gpsMeterPct = 0;
        s.radioTone = TONE_OFF;
        s.radioIcon = "hotspot";
        s.radioValue = "확인 중";
        s.radioSub = "-";
        return s;
    }

    static void triggerDashboardOverlay() {
        try {
            File f = new File(SHOW_UI_TRIGGER_PATH);
            File p = f.getParentFile();
            if (p != null && !p.exists()) {
                p.mkdirs();
            }
            FileOutputStream fos = new FileOutputStream(f);
            try {
                fos.write("1\n".getBytes(UTF8));
            } finally {
                fos.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String iconSpec(String key) {
        if ("comma".equals(key)) return "M4 12 L20 4 L14 20 L11 13 Z";
        if ("satellite".equals(key)) return "M5 9 L9 5 L13 9 L9 13 Z M11 15 L15 11 L19 15 L15 19 Z M11 9 L13 11 M4 20 Q7.5 19 8 16";
        if ("wifi".equals(key)) return "M2.5 9 Q12 0.5 21.5 9 M5.5 12.5 Q12 6.8 18.5 12.5 M8.5 16 Q12 13 15.5 16 O 12 19.5 1";
        return "O 12 12 2 M7.8 7.8 Q5.3 12 7.8 16.2 M16.2 7.8 Q18.7 12 16.2 16.2 M5 5 Q0.8 12 5 19 M19 5 Q23.2 12 19 19";
    }

    private static Bitmap renderIconBitmap(String key, int color, int sizePx) {
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        float scale = sizePx / 24f;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(2.0f * scale);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        Path path = new Path();
        String[] tokens = iconSpec(key).trim().split("\\s+");
        int i = 0;
        while (i < tokens.length) {
            String tok = tokens[i];
            char cmd = tok.charAt(0);
            List<Float> nums = new ArrayList<Float>();
            if (tok.length() > 1) nums.add(Float.valueOf(tok.substring(1)));
            i++;
            while (i < tokens.length && !Character.isLetter(tokens[i].charAt(0))) {
                nums.add(Float.valueOf(tokens[i]));
                i++;
            }
            if (cmd == 'M' && nums.size() == 2) {
                path.moveTo(nums.get(0) * scale, nums.get(1) * scale);
            } else if (cmd == 'L' && nums.size() == 2) {
                path.lineTo(nums.get(0) * scale, nums.get(1) * scale);
            } else if (cmd == 'Q' && nums.size() == 4) {
                path.quadTo(nums.get(0) * scale, nums.get(1) * scale,
                        nums.get(2) * scale, nums.get(3) * scale);
            } else if (cmd == 'O' && nums.size() == 3) {
                path.addCircle(nums.get(0) * scale, nums.get(1) * scale,
                        nums.get(2) * scale, Path.Direction.CW);
            } else if (cmd == 'Z') {
                path.close();
            }
        }
        canvas.drawPath(path, paint);
        return bmp;
    }

    private static Bitmap renderMeterBitmap(int pct, int fillColor, int widthPx, int heightPx) {
        Bitmap bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        float radius = heightPx / 2f;

        p.setColor(C_TRACK);
        canvas.drawRoundRect(new RectF(0f, 0f, widthPx, heightPx), radius, radius, p);

        int clamped = clampPct(pct);
        if (clamped > 0) {
            float fw = Math.max(heightPx, widthPx * (clamped / 100f));
            p.setColor(fillColor);
            canvas.drawRoundRect(new RectF(0f, 0f, fw, heightPx), radius, radius, p);
        }
        return bmp;
    }

    private static String readFileUtf8(File f, int maxBytes) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                int take = Math.min(n, maxBytes - total);
                if (take > 0) {
                    out.write(buf, 0, take);
                    total += take;
                }
                if (total >= maxBytes) break;
            }
            return new String(out.toByteArray(), UTF8);
        } finally {
            in.close();
        }
    }

    private static int clampPct(int pct) {
        return Math.max(0, Math.min(100, pct));
    }

    private static String nonNull(String s, String def) {
        return (s == null || s.isEmpty()) ? def : s;
    }
}
