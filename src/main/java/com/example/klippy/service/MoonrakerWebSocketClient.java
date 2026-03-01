package com.example.klippy.service;

import com.example.klippy.config.PrinterConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.websocket.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Sends JSON-RPC commands to Moonraker over WebSocket.
 * Each call opens a fresh connection (matching the working Python reference),
 * authenticated via a oneshot token from /access/oneshot_token.
 */
@Component
public class MoonrakerWebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(MoonrakerWebSocketClient.class);

    private final PrinterConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;

    public MoonrakerWebSocketClient(PrinterConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Opens a fresh WebSocket, sends a single JSON-RPC request, then closes.
     */
    public void sendRpc(String method, Map<String, Object> params) {
        try {
            String token = fetchOneshotToken();
            String wsUrl = "ws://" + config.host() + ":" + config.port() + "/websocket?token=" + token;

            Map<String, Object> payload = Map.of(
                    "jsonrpc", "2.0",
                    "method", method,
                    "params", params != null ? params : Map.of(),
                    "id", System.currentTimeMillis()
            );
            String json = objectMapper.writeValueAsString(payload);

            log.info("Sending RPC: {} to {}", method, wsUrl);

            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            ClientEndpointConfig endpointConfig = ClientEndpointConfig.Builder.create().build();
            try (Session session = container.connectToServer(new Endpoint() {
                @Override
                public void onOpen(Session s, EndpointConfig c) {
                    // no-op
                }
            }, endpointConfig, URI.create(wsUrl))) {
                session.getBasicRemote().sendText(json);
                // Brief pause to let the message flush before close, matching Python script behavior
                Thread.sleep(1000);
            }

            log.info("RPC {} sent successfully", method);
        } catch (Exception e) {
            log.warn("Failed to send RPC {}: {}", method, e.getMessage());
        }
    }

    private String fetchOneshotToken() throws Exception {
        String url = config.moonrakerBaseUrl() + "/access/oneshot_token";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to get oneshot token, status: " + response.statusCode());
        }
        String body = response.body();
        // Response is {"result": "TOKEN_STRING"}
        String token = objectMapper.readTree(body).path("result").asText();
        log.debug("Obtained oneshot token");
        return token;
    }
}
