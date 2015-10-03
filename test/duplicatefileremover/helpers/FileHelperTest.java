package duplicatefileremover.helpers;

import static org.mockito.Mockito.when;
import static org.junit.Assert.*;
import org.mockito.Mockito;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.*;

/**
 *
 * @author premik
 */
public class FileHelperTest {
    
private FileHelper helper;
    private final String FILE_NOT_FOUND = "FileNotFoundException expected.";
    private final String dirPath = "d:\\testy\\";
    
    @Before
    public void setUp(){
        helper = new FileHelper(dirPath);
    }
    
    @After
    public void tearDown(){
        helper = null;
    }

    @Test
    public void testFileList() {
        assertNotNull(helper.getFileOnlyList());
        assertEquals(6, helper.getFileOnlyList().size());
    }
    
    @Test 
    public void testHashForFile() throws Exception{
        try{
            helper.getSHAHashForFile(new File(""));
            fail(FILE_NOT_FOUND);
        }catch(FileNotFoundException ex){
            assertTrue(FILE_NOT_FOUND, ex != null);
        }
        String fileHash = helper.getSHAHashForFile(new File("d:\\wjazd.ods"));
        assertNotNull(fileHash);
        assertFalse("".equals(fileHash));
        
        String secondFileHash = helper.getSHAHashForFile(new File("d:\\run.bat"));
        assertFalse(fileHash.equalsIgnoreCase(secondFileHash));
    }
    
    @Test 
    public void testGetByteArrayFromFile() throws Exception{
        byte[] actual = helper.getByteArrayFromFile(new File("d:\\wjazd.ods"));
        assertNotNull(actual);
        
        Path filePath = Paths.get("d:\\wjazd.ods");
        byte[] byteArray = Files.readAllBytes(filePath);
        assertArrayEquals(byteArray, actual);
        
        byteArray = helper.getByteArrayFromFile(new File("d:\\run.bat"));
        assertFalse(Arrays.equals(byteArray, actual));
        
        try{
            helper.getByteArrayFromFile(new File(""));
        } catch(FileNotFoundException ex){
            assertTrue(FILE_NOT_FOUND, true);
        }
    }
    
    @Test
    public void testGetPossibleDuplicateFileList() throws Exception{
        List<File> possibleDuplicate = Mockito.mock(List.class);
        when(possibleDuplicate.size()).thenReturn(3);
        Map<String, File> noDuplicatesMap = Mockito.mock(Map.class);
        when(noDuplicatesMap.size()).thenReturn(6);
        assertEquals(3, possibleDuplicate.size());
        assertEquals(6, noDuplicatesMap.size());
    }
    
    @Test
    public void testCreateDuplicateList() throws Exception{
        FileHelper helperF = Mockito.mock(FileHelper.class);
        when(helperF.getDuplicatesList()).thenReturn(new ArrayList<>())
                .thenReturn(new ArrayList<>());
        assertNotNull(helperF.getDuplicatesList());
        
        List<File> duplicates = Mockito.mock(List.class);
        when(duplicates.isEmpty()).thenReturn(false);
        duplicates.addAll(helperF.getDuplicatesList());
        assertTrue(!duplicates.isEmpty());
    }
    
    @Test
    public void testMoveDuplicates() throws Exception{
        helper.createPossibleDuplicateFileList();
        helper.createDuplicatesList();
        helper.moveDuplicates();
        assertNotNull(helper.getDuplicatesList());
        assertTrue(helper.getDuplicatesList().isEmpty());
    }
    
    @Test
    public void testProcessDuplicates() throws Exception{
        helper.processDuplicates();
        assertNotNull(helper.getDuplicatesList());
        assertTrue(helper.getDuplicatesList().isEmpty());
    }
    
    @Test
    public void testFolderCreation() throws Exception{
        String dstDir = String.format("%sduplikaty\\", dirPath);
        Path dir = Paths.get(dstDir);
        assertFalse(Files.exists(dir));
        helper.createDuplicateDirIfNotExists();
        assertTrue(Files.isDirectory(dir));
        assertTrue(Files.deleteIfExists(dir));
    }
}