# Klippy - 3D Printer Camera Monitor

A Spring Boot + Vaadin web application that provides a live camera feed and print statistics from a 3D printer running [Klipper](https://www.klipper3d.org/) with [Moonraker](https://moonraker.readthedocs.io/).

## Features

- **Live camera feed** — continuously fetches `camera/monitor.jpg` from Moonraker and displays it in the browser via Vaadin Push (WebSocket)
- **Camera lifecycle management** — automatically starts the camera monitor via Moonraker's JSON-RPC WebSocket API (`camera.start_monitor` / `camera.stop_monitor`), authenticated with oneshot tokens
- **Print statistics** — polls Moonraker for print state, filename, progress percentage, elapsed print time, and estimated time remaining
- **Start/Stop controls** — buttons to manually start and stop the camera feed
- **Inactivity timeout** — a configurable "Continue?" prompt appears periodically; if not acknowledged within 10 seconds, the camera stops automatically to save resources
- **Auto-detected printer name** — title is resolved from the Fluidd database (`instanceName`), falling back to the Klipper hostname, or configurable via properties

## Requirements

- Java 21+
- A 3D printer running Klipper + Moonraker (tested with Snapmaker U1)

## Quick Start

**With OIDC authentication (default):**

```bash
OIDC_CLIENT_ID=your-client-id \
OIDC_ISSUER_URI=https://your-provider.example.com \
./mvnw spring-boot:run
```

**Without authentication:**

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--global.security.enabled=false"
```

Then open [http://localhost:8080](http://localhost:8080) in your browser.

## Configuration

All settings are in `src/main/resources/application.properties` and can be overridden via command line:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--printer.host=192.168.1.100 --printer.refresh-interval-ms=500"
```

### Properties

| Property | Default         | Description |
|---|-----------------|---|
| `printer.name` | *(empty)*       | Printer display name (optional, used in title) |
| `printer.model` | *(empty)*       | Printer model (optional, appended to name in title) |
| `printer.host` | `192.168.1....` | IP address of the printer |
| `printer.port` | `7125`          | Moonraker API port |
| `printer.refresh-interval-ms` | `2000`          | Camera image refresh interval in milliseconds |
| `printer.domain` | `lan`           | Domain parameter for `camera.start_monitor` / `camera.stop_monitor` RPC calls |
| `printer.monitor-interval` | `0`             | Interval parameter for `camera.start_monitor` RPC call |
| `printer.start-cooldown-seconds` | `5`             | Minimum seconds between `camera.start_monitor` calls |
| `printer.idle-stop-seconds` | `60`            | Idle timeout before stopping the camera (0 = never) |
| `printer.stats-interval-ms` | `10000`         | How often to poll print statistics (milliseconds) |
| `printer.continue-prompt-ms` | `30000`         | How often to show the "Continue?" dialog (milliseconds) |

### Printer Name Resolution

If `printer.name` and `printer.model` are not set, the app fetches the printer name at startup:

1. Fluidd instance name from `GET /server/database/item?namespace=fluidd` (`uiSettings.general.instanceName`)
2. Klipper hostname from `GET /printer/info`
3. Falls back to "3D Printer Camera Monitor"

## OIDC Authentication

The app requires OIDC authentication — all routes are protected by default. It works with any OIDC/OAuth2 provider (Okta, Auth0, Keycloak, Google, etc.) via Spring Security's auto-discovery.

### Setup

1. Register an application with your OIDC provider
2. Set the redirect URI to: `http://localhost:8080/login/oauth2/code/oidc-provider`
3. Set the following environment variables:

```bash
export OIDC_CLIENT_ID=your-client-id
export OIDC_CLIENT_SECRET=your-client-secret
export OIDC_ISSUER_URI=https://your-provider.example.com
```

4. Run the app — opening `http://localhost:8080` will redirect to your provider's login page

### Auth Flow

1. User hits the app URL
2. Spring Security redirects to the OIDC provider
3. User authenticates with the provider
4. Provider redirects back with an authorization code
5. Spring Security exchanges the code for tokens and creates a session
6. User lands on the camera monitor view

## Architecture

```
src/main/java/com/example/klippy/
├── KlippyApplication.java              # Spring Boot entry point, Vaadin Push config
├── config/
│   ├── PrinterConfig.java              # @ConfigurationProperties record
│   └── SecurityConfig.java             # OIDC authentication via VaadinWebSecurity
├── service/
│   ├── MoonrakerWebSocketClient.java   # WebSocket JSON-RPC client (oneshot token auth)
│   ├── SnapshotService.java            # Camera control, image fetching, print stats
│   └── PrintStats.java                 # Print statistics record with time formatting
└── ui/
    └── MonitorView.java                # Vaadin view with live image, stats, controls
```

### How It Works

1. On page load, the app fetches a oneshot token from `GET /access/oneshot_token`
2. Opens a WebSocket to `ws://{host}:{port}/websocket?token={token}` and sends `camera.start_monitor` via JSON-RPC
3. Polls `GET /server/files/camera/monitor.jpg` at the configured interval and pushes each frame to the browser as a base64 data URI
4. Independently polls `GET /printer/objects/query?print_stats&virtual_sdcard` for print statistics
5. On tab close or "Stop Camera", sends `camera.stop_monitor` to shut down the camera

## Building

```bash
# Compile
./mvnw compile

# Package as JAR
./mvnw package

# Run the packaged JAR
java -jar target/klippy-0.0.1-SNAPSHOT.jar
```

### Production Build

The `production` Maven profile compiles and bundles the Vaadin frontend for optimized delivery (no dev mode tools or live reload):

```bash
# Package a production-ready JAR
./mvnw clean package -Pproduction

# Run it
java -jar target/klippy-0.0.1-SNAPSHOT.jar
```

### Docker

The included `Dockerfile` performs a multi-stage production build. Use `docker compose` with a `.env` file for configuration.

1. Copy the sample env file and fill in your values:

```bash
cp .env.sample .env
```

2. Edit `.env`:

```properties
# With OIDC authentication
PORT=8080
GLOBAL_SECURITY_ENABLED=true
OIDC_CLIENT_ID=your-client-id
OIDC_ISSUER_URI=https://your-provider.example.com
PRINTER_HOST=192.168.1.100
PRINTER_PORT=7125
```

Or to run without authentication:

```properties
PORT=8080
GLOBAL_SECURITY_ENABLED=false
PRINTER_HOST=192.168.1.100
PRINTER_PORT=7125
```

3. Build and run:

```bash
docker compose up --build
```

The `PORT` value in `.env` controls the host port (e.g. `PORT=9090` makes the app available at `http://localhost:9090`).
