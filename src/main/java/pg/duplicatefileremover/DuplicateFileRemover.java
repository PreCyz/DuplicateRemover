package pg.duplicatefileremover;

import pg.duplicatefileremover.helpers.*;

import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalTime;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

public class DuplicateFileRemover {
    private static final Path REPORT_PATH = Path.of(System.getProperty(
            "duplicate.report.path",
            Path.of("reports", "duplicates-report.html").toString()
    ));
    private static final Path HASH_CACHE_PATH = Path.of(System.getProperty(
            "duplicate.hash.cache.path",
            Path.of("reports", "hash-cache.properties").toString()
    ));

    private final FileHelper helper;

    public DuplicateFileRemover(String sourceDirectory) {
        this.helper = new FileHelper(sourceDirectory);
    }

    FileHelper getHelper() {
        return helper;
    }

    public static void main(String[] args) {
        try {
            ApplicationArguments arguments = parseArguments(args);
            startupInfo(arguments.diskType()).forEach(IO::println);
            ScanProgress progress = new ScanProgress();
            ScanResult result;
            try (TerminalProgressBar progressBar = new TerminalProgressBar(progress)) {
                result = new FileHelper(arguments.roots(), progress, arguments.diskType(), HASH_CACHE_PATH).scan();
            }

            try (ReportServer server = new ReportServer(result, REPORT_PATH, arguments.diskType())) {
                Path report = new ReportHelper(result, REPORT_PATH, server).createReport();
                server.start();
                System.out.printf("Scanned %d media files and found %d duplicates (%s).%n",
                        result.scannedFiles(),
                        result.duplicateCount(),
                        ReportHelper.formatBytes(result.duplicateBytes()));
                System.out.printf("Report written to [%s].%n", report);
                System.out.printf("Open [%s] to review or remove duplicates.%n", server.reportUri());
                openBrowser(server);
                waitForExit(server);
            }
        } catch (IllegalArgumentException | UnsupportedOperationException | IOException | NoSuchAlgorithmException exception) {
            System.err.println("Duplicate scan failed: " + exception.getMessage());
        }
    }

    public static String getDurationInfo(LocalTime begin, LocalTime end) {
        Duration duration = Duration.between(begin, end);
        return String.format("%d[h]:%d[m]:%d[s]:%d[milli]",
                duration.toHoursPart(),
                duration.toMinutesPart(),
                duration.toSecondsPart(),
                duration.toMillisPart()
        );
    }

    static String scanConcurrencyInfo(DiskType diskType) {
        FileHelper.ScanProfile profile = FileHelper.scanProfile(diskType);
        return "Disk type: %s. Using up to %d traversal, %d sampling, %d hashing, and %d deletion virtual threads."
                .formatted(
                        diskType.displayName(),
                        profile.traversalWorkers(),
                        profile.samplingWorkers(),
                        profile.hashingWorkers(),
                        profile.deletionWorkers()
                );
    }

    static List<String> startupInfo(DiskType diskType) {
        return List.of(supportedMediaInfo(), scanConcurrencyInfo(diskType));
    }

    static String supportedMediaInfo() {
        String extensions = String.join(", ", Arrays.stream(FileExtension.values())
                .map(extension -> "." + extension.extension)
                .toList());
        return "Supported media types to inspect: " + extensions + ".";
    }

    static String missingHeartbeatInfo(Duration heartbeatSilence) {
        return "No browser heartbeat was registered for %d seconds; stopping the report server."
                .formatted(heartbeatSilence.toSeconds());
    }

    protected static void validateArgs(String[] args) {
        parseArguments(args);
    }

    static ApplicationArguments parseArguments(String[] args) {
        if (args == null || args.length == 0) {
            throw new UnsupportedOperationException("Path to folder not specified.");
        }
        DiskType diskType = DiskType.HDD;
        boolean diskTypeSpecified = false;
        List<Path> roots = new ArrayList<>();
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            if (argument == null || argument.isBlank()) {
                throw new IllegalArgumentException("Illegal argument.");
            }
            String diskValue = null;
            if (argument.startsWith("--disk=")) {
                diskValue = argument.substring("--disk=".length());
            } else if ("--disk".equals(argument)) {
                if (++index >= args.length) {
                    throw new IllegalArgumentException("Missing value after --disk; expected HDD or NVMe.");
                }
                diskValue = args[index];
            } else if (argument.startsWith("--")) {
                throw new IllegalArgumentException("Unsupported argument: " + argument);
            } else {
                roots.addAll(expandRootArgument(argument));
            }
            if (diskValue != null) {
                if (diskTypeSpecified) {
                    throw new IllegalArgumentException("Disk type may be specified only once.");
                }
                diskType = DiskType.fromArgument(diskValue);
                diskTypeSpecified = true;
            }
        }
        if (roots.isEmpty()) {
            throw new UnsupportedOperationException("Path to folder not specified.");
        }
        return new ApplicationArguments(List.copyOf(roots), diskType);
    }

    private static List<Path> expandRootArgument(String argument) {
        if (!containsWildcard(argument)) {
            return List.of(Path.of(argument));
        }

        int separator = Math.max(argument.lastIndexOf('/'), argument.lastIndexOf('\\'));
        boolean driveRelativePattern = separator < 0
                && argument.length() > 2
                && Character.isLetter(argument.charAt(0))
                && argument.charAt(1) == ':';
        String parentText;
        String directoryPattern;
        if (driveRelativePattern) {
            parentText = argument.substring(0, 2) + FileSystems.getDefault().getSeparator();
            directoryPattern = argument.substring(2);
        } else {
            parentText = separator < 0 ? "." : argument.substring(0, separator + 1);
            directoryPattern = argument.substring(separator + 1);
        }
        if (directoryPattern.isEmpty() || containsWildcard(parentText)) {
            throw new IllegalArgumentException(
                    "Wildcards are supported only in the final directory name: " + argument
            );
        }

        Path parent = Path.of(parentText);
        PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + directoryPattern);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid directory pattern: " + argument, exception);
        }

        List<Path> matches;
        try (Stream<Path> entries = Files.list(parent)) {
            matches = entries
                    .filter(Files::isDirectory)
                    .filter(path -> matcher.matches(path.getFileName()))
                    .map(path -> path.toAbsolutePath().normalize())
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException | SecurityException exception) {
            throw new IllegalArgumentException(
                    "Cannot inspect wildcard directory [%s]: %s".formatted(parent, exception.getMessage()),
                    exception
            );
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("No directories match pattern: " + argument);
        }
        return matches;
    }

    private static boolean containsWildcard(String value) {
        return value.indexOf('*') >= 0 || value.indexOf('?') >= 0;
    }

    private static void openBrowser(ReportServer server) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            return;
        }
        try {
            Desktop.getDesktop().browse(server.reportUri());
        } catch (IOException | UnsupportedOperationException exception) {
            System.err.println("Could not open the browser automatically: " + exception.getMessage());
        }
    }

    private static void waitForExit(ReportServer server) throws IOException {
        IO.println("Keep this window open while using Remove buttons.");
        IO.println("Close all report tabs or press Enter to stop the report server.");

        CompletableFuture<Void> terminalExit = new CompletableFuture<>();
        Thread.ofVirtual().name("terminal-exit-waiter").start(() -> {
            try {
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
                terminalExit.complete(null);
            } catch (IOException exception) {
                terminalExit.completeExceptionally(exception);
            }
        });

        CompletableFuture<Void> browserExit = server.browserSessionsEnded().toCompletableFuture();
        try {
            CompletableFuture.anyOf(terminalExit, browserExit).join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw exception;
        }
        if (browserExit.isDone() && !terminalExit.isDone()) {
            IO.println(missingHeartbeatInfo(server.heartbeatSilenceBeforeShutdown()));
        }
    }

    record ApplicationArguments(List<Path> roots, DiskType diskType) {
    }
}
