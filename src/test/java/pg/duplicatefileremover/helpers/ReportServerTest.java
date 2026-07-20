package pg.duplicatefileremover.helpers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pg.duplicatefileremover.DiskType;

import java.net.URI;
import java.net.http.*;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ReportServerTest {

    @Test
    void selectsDiskSpecificDeletionConcurrency(@TempDir Path tempDir) throws Exception {
        ScanResult result = new ScanResult(0, List.of(), Duration.ZERO);
        Path hddReport = Files.writeString(tempDir.resolve("hdd-report.html"), "<html>report</html>");
        Path nvmeReport = Files.writeString(tempDir.resolve("nvme-report.html"), "<html>report</html>");

        try (ReportServer hdd = new ReportServer(result, hddReport, DiskType.HDD);
             ReportServer nvme = new ReportServer(result, nvmeReport, DiskType.NVME)) {
            assertThat(hdd.deletionWorkerCount()).isEqualTo(1);
            assertThat(nvme.deletionWorkerCount()).isGreaterThan(hdd.deletionWorkerCount());
            assertThat(ReportServer.deletionRetryWindowMillis(DiskType.HDD))
                    .isGreaterThan(ReportServer.deletionRetryWindowMillis(DiskType.NVME));
        }
    }

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
            HttpResponse<Void> response = client.send(
                    duplicateDeleteRequest(server, duplicate).build(),
                    HttpResponse.BodyHandlers.discarding()
            );

            assertThat(response.statusCode()).isEqualTo(204);
            assertThat(original).exists();
            assertThat(duplicate).doesNotExist();
        }
    }

    @Test
    void rejectsPathThatWasNotRegisteredAsDuplicate(@TempDir Path tempDir) throws Exception {
        Path original = Files.writeString(tempDir.resolve("origin.jpg"), "same-content");
        Path duplicate = Files.writeString(tempDir.resolve("duplicate.jpg"), "same-content");
        Path unrelated = Files.writeString(tempDir.resolve("unrelated.jpg"), "other-content");
        String hash = FileHelper.getSHAHashForFile(original);
        ScanResult result = new ScanResult(
                3,
                List.of(new DuplicateGroup(hash, Files.size(original), original, List.of(duplicate))),
                Duration.ZERO
        );
        Path report = Files.writeString(tempDir.resolve("report.html"), "<html>report</html>");

        try (ReportServer server = new ReportServer(result, report);
             HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            HttpResponse<String> response = client.send(
                    duplicateDeleteRequest(server, unrelated).build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.body()).isEqualTo("Path is not a registered duplicate");
            assertThat(original).exists();
            assertThat(duplicate).exists();
            assertThat(unrelated).exists();
        }
    }

    @Test
    void deletesThePictureWhileItsInMemoryThumbnailIsBeingTransferred(@TempDir Path tempDir) throws Exception {
        Path original = Files.writeString(tempDir.resolve("origin.jpg"), "same-content");
        Path duplicate = Files.writeString(tempDir.resolve("duplicate.jpg"), "same-content");
        String hash = FileHelper.getSHAHashForFile(original);
        ScanResult result = new ScanResult(
                2,
                List.of(new DuplicateGroup(hash, Files.size(original), original, List.of(duplicate))),
                Duration.ZERO
        );
        Path report = Files.writeString(tempDir.resolve("report.html"), "<html>report</html>");
        CountDownLatch thumbnailStarted = new CountDownLatch(1);
        CountDownLatch allowThumbnail = new CountDownLatch(1);

        try (ReportServer server = new ReportServer(
                result,
                report,
                DiskType.HDD,
                Duration.ofSeconds(2),
                ignored -> { },
                path -> {
                    if (!path.equals(duplicate)) {
                        return;
                    }
                    thumbnailStarted.countDown();
                    try {
                        allowThumbnail.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                },
                Files::delete,
                Map.of(duplicate, new ReportHelper.Thumbnail(new byte[]{1, 2, 3}, "image/jpeg"))
        ); HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            CompletableFuture<HttpResponse<Void>> thumbnailResponse = client.sendAsync(
                    HttpRequest.newBuilder(URI.create(server.mediaUrl(duplicate))).GET().build(),
                    HttpResponse.BodyHandlers.discarding()
            );
            assertThat(thumbnailStarted.await(2, TimeUnit.SECONDS)).isTrue();

            HttpResponse<Void> deleteResponse = client.send(
                    duplicateDeleteRequest(server, duplicate).build(),
                    HttpResponse.BodyHandlers.discarding()
            );
            try {
                assertThat(deleteResponse.statusCode()).isEqualTo(204);
                assertThat(duplicate).doesNotExist();
                assertThat(thumbnailResponse).isNotDone();
            } finally {
                allowThumbnail.countDown();
            }

            assertThat(thumbnailResponse.get(2, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(original).exists();
        }
    }

    @Test
    void retriesTransientWindowsDeletionFailures(@TempDir Path tempDir) throws Exception {
        Path original = Files.writeString(tempDir.resolve("origin.jpg"), "same-content");
        Path duplicate = Files.writeString(tempDir.resolve("duplicate.jpg"), "same-content");
        String hash = FileHelper.getSHAHashForFile(original);
        ScanResult result = new ScanResult(
                2,
                List.of(new DuplicateGroup(hash, Files.size(original), original, List.of(duplicate))),
                Duration.ZERO
        );
        Path report = Files.writeString(tempDir.resolve("report.html"), "<html>report</html>");
        AtomicInteger deletionAttempts = new AtomicInteger();

        try (ReportServer server = new ReportServer(
                result,
                report,
                DiskType.HDD,
                Duration.ofSeconds(2),
                ignored -> { },
                ignored -> { },
                path -> {
                    if (deletionAttempts.incrementAndGet() < 3) {
                        throw new AccessDeniedException(path.toString(), null, "used by another process");
                    }
                    Files.delete(path);
                }
        ); HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            HttpResponse<Void> response = client.send(
                    duplicateDeleteRequest(server, duplicate).build(),
                    HttpResponse.BodyHandlers.discarding()
            );

            assertThat(response.statusCode()).isEqualTo(204);
            assertThat(deletionAttempts).hasValue(3);
            assertThat(original).exists();
            assertThat(duplicate).doesNotExist();
        }
    }

    @Test
    void reportsTheActualFilesystemReasonAfterDeletionRetriesFail(@TempDir Path tempDir) throws Exception {
        Path original = Files.writeString(tempDir.resolve("origin.jpg"), "same-content");
        Path duplicate = Files.writeString(tempDir.resolve("duplicate.jpg"), "same-content");
        String hash = FileHelper.getSHAHashForFile(original);
        ScanResult result = new ScanResult(
                2,
                List.of(new DuplicateGroup(hash, Files.size(original), original, List.of(duplicate))),
                Duration.ZERO
        );
        Path report = Files.writeString(tempDir.resolve("report.html"), "<html>report</html>");
        AtomicInteger deletionAttempts = new AtomicInteger();

        try (ReportServer server = new ReportServer(
                result,
                report,
                DiskType.NVME,
                Duration.ofSeconds(3),
                ignored -> { },
                ignored -> { },
                path -> {
                    deletionAttempts.incrementAndGet();
                    throw new AccessDeniedException(path.toString(), null, "used by another process");
                }
        ); HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            HttpResponse<String> response = client.send(
                    duplicateDeleteRequest(server, duplicate).build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(response.body()).isEqualTo("File could not be removed: used by another process");
            assertThat(deletionAttempts).hasValue(5);
            assertThat(original).exists();
            assertThat(duplicate).exists();
        }
    }

    @Test
    void forceDeletesRegisteredDuplicateChangedAfterScan(@TempDir Path tempDir) throws Exception {
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
            HttpResponse<Void> response = client.send(
                    duplicateDeleteRequest(server, duplicate).build(),
                    HttpResponse.BodyHandlers.discarding()
            );

            assertThat(response.statusCode()).isEqualTo(204);
            assertThat(original).exists();
            assertThat(duplicate).doesNotExist();
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
            assertThat(response.body()).contains("first-copy.jpg", "second-copy.jpg")
                    .contains("\"failedPaths\":[]");
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

        try (ReportServer server = new ReportServer(result, report, DiskType.NVME, Duration.ofSeconds(1), ignored -> {
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
            CompletableFuture<HttpResponse<Void>> firstResponse = client.sendAsync(
                    duplicateDeleteRequest(server, firstDuplicate).build(),
                    HttpResponse.BodyHandlers.discarding()
            );
            CompletableFuture<HttpResponse<Void>> secondResponse = client.sendAsync(
                    duplicateDeleteRequest(server, secondDuplicate).build(),
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

        try (ReportServer server = new ReportServer(result, report, DiskType.NVME, Duration.ofSeconds(1), ignored -> {
            deletionStarted.countDown();
            try {
                allowDeletion.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }); HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            CompletableFuture<HttpResponse<Void>> firstResponse = client.sendAsync(
                    duplicateDeleteRequest(server, duplicate).build(),
                    HttpResponse.BodyHandlers.discarding()
            );

            try {
                assertThat(deletionStarted.await(2, TimeUnit.SECONDS)).isTrue();
                HttpResponse<String> conflictingResponse = client.send(
                        duplicateDeleteRequest(server, duplicate).build(),
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

        try (ReportServer server = new ReportServer(result, report, Duration.ofMillis(500));
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
    void refreshResetsTheBrowserShutdownGracePeriod(@TempDir Path tempDir) throws Exception {
        ScanResult result = new ScanResult(0, List.of(), Duration.ZERO);
        Path report = Files.writeString(tempDir.resolve("report.html"), "<html>report</html>");

        try (ReportServer server = new ReportServer(result, report, Duration.ofMillis(400));
             HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            assertThat(postSession(client, server, "heartbeat", "session-before-refresh").statusCode())
                    .isEqualTo(204);
            assertThat(postSession(client, server, "close", "session-before-refresh").statusCode())
                    .isEqualTo(204);

            Thread.sleep(250);
            HttpResponse<String> refreshedReport = client.send(
                    HttpRequest.newBuilder(server.reportUri()).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            Thread.sleep(250);

            assertThat(refreshedReport.statusCode()).isEqualTo(200);
            assertThat(server.browserSessionsEnded().toCompletableFuture()).isNotDone();
            assertThat(postSession(client, server, "heartbeat", "session-after-refresh").statusCode())
                    .isEqualTo(204);
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
            assertThat(server.heartbeatSilenceBeforeShutdown()).isGreaterThanOrEqualTo(Duration.ofMillis(100));
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

        try (ReportServer server = new ReportServer(result, report, DiskType.NVME, Duration.ofMillis(100), ignored -> {
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
            CompletableFuture<HttpResponse<Void>> deleteResponse = client.sendAsync(
                    duplicateDeleteRequest(server, duplicate).build(),
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

    @Test
    void deletionRequestsKeepBrowserSessionAliveWhenHeartbeatTimerIsDelayed(@TempDir Path tempDir) throws Exception {
        Path original = Files.writeString(tempDir.resolve("origin.jpg"), "same-content");
        Path duplicate = Files.writeString(tempDir.resolve("duplicate.jpg"), "same-content");
        String hash = FileHelper.getSHAHashForFile(original);
        ScanResult result = new ScanResult(
                2,
                List.of(new DuplicateGroup(hash, Files.size(original), original, List.of(duplicate))),
                Duration.ZERO
        );
        Path report = Files.writeString(tempDir.resolve("report.html"), "<html>report</html>");
        Duration sessionTimeout = Duration.ofMillis(400);

        try (ReportServer server = new ReportServer(result, report, sessionTimeout);
             HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            String sessionId = "bulk-deletion-session";
            assertThat(postSession(client, server, "heartbeat", sessionId).statusCode()).isEqualTo(204);
            CompletableFuture<Void> browserExit = server.browserSessionsEnded().toCompletableFuture();

            Thread.sleep(300);
            HttpResponse<Void> response = client.send(
                    duplicateDeleteRequest(server, duplicate)
                            .header("X-Report-Session", sessionId)
                            .build(),
                    HttpResponse.BodyHandlers.discarding()
            );
            Thread.sleep(250);

            assertThat(response.statusCode()).isEqualTo(204);
            assertThat(response.headers().firstValue("Access-Control-Allow-Headers").orElseThrow())
                    .contains("X-Report-Session");
            assertThat(browserExit.isDone()).isFalse();
            browserExit.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void bulkDeletionLeaseProtectsRenderingGapsAndReleasesAfterCompletion(@TempDir Path tempDir) throws Exception {
        ScanResult result = new ScanResult(0, List.of(), Duration.ZERO);
        Path report = Files.writeString(tempDir.resolve("report.html"), "<html>report</html>");
        Duration sessionTimeout = Duration.ofMillis(200);

        try (ReportServer server = new ReportServer(result, report, sessionTimeout);
             HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            String sessionId = "rendering-bulk-deletion";
            CompletableFuture<Void> browserExit = server.browserSessionsEnded().toCompletableFuture();

            assertThat(postSession(client, server, "deletion/start", sessionId).statusCode()).isEqualTo(204);
            Thread.sleep(350);

            assertThat(browserExit.isDone()).isFalse();
            assertThat(postSession(client, server, "deletion/finish", sessionId).statusCode()).isEqualTo(204);
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

    private static HttpRequest.Builder duplicateDeleteRequest(ReportServer server, Path duplicate) {
        URI uri = URI.create(server.apiBase() + "/api/duplicates/path?token=" + server.apiToken());
        String path = duplicate.toAbsolutePath().normalize().toString();
        return HttpRequest.newBuilder(uri)
                .header("Content-Type", "text/plain; charset=utf-8")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(path));
    }
}
