package pg.duplicatefileremover.helpers;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalProgressBarTest {

    @Test
    void formatsIndeterminateDiscoveryAndDeterminateStages() {
        assertThat(TerminalProgressBar.format(
                new ScanProgress.Snapshot(ScanProgress.Stage.DISCOVERING, 0, 0, 12, 345),
                1
        )).isEqualTo("Discovering /  12 directories, 345 media files");

        assertThat(TerminalProgressBar.format(
                new ScanProgress.Snapshot(ScanProgress.Stage.HASHING, 3, 4, 12, 345),
                0
        )).contains("Hashing")
                .contains("[##################------]")
                .contains("75%")
                .contains("3 / 4 files");

        assertThat(TerminalProgressBar.formatDuration(Duration.ofMinutes(1)
                .plusSeconds(2)
                .plusMillis(541)))
                .isEqualTo("1m 2s 541ms");
        assertThat(TerminalProgressBar.formatActiveDuration(Duration.ZERO)).isEqualTo("0s");
        assertThat(TerminalProgressBar.formatActiveDuration(Duration.ofMinutes(1).plusSeconds(2)))
                .isEqualTo("1m 2s");
    }

    @Test
    void activeStageTimeAdvancesWithProcessedFileCount() {
        ScanProgress progress = new ScanProgress();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AtomicLong nanoTime = new AtomicLong();

        try (TerminalProgressBar progressBar = new TerminalProgressBar(
                progress,
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                true,
                nanoTime::get
        )) {
            progress.begin(ScanProgress.Stage.HASHING, 2);
            assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("0 / 2 files (0s)");

            nanoTime.set(Duration.ofSeconds(2).toNanos());
            progress.itemCompleted();
            progressBar.render();
            assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("1 / 2 files (2s)");

            nanoTime.set(Duration.ofSeconds(2).plusMillis(541).toNanos());
            progress.itemCompleted();
            progress.complete();
        }

        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("2 / 2 files (2s 541ms)");
    }

    @Test
    void redirectedOutputPrintsOneLinePerStage() {
        ScanProgress progress = new ScanProgress();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (TerminalProgressBar progressBar = new TerminalProgressBar(
                progress,
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                false
        )) {
            progress.begin(ScanProgress.Stage.DISCOVERING, 0);
            progressBar.render();
            progress.directoryProcessed();
            progressBar.render();
            progress.begin(ScanProgress.Stage.GROUPING_BY_SIZE, 2);
            progressBar.render();
            progress.itemCompleted();
            progressBar.render();
            progress.complete();
            progressBar.render();
        }

        assertThat(bytes.toString(StandardCharsets.UTF_8).lines().toList())
                .hasSize(4)
                .satisfiesExactly(
                        line -> assertThat(line).isEqualTo("Preparing scan..."),
                        line -> assertThat(line).startsWith("Discovering"),
                        line -> assertThat(line).startsWith("Grouping by size"),
                        line -> assertThat(line).startsWith("Scan complete")
                );
    }

    @Test
    void warningClearsAndRedrawsAnInteractiveProgressLine() {
        ScanProgress progress = new ScanProgress();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (TerminalProgressBar progressBar = new TerminalProgressBar(
                progress,
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                true
        )) {
            progress.begin(ScanProgress.Stage.DISCOVERING, 0);
            progressBar.render();
            progress.warning("Skipping unreadable path [example]");
        }

        assertThat(bytes.toString(StandardCharsets.UTF_8))
                .contains("Warning: Skipping unreadable path [example]")
                .contains("Discovering")
                .endsWith(System.lineSeparator());
    }

    @Test
    void interactiveOutputKeepsEveryCompletedStageOnItsOwnLine() {
        ScanProgress progress = new ScanProgress();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AtomicLong nanoTime = new AtomicLong();

        try (TerminalProgressBar ignored = new TerminalProgressBar(
                progress,
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                true,
                nanoTime::get
        )) {
            progress.begin(ScanProgress.Stage.DISCOVERING, 0);
            progress.directoryProcessed();
            progress.begin(ScanProgress.Stage.GROUPING_BY_SIZE, 1);
            progress.itemCompleted();
            nanoTime.set(Duration.ofMinutes(1).plusSeconds(2).plusMillis(541).toNanos());
            progress.begin(ScanProgress.Stage.SAMPLING, 1);
            progress.itemCompleted();
            progress.begin(ScanProgress.Stage.HASHING, 1);
            progress.itemCompleted();
            progress.begin(ScanProgress.Stage.FINALIZING, 1);
            progress.itemCompleted();
            progress.complete();
        }

        List<String> visibleLines = List.of(bytes.toString(StandardCharsets.UTF_8)
                        .replace("\r\n", "\n")
                        .split("\n"))
                .stream()
                .map(line -> line.substring(line.lastIndexOf('\r') + 1).stripTrailing())
                .toList();
        assertThat(visibleLines)
                .hasSize(7)
                .satisfiesExactly(
                        line -> assertThat(line).isEqualTo("Preparing scan..."),
                        line -> assertThat(line).startsWith("Discovering").endsWith("(0ms)"),
                        line -> assertThat(line).startsWith("Grouping by size")
                                .contains("100%")
                                .endsWith("(1m 2s 541ms)"),
                        line -> assertThat(line).startsWith("Sampling content").contains("100%").endsWith("(0ms)"),
                        line -> assertThat(line).startsWith("Hashing").contains("100%").endsWith("(0ms)"),
                        line -> assertThat(line).startsWith("Finalizing").contains("100%").endsWith("(0ms)"),
                        line -> assertThat(line).startsWith("Scan complete")
                );
    }
}
