package duplicatefileremover;

import duplicatefileremover.helpers.FileHelper;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.time.*;

/**
 *
 * @author Gawa
 */
public class DuplicateFileRemover {

    private FileHelper helper;

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
        long oneSecond = 1;
        long oneMinute = 60 * oneSecond;
        long oneHour = 60 * oneMinute;
        Duration duration = Duration.between(begin, end);
        if(duration.getSeconds() >= oneHour){
            return String.format("Czas trwania: %d[h].", duration.getSeconds() / oneHour);
        }
        if(duration.getSeconds() >= oneMinute){
            return String.format("Czas trwania: %d[m].", duration.getSeconds() / oneMinute);
        }
        return String.format("Czas trwania: %d[s].", duration.getSeconds());
    }

    protected static void validateArgs(String[] args) {
        if(args == null || args.length == 0){
            throw new UnsupportedOperationException("Nie podano scieżki do katalogu.");
        }
        if(args[0] == null || "".equals(args[0].trim())){
            throw new UnsupportedOperationException("Niewłaściwy parametr.");
        }
    }
}
