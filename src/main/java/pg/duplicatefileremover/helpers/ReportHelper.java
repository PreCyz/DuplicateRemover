package pg.duplicatefileremover.helpers;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class ReportHelper {
    private final static Path REPORT_TEMPLATE = Paths.get(".", "src", "main", "resources", "reportTemplate.html");
    private final static Path DEFAULT_REPORT_PATH = Paths.get(".", "report.html");

    private final Map<String, List<File>> duplicatesMap;
    private final Path reportPath;

    public ReportHelper(Map<String, List<File>> duplicatesMap, Path reportPath) {
        this.duplicatesMap = new LinkedHashMap<>(duplicatesMap);
        this.reportPath = reportPath;
    }

    public ReportHelper(Map<String, List<File>> duplicatesMap) {
        this(duplicatesMap, DEFAULT_REPORT_PATH);
    }

    public void createReport() {
        try (Scanner scanner = new Scanner(REPORT_TEMPLATE.toAbsolutePath(), StandardCharsets.UTF_8);
             FileWriter fileWriter = new FileWriter(reportPath.toFile(), StandardCharsets.UTF_8)
        ) {
            while (scanner.hasNextLine()) {
                String nextLine = scanner.nextLine();
                if ("${duplicatesContent}".equals(nextLine.trim())) {
                    writeToFile(duplicatesMap, fileWriter);
                } else {
                    fileWriter.write(nextLine);
                    fileWriter.flush();
                }
            }
        } catch (IOException ex) {
            System.err.printf("Can't read the [%s]%n", REPORT_TEMPLATE);
        } finally {
            if (duplicatesMap.values().stream().noneMatch(l -> l.size() > 1)) {
                try {
                    Files.deleteIfExists(reportPath);
                } catch (IOException e) {
                    System.err.printf("Couldn't remove %s%n", reportPath);
                }
            } else {
                System.out.printf("Report generated [%s]%n.", reportPath);
            }
        }

    }

    private void writeToFile(Map<String, List<File>> duplicatesMap, FileWriter fileWriter) throws IOException {
        if (duplicatesMap != null && !duplicatesMap.isEmpty()) {
            for (Map.Entry<String, List<File>> entry : duplicatesMap.entrySet()) {
                if (entry.getValue().size() > 1) {
                    fileWriter.write(createTableContent(entry.getKey(), entry.getValue()));
                }
            }
            fileWriter.flush();
        }
    }

    private String createTableContent(String size, List<File> files) {
        return "<tr>" +
                "<td>" + size + "</td>" +
                "<td>" + files.stream()
                        .map(f -> f.toPath().toAbsolutePath().toString())
                        .collect(Collectors.joining("<br/>")) + "</td>" +
                "</tr>";

    }
}
