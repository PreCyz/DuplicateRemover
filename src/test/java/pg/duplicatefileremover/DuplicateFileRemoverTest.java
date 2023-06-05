package pg.duplicatefileremover;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
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
    public void givenStartAndEnd_whenGetDuration_thenReturnProperlyFormattedString() {
        LocalTime start = LocalTime.now();
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
