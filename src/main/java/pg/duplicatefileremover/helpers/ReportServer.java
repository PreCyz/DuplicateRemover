package pg.duplicatefileremover.helpers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;

public final class ReportServer implements AutoCloseable, ReportHelper.ReportLinks {
    private static final String TOKEN_PARAMETER = "token=";

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Path reportPath;
    private final String token = UUID.randomUUID().toString();
    private final Map<Path, String> idsByPath = new LinkedHashMap<>();
    private final Map<String, Path> mediaById = new ConcurrentHashMap<>();
    private final Map<String, RegisteredDuplicate> duplicatesById = new ConcurrentHashMap<>();

    public ReportServer(ScanResult scanResult, Path reportPath) throws IOException {
        this.reportPath = reportPath.toAbsolutePath().normalize();
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        registerFiles(scanResult);
        server.createContext("/", this::handleReport);
        server.createContext("/media/", this::handleMedia);
        server.createContext("/api/duplicates", this::handleDuplicates);
        server.setExecutor(executor);
    }

    public void start() {
        server.start();
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
    public void close() {
        server.stop(0);
        executor.close();
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
        } else {
            send(exchange, 409, "File changed since the scan and was not removed", "text/plain; charset=utf-8");
        }
    }

    private void removeAllDuplicates(HttpExchange exchange) throws IOException {
        List<String> deleted = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String id : List.copyOf(duplicatesById.keySet())) {
            if (deleteDuplicate(id) == DeleteStatus.DELETED) {
                deleted.add(id);
            } else {
                failed.add(id);
            }
        }
        String json = "{\"deletedIds\":" + jsonArray(deleted) + ",\"failedIds\":" + jsonArray(failed) + "}";
        send(exchange, 200, json, "application/json; charset=utf-8");
    }

    private synchronized DeleteStatus deleteDuplicate(String id) {
        RegisteredDuplicate duplicate = duplicatesById.get(id);
        if (duplicate == null) {
            return DeleteStatus.NOT_FOUND;
        }
        try {
            if (!Files.isRegularFile(duplicate.path(), LinkOption.NOFOLLOW_LINKS)
                    || Files.size(duplicate.path()) != duplicate.size()
                    || !FileHelper.getSHAHashForFile(duplicate.path()).equals(duplicate.hash())) {
                return DeleteStatus.CHANGED;
            }
            Files.delete(duplicate.path());
            duplicatesById.remove(id);
            mediaById.remove(id);
            return DeleteStatus.DELETED;
        } catch (IOException | NoSuchAlgorithmException exception) {
            return DeleteStatus.CHANGED;
        }
    }

    private boolean isAuthorized(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        return query != null && query.equals(TOKEN_PARAMETER + token);
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
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, DELETE, OPTIONS");
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
        CHANGED
    }
}
