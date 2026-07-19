package pg.duplicatefileremover.helpers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportHelperTest {

    @Test
    void createsBootstrapReportWithSummaryOriginDuplicateAndActions(@TempDir Path tempDir) throws IOException {
        Path original = Files.writeString(tempDir.resolve("origin & first.jpg"), "same");
        Path duplicate = Files.writeString(tempDir.resolve("duplicate.jpg"), "same");
        ScanResult result = new ScanResult(
                7,
                List.of(new DuplicateGroup("abc123", 4, original, List.of(duplicate))),
                Duration.ofMillis(1250)
        );
        Path report = tempDir.resolve("reports").resolve("duplicates.html");

        new ReportHelper(result, report, new TestLinks(duplicate)).createReport();

        assertThat(report).exists();
        assertThat(Files.readString(report))
                .contains("bootstrap@5.3.8")
                .contains("Remove All Duplicates")
                .contains(">Remove</button>")
                .contains("id=\"remove-progress-section\"")
                .contains("class=\"progress-bar progress-bar-striped progress-bar-animated bg-info\"")
                .contains("aria-label=\"Duplicate removal progress\"")
                .contains("id=\"completion-toast\"")
                .contains("text-bg-info")
                .contains("bootstrap.Toast.getOrCreateInstance")
                .contains("updateProgress(deleted + failed, total)")
                .contains("Files scanned")
                .contains(">7</div>")
                .contains("data-bytes=\"4\"")
                .contains("origin &amp; first.jpg")
                .contains("duplicate.jpg")
                .contains("http://127.0.0.1:12345/media/")
                .contains("const apiToken = 'test-token'")
                .contains("00h 00m 01s 250ms");
    }

    @Test
    void createsReportWhenNoDuplicatesExist(@TempDir Path tempDir) throws IOException {
        ScanResult result = new ScanResult(2, List.of(), Duration.ZERO);
        Path report = tempDir.resolve("report.html");

        new ReportHelper(result, report, new TestLinks(null)).createReport();

        assertThat(Files.readString(report))
                .contains("No duplicate media files found")
                .contains("data-bytes=\"0\"");
    }

    private record TestLinks(Path duplicate) implements ReportHelper.ReportLinks {
        @Override
        public String mediaUrl(Path path) {
            return apiBase() + "/media/" + (path.equals(duplicate) ? "d0" : "m0") + "?token=" + apiToken();
        }

        @Override
        public String duplicateId(Path path) {
            return "d0";
        }

        @Override
        public String apiBase() {
            return "http://127.0.0.1:12345";
        }

        @Override
        public String apiToken() {
            return "test-token";
        }
    }
}
