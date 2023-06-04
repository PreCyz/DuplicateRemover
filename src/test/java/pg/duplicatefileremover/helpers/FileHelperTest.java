package pg.duplicatefileremover.helpers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pg.duplicatefileremover.TestBase;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Fail.fail;

/** @author premik */
public class FileHelperTest extends TestBase {

    private static final String FILE_NOT_FOUND = "FileNotFoundException expected.";
    private FileHelper helper;

    @Test
    public void testFileList(@TempDir Path tempDir) throws IOException {
        createFile("number.txt", tempDir);
        helper = new FileHelper(tempDir.toString());
        assertThat(helper.getFileOnlyList()).isNotNull();
        assertThat(helper.getFileOnlyList()).hasSize(1);
    }

    @Test
    public void givenNotValidExtension_whenGetFileOnlyList_thenReturnEmptyList(@TempDir Path tempDir) throws IOException {
        createFile("number.ttf", tempDir);
        helper = new FileHelper(tempDir.toString());
        assertThat(helper.getFileOnlyList()).isNotNull();
        assertThat(helper.getFileOnlyList()).isEmpty();
    }

    @Test
    public void testHashForFile(@TempDir Path tempDir) throws Exception {
        helper = new FileHelper(tempDir.toString());
        try {
            helper.getSHAHashForFile(new File(""));
            fail(FILE_NOT_FOUND);
        } catch (FileNotFoundException ex) {
            assertThat(ex).isNotNull();
        }
    }

    @Test
    void givenSameFile_whenGetSHAHashForFile_thenReturnSameHash(@TempDir Path tempDir) throws Exception {
        Path resource = createFile("number.txt", tempDir);
        helper = new FileHelper(tempDir.toString());
        String fileHash = helper.getSHAHashForFile(resource.toFile());
        String secondFileHash = helper.getSHAHashForFile(resource.toFile());

        assertThat(fileHash).isNotNull();
        assertThat(fileHash).isNotEmpty();
        assertThat(fileHash).isEqualToIgnoringCase(secondFileHash);
    }

    @Test
    public void testGetByteArrayFromFile(@TempDir Path tempDir) throws Exception {
        Path resource = createFile("number.txt", tempDir);
        helper = new FileHelper(tempDir.toString());

        byte[] actual = helper.getByteArrayFromFile(resource.toFile());
        byte[] byteArray = Files.readAllBytes(resource);
        byte[] byteArrayFromFile = helper.getByteArrayFromFile(resource.toFile());

        assertThat(actual).isNotNull();
        assertThat(actual).isEqualTo(byteArray);
        assertThat(actual).containsExactly(byteArrayFromFile);
    }

    @Test
    void givenNoFile_whenGetByteArrayFromFile_thenThrowFileNotFoundException(@TempDir Path tempDir) {
        helper = new FileHelper(tempDir.toString());
        try {
            helper.getByteArrayFromFile(new File(""));
        } catch (Exception ex) {
            assertThat(ex).isInstanceOf(FileNotFoundException.class);
        }
    }

    @Test
    public void testMoveDuplicates(@TempDir Path tempDir) throws Exception {
        helper = new FileHelper(tempDir.toString());
        List<File> duplicatesList = helper.createDuplicatesList(helper.createPossibleDuplicates());

        helper.moveDuplicates(duplicatesList);

        assertThat(duplicatesList).isNotNull();
        assertThat(duplicatesList).isEmpty();
    }

    @Test
    public void testFolderCreation(@TempDir Path tmpDir) throws Exception {
        Path dir = tmpDir.resolve("duplicates");
        helper = new FileHelper(tmpDir.toString());
        helper.createDuplicateDirIfNotExists();
        assertThat(Files.isDirectory(dir)).isTrue();
        assertThat(Files.deleteIfExists(dir)).isTrue();
    }
}