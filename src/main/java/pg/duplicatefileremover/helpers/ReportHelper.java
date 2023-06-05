package pg.duplicatefileremover.helpers;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class ReportHelper {
    private static final Path REPORT_TEMPLATE = Paths.get(".", "src", "main", "resources", "reportTemplate.html");
    private static final Path DEFAULT_REPORT_PATH = Paths.get(".", "report.html");
    private static final String LINE_TEMPLATE_VALUE = "${duplicatesContent}";

    private final Map<Long, DuplicateDTO> duplicatesMap;
    private final Path reportPath;

    public ReportHelper(Map<Long, DuplicateDTO> duplicatesMap, Path reportPath) {
        this.duplicatesMap = new LinkedHashMap<>(duplicatesMap);
        this.reportPath = reportPath;
    }

    public ReportHelper(Map<Long, DuplicateDTO> duplicatesMap) {
        this(duplicatesMap, DEFAULT_REPORT_PATH);
    }

    public void createReport() {
        try (Scanner scanner = new Scanner(REPORT_TEMPLATE.toAbsolutePath(), StandardCharsets.UTF_8);
             FileWriter fileWriter = new FileWriter(reportPath.toFile(), StandardCharsets.UTF_8)
        ) {
            while (scanner.hasNextLine()) {
                String nextLine = scanner.nextLine();
                if (nextLine.contains(LINE_TEMPLATE_VALUE)) {
                    writeToFile(duplicatesMap, fileWriter, nextLine);
                } else {
                    fileWriter.write(nextLine);
                    fileWriter.flush();
                }
            }
        } catch (IOException ex) {
            System.err.printf("Can't read the [%s].%n", REPORT_TEMPLATE);
        } finally {
            if (duplicatesMap.values().stream().noneMatch(dto -> dto.sameFiles.size() > 1)) {
                try {
                    Files.deleteIfExists(reportPath);
                } catch (IOException e) {
                    System.err.printf("Couldn't remove [%s].%n", reportPath);
                }
            } else {
                System.out.printf("Report generated [%s].%n", reportPath);
            }
        }
    }

    private void writeToFile(Map<Long, DuplicateDTO> duplicatesMap, FileWriter fileWriter, String lineTemplate) throws IOException {
        if (duplicatesMap != null && !duplicatesMap.isEmpty()) {
            for (Map.Entry<Long, DuplicateDTO> entry : duplicatesMap.entrySet()) {
                if (entry.getValue().sameFiles.size() > 1) {
                    fileWriter.write(createTableContent(entry.getValue(), lineTemplate));
                }
            }
            fileWriter.flush();
        }
    }

    private String createTableContent(DuplicateDTO duplicateDto, String lineTemplate) {
        return "<tr>" +
                "<td>" + lineTemplate.replace(LINE_TEMPLATE_VALUE, String.valueOf(duplicateDto.size)) + "</td>" +
                "<td>" + lineTemplate.replace(LINE_TEMPLATE_VALUE, duplicateDto.fileHash) + "</td>" +
                "<td>" + lineTemplate.replace(
                        LINE_TEMPLATE_VALUE,
                        duplicateDto.sameFiles.stream()
                                .map(f -> f.toPath().toAbsolutePath().toString())
                                .collect(Collectors.joining("<br/>"))
                ) + "</td>" +
                "</tr>";
    }
}
