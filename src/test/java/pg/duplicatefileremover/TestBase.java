package pg.duplicatefileremover;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public abstract class TestBase {
    protected Path createFile(final String name, final Path tempDir) throws IOException {
        Path filePath = tempDir.resolve(name);
        List<String> lines = Arrays.asList("1", "2", "3");
        Files.write(filePath, lines);
        return filePath;
    }
}
