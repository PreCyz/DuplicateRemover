package pg.duplicatefileremover.helpers;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ScanProgressTest {

    @Test
    void recordsEachCompletedStageUsingMonotonicTime() {
        AtomicLong nanoTime = new AtomicLong();
        ScanProgress progress = new ScanProgress(ignored -> { }, nanoTime::get);

        progress.begin(ScanProgress.Stage.DISCOVERING, 0);
        nanoTime.set(Duration.ofMillis(125).toNanos());
        progress.begin(ScanProgress.Stage.GROUPING_BY_SIZE, 2);
        nanoTime.addAndGet(Duration.ofMillis(250).toNanos());
        progress.begin(ScanProgress.Stage.VALIDATING_HASH_CACHE, 2);
        nanoTime.addAndGet(Duration.ofMillis(375).toNanos());
        progress.begin(ScanProgress.Stage.SAMPLING, 2);
        nanoTime.addAndGet(Duration.ofSeconds(1).toNanos());
        progress.begin(ScanProgress.Stage.HASHING, 2);
        nanoTime.addAndGet(Duration.ofSeconds(2).plusMillis(375).toNanos());
        progress.begin(ScanProgress.Stage.FINALIZING, 1);
        nanoTime.addAndGet(Duration.ofMillis(50).toNanos());
        progress.begin(ScanProgress.Stage.VERIFYING_DUPLICATES, 2);
        nanoTime.addAndGet(Duration.ofMillis(75).toNanos());
        progress.complete();

        assertThat(progress.stageDurations())
                .containsEntry(ScanProgress.Stage.DISCOVERING, Duration.ofMillis(125))
                .containsEntry(ScanProgress.Stage.GROUPING_BY_SIZE, Duration.ofMillis(250))
                .containsEntry(ScanProgress.Stage.VALIDATING_HASH_CACHE, Duration.ofMillis(375))
                .containsEntry(ScanProgress.Stage.SAMPLING, Duration.ofSeconds(1))
                .containsEntry(ScanProgress.Stage.HASHING, Duration.ofSeconds(2).plusMillis(375))
                .containsEntry(ScanProgress.Stage.FINALIZING, Duration.ofMillis(50))
                .containsEntry(ScanProgress.Stage.VERIFYING_DUPLICATES, Duration.ofMillis(75));
    }
}
