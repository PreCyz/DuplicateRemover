package pg.duplicatefileremover;

import pg.duplicatefileremover.helpers.*;

import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class DuplicateFileRemover {
    private static final Path REPORT_PATH = Path.of(System.getProperty(
            "duplicate.report.path",
            Path.of("reports", "duplicates-report.html").toString()
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
            validateArgs(args);
            List<Path> roots = Arrays.stream(args).map(Path::of).toList();
            ScanProgress progress = new ScanProgress();
            ScanResult result;
            try (TerminalProgressBar progressBar = new TerminalProgressBar(progress)) {
                result = new FileHelper(roots, progress).scan();
            }

            try (ReportServer server = new ReportServer(result, REPORT_PATH)) {
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

    protected static void validateArgs(String[] args) {
        if (args == null || args.length == 0) {
            throw new UnsupportedOperationException("Path to folder not specified.");
        }
        for (String argument : args) {
            if (argument == null || argument.isBlank()) {
                throw new IllegalArgumentException("Illegal argument.");
            }
        }
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
            IO.println("All report tabs closed; stopping the report server.");
        }
    }
}
