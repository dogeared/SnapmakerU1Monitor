package com.example.klippy.service;

import com.example.klippy.config.PrinterConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Service
public class SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);

    private final PrinterConfig config;
    private final MoonrakerWebSocketClient wsClient;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile long lastStartTime = 0;

    public SnapshotService(PrinterConfig config, MoonrakerWebSocketClient wsClient) {
        this.config = config;
        this.wsClient = wsClient;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Sends camera.start_monitor if the cooldown period has elapsed.
     */
    public void ensureMonitorRunning() {
        long now = System.currentTimeMillis();
        long cooldownMs = config.startCooldownSeconds() * 1000L;
        if (now - lastStartTime >= cooldownMs) {
            lastStartTime = now;
            wsClient.sendRpc("camera.start_monitor", Map.of(
                    "domain", config.domain(),
                    "interval", config.monitorInterval()
            ));
        }
    }

    /**
     * Sends camera.stop_monitor to shut down the camera.
     */
    public void stopMonitor() {
        log.info("Stopping camera monitor");
        wsClient.sendRpc("camera.stop_monitor", Map.of(
                "domain", config.domain()
        ));
    }

    public byte[] fetchSnapshot() {
        String url = config.snapshotUrl();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                return response.body();
            }
            log.warn("Snapshot fetch returned status {}", response.statusCode());
        } catch (Exception e) {
            log.warn("Failed to fetch snapshot from {}: {}", url, e.getMessage());
        }
        return null;
    }

    /**
     * Fetches the printer name. Tries the Fluidd database instanceName first,
     * then falls back to the hostname from /printer/info.
     */
    public String fetchPrinterName() {
        // Try Fluidd instanceName first
        String url = config.moonrakerBaseUrl() + "/server/database/item?namespace=fluidd";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                String instanceName = root.path("result").path("value")
                        .path("uiSettings").path("general").path("instanceName").asText("");
                if (!instanceName.isEmpty()) {
                    return instanceName;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch Fluidd database: {}", e.getMessage());
        }

        // Fall back to hostname from /printer/info
        url = config.moonrakerBaseUrl() + "/printer/info";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                return root.path("result").path("hostname").asText("");
            }
        } catch (Exception e) {
            log.warn("Failed to fetch printer info: {}", e.getMessage());
        }
        return "";
    }

    /**
     * Queries Moonraker for print_stats and virtual_sdcard objects via HTTP.
     */
    public PrintStats fetchPrintStats() {
        String url = config.moonrakerBaseUrl()
                + "/printer/objects/query?print_stats&virtual_sdcard";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Print stats query returned status {}", response.statusCode());
                return null;
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode status = root.path("result").path("status");
            JsonNode ps = status.path("print_stats");
            JsonNode vs = status.path("virtual_sdcard");

            return new PrintStats(
                    ps.path("state").asText("unknown"),
                    ps.path("filename").asText(""),
                    vs.path("progress").asDouble(0.0),
                    ps.path("print_duration").asDouble(0.0),
                    ps.path("total_duration").asDouble(0.0)
            );
        } catch (Exception e) {
            log.warn("Failed to fetch print stats: {}", e.getMessage());
            return null;
        }
    }
}
