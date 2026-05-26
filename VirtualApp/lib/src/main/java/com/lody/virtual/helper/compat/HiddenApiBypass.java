package com.lody.virtual.helper.compat;

import android.os.Build;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Bypasses Android hidden API enforcement (introduced in API 28).
 *
 * Strategy (applied in order):
 *  1. Native JNI call to VMRuntime.setHiddenApiExemptions — JNI bypasses
 *     the Java-layer hidden API check entirely; works on Android 9–16.
 *  2. Double-reflection fallback — uses Class.getDeclaredMethod indirectly
 *     so the check sees a trusted caller; works on Android 9–11.
 *  3. Graceful no-op — logs a warning and lets the app continue; individual
 *     reflection sites are wrapped in try/catch so failures are non-fatal.
 *
 * Call {@link #exemptAll()} as early as possible in Application.attachBaseContext(),
 * before any VirtualCore startup or mirror.* reflection.
 */
public class HiddenApiBypass {

    private static final String TAG = "HiddenApiBypass";
    private static volatile boolean sBypassDone = false;

    public static void exemptAll() {
        if (sBypassDone) return;
        sBypassDone = true;

        if (Build.VERSION.SDK_INT < 28) {
            return;
        }

        if (tryNativeBypass()) {
            Log.d(TAG, "Hidden API exempted via JNI (SDK " + Build.VERSION.SDK_INT + ")");
            return;
        }

        if (Build.VERSION.SDK_INT <= 30) {
            try {
                doubleReflectionExempt();
                Log.d(TAG, "Hidden API exempted via double-reflection (SDK " + Build.VERSION.SDK_INT + ")");
                return;
            } catch (Throwable t) {
                Log.w(TAG, "Double-reflection bypass failed: " + t.getMessage());
            }
        }

        Log.w(TAG, "No hidden API bypass succeeded on SDK " + Build.VERSION.SDK_INT
                + "; reflection sites will fail gracefully.");
    }

    private static boolean tryNativeBypass() {
        try {
            System.loadLibrary("va++");
            return nativeExemptAll();
        } catch (Throwable t) {
            Log.d(TAG, "Native bypass unavailable: " + t.getMessage());
            return false;
        }
    }

    /**
     * Double-reflection trick: invoking Class.getDeclaredMethod through another
     * Class.getDeclaredMethod call makes ART believe the caller is java.lang.Class
     * (a trusted boot-class caller), bypassing the hidden API check.
     * Works on Android 9–11 (patched in Android 12).
     */
    private static void doubleReflectionExempt() throws Throwable {
        Method forName = Class.class.getDeclaredMethod("forName", String.class);
        Method getDeclaredMethod = Class.class.getDeclaredMethod(
                "getDeclaredMethod", String.class, Class[].class);

        Class<?> vmRuntimeClass = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
        Method getRuntime = (Method) getDeclaredMethod.invoke(
                vmRuntimeClass, "getRuntime", (Object) null);
        Method setHiddenApiExemptions = (Method) getDeclaredMethod.invoke(
                vmRuntimeClass, "setHiddenApiExemptions", new Class[]{String[].class});

        Object runtime = getRuntime.invoke(null);
        setHiddenApiExemptions.invoke(runtime, new Object[]{new String[]{"L"}});
    }

    /**
     * JNI implementation in VAJni.cpp — registered in JNI_OnLoad.
     * Calls VMRuntime.getRuntime().setHiddenApiExemptions(["L"]) directly
     * through JNI, bypassing the Java hidden API enforcement layer entirely.
     */
    private static native boolean nativeExemptAll();
}
