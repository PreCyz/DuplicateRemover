package pg.duplicatefileremover.helpers;

import java.nio.file.Path;
import java.util.List;

public record DuplicateGroup(
        String hash,
        long fileSize,
        Path original,
        List<Path> duplicates
) {
    public DuplicateGroup {
        duplicates = List.copyOf(duplicates);
    }

    public long duplicateBytes() {
        return Math.multiplyExact(fileSize, duplicates.size());
    }
}
