package pg.duplicatefileremover;

import java.util.Locale;

public enum DiskType {
    HDD("HDD"),
    NVME("NVMe");

    private final String displayName;

    DiskType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static DiskType fromArgument(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Disk type must be HDD or NVMe.");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported disk type [%s]; expected HDD or NVMe."
                    .formatted(value));
        }
    }
}
