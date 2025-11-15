package me.davidthaler.tiktokliverecorder.config;

import java.time.temporal.ChronoUnit;

/**
 * Record representation of the watcher configuration.
 * @param channel The channel to watch.
 * @param pollIntervalQty The quantity of the interval to poll for live status.
 * @param pollIntervalUnit The unit of the interval to poll for live status.
 * @param outputPath The output path for the videos.
 * @param outputFilenamePrefix The prefix to the file name before the timestamp.
 * @param keepMKVFiles Should the MKV files be kept after converting to mp4 or not. Defaults to false.
 * @param logFfmpegOutput Should the ffmpeg raw output be logged along with the video? Defaults to false.
 * @param logToFile Should the watcher log status and error reports to a log file? Defaults to true.
 */
public record WatcherConfig(
        String channel,
        long pollIntervalQty,
        ChronoUnit pollIntervalUnit,
        String outputPath,
        String outputFilenamePrefix,
        Boolean keepMKVFiles,
        Boolean logFfmpegOutput,
        Boolean logToFile) {

    /**
     * Overrides default keepMKVFiles getter.
     * @return Should we keep MKV files after converting to mp4?
     */
    public Boolean keepMKVFiles() {
        return defaultBool(false, keepMKVFiles);
    }

    /**
     * Overrides default logFfmpegOutput getter.
     * @return Should the ffmpeg raw output be logged?
     */
    public Boolean logFfmpegOutput() {
        return defaultBool(false, logFfmpegOutput);
    }

    /**
     * Overrides default logToFile getter.
     * @return Should we log the watcher logs to a log file.
     */
    public Boolean logToFile() {
        return defaultBool(true, logToFile);
    }

    /**
     * Defaults a boolean value.
     * @param defaultVal The default value when origin not provided.
     * @param input The input value.
     * @return Returned the input value or default if not there.
     */
    private Boolean defaultBool(boolean defaultVal, Boolean input) {
        boolean bool = defaultVal;
        if (input != null) bool = input;
        return bool;
    }

}
