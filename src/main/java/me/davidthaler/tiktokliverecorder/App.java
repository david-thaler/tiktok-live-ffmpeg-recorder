package me.davidthaler.tiktokliverecorder;

import me.davidthaler.tiktokliverecorder.config.AppConfig;
import me.davidthaler.tiktokliverecorder.config.WatcherConfig;
import me.davidthaler.tiktokliverecorder.watcher.WatcherRunner;
import org.apache.commons.cli.*;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Application loader class.
 */
public class App {

    /** Object mapper instance. */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /** HTTP Client instance. */
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    /**
     * Main starter method. Responsible for configuring all tooling & starting watchers.
     * @param args The command line args.
     */
    static void main(String[] args) {
        Options options = new Options();
        options.addOption("c", "config", true, "Config file path");
        CommandLineParser parser = new DefaultParser();
        try {
            CommandLine cmd = parser.parse(options, args);
            String configPath = cmd.getOptionValue("c", "config/config.json");
            File configFile = new File(configPath);
            if (!configFile.exists()) {
                throw new RuntimeException("Config file does not exist: " + configFile.getAbsolutePath());
            }
            // Load config and spawn watcher jobs.
            AppConfig appConfig = OBJECT_MAPPER.readValue(configFile, AppConfig.class);
            try (ScheduledExecutorService executorService =
                         Executors.newScheduledThreadPool(appConfig.watchers().size(), Thread.ofVirtual().factory())) {
                for (WatcherConfig watcher : appConfig.watchers()) {
                    Duration duration = Duration.of(watcher.pollIntervalQty(), watcher.pollIntervalUnit());
                    executorService.scheduleWithFixedDelay(
                            new WatcherRunner(appConfig, watcher, HTTP_CLIENT, OBJECT_MAPPER),
                            0, duration.toMillis(), TimeUnit.MILLISECONDS);
                }
                try {
                    // Keep main thread open to stop the JVM shutting down since virtual threads don't hold the jvm open.
                    Thread.currentThread().join();
                } catch (InterruptedException e) {
                    // Do nothing.
                }
            }
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }
}
