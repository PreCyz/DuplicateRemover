package pg.duplicatefileremover.helpers;

import java.io.File;
import java.util.List;

public class DuplicateDTO {
    public final Long size;
    public String fileHash;
    public final List<File> sameFiles;

    public DuplicateDTO(Long size, List<File> sameFiles) {
        this(size, "", sameFiles);
    }

    DuplicateDTO(Long size, String fileHash, List<File> sameFiles) {
        this.size = size;
        this.fileHash = fileHash;
        this.sameFiles = sameFiles;
    }
}
