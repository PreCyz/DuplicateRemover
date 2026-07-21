package pg.duplicatefileremover.helpers;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class BrowserSessionTracker implements AutoCloseable {
    private static final int BULK_DELETION_TIMEOUT_MULTIPLIER = 4;
    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private final Object lock = new Object();
    private final Duration sessionTimeout;
    private final Duration bulkDeletionTimeout;
    private final ScheduledExecutorService monitor;
    private final Map<String, Long> browserSessions = new HashMap<>();
    private final Map<String, Long> bulkDeletionSessions = new HashMap<>();
    private final CompletableFuture<Void> sessionsEnded = new CompletableFuture<>();
    private boolean browserConnected;
    private long lastSessionActivityNanos;
    private long lastHeartbeatNanos;
    private long heartbeatSilenceAtShutdownNanos = -1;
    private int activeDeletionRequests;
    private int activeReportRequests;

    BrowserSessionTracker(Duration sessionTimeout) {
        if (sessionTimeout.isZero() || sessionTimeout.isNegative()) {
            throw new IllegalArgumentException("Session timeout must be positive");
        }
        this.sessionTimeout = sessionTimeout;
        this.bulkDeletionTimeout = sessionTimeout.multipliedBy(BULK_DELETION_TIMEOUT_MULTIPLIER);
        this.monitor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("report-session-monitor").factory()
        );
    }

    static boolean isValidSessionId(String sessionId) {
        return sessionId != null && SESSION_ID.matcher(sessionId).matches();
    }

    void start() {
        long intervalMillis = Math.clamp(sessionTimeout.toMillis() / 4, 25, 1_000);
        monitor.scheduleAtFixedRate(this::expireSafely, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    CompletionStage<Void> sessionsEnded() {
        return sessionsEnded.minimalCompletionStage();
    }

    Duration heartbeatSilenceBeforeShutdown() {
        synchronized (lock) {
            if (heartbeatSilenceAtShutdownNanos < 0) {
                throw new IllegalStateException("Browser-session shutdown has not been triggered");
            }
            return Duration.ofNanos(heartbeatSilenceAtShutdownNanos);
        }
    }

    void heartbeat(String sessionId) {
        synchronized (lock) {
            long now = System.nanoTime();
            browserSessions.put(sessionId, now);
            browserConnected = true;
            lastHeartbeatNanos = now;
            lastSessionActivityNanos = now;
        }
    }

    void startBulkDeletion(String sessionId) {
        synchronized (lock) {
            long now = System.nanoTime();
            browserSessions.put(sessionId, now);
            bulkDeletionSessions.put(sessionId, now);
            browserConnected = true;
            lastHeartbeatNanos = now;
            lastSessionActivityNanos = now;
        }
    }

    void finishBulkDeletion(String sessionId) {
        synchronized (lock) {
            long now = System.nanoTime();
            bulkDeletionSessions.remove(sessionId);
            browserSessions.put(sessionId, now);
            lastHeartbeatNanos = now;
            lastSessionActivityNanos = now;
        }
    }

    void closeSession(String sessionId) {
        synchronized (lock) {
            browserSessions.remove(sessionId);
            bulkDeletionSessions.remove(sessionId);
            lastSessionActivityNanos = System.nanoTime();
        }
    }

    boolean beginDeletionRequest(String sessionId) {
        synchronized (lock) {
            if (sessionsEnded.isDone()) {
                return false;
            }
            long now = System.nanoTime();
            touchRequestSession(sessionId, now);
            activeDeletionRequests++;
            lastSessionActivityNanos = now;
            return true;
        }
    }

    void finishDeletionRequest(String sessionId) {
        synchronized (lock) {
            activeDeletionRequests--;
            long now = System.nanoTime();
            touchRequestSession(sessionId, now);
            lastSessionActivityNanos = now;
        }
    }

    void beginReportRequest() {
        synchronized (lock) {
            activeReportRequests++;
            browserConnected = true;
            lastSessionActivityNanos = System.nanoTime();
        }
    }

    void finishReportRequest() {
        synchronized (lock) {
            activeReportRequests--;
            lastSessionActivityNanos = System.nanoTime();
        }
    }

    private void touchRequestSession(String sessionId, long now) {
        if (!isValidSessionId(sessionId)) {
            return;
        }
        browserSessions.put(sessionId, now);
        if (bulkDeletionSessions.containsKey(sessionId)) {
            bulkDeletionSessions.put(sessionId, now);
        }
        browserConnected = true;
        lastHeartbeatNanos = now;
    }

    private void expireSafely() {
        try {
            expire();
        } catch (RuntimeException ignored) {
            // Session monitoring must not terminate the report server.
        }
    }

    private void expire() {
        synchronized (lock) {
            long now = System.nanoTime();
            long cutoff = now - sessionTimeout.toNanos();
            long bulkDeletionCutoff = now - bulkDeletionTimeout.toNanos();
            bulkDeletionSessions.entrySet().removeIf(entry -> entry.getValue() < bulkDeletionCutoff);
            browserSessions.entrySet().removeIf(entry -> entry.getValue() < cutoff
                    && !bulkDeletionSessions.containsKey(entry.getKey()));
            if (browserConnected
                    && browserSessions.isEmpty()
                    && bulkDeletionSessions.isEmpty()
                    && activeDeletionRequests == 0
                    && activeReportRequests == 0
                    && now - lastSessionActivityNanos >= sessionTimeout.toNanos()) {
                if (heartbeatSilenceAtShutdownNanos < 0) {
                    heartbeatSilenceAtShutdownNanos = Math.max(0, now - lastHeartbeatNanos);
                }
                sessionsEnded.complete(null);
            }
        }
    }

    @Override
    public void close() {
        sessionsEnded.complete(null);
        monitor.close();
    }
}
