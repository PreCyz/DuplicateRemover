package pg.duplicatefileremover.helpers;

import pg.duplicatefileremover.DiskType;
import pg.duplicatefileremover.FileExtension;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
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
    private static final int DEFAULT_HASH_BUFFER_SIZE = 64 * 1024;
    private static final int FINGERPRINT_SAMPLE_SIZE = 64 * 1024;
    private static final long FINGERPRINT_MIN_FILE_SIZE = FINGERPRINT_SAMPLE_SIZE * 3L;
    private static final Set<String> ALLOWED_EXTENSIONS = EnumSet.allOf(FileExtension.class)
            .stream()
            .map(extension -> extension.extension)
            .collect(Collectors.toUnmodifiableSet());

    private final List<Path> roots;
    private final ScanProgress progress;
    private final ScanProfile scanProfile;
    private final HashCache hashCache;

    public FileHelper(String root) {
        this(List.of(Path.of(root)));
    }

    public FileHelper(List<Path> roots) {
        this(roots, new ScanProgress());
    }

    public FileHelper(List<Path> roots, ScanProgress progress) {
        this(roots, progress, DiskType.HDD, null);
    }

    public FileHelper(List<Path> roots, ScanProgress progress, DiskType diskType, Path hashCachePath) {
        this.roots = roots.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .toList();
        this.progress = Objects.requireNonNull(progress, "progress");
        this.scanProfile = scanProfile(Objects.requireNonNull(diskType, "diskType"));
        this.hashCache = new HashCache(hashCachePath);
    }

    public ScanResult scan() throws IOException, NoSuchAlgorithmException {
        long startNanos = System.nanoTime();
        progress.begin(ScanProgress.Stage.DISCOVERING, 0);
        try {
            hashCache.load(progress);
            Map<Long, List<FileMetadata>> filesBySize;
            ConcurrentMap<HashKey, ConcurrentLinkedQueue<FileMetadata>> filesByHash;
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<FileMetadata> mediaFiles = collectMediaFiles(executor);
                filesBySize = groupFilesBySize(mediaFiles);
                ConcurrentLinkedQueue<FileMetadata> hashCandidates = prefilterHashCandidates(filesBySize, executor);
                filesByHash = groupFilesByHash(hashCandidates, executor);
            }
            hashCache.save(progress);

            progress.begin(ScanProgress.Stage.FINALIZING, filesByHash.size());
            List<DuplicateGroup> duplicateGroups = new ArrayList<>();
            for (Map.Entry<HashKey, ConcurrentLinkedQueue<FileMetadata>> hashGroup : filesByHash.entrySet()) {
                List<FileMetadata> matchingFiles = new ArrayList<>(hashGroup.getValue());
                if (matchingFiles.size() > 1) {
                    matchingFiles.sort(Comparator
                            .comparing(FileMetadata::creationTime)
                            .thenComparing(FileMetadata::lastModifiedTime)
                            .thenComparing(metadata -> metadata.path().toString()));
                    duplicateGroups.add(new DuplicateGroup(
                            hashGroup.getKey().hash(),
                            hashGroup.getKey().size(),
                            matchingFiles.getFirst().path(),
                            matchingFiles.subList(1, matchingFiles.size()).stream()
                                    .map(FileMetadata::path)
                                    .toList()
                    ));
                }
                progress.itemCompleted();
            }

            duplicateGroups.sort(Comparator.comparing(group -> group.original().toString()));
            long scannedFiles = filesBySize.values().stream().mapToLong(List::size).sum();
            progress.complete();
            return new ScanResult(scannedFiles, duplicateGroups, Duration.ofNanos(System.nanoTime() - startNanos));
        } catch (IOException | NoSuchAlgorithmException | RuntimeException exception) {
            progress.failed();
            throw exception;
        }
    }

    public static int concurrentWorkerCount() {
        ScanProfile profile = scanProfile(DiskType.HDD);
        return Math.max(profile.traversalWorkers(), Math.max(profile.samplingWorkers(), profile.hashingWorkers()));
    }

    public static ScanProfile scanProfile(DiskType diskType) {
        int processors = Runtime.getRuntime().availableProcessors();
        return switch (diskType) {
            case HDD -> new ScanProfile(2, 1, 1, 256 * 1024);
            case NVME -> new ScanProfile(
                    (int) Math.clamp((long) processors, 4, 16),
                    (int) Math.clamp(processors * 2L, 4, 32),
                    (int) Math.clamp((long) processors, 2, 16),
                    1024 * 1024
            );
        };
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
        byte[] buffer = new byte[DEFAULT_HASH_BUFFER_SIZE];
        return getSHAHashForFile(file, digest, buffer);
    }

    private static String getSHAHashForFile(Path file, MessageDigest digest, byte[] buffer) throws IOException {
        digest.reset();
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

    private List<FileMetadata> collectMediaFiles(ExecutorService executor) throws IOException {
        LinkedBlockingQueue<Path> directories = new LinkedBlockingQueue<>();
        Set<Path> visitedDirectories = ConcurrentHashMap.newKeySet();
        ConcurrentMap<Path, FileMetadata> mediaFiles = new ConcurrentHashMap<>();
        Phaser pendingDirectories = new Phaser(1);
        AtomicBoolean traversalComplete = new AtomicBoolean();

        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                throw new IOException("Not a readable directory: " + root);
            }
            enqueueDirectory(root, directories, visitedDirectories, pendingDirectories);
        }

        List<Future<?>> workers = startWorkers(executor, scanProfile.traversalWorkers(), () -> {
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
        return mediaFiles.values().stream()
                .sorted(Comparator.comparing(metadata -> metadata.path().toString()))
                .toList();
    }

    private Map<Long, List<FileMetadata>> groupFilesBySize(List<FileMetadata> mediaFiles) {
        progress.begin(ScanProgress.Stage.GROUPING_BY_SIZE, mediaFiles.size());
        Map<Long, List<FileMetadata>> filesBySize = new HashMap<>();
        for (FileMetadata mediaFile : mediaFiles) {
            filesBySize.computeIfAbsent(mediaFile.size(), ignored -> new ArrayList<>()).add(mediaFile);
            progress.itemCompleted();
        }
        return filesBySize;
    }

    private ConcurrentLinkedQueue<FileMetadata> prefilterHashCandidates(
            Map<Long, List<FileMetadata>> filesBySize,
            ExecutorService executor
    ) throws IOException, NoSuchAlgorithmException {
        ConcurrentLinkedQueue<FileMetadata> candidates = new ConcurrentLinkedQueue<>();
        filesBySize.forEach((size, paths) -> {
            if (paths.size() > 1) {
                candidates.addAll(paths);
            }
        });
        progress.begin(ScanProgress.Stage.SAMPLING, candidates.size());

        ConcurrentLinkedQueue<FileMetadata> fullHashCandidates = new ConcurrentLinkedQueue<>();
        ConcurrentMap<QuickHashKey, ConcurrentLinkedQueue<FileMetadata>> filesByFingerprint = new ConcurrentHashMap<>();
        ConcurrentLinkedQueue<Exception> failures = new ConcurrentLinkedQueue<>();
        List<Future<?>> workers = startWorkers(executor, scanProfile.samplingWorkers(), () -> {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                ByteBuffer sample = ByteBuffer.allocate(FINGERPRINT_SAMPLE_SIZE);
                FileMetadata candidate;
                while (failures.isEmpty() && (candidate = candidates.poll()) != null) {
                    try {
                        if (candidate.size() <= FINGERPRINT_MIN_FILE_SIZE) {
                            fullHashCandidates.add(candidate);
                        } else {
                            QuickHashKey key = new QuickHashKey(
                                    candidate.size(),
                                    getSampledFingerprint(candidate, digest, sample)
                            );
                            filesByFingerprint.computeIfAbsent(key, ignored -> new ConcurrentLinkedQueue<>())
                                    .add(candidate);
                        }
                    } catch (IOException exception) {
                        failures.add(exception);
                    } finally {
                        progress.itemCompleted();
                    }
                }
            } catch (NoSuchAlgorithmException exception) {
                failures.add(exception);
            }
        });
        awaitWorkers(workers);
        throwHashingFailure(failures.peek());

        filesByFingerprint.values().stream()
                .filter(paths -> paths.size() > 1)
                .forEach(fullHashCandidates::addAll);
        return fullHashCandidates;
    }

    private ConcurrentMap<HashKey, ConcurrentLinkedQueue<FileMetadata>> groupFilesByHash(
            ConcurrentLinkedQueue<FileMetadata> candidates,
            ExecutorService executor
    ) throws IOException, NoSuchAlgorithmException {
        progress.begin(ScanProgress.Stage.HASHING, candidates.size());

        ConcurrentMap<HashKey, ConcurrentLinkedQueue<FileMetadata>> filesByHash = new ConcurrentHashMap<>();
        ConcurrentLinkedQueue<Exception> failures = new ConcurrentLinkedQueue<>();
        List<Future<?>> workers = startWorkers(executor, scanProfile.hashingWorkers(), () -> {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[scanProfile.hashBufferSize()];
                FileMetadata candidate;
                while (failures.isEmpty() && (candidate = candidates.poll()) != null) {
                    try {
                        ensureMetadataUnchanged(candidate);
                        String hash = hashCache.find(candidate);
                        if (hash == null) {
                            hash = getSHAHashForFile(candidate.path(), digest, buffer);
                            hashCache.put(candidate, hash);
                        }
                        HashKey key = new HashKey(candidate.size(), hash);
                        filesByHash.computeIfAbsent(key, ignored -> new ConcurrentLinkedQueue<>()).add(candidate);
                    } catch (IOException exception) {
                        failures.add(exception);
                    } finally {
                        progress.itemCompleted();
                    }
                }
            } catch (NoSuchAlgorithmException exception) {
                failures.add(exception);
            }
        });
        awaitWorkers(workers);
        throwHashingFailure(failures.peek());
        return filesByHash;
    }

    private static String getSampledFingerprint(
            FileMetadata file,
            MessageDigest digest,
            ByteBuffer sample
    ) throws IOException {
        digest.reset();
        try (FileChannel channel = FileChannel.open(file.path(), StandardOpenOption.READ)) {
            if (channel.size() != file.size()) {
                throw new IOException("File size changed while scanning: " + file.path());
            }
            long middle = (file.size() - FINGERPRINT_SAMPLE_SIZE) / 2;
            updateDigestFromPosition(channel, digest, sample, 0);
            updateDigestFromPosition(channel, digest, sample, middle);
            updateDigestFromPosition(channel, digest, sample, file.size() - FINGERPRINT_SAMPLE_SIZE);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void ensureMetadataUnchanged(FileMetadata metadata) throws IOException {
        BasicFileAttributes current = Files.readAttributes(
                metadata.path(),
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (!current.isRegularFile()
                || current.size() != metadata.size()
                || !current.creationTime().equals(metadata.creationTime())
                || !current.lastModifiedTime().equals(metadata.lastModifiedTime())) {
            throw new IOException("File changed while scanning: " + metadata.path());
        }
    }

    private static void updateDigestFromPosition(
            FileChannel channel,
            MessageDigest digest,
            ByteBuffer sample,
            long filePosition
    ) throws IOException {
        sample.clear();
        while (sample.hasRemaining()) {
            int bytesRead = channel.read(sample, filePosition + sample.position());
            if (bytesRead < 0) {
                throw new EOFException("File changed while calculating its sampled fingerprint");
            }
        }
        sample.flip();
        digest.update(sample);
    }

    private static void throwHashingFailure(Exception failure) throws IOException, NoSuchAlgorithmException {
        if (failure instanceof IOException ioException) {
            throw ioException;
        }
        if (failure instanceof NoSuchAlgorithmException algorithmException) {
            throw algorithmException;
        }
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
            ConcurrentMap<Path, FileMetadata> mediaFiles,
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
                        Path normalized = entry.toAbsolutePath().normalize();
                        FileMetadata metadata = new FileMetadata(
                                normalized,
                                attributes.size(),
                                attributes.creationTime(),
                                attributes.lastModifiedTime()
                        );
                        if (mediaFiles.putIfAbsent(normalized, metadata) == null) {
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

    private static List<Future<?>> startWorkers(ExecutorService executor, int workerCount, Runnable worker) {
        List<Future<?>> workers = new ArrayList<>(workerCount);
        for (int index = 0; index < workerCount; index++) {
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

    private static final class HashCache {
        private final Path path;
        private final ConcurrentMap<String, CachedHash> entries = new ConcurrentHashMap<>();

        private HashCache(Path path) {
            this.path = path == null ? null : path.toAbsolutePath().normalize();
        }

        private void load(ScanProgress progress) {
            if (path == null || !Files.isRegularFile(path)) {
                return;
            }
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(path)) {
                properties.load(input);
                for (String filePath : properties.stringPropertyNames()) {
                    String[] fields = properties.getProperty(filePath).split(",", 4);
                    if (fields.length == 4) {
                        entries.put(filePath, new CachedHash(
                                Long.parseLong(fields[0]),
                                Long.parseLong(fields[1]),
                                Long.parseLong(fields[2]),
                                fields[3]
                        ));
                    }
                }
            } catch (IOException | RuntimeException exception) {
                entries.clear();
                progress.warning("Ignoring unreadable hash cache [%s]: %s".formatted(path, exception.getMessage()));
            }
        }

        private String find(FileMetadata metadata) {
            CachedHash cached = entries.get(metadata.path().toString());
            if (cached == null
                    || cached.size() != metadata.size()
                    || cached.creationMillis() != metadata.creationTime().toMillis()
                    || cached.modifiedMillis() != metadata.lastModifiedTime().toMillis()) {
                return null;
            }
            return cached.hash();
        }

        private void put(FileMetadata metadata, String hash) {
            entries.put(metadata.path().toString(), new CachedHash(
                    metadata.size(),
                    metadata.creationTime().toMillis(),
                    metadata.lastModifiedTime().toMillis(),
                    hash
            ));
        }

        private void save(ScanProgress progress) {
            if (path == null) {
                return;
            }
            Path temporary = null;
            try {
                Path parent = path.getParent();
                Files.createDirectories(parent);
                temporary = Files.createTempFile(parent, "hash-cache-", ".tmp");
                Properties properties = new Properties();
                entries.forEach((filePath, cached) -> properties.setProperty(
                        filePath,
                        "%d,%d,%d,%s".formatted(
                                cached.size(),
                                cached.creationMillis(),
                                cached.modifiedMillis(),
                                cached.hash()
                        )
                ));
                try (OutputStream output = Files.newOutputStream(temporary)) {
                    properties.store(output, "Duplicate File Remover SHA-256 cache");
                }
                try {
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                }
                temporary = null;
            } catch (IOException exception) {
                progress.warning("Could not update hash cache [%s]: %s".formatted(path, exception.getMessage()));
            } finally {
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException ignored) {
                        // A failed cache cleanup must not fail the scan.
                    }
                }
            }
        }
    }

    public record ScanProfile(
            int traversalWorkers,
            int samplingWorkers,
            int hashingWorkers,
            int hashBufferSize
    ) {
    }

    private record FileMetadata(
            Path path,
            long size,
            FileTime creationTime,
            FileTime lastModifiedTime
    ) {
    }

    private record HashKey(long size, String hash) {
    }

    private record QuickHashKey(long size, String hash) {
    }

    private record CachedHash(long size, long creationMillis, long modifiedMillis, String hash) {
    }
}
