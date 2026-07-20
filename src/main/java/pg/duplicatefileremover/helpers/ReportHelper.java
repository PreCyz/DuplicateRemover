package pg.duplicatefileremover.helpers;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

public class ReportHelper {
    private static final String TEMPLATE_RESOURCE = "/reportTemplate.html";
    private static final int THUMBNAIL_MAX_WIDTH = 288;
    private static final int THUMBNAIL_MAX_HEIGHT = 192;
    private static final List<ScanProgress.Stage> REPORTED_STAGES = List.of(
            ScanProgress.Stage.DISCOVERING,
            ScanProgress.Stage.GROUPING_BY_SIZE,
            ScanProgress.Stage.VALIDATING_HASH_CACHE,
            ScanProgress.Stage.SAMPLING,
            ScanProgress.Stage.HASHING,
            ScanProgress.Stage.FINALIZING,
            ScanProgress.Stage.VERIFYING_DUPLICATES,
            ScanProgress.Stage.THUMBNAILS
    );

    static {
        ImageIO.setUseCache(false);
    }

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
                .replace("${scanStepsSummary}", createScanStepsSummary())
                .replace("${duplicatesContent}", createTableContent())
                .replace("${apiBase}", escapeJavaScript(links.apiBase()))
                .replace("${apiToken}", escapeJavaScript(links.apiToken()))
                .replace("${deletionWorkerCount}", Integer.toString(links.deletionWorkerCount()));

        Path parent = reportPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(reportPath, report, StandardCharsets.UTF_8);
        return reportPath;
    }

    public static ScanResult retainExistingFiles(ScanResult scanResult) {
        return retainExistingFiles(scanResult, null);
    }

    static ScanResult retainExistingFiles(ScanResult scanResult, ScanProgress progress) {
        return retainExistingFiles(scanResult, progress, true);
    }

    static ScanResult retainExistingFiles(
            ScanResult scanResult,
            ScanProgress progress,
            boolean completeProgress
    ) {
        Objects.requireNonNull(scanResult, "scanResult");
        long filesToVerify = scanResult.duplicateGroups().stream()
                .mapToLong(group -> 1L + group.duplicates().size())
                .sum();
        if (progress != null) {
            progress.begin(ScanProgress.Stage.VERIFYING_DUPLICATES, filesToVerify);
        }
        List<DuplicateGroup> existingGroups = new ArrayList<>();
        for (DuplicateGroup group : scanResult.duplicateGroups()) {
            List<Path> existingFiles = new ArrayList<>();
            if (Files.isRegularFile(group.original(), LinkOption.NOFOLLOW_LINKS)) {
                existingFiles.add(group.original());
            }
            if (progress != null) {
                progress.itemCompleted();
            }
            group.duplicates().stream()
                    .forEach(path -> {
                        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                            existingFiles.add(path);
                        }
                        if (progress != null) {
                            progress.itemCompleted();
                        }
                    });
            if (existingFiles.size() > 1) {
                existingGroups.add(new DuplicateGroup(
                        group.hash(),
                        group.fileSize(),
                        existingFiles.getFirst(),
                        existingFiles.subList(1, existingFiles.size())
                ));
            }
        }
        if (progress == null || !completeProgress) {
            return new ScanResult(
                    scanResult.scannedFiles(),
                    existingGroups,
                    scanResult.duration(),
                    scanResult.stageDurations()
            );
        }
        progress.complete();
        Map<ScanProgress.Stage, Duration> stageDurations = progress.stageDurations();
        Duration verificationDuration = stageDurations.getOrDefault(
                ScanProgress.Stage.VERIFYING_DUPLICATES,
                Duration.ZERO
        );
        return new ScanResult(
                scanResult.scannedFiles(),
                existingGroups,
                scanResult.duration().plus(verificationDuration),
                stageDurations
        );
    }

    public static ReportPreparation prepareReport(
            ScanResult scanResult,
            ScanProgress progress,
            boolean showThumbnails
    ) {
        Objects.requireNonNull(scanResult, "scanResult");
        Objects.requireNonNull(progress, "progress");
        Map<Path, Thumbnail> thumbnails = showThumbnails
                ? generateThumbnails(scanResult, progress)
                : Map.of();
        progress.complete();

        Map<ScanProgress.Stage, Duration> stageDurations = progress.stageDurations();
        Duration additionalDuration = stageDurations.getOrDefault(
                ScanProgress.Stage.VERIFYING_DUPLICATES,
                Duration.ZERO
        ).plus(stageDurations.getOrDefault(
                ScanProgress.Stage.THUMBNAILS,
                Duration.ZERO
        ));
        ScanResult preparedResult = new ScanResult(
                scanResult.scannedFiles(),
                scanResult.duplicateGroups(),
                scanResult.duration().plus(additionalDuration),
                stageDurations
        );
        return new ReportPreparation(preparedResult, thumbnails);
    }

    private static Map<Path, Thumbnail> generateThumbnails(ScanResult scanResult, ScanProgress progress) {
        LinkedHashSet<Path> imagePaths = new LinkedHashSet<>();
        for (DuplicateGroup group : scanResult.duplicateGroups()) {
            if (!isVideo(group.original())) {
                imagePaths.add(group.original().toAbsolutePath().normalize());
            }
            group.duplicates().stream()
                    .filter(path -> !isVideo(path))
                    .map(path -> path.toAbsolutePath().normalize())
                    .forEach(imagePaths::add);
        }

        progress.begin(ScanProgress.Stage.THUMBNAILS, imagePaths.size());
        Map<Path, Thumbnail> thumbnails = new LinkedHashMap<>();
        int failures = 0;
        for (Path imagePath : imagePaths) {
            try {
                Thumbnail thumbnail = createThumbnail(imagePath);
                if (thumbnail != null) {
                    thumbnails.put(imagePath, thumbnail);
                } else {
                    failures++;
                }
            } catch (IOException | RuntimeException exception) {
                failures++;
            } finally {
                progress.itemCompleted();
            }
        }
        if (failures > 0) {
            progress.warning("Could not generate %,d thumbnail%s; those images will be shown without previews."
                    .formatted(failures, failures == 1 ? "" : "s"));
        }
        return Collections.unmodifiableMap(thumbnails);
    }

    private static Thumbnail createThumbnail(Path imagePath) throws IOException {
        BufferedImage source;
        try (InputStream input = Files.newInputStream(imagePath)) {
            source = ImageIO.read(input);
        }
        if (source == null) {
            return null;
        }

        double scale = Math.min(
                1.0,
                Math.min(
                        (double) THUMBNAIL_MAX_WIDTH / source.getWidth(),
                        (double) THUMBNAIL_MAX_HEIGHT / source.getHeight()
                )
        );
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resized.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
            source.flush();
        }

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(resized, "jpeg", output)) {
                return null;
            }
            return new Thumbnail(output.toByteArray(), "image/jpeg");
        } finally {
            resized.flush();
        }
    }

    private String createScanStepsSummary() {
        StringBuilder cards = new StringBuilder();
        for (ScanProgress.Stage stage : REPORTED_STAGES) {
            Duration duration = scanResult.stageDurations().get(stage);
            if (duration == null) {
                continue;
            }
            cards.append("<div class=\"col-6 col-md-4 col-xl\"><div class=\"card summary-card h-100\"><div class=\"card-body\"><div class=\"text-secondary small text-uppercase\">")
                    .append(stageLabel(stage))
                    .append("</div><div class=\"summary-value\">")
                    .append(escapeHtml(formatDuration(duration)))
                    .append("</div></div></div></div>");
        }
        if (cards.isEmpty()) {
            return "";
        }
        return "<section class=\"mb-4\" aria-labelledby=\"scan-steps-title\">"
                + "<h2 id=\"scan-steps-title\" class=\"h5 mb-3\">Scan steps</h2>"
                + "<div class=\"row g-3\">" + cards + "</div></section>";
    }

    private static String stageLabel(ScanProgress.Stage stage) {
        return switch (stage) {
            case DISCOVERING -> "Discovering files";
            case GROUPING_BY_SIZE -> "Grouping by size";
            case VALIDATING_HASH_CACHE -> "Validating cache";
            case SAMPLING -> "Sampling content";
            case HASHING -> "Hashing files";
            case FINALIZING -> "Finalizing results";
            case VERIFYING_DUPLICATES -> "Verifying dups";
            case THUMBNAILS -> "Thumbnails";
            default -> throw new IllegalArgumentException("Unsupported report stage: " + stage);
        };
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
                    .append(createMediaCard(group.original(), group.fileSize(), true))
                    .append("</td><td><div class=\"vstack gap-3\">");
            for (Path duplicate : group.duplicates()) {
                content.append(createMediaCard(duplicate, group.fileSize(), false));
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

    private String createMediaCard(Path path, long fileSize, boolean original) {
        String absolutePath = path.toAbsolutePath().normalize().toString();
        StringBuilder card = new StringBuilder("<div class=\"media-card ")
                .append(original ? "original-card" : "duplicate-card")
                .append("\"");
        if (!original) {
            card.append(" data-duplicate-path=\"").append(escapeHtml(absolutePath))
                    .append("\" data-bytes=\"").append(fileSize).append("\"");
        }
        card.append(">");
        if (!isVideo(path) && links.hasThumbnail(path)) {
            String mediaUrl = escapeHtml(links.mediaUrl(path));
            card.append("<div class=\"media-preview\"><img src=\"").append(mediaUrl)
                    .append("\" loading=\"lazy\" alt=\"Thumbnail of ")
                    .append(escapeHtml(absolutePath)).append("\">");
            card.append("</div>");
        }
        card.append("<div class=\"flex-grow-1 min-width-0\"><div class=\"fw-semibold mb-1\">")
                .append(original ? "Original" : "Duplicate")
                .append("</div><div class=\"path-text\" title=\"")
                .append(escapeHtml(absolutePath)).append("\">")
                .append(escapeHtml(absolutePath)).append("</div></div>");
        if (!original) {
            card.append("<button type=\"button\" class=\"btn btn-primary btn-sm remove-duplicate\" data-path=\"")
                    .append(escapeHtml(absolutePath)).append("\">Remove</button>");
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

        default boolean hasThumbnail(Path path) {
            return true;
        }

        String apiBase();

        String apiToken();

        int deletionWorkerCount();
    }

    public record Thumbnail(byte[] bytes, String contentType) {
        public Thumbnail {
            Objects.requireNonNull(bytes, "bytes");
            Objects.requireNonNull(contentType, "contentType");
        }
    }

    public record ReportPreparation(ScanResult scanResult, Map<Path, Thumbnail> thumbnails) {
        public ReportPreparation {
            Objects.requireNonNull(scanResult, "scanResult");
            thumbnails = Map.copyOf(thumbnails);
        }
    }
}
