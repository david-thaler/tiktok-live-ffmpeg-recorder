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
        String outputFilenamePrefix,
        Boolean keepMKVFiles
) {

    /**
     * Overrides default keepMKVFiles getter to default it to false.
     * @return Should we keep MKV files after converting to mp4?
     */
    public Boolean keepMKVFiles() {
        boolean keep = false;
        if (keepMKVFiles != null) keep = keepMKVFiles;
        return keep;
    }

}
