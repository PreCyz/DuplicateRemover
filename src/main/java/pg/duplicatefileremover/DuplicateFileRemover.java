package pg.duplicatefileremover;

import pg.duplicatefileremover.helpers.FileHelper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalTime;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.*;

/**
 * @author Gawa
 */
public class DuplicateFileRemover {

    private final FileHelper helper;
    private static final Executor executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    private static final List<Runnable> runnables = new LinkedList<>();

    public DuplicateFileRemover(String srcDirPath) {
        this.helper = new FileHelper(srcDirPath);
    }

    FileHelper getHelper() {
        return helper;
    }

    public static void main(String[] args) {
        LocalTime start = LocalTime.now();
        try {
            validateArgs(args);
            for (String arg : args) {
                processPath(arg);
            }
            System.out.printf("Processing %d folders.%n", runnables.size());
            CompletableFuture.allOf(
                    runnables.stream()
                            .map(it -> CompletableFuture.runAsync(it, executor))
                            .toArray(CompletableFuture[]::new)
            ).join();

            System.out.printf("%d sizes processed.%n", runnables.size());
            System.out.printf("Unique extensions [%s].%n", String.join(",", FileHelper.extensions));
        } catch (UnsupportedOperationException ex) {
            System.out.println(ex.getMessage());
        } finally {
            System.out.printf("All done - total duration %s.%n", getDurationInfo(start, LocalTime.now()));
            System.exit(0);
        }
    }

    private static void processPath(String path) {
        File[] files = Paths.get(path).toFile().listFiles();
        if (files == null) return;

        LinkedList<File> dirList = new LinkedList<>();
        for (File dir : files) {
            if (dir.isDirectory()) {
                dirList.add(dir);
            }
        }
        if (!dirList.isEmpty()) {
            dirList.forEach(d -> processPath(d.getAbsolutePath()));
        }
        runnables.add(() -> processDir(path));
        System.out.printf("[%s] added to process. Thread [%s].%n", path, Thread.currentThread().getName());
    }

    private static void processDir(String arg) {
        System.out.printf("Processing path [%s] thread [%s].%n", arg, Thread.currentThread().getName());
        try {
            LocalTime start = LocalTime.now();
            DuplicateFileRemover dfr = new DuplicateFileRemover(arg);
            dfr.helper.processDuplicates();
            LocalTime stop = LocalTime.now();
            System.out.printf("Finished [%s] - duration: %s Thread [%s].%n",
                    arg,
                    getDurationInfo(start, stop),
                    Thread.currentThread().getName()
            );
        } catch (NoSuchAlgorithmException | IOException ex) {
            System.err.printf("Thread [%s], Path [%s] finished with error %s%n",
                    Thread.currentThread().getName(),
                    arg,
                    ex.getMessage()
            );
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
        if (args[0] == null || "".equals(args[0].trim())) {
            throw new IllegalArgumentException("Illegal argument.");
        }
    }
}
