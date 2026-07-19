package pg.duplicatefileremover.helpers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import pg.duplicatefileremover.DiskType;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class ReportServer implements AutoCloseable, ReportHelper.ReportLinks {
    private static final String TOKEN_PARAMETER = "token=";
    private static final String BOOTSTRAP_CSS_RESOURCE = "/bootstrap.min.css";
    private static final Duration DEFAULT_SESSION_TIMEOUT = Duration.ofSeconds(15);
    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService deletionExecutor;
    private final int deletionWorkerCount;
    private final int deletionHashBufferSize;
    private final ScheduledExecutorService sessionMonitor;
    private final Path reportPath;
    private final Duration sessionTimeout;
    private final Consumer<Path> beforeDelete;
    private final Object lifecycleLock = new Object();
    private final String token = UUID.randomUUID().toString();
    private final Map<Path, String> idsByPath = new LinkedHashMap<>();
    private final Map<String, Path> mediaById = new ConcurrentHashMap<>();
    private final Map<String, RegisteredDuplicate> duplicatesById = new ConcurrentHashMap<>();
    private final Set<String> deletionsInProgress = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> browserSessions = new ConcurrentHashMap<>();
    private final CompletableFuture<Void> browserSessionsEnded = new CompletableFuture<>();
    private final AtomicBoolean browserConnected = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong lastSessionActivityNanos = new AtomicLong();
    private final AtomicLong lastHeartbeatNanos = new AtomicLong();
    private final AtomicLong heartbeatSilenceAtShutdownNanos = new AtomicLong(-1);
    private int activeDeletionRequests;

    public ReportServer(ScanResult scanResult, Path reportPath) throws IOException {
        this(scanResult, reportPath, DiskType.HDD);
    }

    public ReportServer(ScanResult scanResult, Path reportPath, DiskType diskType) throws IOException {
        this(scanResult, reportPath, diskType, DEFAULT_SESSION_TIMEOUT, ignored -> { });
    }

    ReportServer(ScanResult scanResult, Path reportPath, Duration sessionTimeout) throws IOException {
        this(scanResult, reportPath, DiskType.HDD, sessionTimeout, ignored -> { });
    }

    ReportServer(
            ScanResult scanResult,
            Path reportPath,
            Duration sessionTimeout,
            Consumer<Path> beforeDelete
    ) throws IOException {
        this(scanResult, reportPath, DiskType.HDD, sessionTimeout, beforeDelete);
    }

    ReportServer(
            ScanResult scanResult,
            Path reportPath,
            DiskType diskType,
            Duration sessionTimeout,
            Consumer<Path> beforeDelete
    ) throws IOException {
        if (sessionTimeout.isZero() || sessionTimeout.isNegative()) {
            throw new IllegalArgumentException("Session timeout must be positive");
        }
        FileHelper.ScanProfile profile = FileHelper.scanProfile(Objects.requireNonNull(diskType, "diskType"));
        this.deletionWorkerCount = profile.deletionWorkers();
        this.deletionHashBufferSize = profile.deletionHashBufferSize();
        this.deletionExecutor = Executors.newFixedThreadPool(
                deletionWorkerCount,
                Thread.ofVirtual().name("duplicate-deletion-", 0).factory()
        );
        this.reportPath = reportPath.toAbsolutePath().normalize();
        this.sessionTimeout = sessionTimeout;
        this.beforeDelete = Objects.requireNonNull(beforeDelete);
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        this.sessionMonitor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("report-session-monitor").factory()
        );
        registerFiles(scanResult);
        server.createContext("/", this::handleReport);
        server.createContext("/assets/", this::handleAsset);
        server.createContext("/media/", this::handleMedia);
        server.createContext("/api/duplicates", this::handleDuplicates);
        server.createContext("/api/session", this::handleSession);
        server.setExecutor(executor);
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        server.start();
        long monitorIntervalMillis = Math.clamp(sessionTimeout.toMillis() / 4, 25, 1_000);
        sessionMonitor.scheduleAtFixedRate(
                this::expireBrowserSessions,
                monitorIntervalMillis,
                monitorIntervalMillis,
                TimeUnit.MILLISECONDS
        );
    }

    public URI reportUri() {
        return URI.create(apiBase() + "/?token=" + token);
    }

    @Override
    public String mediaUrl(Path path) {
        String id = idsByPath.get(path.toAbsolutePath().normalize());
        if (id == null) {
            throw new IllegalArgumentException("Path is not registered in this report: " + path);
        }
        return apiBase() + "/media/" + id + "?token=" + token;
    }

    @Override
    public String bootstrapCssUrl() {
        return apiBase() + "/assets/bootstrap.min.css?token=" + token;
    }

    @Override
    public String duplicateId(Path path) {
        String id = idsByPath.get(path.toAbsolutePath().normalize());
        if (id == null || !duplicatesById.containsKey(id)) {
            throw new IllegalArgumentException("Path is not a registered duplicate: " + path);
        }
        return id;
    }

    @Override
    public String apiBase() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public String apiToken() {
        return token;
    }

    @Override
    public int deletionWorkerCount() {
        return deletionWorkerCount;
    }

    public CompletionStage<Void> browserSessionsEnded() {
        return browserSessionsEnded.minimalCompletionStage();
    }

    public Duration heartbeatSilenceBeforeShutdown() {
        long silenceNanos = heartbeatSilenceAtShutdownNanos.get();
        if (silenceNanos < 0) {
            throw new IllegalStateException("Browser-session shutdown has not been triggered");
        }
        return Duration.ofNanos(silenceNanos);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        browserSessionsEnded.complete(null);
        server.stop(0);
        sessionMonitor.close();
        executor.close();
        deletionExecutor.close();
    }

    private void registerFiles(ScanResult scanResult) {
        int mediaIndex = 0;
        int duplicateIndex = 0;
        for (DuplicateGroup group : scanResult.duplicateGroups()) {
            Path original = group.original().toAbsolutePath().normalize();
            String originalId = "m" + mediaIndex++;
            idsByPath.put(original, originalId);
            mediaById.put(originalId, original);
            for (Path duplicate : group.duplicates()) {
                Path normalized = duplicate.toAbsolutePath().normalize();
                String duplicateId = "d" + duplicateIndex++;
                idsByPath.put(normalized, duplicateId);
                mediaById.put(duplicateId, normalized);
                duplicatesById.put(duplicateId, new RegisteredDuplicate(normalized, group.fileSize(), group.hash()));
            }
        }
    }

    private void handleReport(HttpExchange exchange) throws IOException {
        if (!"/".equals(exchange.getRequestURI().getPath()) || !"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 404, "Not found", "text/plain; charset=utf-8");
            return;
        }
        if (!isAuthorized(exchange)) {
            send(exchange, 403, "Forbidden", "text/plain; charset=utf-8");
            return;
        }
        sendFile(exchange, reportPath, "text/html; charset=utf-8");
    }

    private void handleMedia(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) || !isAuthorized(exchange)) {
            send(exchange, 403, "Forbidden", "text/plain; charset=utf-8");
            return;
        }
        String id = exchange.getRequestURI().getPath().substring("/media/".length());
        Path media = mediaById.get(id);
        if (media == null || !Files.isRegularFile(media, LinkOption.NOFOLLOW_LINKS)) {
            send(exchange, 404, "Media not found", "text/plain; charset=utf-8");
            return;
        }
        String contentType = Files.probeContentType(media);
        sendFile(exchange, media, contentType == null ? "application/octet-stream" : contentType);
    }

    private void handleAsset(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) || !isAuthorized(exchange)) {
            send(exchange, 403, "Forbidden", "text/plain; charset=utf-8");
            return;
        }
        if (!"/assets/bootstrap.min.css".equals(exchange.getRequestURI().getPath())) {
            send(exchange, 404, "Asset not found", "text/plain; charset=utf-8");
            return;
        }
        sendResource(exchange, BOOTSTRAP_CSS_RESOURCE, "text/css; charset=utf-8");
    }

    private void handleDuplicates(HttpExchange exchange) throws IOException {
        addCommonHeaders(exchange);
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        if (!"DELETE".equals(exchange.getRequestMethod()) || !isAuthorized(exchange)) {
            send(exchange, 403, "Forbidden", "text/plain; charset=utf-8");
            return;
        }

        if (!beginDeletionRequest()) {
            send(exchange, 503, "Report server is shutting down", "text/plain; charset=utf-8");
            return;
        }

        try {
            String path = exchange.getRequestURI().getPath();
            if ("/api/duplicates".equals(path)) {
                removeAllDuplicates(exchange);
                return;
            }
            String prefix = "/api/duplicates/";
            if (!path.startsWith(prefix) || path.length() == prefix.length()) {
                send(exchange, 404, "Not found", "text/plain; charset=utf-8");
                return;
            }
            String id = path.substring(prefix.length());
            DeleteStatus status = deleteDuplicate(id);
            if (status == DeleteStatus.DELETED) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            } else if (status == DeleteStatus.NOT_FOUND) {
                send(exchange, 404, "Duplicate not found", "text/plain; charset=utf-8");
            } else if (status == DeleteStatus.CHANGED) {
                send(exchange, 409, "File changed since the scan and was not removed", "text/plain; charset=utf-8");
            } else {
                send(exchange, 409, "Duplicate is already being removed", "text/plain; charset=utf-8");
            }
        } finally {
            finishDeletionRequest();
        }
    }

    private void handleSession(HttpExchange exchange) throws IOException {
        addCommonHeaders(exchange);
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod()) || !isAuthorized(exchange)) {
            send(exchange, 403, "Forbidden", "text/plain; charset=utf-8");
            return;
        }

        String sessionId = readSessionId(exchange);
        if (sessionId == null) {
            send(exchange, 400, "Invalid browser session", "text/plain; charset=utf-8");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        long now = System.nanoTime();
        synchronized (lifecycleLock) {
            if ("/api/session/heartbeat".equals(path)) {
                browserSessions.put(sessionId, now);
                browserConnected.set(true);
                lastHeartbeatNanos.set(now);
                lastSessionActivityNanos.set(now);
            } else if ("/api/session/close".equals(path)) {
                browserSessions.remove(sessionId);
                lastSessionActivityNanos.set(now);
            } else {
                send(exchange, 404, "Not found", "text/plain; charset=utf-8");
                return;
            }
        }
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private void removeAllDuplicates(HttpExchange exchange) throws IOException {
        List<String> deleted = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Map<String, Future<DeleteStatus>> deletions = new LinkedHashMap<>();
        for (String id : List.copyOf(duplicatesById.keySet())) {
            deletions.put(id, deletionExecutor.submit(() -> deleteDuplicateNow(id)));
        }
        for (Map.Entry<String, Future<DeleteStatus>> deletion : deletions.entrySet()) {
            if (awaitDeletion(deletion.getValue()) == DeleteStatus.DELETED) {
                String id = deletion.getKey();
                deleted.add(id);
            } else {
                failed.add(deletion.getKey());
            }
        }
        String json = "{\"deletedIds\":" + jsonArray(deleted) + ",\"failedIds\":" + jsonArray(failed) + "}";
        send(exchange, 200, json, "application/json; charset=utf-8");
    }

    private DeleteStatus deleteDuplicate(String id) throws IOException {
        return awaitDeletion(deletionExecutor.submit(() -> deleteDuplicateNow(id)));
    }

    private DeleteStatus deleteDuplicateNow(String id) {
        if (!deletionsInProgress.add(id)) {
            return DeleteStatus.IN_PROGRESS;
        }
        try {
            RegisteredDuplicate duplicate = duplicatesById.get(id);
            if (duplicate == null) {
                return DeleteStatus.NOT_FOUND;
            }
            if (!Files.isRegularFile(duplicate.path(), LinkOption.NOFOLLOW_LINKS)
                    || Files.size(duplicate.path()) != duplicate.size()
                    || !FileHelper.getSHAHashForFile(duplicate.path(), deletionHashBufferSize)
                            .equals(duplicate.hash())) {
                return DeleteStatus.CHANGED;
            }
            beforeDelete.accept(duplicate.path());
            Files.delete(duplicate.path());
            duplicatesById.remove(id, duplicate);
            mediaById.remove(id, duplicate.path());
            return DeleteStatus.DELETED;
        } catch (IOException | NoSuchAlgorithmException exception) {
            return DeleteStatus.CHANGED;
        } finally {
            deletionsInProgress.remove(id);
        }
    }

    private DeleteStatus awaitDeletion(Future<DeleteStatus> deletion) throws IOException {
        try {
            return deletion.get();
        } catch (InterruptedException exception) {
            deletion.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while removing duplicate", exception);
        } catch (ExecutionException exception) {
            throw new IOException("Concurrent duplicate removal failed", exception.getCause());
        }
    }

    private boolean isAuthorized(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        return query != null && query.equals(TOKEN_PARAMETER + token);
    }

    private String readSessionId(HttpExchange exchange) throws IOException {
        byte[] bytes;
        try (InputStream input = exchange.getRequestBody()) {
            bytes = input.readNBytes(65);
        }
        String sessionId = new String(bytes, StandardCharsets.UTF_8);
        return bytes.length <= 64 && SESSION_ID.matcher(sessionId).matches() ? sessionId : null;
    }

    private void expireBrowserSessions() {
        try {
            long now = System.nanoTime();
            long cutoff = now - sessionTimeout.toNanos();
            synchronized (lifecycleLock) {
                browserSessions.entrySet().removeIf(entry -> entry.getValue() < cutoff);
                if (browserConnected.get()
                        && browserSessions.isEmpty()
                        && activeDeletionRequests == 0
                        && now - lastSessionActivityNanos.get() >= sessionTimeout.toNanos()) {
                    heartbeatSilenceAtShutdownNanos.compareAndSet(
                            -1,
                            Math.max(0, now - lastHeartbeatNanos.get())
                    );
                    browserSessionsEnded.complete(null);
                }
            }
        } catch (RuntimeException ignored) {
            // Session monitoring must not terminate the report server.
        }
    }

    private boolean beginDeletionRequest() {
        synchronized (lifecycleLock) {
            if (browserSessionsEnded.isDone()) {
                return false;
            }
            activeDeletionRequests++;
            lastSessionActivityNanos.set(System.nanoTime());
            return true;
        }
    }

    private void finishDeletionRequest() {
        synchronized (lifecycleLock) {
            activeDeletionRequests--;
            lastSessionActivityNanos.set(System.nanoTime());
        }
    }

    private void sendFile(HttpExchange exchange, Path path, String contentType) throws IOException {
        addCommonHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        long size = Files.size(path);
        exchange.sendResponseHeaders(200, size);
        try (OutputStream output = exchange.getResponseBody()) {
            Files.copy(path, output);
        }
    }

    private void sendResource(HttpExchange exchange, String resourceName, String contentType) throws IOException {
        byte[] bytes;
        try (InputStream input = ReportServer.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                send(exchange, 500, "Packaged asset is missing", "text/plain; charset=utf-8");
                return;
            }
            bytes = input.readAllBytes();
        }
        addCommonHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void send(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        addCommonHeaders(exchange);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void addCommonHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
    }

    private String jsonArray(List<String> values) {
        return values.stream().map(value -> "\"" + value + "\"").collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private record RegisteredDuplicate(Path path, long size, String hash) {
    }

    private enum DeleteStatus {
        DELETED,
        NOT_FOUND,
        CHANGED,
        IN_PROGRESS
    }
}
