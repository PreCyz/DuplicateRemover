package pg.duplicatefileremover.helpers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.StringJoiner;

public class ReportHelper {
    private static final String TEMPLATE_RESOURCE = "/reportTemplate.html";

    private final ScanResult scanResult;
    private final Path reportPath;
    private final ReportLinks links;

    public ReportHelper(ScanResult scanResult, Path reportPath, ReportLinks links) {
        this.scanResult = scanResult;
        this.reportPath = reportPath.toAbsolutePath().normalize();
        this.links = links;
    }

    public Path createReport() throws IOException {
        String template;
        try (InputStream input = ReportHelper.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (input == null) {
                throw new IOException("Missing report template: " + TEMPLATE_RESOURCE);
            }
            template = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        String report = template
                .replace("${bootstrapCssUrl}", escapeHtml(links.bootstrapCssUrl()))
                .replace("${scannedFiles}", Long.toString(scanResult.scannedFiles()))
                .replace("${duplicateCount}", Long.toString(scanResult.duplicateCount()))
                .replace("${duplicateBytesRaw}", Long.toString(scanResult.duplicateBytes()))
                .replace("${duplicateBytes}", escapeHtml(formatBytes(scanResult.duplicateBytes())))
                .replace("${scanDuration}", escapeHtml(formatDuration(scanResult.duration())))
                .replace("${duplicatesContent}", createTableContent())
                .replace("${apiBase}", escapeJavaScript(links.apiBase()))
                .replace("${apiToken}", escapeJavaScript(links.apiToken()));

        Path parent = reportPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(reportPath, report, StandardCharsets.UTF_8);
        return reportPath;
    }

    private String createTableContent() {
        if (scanResult.duplicateGroups().isEmpty()) {
            return "<tr id=\"empty-result\"><td colspan=\"4\" class=\"text-center py-5 text-secondary\">No duplicate media files found.</td></tr>";
        }

        StringBuilder content = new StringBuilder();
        int groupNumber = 1;
        for (DuplicateGroup group : scanResult.duplicateGroups()) {
            content.append("<tr class=\"duplicate-group\" data-group=\"")
                    .append(groupNumber)
                    .append("\"><td class=\"align-middle\"><span class=\"badge text-bg-secondary\">#")
                    .append(groupNumber)
                    .append("</span></td><td>")
                    .append(createMediaCard(group.original(), null, group.fileSize(), true))
                    .append("</td><td><div class=\"vstack gap-3\">");
            for (Path duplicate : group.duplicates()) {
                content.append(createMediaCard(duplicate, links.duplicateId(duplicate), group.fileSize(), false));
            }
            content.append("</div></td><td class=\"align-middle text-nowrap\"><div>")
                    .append(escapeHtml(formatBytes(group.fileSize())))
                    .append(" each</div><code class=\"small text-break\">")
                    .append(escapeHtml(group.hash()))
                    .append("</code></td></tr>");
            groupNumber++;
        }
        return content.toString();
    }

    private String createMediaCard(Path path, String duplicateId, long fileSize, boolean original) {
        String absolutePath = path.toAbsolutePath().normalize().toString();
        String mediaUrl = escapeHtml(links.mediaUrl(path));
        StringBuilder card = new StringBuilder("<div class=\"media-card ")
                .append(original ? "original-card" : "duplicate-card")
                .append("\"");
        if (duplicateId != null) {
            card.append(" data-duplicate-id=\"").append(escapeHtml(duplicateId))
                    .append("\" data-bytes=\"").append(fileSize).append("\"");
        }
        card.append("><div class=\"media-preview\">");
        if (isVideo(path)) {
            card.append("<video src=\"").append(mediaUrl)
                    .append("\" preload=\"metadata\" muted controls aria-label=\"Preview of ")
                    .append(escapeHtml(absolutePath)).append("\"></video>");
        } else {
            card.append("<img src=\"").append(mediaUrl).append("\" loading=\"lazy\" alt=\"Thumbnail of ")
                    .append(escapeHtml(absolutePath)).append("\">");
        }
        card.append("</div><div class=\"flex-grow-1 min-width-0\"><div class=\"fw-semibold mb-1\">")
                .append(original ? "Original" : "Duplicate")
                .append("</div><div class=\"path-text\" title=\"")
                .append(escapeHtml(absolutePath)).append("\">")
                .append(escapeHtml(absolutePath)).append("</div></div>");
        if (duplicateId != null) {
            card.append("<button type=\"button\" class=\"btn btn-primary btn-sm remove-duplicate\" data-id=\"")
                    .append(escapeHtml(duplicateId)).append("\">Remove</button>");
        }
        return card.append("</div>").toString();
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.2f %s", value, units[unit]);
    }

    static String formatDuration(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();
        long millis = duration.toMillisPart();
        StringJoiner formatted = new StringJoiner(" ");
        if (hours != 0) {
            formatted.add(String.format(Locale.ROOT, "%02dh", hours));
        }
        if (minutes != 0) {
            formatted.add(String.format(Locale.ROOT, "%02dm", minutes));
        }
        if (seconds != 0) {
            formatted.add(String.format(Locale.ROOT, "%02ds", seconds));
        }
        if (millis != 0) {
            formatted.add(String.format(Locale.ROOT, "%03dms", millis));
        }
        return formatted.length() == 0 ? "0ms" : formatted.toString();
    }

    private static boolean isVideo(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".3gp") || name.endsWith(".m4v");
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String escapeJavaScript(String value) {
        return value.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("</", "<\\/");
    }

    public interface ReportLinks {
        String bootstrapCssUrl();

        String mediaUrl(Path path);

        String duplicateId(Path path);

        String apiBase();

        String apiToken();
    }
}
