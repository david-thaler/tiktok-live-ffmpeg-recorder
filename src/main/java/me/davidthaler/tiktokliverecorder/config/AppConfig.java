package me.davidthaler.tiktokliverecorder.config;

import java.util.List;

/**
 * Record to represent the top-level config file.
 * @param ffmpegPath Path of the ffmpeg executable.
 * @param ytdlpPath Path of the yt-dlp executable.
 * @param watchers The list of watchers to run.
 */
public record AppConfig(
        String ffmpegPath,
        String ytdlpPath,
        List<WatcherConfig> watchers
) {
}
