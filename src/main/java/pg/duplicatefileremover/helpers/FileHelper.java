package pg.duplicatefileremover.helpers;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * @author Gawa
 */
public class FileHelper {

    private final Path dirPath;
    private final Path destDir;
    private List<File> possibleDuplicates;
    private Map<String, File> noDuplicatesMap;
    private List<File> duplicatesList;

    public FileHelper(String dirPath) {
        this.dirPath = Paths.get(dirPath);
        this.destDir = Paths.get(dirPath, "duplicates");
    }

    protected List<File> getPossibleDuplicates() {
        return possibleDuplicates;
    }

    protected Map<String, File> getNoDuplicatesMap() {
        return noDuplicatesMap;
    }

    protected List<File> getDuplicatesList() {
        return duplicatesList;
    }

    public void processDuplicates() throws NoSuchAlgorithmException, IOException {
        createPossibleDuplicateFileList();
        possibleDuplicates.forEach(file -> {
            System.out.printf("Duplicate (?): %s%n", file.getName());
        });
        createDuplicatesList();
        duplicatesList.forEach(file -> {
            System.out.printf("Duplicate: %s%n", file.getName());
        });
        if (!duplicatesList.isEmpty()) {
            createDuplicateDirIfNotExists();
            moveDuplicates();
        }
    }

    protected void createPossibleDuplicateFileList() {
        List<File> fileList = getFileOnlyList();
        possibleDuplicates = new ArrayList<>();
        noDuplicatesMap = new HashMap<>();
        fileList.forEach((file) -> {
            String fileSize = String.valueOf(file.length());
            if (noDuplicatesMap.containsKey(fileSize)) {
                possibleDuplicates.add(file);
            } else {
                noDuplicatesMap.put(fileSize, file);
            }
        });
    }

    protected List<File> getFileOnlyList() {
        if (dirPath == null || dirPath.toFile().isFile()) {
            return new ArrayList<>();
        }
        return Arrays.stream(Optional.ofNullable(dirPath.toFile().listFiles()).orElseGet(() -> new File[0]))
                .filter(File::isFile)
                .toList();
    }

    protected void createDuplicatesList() throws NoSuchAlgorithmException, IOException {
        duplicatesList = new ArrayList<>();
        for (File possibleDuplicate : possibleDuplicates) {
            File notDuplicate = (File) noDuplicatesMap.get(
                    String.valueOf(possibleDuplicate.length())
            );
            if (notDuplicate != null) {
                String posDupHash = getSHAHashForFile(possibleDuplicate);
                String notDupHash = getSHAHashForFile(notDuplicate);
                if (posDupHash.equals(notDupHash)) {
                    duplicatesList.add(possibleDuplicate);
                }
            }
        }
    }

    protected String getSHAHashForFile(File file) throws NoSuchAlgorithmException, IOException {
        MessageDigest md = MessageDigest.getInstance("SHA-512");
        byte[] dataBytes = getByteArrayFromFile(file);
        byte[] mdBytes = md.digest(dataBytes);

        StringBuilder hexString = new StringBuilder();
        for (byte mdByte : mdBytes) {
            hexString.append(Integer.toHexString(0xFF & mdByte));
        }
        return hexString.toString();
    }

    protected byte[] getByteArrayFromFile(File file) throws FileNotFoundException
            , IOException {
        byte[] byteArray;
        try (FileInputStream fis = new FileInputStream(file)) {
            byteArray = new byte[(int) file.length()];
            fis.read(byteArray);
        }
        return byteArray;
    }

    public void createDuplicateDirIfNotExists() throws IOException {
        if (!Files.exists(destDir)) {
            Files.createDirectory(destDir);
        }
    }

    protected void moveDuplicates() {
        List<File> pomList = new LinkedList<>(duplicatesList);
        for (File file : pomList) {
            try {
                Path source = Paths.get(file.getAbsolutePath());
                Path destination = Paths.get(destDir.toString(), file.getName());
                Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
                System.out.printf("Duplicate %s moved to %s%n", file.getName(), destination);
                duplicatesList.remove(file);
            } catch (IOException ex) {
                System.err.println("Can't move the file." + ex.getMessage());
            }
        }
    }
}