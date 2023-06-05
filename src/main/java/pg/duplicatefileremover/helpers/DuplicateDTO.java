package pg.duplicatefileremover.helpers;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

public class DuplicateDTO {
    public final Long size;
    public final List<File> sameFiles;
    public String fileHash;
    public Path processedDir;

    public DuplicateDTO(Long size, List<File> sameFiles, Path processedDir) {
        this(size, sameFiles, "", processedDir);
    }

    DuplicateDTO(Long size, List<File> sameFiles, String fileHash, Path processedDir) {
        this.size = size;
        this.sameFiles = sameFiles;
        this.fileHash = fileHash;
        this.processedDir = processedDir;
    }
}
