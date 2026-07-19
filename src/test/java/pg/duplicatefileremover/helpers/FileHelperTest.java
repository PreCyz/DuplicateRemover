package pg.duplicatefileremover.helpers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pg.duplicatefileremover.TestBase;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileHelperTest extends TestBase {

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
    void scanRejectsNonDirectory(@TempDir Path tempDir) throws IOException {
        Path file = Files.writeString(tempDir.resolve("image.jpg"), "content");
        ScanProgress progress = new ScanProgress();

        assertThatThrownBy(() -> new FileHelper(List.of(file), progress).scan())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Not a readable directory");
        assertThat(progress.snapshot().stage()).isEqualTo(ScanProgress.Stage.FAILED);
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

        new FileHelper(List.of(tempDir), progress).scan();

        assertThat(updates)
                .extracting(ScanProgress.Snapshot::stage)
                .containsSubsequence(
                        ScanProgress.Stage.DISCOVERING,
                        ScanProgress.Stage.READING_METADATA,
                        ScanProgress.Stage.HASHING,
                        ScanProgress.Stage.FINALIZING,
                        ScanProgress.Stage.COMPLETE
                );
        assertThat(updates).filteredOn(update -> update.stage() == ScanProgress.Stage.READING_METADATA)
                .allSatisfy(update -> assertThat(update.total()).isEqualTo(2));
        assertThat(updates).filteredOn(update -> update.stage() == ScanProgress.Stage.HASHING)
                .allSatisfy(update -> assertThat(update.total()).isEqualTo(2));
        assertThat(updates).filteredOn(update -> update.stage() == ScanProgress.Stage.FINALIZING)
                .allSatisfy(update -> assertThat(update.total()).isEqualTo(1));
        for (ScanProgress.Stage stage : List.of(
                ScanProgress.Stage.READING_METADATA,
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
    }
}
