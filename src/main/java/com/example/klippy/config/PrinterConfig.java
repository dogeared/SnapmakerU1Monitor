package com.example.klippy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "printer")
public record PrinterConfig(
        String name,
        String model,
        String host,
        int port,
        long refreshIntervalMs,
        String domain,
        int monitorInterval,
        int startCooldownSeconds,
        int idleStopSeconds,
        long statsIntervalMs,
        long continuePromptMs
) {
    public PrinterConfig {
        if (name == null || name.isBlank()) name = "";
        if (model == null || model.isBlank()) model = "";
        if (host == null || host.isBlank()) host = "192.168.68.105";
        if (port <= 0) port = 7125;
        if (refreshIntervalMs <= 0) refreshIntervalMs = 2000;
        if (domain == null || domain.isBlank()) domain = "lan";
        if (monitorInterval < 0) monitorInterval = 0;
        if (startCooldownSeconds <= 0) startCooldownSeconds = 5;
        if (idleStopSeconds < 0) idleStopSeconds = 60;
        if (statsIntervalMs <= 0) statsIntervalMs = 10000;
        if (continuePromptMs <= 0) continuePromptMs = 30000;
    }

    public String snapshotUrl() {
        return "http://" + host + ":" + port + "/server/files/camera/monitor.jpg";
    }

    public String moonrakerBaseUrl() {
        return "http://" + host + ":" + port;
    }
}
