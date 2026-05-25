package io.virtualapp.utils;

import android.app.ActivityManager;
import android.content.Context;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * DeviceStatsProvider
 *
 * Utility class for fetching real-time RAM and CPU statistics.
 * All methods are static and synchronous — call from a background thread.
 * No dependency on VirtualCore or the VA engine.
 */
public final class DeviceStatsProvider {

    private DeviceStatsProvider() {}

    // ─────────────────────────────────────────────
    // RAM
    // ─────────────────────────────────────────────

    public static final class RamInfo {
        public final long totalMb;
        public final long availableMb;
        public final long usedMb;
        public final float usagePercent;

        RamInfo(long totalMb, long availableMb) {
            this.totalMb = totalMb;
            this.availableMb = availableMb;
            this.usedMb = totalMb - availableMb;
            this.usagePercent = totalMb > 0 ? (usedMb / (float) totalMb) * 100f : 0f;
        }

        @Override
        public String toString() {
            return String.format("RAM: %dMB used / %dMB total (%.1f%%)", usedMb, totalMb, usagePercent);
        }
    }

    /**
     * Returns current RAM stats using ActivityManager.
     * Safe to call on any background thread.
     */
    public static RamInfo getRamInfo(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(memInfo);
        long totalMb = memInfo.totalMem / (1024 * 1024);
        long availableMb = memInfo.availMem / (1024 * 1024);
        return new RamInfo(totalMb, availableMb);
    }

    // ─────────────────────────────────────────────
    // CPU
    // ─────────────────────────────────────────────

    public static final class CpuInfo {
        public final float usagePercent;

        CpuInfo(float usagePercent) {
            this.usagePercent = Math.max(0f, Math.min(100f, usagePercent));
        }

        @Override
        public String toString() {
            return String.format("CPU: %.1f%%", usagePercent);
        }
    }

    /**
     * Returns CPU usage by reading /proc/stat twice with a 500ms gap.
     * Blocks the calling thread for ~500ms — always call from a background thread.
     */
    public static CpuInfo getCpuUsage() {
        long[] snapshot1 = readProcStat();
        if (snapshot1 == null) return new CpuInfo(0f);

        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CpuInfo(0f);
        }

        long[] snapshot2 = readProcStat();
        if (snapshot2 == null) return new CpuInfo(0f);

        long totalDelta = snapshot2[0] - snapshot1[0];
        long idleDelta  = snapshot2[1] - snapshot1[1];

        if (totalDelta <= 0) return new CpuInfo(0f);

        float usage = ((totalDelta - idleDelta) / (float) totalDelta) * 100f;
        return new CpuInfo(usage);
    }

    /**
     * Reads /proc/stat and returns [totalJiffies, idleJiffies].
     * Returns null if the file cannot be read.
     */
    private static long[] readProcStat() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"))) {
            String line = reader.readLine();
            if (line == null || !line.startsWith("cpu")) return null;

            // Format: cpu  user nice system idle iowait irq softirq steal guest guest_nice
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 5) return null;

            long user    = Long.parseLong(parts[1]);
            long nice    = Long.parseLong(parts[2]);
            long system  = Long.parseLong(parts[3]);
            long idle    = Long.parseLong(parts[4]);
            long iowait  = parts.length > 5 ? Long.parseLong(parts[5]) : 0;
            long irq     = parts.length > 6 ? Long.parseLong(parts[6]) : 0;
            long softirq = parts.length > 7 ? Long.parseLong(parts[7]) : 0;
            long steal   = parts.length > 8 ? Long.parseLong(parts[8]) : 0;

            long totalIdle  = idle + iowait;
            long totalBusy  = user + nice + system + irq + softirq + steal;
            long total      = totalIdle + totalBusy;

            return new long[]{total, totalIdle};
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }
}
