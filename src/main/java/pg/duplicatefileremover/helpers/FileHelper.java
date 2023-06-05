package pg.duplicatefileremover.helpers;

import pg.duplicatefileremover.FileExtension;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Gawa
 */
public class FileHelper {

    private final Path dirPath;
    private final Path destDir;
    private final boolean moveDuplicates;
    private Map<Long, DuplicateDTO> filesMap;

    public FileHelper(String dirPath) {
        this(dirPath, false);
    }

    public FileHelper(String dirPath, boolean moveDuplicates) {
        this.dirPath = Paths.get(dirPath);
        this.destDir = Paths.get(dirPath, "duplicates");
        this.moveDuplicates = moveDuplicates;
    }


    public void processDuplicates() throws NoSuchAlgorithmException, IOException {
        List<File> possibleDuplicates = createPossibleDuplicates();
        List<File> duplicates = createDuplicatesList(possibleDuplicates);

        String reportFile = "report-" + dirPath.toFile().getName().replaceAll(" ", "_") + ".html";
        new ReportHelper(
                filesMap,
                Paths.get(".", "reports", reportFile)
        ).createReport();

        if (moveDuplicates && !duplicates.isEmpty()) {
            createDuplicateDirIfNotExists();
            moveDuplicates(duplicates);
        }
    }

    protected List<File> createPossibleDuplicates() {
        List<File> fileList = getFileOnlyList();
        System.out.printf("Processing [%d] files. Thread [%s]%n", fileList.size(), Thread.currentThread().getName());
        List<File> possibleDuplicates = new ArrayList<>();
        filesMap = new LinkedHashMap<>();
        fileList.forEach(file -> {
            if (filesMap.containsKey(file.length())) {
                filesMap.get(file.length()).sameFiles.add(file);
                possibleDuplicates.add(file);
            } else {
                filesMap.put(file.length(), new DuplicateDTO(file.length() , new ArrayList<>(List.of(file))));
            }
        });
        /*possibleDuplicates.forEach(file -> {
            System.out.printf("Duplicate (?): %s%n", file.getName());
        });*/
        return possibleDuplicates;
    }

    protected List<File> getFileOnlyList() {
        if (dirPath == null || dirPath.toFile().isFile()) {
            return new ArrayList<>();
        }
        Set<String> allowedExtensions = EnumSet.allOf(FileExtension.class)
                .stream()
                .map(fe -> fe.extension)
                .collect(Collectors.toSet());
        return Arrays.stream(Optional.ofNullable(dirPath.toFile().listFiles()).orElseGet(() -> new File[0]))
                .filter(File::isFile)
                .filter(f -> allowedExtensions.contains(f.getName().substring(f.getName().indexOf(".") + 1)))
                .toList();
    }

    protected List<File> createDuplicatesList(List<File> possibleDuplicates) throws NoSuchAlgorithmException, IOException {
        List<File> duplicatesList = new ArrayList<>();
        for (File possibleDuplicate : possibleDuplicates) {
            DuplicateDTO duplicateDto = filesMap.get(possibleDuplicate.length());
            if (duplicateDto.sameFiles != null && duplicateDto.sameFiles.size() > 1) {
                String posDupHash = getSHAHashForFile(possibleDuplicate);
                for (File file: duplicateDto.sameFiles) {
                    String notDupHash = getSHAHashForFile(file);
                    if (posDupHash.equals(notDupHash)) {
                        duplicateDto.fileHash = notDupHash;
                        duplicatesList.add(possibleDuplicate);
                    }
                }
            }
        }
        /*duplicatesList.forEach(file -> {
            System.out.printf("Duplicate: %s%n", file.getName());
        });*/
        return duplicatesList;
    }

    protected String getSHAHashForFile(File file) throws NoSuchAlgorithmException, IOException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] dataBytes = getByteArrayFromFile(file);
        byte[] mdBytes = md.digest(dataBytes);

        StringBuilder hexString = new StringBuilder();
        for (byte mdByte : mdBytes) {
            hexString.append(Integer.toHexString(0xFF & mdByte));
        }
        return hexString.toString();
    }

    protected byte[] getByteArrayFromFile(File file) throws IOException {
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

    protected void moveDuplicates(List<File> duplicatesList) {
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