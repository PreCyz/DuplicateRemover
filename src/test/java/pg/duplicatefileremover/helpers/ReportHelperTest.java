package pg.duplicatefileremover.helpers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pg.duplicatefileremover.TestBase;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportHelperTest extends TestBase {

    private ReportHelper reportHelper;

    @Test
    void givenEmptyList_whenCreateReport_thenGenerateEmptyReport(@TempDir Path tempDir) throws IOException {
        List<File> duplicates = List.of(createFile("number.txt", tempDir).toFile());
        reportHelper = new ReportHelper(duplicates, duplicates, tempDir.resolve("report.html"));
        reportHelper.createReport();
        assertThat(tempDir.resolve("report.html").toFile().exists()).isTrue();

    }
}