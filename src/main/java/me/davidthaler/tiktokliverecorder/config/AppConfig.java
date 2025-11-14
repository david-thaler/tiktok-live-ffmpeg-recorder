package me.davidthaler.tiktokliverecorder.config;

import java.util.List;

/**
 * Record to represent the top-level config file.
 * @param ffmpegPath Path of the ffmpeg executable.
 * @param ytdlpPath Path of the yt-dlp executable.
 * @param encodeWhileDownloading Should ffmpeg encode while downloading? Will default to straight
 *                              copying the stream if null or false.
 * @param watchers The list of watchers to run.
 */
public record AppConfig(
        String ffmpegPath,
        String ytdlpPath,
        Boolean encodeWhileDownloading,
        List<WatcherConfig> watchers) {

    /**
     * Overrides default encodeWhileDownloading getter to default it to false.
     * @return Should we encode during the download?
     */
    public Boolean encodeWhileDownloading() {
        boolean encode = false;
        if (encodeWhileDownloading != null) encode = encodeWhileDownloading;
        return encode;
    }

}
