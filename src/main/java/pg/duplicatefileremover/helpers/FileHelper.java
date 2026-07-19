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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class FileHelper {
    private static final int HASH_BUFFER_SIZE = 64 * 1024;
    private static final int MAX_CONCURRENT_FILE_OPERATIONS = Math.clamp(Runtime.getRuntime().availableProcessors() * 2L, 4, 32);
    private static final Set<String> ALLOWED_EXTENSIONS = EnumSet.allOf(FileExtension.class)
            .stream()
            .map(extension -> extension.extension)
            .collect(Collectors.toUnmodifiableSet());

    private final List<Path> roots;
    private final ScanProgress progress;

    public FileHelper(String root) {
        this(List.of(Path.of(root)));
    }

    public FileHelper(List<Path> roots) {
        this(roots, new ScanProgress());
    }

    public FileHelper(List<Path> roots, ScanProgress progress) {
        this.roots = roots.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .toList();
        this.progress = Objects.requireNonNull(progress, "progress");
    }

    public ScanResult scan() throws IOException, NoSuchAlgorithmException {
        long startNanos = System.nanoTime();
        progress.begin(ScanProgress.Stage.DISCOVERING, 0);
        try {
            ConcurrentMap<Long, ConcurrentLinkedQueue<Path>> filesBySize;
            ConcurrentMap<HashKey, ConcurrentLinkedQueue<Path>> filesByHash;
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Path> mediaFiles = collectMediaFiles(executor);
                filesBySize = groupFilesBySize(mediaFiles, executor);
                filesByHash = groupFilesByHash(filesBySize, executor);
            }

            progress.begin(ScanProgress.Stage.FINALIZING, filesByHash.size());
            List<DuplicateGroup> duplicateGroups = new ArrayList<>();
            for (Map.Entry<HashKey, ConcurrentLinkedQueue<Path>> hashGroup : filesByHash.entrySet()) {
                List<Path> matchingFiles = new ArrayList<>(hashGroup.getValue());
                if (matchingFiles.size() > 1) {
                    matchingFiles.sort(Comparator
                            .comparing(this::creationTime)
                            .thenComparing(this::lastModifiedTime)
                            .thenComparing(Path::toString));
                    duplicateGroups.add(new DuplicateGroup(
                            hashGroup.getKey().hash(),
                            hashGroup.getKey().size(),
                            matchingFiles.getFirst(),
                            matchingFiles.subList(1, matchingFiles.size())
                    ));
                }
                progress.itemCompleted();
            }

            duplicateGroups.sort(Comparator.comparing(group -> group.original().toString()));
            long scannedFiles = filesBySize.values().stream().mapToLong(ConcurrentLinkedQueue::size).sum();
            progress.complete();
            return new ScanResult(scannedFiles, duplicateGroups, Duration.ofNanos(System.nanoTime() - startNanos));
        } catch (IOException | NoSuchAlgorithmException | RuntimeException exception) {
            progress.failed();
            throw exception;
        }
    }

    public static int concurrentWorkerCount() {
        return MAX_CONCURRENT_FILE_OPERATIONS;
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

    private List<Path> collectMediaFiles(ExecutorService executor) throws IOException {
        LinkedBlockingQueue<Path> directories = new LinkedBlockingQueue<>();
        Set<Path> visitedDirectories = ConcurrentHashMap.newKeySet();
        ConcurrentSkipListSet<Path> mediaFiles = new ConcurrentSkipListSet<>();
        Phaser pendingDirectories = new Phaser(1);
        AtomicBoolean traversalComplete = new AtomicBoolean();

        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                throw new IOException("Not a readable directory: " + root);
            }
            enqueueDirectory(root, directories, visitedDirectories, pendingDirectories);
        }

        List<Future<?>> workers = startWorkers(executor, () -> {
            while (!traversalComplete.get() || !directories.isEmpty()) {
                try {
                    Path directory = directories.poll(50, TimeUnit.MILLISECONDS);
                    if (directory != null) {
                        scanDirectory(directory, directories, visitedDirectories, mediaFiles, pendingDirectories, progress);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });

        pendingDirectories.arriveAndAwaitAdvance();
        traversalComplete.set(true);
        awaitWorkers(workers);
        return List.copyOf(mediaFiles);
    }

    private ConcurrentMap<Long, ConcurrentLinkedQueue<Path>> groupFilesBySize(
            List<Path> mediaFiles,
            ExecutorService executor
    ) throws IOException {
        progress.begin(ScanProgress.Stage.READING_METADATA, mediaFiles.size());
        ConcurrentLinkedQueue<Path> remainingFiles = new ConcurrentLinkedQueue<>(mediaFiles);
        ConcurrentMap<Long, ConcurrentLinkedQueue<Path>> filesBySize = new ConcurrentHashMap<>();
        List<Future<?>> workers = startWorkers(executor, () -> {
            Path mediaFile;
            while ((mediaFile = remainingFiles.poll()) != null) {
                try {
                    filesBySize.computeIfAbsent(Files.size(mediaFile), ignored -> new ConcurrentLinkedQueue<>())
                            .add(mediaFile);
                } catch (IOException exception) {
                    progress.warning("Skipping unreadable media file [%s]: %s".formatted(
                            mediaFile,
                            exception.getMessage()
                    ));
                } finally {
                    progress.itemCompleted();
                }
            }
        });
        awaitWorkers(workers);
        return filesBySize;
    }

    private ConcurrentMap<HashKey, ConcurrentLinkedQueue<Path>> groupFilesByHash(
            ConcurrentMap<Long, ConcurrentLinkedQueue<Path>> filesBySize,
            ExecutorService executor
    ) throws IOException, NoSuchAlgorithmException {
        ConcurrentLinkedQueue<SizedPath> candidates = new ConcurrentLinkedQueue<>();
        filesBySize.forEach((size, paths) -> {
            if (paths.size() > 1) {
                paths.forEach(path -> candidates.add(new SizedPath(size, path)));
            }
        });
        progress.begin(ScanProgress.Stage.HASHING, candidates.size());

        ConcurrentMap<HashKey, ConcurrentLinkedQueue<Path>> filesByHash = new ConcurrentHashMap<>();
        ConcurrentLinkedQueue<Exception> failures = new ConcurrentLinkedQueue<>();
        List<Future<?>> workers = startWorkers(executor, () -> {
            SizedPath candidate;
            while (failures.isEmpty() && (candidate = candidates.poll()) != null) {
                try {
                    HashKey key = new HashKey(candidate.size(), getSHAHashForFile(candidate.path()));
                    filesByHash.computeIfAbsent(key, ignored -> new ConcurrentLinkedQueue<>()).add(candidate.path());
                } catch (IOException | NoSuchAlgorithmException exception) {
                    failures.add(exception);
                } finally {
                    progress.itemCompleted();
                }
            }
        });
        awaitWorkers(workers);

        Exception failure = failures.peek();
        if (failure instanceof IOException ioException) {
            throw ioException;
        }
        if (failure instanceof NoSuchAlgorithmException algorithmException) {
            throw algorithmException;
        }
        return filesByHash;
    }

    private static void enqueueDirectory(
            Path directory,
            LinkedBlockingQueue<Path> directories,
            Set<Path> visitedDirectories,
            Phaser pendingDirectories
    ) {
        Path normalized = directory.toAbsolutePath().normalize();
        if (visitedDirectories.add(normalized)) {
            pendingDirectories.register();
            directories.add(normalized);
        }
    }

    private static void scanDirectory(
            Path directory,
            LinkedBlockingQueue<Path> directories,
            Set<Path> visitedDirectories,
            Set<Path> mediaFiles,
            Phaser pendingDirectories,
            ScanProgress progress
    ) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                try {
                    BasicFileAttributes attributes = Files.readAttributes(
                            entry,
                            BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS
                    );
                    if (attributes.isDirectory()) {
                        enqueueDirectory(entry, directories, visitedDirectories, pendingDirectories);
                    } else if (attributes.isRegularFile() && isSupportedMedia(entry)) {
                        if (mediaFiles.add(entry.toAbsolutePath().normalize())) {
                            progress.mediaFileDiscovered();
                        }
                    }
                } catch (IOException | SecurityException exception) {
                    progress.warning("Skipping unreadable path [%s]: %s".formatted(entry, exception.getMessage()));
                }
            }
        } catch (DirectoryIteratorException exception) {
            Throwable cause = exception.getCause();
            progress.warning("Skipping unreadable path [%s]: %s".formatted(
                    directory,
                    cause == null ? exception.getMessage() : cause.getMessage()
            ));
        } catch (IOException | SecurityException exception) {
            progress.warning("Skipping unreadable path [%s]: %s".formatted(directory, exception.getMessage()));
        } finally {
            progress.directoryProcessed();
            pendingDirectories.arriveAndDeregister();
        }
    }

    private static List<Future<?>> startWorkers(ExecutorService executor, Runnable worker) {
        List<Future<?>> workers = new ArrayList<>(MAX_CONCURRENT_FILE_OPERATIONS);
        for (int index = 0; index < MAX_CONCURRENT_FILE_OPERATIONS; index++) {
            workers.add(executor.submit(worker));
        }
        return workers;
    }

    private static void awaitWorkers(List<Future<?>> workers) throws IOException {
        for (Future<?> worker : workers) {
            try {
                worker.get();
            } catch (InterruptedException exception) {
                workers.forEach(future -> future.cancel(true));
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while scanning files", exception);
            } catch (ExecutionException exception) {
                workers.forEach(future -> future.cancel(true));
                throw new IOException("Concurrent file scan failed", exception.getCause());
            }
        }
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

    private record SizedPath(long size, Path path) {
    }

    private record HashKey(long size, String hash) {
    }
}
