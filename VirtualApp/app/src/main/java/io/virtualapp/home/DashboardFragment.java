package io.virtualapp.home;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;

import io.virtualapp.R;
import io.virtualapp.utils.DeviceStatsProvider;

public class DashboardFragment extends Fragment {

    private static final long POLL_INTERVAL_MS = 2000L;

    // Affiliate URL — replace with your real affiliate deep-link before release
    private static final String AFFILIATE_URL =
            "https://play.google.com/store/search?q=battery+cleaner&c=apps";

    // Gauge views
    private ProgressBar mGaugeRam;
    private ProgressBar mGaugeCpu;
    private TextView    mTextRamPercent;
    private TextView    mTextCpuPercent;
    private TextView    mTextRamLabel;
    private TextView    mTextCpuLabel;

    // Health score views
    private ProgressBar mGaugeHealth;
    private TextView    mTextHealthScore;
    private TextView    mTextHealthStatus;
    private TextView    mTextHealthDesc;

    // Memory detail views
    private TextView    mTextRamTotal;
    private TextView    mTextRamUsed;
    private TextView    mTextRamAvailable;

    // AdMob banner
    private AdView      mAdView;

    private Handler  mUiHandler;
    private boolean  mRunning = false;

    // Health thresholds
    private static final int THRESHOLD_HEALTHY  = 70;
    private static final int THRESHOLD_MODERATE = 40;

    // Health colors
    private static final int COLOR_HEALTHY  = Color.parseColor("#4CAF50");
    private static final int COLOR_MODERATE = Color.parseColor("#FFB74D");
    private static final int COLOR_PRESSURE = Color.parseColor("#FF5370");

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dashboard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mUiHandler        = new Handler(Looper.getMainLooper());

        // Gauge views
        mGaugeRam         = view.findViewById(R.id.gauge_ram);
        mGaugeCpu         = view.findViewById(R.id.gauge_cpu);
        mTextRamPercent   = view.findViewById(R.id.text_ram_percent);
        mTextCpuPercent   = view.findViewById(R.id.text_cpu_percent);
        mTextRamLabel     = view.findViewById(R.id.text_ram_label);
        mTextCpuLabel     = view.findViewById(R.id.text_cpu_label);

        // Health score views
        mGaugeHealth      = view.findViewById(R.id.gauge_health);
        mTextHealthScore  = view.findViewById(R.id.text_health_score);
        mTextHealthStatus = view.findViewById(R.id.text_health_status);
        mTextHealthDesc   = view.findViewById(R.id.text_health_desc);

        // Memory detail views
        mTextRamTotal     = view.findViewById(R.id.text_ram_total);
        mTextRamUsed      = view.findViewById(R.id.text_ram_used);
        mTextRamAvailable = view.findViewById(R.id.text_ram_available);

        // Affiliate CTA
        Button btnAffiliate = view.findViewById(R.id.btn_affiliate_cta);
        btnAffiliate.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(AFFILIATE_URL));
            startActivity(intent);
        });

        // Banner ad — initialise SDK and load asynchronously
        initBannerAd(view);
    }

    /**
     * Initialises MobileAds SDK and attaches a banner AdView to the container.
     * loadAd() fires a background network request — never blocks the UI thread.
     */
    private void initBannerAd(View root) {
        Context ctx = getContext();
        if (ctx == null) return;

        MobileAds.initialize(ctx, getString(R.string.admob_app_id));

        mAdView = new AdView(ctx);
        mAdView.setAdSize(AdSize.BANNER);
        mAdView.setAdUnitId(getString(R.string.admob_banner_id));

        FrameLayout container = root.findViewById(R.id.banner_ad_container);
        container.addView(mAdView);

        // Async — returns immediately; ad renders when response arrives
        mAdView.loadAd(new AdRequest.Builder().build());
    }

    // ── Polling loop ─────────────────────────────────────────────────────────

    @Override
    public void onResume() {
        super.onResume();
        mRunning = true;
        mUiHandler.post(mPollRunnable);
        if (mAdView != null) mAdView.resume();
    }

    @Override
    public void onPause() {
        super.onPause();
        mRunning = false;
        mUiHandler.removeCallbacksAndMessages(null);
        if (mAdView != null) mAdView.pause();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mAdView != null) {
            mAdView.destroy();
            mAdView = null;
        }
    }

    private final Runnable mPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mRunning || getContext() == null) return;
            final Context appCtx = getContext().getApplicationContext();
            new Thread(() -> {
                final DeviceStatsProvider.RamInfo ram = DeviceStatsProvider.getRamInfo(appCtx);
                final DeviceStatsProvider.CpuInfo cpu = DeviceStatsProvider.getCpuUsage();
                if (mUiHandler != null) {
                    mUiHandler.post(() -> updateUi(ram, cpu));
                }
            }).start();
            mUiHandler.postDelayed(mPollRunnable, POLL_INTERVAL_MS);
        }
    };

    // ── UI update ────────────────────────────────────────────────────────────

    private void updateUi(DeviceStatsProvider.RamInfo ram, DeviceStatsProvider.CpuInfo cpu) {
        if (!isAdded() || getView() == null) return;

        // RAM gauge
        int ramProgress = Math.round(ram.usagePercent);
        mGaugeRam.setProgress(ramProgress);
        mTextRamPercent.setText(ramProgress + "%");
        mTextRamLabel.setText(ram.usedMb + " / " + ram.totalMb + " MB");

        // CPU gauge
        int cpuProgress = Math.round(cpu.usagePercent);
        mGaugeCpu.setProgress(cpuProgress);
        mTextCpuPercent.setText(cpuProgress + "%");
        mTextCpuLabel.setText(String.format("%.1f%% load", cpu.usagePercent));

        // Memory details
        mTextRamTotal.setText(ram.totalMb + " MB");
        mTextRamUsed.setText(ram.usedMb + " MB");
        mTextRamAvailable.setText(ram.availableMb + " MB");

        // Health score
        int score = calculateHealthScore(ram, cpu);
        mGaugeHealth.setProgress(score);
        mTextHealthScore.setText(String.valueOf(score));
        applyHealthColor(score);
    }

    // ── Health score logic ────────────────────────────────────────────────────

    /**
     * Composite health score 0–100.
     * RAM weighted 60%, CPU weighted 40%.
     * Higher score = healthier device.
     */
    private int calculateHealthScore(DeviceStatsProvider.RamInfo ram,
                                     DeviceStatsProvider.CpuInfo cpu) {
        float ramScore  = 100f - ram.usagePercent;
        float cpuScore  = 100f - cpu.usagePercent;
        float composite = (ramScore * 0.6f) + (cpuScore * 0.4f);
        return Math.max(0, Math.min(100, Math.round(composite)));
    }

    /**
     * Updates health status label, description, progress bar tint,
     * and score text color based on the composite score band.
     */
    private void applyHealthColor(int score) {
        int    color;
        String status;
        String desc;

        if (score >= THRESHOLD_HEALTHY) {
            color  = COLOR_HEALTHY;
            status = "Healthy";
            desc   = "Your device is running smoothly";
        } else if (score >= THRESHOLD_MODERATE) {
            color  = COLOR_MODERATE;
            status = "Moderate";
            desc   = "Some resources are under load";
        } else {
            color  = COLOR_PRESSURE;
            status = "Under Pressure";
            desc   = "High resource usage detected";
        }

        mTextHealthScore.setTextColor(color);
        mTextHealthStatus.setTextColor(color);
        mTextHealthStatus.setText(status);
        mTextHealthDesc.setText(desc);
        mGaugeHealth.setProgressTintList(
                android.content.res.ColorStateList.valueOf(color));
    }
}
