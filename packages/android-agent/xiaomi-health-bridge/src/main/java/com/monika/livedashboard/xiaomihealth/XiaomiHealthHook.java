package com.monika.livedashboard.xiaomihealth;

import android.net.Uri;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Grants provider read access to exactly the companion live-dashboard agent. */
public final class XiaomiHealthHook implements IXposedHookLoadPackage {
    private static final String TARGET_PACKAGE = "com.mi.health";
    private static final String AGENT_PACKAGE = "com.monika.livedashboard.agent";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (!TARGET_PACKAGE.equals(loadPackageParam.packageName)) {
            return;
        }

        try {
            Class<?> providerInfoClass = XposedHelpers.findClass(
                "com.xiaomi.fitness.dataprovider.DataProviderInfo",
                loadPackageParam.classLoader
            );
            XposedHelpers.findAndHookMethod(
                "com.xiaomi.fitness.dataprovider.DataContentProvider",
                loadPackageParam.classLoader,
                "checkReadPermission",
                Uri.class,
                String.class,
                providerInfoClass,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Uri uri = (Uri) param.args[0];
                        String callingPackage = (String) param.args[1];
                        if (AGENT_PACKAGE.equals(callingPackage) && isAllowedReadUri(uri)) {
                            // Void method: returning null skips Xiaomi's signature permission check.
                            param.setResult(null);
                        }
                    }
                }
            );
            XposedBridge.log("LiveDashboard Xiaomi Health Bridge active in " + loadPackageParam.processName);
        } catch (Throwable error) {
            XposedBridge.log("LiveDashboard Xiaomi Health Bridge failed: " + error);
        }
    }

    private static boolean isAllowedReadUri(Uri uri) {
        if (uri == null || !"com.mi.health.provider.main".equals(uri.getAuthority())) {
            return false;
        }
        String path = uri.getPath();
        return path != null && (
            path.equals("/sleep") || path.startsWith("/sleep/") ||
            path.equals("/activity/steps") || path.startsWith("/activity/steps/") ||
            path.equals("/heartrate") || path.startsWith("/heartrate/")
        );
    }
}
