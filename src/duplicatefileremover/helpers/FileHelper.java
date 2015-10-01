package duplicatefileremover.helpers;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 *
 * @author Gawa
 */
public class FileHelper {
    
    private final String dirPath;
    private final String destDir;
    private List<File> possibleDuplicates;
    private Map<String, File> noDuplicatesMap;
    private List<File> duplicatesList;

    public FileHelper(String dirPath, String destDir) {
        this.dirPath = dirPath;
        this.destDir = destDir;
    }
    
    protected List<File> getFileOnlyList() {
        File dir = new File(dirPath);
        List<File> onlyFileList = Arrays.asList(dir.listFiles())
                .stream()
                .filter(file -> file.isFile())
                .collect(Collectors.toList());
        return onlyFileList;
    }
    
    protected String getSHAHashForFile(File file) throws NoSuchAlgorithmException
            , FileNotFoundException, IOException {
        MessageDigest md = MessageDigest.getInstance("SHA-512");
        byte[] dataBytes = getByteArrayFromFile(file);
        byte[] mdbytes = md.digest(dataBytes);

        StringBuilder hexString = new StringBuilder();
        for (int i = 0; i < mdbytes.length; i++) {
            hexString.append(Integer.toHexString(0xFF & mdbytes[i]));
        }
        return hexString.toString();
    }

    protected byte[] getByteArrayFromFile(File file) throws FileNotFoundException
            , IOException {
        FileInputStream fis = new FileInputStream(file);
        byte[] byteArray = new byte[(int)file.length()];
        fis.read(byteArray);
        fis.close();
        return byteArray;
    }

    protected void createPossibleDuplicateFileList() {
        List<File> fileList = getFileOnlyList();
        possibleDuplicates = new ArrayList<>();
        noDuplicatesMap = new HashMap<>();
        fileList.stream().forEach((file) -> {
            String fileSize = String.valueOf(file.length()); 
            if (noDuplicatesMap.containsKey(fileSize)) {
                possibleDuplicates.add(file);
            } else {
                noDuplicatesMap.put(fileSize, file);
            }
        });
    }
    
    protected List<File> getPossibleDuplicates(){
        return possibleDuplicates;
    }
    
    protected Map<String, File> getNoDuplicatesMap(){
        return noDuplicatesMap;
    }

    protected void createDuplicatesList() throws NoSuchAlgorithmException, IOException {
        duplicatesList = new ArrayList<>();
        for(File possibleDuplicate : possibleDuplicates){
            File notDuplicate = (File)noDuplicatesMap.get(
                    String.valueOf(possibleDuplicate.length())
            );
            if(notDuplicate != null){
                String posDupHash = getSHAHashForFile(possibleDuplicate);
                String notDupHash = getSHAHashForFile(notDuplicate);
                if(posDupHash.equals(notDupHash)){
                    duplicatesList.add(possibleDuplicate);
                }
            }
        }
        duplicatesList.forEach(file -> {
            System.out.println(String.format("Duplikat: %s", file.getName()));
        });
    }

    protected List<File> getDuplicatesList() {
        return duplicatesList;
    }

    protected void moveDuplicates() {
        List<File> pomList = new ArrayList<>();
        pomList.addAll(duplicatesList);
        pomList.forEach(file -> {
            try {
                Path srcPath = Paths.get(file.getAbsolutePath());
                Path dstPath = Paths.get(String.format("%s%s", destDir, file.getName()));
                Files.move(srcPath, dstPath, StandardCopyOption.REPLACE_EXISTING);
                System.out.println(
                        String.format("Duplikat %s przeniesiony do %s"
                                , file.getName()
                                , dstPath.toString()
                        )
                );
                duplicatesList.remove(file);
            } catch (IOException ex) {
                System.out.println("Nie można przenieść pliku."+ex.getMessage());
            }
        });
    }

    public void processDuplicates() throws NoSuchAlgorithmException, IOException {
        createPossibleDuplicateFileList();
        possibleDuplicates.forEach(file -> {
            System.out.println(String.format("Potencjalny duplikat: %s", file.getName()));
        });
        createDuplicatesList();
        duplicatesList.forEach(file -> {
            System.out.println(String.format("Duplikat: %s", file.getName()));
        });
        if(!duplicatesList.isEmpty()){
            moveDuplicates();
        }
    }
}