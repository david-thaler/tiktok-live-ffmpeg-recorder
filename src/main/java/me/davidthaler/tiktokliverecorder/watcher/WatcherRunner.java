package me.davidthaler.tiktokliverecorder.watcher;

import me.davidthaler.tiktokliverecorder.config.AppConfig;
import me.davidthaler.tiktokliverecorder.config.WatcherConfig;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
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
            try {
                if (userIsLive()) {
                    System.out.println("user " + watcherConfig.channel() + " is live");
                    startRecording();
                } else {
                    System.out.println("user " + watcherConfig.channel() + " is NOT live");
                    System.out.println("Checking again in " + watcherConfig.pollIntervalQty() + " "
                            + watcherConfig.pollIntervalUnit());
                }
            } catch (IOException | InterruptedException | URISyntaxException e) {
                throw new RuntimeException(e);
            }
    }

    /**
     * Checks if the user for this watcher is live.
     * @return True if the user is live, false otherwise.
     * @throws IOException Thrown on error during http call.
     * @throws InterruptedException Thrown on error during http call.
     * @throws URISyntaxException Thrown if unable to construct the URI instance.
     */
    private boolean userIsLive() throws IOException, InterruptedException, URISyntaxException {
        HttpResponse<String> response = httpClient.send(signedUrlGetter, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> signedResponse = objectMapper.readValue(response.body(), Map.class);
        String signedUrl = signedResponse.get("signed_url").toString();
        response = httpClient.send(
                HttpRequest.newBuilder(new URI(signedUrl)).build(), HttpResponse.BodyHandlers.ofString());
        Map<String, Object> responseBody = objectMapper.readValue(response.body(), Map.class);
        String roomId = ((Map) ((Map) responseBody.getOrDefault("data", Map.of()))
                .getOrDefault("user", Map.of())).getOrDefault("roomId", "NULL").toString();
        if (roomId.equals("NULL")) roomId = null;
        response = httpClient.send(HttpRequest.newBuilder(
                new URI("https://webcast.tiktok.com/webcast/room/check_alive/?aid=1988&region=CH&room_ids="
                        + roomId + "&user_is_login=true")).build(), HttpResponse.BodyHandlers.ofString());
        responseBody = objectMapper.readValue(response.body(), Map.class);
        return Boolean.parseBoolean(((List<Map>) responseBody.get("data")).getFirst().get("alive").toString());
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
            pb.command().forEach(s -> System.out.print(s + " "));
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            final Process p = pb.start();
            Thread hook = new Thread(() -> {
                try {
                    System.out.println("Gracefully shutting down recording for "
                        + watcherConfig.channel());
                    p.getOutputStream().write("q\n".getBytes());
                    p.getOutputStream().flush();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            Runtime.getRuntime().addShutdownHook(hook);
            p.waitFor();
            Runtime.getRuntime().removeShutdownHook(hook);
            new Thread(CONVERTER_JOB_RUNNER).start();
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
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

}
