package duplicatefileremover;

import static org.junit.Assert.*;
import java.time.*;
import org.junit.*;

/**
 *
 * @author premik
 */
public class DuplicateFileRemoverTest {
    private DuplicateFileRemover dfr;
    
    @Before
    public void setUp() {
        dfr = new DuplicateFileRemover("aa");
    }
    
    @After
    public void tearDown(){
        dfr = null;
    }

    @Test
    public void testGetHelper() {
        assertNotNull(dfr.getHelper());
    }
    
    //@Test
    public void testGetDuration() throws Exception{
        LocalTime start = LocalTime.now();
        Duration hours = Duration.ofHours(1);
        LocalTime stop = start.plus(hours);
        
        //assertEquals(1, dfr.getDurationInfo(start, stop));
        
        Duration minutes = Duration.ofMinutes(3);
        stop = start.plus(minutes);
        assertEquals("Czas trwania: 3[m].", dfr.getDurationInfo(start, stop));
        minutes = Duration.ofMinutes(60);
        stop = start.plus(minutes);
        assertEquals("Czas trwania: 1[h].", dfr.getDurationInfo(start, stop));
        
        Duration seconds = Duration.ofSeconds(4);
        stop = start.plus(seconds);
        assertEquals("Czas trwania: 4[s].", dfr.getDurationInfo(start, stop));
        seconds = Duration.ofSeconds(60);
        stop = start.plus(seconds);
        assertEquals("Czas trwania: 1[m].", dfr.getDurationInfo(start, stop));
    }
}
