package com.monika.livedashboard.agent.xposed;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class XiaomiHealthHook {
    private static final String AUTHORITY = "com.mi.health.provider.main";
    private static final String BASE_URI = "content://" + AUTHORITY;
    private static final long COLLECTION_INTERVAL_MS = 60_000L;
    private static final Set<String> SENT_HISTORY = new HashSet<>();
    private static volatile boolean started;
    private static volatile boolean sleepStateLogged;

    private XiaomiHealthHook() {}

    static void install(String processName) {
        try {
            XposedHelpers.findAndHookMethod(
                Application.class,
                "attach",
                Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        startCollector((Application) param.thisObject);
                    }
                }
            );
            XposedBridge.log(
                "LiveDashboard: Xiaomi Health collector hook active in " + processName
            );
        } catch (Throwable error) {
            XposedBridge.log("LiveDashboard: Xiaomi Health hook failed: " + error);
        }
    }

    private static synchronized void startCollector(Application application) {
        if (started) return;
        started = true;
        ContentResolver resolver = application.getContentResolver();
        HandlerThread thread = new HandlerThread("LiveDashboardHealth");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        Runnable collection = new Runnable() {
            @Override
            public void run() {
                try {
                    collect(resolver);
                } catch (Throwable error) {
                    XposedBridge.log("LiveDashboard: health collection failed: " + error);
                } finally {
                    handler.postDelayed(this, COLLECTION_INTERVAL_MS);
                }
            }
        };
        handler.postDelayed(collection, 15_000L);
    }

    private static void collect(ContentResolver resolver) throws Exception {
        JSONArray records = new JSONArray();
        long now = System.currentTimeMillis();
        collectSleepState(resolver, records, now);

        collectActivity(resolver, records, now);
        collectHeartRate(resolver, records);
        collectSleepHistory(resolver, records);

        if (records.length() > 0) {
            DaemonSocket.send(new JSONObject()
                .put("type", "health")
                .put("timestamp_ms", now)
                .put("records", records));
        }
    }

    private static void collectSleepState(
        ContentResolver resolver, JSONArray records, long now
    ) {
        try {
            // Xiaomi's provider treats the method itself as a full URI and reads the
            // operation name from its fragment. Prefer the authority overload on
            // Android 10+ so the full URI reaches DataContentProvider.call unchanged.
            Bundle result;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                result = resolver.call(
                    AUTHORITY, BASE_URI + "/sleep#is_maybe_sleeping", null, null
                );
            } else {
                result = resolver.call(
                    Uri.parse(BASE_URI), BASE_URI + "/sleep#is_maybe_sleeping", null, null
                );
            }
            if (result == null || !result.containsKey("extra_maybe_sleeping")) {
                throw new IllegalStateException("Xiaomi Health returned no sleep state");
            }
            boolean sleeping = result.getBoolean("extra_maybe_sleeping");
            records.put(record("sleep_state", sleeping ? 1.0 : 0.0, "state", now, null));
            if (!sleepStateLogged) {
                sleepStateLogged = true;
                XposedBridge.log(
                    "LiveDashboard: sleep state collection active (sleeping=" + sleeping + ")"
                );
            }
        } catch (Throwable error) {
            XposedBridge.log("LiveDashboard: sleep state collection failed: " + error);
        }
    }

    private static void collectActivity(ContentResolver resolver, JSONArray records, long now) {
        try (Cursor cursor = resolver.query(
            Uri.parse(BASE_URI + "/activity/steps/brief"),
            new String[]{"steps", "distance", "energy"}, null, null, null
        )) {
            if (cursor == null || !cursor.moveToFirst()) return;
            putDouble(cursor, records, "steps", "steps", "steps", 1.0, now);
            putDouble(cursor, records, "distance", "distance", "m", 1000.0, now);
            putDouble(cursor, records, "energy", "active_calories", "kcal", 1.0, now);
        } catch (Throwable ignored) {
        }
    }

    private static void collectHeartRate(ContentResolver resolver, JSONArray records) {
        try (Cursor cursor = resolver.query(
            Uri.parse(BASE_URI + "/heartrate/recent"),
            new String[]{"hrm", "timestamp"}, null, null, null
        )) {
            if (cursor == null || !cursor.moveToFirst()) return;
            Double bpm = getDouble(cursor, "hrm");
            Long timestamp = getLong(cursor, "timestamp");
            if (bpm != null && bpm > 0 && timestamp != null && timestamp > 0) {
                records.put(record("heart_rate", bpm, "bpm", timestamp, null));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void collectSleepHistory(ContentResolver resolver, JSONArray records) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        for (long daysAgo = 0; daysAgo <= 7; daysAgo++) {
            long day = today.minusDays(daysAgo).atStartOfDay(zone).toInstant().toEpochMilli();
            try (Cursor cursor = resolver.query(
                Uri.parse(BASE_URI + "/sleep/report"),
                new String[]{"sleep_time", "wake_time", "duration"},
                "date_time = ?", new String[]{Long.toString(day)}, null
            )) {
                if (cursor == null || !cursor.moveToFirst()) continue;
                Long start = getLong(cursor, "sleep_time");
                Long wake = getLong(cursor, "wake_time");
                Long duration = getLong(cursor, "duration");
                if (start == null || start <= 0 || duration == null || duration <= 0) continue;
                String key = start + "|" + duration;
                synchronized (SENT_HISTORY) {
                    if (!SENT_HISTORY.add(key)) continue;
                    if (SENT_HISTORY.size() > 64) SENT_HISTORY.clear();
                }
                records.put(record("sleep", duration / 60_000.0, "min", start,
                    wake != null && wake > start ? wake : null));
            } catch (Throwable ignored) {
            }
        }
    }

    private static void putDouble(
        Cursor cursor, JSONArray records, String column, String type, String unit,
        double multiplier, long timestamp
    ) {
        Double value = getDouble(cursor, column);
        if (value != null) records.put(record(type, value * multiplier, unit, timestamp, null));
    }

    private static JSONObject record(
        String type, double value, String unit, long timestamp, Long endTime
    ) {
        JSONObject item = new JSONObject();
        try {
            item.put("type", type)
                .put("value", value)
                .put("unit", unit)
                .put("timestamp_ms", timestamp);
            if (endTime != null) item.put("end_time_ms", endTime);
        } catch (Throwable ignored) {
        }
        return item;
    }

    private static Double getDouble(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index >= 0 && !cursor.isNull(index) ? cursor.getDouble(index) : null;
    }

    private static Long getLong(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index >= 0 && !cursor.isNull(index) ? cursor.getLong(index) : null;
    }
}
