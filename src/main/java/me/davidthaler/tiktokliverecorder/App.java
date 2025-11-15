package me.davidthaler.tiktokliverecorder;

import me.davidthaler.tiktokliverecorder.config.AppConfig;
import me.davidthaler.tiktokliverecorder.config.WatcherConfig;
import me.davidthaler.tiktokliverecorder.watcher.WatcherRunner;
import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    /** Logger instance. */
    private static final Logger LOGGER = LoggerFactory.getLogger(App.class);

    /**
     * Main starter method. Responsible for configuring all tooling & starting watchers.
     * @param args The command line args.
     */
    static void main(String[] args) {
        LOGGER.info("Starting Tiktok Live Recorder...");
        Options options = new Options();
        options.addOption("c", "config", true, "Config file path");
        CommandLineParser parser = new DefaultParser();
        try {
            CommandLine cmd = parser.parse(options, args);
            String configPath = cmd.getOptionValue("c", "config/config.json");
            File configFile = new File(configPath);
            LOGGER.info("Loading configuration from [{}] resolved to [{}]", configPath, configFile.getAbsolutePath());
            if (!configFile.exists()) {
                LOGGER.error("Failed to start",
                        new RuntimeException("Config file does not exist: " + configFile.getAbsolutePath()));
                System.exit(1);
            }
            // Load config and spawn watcher jobs.
            LOGGER.info("Creating Watchers...");
            AppConfig appConfig = OBJECT_MAPPER.readValue(configFile, AppConfig.class);
            try (ScheduledExecutorService executorService =
                         Executors.newScheduledThreadPool(appConfig.watchers().size(), Thread.ofVirtual().factory())) {
                for (WatcherConfig watcher : appConfig.watchers()) {
                    LOGGER.info("Spawning watcher job for channel [{}]", watcher.channel());
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
        } catch (ParseException ex) {
            LOGGER.error("Error parsing config file.", ex);
        }
    }
}
