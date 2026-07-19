package pg.duplicatefileremover;

import org.junit.jupiter.api.*;
import pg.duplicatefileremover.helpers.FileHelper;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

/** @author premik */
public class DuplicateFileRemoverTest {
    private DuplicateFileRemover dfr;

    @BeforeEach
    public void setUp() {
        dfr = new DuplicateFileRemover("aa");
    }

    @AfterEach
    public void tearDown() {
        dfr = null;
    }

    @Test
    public void testGetHelper() {
        assertThat(dfr.getHelper()).isNotNull();
    }

    @Test
    void reportsTheConfiguredScanConcurrency() {
        FileHelper.ScanProfile profile = FileHelper.scanProfile(DiskType.HDD);

        assertThat(DuplicateFileRemover.scanConcurrencyInfo(DiskType.HDD))
                .isEqualTo("Disk type: HDD. Using up to %d traversal, %d sampling, %d hashing, and %d deletion virtual threads."
                        .formatted(
                                profile.traversalWorkers(),
                                profile.samplingWorkers(),
                                profile.hashingWorkers(),
                                profile.deletionWorkers()
                        ));
    }

    @Test
    void defaultsToHddAndTreatsRemainingArgumentsAsRoots() {
        DuplicateFileRemover.ApplicationArguments arguments = DuplicateFileRemover.parseArguments(
                new String[]{"first", "second"}
        );

        assertThat(arguments.diskType()).isEqualTo(DiskType.HDD);
        assertThat(arguments.roots()).containsExactly(Path.of("first"), Path.of("second"));
    }

    @Test
    void acceptsBothDiskArgumentForms() {
        assertThat(DuplicateFileRemover.parseArguments(new String[]{"--disk=NVMe", "photos"}).diskType())
                .isEqualTo(DiskType.NVME);
        assertThat(DuplicateFileRemover.parseArguments(new String[]{"--disk", "hdd", "photos"}).diskType())
                .isEqualTo(DiskType.HDD);
    }

    @Test
    void rejectsUnsupportedOrRepeatedDiskArguments() {
        assertThatThrownBy(() -> DuplicateFileRemover.parseArguments(new String[]{"--disk=SSD", "photos"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected HDD or NVMe");
        assertThatThrownBy(() -> DuplicateFileRemover.parseArguments(
                new String[]{"--disk=HDD", "--disk=NVMe", "photos"}
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only once");
    }

    @Test
    void reportsHeartbeatSilenceBeforeServerTermination() {
        assertThat(DuplicateFileRemover.missingHeartbeatInfo(Duration.ofSeconds(15)))
                .isEqualTo("No browser heartbeat was registered for 15 seconds; stopping the report server.");
    }

    @Test
    public void givenStartAndEnd_whenGetDuration_thenReturnProperlyFormattedString() {
        LocalTime start = LocalTime.of(10, 0);
        Duration hours = Duration.ofHours(1);
        LocalTime stop = start.plus(hours);

        Duration minutes = Duration.ofMinutes(3);
        stop = start.plus(minutes);
        assertThat(DuplicateFileRemover.getDurationInfo(start, stop)).isEqualTo("0[h]:3[m]:0[s]:0[milli]");
        minutes = Duration.ofMinutes(60);
        stop = start.plus(minutes);
        assertThat(DuplicateFileRemover.getDurationInfo(start, stop)).isEqualTo("1[h]:0[m]:0[s]:0[milli]");

        Duration seconds = Duration.ofSeconds(4);
        stop = start.plus(seconds);
        assertThat(DuplicateFileRemover.getDurationInfo(start, stop)).isEqualTo("0[h]:0[m]:4[s]:0[milli]");
        seconds = Duration.ofSeconds(60);
        stop = start.plus(seconds);
        assertThat(DuplicateFileRemover.getDurationInfo(start, stop)).isEqualTo("0[h]:1[m]:0[s]:0[milli]");

        Duration milli = Duration.ofMillis(4);
        stop = start.plus(milli);
        assertThat(DuplicateFileRemover.getDurationInfo(start, stop)).isEqualTo("0[h]:0[m]:0[s]:4[milli]");
    }

    @Test
    public void givenNullArgument_whenValidateArgs_thenThrowUnsupportedOperationException() {
        try {
            DuplicateFileRemover.validateArgs(null);
            fail("Should throw UnsupportedOperationException");
        } catch (UnsupportedOperationException ex) {
            assertThat(ex.getMessage()).isEqualTo("Path to folder not specified.");
        }
    }

    @Test
    void givenEmptyArgArray_whenValidateArgs_thenThrowUnsupportedOperationException() {
        try {
            DuplicateFileRemover.validateArgs(new String[]{});
            fail("Should throw UnsupportedOperationException");
        } catch (UnsupportedOperationException ex) {
            assertThat(ex.getMessage()).isEqualTo("Path to folder not specified.");
        }
    }

    @Test
    void givenArgsArrayWithOnlyOneEmptyString_whenValidateArgs_thenThrowIllegalArgumentException() {
        try {
            DuplicateFileRemover.validateArgs(new String[]{""});
            fail("Should throw UnsupportedOperationException");
        } catch (Exception ex) {
            assertThat(ex).isInstanceOf(IllegalArgumentException.class);
            assertThat(ex.getMessage()).isEqualTo("Illegal argument.");
        }
    }

    @Test
    void givenArgsArrayWithOneNullElement_whenValidateArgs_thenThrowIllegalArgumentException() {
        try {
            DuplicateFileRemover.validateArgs(new String[]{null});
            fail("Should throw UnsupportedOperationException");
        } catch (Exception ex) {
            assertThat(ex).isInstanceOf(IllegalArgumentException.class);
            assertThat(ex.getMessage()).isEqualTo("Illegal argument.");
        }
    }

    @Test
    void givenArgsArrayWithSpaceOnlyElement_whenValidateArgs_thenThrowIllegalArgumentException() {
        try {
            DuplicateFileRemover.validateArgs(new String[]{" "});
            fail("Should throw UnsupportedOperationException");
        } catch (Exception ex) {
            assertThat(ex).isInstanceOf(IllegalArgumentException.class);
            assertThat(ex.getMessage()).isEqualTo("Illegal argument.");
        }
    }
}
