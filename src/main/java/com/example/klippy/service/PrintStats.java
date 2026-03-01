package com.example.klippy.service;

/**
 * Snapshot of current print statistics from Moonraker.
 *
 * @param state        printer state: standby, printing, paused, complete, cancelled, error
 * @param filename     name of the file being printed
 * @param progress     0.0 to 1.0
 * @param printDuration seconds spent actively printing
 * @param totalDuration total elapsed seconds (including pauses)
 */
public record PrintStats(
        String state,
        String filename,
        double progress,
        double printDuration,
        double totalDuration
) {
    /**
     * Estimated seconds remaining, based on progress and elapsed print time.
     * Returns -1 if not enough data to estimate.
     */
    public double estimatedTimeRemaining() {
        if (progress <= 0.0 || printDuration <= 0.0) {
            return -1;
        }
        double totalEstimated = printDuration / progress;
        return totalEstimated - printDuration;
    }

    public static String formatDuration(double seconds) {
        if (seconds < 0) {
            return "--:--:--";
        }
        long total = (long) seconds;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }
}
