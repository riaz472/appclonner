package io.virtualapp.splash;

import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

import com.lody.virtual.client.core.VirtualCore;

import io.virtualapp.R;
import io.virtualapp.VCommends;
import io.virtualapp.abs.ui.VActivity;
import io.virtualapp.abs.ui.VUiKit;
import io.virtualapp.home.FlurryROMCollector;
import io.virtualapp.home.HomeActivity;
import jonathanfinerty.once.Once;

public class SplashActivity extends VActivity {

    private static final String TAG = "SplashActivity";
    private static final long ENGINE_WAIT_TIMEOUT_MS = 8_000L;
    private static final long SPLASH_MIN_MS = 3_000L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        @SuppressWarnings("unused")
        boolean enterGuide = !Once.beenDone(Once.THIS_APP_INSTALL, VCommends.TAG_NEW_VERSION);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        VUiKit.defer().when(() -> {
            if (!Once.beenDone("collect_flurry")) {
                FlurryROMCollector.startCollect();
                Once.markDone("collect_flurry");
            }
            long start = System.currentTimeMillis();
            waitForEngineWithTimeout();
            long elapsed = System.currentTimeMillis() - start;
            long delta = SPLASH_MIN_MS - elapsed;
            if (delta > 0) {
                VUiKit.sleep(delta);
            }
        }).done((res) -> {
            HomeActivity.goHome(this);
            finish();
        });
    }

    /**
     * Waits for the VA engine (server process) to become ready, with a hard
     * timeout so the splash never hangs indefinitely on devices where the engine
     * process fails to start or takes too long.
     *
     * On Android 11+ getRunningAppProcesses() only returns the caller's own
     * process, so isEngineLaunched() naturally returns false; we always call
     * waitForEngine() in that case, which is safe and idempotent.
     */
    private void waitForEngineWithTimeout() {
        Thread waitThread = new Thread(() -> {
            try {
                if (!VirtualCore.get().isEngineLaunched()) {
                    VirtualCore.get().waitForEngine();
                }
            } catch (Throwable e) {
                Log.e(TAG, "waitForEngine failed: " + e.getMessage());
            }
        }, "engine-wait");
        waitThread.setDaemon(true);
        waitThread.start();
        try {
            waitThread.join(ENGINE_WAIT_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (waitThread.isAlive()) {
            Log.w(TAG, "Engine did not respond within " + ENGINE_WAIT_TIMEOUT_MS
                    + " ms — proceeding to home screen.");
            waitThread.interrupt();
        }
    }
}
