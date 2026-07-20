package pg.duplicatefileremover.helpers;

import java.io.PrintStream;
import java.time.Clock;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class TerminalProgressBar implements AutoCloseable {
    private static final int BAR_WIDTH = 24;
    private static final Duration ETA_WARM_UP = Duration.ofSeconds(5);
    private static final long RATE_SAMPLE_INTERVAL_NANOS = Duration.ofMillis(500).toNanos();
    private static final double RATE_SMOOTHING_FACTOR = 0.3;
    private static final char[] SPINNER = {'|', '/', '-', '\\'};

    private final ScanProgress progress;
    private final PrintStream output;
    private final boolean interactive;
    private final ScheduledExecutorService renderer;
    private final AtomicReference<ScanProgress.Snapshot> latestSnapshot;
    private final LongSupplier nanoTime;
    private final Clock clock;
    private final Consumer<ScanProgress.Snapshot> progressListener = this::observeProgress;
    private ScanProgress.Stage lastPrintedStage;
    private long stageStartedNanos;
    private int frame;
    private int previousLength;
    private ScanProgress.Stage rateStage = ScanProgress.Stage.NOT_STARTED;
    private long rateSampleNanos;
    private long rateSampleCompleted;
    private double smoothedRate;

    public TerminalProgressBar(ScanProgress progress) {
        this(progress, System.out, System.console() != null);
    }

    TerminalProgressBar(ScanProgress progress, PrintStream output, boolean interactive) {
        this(progress, output, interactive, System::nanoTime);
    }

    TerminalProgressBar(
            ScanProgress progress,
            PrintStream output,
            boolean interactive,
            LongSupplier nanoTime
    ) {
        this(progress, output, interactive, nanoTime, Clock.systemDefaultZone());
    }

    TerminalProgressBar(
            ScanProgress progress,
            PrintStream output,
            boolean interactive,
            LongSupplier nanoTime,
            Clock clock
    ) {
        this.progress = Objects.requireNonNull(progress, "progress");
        this.output = Objects.requireNonNull(output, "output");
        this.interactive = interactive;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.clock = Objects.requireNonNull(clock, "clock");
        latestSnapshot = new AtomicReference<>(progress.snapshot());
        stageStartedNanos = nanoTime.getAsLong();
        resetRate(progress.snapshot(), stageStartedNanos);
        renderer = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("scan-progress-renderer").factory()
        );
        progress.addListener(progressListener);
        progress.setWarningHandler(this::printWarning);
        progress.setInformationHandler(this::printInformation);
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
        progress.resetInformationHandler();
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
            case GROUPING_BY_SIZE -> determinate("Grouping by size", snapshot);
            case SAMPLING -> determinate("Sampling content", snapshot, "samples");
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

    private static String formatCompleted(
            ScanProgress.Snapshot snapshot,
            int frame,
            Duration elapsed
    ) {
        return "%s (%s)".formatted(format(snapshot, frame), formatDuration(elapsed));
    }

    private String formatActive(
            ScanProgress.Snapshot snapshot,
            int frame,
            Duration elapsed
    ) {
        return "%s (%s)".formatted(
                format(snapshot, frame),
                formatActiveTiming(snapshot, elapsed, smoothedRate, clock)
        );
    }

    static String formatActiveTiming(
            ScanProgress.Snapshot snapshot,
            Duration elapsed,
            double recentRate,
            Clock clock
    ) {
        String elapsedText = formatActiveDuration(elapsed) + " elapsed";
        if (snapshot.stage() == ScanProgress.Stage.DISCOVERING
                || snapshot.total() <= 0
                || snapshot.completed() >= snapshot.total()) {
            return elapsedText;
        }
        if (elapsed.compareTo(ETA_WARM_UP) < 0 || snapshot.completed() <= 0) {
            return elapsedText + ", ETA calculating...";
        }

        double rate = recentRate > 0
                ? recentRate
                : snapshot.completed() / Math.max(elapsed.toNanos() / 1_000_000_000.0, 0.001);
        if (!Double.isFinite(rate) || rate <= 0) {
            return elapsedText + ", ETA calculating...";
        }
        long remainingSeconds = Math.max(
                1,
                (long) Math.ceil((snapshot.total() - snapshot.completed()) / rate)
        );
        Duration remaining = Duration.ofSeconds(remainingSeconds);
        DateTimeFormatter finishTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
                .withZone(clock.getZone());
        String finishTime = finishTimeFormatter.format(clock.instant().plus(remaining));
        return "%s, about %s remaining, ends around %s".formatted(
                elapsedText,
                formatActiveDuration(remaining),
                finishTime
        );
    }

    static String formatActiveDuration(Duration duration) {
        long totalSeconds = Math.max(0, duration.toSeconds());
        long hours = totalSeconds / 3_600;
        long minutes = totalSeconds % 3_600 / 60;
        long seconds = totalSeconds % 60;
        StringJoiner formatted = new StringJoiner(" ");
        if (hours != 0) {
            formatted.add(hours + "h");
        }
        if (minutes != 0) {
            formatted.add(minutes + "m");
        }
        if (seconds != 0) {
            formatted.add(seconds + "s");
        }
        return formatted.length() == 0 ? "0s" : formatted.toString();
    }

    static String formatDuration(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();
        long millis = duration.toMillisPart();
        StringJoiner formatted = new StringJoiner(" ");
        if (hours != 0) {
            formatted.add(hours + "h");
        }
        if (minutes != 0) {
            formatted.add(minutes + "m");
        }
        if (seconds != 0) {
            formatted.add(seconds + "s");
        }
        if (millis != 0) {
            formatted.add(millis + "ms");
        }
        return formatted.length() == 0 ? "0ms" : formatted.toString();
    }

    private static String determinate(String label, ScanProgress.Snapshot snapshot) {
        return determinate(label, snapshot, "files");
    }

    private static String determinate(String label, ScanProgress.Snapshot snapshot, String unit) {
        long total = snapshot.total();
        long completed = Math.min(snapshot.completed(), total);
        int percentage = total == 0 ? 100 : (int) Math.min(100, completed * 100 / total);
        int filled = percentage * BAR_WIDTH / 100;
        String bar = "#".repeat(filled) + "-".repeat(BAR_WIDTH - filled);
        return String.format(
                Locale.ROOT,
                "%-16s [%s] %3d%%  %,d / %,d %s",
                label,
                bar,
                percentage,
                completed,
                total,
                unit
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
        if (!interactive && snapshot.stage() != ScanProgress.Stage.NOT_STARTED
                && snapshot.stage() != ScanProgress.Stage.COMPLETE
                && snapshot.stage() != ScanProgress.Stage.FAILED) {
            return;
        }
        if (!interactive && snapshot.stage() == lastPrintedStage) {
            return;
        }
        renderSnapshot(snapshot);
    }

    private void renderSnapshot(ScanProgress.Snapshot snapshot) {
        if (!interactive && snapshot.stage() == lastPrintedStage) {
            return;
        }

        String line = isWorkStage(snapshot.stage())
                ? formatActive(
                        snapshot,
                        frame++,
                        Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - stageStartedNanos))
                )
                : format(snapshot, frame++);
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
        synchronized (this) {
            long observationNanos = nanoTime.getAsLong();
            if (previous != null && previous.stage() != snapshot.stage()) {
                if (isWorkStage(previous.stage())) {
                    renderCompletedSnapshot(
                            previous,
                            Duration.ofNanos(Math.max(0, observationNanos - stageStartedNanos))
                    );
                }
                stageStartedNanos = observationNanos;
                resetRate(snapshot, observationNanos);
                if (interactive
                        || snapshot.stage() == ScanProgress.Stage.COMPLETE
                        || snapshot.stage() == ScanProgress.Stage.FAILED) {
                    renderSnapshot(snapshot);
                }
            } else {
                updateRate(snapshot, observationNanos);
            }
        }
    }

    private void resetRate(ScanProgress.Snapshot snapshot, long observationNanos) {
        rateStage = snapshot.stage();
        rateSampleNanos = observationNanos;
        rateSampleCompleted = snapshot.completed();
        smoothedRate = 0;
    }

    private void updateRate(ScanProgress.Snapshot snapshot, long observationNanos) {
        if (snapshot.stage() != rateStage || snapshot.completed() < rateSampleCompleted) {
            resetRate(snapshot, observationNanos);
            return;
        }
        long elapsedNanos = observationNanos - rateSampleNanos;
        long completedSinceSample = snapshot.completed() - rateSampleCompleted;
        if (completedSinceSample <= 0 || elapsedNanos < RATE_SAMPLE_INTERVAL_NANOS) {
            return;
        }
        double currentRate = completedSinceSample / (elapsedNanos / 1_000_000_000.0);
        smoothedRate = smoothedRate == 0
                ? currentRate
                : RATE_SMOOTHING_FACTOR * currentRate + (1 - RATE_SMOOTHING_FACTOR) * smoothedRate;
        rateSampleNanos = observationNanos;
        rateSampleCompleted = snapshot.completed();
    }

    private void renderCompletedSnapshot(ScanProgress.Snapshot snapshot, Duration elapsed) {
        String line = formatCompleted(snapshot, frame++, elapsed);
        if (interactive) {
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

    private static boolean isWorkStage(ScanProgress.Stage stage) {
        return stage != ScanProgress.Stage.NOT_STARTED
                && stage != ScanProgress.Stage.COMPLETE
                && stage != ScanProgress.Stage.FAILED;
    }

    private synchronized void printWarning(String message) {
        printMessage("Warning: " + message);
    }

    private synchronized void printInformation(String message) {
        printMessage(message);
    }

    private void printMessage(String message) {
        if (interactive) {
            output.print('\r');
            output.print(" ".repeat(previousLength));
            output.print('\r');
        }
        output.println(message);
        previousLength = 0;
        lastPrintedStage = null;
        if (interactive) {
            renderSnapshot(progress.snapshot());
        }
    }
}
