package pg.duplicatefileremover.helpers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pg.duplicatefileremover.DiskType;
import pg.duplicatefileremover.TestBase;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileHelperTest extends TestBase {

    @Test
    void selectsStorageSpecificConcurrencyAndBufferProfiles() {
        FileHelper.ScanProfile hdd = FileHelper.scanProfile(DiskType.HDD);
        FileHelper.ScanProfile nvme = FileHelper.scanProfile(DiskType.NVME);

        assertThat(hdd.traversalWorkers()).isEqualTo(2);
        assertThat(hdd.samplingWorkers()).isEqualTo(1);
        assertThat(hdd.hashingWorkers()).isEqualTo(1);
        assertThat(hdd.progressiveSampling()).isTrue();
        assertThat(hdd.pathOrderedIo()).isTrue();
        assertThat(hdd.deletionWorkers()).isEqualTo(1);
        assertThat(nvme.samplingWorkers()).isGreaterThan(hdd.samplingWorkers());
        assertThat(nvme.hashingWorkers()).isGreaterThan(hdd.hashingWorkers());
        assertThat(nvme.hashBufferSize()).isGreaterThan(hdd.hashBufferSize());
        assertThat(nvme.progressiveSampling()).isFalse();
        assertThat(nvme.pathOrderedIo()).isFalse();
        assertThat(nvme.deletionWorkers()).isGreaterThan(hdd.deletionWorkers());
        assertThat(nvme.deletionHashBufferSize()).isGreaterThan(hdd.deletionHashBufferSize());
    }

    @Test
    void listsOnlySupportedMediaFiles(@TempDir Path tempDir) throws IOException {
        createFile("image.JPG", tempDir);
        createFile("notes.txt", tempDir);

        assertThat(new FileHelper(tempDir.toString()).getFileOnlyList())
                .extracting(File::getName)
                .containsExactly("image.JPG");
    }

    @Test
    void hashesEqualContentEqually(@TempDir Path tempDir) throws Exception {
        Path first = createFile("first.jpg", tempDir);
        Path second = createFile("second.jpg", tempDir);
        FileHelper helper = new FileHelper(tempDir.toString());

        assertThat(helper.getSHAHashForFile(first.toFile()))
                .isNotEmpty()
                .isEqualTo(helper.getSHAHashForFile(second.toFile()));
    }

    @Test
    void rejectsMissingFileWhenHashing(@TempDir Path tempDir) {
        FileHelper helper = new FileHelper(tempDir.toString());

        assertThatThrownBy(() -> helper.getSHAHashForFile(tempDir.resolve("missing.jpg").toFile()))
                .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void readsCompleteFile(@TempDir Path tempDir) throws Exception {
        Path resource = createFile("number.png", tempDir);
        FileHelper helper = new FileHelper(tempDir.toString());

        assertThat(helper.getByteArrayFromFile(resource.toFile())).containsExactly(Files.readAllBytes(resource));
    }

    @Test
    void scanSeparatesEqualSizedDifferentContentAndSelectsOneOrigin(@TempDir Path tempDir) throws Exception {
        Path original = tempDir.resolve("z-original.jpg");
        Path nested = Files.createDirectories(tempDir.resolve("nested"));
        Path duplicate = nested.resolve("a-copy.JPG");
        Files.writeString(original, "same");
        Files.writeString(duplicate, "same");
        Files.getFileAttributeView(original, BasicFileAttributeView.class)
                .setTimes(FileTime.fromMillis(1_000), null, FileTime.fromMillis(1_000));
        Files.getFileAttributeView(duplicate, BasicFileAttributeView.class)
                .setTimes(FileTime.fromMillis(2_000), null, FileTime.fromMillis(2_000));
        Files.writeString(tempDir.resolve("different.jpg"), "diff");
        Files.writeString(tempDir.resolve("unique.png"), "unique");
        Files.writeString(tempDir.resolve("ignored.txt"), "same");

        ScanResult result = new FileHelper(tempDir.toString()).scan();

        assertThat(result.scannedFiles()).isEqualTo(4);
        assertThat(result.duplicateCount()).isEqualTo(1);
        assertThat(result.duplicateBytes()).isEqualTo(4);
        assertThat(result.duplicateGroups()).singleElement().satisfies(group -> {
            assertThat(group.original()).isEqualTo(original.toAbsolutePath());
            assertThat(group.duplicates()).containsExactly(duplicate.toAbsolutePath());
            assertThat(group.fileSize()).isEqualTo(4);
        });
    }

    @Test
    void sampledFingerprintSkipsFullHashForClearlyDifferentLargeFiles(@TempDir Path tempDir) throws Exception {
        int fileSize = 512 * 1024;
        byte[] firstContent = new byte[fileSize];
        byte[] secondContent = new byte[fileSize];
        secondContent[0] = 1;
        Files.write(tempDir.resolve("first.jpg"), firstContent);
        Files.write(tempDir.resolve("second.jpg"), secondContent);
        List<ScanProgress.Snapshot> updates = new CopyOnWriteArrayList<>();

        ScanResult result = new FileHelper(List.of(tempDir), new ScanProgress(updates::add)).scan();

        assertThat(result.duplicateGroups()).isEmpty();
        assertThat(updates).filteredOn(update -> update.stage() == ScanProgress.Stage.SAMPLING)
                .allSatisfy(update -> assertThat(update.total()).isEqualTo(6));
        assertThat(updates).filteredOn(update -> update.stage() == ScanProgress.Stage.HASHING)
                .allSatisfy(update -> assertThat(update.total()).isZero());
    }

    @Test
    void matchingSamplesStillUseFullHashToSeparateLargeFiles(@TempDir Path tempDir) throws Exception {
        int fileSize = 512 * 1024;
        byte[] matchingContent = new byte[fileSize];
        byte[] differentContent = matchingContent.clone();
        differentContent[128 * 1024] = 1;
        Path original = Files.write(tempDir.resolve("original.jpg"), matchingContent);
        Path duplicate = Files.write(tempDir.resolve("duplicate.jpg"), matchingContent);
        Path different = Files.write(tempDir.resolve("different.jpg"), differentContent);
        List<ScanProgress.Snapshot> updates = new CopyOnWriteArrayList<>();

        ScanResult result = new FileHelper(List.of(tempDir), new ScanProgress(updates::add)).scan();

        assertThat(result.duplicateCount()).isEqualTo(1);
        assertThat(result.duplicateGroups()).singleElement().satisfies(group -> {
            assertThat(List.of(group.original(), group.duplicates().getFirst()))
                    .containsExactlyInAnyOrder(original.toAbsolutePath(), duplicate.toAbsolutePath());
            assertThat(group.original()).isNotEqualTo(different.toAbsolutePath());
            assertThat(group.duplicates()).doesNotContain(different.toAbsolutePath());
        });
        assertThat(updates).filteredOn(update -> update.stage() == ScanProgress.Stage.HASHING)
                .allSatisfy(update -> assertThat(update.total()).isEqualTo(3));
    }

    @Test
    void progressiveHddSamplingPreservesDuplicateDetection(@TempDir Path tempDir) throws Exception {
        int fileSize = 512 * 1024;
        byte[] matchingContent = new byte[fileSize];
        byte[] differentContent = matchingContent.clone();
        differentContent[fileSize / 2] = 1;
        Path original = Files.write(tempDir.resolve("original.jpg"), matchingContent);
        Path duplicate = Files.write(tempDir.resolve("duplicate.jpg"), matchingContent);
        Path different = Files.write(tempDir.resolve("different.jpg"), differentContent);
        List<ScanProgress.Snapshot> updates = new CopyOnWriteArrayList<>();

        ScanResult result = new FileHelper(
                List.of(tempDir),
                new ScanProgress(updates::add),
                DiskType.HDD,
                null
        ).scan();

        assertThat(result.duplicateCount()).isEqualTo(1);
        assertThat(result.duplicateGroups()).singleElement().satisfies(group -> {
            assertThat(List.of(group.original(), group.duplicates().getFirst()))
                    .containsExactlyInAnyOrder(original.toAbsolutePath(), duplicate.toAbsolutePath());
            assertThat(group.original()).isNotEqualTo(different.toAbsolutePath());
            assertThat(group.duplicates()).doesNotContain(different.toAbsolutePath());
        });
        assertThat(updates).filteredOn(update -> update.stage() == ScanProgress.Stage.SAMPLING)
                .extracting(ScanProgress.Snapshot::completed)
                .startsWith(0L, 1L, 2L, 3L)
                .endsWith(9L);
    }

    @Test
    void progressiveHddSamplingReachesOneHundredPercentOnlyAfterAllSampleReads(@TempDir Path tempDir)
            throws Exception {
        int fileSize = 512 * 1024;
        byte[] matchingContent = new byte[fileSize];
        byte[] differentContent = matchingContent.clone();
        differentContent[fileSize / 2] = 1;
        Files.write(tempDir.resolve("original.jpg"), matchingContent);
        Files.write(tempDir.resolve("duplicate.jpg"), matchingContent);
        Path different = Files.write(tempDir.resolve("different.jpg"), differentContent);
        AtomicBoolean removedAtOneHundredPercent = new AtomicBoolean();
        ScanProgress progress = new ScanProgress(update -> {
            if (update.stage() == ScanProgress.Stage.SAMPLING
                    && update.total() == 9
                    && update.completed() == update.total()) {
                try {
                    removedAtOneHundredPercent.set(Files.deleteIfExists(different));
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }
        });

        ScanResult result = new FileHelper(List.of(tempDir), progress, DiskType.HDD, null).scan();

        assertThat(removedAtOneHundredPercent).isTrue();
        assertThat(result.duplicateCount()).isEqualTo(1);
    }

    @Test
    void fullyCachedSizeGroupSkipsSamplingAndHashing(@TempDir Path tempDir) throws Exception {
        byte[] content = new byte[512 * 1024];
        Files.write(tempDir.resolve("original.jpg"), content);
        Files.write(tempDir.resolve("duplicate.jpg"), content);
        Path cache = tempDir.resolve("cache").resolve("hashes.properties");

        new FileHelper(List.of(tempDir), new ScanProgress(), DiskType.HDD, cache).scan();
        List<ScanProgress.Snapshot> updates = new CopyOnWriteArrayList<>();
        ScanResult cachedScan = new FileHelper(
                List.of(tempDir),
                new ScanProgress(updates::add),
                DiskType.HDD,
                cache
        ).scan();

        assertThat(cachedScan.duplicateCount()).isEqualTo(1);
        assertThat(updates).filteredOn(update -> update.stage() == ScanProgress.Stage.VALIDATING_HASH_CACHE)
                .allSatisfy(update -> {
                    assertThat(update.total()).isEqualTo(4);
                    assertThat(update.completed()).isBetween(0L, 4L);
                });
        assertThat(updates).filteredOn(update -> update.stage() == ScanProgress.Stage.VALIDATING_HASH_CACHE)
                .extracting(ScanProgress.Snapshot::completed)
                .endsWith(4L);
        assertThat(updates).filteredOn(update -> update.stage() == ScanProgress.Stage.SAMPLING)
                .allSatisfy(update -> assertThat(update.total()).isZero());
        assertThat(updates).filteredOn(update -> update.stage() == ScanProgress.Stage.HASHING)
                .allSatisfy(update -> assertThat(update.total()).isZero());
    }

    @Test
    void persistentHashCacheIsInvalidatedWhenFileMetadataChanges(@TempDir Path tempDir) throws Exception {
        Path original = Files.writeString(tempDir.resolve("original.jpg"), "same");
        Path duplicate = Files.writeString(tempDir.resolve("duplicate.jpg"), "same");
        Path cache = tempDir.resolve("cache").resolve("hashes.properties");

        ScanResult firstScan = new FileHelper(
                List.of(tempDir),
                new ScanProgress(),
                DiskType.HDD,
                cache
        ).scan();
        assertThat(firstScan.duplicateCount()).isEqualTo(1);
        assertThat(cache).exists();

        Files.writeString(duplicate, "diff");
        Files.setLastModifiedTime(duplicate, FileTime.fromMillis(System.currentTimeMillis() + 2_000));
        ScanResult secondScan = new FileHelper(
                List.of(tempDir),
                new ScanProgress(),
                DiskType.HDD,
                cache
        ).scan();

        assertThat(secondScan.duplicateGroups()).isEmpty();
        assertThat(original).exists();
        assertThat(duplicate).exists();
    }

    @Test
    void removesMissingFilesFromPersistentHashCacheDuringValidation(@TempDir Path tempDir) throws Exception {
        Path original = Files.writeString(tempDir.resolve("original.jpg"), "same");
        Path duplicate = Files.writeString(tempDir.resolve("duplicate.jpg"), "same");
        Path cache = tempDir.resolve("cache").resolve("hashes.properties");

        new FileHelper(List.of(tempDir), new ScanProgress(), DiskType.HDD, cache).scan();
        Files.delete(duplicate);
        List<ScanProgress.Snapshot> updates = new CopyOnWriteArrayList<>();

        new FileHelper(
                List.of(tempDir),
                new ScanProgress(updates::add),
                DiskType.HDD,
                cache
        ).scan();

        Set<String> cachedPaths = readCachedPaths(cache);
        assertThat(cachedPaths).contains(original.toAbsolutePath().normalize().toString());
        assertThat(cachedPaths).doesNotContain(duplicate.toAbsolutePath().normalize().toString());
        assertThat(updates).filteredOn(update -> update.stage() == ScanProgress.Stage.VALIDATING_HASH_CACHE)
                .extracting(ScanProgress.Snapshot::completed)
                .endsWith(2L);
    }

    @Test
    void preservesCacheEntriesOutsideTheCurrentScanRoots(@TempDir Path tempDir) throws Exception {
        Path firstRoot = Files.createDirectory(tempDir.resolve("first"));
        Path secondRoot = Files.createDirectory(tempDir.resolve("second"));
        Path firstOriginal = Files.writeString(firstRoot.resolve("first-original.jpg"), "first");
        Path firstDuplicate = Files.writeString(firstRoot.resolve("first-duplicate.jpg"), "first");
        Path secondOriginal = Files.writeString(secondRoot.resolve("second-original.jpg"), "second");
        Path secondDuplicate = Files.writeString(secondRoot.resolve("second-duplicate.jpg"), "second");
        Path cache = tempDir.resolve("hashes.properties");

        new FileHelper(
                List.of(firstRoot, secondRoot),
                new ScanProgress(),
                DiskType.HDD,
                cache
        ).scan();
        Files.delete(firstDuplicate);

        new FileHelper(List.of(firstRoot), new ScanProgress(), DiskType.HDD, cache).scan();

        assertThat(readCachedPaths(cache)).contains(
                firstOriginal.toAbsolutePath().normalize().toString(),
                secondOriginal.toAbsolutePath().normalize().toString(),
                secondDuplicate.toAbsolutePath().normalize().toString()
        ).doesNotContain(firstDuplicate.toAbsolutePath().normalize().toString());
    }

    @Test
    void doesNotRewriteUnchangedHashCache(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("original.jpg"), "same");
        Files.writeString(tempDir.resolve("duplicate.jpg"), "same");
        Path cache = tempDir.resolve("hashes.properties");
        new FileHelper(List.of(tempDir), new ScanProgress(), DiskType.HDD, cache).scan();
        FileTime preservedTimestamp = FileTime.fromMillis(1_000);
        Files.setLastModifiedTime(cache, preservedTimestamp);

        new FileHelper(List.of(tempDir), new ScanProgress(), DiskType.HDD, cache).scan();

        assertThat(Files.getLastModifiedTime(cache)).isEqualTo(preservedTimestamp);
    }

    @Test
    void storesVersionedSnapshotAndReplaysIncrementalJournal(@TempDir Path tempDir) throws Exception {
        Path original = Files.writeString(tempDir.resolve("original.jpg"), "same");
        Path duplicate = Files.writeString(tempDir.resolve("duplicate.jpg"), "same");
        Path cache = tempDir.resolve("hashes.properties");
        new FileHelper(List.of(tempDir), new ScanProgress(), DiskType.HDD, cache).scan();

        assertThat(Files.readString(cache)).startsWith("# duplicate-file-remover-hash-cache-v2");
        Files.writeString(duplicate, "diff");
        Files.setLastModifiedTime(duplicate, FileTime.fromMillis(System.currentTimeMillis() + 2_000));
        new FileHelper(List.of(tempDir), new ScanProgress(), DiskType.HDD, cache).scan();

        Path journal = cache.resolveSibling(cache.getFileName() + ".journal");
        assertThat(journal).exists();
        assertThat(Files.readString(journal)).startsWith("# duplicate-file-remover-hash-cache-journal-v2");
        ScanResult journalBackedScan = new FileHelper(
                List.of(tempDir),
                new ScanProgress(),
                DiskType.HDD,
                cache
        ).scan();
        assertThat(journalBackedScan.duplicateGroups()).isEmpty();
        assertThat(readCachedPaths(cache)).containsExactlyInAnyOrder(
                original.toAbsolutePath().normalize().toString(),
                duplicate.toAbsolutePath().normalize().toString()
        );
    }

    @Test
    void migratesLegacyPropertiesCacheToVersionedSnapshot(@TempDir Path tempDir) throws Exception {
        Path original = Files.writeString(tempDir.resolve("original.jpg"), "same");
        Path duplicate = Files.writeString(tempDir.resolve("duplicate.jpg"), "same");
        Path cache = tempDir.resolve("hashes.properties");
        String hash = FileHelper.getSHAHashForFile(original);
        Properties legacy = new Properties();
        for (Path file : List.of(original, duplicate)) {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            legacy.setProperty(
                    file.toAbsolutePath().normalize().toString(),
                    "%d,%d,%d,%s".formatted(
                            attributes.size(),
                            attributes.creationTime().toMillis(),
                            attributes.lastModifiedTime().toMillis(),
                            hash
                    )
            );
        }
        try (OutputStream output = Files.newOutputStream(cache)) {
            legacy.store(output, "Legacy cache");
        }

        ScanResult result = new FileHelper(List.of(tempDir), new ScanProgress(), DiskType.HDD, cache).scan();

        assertThat(result.duplicateCount()).isEqualTo(1);
        assertThat(Files.readString(cache)).startsWith("# duplicate-file-remover-hash-cache-v2");
    }

    @Test
    void removesVersionedCacheEntriesNotSeenForOneHundredEightyDays(@TempDir Path tempDir) throws Exception {
        Path scanRoot = Files.createDirectory(tempDir.resolve("current"));
        Files.writeString(scanRoot.resolve("current.jpg"), "current");
        Path stalePath = tempDir.resolve("outside-current-scan.jpg").toAbsolutePath().normalize();
        Path cache = tempDir.resolve("hashes.properties");
        String staleRecord = "%s\t1\t1\t1\t0\t%s".formatted(
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                        stalePath.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)
                ),
                "0".repeat(64)
        );
        Files.writeString(
                cache,
                "# duplicate-file-remover-hash-cache-v2%n%s%n".formatted(staleRecord)
        );

        new FileHelper(List.of(scanRoot), new ScanProgress(), DiskType.HDD, cache).scan();

        assertThat(readCachedPaths(cache)).doesNotContain(stalePath.toString());
    }

    @Test
    void scanRejectsNonDirectory(@TempDir Path tempDir) throws IOException {
        Path file = Files.writeString(tempDir.resolve("image.jpg"), "content");
        ScanProgress progress = new ScanProgress();

        assertThatThrownBy(() -> new FileHelper(List.of(file), progress).scan())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Not a readable directory");
        assertThat(progress.snapshot().stage()).isEqualTo(ScanProgress.Stage.FAILED);
    }

    @Test
    void scanReportsNonDirectoryRootAsInformationWhenReadableDirectoryRootExists(@TempDir Path tempDir)
            throws Exception {
        Path mediaRoot = Files.createDirectory(tempDir.resolve("2024-photos"));
        Files.writeString(mediaRoot.resolve("image.jpg"), "content");
        Path expandedFile = Files.writeString(tempDir.resolve("20250524_095600.jpg"), "content");
        Path secondExpandedFile = Files.writeString(tempDir.resolve("20250524_095601.jpg"), "content");
        List<String> information = new CopyOnWriteArrayList<>();
        ScanProgress progress = new ScanProgress();
        progress.setInformationHandler(information::add);

        ScanResult result = new FileHelper(List.of(expandedFile, secondExpandedFile, mediaRoot), progress).scan();

        assertThat(result.scannedFiles()).isEqualTo(1);
        assertThat(information).containsExactly("Skipping non-directory scan root.");
    }

    @Test
    void scanDeduplicatesOverlappingRootsWhileProcessingNestedDirectories(@TempDir Path tempDir) throws Exception {
        Path nested = Files.createDirectories(tempDir.resolve("nested").resolve("deeper"));
        Path original = Files.writeString(tempDir.resolve("original.jpg"), "same");
        Path duplicate = Files.writeString(nested.resolve("duplicate.jpg"), "same");

        ScanResult result = new FileHelper(List.of(tempDir, tempDir.resolve("nested"))).scan();

        assertThat(result.scannedFiles()).isEqualTo(2);
        assertThat(result.duplicateCount()).isEqualTo(1);
        assertThat(result.duplicateGroups()).singleElement().satisfies(group ->
                assertThat(List.of(group.original(), group.duplicates().getFirst()))
                        .containsExactlyInAnyOrder(original.toAbsolutePath(), duplicate.toAbsolutePath())
        );
    }

    @Test
    void scanPublishesMonotonicProgressForEveryStage(@TempDir Path tempDir) throws Exception {
        Path nested = Files.createDirectories(tempDir.resolve("nested"));
        Files.writeString(tempDir.resolve("original.jpg"), "same");
        Files.writeString(nested.resolve("duplicate.jpg"), "same");
        List<ScanProgress.Snapshot> updates = new CopyOnWriteArrayList<>();
        ScanProgress progress = new ScanProgress(updates::add);

        ScanResult result = new FileHelper(List.of(tempDir), progress).scan();

        assertThat(updates)
                .extracting(ScanProgress.Snapshot::stage)
                .containsSubsequence(
                        ScanProgress.Stage.DISCOVERING,
                        ScanProgress.Stage.GROUPING_BY_SIZE,
                        ScanProgress.Stage.VALIDATING_HASH_CACHE,
                        ScanProgress.Stage.SAMPLING,
                        ScanProgress.Stage.HASHING,
                        ScanProgress.Stage.FINALIZING,
                        ScanProgress.Stage.COMPLETE
                );
        assertThat(updates).filteredOn(update -> update.stage() == ScanProgress.Stage.GROUPING_BY_SIZE)
                .allSatisfy(update -> assertThat(update.total()).isEqualTo(2));
        assertThat(updates).filteredOn(update -> update.stage() == ScanProgress.Stage.VALIDATING_HASH_CACHE)
                .allSatisfy(update -> assertThat(update.total()).isEqualTo(2));
        assertThat(updates).filteredOn(update -> update.stage() == ScanProgress.Stage.SAMPLING)
                .allSatisfy(update -> assertThat(update.total()).isEqualTo(6));
        assertThat(updates).filteredOn(update -> update.stage() == ScanProgress.Stage.HASHING)
                .allSatisfy(update -> assertThat(update.total()).isEqualTo(2));
        assertThat(updates).filteredOn(update -> update.stage() == ScanProgress.Stage.FINALIZING)
                .allSatisfy(update -> assertThat(update.total()).isEqualTo(1));
        for (ScanProgress.Stage stage : List.of(
                ScanProgress.Stage.GROUPING_BY_SIZE,
                ScanProgress.Stage.VALIDATING_HASH_CACHE,
                ScanProgress.Stage.SAMPLING,
                ScanProgress.Stage.HASHING,
                ScanProgress.Stage.FINALIZING
        )) {
            assertThat(updates).filteredOn(update -> update.stage() == stage)
                    .extracting(ScanProgress.Snapshot::completed)
                    .isSorted();
        }

        ScanProgress.Snapshot completed = progress.snapshot();
        assertThat(completed.stage()).isEqualTo(ScanProgress.Stage.COMPLETE);
        assertThat(completed.mediaFilesDiscovered()).isEqualTo(2);
        assertThat(completed.directoriesProcessed()).isEqualTo(2);
        assertThat(completed.completed()).isEqualTo(completed.total());
        assertThat(result.stageDurations()).containsOnlyKeys(
                ScanProgress.Stage.DISCOVERING,
                ScanProgress.Stage.GROUPING_BY_SIZE,
                ScanProgress.Stage.VALIDATING_HASH_CACHE,
                ScanProgress.Stage.SAMPLING,
                ScanProgress.Stage.HASHING,
                ScanProgress.Stage.FINALIZING
        );
        assertThat(result.stageDurations().values()).allSatisfy(duration ->
                assertThat(duration).isGreaterThanOrEqualTo(Duration.ZERO)
        );
    }

    private static Set<String> readCachedPaths(Path cache) throws IOException {
        Set<String> paths = new HashSet<>();
        for (String line : Files.readAllLines(cache)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            paths.add(decodeCachePath(line.substring(0, line.indexOf('\t'))));
        }
        Path journal = cache.resolveSibling(cache.getFileName() + ".journal");
        if (Files.isRegularFile(journal)) {
            for (String line : Files.readAllLines(journal)) {
                if (line.startsWith("D\t")) {
                    paths.remove(decodeCachePath(line.substring(2)));
                } else if (line.startsWith("P\t")) {
                    String record = line.substring(2);
                    paths.add(decodeCachePath(record.substring(0, record.indexOf('\t'))));
                }
            }
        }
        return paths;
    }

    private static String decodeCachePath(String encoded) {
        return new String(Base64.getUrlDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8);
    }
}
