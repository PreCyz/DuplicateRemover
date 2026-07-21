package pg.duplicatefileremover.helpers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportHelperTest {

    @Test
    void createsBootstrapReportWithSummaryOriginDuplicateAndActions(@TempDir Path tempDir) throws IOException {
        Path original = Files.writeString(tempDir.resolve("origin & first.jpg"), "same");
        Path duplicate = Files.writeString(tempDir.resolve("duplicate.jpg"), "same");
        ScanResult result = new ScanResult(
                7,
                List.of(new DuplicateGroup("abc123", 4, original, List.of(duplicate))),
                Duration.ofMillis(1250),
                Map.of(
                        ScanProgress.Stage.DISCOVERING, Duration.ofMillis(125),
                        ScanProgress.Stage.GROUPING_BY_SIZE, Duration.ofMillis(250),
                        ScanProgress.Stage.VALIDATING_HASH_CACHE, Duration.ofMillis(375),
                        ScanProgress.Stage.SAMPLING, Duration.ofSeconds(1),
                        ScanProgress.Stage.HASHING, Duration.ofSeconds(2).plusMillis(375),
                        ScanProgress.Stage.FINALIZING, Duration.ZERO,
                        ScanProgress.Stage.VERIFYING_DUPLICATES, Duration.ofMillis(50)
                )
        );
        Path report = tempDir.resolve("reports").resolve("duplicates.html");

        new ReportHelper(result, report, new TestLinks(duplicate)).createReport();

        assertThat(report).exists();
        assertThat(Files.readString(report))
                .contains("href=\"http://127.0.0.1:12345/assets/bootstrap.min.css?token=test-token\"")
                .doesNotContain("cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/css")
                .contains("Remove All Duplicates")
                .contains(">Remove</button>")
                .contains("data-duplicate-path=\"" + duplicate.toAbsolutePath() + "\"")
                .contains("data-path=\"" + duplicate.toAbsolutePath() + "\"")
                .contains("id=\"remove-progress-section\"")
                .contains("class=\"progress-bar progress-bar-striped progress-bar-animated bg-info\"")
                .contains("aria-label=\"Duplicate removal progress\"")
                .contains("id=\"completion-toast\"")
                .contains("text-bg-info")
                .contains("bootstrap.Toast.getOrCreateInstance")
                .contains("const duplicateCardsByPath = new Map()")
                .contains("let remainingDuplicateCount = duplicateCardsByPath.size")
                .contains("duplicateCount.textContent = String(remainingDuplicateCount)")
                .contains("remainingDuplicateBytes = Math.max(0, remainingDuplicateBytes - bytes)")
                .contains("remainingDuplicateBytes - bytes);\n            updateDuplicateSummary();\n            queueCardRemoval(card);")
                .contains("duplicateCardsByPath.delete(path)")
                .contains("const deadline = performance.now() + 8")
                .contains("performance.now() < deadline")
                .contains("scheduleCardRemovalBatch(flushCardRemovalBatch)")
                .contains("await waitForCardRemovals()")
                .contains("const allowBrowserPaint = () => {")
                .contains("performance.now() - lastUiPaintAt < 100")
                .contains("requestAnimationFrame(finishPaint)")
                .contains("const visualPercentage = total === 0 ? 100 : Math.min(100, processed * 100 / total)")
                .contains("progressBar.style.width = `${visualPercentage.toFixed(4)}%`")
                .contains("progressBar.textContent = `${displayedPercentage}%`")
                .contains("updateProgress(deleted + failed, total);\n                await allowBrowserPaint();")
                .contains("const cards = Array.from(duplicateCardsByPath.values())")
                .contains("const total = remainingDuplicateCount")
                .contains("updateProgress(deleted + failed, total)")
                .contains("const deletionWorkerCount = 4")
                .contains("'X-Report-Session': sessionId")
                .contains("/api/duplicates?token=${encodeURIComponent(apiToken)}`")
                .contains("const response = await fetch(bulkDeletionUrl, bulkDeletionRequestOptions)")
                .contains("response.body.getReader()")
                .contains("await applyDeletionResult(JSON.parse(line))")
                .contains("Files scanned")
                .contains(">7</div>")
                .contains("data-bytes=\"4\"")
                .contains("origin &amp; first.jpg")
                .contains("duplicate.jpg")
                .contains("http://127.0.0.1:12345/media/")
                .contains("const apiToken = 'test-token'")
                .contains("/api/session/heartbeat")
                .contains("/api/session/close")
                .doesNotContain("/api/session/deletion/start", "/api/session/deletion/finish")
                .contains("navigator.sendBeacon")
                .contains("window.addEventListener('pagehide'")
                .contains("01s 250ms")
                .contains("Scan steps")
                .contains("Discovering files", "125ms")
                .contains("Grouping by size", "250ms")
                .contains("Validating cache", "375ms")
                .contains("Sampling content", "01s")
                .contains("Hashing files", "02s 375ms")
                .contains("Finalizing results", "0ms")
                .contains("Verifying dups", "50ms");

        String html = Files.readString(report);
        assertThat(html.indexOf("Scan steps")).isGreaterThan(html.indexOf("Scan time"));
        assertThat(html.indexOf("const sendHeartbeat")).isLessThan(html.indexOf("<tbody id=\"duplicates-table\""));
    }

    @Test
    void rendersOneSummaryCountAndCardForEveryDuplicate(@TempDir Path tempDir) throws IOException {
        Path original = Files.writeString(tempDir.resolve("origin.jpg"), "same");
        Path firstDuplicate = Files.writeString(tempDir.resolve("first-copy.jpg"), "same");
        Path secondDuplicate = Files.writeString(tempDir.resolve("second-copy.jpg"), "same");
        ScanResult result = new ScanResult(
                3,
                List.of(new DuplicateGroup(
                        "abc123",
                        4,
                        original,
                        List.of(firstDuplicate, secondDuplicate)
                )),
                Duration.ZERO
        );
        Path report = tempDir.resolve("report.html");

        new ReportHelper(result, report, new MultipleDuplicateLinks()).createReport();

        String html = Files.readString(report);
        assertThat(html).contains("id=\"duplicate-count\" class=\"summary-value\">2</div>");
        assertThat(html.split("class=\"media-card duplicate-card\"", -1)).hasSize(3);
    }

    @Test
    void doesNotCreateThumbnailsOrBrowserMediaUrlsForVideos(@TempDir Path tempDir) throws IOException {
        Path original = Files.writeString(tempDir.resolve("origin.mp4"), "same");
        Path duplicate = Files.writeString(tempDir.resolve("duplicate.mov"), "same");
        ScanResult result = new ScanResult(
                2,
                List.of(new DuplicateGroup("abc123", 4, original, List.of(duplicate))),
                Duration.ZERO
        );
        Path report = tempDir.resolve("report.html");

        new ReportHelper(result, report, new TestLinks(duplicate) {
            @Override
            public String mediaUrl(Path path) {
                throw new AssertionError("Video media URL must not be generated");
            }
        }).createReport();

        assertThat(Files.readString(report))
                .contains("origin.mp4", "duplicate.mov", ">Remove</button>")
                .doesNotContain("<video", "<img", "/media/");
    }

    @Test
    void generatesCompressedThumbnailsInMemoryWithProgress(@TempDir Path tempDir) throws IOException {
        BufferedImage source = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        Path original = tempDir.resolve("original.jpg");
        Path duplicate = tempDir.resolve("duplicate.jpg");
        assertThat(ImageIO.write(source, "jpeg", original.toFile())).isTrue();
        assertThat(ImageIO.write(source, "jpeg", duplicate.toFile())).isTrue();
        ScanResult result = new ScanResult(
                2,
                List.of(new DuplicateGroup("abc123", Files.size(original), original, List.of(duplicate))),
                Duration.ZERO
        );
        List<ScanProgress.Snapshot> updates = new ArrayList<>();
        ScanProgress progress = new ScanProgress(updates::add);
        progress.begin(ScanProgress.Stage.VERIFYING_DUPLICATES, 2);
        progress.itemsCompleted(2);

        ReportHelper.ReportPreparation preparation = ReportHelper.prepareReport(result, progress, true);

        assertThat(preparation.thumbnails()).containsOnlyKeys(
                original.toAbsolutePath().normalize(),
                duplicate.toAbsolutePath().normalize()
        );
        for (ReportHelper.Thumbnail thumbnail : preparation.thumbnails().values()) {
            assertThat(thumbnail.contentType()).isEqualTo("image/jpeg");
            BufferedImage generated = ImageIO.read(new java.io.ByteArrayInputStream(thumbnail.bytes()));
            assertThat(generated.getWidth()).isLessThanOrEqualTo(288);
            assertThat(generated.getHeight()).isLessThanOrEqualTo(192);
        }
        assertThat(updates).filteredOn(update -> update.stage() == ScanProgress.Stage.THUMBNAILS)
                .extracting(ScanProgress.Snapshot::completed)
                .containsExactly(0L, 1L, 2L);
        assertThat(updates.getLast().stage()).isEqualTo(ScanProgress.Stage.COMPLETE);
        assertThat(preparation.scanResult().stageDurations()).containsKey(ScanProgress.Stage.THUMBNAILS);
    }

    @Test
    void skipsThumbnailGenerationAndMarkupWhenDisabled(@TempDir Path tempDir) throws IOException {
        Path original = Files.writeString(tempDir.resolve("original.jpg"), "same");
        Path duplicate = Files.writeString(tempDir.resolve("duplicate.jpg"), "same");
        ScanResult result = new ScanResult(
                2,
                List.of(new DuplicateGroup("abc123", 4, original, List.of(duplicate))),
                Duration.ZERO
        );
        List<ScanProgress.Snapshot> updates = new ArrayList<>();
        ScanProgress progress = new ScanProgress(updates::add);
        progress.begin(ScanProgress.Stage.VERIFYING_DUPLICATES, 2);
        progress.itemsCompleted(2);

        ReportHelper.ReportPreparation preparation = ReportHelper.prepareReport(result, progress, false);
        Path report = tempDir.resolve("report.html");
        new ReportHelper(preparation.scanResult(), report, new TestLinks(duplicate) {
            @Override
            public boolean hasThumbnail(Path path) {
                return false;
            }

            @Override
            public String mediaUrl(Path path) {
                throw new AssertionError("Media URL must not be generated when thumbnails are disabled");
            }
        }).createReport();

        assertThat(preparation.thumbnails()).isEmpty();
        assertThat(updates).noneMatch(update -> update.stage() == ScanProgress.Stage.THUMBNAILS);
        assertThat(Files.readString(report)).doesNotContain("<img", "/media/");
    }

    @Test
    void removesMissingDuplicatesBeforePreparingTheReport(@TempDir Path tempDir) throws Exception {
        Path original = Files.writeString(tempDir.resolve("original.jpg"), "same");
        Path missingDuplicate = Files.writeString(tempDir.resolve("missing.jpg"), "same");
        Path existingDuplicate = Files.writeString(tempDir.resolve("existing.jpg"), "same");
        ScanResult result = new ScanResult(
                3,
                List.of(new DuplicateGroup(
                        FileHelper.getSHAHashForFile(original),
                        4,
                        original,
                        List.of(missingDuplicate, existingDuplicate)
                )),
                Duration.ZERO
        );
        Files.delete(missingDuplicate);

        List<ScanProgress.Snapshot> progressUpdates = new ArrayList<>();
        ScanResult existingResult = ReportHelper.retainExistingFiles(
                result,
                new ScanProgress(progressUpdates::add)
        );

        assertThat(existingResult.scannedFiles()).isEqualTo(3);
        assertThat(existingResult.duplicateCount()).isEqualTo(1);
        assertThat(existingResult.duplicateGroups().getFirst().original()).isEqualTo(original);
        assertThat(existingResult.duplicateGroups().getFirst().duplicates()).containsExactly(existingDuplicate);
        assertThat(progressUpdates).filteredOn(update -> update.stage() == ScanProgress.Stage.VERIFYING_DUPLICATES)
                .extracting(ScanProgress.Snapshot::completed)
                .contains(0L, 1L, 2L, 3L);
        assertThat(progressUpdates.getLast().stage()).isEqualTo(ScanProgress.Stage.COMPLETE);
    }

    @Test
    void existenceVerificationDoesNotRecalculateHashes(@TempDir Path tempDir) throws Exception {
        Path original = Files.writeString(tempDir.resolve("original.jpg"), "same");
        Path changedDuplicate = Files.writeString(tempDir.resolve("changed.jpg"), "diff");
        Path validDuplicate = Files.writeString(tempDir.resolve("valid.jpg"), "same");
        ScanResult cachedResult = new ScanResult(
                3,
                List.of(new DuplicateGroup(
                        FileHelper.getSHAHashForFile(original),
                        Files.size(original),
                        original,
                        List.of(changedDuplicate, validDuplicate)
                )),
                Duration.ZERO
        );

        ScanResult verifiedResult = ReportHelper.retainExistingFiles(cachedResult);

        assertThat(verifiedResult.duplicateCount()).isEqualTo(2);
        assertThat(verifiedResult.duplicateGroups().getFirst().duplicates())
                .containsExactly(changedDuplicate, validDuplicate);
    }

    @Test
    void createsReportWhenNoDuplicatesExist(@TempDir Path tempDir) throws IOException {
        ScanResult result = new ScanResult(2, List.of(), Duration.ZERO);
        Path report = tempDir.resolve("report.html");

        new ReportHelper(result, report, new TestLinks(null)).createReport();

        assertThat(Files.readString(report))
                .contains("No duplicate media files found")
                .contains("data-bytes=\"0\"")
                .doesNotContain("${scanStepsSummary}");
    }

    @Test
    void formatsDurationWithoutZeroValuedUnits() {
        assertThat(ReportHelper.formatDuration(Duration.ofHours(1).plusSeconds(2)))
                .isEqualTo("01h 02s");
        assertThat(ReportHelper.formatDuration(Duration.ofMinutes(7).plusSeconds(16).plusMillis(285)))
                .isEqualTo("07m 16s 285ms");
        assertThat(ReportHelper.formatDuration(Duration.ofSeconds(16).plusMillis(285)))
                .isEqualTo("16s 285ms");
        assertThat(ReportHelper.formatDuration(Duration.ofMinutes(7).plusMillis(285)))
                .isEqualTo("07m 285ms");
        assertThat(ReportHelper.formatDuration(Duration.ofSeconds(16)))
                .isEqualTo("16s");
        assertThat(ReportHelper.formatDuration(Duration.ZERO))
                .isEqualTo("0ms");
    }

    private static class TestLinks implements ReportHelper.ReportLinks {
        private final Path duplicate;

        private TestLinks(Path duplicate) {
            this.duplicate = duplicate;
        }

        @Override
        public String bootstrapCssUrl() {
            return apiBase() + "/assets/bootstrap.min.css?token=" + apiToken();
        }

        @Override
        public String mediaUrl(Path path) {
            return apiBase() + "/media/" + (path.equals(duplicate) ? "d0" : "m0") + "?token=" + apiToken();
        }

        @Override
        public String apiBase() {
            return "http://127.0.0.1:12345";
        }

        @Override
        public String apiToken() {
            return "test-token";
        }

        @Override
        public int deletionWorkerCount() {
            return 4;
        }
    }

    private static final class MultipleDuplicateLinks implements ReportHelper.ReportLinks {
        @Override
        public String bootstrapCssUrl() {
            return "bootstrap.css";
        }

        @Override
        public String mediaUrl(Path path) {
            return "media/" + path.getFileName();
        }

        @Override
        public String apiBase() {
            return "http://127.0.0.1:12345";
        }

        @Override
        public String apiToken() {
            return "test-token";
        }

        @Override
        public int deletionWorkerCount() {
            return 2;
        }
    }
}
