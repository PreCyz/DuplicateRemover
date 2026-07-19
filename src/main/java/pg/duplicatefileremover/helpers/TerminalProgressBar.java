package pg.duplicatefileremover.helpers;

import java.io.PrintStream;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class TerminalProgressBar implements AutoCloseable {
    private static final int BAR_WIDTH = 24;
    private static final char[] SPINNER = {'|', '/', '-', '\\'};

    private final ScanProgress progress;
    private final PrintStream output;
    private final boolean interactive;
    private final ScheduledExecutorService renderer;
    private final AtomicReference<ScanProgress.Snapshot> latestSnapshot;
    private final Consumer<ScanProgress.Snapshot> progressListener = this::observeProgress;
    private ScanProgress.Stage lastPrintedStage;
    private int frame;
    private int previousLength;

    public TerminalProgressBar(ScanProgress progress) {
        this(progress, System.out, System.console() != null);
    }

    TerminalProgressBar(ScanProgress progress, PrintStream output, boolean interactive) {
        this.progress = Objects.requireNonNull(progress, "progress");
        this.output = Objects.requireNonNull(output, "output");
        this.interactive = interactive;
        latestSnapshot = new AtomicReference<>(progress.snapshot());
        renderer = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("scan-progress-renderer").factory()
        );
        progress.addListener(progressListener);
        progress.setWarningHandler(this::printWarning);
        render();
        renderer.scheduleAtFixedRate(this::renderSafely, 100, 100, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        renderer.shutdownNow();
        progress.removeListener(progressListener);
        synchronized (this) {
            renderSnapshot(progress.snapshot());
            if (interactive) {
                output.println();
            }
            output.flush();
        }
        progress.resetWarningHandler();
    }

    static String format(ScanProgress.Snapshot snapshot, int frame) {
        return switch (snapshot.stage()) {
            case NOT_STARTED -> "Preparing scan...";
            case DISCOVERING -> String.format(
                    Locale.ROOT,
                    "Discovering %c  %,d directories, %,d media files",
                    SPINNER[Math.floorMod(frame, SPINNER.length)],
                    snapshot.directoriesProcessed(),
                    snapshot.mediaFilesDiscovered()
            );
            case READING_METADATA -> determinate("Reading metadata", snapshot);
            case HASHING -> determinate("Hashing", snapshot);
            case FINALIZING -> determinate("Finalizing", snapshot);
            case COMPLETE -> String.format(
                    Locale.ROOT,
                    "Scan complete: %,d media files in %,d directories",
                    snapshot.mediaFilesDiscovered(),
                    snapshot.directoriesProcessed()
            );
            case FAILED -> String.format(
                    Locale.ROOT,
                    "Scan stopped after %,d media files in %,d directories",
                    snapshot.mediaFilesDiscovered(),
                    snapshot.directoriesProcessed()
            );
        };
    }

    private static String determinate(String label, ScanProgress.Snapshot snapshot) {
        long total = snapshot.total();
        long completed = Math.min(snapshot.completed(), total);
        int percentage = total == 0 ? 100 : (int) Math.min(100, completed * 100 / total);
        int filled = percentage * BAR_WIDTH / 100;
        String bar = "#".repeat(filled) + "-".repeat(BAR_WIDTH - filled);
        return String.format(
                Locale.ROOT,
                "%-16s [%s] %3d%%  %,d / %,d files",
                label,
                bar,
                percentage,
                completed,
                total
        );
    }

    private void renderSafely() {
        try {
            render();
        } catch (RuntimeException ignored) {
            // Progress rendering must never abort a scan.
        }
    }

    synchronized void render() {
        ScanProgress.Snapshot snapshot = progress.snapshot();
        if (!interactive && snapshot.stage() == lastPrintedStage) {
            return;
        }
        renderSnapshot(snapshot);
    }

    private void renderSnapshot(ScanProgress.Snapshot snapshot) {
        if (!interactive && snapshot.stage() == lastPrintedStage) {
            return;
        }

        String line = format(snapshot, frame++);
        if (interactive) {
            if (lastPrintedStage != null && snapshot.stage() != lastPrintedStage) {
                output.println();
                previousLength = 0;
            }
            int padding = Math.max(0, previousLength - line.length());
            output.print('\r');
            output.print(line);
            output.print(" ".repeat(padding));
            previousLength = line.length();
        } else {
            output.println(line);
        }
        output.flush();
        lastPrintedStage = snapshot.stage();
    }

    private void observeProgress(ScanProgress.Snapshot snapshot) {
        ScanProgress.Snapshot previous = latestSnapshot.getAndSet(snapshot);
        if (previous != null && previous.stage() != snapshot.stage()) {
            synchronized (this) {
                renderSnapshot(previous);
                renderSnapshot(snapshot);
            }
        }
    }

    private synchronized void printWarning(String message) {
        if (interactive) {
            output.print('\r');
            output.print(" ".repeat(previousLength));
            output.print('\r');
        }
        output.println("Warning: " + message);
        previousLength = 0;
        lastPrintedStage = null;
        renderSnapshot(progress.snapshot());
    }
}
