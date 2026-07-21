package pg.duplicatefileremover.helpers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import pg.duplicatefileremover.DiskType;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class ReportServer implements AutoCloseable, ReportHelper.ReportLinks {
    private static final String TOKEN_PARAMETER = "token=";
    private static final String SESSION_HEADER = "X-Report-Session";
    private static final String BOOTSTRAP_CSS_RESOURCE = "/bootstrap.min.css";
    private static final Duration DEFAULT_SESSION_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_PATH_REQUEST_BYTES = 64 * 1024;

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final DuplicateDeletionService deletionService;
    private final Path reportPath;
    private final BrowserSessionTracker sessionTracker;
    private final Consumer<Path> beforeMediaSend;
    private final Map<Path, ReportHelper.Thumbnail> thumbnailsByPath;
    private final String token = UUID.randomUUID().toString();
    private final Map<Path, String> idsByPath = new LinkedHashMap<>();
    private final Map<String, Path> mediaById = new ConcurrentHashMap<>();
    private final Map<String, ReportHelper.Thumbnail> thumbnailsById = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

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
            DuplicateDeletionService.FileDeletion fileDeletion
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
            DuplicateDeletionService.FileDeletion fileDeletion,
            Map<Path, ReportHelper.Thumbnail> thumbnails
    ) throws IOException {
        this.sessionTracker = new BrowserSessionTracker(sessionTimeout);
        this.deletionService = new DuplicateDeletionService(scanResult, diskType, beforeDelete, fileDeletion);
        this.reportPath = reportPath.toAbsolutePath().normalize();
        this.beforeMediaSend = Objects.requireNonNull(beforeMediaSend);
        Map<Path, ReportHelper.Thumbnail> normalizedThumbnails = new HashMap<>();
        Objects.requireNonNull(thumbnails, "thumbnails").forEach((path, thumbnail) ->
                normalizedThumbnails.put(
                        path.toAbsolutePath().normalize(),
                        Objects.requireNonNull(thumbnail, "thumbnail")
                )
        );
        this.thumbnailsByPath = Map.copyOf(normalizedThumbnails);
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
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
        sessionTracker.start();
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
        return deletionService.workerCount();
    }

    public CompletionStage<Void> browserSessionsEnded() {
        return sessionTracker.sessionsEnded();
    }

    public Duration heartbeatSilenceBeforeShutdown() {
        return sessionTracker.heartbeatSilenceBeforeShutdown();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        server.stop(0);
        sessionTracker.close();
        executor.close();
        deletionService.close();
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
            DuplicateDeletionService.DeleteResult deletion = deletionService.delete(duplicatePath);
            DuplicateDeletionService.Status status = deletion.status();
            if (status == DuplicateDeletionService.Status.DELETED) {
                unregisterDeletedMedia(duplicatePath);
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            } else if (status == DuplicateDeletionService.Status.NOT_FOUND) {
                send(exchange, 404, "Duplicate file no longer exists", "text/plain; charset=utf-8");
            } else if (status == DuplicateDeletionService.Status.NOT_REGISTERED) {
                send(exchange, 404, "Path is not a registered duplicate", "text/plain; charset=utf-8");
            } else if (status == DuplicateDeletionService.Status.NOT_FILE) {
                send(exchange, 409, "Duplicate path no longer identifies a regular file", "text/plain; charset=utf-8");
            } else if (status == DuplicateDeletionService.Status.CONTENT_CHANGED) {
                send(exchange, 409, "File changed after scanning and was preserved", "text/plain; charset=utf-8");
            } else if (status == DuplicateDeletionService.Status.DELETE_FAILED) {
                String reason = deletionService.takeFailureReason(duplicatePath);
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
        if ("/api/session/heartbeat".equals(path)) {
            sessionTracker.heartbeat(sessionId);
        } else if ("/api/session/deletion/start".equals(path)) {
            sessionTracker.startBulkDeletion(sessionId);
        } else if ("/api/session/deletion/finish".equals(path)) {
            sessionTracker.finishBulkDeletion(sessionId);
        } else if ("/api/session/close".equals(path)) {
            sessionTracker.closeSession(sessionId);
        } else {
            send(exchange, 404, "Not found", "text/plain; charset=utf-8");
            return;
        }
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private void removeAllDuplicates(HttpExchange exchange) throws IOException {
        DuplicateDeletionService.DeletionBatch batch = deletionService.deleteAll();

        addCommonHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
        exchange.sendResponseHeaders(200, 0);
        boolean responseConnected = true;
        try (OutputStream output = exchange.getResponseBody()) {
            for (int completed = 0; completed < batch.size(); completed++) {
                DuplicateDeletionService.DeleteResult result = awaitDeletionResult(batch.completions());
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

    private DuplicateDeletionService.DeleteResult awaitDeletionResult(
            CompletionService<DuplicateDeletionService.DeleteResult> deletions
    ) throws IOException {
        try {
            return deletions.take().get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while removing duplicates", exception);
        } catch (ExecutionException exception) {
            throw new IOException("Concurrent duplicate removal failed", exception.getCause());
        }
    }

    private String deletionResultJson(DuplicateDeletionService.DeleteResult result) {
        String path = result.path().toString();
        if (result.status() == DuplicateDeletionService.Status.DELETED) {
            unregisterDeletedMedia(result.path());
            return "{\"path\":" + jsonString(path) + ",\"deleted\":true}";
        }
        String reason = deletionService.takeFailureReason(result.path());
        String message = reason == null ? deletionStatusMessage(result.status()) : reason;
        return "{\"path\":" + jsonString(path)
                + ",\"deleted\":false,\"message\":" + jsonString(message) + "}";
    }

    private static String deletionStatusMessage(DuplicateDeletionService.Status status) {
        return switch (status) {
            case NOT_FOUND -> "Duplicate file no longer exists";
            case NOT_REGISTERED -> "Path is not a registered duplicate";
            case NOT_FILE -> "Duplicate path no longer identifies a regular file";
            case CONTENT_CHANGED -> "File changed after scanning and was preserved";
            case DELETE_FAILED -> "File could not be removed; it may be open or protected";
            case IN_PROGRESS -> "Duplicate is already being removed";
            case DELETED -> throw new IllegalArgumentException("A successful deletion has no failure message");
        };
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

    static long deletionRetryWindowMillis(DiskType diskType) {
        return DuplicateDeletionService.retryWindowMillis(diskType);
    }

    private void unregisterDeletedMedia(Path path) {
        String mediaId = idsByPath.get(path);
        if (mediaId != null) {
            mediaById.remove(mediaId, path);
            thumbnailsById.remove(mediaId);
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
        return bytes.length <= 64 && BrowserSessionTracker.isValidSessionId(sessionId) ? sessionId : null;
    }

    private boolean beginDeletionRequest(HttpExchange exchange) {
        return sessionTracker.beginDeletionRequest(exchange.getRequestHeaders().getFirst(SESSION_HEADER));
    }

    private void beginReportRequest() {
        sessionTracker.beginReportRequest();
    }

    private void finishReportRequest() {
        sessionTracker.finishReportRequest();
    }

    private void finishDeletionRequest(HttpExchange exchange) {
        sessionTracker.finishDeletionRequest(exchange.getRequestHeaders().getFirst(SESSION_HEADER));
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

}
