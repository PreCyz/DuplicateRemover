package pg.duplicatefileremover.helpers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import pg.duplicatefileremover.DiskType;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "hashCacheBenchmark", matches = "true")
class HashCachePerformanceTest {
    private static final int CACHE_ENTRIES = 10_000;
    private static final int WARMUP_RUNS = 1;
    private static final int MEASURED_RUNS = 5;

    @Test
    void measuresWholeScanWithLargeHashCache(@TempDir Path tempDir) throws Exception {
        long[] measurements = new long[MEASURED_RUNS];
        for (int run = 0; run < WARMUP_RUNS + MEASURED_RUNS; run++) {
            Path scanRoot = Files.createDirectory(tempDir.resolve("scan-" + run));
            Files.writeString(scanRoot.resolve("original.jpg"), "same-content");
            Files.writeString(scanRoot.resolve("duplicate.jpg"), "same-content");
            Path cache = tempDir.resolve("hash-cache-" + run + ".properties");
            createCache(cache, scanRoot);

            long started = System.nanoTime();
            ScanResult result = new FileHelper(
                    java.util.List.of(scanRoot),
                    new ScanProgress(),
                    DiskType.HDD,
                    cache
            ).scan();
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

            assertThat(result.duplicateCount()).isEqualTo(1);
            if (run >= WARMUP_RUNS) {
                measurements[run - WARMUP_RUNS] = elapsedMillis;
            }
        }

        long[] sorted = measurements.clone();
        Arrays.sort(sorted);
        System.out.printf(
                "HASH_CACHE_WHOLE_SCAN_MS entries=%d median=%d samples=%s%n",
                CACHE_ENTRIES,
                sorted[sorted.length / 2],
                Arrays.toString(measurements)
        );
    }

    private static void createCache(Path cache, Path scanRoot) throws Exception {
        Properties properties = new Properties();
        for (int index = 0; index < CACHE_ENTRIES; index++) {
            properties.setProperty(
                    scanRoot.resolve("missing-" + index + ".jpg").toAbsolutePath().normalize().toString(),
                    "1,1,1,0000000000000000000000000000000000000000000000000000000000000000"
            );
        }
        try (OutputStream output = Files.newOutputStream(cache)) {
            properties.store(output, "Hash cache performance fixture");
        }
    }
}
