package pg.duplicatefileremover.helpers;

import pg.duplicatefileremover.FileExtension;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

public class FileHelper {
    private static final int HASH_BUFFER_SIZE = 64 * 1024;
    private static final Set<String> ALLOWED_EXTENSIONS = EnumSet.allOf(FileExtension.class)
            .stream()
            .map(extension -> extension.extension)
            .collect(Collectors.toUnmodifiableSet());

    private final List<Path> roots;

    public FileHelper(String root) {
        this(List.of(Path.of(root)));
    }

    public FileHelper(List<Path> roots) {
        this.roots = roots.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .toList();
    }

    public ScanResult scan() throws IOException, NoSuchAlgorithmException {
        long startNanos = System.nanoTime();
        List<Path> mediaFiles = collectMediaFiles();
        Map<Long, List<Path>> filesBySize = new LinkedHashMap<>();
        long scannedFiles = 0;
        for (Path mediaFile : mediaFiles) {
            try {
                filesBySize.computeIfAbsent(Files.size(mediaFile), ignored -> new ArrayList<>()).add(mediaFile);
                scannedFiles++;
            } catch (IOException exception) {
                System.err.printf("Skipping unreadable media file [%s]: %s%n", mediaFile, exception.getMessage());
            }
        }
        List<DuplicateGroup> duplicateGroups = new ArrayList<>();

        for (Map.Entry<Long, List<Path>> sizeGroup : filesBySize.entrySet()) {
            if (sizeGroup.getValue().size() < 2) {
                continue;
            }
            Map<String, List<Path>> filesByHash = new LinkedHashMap<>();
            for (Path path : sizeGroup.getValue()) {
                filesByHash.computeIfAbsent(getSHAHashForFile(path), ignored -> new ArrayList<>()).add(path);
            }
            for (Map.Entry<String, List<Path>> hashGroup : filesByHash.entrySet()) {
                List<Path> matchingFiles = hashGroup.getValue();
                if (matchingFiles.size() > 1) {
                    matchingFiles.sort(Comparator
                            .comparing(this::creationTime)
                            .thenComparing(this::lastModifiedTime)
                            .thenComparing(Path::toString));
                    duplicateGroups.add(new DuplicateGroup(
                            hashGroup.getKey(),
                            sizeGroup.getKey(),
                            matchingFiles.getFirst(),
                            matchingFiles.subList(1, matchingFiles.size())
                    ));
                }
            }
        }

        duplicateGroups.sort(Comparator.comparing(group -> group.original().toString()));
        return new ScanResult(scannedFiles, duplicateGroups, Duration.ofNanos(System.nanoTime() - startNanos));
    }

    protected List<File> getFileOnlyList() {
        if (roots.size() != 1 || !Files.isDirectory(roots.getFirst())) {
            return List.of();
        }
        try (var files = Files.list(roots.getFirst())) {
            return files.filter(Files::isRegularFile)
                    .filter(FileHelper::isSupportedMedia)
                    .sorted()
                    .map(Path::toFile)
                    .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    protected String getSHAHashForFile(File file) throws NoSuchAlgorithmException, IOException {
        return getSHAHashForFile(file.toPath());
    }

    public static String getSHAHashForFile(Path file) throws NoSuchAlgorithmException, IOException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[HASH_BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(file)) {
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    protected byte[] getByteArrayFromFile(File file) throws IOException {
        return Files.readAllBytes(file.toPath());
    }

    private List<Path> collectMediaFiles() throws IOException {
        Set<Path> mediaFiles = new LinkedHashSet<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                throw new IOException("Not a readable directory: " + root);
            }
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile() && isSupportedMedia(file)) {
                        mediaFiles.add(file.toAbsolutePath().normalize());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    System.err.printf("Skipping unreadable path [%s]: %s%n", file, exception.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return mediaFiles.stream().sorted().toList();
    }

    private static boolean isSupportedMedia(Path path) {
        String filename = path.getFileName().toString();
        int extensionSeparator = filename.lastIndexOf('.');
        if (extensionSeparator < 0 || extensionSeparator == filename.length() - 1) {
            return false;
        }
        return ALLOWED_EXTENSIONS.contains(filename.substring(extensionSeparator + 1).toLowerCase(Locale.ROOT));
    }

    private FileTime creationTime(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class).creationTime();
        } catch (IOException exception) {
            return FileTime.fromMillis(Long.MAX_VALUE);
        }
    }

    private FileTime lastModifiedTime(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException exception) {
            return FileTime.fromMillis(Long.MAX_VALUE);
        }
    }
}
