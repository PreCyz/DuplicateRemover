package pg.duplicatefileremover.helpers;

import java.time.Duration;
import java.util.List;

public record ScanResult(
        long scannedFiles,
        List<DuplicateGroup> duplicateGroups,
        Duration duration
) {
    public ScanResult {
        duplicateGroups = List.copyOf(duplicateGroups);
    }

    public long duplicateCount() {
        return duplicateGroups.stream().mapToLong(group -> group.duplicates().size()).sum();
    }

    public long duplicateBytes() {
        return duplicateGroups.stream().mapToLong(DuplicateGroup::duplicateBytes).sum();
    }
}
