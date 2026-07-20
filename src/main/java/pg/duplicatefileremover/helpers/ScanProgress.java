package pg.duplicatefileremover.helpers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class ScanProgress {
    private final AtomicReference<Stage> stage = new AtomicReference<>(Stage.NOT_STARTED);
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong total = new AtomicLong();
    private final AtomicLong directoriesProcessed = new AtomicLong();
    private final AtomicLong mediaFilesDiscovered = new AtomicLong();
    private final AtomicReference<Consumer<String>> warningHandler = new AtomicReference<>(System.err::println);
    private final AtomicReference<Consumer<String>> informationHandler = new AtomicReference<>(System.out::println);
    private final CopyOnWriteArrayList<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();
    private final EnumMap<Stage, Duration> stageDurations = new EnumMap<>(Stage.class);
    private final LongSupplier nanoTime;
    private long stageStartedNanos;

    public ScanProgress() {
        this(ignored -> {
        });
    }

    public ScanProgress(Consumer<Snapshot> listener) {
        this(listener, System::nanoTime);
    }

    ScanProgress(Consumer<Snapshot> listener, LongSupplier nanoTime) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        stageStartedNanos = nanoTime.getAsLong();
    }

    public Snapshot snapshot() {
        return new Snapshot(
                stage.get(),
                completed.get(),
                total.get(),
                directoriesProcessed.get(),
                mediaFilesDiscovered.get()
        );
    }

    synchronized void begin(Stage nextStage, long stageTotal) {
        completed.set(0);
        total.set(Math.max(0, stageTotal));
        transitionTo(nextStage);
        publish();
    }

    void directoryProcessed() {
        directoriesProcessed.incrementAndGet();
        publish();
    }

    void mediaFileDiscovered() {
        mediaFilesDiscovered.incrementAndGet();
        publish();
    }

    void itemCompleted() {
        itemsCompleted(1);
    }

    void itemsCompleted(long count) {
        if (count <= 0) {
            return;
        }
        completed.addAndGet(count);
        publish();
    }

    synchronized void complete() {
        completed.set(total.get());
        transitionTo(Stage.COMPLETE);
        publish();
    }

    synchronized void failed() {
        transitionTo(Stage.FAILED);
        publish();
    }

    synchronized Map<Stage, Duration> stageDurations() {
        return Collections.unmodifiableMap(new EnumMap<>(stageDurations));
    }

    void warning(String message) {
        warningHandler.get().accept(message);
    }

    void information(String message) {
        informationHandler.get().accept(message);
    }

    void setWarningHandler(Consumer<String> handler) {
        warningHandler.set(Objects.requireNonNull(handler, "handler"));
    }

    void resetWarningHandler() {
        warningHandler.set(System.err::println);
    }

    void setInformationHandler(Consumer<String> handler) {
        informationHandler.set(Objects.requireNonNull(handler, "handler"));
    }

    void resetInformationHandler() {
        informationHandler.set(System.out::println);
    }

    void addListener(Consumer<Snapshot> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    void removeListener(Consumer<Snapshot> listener) {
        listeners.remove(listener);
    }

    private void publish() {
        Snapshot snapshot = snapshot();
        for (Consumer<Snapshot> listener : listeners) {
            try {
                listener.accept(snapshot);
            } catch (RuntimeException ignored) {
                // Progress observers must never abort a scan.
            }
        }
    }

    private void transitionTo(Stage nextStage) {
        long transitionNanos = nanoTime.getAsLong();
        Stage previousStage = stage.get();
        if (isWorkStage(previousStage)) {
            stageDurations.put(
                    previousStage,
                    Duration.ofNanos(Math.max(0, transitionNanos - stageStartedNanos))
            );
        }
        stageStartedNanos = transitionNanos;
        stage.set(nextStage);
    }

    private static boolean isWorkStage(Stage stage) {
        return stage != Stage.NOT_STARTED && stage != Stage.COMPLETE && stage != Stage.FAILED;
    }

    public enum Stage {
        NOT_STARTED,
        DISCOVERING,
        GROUPING_BY_SIZE,
        SAMPLING,
        HASHING,
        FINALIZING,
        COMPLETE,
        FAILED
    }

    public record Snapshot(
            Stage stage,
            long completed,
            long total,
            long directoriesProcessed,
            long mediaFilesDiscovered
    ) {
    }
}
