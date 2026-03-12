package com.example.klippy.ui;

import com.example.klippy.service.FileInfo;
import com.example.klippy.service.FileService;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.html.UnorderedList;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.selection.SelectionEvent;
import com.vaadin.flow.router.Route;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Route(value = "files", layout = MainLayout.class)
@StyleSheet("/styles/files-view.css")
public class FilesView extends VerticalLayout {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final FileService fileService;
    private final Grid<FileInfo> grid;
    private final Grid.Column<FileInfo> actionsColumn;
    private final Select<String> rootSelect;
    private final Button downloadSelectedBtn;
    private final Button deleteSelectedBtn;
    private List<FileInfo> currentFiles = List.of();

    public FilesView(FileService fileService) {
        this.fileService = fileService;
        addClassName("files-view");

        H2 title = new H2("File Manager");

        rootSelect = new Select<>();
        rootSelect.setItems("gcodes", "camera");
        rootSelect.setValue("gcodes");
        rootSelect.setLabel("Root");
        rootSelect.addValueChangeListener(e -> refreshGrid());

        Button refreshButton = new Button("Refresh", e -> refreshGrid());

        downloadSelectedBtn = new Button("Download Selected", e -> downloadSelected());
        downloadSelectedBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        downloadSelectedBtn.setEnabled(false);

        deleteSelectedBtn = new Button("Delete Selected", e -> showBulkDeleteDialog());
        deleteSelectedBtn.addThemeVariants(ButtonVariant.LUMO_ERROR);
        deleteSelectedBtn.setEnabled(false);

        HorizontalLayout toolbar = new HorizontalLayout(rootSelect, refreshButton, downloadSelectedBtn, deleteSelectedBtn);
        toolbar.setDefaultVerticalComponentAlignment(Alignment.END);

        grid = new Grid<>();
        grid.setSelectionMode(Grid.SelectionMode.MULTI);
        grid.addSelectionListener(this::onSelectionChanged);

        grid.addColumn(FileInfo::filename).setHeader("Name").setSortable(true).setFlexGrow(3);
        grid.addColumn(FileInfo::formattedSize).setHeader("Size")
                .setComparator((a, b) -> Long.compare(a.size(), b.size()))
                .setSortable(true).setFlexGrow(1);
        grid.addColumn(file -> DATE_FMT.format(Instant.ofEpochSecond(file.modified())))
                .setHeader("Modified").setSortable(true).setFlexGrow(1);
        actionsColumn = grid.addComponentColumn(this::createActions).setHeader("Actions").setFlexGrow(1);
        grid.setWidthFull();

        add(title, toolbar, grid);
        setSizeFull();

        refreshGrid();
    }

    private void onSelectionChanged(SelectionEvent<Grid<FileInfo>, FileInfo> event) {
        int count = event.getAllSelectedItems().size();
        downloadSelectedBtn.setEnabled(count > 0);
        downloadSelectedBtn.setText(count > 0 ? "Download Selected (" + count + ")" : "Download Selected");
        deleteSelectedBtn.setEnabled(count > 0);
        deleteSelectedBtn.setText(count > 0 ? "Delete Selected (" + count + ")" : "Delete Selected");
    }

    private void refreshGrid() {
        currentFiles = fileService.listFiles(rootSelect.getValue());
        grid.setItems(currentFiles);
        grid.deselectAll();
        boolean writable = isWritableRoot();
        downloadSelectedBtn.setEnabled(false);
        downloadSelectedBtn.setText("Download Selected");
        deleteSelectedBtn.setEnabled(false);
        deleteSelectedBtn.setText("Delete Selected");
        deleteSelectedBtn.setVisible(writable);
        actionsColumn.setVisible(writable);
    }

    private boolean isWritableRoot() {
        String root = rootSelect.getValue();
        return "gcodes".equals(root) || "config".equals(root);
    }

    private HorizontalLayout createActions(FileInfo file) {
        HorizontalLayout layout = new HorizontalLayout();
        layout.setSpacing(true);

        if (isWritableRoot()) {
            Button renameBtn = new Button("Rename", e -> showRenameDialog(file));
            renameBtn.addThemeVariants(ButtonVariant.LUMO_SMALL);

            Button deleteBtn = new Button("Delete", e -> showDeleteDialog(file));
            deleteBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR);

            layout.add(renameBtn, deleteBtn);
        }

        return layout;
    }

    private void downloadSelected() {
        Set<FileInfo> selected = grid.getSelectedItems();
        if (selected.isEmpty()) {
            return;
        }

        List<FileInfo> files = new ArrayList<>(selected);
        String root = rootSelect.getValue();

        Dialog progressDialog = new Dialog();
        progressDialog.setCloseOnOutsideClick(false);
        progressDialog.setCloseOnEsc(false);
        progressDialog.setHeaderTitle("Downloading Files");

        Span overallLabel = new Span("Starting download...");
        ProgressBar overallBar = new ProgressBar(0, files.size() + 1, 0);
        overallBar.setWidthFull();

        Span fileLabel = new Span("");
        ProgressBar fileBar = new ProgressBar(0, 1, 0);
        fileBar.setWidthFull();

        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);

        Button cancelBtn = new Button("Cancel");
        cancelBtn.addThemeVariants(ButtonVariant.LUMO_ERROR);
        progressDialog.getFooter().add(cancelBtn);

        VerticalLayout content = new VerticalLayout(overallLabel, overallBar, fileLabel, fileBar);
        content.setPadding(false);
        content.setWidth("450px");
        progressDialog.add(content);
        progressDialog.open();

        UI ui = UI.getCurrent();

        Thread.startVirtualThread(() -> {
            Path tempDir = null;
            try {
                tempDir = Files.createTempDirectory(fileService.getTempDir(), "klippy-dl-");
                List<Path> tempFiles = new ArrayList<>();

                Path finalTempDir = tempDir;
                ui.access(() -> cancelBtn.addClickListener(e -> {
                    cancelled.set(true);
                    progressDialog.close();
                    cleanupTempDir(finalTempDir);
                }));

                for (int i = 0; i < files.size(); i++) {
                    if (cancelled.get()) return;

                    FileInfo fi = files.get(i);
                    int fileIndex = i;

                    ui.access(() -> {
                        overallLabel.setText("Fetching file " + (fileIndex + 1) + " of " + files.size());
                        overallBar.setValue(fileIndex);
                        fileLabel.setText(fi.filename() + " (" + fi.formattedSize() + ")");
                        fileBar.setValue(0);
                        fileBar.setMax(1);
                    });

                    Path downloaded = fileService.fetchFileToTemp(root, fi.filename(), tempDir, (bytesRead, totalBytes) -> {
                        if (cancelled.get()) return;
                        ui.access(() -> {
                            if (totalBytes > 0) {
                                fileBar.setMax(totalBytes);
                                fileBar.setValue(bytesRead);
                            } else {
                                fileBar.setIndeterminate(true);
                            }
                        });
                    });

                    if (cancelled.get()) return;

                    if (downloaded != null) {
                        tempFiles.add(downloaded);
                    }

                    ui.access(() -> {
                        fileBar.setIndeterminate(false);
                        fileBar.setMax(1);
                        fileBar.setValue(1);
                    });
                }

                if (cancelled.get()) return;

                ui.access(() -> {
                    overallLabel.setText("Creating zip file...");
                    overallBar.setValue(files.size());
                    fileLabel.setText("");
                    fileBar.setIndeterminate(true);
                });

                Path zipPath = tempDir.resolve(root + "-files.zip");
                try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
                    for (Path tf : tempFiles) {
                        zos.putNextEntry(new ZipEntry(tf.getFileName().toString()));
                        Files.copy(tf, zos);
                        zos.closeEntry();
                    }
                }

                for (Path tf : tempFiles) {
                    Files.deleteIfExists(tf);
                }

                String downloadId = fileService.registerDownload(zipPath);
                String downloadUrl = "/api/files/download-zip/" + downloadId;

                if (cancelled.get()) return;

                ui.access(() -> {
                    overallLabel.setText("Download ready!");
                    overallBar.setValue(files.size() + 1);
                    fileLabel.setText("");
                    fileBar.setIndeterminate(false);
                    fileBar.setMax(1);
                    fileBar.setValue(1);

                    progressDialog.getFooter().removeAll();
                    Button closeBtn = new Button("Close", e -> progressDialog.close());
                    progressDialog.getFooter().add(closeBtn);
                    progressDialog.setCloseOnOutsideClick(true);
                    progressDialog.setCloseOnEsc(true);

                    ui.getPage().open(downloadUrl);
                });

            } catch (Exception e) {
                Path cleanupDir = tempDir;
                ui.access(() -> {
                    progressDialog.close();
                    Notification.show("Download failed: " + e.getMessage(), 5000, Notification.Position.BOTTOM_START)
                            .addThemeVariants(NotificationVariant.LUMO_ERROR);
                });
                if (cleanupDir != null) {
                    cleanupTempDir(cleanupDir);
                }
            }
        });
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
        } catch (IOException ignored) {
        }
    }

    private void showRenameDialog(FileInfo file) {
        TextField nameField = new TextField("New name");
        nameField.setValue(file.filename());
        nameField.setWidthFull();

        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Rename " + file.filename());
        dialog.add(nameField);

        Button confirmBtn = new Button("Rename", e -> {
            String newName = nameField.getValue().trim();
            if (newName.isEmpty() || newName.equals(file.filename())) {
                dialog.close();
                return;
            }
            if (fileService.renameFile(file.root(), file.filename(), newName)) {
                Notification.show("Renamed to " + newName, 3000, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
                refreshGrid();
            } else {
                Notification.show("Rename failed", 3000, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
            dialog.close();
        });
        confirmBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button cancelBtn = new Button("Cancel", e -> dialog.close());

        dialog.getFooter().add(cancelBtn, confirmBtn);
        dialog.open();
    }

    private void showDeleteDialog(FileInfo file) {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader("Delete " + file.filename() + "?");
        dialog.setText("This cannot be undone.");
        dialog.setCancelable(true);
        dialog.setConfirmText("Delete");
        dialog.setConfirmButtonTheme("error primary");

        dialog.addConfirmListener(e -> {
            if (fileService.deleteFile(file.root(), file.filename())) {
                Notification.show("Deleted " + file.filename(), 3000, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
                refreshGrid();
            } else {
                Notification.show("Delete failed", 3000, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });

        dialog.open();
    }

    private void showBulkDeleteDialog() {
        Set<FileInfo> selected = grid.getSelectedItems();
        if (selected.isEmpty()) {
            return;
        }

        List<FileInfo> files = new ArrayList<>(selected);
        files.sort((a, b) -> a.filename().compareToIgnoreCase(b.filename()));

        UnorderedList fileList = new UnorderedList();
        files.forEach(f -> fileList.add(new ListItem(f.filename())));

        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader("Delete " + files.size() + " file" + (files.size() > 1 ? "s" : "") + "?");
        dialog.setText(new Span("The following files will be permanently deleted:"));
        dialog.setText(fileList);
        dialog.setCancelable(true);
        dialog.setConfirmText("Delete All");
        dialog.setConfirmButtonTheme("error primary");

        dialog.addConfirmListener(e -> {
            int success = 0;
            int failed = 0;
            for (FileInfo file : files) {
                if (fileService.deleteFile(file.root(), file.filename())) {
                    success++;
                } else {
                    failed++;
                }
            }
            if (failed == 0) {
                Notification.show("Deleted " + success + " file" + (success > 1 ? "s" : ""),
                                3000, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            } else {
                Notification.show("Deleted " + success + ", failed " + failed,
                                3000, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_WARNING);
            }
            refreshGrid();
        });

        dialog.open();
    }
}
