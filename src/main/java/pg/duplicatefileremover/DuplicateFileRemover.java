package pg.duplicatefileremover;

import pg.duplicatefileremover.helpers.FileHelper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalTime;
import java.util.LinkedList;

/**
 * @author Gawa
 */
public class DuplicateFileRemover {

    private final FileHelper helper;

    public DuplicateFileRemover(String srcDirPath) {
        this.helper = new FileHelper(srcDirPath);
    }

    public FileHelper getHelper() {
        return helper;
    }

    public static void main(String[] args) {
        try {
            validateArgs(args);
            for (String arg : args) {
                processPath(arg);

            }
        } catch (UnsupportedOperationException ex) {
            System.out.println(ex.getMessage());
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
        processDir(path);
    }

    private static void processDir(String arg) {
        System.out.printf("Processing path [%s]. ", arg);
        try {
            LocalTime start = LocalTime.now();
            DuplicateFileRemover dfr = new DuplicateFileRemover(arg);
            dfr.getHelper().processDuplicates();
            LocalTime stop = LocalTime.now();
            System.out.printf("Finished - duration: %s%n", dfr.getDurationInfo(start, stop));
        } catch (NoSuchAlgorithmException | IOException ex) {
            System.err.printf("Path [%s] finished with error %s%n", arg, ex.getMessage());
        }
    }

    public String getDurationInfo(LocalTime begin, LocalTime end) {
        Duration duration = Duration.between(begin, end);
        return String.format("%d[h]:%d[m]:%d[s]:%d[milli].",
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
