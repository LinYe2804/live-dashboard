package com.monika.livedashboard.agent.xposed;

import android.app.Notification;
import android.content.ComponentName;
import android.os.Bundle;

import org.json.JSONObject;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class LiveDashboardHook implements IXposedHookLoadPackage {
    private static final String ANDROID_PACKAGE = "android";
    private static final String XIAOMI_HEALTH_PACKAGE = "com.mi.health";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (ANDROID_PACKAGE.equals(param.packageName) && ANDROID_PACKAGE.equals(param.processName)) {
            hookForegroundActivity(param.classLoader);
            hookMediaNotifications(param.classLoader);
        } else if (XIAOMI_HEALTH_PACKAGE.equals(param.packageName)) {
            XiaomiHealthHook.install(param.processName);
        }
    }

    private static void hookForegroundActivity(ClassLoader loader) {
        try {
            Class<?> activityRecord = XposedHelpers.findClass(
                "com.android.server.wm.ActivityRecord", loader
            );
            XposedHelpers.findAndHookMethod(
                "com.android.server.wm.TaskFragment",
                loader,
                "setResumedActivity",
                activityRecord,
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object record = param.args[0];
                        if (record == null) return;
                        try {
                            String packageName = (String) XposedHelpers.getObjectField(
                                record, "packageName"
                            );
                            ComponentName component = (ComponentName) XposedHelpers.getObjectField(
                                record, "mActivityComponent"
                            );
                            JSONObject event = new JSONObject()
                                .put("type", "foreground")
                                .put("package_name", packageName)
                                .put("app_name", packageName)
                                .put("activity", component == null ? "" : component.flattenToShortString())
                                .put("timestamp_ms", System.currentTimeMillis());
                            DaemonSocket.send(event);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            );
            XposedBridge.log("LiveDashboard: Android foreground hook active");
        } catch (Throwable error) {
            XposedBridge.log("LiveDashboard: foreground hook failed: " + error);
        }
    }

    private static void hookMediaNotifications(ClassLoader loader) {
        try {
            Class<?> manager = XposedHelpers.findClass(
                "com.android.server.notification.NotificationManagerService", loader
            );
            XposedBridge.hookAllMethods(manager, "enqueueNotificationInternal", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Notification notification = null;
                    String packageName = "";
                    for (Object argument : param.args) {
                        if (notification == null && argument instanceof Notification) {
                            notification = (Notification) argument;
                        } else if (packageName.isEmpty() && argument instanceof String) {
                            packageName = (String) argument;
                        }
                    }
                    if (notification == null || notification.extras == null) return;
                    Bundle extras = notification.extras;
                    boolean isMedia = extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
                        || Notification.CATEGORY_TRANSPORT.equals(notification.category);
                    if (!isMedia) return;
                    CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
                    CharSequence artist = extras.getCharSequence(Notification.EXTRA_TEXT);
                    if (title == null || title.length() == 0) return;
                    try {
                        DaemonSocket.send(new JSONObject()
                            .put("type", "music")
                            .put("title", title.toString())
                            .put("artist", artist == null ? "" : artist.toString())
                            .put("music_app", packageName)
                            .put("timestamp_ms", System.currentTimeMillis()));
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.log("LiveDashboard: Android media hook active");
        } catch (Throwable error) {
            XposedBridge.log("LiveDashboard: media hook failed: " + error);
        }
    }
}
