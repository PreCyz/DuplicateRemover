package pg.duplicatefileremover;

import pg.duplicatefileremover.helpers.FileHelper;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.time.*;

/** @author Gawa */
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
            String sourcePath = args[0];
            LocalTime start = LocalTime.now();
            DuplicateFileRemover dfr = new DuplicateFileRemover(sourcePath);
            dfr.getHelper().processDuplicates();
            LocalTime stop = LocalTime.now();
            System.out.println(dfr.getDurationInfo(start, stop));
        } catch (NoSuchAlgorithmException | IOException | UnsupportedOperationException ex) {
            System.out.println(ex.getMessage());
        }
    }

    public String getDurationInfo(LocalTime begin, LocalTime end) {
        Duration duration = Duration.between(begin, end);
        return String.format("Duration: %d[h]:%d[m]:%d[s].",
                duration.toHoursPart(),
                duration.toMinutesPart(),
                duration.toSecondsPart()
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
