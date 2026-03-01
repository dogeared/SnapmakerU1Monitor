package com.example.klippy.ui;

import com.example.klippy.config.PrinterConfig;
import com.example.klippy.service.PrintStats;
import com.example.klippy.service.SnapshotService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;

import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Route("")
@StyleSheet("/styles/monitor-view.css")
public class MonitorView extends VerticalLayout {

    private final SnapshotService snapshotService;
    private final PrinterConfig config;
    private final Image image;
    private final Span statusLabel;
    private final AtomicLong frameCounter = new AtomicLong(0);

    // Stats labels
    private final Span stateLabel = new Span();
    private final Span filenameLabel = new Span();
    private final Span progressLabel = new Span();
    private final Span printTimeLabel = new Span();
    private final Span timeRemainingLabel = new Span();

    // Camera controls
    private final Button startButton;
    private final Button stopButton;
    private final Span inactivityMessage;

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> imageTask;
    private ScheduledFuture<?> statsTask;
    private ScheduledFuture<?> promptTask;
    private ScheduledFuture<?> promptTimeoutTask;
    private volatile boolean cameraRunning;
    private volatile boolean promptOpen;
    private ConfirmDialog activeDialog;
    private UI ui;

    public MonitorView(SnapshotService snapshotService, PrinterConfig config) {
        this.snapshotService = snapshotService;
        this.config = config;

        addClassName("monitor-view");

        H2 title = new H2("3D Printer Camera Monitor");

        statusLabel = new Span("Connecting to " + config.host() + ":" + config.port() + "...");

        startButton = new Button("Start Camera", e -> startCamera());
        startButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        startButton.setEnabled(false);

        stopButton = new Button("Stop Camera", e -> stopCamera());
        stopButton.addThemeVariants(ButtonVariant.LUMO_ERROR);

        HorizontalLayout buttonBar = new HorizontalLayout(startButton, stopButton);
        buttonBar.setSpacing(true);

        inactivityMessage = new Span();
        inactivityMessage.addClassName("inactivity-message");
        inactivityMessage.setVisible(false);

        image = new Image();
        image.setAlt("Printer camera snapshot");
        image.addClassName("camera-image");

        VerticalLayout statsPanel = buildStatsPanel();

        add(title, statusLabel, buttonBar, inactivityMessage, image, statsPanel);
    }

    private VerticalLayout buildStatsPanel() {
        VerticalLayout panel = new VerticalLayout();
        panel.addClassName("stats-panel");
        panel.setPadding(false);
        panel.setSpacing(false);

        stateLabel.setText("State: --");
        filenameLabel.setText("File: --");
        progressLabel.setText("Progress: --");

        HorizontalLayout timeRow = new HorizontalLayout();
        timeRow.addClassName("time-row");
        printTimeLabel.setText("Printing: --:--:--");
        timeRemainingLabel.setText("Remaining: --:--:--");
        printTimeLabel.addClassName("time-label");
        timeRemainingLabel.addClassName("time-label");
        timeRow.add(printTimeLabel, timeRemainingLabel);

        panel.add(stateLabel, filenameLabel, progressLabel, timeRow);
        return panel;
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        ui = attachEvent.getUI();

        executor = Executors.newScheduledThreadPool(3);

        startImagePolling();

        statsTask = executor.scheduleAtFixedRate(
                () -> updateStats(ui),
                0,
                config.statsIntervalMs(),
                TimeUnit.MILLISECONDS
        );
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        stopImagePolling();
        cancelPromptTask();
        cancelPromptTimeout();
        if (statsTask != null) {
            statsTask.cancel(true);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        snapshotService.stopMonitor();
    }

    private void startCamera() {
        inactivityMessage.setVisible(false);
        startImagePolling();
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        statusLabel.setText("Starting camera...");
    }

    private void stopCamera() {
        stopImagePolling();
        cancelPromptTask();
        snapshotService.stopMonitor();
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        cameraRunning = false;
        statusLabel.setText("Camera stopped");
    }

    private void startImagePolling() {
        if (imageTask != null) {
            imageTask.cancel(false);
        }
        imageTask = executor.scheduleAtFixedRate(
                () -> updateImage(ui),
                0,
                config.refreshIntervalMs(),
                TimeUnit.MILLISECONDS
        );
        cameraRunning = true;
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        schedulePrompt();
    }

    private void stopImagePolling() {
        if (imageTask != null) {
            imageTask.cancel(true);
            imageTask = null;
        }
    }

    private void schedulePrompt() {
        cancelPromptTask();
        promptTask = executor.schedule(
                () -> showContinuePrompt(ui),
                config.continuePromptMs(),
                TimeUnit.MILLISECONDS
        );
    }

    private void cancelPromptTask() {
        if (promptTask != null) {
            promptTask.cancel(false);
            promptTask = null;
        }
    }

    private void showContinuePrompt(UI ui) {
        if (!cameraRunning || promptOpen) {
            return;
        }
        promptOpen = true;

        // Pause image fetching while the dialog is open
        stopImagePolling();

        ui.access(() -> {
            ConfirmDialog dialog = new ConfirmDialog();
            dialog.setHeader("Continue?");
            dialog.setText("The camera feed is still running. Continue? (auto-stops in 10 seconds)");
            dialog.setCloseOnEsc(false);
            dialog.setCancelable(true);

            dialog.setConfirmText("Yes");
            dialog.setCancelText("No");

            dialog.addConfirmListener(e -> {
                cancelPromptTimeout();
                promptOpen = false;
                activeDialog = null;
                startImagePolling();
            });

            dialog.addCancelListener(e -> {
                cancelPromptTimeout();
                promptOpen = false;
                activeDialog = null;
                stopCamera();
            });

            activeDialog = dialog;
            dialog.open();

            // Auto-dismiss after 10 seconds, defaulting to "No"
            promptTimeoutTask = executor.schedule(() -> {
                if (promptOpen && activeDialog != null) {
                    ui.access(() -> {
                        activeDialog.close();
                        activeDialog = null;
                        promptOpen = false;
                        stopCamera();
                        inactivityMessage.setText("Camera paused due to inactivity");
                        inactivityMessage.setVisible(true);
                    });
                }
            }, 10, TimeUnit.SECONDS);
        });
    }

    private void cancelPromptTimeout() {
        if (promptTimeoutTask != null) {
            promptTimeoutTask.cancel(false);
            promptTimeoutTask = null;
        }
    }

    private void updateImage(UI ui) {
        snapshotService.ensureMonitorRunning();
        byte[] data = snapshotService.fetchSnapshot();
        if (data == null) {
            ui.access(() -> statusLabel.setText("Unable to reach printer at " + config.host()));
            return;
        }
        long frame = frameCounter.incrementAndGet();
        String dataUri = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(data);
        ui.access(() -> {
            image.setSrc(dataUri);
            statusLabel.setText("Connected — refreshing every " + config.refreshIntervalMs() + "ms");
        });
    }

    private void updateStats(UI ui) {
        PrintStats stats = snapshotService.fetchPrintStats();
        if (stats == null) {
            ui.access(() -> stateLabel.setText("State: unavailable"));
            return;
        }
        String state = stats.state().substring(0, 1).toUpperCase() + stats.state().substring(1);
        String filename = stats.filename().isEmpty() ? "--" : stats.filename();
        String pct = String.format("%.1f%%", stats.progress() * 100);
        String printTime = PrintStats.formatDuration(stats.printDuration());
        String remaining = PrintStats.formatDuration(stats.estimatedTimeRemaining());

        ui.access(() -> {
            stateLabel.setText("State: " + state);
            filenameLabel.setText("File: " + filename);
            progressLabel.setText("Progress: " + pct);
            printTimeLabel.setText("Printing: " + printTime);
            timeRemainingLabel.setText("Remaining: " + remaining);
        });
    }
}
