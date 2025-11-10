package me.davidthaler.tiktokliverecorder.config;

import java.time.temporal.ChronoUnit;

/**
 * Record representation of the watcher configuration.
 * @param channel The channel to watch.
 * @param pollIntervalQty The quantity of the interval to poll for live status.
 * @param pollIntervalUnit The unit of the interval to poll for live status.
 */
public record WatcherConfig(
        String channel,
        long pollIntervalQty,
        ChronoUnit pollIntervalUnit,
        String outputPath,
        String outputFilenamePrefix
) {
}
