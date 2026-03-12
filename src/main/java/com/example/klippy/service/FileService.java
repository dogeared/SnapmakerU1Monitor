package com.example.klippy.service;

import com.example.klippy.config.PrinterConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    private final PrinterConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, Path> readyDownloads = new ConcurrentHashMap<>();

    public interface FileProgressListener {
        void onProgress(long bytesDownloaded, long totalBytes);
    }

    public FileService(PrinterConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public Path getTempDir() {
        return Path.of(config.tempDir());
    }

    public List<FileInfo> listFiles(String root) {
        String url = config.moonrakerBaseUrl() + "/server/files/list?root=" + root;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("File list for root '{}' returned status {}", root, response.statusCode());
                return List.of();
            }
            JsonNode result = objectMapper.readTree(response.body()).path("result");
            List<FileInfo> files = new ArrayList<>();
            for (JsonNode node : result) {
                files.add(new FileInfo(
                        node.path("path").asText(),
                        root,
                        node.path("modified").asLong(),
                        node.path("size").asLong()
                ));
            }
            return files;
        } catch (Exception e) {
            log.warn("Failed to list files for root '{}': {}", root, e.getMessage());
            return List.of();
        }
    }

    public Path fetchFileToTemp(String root, String filename, Path destDir, FileProgressListener listener) throws IOException {
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        String url = config.moonrakerBaseUrl() + "/server/files/" + root + "/" + encodedFilename;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                log.warn("Fetch {}/{} returned status {}", root, filename, response.statusCode());
                return null;
            }

            long totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            Path destFile = destDir.resolve(filename);

            try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(destFile)) {
                byte[] buffer = new byte[8192];
                long bytesRead = 0;
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                    bytesRead += n;
                    if (listener != null) {
                        listener.onProgress(bytesRead, totalBytes);
                    }
                }
            }

            log.info("Downloaded {}/{} to {}", root, filename, destFile);
            return destFile;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }
    }

    public String registerDownload(Path zipFile) {
        String id = UUID.randomUUID().toString();
        readyDownloads.put(id, zipFile);
        return id;
    }

    public Path getAndRemoveDownload(String id) {
        return readyDownloads.remove(id);
    }

    public boolean deleteFile(String root, String filename) {
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        String url = config.moonrakerBaseUrl() + "/server/files/" + root + "/" + encodedFilename;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .DELETE()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                log.info("Deleted {}/{}", root, filename);
                return true;
            }
            log.warn("Delete {}/{} returned status {}", root, filename, response.statusCode());
        } catch (Exception e) {
            log.warn("Failed to delete {}/{}: {}", root, filename, e.getMessage());
        }
        return false;
    }

    public boolean renameFile(String root, String oldName, String newName) {
        String url = config.moonrakerBaseUrl() + "/server/files/move";
        try {
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "source", root + "/" + oldName,
                    "dest", root + "/" + newName
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                log.info("Renamed {}/{} to {}", root, oldName, newName);
                return true;
            }
            log.warn("Rename {}/{} returned status {}", root, oldName, response.statusCode());
        } catch (Exception e) {
            log.warn("Failed to rename {}/{}: {}", root, oldName, e.getMessage());
        }
        return false;
    }
}
