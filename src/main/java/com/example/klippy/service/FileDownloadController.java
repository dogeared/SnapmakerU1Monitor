package com.example.klippy.service;

import com.example.klippy.config.PrinterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@RestController
public class FileDownloadController {

    private static final Logger log = LoggerFactory.getLogger(FileDownloadController.class);

    private final PrinterConfig config;
    private final HttpClient httpClient;
    private final FileService fileService;

    public FileDownloadController(PrinterConfig config, FileService fileService) {
        this.config = config;
        this.fileService = fileService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @GetMapping("/api/files/download")
    public ResponseEntity<byte[]> download(@RequestParam String root, @RequestParam String filename) {
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        String url = config.moonrakerBaseUrl() + "/server/files/" + root + "/" + encodedFilename;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                return ResponseEntity.status(response.statusCode()).build();
            }

            String contentDisposition = "attachment; filename=\"" + filename + "\"";
            MediaType mediaType = guessMediaType(filename);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                    .contentType(mediaType)
                    .body(response.body());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/api/files/download-zip/{id}")
    public ResponseEntity<byte[]> downloadZip(@PathVariable String id) {
        Path zipFile = fileService.getAndRemoveDownload(id);
        if (zipFile == null || !Files.exists(zipFile)) {
            return ResponseEntity.notFound().build();
        }

        try {
            byte[] zipBytes = Files.readAllBytes(zipFile);
            String zipName = zipFile.getFileName().toString();
            Path tempDir = zipFile.getParent();

            cleanupTempDir(tempDir);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + zipName + "\"")
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .body(zipBytes);
        } catch (IOException e) {
            log.error("Failed to serve zip file {}: {}", zipFile, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    private void cleanupTempDir(Path dir) {
        if (dir == null) return;
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
            log.info("Cleaned up temp directory: {}", dir);
        } catch (IOException e) {
            log.warn("Failed to clean up temp directory {}: {}", dir, e.getMessage());
        }
    }

    private MediaType guessMediaType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".mp4")) return MediaType.parseMediaType("video/mp4");
        if (lower.endsWith(".gcode")) return MediaType.APPLICATION_OCTET_STREAM;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
