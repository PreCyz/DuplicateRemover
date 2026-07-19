package pg.duplicatefileremover.helpers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ReportServerTest {

    @Test
    void servesPackagedBootstrapStylesheet(@TempDir Path tempDir) throws Exception {
        ScanResult result = new ScanResult(0, List.of(), Duration.ZERO);
        Path report = Files.writeString(tempDir.resolve("report.html"), "<html>report</html>");

        try (ReportServer server = new ReportServer(result, report);
             HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(server.bootstrapCssUrl())).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("text/css");
            assertThat(response.body()).contains("Bootstrap  v5.3.8");
        }
    }

    @Test
    void removesRegisteredDuplicateAndPreservesOrigin(@TempDir Path tempDir) throws Exception {
        Path original = Files.writeString(tempDir.resolve("origin.jpg"), "same-content");
        Path duplicate = Files.writeString(tempDir.resolve("duplicate.jpg"), "same-content");
        String hash = FileHelper.getSHAHashForFile(original);
        ScanResult result = new ScanResult(
                2,
                List.of(new DuplicateGroup(hash, Files.size(original), original, List.of(duplicate))),
                Duration.ZERO
        );
        Path report = Files.writeString(tempDir.resolve("report.html"), "<html>report</html>");

        try (ReportServer server = new ReportServer(result, report);
             HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            String duplicateId = server.duplicateId(duplicate);
            URI deleteUri = URI.create(server.apiBase() + "/api/duplicates/" + duplicateId + "?token=" + server.apiToken());
            HttpResponse<Void> response = client.send(
                    HttpRequest.newBuilder(deleteUri).DELETE().build(),
                    HttpResponse.BodyHandlers.discarding()
            );

            assertThat(response.statusCode()).isEqualTo(204);
            assertThat(original).exists();
            assertThat(duplicate).doesNotExist();
        }
    }

    @Test
    void preservesDuplicateChangedAfterScan(@TempDir Path tempDir) throws Exception {
        Path original = Files.writeString(tempDir.resolve("origin.jpg"), "same-content");
        Path duplicate = Files.writeString(tempDir.resolve("duplicate.jpg"), "same-content");
        String hash = FileHelper.getSHAHashForFile(original);
        ScanResult result = new ScanResult(
                2,
                List.of(new DuplicateGroup(hash, Files.size(original), original, List.of(duplicate))),
                Duration.ZERO
        );
        Path report = Files.writeString(tempDir.resolve("report.html"), "<html>report</html>");

        try (ReportServer server = new ReportServer(result, report);
             HttpClient client = HttpClient.newHttpClient()) {
            Files.writeString(duplicate, "changed-content");
            server.start();
            URI deleteUri = URI.create(server.apiBase() + "/api/duplicates/" + server.duplicateId(duplicate) + "?token=" + server.apiToken());
            HttpResponse<Void> response = client.send(
                    HttpRequest.newBuilder(deleteUri).DELETE().build(),
                    HttpResponse.BodyHandlers.discarding()
            );

            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(original).exists();
            assertThat(duplicate).exists();
        }
    }

    @Test
    void removesAllRegisteredDuplicatesAndPreservesOrigin(@TempDir Path tempDir) throws Exception {
        Path original = Files.writeString(tempDir.resolve("origin.jpg"), "same-content");
        Path firstDuplicate = Files.writeString(tempDir.resolve("first-copy.jpg"), "same-content");
        Path secondDuplicate = Files.writeString(tempDir.resolve("second-copy.jpg"), "same-content");
        String hash = FileHelper.getSHAHashForFile(original);
        ScanResult result = new ScanResult(
                3,
                List.of(new DuplicateGroup(
                        hash,
                        Files.size(original),
                        original,
                        List.of(firstDuplicate, secondDuplicate)
                )),
                Duration.ZERO
        );
        Path report = Files.writeString(tempDir.resolve("report.html"), "<html>report</html>");

        try (ReportServer server = new ReportServer(result, report);
             HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            URI deleteUri = URI.create(server.apiBase() + "/api/duplicates?token=" + server.apiToken());
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(deleteUri).DELETE().build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("d0", "d1").contains("\"failedIds\":[]");
            assertThat(original).exists();
            assertThat(firstDuplicate).doesNotExist();
            assertThat(secondDuplicate).doesNotExist();
        }
    }

    @Test
    void removesDifferentDuplicatesConcurrentlyWithinTheWorkerLimit(@TempDir Path tempDir) throws Exception {
        Path original = Files.writeString(tempDir.resolve("origin.jpg"), "same-content");
        Path firstDuplicate = Files.writeString(tempDir.resolve("first-copy.jpg"), "same-content");
        Path secondDuplicate = Files.writeString(tempDir.resolve("second-copy.jpg"), "same-content");
        String hash = FileHelper.getSHAHashForFile(original);
        ScanResult result = new ScanResult(
                3,
                List.of(new DuplicateGroup(
                        hash,
                        Files.size(original),
                        original,
                        List.of(firstDuplicate, secondDuplicate)
                )),
                Duration.ZERO
        );
        Path report = Files.writeString(tempDir.resolve("report.html"), "<html>report</html>");
        CountDownLatch bothDeletionsStarted = new CountDownLatch(2);
        CountDownLatch allowDeletions = new CountDownLatch(1);
        AtomicInteger activeDeletions = new AtomicInteger();
        AtomicInteger maximumActiveDeletions = new AtomicInteger();

        try (ReportServer server = new ReportServer(result, report, Duration.ofSeconds(1), ignored -> {
            int active = activeDeletions.incrementAndGet();
            maximumActiveDeletions.accumulateAndGet(active, Math::max);
            bothDeletionsStarted.countDown();
            try {
                allowDeletions.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                activeDeletions.decrementAndGet();
            }
        }); HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            URI firstUri = duplicateDeleteUri(server, firstDuplicate);
            URI secondUri = duplicateDeleteUri(server, secondDuplicate);
            CompletableFuture<HttpResponse<Void>> firstResponse = client.sendAsync(
                    HttpRequest.newBuilder(firstUri).DELETE().build(),
                    HttpResponse.BodyHandlers.discarding()
            );
            CompletableFuture<HttpResponse<Void>> secondResponse = client.sendAsync(
                    HttpRequest.newBuilder(secondUri).DELETE().build(),
                    HttpResponse.BodyHandlers.discarding()
            );

            try {
                assertThat(server.deletionWorkerCount()).isBetween(2, 8);
                assertThat(bothDeletionsStarted.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(maximumActiveDeletions).hasValue(2);
            } finally {
                allowDeletions.countDown();
            }

            assertThat(firstResponse.get(2, TimeUnit.SECONDS).statusCode()).isEqualTo(204);
            assertThat(secondResponse.get(2, TimeUnit.SECONDS).statusCode()).isEqualTo(204);
            assertThat(original).exists();
            assertThat(firstDuplicate).doesNotExist();
            assertThat(secondDuplicate).doesNotExist();
        }
    }

    @Test
    void rejectsConcurrentDeletionOfTheSameDuplicate(@TempDir Path tempDir) throws Exception {
        Path original = Files.writeString(tempDir.resolve("origin.jpg"), "same-content");
        Path duplicate = Files.writeString(tempDir.resolve("duplicate.jpg"), "same-content");
        String hash = FileHelper.getSHAHashForFile(original);
        ScanResult result = new ScanResult(
                2,
                List.of(new DuplicateGroup(hash, Files.size(original), original, List.of(duplicate))),
                Duration.ZERO
        );
        Path report = Files.writeString(tempDir.resolve("report.html"), "<html>report</html>");
        CountDownLatch deletionStarted = new CountDownLatch(1);
        CountDownLatch allowDeletion = new CountDownLatch(1);

        try (ReportServer server = new ReportServer(result, report, Duration.ofSeconds(1), ignored -> {
            deletionStarted.countDown();
            try {
                allowDeletion.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }); HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            URI deleteUri = duplicateDeleteUri(server, duplicate);
            CompletableFuture<HttpResponse<Void>> firstResponse = client.sendAsync(
                    HttpRequest.newBuilder(deleteUri).DELETE().build(),
                    HttpResponse.BodyHandlers.discarding()
            );

            try {
                assertThat(deletionStarted.await(2, TimeUnit.SECONDS)).isTrue();
                HttpResponse<String> conflictingResponse = client.send(
                        HttpRequest.newBuilder(deleteUri).DELETE().build(),
                        HttpResponse.BodyHandlers.ofString()
                );
                assertThat(conflictingResponse.statusCode()).isEqualTo(409);
                assertThat(conflictingResponse.body()).contains("already being removed");
            } finally {
                allowDeletion.countDown();
            }

            assertThat(firstResponse.get(2, TimeUnit.SECONDS).statusCode()).isEqualTo(204);
            assertThat(original).exists();
            assertThat(duplicate).doesNotExist();
        }
    }

    @Test
    void signalsShutdownAfterTheLastBrowserSessionCloses(@TempDir Path tempDir) throws Exception {
        ScanResult result = new ScanResult(0, List.of(), Duration.ZERO);
        Path report = Files.writeString(tempDir.resolve("report.html"), "<html>report</html>");

        try (ReportServer server = new ReportServer(result, report, Duration.ofMillis(100));
             HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            CompletableFuture<Void> browserExit = server.browserSessionsEnded().toCompletableFuture();

            assertThat(postSession(client, server, "heartbeat", "first-session").statusCode()).isEqualTo(204);
            assertThat(postSession(client, server, "heartbeat", "second-session").statusCode()).isEqualTo(204);
            assertThat(postSession(client, server, "close", "first-session").statusCode()).isEqualTo(204);
            assertThat(browserExit.isDone()).isFalse();

            assertThat(postSession(client, server, "close", "second-session").statusCode()).isEqualTo(204);
            browserExit.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void signalsShutdownWhenBrowserHeartbeatExpires(@TempDir Path tempDir) throws Exception {
        ScanResult result = new ScanResult(0, List.of(), Duration.ZERO);
        Path report = Files.writeString(tempDir.resolve("report.html"), "<html>report</html>");

        try (ReportServer server = new ReportServer(result, report, Duration.ofMillis(100));
             HttpClient client = HttpClient.newHttpClient()) {
            server.start();

            assertThat(postSession(client, server, "heartbeat", "abandoned-session").statusCode()).isEqualTo(204);
            server.browserSessionsEnded().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void waitsForActiveDeletionBeforeSignallingBrowserShutdown(@TempDir Path tempDir) throws Exception {
        Path original = Files.writeString(tempDir.resolve("origin.jpg"), "same-content");
        Path duplicate = Files.writeString(tempDir.resolve("duplicate.jpg"), "same-content");
        String hash = FileHelper.getSHAHashForFile(original);
        ScanResult result = new ScanResult(
                2,
                List.of(new DuplicateGroup(hash, Files.size(original), original, List.of(duplicate))),
                Duration.ZERO
        );
        Path report = Files.writeString(tempDir.resolve("report.html"), "<html>report</html>");
        CountDownLatch deletionStarted = new CountDownLatch(1);
        CountDownLatch allowDeletion = new CountDownLatch(1);

        try (ReportServer server = new ReportServer(result, report, Duration.ofMillis(100), ignored -> {
            deletionStarted.countDown();
            try {
                allowDeletion.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }); HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            assertThat(postSession(client, server, "heartbeat", "slow-deletion-session").statusCode()).isEqualTo(204);
            CompletableFuture<Void> browserExit = server.browserSessionsEnded().toCompletableFuture();
            URI deleteUri = URI.create(server.apiBase() + "/api/duplicates/" + server.duplicateId(duplicate)
                    + "?token=" + server.apiToken());
            CompletableFuture<HttpResponse<Void>> deleteResponse = client.sendAsync(
                    HttpRequest.newBuilder(deleteUri).DELETE().build(),
                    HttpResponse.BodyHandlers.discarding()
            );

            try {
                assertThat(deletionStarted.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(postSession(client, server, "close", "slow-deletion-session").statusCode()).isEqualTo(204);
                Thread.sleep(300);
                assertThat(browserExit.isDone()).isFalse();
            } finally {
                allowDeletion.countDown();
            }

            assertThat(deleteResponse.get(2, TimeUnit.SECONDS).statusCode()).isEqualTo(204);
            assertThat(duplicate).doesNotExist();
            browserExit.get(2, TimeUnit.SECONDS);
        }
    }

    private static HttpResponse<Void> postSession(
            HttpClient client,
            ReportServer server,
            String action,
            String sessionId
    ) throws Exception {
        URI uri = URI.create(server.apiBase() + "/api/session/" + action + "?token=" + server.apiToken());
        return client.send(
                HttpRequest.newBuilder(uri)
                        .POST(HttpRequest.BodyPublishers.ofString(sessionId))
                        .build(),
                HttpResponse.BodyHandlers.discarding()
        );
    }

    private static URI duplicateDeleteUri(ReportServer server, Path duplicate) {
        return URI.create(server.apiBase() + "/api/duplicates/" + server.duplicateId(duplicate)
                + "?token=" + server.apiToken());
    }
}
