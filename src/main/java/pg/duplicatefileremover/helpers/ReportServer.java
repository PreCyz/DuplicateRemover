package pg.duplicatefileremover.helpers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import pg.duplicatefileremover.DiskType;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.DosFileAttributeView;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class ReportServer implements AutoCloseable, ReportHelper.ReportLinks {
    private static final String TOKEN_PARAMETER = "token=";
    private static final String SESSION_HEADER = "X-Report-Session";
    private static final String BOOTSTRAP_CSS_RESOURCE = "/bootstrap.min.css";
    private static final Duration DEFAULT_SESSION_TIMEOUT = Duration.ofSeconds(15);
    private static final int BULK_DELETION_TIMEOUT_MULTIPLIER = 4;
    private static final int MAX_PATH_REQUEST_BYTES = 64 * 1024;
    private static final long[] HDD_DELETE_RETRY_DELAYS_MILLIS = {250, 500, 1_000, 2_000, 4_000, 8_000};
    private static final long[] NVME_DELETE_RETRY_DELAYS_MILLIS = {100, 200, 400, 800};
    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService deletionExecutor;
    private final int deletionWorkerCount;
    private final long[] deletionRetryDelaysMillis;
    private final ScheduledExecutorService sessionMonitor;
    private final Path reportPath;
    private final Duration sessionTimeout;
    private final Duration bulkDeletionTimeout;
    private final Consumer<Path> beforeDelete;
    private final Consumer<Path> beforeMediaSend;
    private final FileDeletion fileDeletion;
    private final Map<Path, ReportHelper.Thumbnail> thumbnailsByPath;
    private final Object lifecycleLock = new Object();
    private final String token = UUID.randomUUID().toString();
    private final Map<Path, String> idsByPath = new LinkedHashMap<>();
    private final Map<String, Path> mediaById = new ConcurrentHashMap<>();
    private final Map<String, ReportHelper.Thumbnail> thumbnailsById = new ConcurrentHashMap<>();
    private final Set<Path> duplicatePaths = ConcurrentHashMap.newKeySet();
    private final Map<Path, String> deletionFailureReasons = new ConcurrentHashMap<>();
    private final Set<Path> deletionsInProgress = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> browserSessions = new ConcurrentHashMap<>();
    private final Map<String, Long> bulkDeletionSessions = new ConcurrentHashMap<>();
    private final CompletableFuture<Void> browserSessionsEnded = new CompletableFuture<>();
    private final AtomicBoolean browserConnected = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong lastSessionActivityNanos = new AtomicLong();
    private final AtomicLong lastHeartbeatNanos = new AtomicLong();
    private final AtomicLong heartbeatSilenceAtShutdownNanos = new AtomicLong(-1);
    private int activeDeletionRequests;
    private int activeReportRequests;

    public ReportServer(ScanResult scanResult, Path reportPath) throws IOException {
        this(scanResult, reportPath, DiskType.HDD);
    }

    public ReportServer(ScanResult scanResult, Path reportPath, DiskType diskType) throws IOException {
        this(scanResult, reportPath, diskType, Map.of());
    }

    public ReportServer(
            ScanResult scanResult,
            Path reportPath,
            DiskType diskType,
            Map<Path, ReportHelper.Thumbnail> thumbnails
    ) throws IOException {
        this(
                scanResult,
                reportPath,
                diskType,
                DEFAULT_SESSION_TIMEOUT,
                ignored -> { },
                ignored -> { },
                Files::delete,
                thumbnails
        );
    }

    ReportServer(ScanResult scanResult, Path reportPath, Duration sessionTimeout) throws IOException {
        this(scanResult, reportPath, DiskType.HDD, sessionTimeout, ignored -> { });
    }

    ReportServer(
            ScanResult scanResult,
            Path reportPath,
            DiskType diskType,
            Duration sessionTimeout,
            Consumer<Path> beforeDelete
    ) throws IOException {
        this(scanResult, reportPath, diskType, sessionTimeout, beforeDelete, ignored -> { });
    }

    ReportServer(
            ScanResult scanResult,
            Path reportPath,
            DiskType diskType,
            Duration sessionTimeout,
            Consumer<Path> beforeDelete,
            Consumer<Path> beforeMediaSend
    ) throws IOException {
        this(scanResult, reportPath, diskType, sessionTimeout, beforeDelete, beforeMediaSend, Files::delete);
    }

    ReportServer(
            ScanResult scanResult,
            Path reportPath,
            DiskType diskType,
            Duration sessionTimeout,
            Consumer<Path> beforeDelete,
            Consumer<Path> beforeMediaSend,
            FileDeletion fileDeletion
    ) throws IOException {
        this(
                scanResult,
                reportPath,
                diskType,
                sessionTimeout,
                beforeDelete,
                beforeMediaSend,
                fileDeletion,
                Map.of()
        );
    }

    ReportServer(
            ScanResult scanResult,
            Path reportPath,
            DiskType diskType,
            Duration sessionTimeout,
            Consumer<Path> beforeDelete,
            Consumer<Path> beforeMediaSend,
            FileDeletion fileDeletion,
            Map<Path, ReportHelper.Thumbnail> thumbnails
    ) throws IOException {
        if (sessionTimeout.isZero() || sessionTimeout.isNegative()) {
            throw new IllegalArgumentException("Session timeout must be positive");
        }
        FileHelper.ScanProfile profile = FileHelper.scanProfile(Objects.requireNonNull(diskType, "diskType"));
        this.deletionWorkerCount = profile.deletionWorkers();
        this.deletionRetryDelaysMillis = deletionRetryDelays(diskType);
        this.deletionExecutor = Executors.newFixedThreadPool(
                deletionWorkerCount,
                Thread.ofVirtual().name("duplicate-deletion-", 0).factory()
        );
        this.reportPath = reportPath.toAbsolutePath().normalize();
        this.sessionTimeout = sessionTimeout;
        this.bulkDeletionTimeout = sessionTimeout.multipliedBy(BULK_DELETION_TIMEOUT_MULTIPLIER);
        this.beforeDelete = Objects.requireNonNull(beforeDelete);
        this.beforeMediaSend = Objects.requireNonNull(beforeMediaSend);
        this.fileDeletion = Objects.requireNonNull(fileDeletion);
        Map<Path, ReportHelper.Thumbnail> normalizedThumbnails = new HashMap<>();
        Objects.requireNonNull(thumbnails, "thumbnails").forEach((path, thumbnail) ->
                normalizedThumbnails.put(
                        path.toAbsolutePath().normalize(),
                        Objects.requireNonNull(thumbnail, "thumbnail")
                )
        );
        this.thumbnailsByPath = Map.copyOf(normalizedThumbnails);
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
    public boolean hasThumbnail(Path path) {
        return thumbnailsByPath.containsKey(path.toAbsolutePath().normalize());
    }

    @Override
    public String bootstrapCssUrl() {
        return apiBase() + "/assets/bootstrap.min.css?token=" + token;
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
            ReportHelper.Thumbnail originalThumbnail = thumbnailsByPath.get(original);
            if (originalThumbnail != null) {
                thumbnailsById.put(originalId, originalThumbnail);
            }
            for (Path duplicate : group.duplicates()) {
                Path normalized = duplicate.toAbsolutePath().normalize();
                String duplicateId = "d" + duplicateIndex++;
                idsByPath.put(normalized, duplicateId);
                mediaById.put(duplicateId, normalized);
                ReportHelper.Thumbnail duplicateThumbnail = thumbnailsByPath.get(normalized);
                if (duplicateThumbnail != null) {
                    thumbnailsById.put(duplicateId, duplicateThumbnail);
                }
                duplicatePaths.add(normalized);
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
        beginReportRequest();
        try {
            sendFile(exchange, reportPath, "text/html; charset=utf-8");
        } finally {
            finishReportRequest();
        }
    }

    private void handleMedia(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) || !isAuthorized(exchange)) {
            send(exchange, 403, "Forbidden", "text/plain; charset=utf-8");
            return;
        }
        String id = exchange.getRequestURI().getPath().substring("/media/".length());
        Path media = mediaById.get(id);
        ReportHelper.Thumbnail thumbnail = thumbnailsById.get(id);
        if (media == null || thumbnail == null) {
            send(exchange, 404, "Media not found", "text/plain; charset=utf-8");
            return;
        }
        if (!media.equals(mediaById.get(id))) {
            send(exchange, 404, "Media not found", "text/plain; charset=utf-8");
            return;
        }
        beforeMediaSend.accept(media);
        sendBytes(exchange, thumbnail.bytes(), thumbnail.contentType());
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

        if (!beginDeletionRequest(exchange)) {
            send(exchange, 503, "Report server is shutting down", "text/plain; charset=utf-8");
            return;
        }

        try {
            String path = exchange.getRequestURI().getPath();
            if ("/api/duplicates".equals(path)) {
                removeAllDuplicates(exchange);
                return;
            }
            if (!"/api/duplicates/path".equals(path)) {
                send(exchange, 404, "Not found", "text/plain; charset=utf-8");
                return;
            }
            Path duplicatePath = readDuplicatePath(exchange);
            if (duplicatePath == null) {
                send(exchange, 400, "Invalid duplicate path", "text/plain; charset=utf-8");
                return;
            }
            DeleteStatus status = deleteDuplicate(duplicatePath);
            if (status == DeleteStatus.DELETED) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            } else if (status == DeleteStatus.NOT_FOUND) {
                send(exchange, 404, "Duplicate file no longer exists", "text/plain; charset=utf-8");
            } else if (status == DeleteStatus.NOT_REGISTERED) {
                send(exchange, 404, "Path is not a registered duplicate", "text/plain; charset=utf-8");
            } else if (status == DeleteStatus.NOT_FILE) {
                send(exchange, 409, "Duplicate path no longer identifies a regular file", "text/plain; charset=utf-8");
            } else if (status == DeleteStatus.DELETE_FAILED) {
                String reason = deletionFailureReasons.remove(duplicatePath);
                String message = reason == null
                        ? "File could not be removed; it may be open or protected"
                        : "File could not be removed: " + reason;
                send(exchange, 409, message, "text/plain; charset=utf-8");
            } else {
                send(exchange, 409, "Duplicate is already being removed", "text/plain; charset=utf-8");
            }
        } finally {
            finishDeletionRequest(exchange);
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
            } else if ("/api/session/deletion/start".equals(path)) {
                browserSessions.put(sessionId, now);
                bulkDeletionSessions.put(sessionId, now);
                browserConnected.set(true);
                lastHeartbeatNanos.set(now);
                lastSessionActivityNanos.set(now);
            } else if ("/api/session/deletion/finish".equals(path)) {
                bulkDeletionSessions.remove(sessionId);
                browserSessions.put(sessionId, now);
                lastHeartbeatNanos.set(now);
                lastSessionActivityNanos.set(now);
            } else if ("/api/session/close".equals(path)) {
                browserSessions.remove(sessionId);
                bulkDeletionSessions.remove(sessionId);
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
        List<Path> duplicatePathsToDelete = List.copyOf(duplicatePaths);
        CompletionService<DeletionResult> deletions = new ExecutorCompletionService<>(deletionExecutor);
        for (Path duplicatePath : duplicatePathsToDelete) {
            deletions.submit(() -> new DeletionResult(duplicatePath, deleteDuplicateNow(duplicatePath)));
        }

        addCommonHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
        exchange.sendResponseHeaders(200, 0);
        boolean responseConnected = true;
        try (OutputStream output = exchange.getResponseBody()) {
            for (int completed = 0; completed < duplicatePathsToDelete.size(); completed++) {
                DeletionResult result = awaitDeletionResult(deletions);
                String line = deletionResultJson(result) + "\n";
                if (responseConnected) {
                    try {
                        output.write(line.getBytes(StandardCharsets.UTF_8));
                        output.flush();
                    } catch (IOException ignored) {
                        // The browser may close, but this server-owned job must still finish deleting its snapshot.
                        responseConnected = false;
                    }
                }
            }
        } catch (IOException ignored) {
            // A disconnected browser must not cancel an already accepted bulk deletion.
        }
    }

    private DeletionResult awaitDeletionResult(CompletionService<DeletionResult> deletions) throws IOException {
        try {
            return deletions.take().get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while removing duplicates", exception);
        } catch (ExecutionException exception) {
            throw new IOException("Concurrent duplicate removal failed", exception.getCause());
        }
    }

    private String deletionResultJson(DeletionResult result) {
        String path = result.path().toString();
        if (result.status() == DeleteStatus.DELETED) {
            return "{\"path\":" + jsonString(path) + ",\"deleted\":true}";
        }
        String reason = deletionFailureReasons.remove(result.path());
        String message = reason == null ? deletionStatusMessage(result.status()) : reason;
        return "{\"path\":" + jsonString(path)
                + ",\"deleted\":false,\"message\":" + jsonString(message) + "}";
    }

    private static String deletionStatusMessage(DeleteStatus status) {
        return switch (status) {
            case NOT_FOUND -> "Duplicate file no longer exists";
            case NOT_REGISTERED -> "Path is not a registered duplicate";
            case NOT_FILE -> "Duplicate path no longer identifies a regular file";
            case DELETE_FAILED -> "File could not be removed; it may be open or protected";
            case IN_PROGRESS -> "Duplicate is already being removed";
            case DELETED -> throw new IllegalArgumentException("A successful deletion has no failure message");
        };
    }

    private DeleteStatus deleteDuplicate(Path path) throws IOException {
        return awaitDeletion(deletionExecutor.submit(() -> deleteDuplicateNow(path)));
    }

    private DeleteStatus deleteDuplicateNow(Path path) {
        if (!duplicatePaths.contains(path)) {
            return DeleteStatus.NOT_REGISTERED;
        }
        if (!deletionsInProgress.add(path)) {
            return DeleteStatus.IN_PROGRESS;
        }
        deletionFailureReasons.remove(path);
        try {
            if (!duplicatePaths.contains(path)) {
                return DeleteStatus.NOT_REGISTERED;
            }
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return DeleteStatus.NOT_FOUND;
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return DeleteStatus.NOT_FILE;
            }
            beforeDelete.accept(path);
            try {
                deleteFileWithRetries(path);
            } catch (NoSuchFileException exception) {
                return DeleteStatus.NOT_FOUND;
            } catch (IOException exception) {
                String reason = deletionFailureReason(exception);
                deletionFailureReasons.put(path, reason);
                System.err.printf(
                        "Could not remove [%s] after %d attempts: %s%n",
                        path,
                        deletionRetryDelaysMillis.length + 1,
                        reason
                );
                return DeleteStatus.DELETE_FAILED;
            }
            duplicatePaths.remove(path);
            String mediaId = idsByPath.get(path);
            if (mediaId != null) {
                mediaById.remove(mediaId, path);
                thumbnailsById.remove(mediaId);
            }
            return DeleteStatus.DELETED;
        } finally {
            deletionsInProgress.remove(path);
        }
    }

    private static Path readDuplicatePath(HttpExchange exchange) throws IOException {
        byte[] bytes;
        try (InputStream input = exchange.getRequestBody()) {
            bytes = input.readNBytes(MAX_PATH_REQUEST_BYTES + 1);
        }
        if (bytes.length == 0 || bytes.length > MAX_PATH_REQUEST_BYTES) {
            return null;
        }
        try {
            Path path = Path.of(new String(bytes, StandardCharsets.UTF_8));
            return path.isAbsolute() ? path.toAbsolutePath().normalize() : null;
        } catch (InvalidPathException exception) {
            return null;
        }
    }

    private static String deletionFailureReason(IOException exception) {
        if (exception instanceof FileSystemException fileSystemException
                && fileSystemException.getReason() != null
                && !fileSystemException.getReason().isBlank()) {
            return fileSystemException.getReason();
        }
        if (exception instanceof FileSystemException) {
            return exception.getClass().getSimpleName()
                    + " (the filesystem supplied no reason; the file may be open, protected, or unavailable on the network)";
        }
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    static long deletionRetryWindowMillis(DiskType diskType) {
        return Arrays.stream(deletionRetryDelays(diskType)).sum();
    }

    private static long[] deletionRetryDelays(DiskType diskType) {
        return switch (diskType) {
            case HDD -> HDD_DELETE_RETRY_DELAYS_MILLIS.clone();
            case NVME -> NVME_DELETE_RETRY_DELAYS_MILLIS.clone();
        };
    }

    private void deleteFileWithRetries(Path path) throws IOException {
        boolean restoreReadOnly = clearDosReadOnly(path);
        IOException lastFailure = null;
        try {
            for (int attempt = 0; attempt <= deletionRetryDelaysMillis.length; attempt++) {
                try {
                    fileDeletion.delete(path);
                    return;
                } catch (NoSuchFileException exception) {
                    throw exception;
                } catch (IOException exception) {
                    lastFailure = exception;
                    if (attempt == deletionRetryDelaysMillis.length) {
                        break;
                    }
                    try {
                        Thread.sleep(deletionRetryDelaysMillis[attempt]);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while retrying duplicate removal", interrupted);
                    }
                }
            }
            throw Objects.requireNonNull(lastFailure);
        } catch (IOException exception) {
            if (restoreReadOnly && Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    setDosReadOnly(path, true);
                } catch (IOException restoreFailure) {
                    exception.addSuppressed(restoreFailure);
                }
            }
            throw exception;
        }
    }

    private static boolean clearDosReadOnly(Path path) {
        try {
            DosFileAttributeView attributes = Files.getFileAttributeView(
                    path,
                    DosFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            if (attributes == null || !attributes.readAttributes().isReadOnly()) {
                return false;
            }
            attributes.setReadOnly(false);
            return true;
        } catch (IOException | UnsupportedOperationException exception) {
            return false;
        }
    }

    private static void setDosReadOnly(Path path, boolean readOnly) throws IOException {
        DosFileAttributeView attributes = Files.getFileAttributeView(
                path,
                DosFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (attributes != null) {
            attributes.setReadOnly(readOnly);
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
            long bulkDeletionCutoff = now - bulkDeletionTimeout.toNanos();
            synchronized (lifecycleLock) {
                bulkDeletionSessions.entrySet().removeIf(entry -> entry.getValue() < bulkDeletionCutoff);
                browserSessions.entrySet().removeIf(entry -> entry.getValue() < cutoff
                        && !bulkDeletionSessions.containsKey(entry.getKey()));
                if (browserConnected.get()
                        && browserSessions.isEmpty()
                        && bulkDeletionSessions.isEmpty()
                        && activeDeletionRequests == 0
                        && activeReportRequests == 0
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

    private boolean beginDeletionRequest(HttpExchange exchange) {
        synchronized (lifecycleLock) {
            if (browserSessionsEnded.isDone()) {
                return false;
            }
            long now = System.nanoTime();
            String sessionId = exchange.getRequestHeaders().getFirst(SESSION_HEADER);
            if (sessionId != null && SESSION_ID.matcher(sessionId).matches()) {
                browserSessions.put(sessionId, now);
                if (bulkDeletionSessions.containsKey(sessionId)) {
                    bulkDeletionSessions.put(sessionId, now);
                }
                browserConnected.set(true);
                lastHeartbeatNanos.set(now);
            }
            activeDeletionRequests++;
            lastSessionActivityNanos.set(now);
            return true;
        }
    }

    private void beginReportRequest() {
        synchronized (lifecycleLock) {
            long now = System.nanoTime();
            activeReportRequests++;
            browserConnected.set(true);
            lastSessionActivityNanos.set(now);
        }
    }

    private void finishReportRequest() {
        synchronized (lifecycleLock) {
            activeReportRequests--;
            lastSessionActivityNanos.set(System.nanoTime());
        }
    }

    private void finishDeletionRequest(HttpExchange exchange) {
        synchronized (lifecycleLock) {
            activeDeletionRequests--;
            long now = System.nanoTime();
            String sessionId = exchange.getRequestHeaders().getFirst(SESSION_HEADER);
            if (sessionId != null && SESSION_ID.matcher(sessionId).matches()) {
                browserSessions.put(sessionId, now);
                if (bulkDeletionSessions.containsKey(sessionId)) {
                    bulkDeletionSessions.put(sessionId, now);
                }
                lastHeartbeatNanos.set(now);
            }
            lastSessionActivityNanos.set(now);
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
        sendBytes(exchange, bytes, contentType);
    }

    private void sendBytes(HttpExchange exchange, byte[] bytes, String contentType) throws IOException {
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
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", SESSION_HEADER);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
    }

    private static String jsonString(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append("\\u%04x".formatted((int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    @FunctionalInterface
    interface FileDeletion {
        void delete(Path path) throws IOException;
    }

    private record DeletionResult(Path path, DeleteStatus status) { }

    private enum DeleteStatus {
        DELETED,
        NOT_FOUND,
        NOT_REGISTERED,
        NOT_FILE,
        DELETE_FAILED,
        IN_PROGRESS
    }
}
