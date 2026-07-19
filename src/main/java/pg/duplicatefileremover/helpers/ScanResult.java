package pg.duplicatefileremover.helpers;

import java.time.Duration;
import java.util.*;

public record ScanResult(
        long scannedFiles,
        List<DuplicateGroup> duplicateGroups,
        Duration duration,
        Map<ScanProgress.Stage, Duration> stageDurations
) {
    public ScanResult {
        duplicateGroups = List.copyOf(duplicateGroups);
        duration = Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(stageDurations, "stageDurations");
        EnumMap<ScanProgress.Stage, Duration> durations = new EnumMap<>(ScanProgress.Stage.class);
        stageDurations.forEach((stage, stageDuration) -> durations.put(
                Objects.requireNonNull(stage, "stage"),
                Objects.requireNonNull(stageDuration, "stageDuration")
        ));
        stageDurations = Collections.unmodifiableMap(durations);
    }

    public ScanResult(long scannedFiles, List<DuplicateGroup> duplicateGroups, Duration duration) {
        this(scannedFiles, duplicateGroups, duration, Map.of());
    }

    public long duplicateCount() {
        return duplicateGroups.stream().mapToLong(group -> group.duplicates().size()).sum();
    }

    public long duplicateBytes() {
        return duplicateGroups.stream().mapToLong(DuplicateGroup::duplicateBytes).sum();
    }
}
