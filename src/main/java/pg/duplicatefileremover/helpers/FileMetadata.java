package pg.duplicatefileremover.helpers;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

record FileMetadata(
        Path path,
        long size,
        FileTime creationTime,
        FileTime lastModifiedTime
) {
}
