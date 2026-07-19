package pg.duplicatefileremover.helpers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pg.duplicatefileremover.TestBase;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;

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

        assertThatThrownBy(() -> new FileHelper(file.toString()).scan())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Not a readable directory");
    }
}
