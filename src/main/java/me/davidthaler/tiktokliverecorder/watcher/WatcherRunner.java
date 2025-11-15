package me.davidthaler.tiktokliverecorder.watcher;

import me.davidthaler.tiktokliverecorder.config.AppConfig;
import me.davidthaler.tiktokliverecorder.config.WatcherConfig;
import org.apache.logging.log4j.ThreadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runnable implementation for watching for live status and recording when live.
 */
public class WatcherRunner implements Runnable {

    /** Application config for context. */
    private final AppConfig appConfig;
    /** Watcher config for this watcher instance. */
    private final WatcherConfig watcherConfig;
    /** Http client instance. */
    private final HttpClient httpClient;
    /** Object mapper instance. */
    private final ObjectMapper objectMapper;
    /** Pre-built request for getting the url to then query live status. */
    private final HttpRequest signedUrlGetter;
    /** Output directory for this recorder instance. */
    private final File OUTPUT_DIRECTORY;
    /** Instance of the Convert to MP4 runner to be executed after every recording. */
    private final ConvertToMP4 CONVERTER_JOB_RUNNER;
    /** Logger instance for the specific watcher instance. */
    private Logger logger;
    /** Api url to fetch the signed url for the live status. */
    private static final String SIGNED_URL_GETTER_URL =
        "https://tikrec.com/tiktok/room/api/sign?unique_id=";
    /** Date formatter for putting timestamps on file names. */
    private static final DateTimeFormatter DATE_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    /**
     * Default constructor.
     * @param appConfig The app config instance.
     * @param watcherConfig The watcher config instance.
     * @param httpClient The http client instance.
     * @param objectMapper The object mapper instance.
     */
    public WatcherRunner(AppConfig appConfig, WatcherConfig watcherConfig, HttpClient httpClient,
                         ObjectMapper objectMapper) {
        this.appConfig = appConfig;
        this.watcherConfig = watcherConfig;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        try {
            signedUrlGetter = HttpRequest.newBuilder(
                    new URI(SIGNED_URL_GETTER_URL +
                            URLEncoder.encode(watcherConfig.channel(), Charset.defaultCharset()))).build();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        String outPath = watcherConfig.outputPath();
        if (outPath == null || outPath.isEmpty()) {
            outPath = "out/" + watcherConfig.channel();
        }
        if (outPath.charAt(outPath.length() - 1) != '/') {
            outPath += "/";
        }
        OUTPUT_DIRECTORY = new File(outPath);
        OUTPUT_DIRECTORY.mkdirs();
        CONVERTER_JOB_RUNNER = new ConvertToMP4(OUTPUT_DIRECTORY, appConfig, watcherConfig);
        // Cleanup anything left over.
        new Thread(CONVERTER_JOB_RUNNER).start();
    }

    /**
     * Called by thread executor, checks if the user is live and if it is, begins a blocking recording.
     */
    @Override
    public void run() {
            String channel = watcherConfig.channel();
            if (logger == null) {
                Thread.currentThread().setName("w-" + channel);
                ThreadContext.put("channel", channel);
                logger = LoggerFactory.getLogger("watcher");
            }
            try {
                if (userIsLive()) {
                    logger.info("User {} is live.", channel);
                    startRecording();
                } else {
                    logger.info("User {} is NOT live, checking again in {} {}.",
                            channel, watcherConfig.pollIntervalQty(), watcherConfig.pollIntervalUnit());
                }
            } catch (Exception ex) {
                logger.error("Unhandled exception was caught.", ex);
            }
    }

    /**
     * Checks if the user for this watcher is live.
     * @return True if the user is live, false otherwise.
     * @throws IOException Thrown on error during http call.
     * @throws InterruptedException Thrown on error during http call.
     * @throws URISyntaxException Thrown if unable to construct the URI instance.
     */
    private boolean userIsLive() {
        HttpResponse<String> response = null;
        try {
            response = httpClient.send(signedUrlGetter, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> signedResponse = objectMapper.readValue(response.body(), Map.class);
            String signedUrl = signedResponse.get("signed_url").toString();
            response = httpClient.send(
                    HttpRequest.newBuilder(new URI(signedUrl)).build(), HttpResponse.BodyHandlers.ofString());
            Map<String, Object> responseBody = objectMapper.readValue(response.body(), Map.class);
            if (((String) responseBody.getOrDefault("message", "empty"))
                    .equalsIgnoreCase("user_not_found")) {
                logger.error("User {} was reported as not found. " +
                        "If this is the first time watching this user then this message may be correct. " +
                        "If seen randomly, this could be a sign of a time out and this message can be ignored.",
                        watcherConfig.channel());
                return false;
            }
            String roomId = ((Map) ((Map) responseBody.getOrDefault("data", Map.of()))
                    .getOrDefault("user", Map.of())).getOrDefault("roomId", "NULL").toString();
            if (roomId.equals("NULL")) roomId = null;
            response = httpClient.send(HttpRequest.newBuilder(
                    new URI("https://webcast.tiktok.com/webcast/room/check_alive/?aid=1988&region=CH&room_ids="
                            + roomId + "&user_is_login=true")).build(), HttpResponse.BodyHandlers.ofString());
            responseBody = objectMapper.readValue(response.body(), Map.class);
            return Boolean.parseBoolean(((List<Map>) responseBody.get("data")).getFirst().get("alive").toString());
        } catch (IOException | URISyntaxException | InterruptedException ex) {
            logger.error("Error occurred while querying for live status.", ex);
        } catch (ClassCastException | NullPointerException ex) {
            // Should never happen
            if (response == null) throw ex;
            logger.error("Error occurred reading response from status check, failure processing body: "
                    + response.body(), ex);
        }
        return false;
    }

    /**
     * Starts recording the live and blocks the thread while recording.
     */
    private void startRecording() {
        try {
            String url = getRecordingUrl();
            String ffmpeg = appConfig.ffmpegPath();
            if (ffmpeg == null || ffmpeg.isEmpty()) {
                ffmpeg = "ffmpeg";
            }
            String filenamePrefix = watcherConfig.outputFilenamePrefix();
            if (filenamePrefix == null || filenamePrefix.isEmpty()) {
                filenamePrefix = watcherConfig.channel();
            }
            LocalDateTime dateTime = LocalDateTime.now();
            filenamePrefix += "_" + dateTime.format(DATE_FORMATTER) + ".mkv";
            File outFile = new File(OUTPUT_DIRECTORY, filenamePrefix);
            List<String> params = new ArrayList<>();
            params.addAll(List.of(ffmpeg, "-i", url));
            List<String> copyParams;
            if (appConfig.encodeWhileDownloading()) {
                copyParams = List.of("-c:v", "libx264", "-c:a", "aac");
            } else {
                copyParams = List.of("-c", "copy");
            }
            params.addAll(copyParams);
            params.addAll(List.of("-strftime", "1", outFile.getAbsolutePath()));
            ProcessBuilder pb = new ProcessBuilder(params);
            final StringBuilder sb = new StringBuilder();
            pb.command().forEach(s -> sb.append(s).append(" "));
            logger.info("Starting process: {}", sb);
            final Process p = pb.start();
            if (watcherConfig.logFfmpegOutput()) {
                final Logger ffmpegLog = LoggerFactory.getLogger("ffmpeg");
                new Thread(new ProcessLogger(p.getInputStream(), outFile.getAbsolutePath(), ffmpegLog, false)).start();
                new Thread(new ProcessLogger(p.getErrorStream(), outFile.getAbsolutePath(), ffmpegLog, true)).start();
            }
            Thread hook = new Thread(() -> {
                try {
                    String channel = watcherConfig.channel();
                    ThreadContext.put("channel", channel);
                    logger.info("Gracefully shutting down recording for {}.", channel);
                    p.getOutputStream().write("q\n".getBytes());
                    p.getOutputStream().flush();
                } catch (IOException ex) {
                    logger.error("Error occurred while gracefully shutting down ffmpeg.", ex);
                }
            });
            Runtime.getRuntime().addShutdownHook(hook);
            p.waitFor();
            Runtime.getRuntime().removeShutdownHook(hook);
            new Thread(CONVERTER_JOB_RUNNER).start();
        } catch (InterruptedException | IOException ex) {
            logger.error("Error occurred while setting up/recording live stream.", ex);
        }
    }

    /**
     * Gets the url for recording the stream. Uses yt-dlp to get the url, expects the first line of output to be the
     * url.
     * @return The url for recording the stream.
     * @throws IOException Thrown if an error occurs while executing yt-dlp.
     */
    private String getRecordingUrl() throws IOException {
        String url = null;
        String ytdlp = appConfig.ytdlpPath();
        if (ytdlp == null || ytdlp.isEmpty()) {
            ytdlp = "yt-dlp";
        }
        // Give it multiple tries, seems that yt-dlp sometimes randomly returns null.
        for (int i = 0; i < 5; i++ ) {
            Process p = new ProcessBuilder(ytdlp, "-g", "https://www.tiktok.com/@"
                    + watcherConfig.channel() + "/live").start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                url = br.readLine();
                if (url != null) {
                    break;
                }
            }
        }
        return url;
    }

    /**
     * Runnable class for logging a processes input stream.
     */
    private class ProcessLogger implements Runnable {

        /** The input stream to log. */
        private final InputStream inputStream;
        /** The file name for the thread context. */
        private final String fileName;
        /** The logger instance to write to. */
        private final Logger logger;
        /** Is this reporting an error stream or not? */
        private final boolean error;

        /**
         * Default constructor.
         * @param inputStream The input stream to read.
         * @param fileName The file name for context.
         * @param logger The logger instance to write to.
         * @param error Is this an error stream being reported?
         */
        public ProcessLogger(InputStream inputStream, String fileName, Logger logger, boolean error) {
            this.inputStream = inputStream;
            this.fileName = fileName;
            this.logger = logger;
            this.error = error;
        }

        /**
         * Listens to the input stream and writes the output to the log.
         */
        @Override
        public void run() {
            ThreadContext.put("ffmpegFileName", fileName);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    // Skip progress lines like: frame=... fps=... size=... time=...
                    if (line.startsWith("frame=") || line.startsWith("size=") || line.startsWith("time=") || line.startsWith("bitrate=")) {
                        continue;
                    }
                    // Skip \r-only updates
                    if (!line.contains("\r")) {
                        if (error) {
                            logger.error(line);
                        } else {
                            logger.info(line);
                        }
                    }
                }
            } catch (IOException ex) {
                logger.error("Error while writing log stream.", ex);
            }
        }
    }

}
