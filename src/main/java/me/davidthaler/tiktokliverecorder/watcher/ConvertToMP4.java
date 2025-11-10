package me.davidthaler.tiktokliverecorder.watcher;

import me.davidthaler.tiktokliverecorder.config.AppConfig;
import me.davidthaler.tiktokliverecorder.config.WatcherConfig;

import java.io.File;
import java.io.IOException;

/**
 * Runnable job to convert mkv files to mp4 after the stream is finished.
 */
public class ConvertToMP4 implements Runnable {

    private final File targetDirectory;
    private final AppConfig appConfig;
    private final WatcherConfig watcherConfig;

    /**
     * Default Constructor.
     * @param targetDirectory Target directory to convert on.
     * @param appConfig The application config instance.
     * @param watcherConfig The watcher config instance.
     */
    public ConvertToMP4(File targetDirectory, AppConfig appConfig, WatcherConfig watcherConfig) {
        this.targetDirectory = targetDirectory;
        this.appConfig = appConfig;
        this.watcherConfig = watcherConfig;
    }

    /**
     * Iterates through the given target directory finding all mkv files that have not yet been converted (or may have
     * been incompletely converted) and converts them to mp4 one by one.
     */
    @Override
    public void run() {
        for (File file : targetDirectory.listFiles()) {
            String fName = file.getName();
            if (fName.endsWith(".mkv") && !fName.endsWith("-converted.mkv")) {
                File mp4File = new File(file.getAbsolutePath().replace(".mkv", ".mp4"));
                if (mp4File.exists()) {
                    // mp4 exists with mkv still existing and not kept as a "-converted" then it's probably bad.
                    mp4File.delete();
                }
                String ffmpeg = appConfig.ffmpegPath();
                if (ffmpeg == null || ffmpeg.isEmpty()) {
                    ffmpeg = "ffmpeg";
                }
                ProcessBuilder pb = new ProcessBuilder(
                        ffmpeg, "-i", file.getAbsolutePath(), "-c", "copy", mp4File.getAbsolutePath());
                pb.inheritIO();
                try {
                    Process p = pb.start();
                    p.waitFor();
                    // Wait for the process to fully let go of the original file.
                    Thread.sleep(500);
                    if (watcherConfig.keepMKVFiles()) {
                        file.renameTo(new File(file.getAbsolutePath().replace(".mkv",
                                "-converted.mkv")));
                    } else {
                        file.delete();
                    }
                } catch (IOException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }
}
