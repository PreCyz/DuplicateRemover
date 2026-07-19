package pg.duplicatefileremover.helpers;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class ScanProgress {
    private final AtomicReference<Stage> stage = new AtomicReference<>(Stage.NOT_STARTED);
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong total = new AtomicLong();
    private final AtomicLong directoriesProcessed = new AtomicLong();
    private final AtomicLong mediaFilesDiscovered = new AtomicLong();
    private final AtomicReference<Consumer<String>> warningHandler = new AtomicReference<>(System.err::println);
    private final CopyOnWriteArrayList<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();

    public ScanProgress() {
        this(ignored -> {
        });
    }

    public ScanProgress(Consumer<Snapshot> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
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

    void begin(Stage nextStage, long stageTotal) {
        completed.set(0);
        total.set(Math.max(0, stageTotal));
        stage.set(nextStage);
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
        completed.incrementAndGet();
        publish();
    }

    void complete() {
        completed.set(total.get());
        stage.set(Stage.COMPLETE);
        publish();
    }

    void failed() {
        stage.set(Stage.FAILED);
        publish();
    }

    void warning(String message) {
        warningHandler.get().accept(message);
    }

    void setWarningHandler(Consumer<String> handler) {
        warningHandler.set(Objects.requireNonNull(handler, "handler"));
    }

    void resetWarningHandler() {
        warningHandler.set(System.err::println);
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

    public enum Stage {
        NOT_STARTED,
        DISCOVERING,
        READING_METADATA,
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
