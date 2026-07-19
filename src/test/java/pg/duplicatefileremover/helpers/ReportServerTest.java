package pg.duplicatefileremover.helpers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportServerTest {

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
}
