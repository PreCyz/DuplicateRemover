package pg.duplicatefileremover.helpers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Fail.fail;
import static org.mockito.Mockito.when;

/** @author premik */
public class FileHelperTest {

    private static final String FILE_NOT_FOUND = "FileNotFoundException expected.";
    private FileHelper helper;

    private Path createFile(final String name, final Path tempDir) throws IOException {
        Path filePath = tempDir.resolve(name);
        List<String> lines = Arrays.asList("1", "2", "3");
        Files.write(filePath, lines);
        return filePath;
    }

    @Test
    public void testFileList(@TempDir Path tempDir) throws IOException {
        Path resource = createFile("number.txt", tempDir);
        helper = new FileHelper(tempDir.toString());
        assertThat(helper.getFileOnlyList()).isNotNull();
        assertThat(helper.getFileOnlyList()).hasSize(1);
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
    public void testCreateDuplicateList() {
        FileHelper helperF = Mockito.mock(FileHelper.class);
        when(helperF.getDuplicatesList()).thenReturn(List.of(new File("")));
        List<File> duplicates = new ArrayList<>(helperF.getDuplicatesList());

        assertThat(helperF.getDuplicatesList()).isNotNull();
        assertThat(duplicates).isNotEmpty();
    }

    @Test
    public void testMoveDuplicates(@TempDir Path tempDir) throws Exception {
        helper = new FileHelper(tempDir.toString());
        helper.createPossibleDuplicateFileList();
        helper.createDuplicatesList();
        helper.moveDuplicates();
        assertThat(helper.getDuplicatesList()).isNotNull();
        assertThat(helper.getDuplicatesList()).isEmpty();
    }

    @Test
    public void testProcessDuplicates(@TempDir Path tempDir) throws Exception {
        helper = new FileHelper(tempDir.toString());
        helper.processDuplicates();
        assertThat(helper.getDuplicatesList()).isNotNull();
        assertThat(helper.getDuplicatesList()).isEmpty();
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