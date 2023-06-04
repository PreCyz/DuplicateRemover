package pg.duplicatefileremover.helpers;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class ReportHelper {
    private final static Path REPORT_TEMPLATE = Paths.get(".", "src", "main", "resources", "reportTemplate.html");
    private final static Path DEFAULT_REPORT_PATH = Paths.get(".", "report.html");

    private final List<File> possibleDuplicates;
    private final List<File> duplicates;
    private final Path reportPath;

    public ReportHelper(List<File> possibleDuplicates, List<File> duplicates, Path reportPath) {
        this.possibleDuplicates = new ArrayList<>(possibleDuplicates);
        this.duplicates = new ArrayList<>(duplicates);
        this.reportPath = reportPath;
    }

    public ReportHelper(List<File> possibleDuplicates, List<File> duplicates) {
        this(possibleDuplicates, duplicates, DEFAULT_REPORT_PATH);
    }

    public void createReport() {
        try (Scanner scanner = new Scanner(REPORT_TEMPLATE.toAbsolutePath(), StandardCharsets.UTF_8);
             FileWriter fileWriter = new FileWriter(reportPath.toFile(), StandardCharsets.UTF_8)
        ) {
            while (scanner.hasNextLine()) {
                String nextLine = scanner.nextLine();
                if ("${duplicatesContent}".equals(nextLine.trim())) {
                    writeToFile(duplicates, fileWriter);
                } else if ("${possibleDuplicatesContent}".equals(nextLine.trim())) {
                    writeToFile(possibleDuplicates, fileWriter);
                } else {
                    fileWriter.write(nextLine);
                    fileWriter.flush();
                }
            }
        } catch (IOException ex) {
            System.err.printf("Can't read the [%s]%n", REPORT_TEMPLATE);
        } finally {
            if (duplicates.isEmpty() && possibleDuplicates.isEmpty()) {
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

    private void writeToFile(List<File> duplicates, FileWriter fileWriter) throws IOException {
        if (duplicates != null && !duplicates.isEmpty()) {
            for (File file : duplicates) {
                fileWriter.write(createTableContent(file));
            }
            fileWriter.flush();
        }
    }

    private String createTableContent(File file) {
        try {
            return "<tr>" +
                    "<td>" + file.getName() + "</td>" +
                    "<td>" + file.getAbsolutePath() + "</td>" +
                    "<td>" + Files.readAllBytes(file.toPath()).length + "</td>" +
                    "</tr>";
        } catch (IOException e) {
            System.err.printf("Could not create row for file [%s]%n", file.getAbsolutePath());
            return "";
        }
    }
}
