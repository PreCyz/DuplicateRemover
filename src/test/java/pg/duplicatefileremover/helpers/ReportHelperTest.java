package pg.duplicatefileremover.helpers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pg.duplicatefileremover.TestBase;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class ReportHelperTest extends TestBase {

    private ReportHelper reportHelper;

    @Test
    void givenEmptyList_whenCreateReport_thenGenerateEmptyReport(@TempDir Path tempDir) throws IOException {
        Path resource = createFile("number.txt", tempDir);
        Map<Long, DuplicateDTO> duplicatesMap = new LinkedHashMap<>();
        duplicatesMap.put(
                resource.toFile().length(),
                new DuplicateDTO(resource.toFile().length(), "hash", List.of(resource.toFile(), resource.toFile()))
        );

        reportHelper = new ReportHelper(duplicatesMap, tempDir.resolve("report.html"));
        reportHelper.createReport();
        assertThat(tempDir.resolve("report.html").toFile().exists()).isTrue();

    }
}