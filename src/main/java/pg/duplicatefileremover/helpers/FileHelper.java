package pg.duplicatefileremover.helpers;

import pg.duplicatefileremover.DiskType;
import pg.duplicatefileremover.FileExtension;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
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
        return scan(true);
    }

    public ScanResult scanForReport() throws IOException, NoSuchAlgorithmException {
        return scanForReport(true);
    }

    public ScanResult scanForReport(boolean completeProgress) throws IOException, NoSuchAlgorithmException {
        return ReportHelper.retainExistingFiles(scan(false), progress, completeProgress);
    }

    private ScanResult scan(boolean completeProgress) throws IOException, NoSuchAlgorithmException {
        long startNanos = System.nanoTime();
        progress.begin(ScanProgress.Stage.DISCOVERING, 0);
        try {
            hashCache.load(progress);
            Map<Long, List<FileMetadata>> filesBySize;
            ConcurrentMap<HashKey, ConcurrentLinkedQueue<FileMetadata>> filesByHash = new ConcurrentHashMap<>();
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<FileMetadata> mediaFiles = collectMediaFiles(executor);
                filesBySize = groupFilesBySize(mediaFiles);
                ConcurrentLinkedQueue<FileMetadata> hashCandidates = prefilterHashCandidates(
                        filesBySize,
                        executor,
                        filesByHash
                );
                groupFilesByHash(hashCandidates, executor, filesByHash);
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
            if (completeProgress) {
                progress.complete();
            }
            return new ScanResult(
                    scannedFiles,
                    duplicateGroups,
                    Duration.ofNanos(System.nanoTime() - startNanos),
                    progress.stageDurations()
            );
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
            case HDD -> new ScanProfile(2, 1, 1, 256 * 1024, true, true, 1, 256 * 1024);
            case NVME -> new ScanProfile(
                    (int) Math.clamp((long) processors, 4, 16),
                    (int) Math.clamp(processors * 2L, 4, 32),
                    (int) Math.clamp((long) processors, 2, 16),
                    1024 * 1024,
                    false,
                    false,
                    (int) Math.clamp((long) processors, 2, 8),
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
        return getSHAHashForFile(file, DEFAULT_HASH_BUFFER_SIZE);
    }

    static String getSHAHashForFile(Path file, int bufferSize) throws NoSuchAlgorithmException, IOException {
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("Hash buffer size must be positive");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[bufferSize];
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

        List<Path> readableRoots = roots.stream()
                .filter(Files::isDirectory)
                .toList();
        if (readableRoots.isEmpty()) {
            if (roots.isEmpty()) {
                throw new IOException("No readable scan directories were provided.");
            }
            throw new IOException("Not a readable directory: " + roots.getFirst());
        }
        if (roots.stream().anyMatch(root -> !Files.isDirectory(root))) {
            progress.information("Skipping non-directory scan root.");
        }

        for (Path root : readableRoots) {
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
            ExecutorService executor,
            ConcurrentMap<HashKey, ConcurrentLinkedQueue<FileMetadata>> filesByHash
    ) throws IOException, NoSuchAlgorithmException {
        List<List<FileMetadata>> duplicateSizeGroups = filesBySize.values().stream()
                .filter(paths -> paths.size() > 1)
                .toList();
        long cacheValidationWork = Math.addExact(
                duplicateSizeGroups.stream().mapToLong(List::size).sum(),
                hashCache.size()
        );
        progress.begin(ScanProgress.Stage.VALIDATING_HASH_CACHE, cacheValidationWork);
        hashCache.reconcileWithScan(filesBySize.values(), roots, progress);

        List<FileMetadata> candidateList = new ArrayList<>();
        ConcurrentLinkedQueue<CachedCandidate> cachedCandidates = new ConcurrentLinkedQueue<>();
        for (List<FileMetadata> paths : duplicateSizeGroups) {
            Map<FileMetadata, String> cachedHashes = new LinkedHashMap<>();
            for (FileMetadata candidate : paths) {
                String hash = hashCache.find(candidate);
                if (hash != null) {
                    cachedHashes.put(candidate, hash);
                }
            }
            if (cachedHashes.size() == paths.size()) {
                cachedHashes.forEach((candidate, hash) ->
                        cachedCandidates.add(new CachedCandidate(candidate, hash)));
            } else {
                candidateList.addAll(paths);
                progress.itemsCompleted(paths.size());
            }
        }

        List<Future<?>> cacheWorkers = startWorkers(executor, scanProfile.samplingWorkers(), () -> {
            CachedCandidate cachedCandidate;
            while ((cachedCandidate = cachedCandidates.poll()) != null) {
                try {
                    FileMetadata candidate = cachedCandidate.file();
                    filesByHash
                            .computeIfAbsent(
                                    new HashKey(candidate.size(), cachedCandidate.hash()),
                                    ignored -> new ConcurrentLinkedQueue<>()
                            )
                            .add(candidate);
                } finally {
                    progress.itemCompleted();
                }
            }
        });
        awaitWorkers(cacheWorkers);

        candidateList = orderForIo(candidateList);
        ConcurrentLinkedQueue<FileMetadata> candidates = new ConcurrentLinkedQueue<>(candidateList);
        long samplingWork = scanProfile.progressiveSampling() ? candidates.size() * 3L : candidates.size();
        progress.begin(ScanProgress.Stage.SAMPLING, samplingWork);

        if (scanProfile.progressiveSampling()) {
            return progressivelySampleHddCandidates(candidateList);
        }

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
        return new ConcurrentLinkedQueue<>(orderForIo(fullHashCandidates));
    }

    private ConcurrentLinkedQueue<FileMetadata> progressivelySampleHddCandidates(
            List<FileMetadata> candidates
    ) throws IOException, NoSuchAlgorithmException {
        List<FileMetadata> fullHashCandidates = new ArrayList<>();
        Map<Long, List<FileMetadata>> largeFilesBySize = new LinkedHashMap<>();
        for (FileMetadata candidate : candidates) {
            if (candidate.size() <= FINGERPRINT_MIN_FILE_SIZE) {
                fullHashCandidates.add(candidate);
                progress.itemsCompleted(3);
            } else {
                largeFilesBySize.computeIfAbsent(candidate.size(), ignored -> new ArrayList<>()).add(candidate);
            }
        }

        List<ProgressiveCandidate> activeCandidates = new ArrayList<>();
        for (Map.Entry<Long, List<FileMetadata>> sizeGroup : largeFilesBySize.entrySet()) {
            for (FileMetadata file : sizeGroup.getValue()) {
                activeCandidates.add(new ProgressiveCandidate(file, Long.toString(sizeGroup.getKey())));
            }
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteBuffer sample = ByteBuffer.allocate(FINGERPRINT_SAMPLE_SIZE);
        for (int round = 0; round < 3 && !activeCandidates.isEmpty(); round++) {
            activeCandidates.sort(Comparator.comparing(candidate -> candidate.file().path().toString()));
            Map<String, List<ProgressiveCandidate>> candidatesByFingerprint = new LinkedHashMap<>();
            for (ProgressiveCandidate candidate : activeCandidates) {
                String fingerprint = getSampledFingerprint(candidate.file(), digest, sample, round);
                progress.itemCompleted();
                String groupingKey = candidate.groupingKey() + ':' + fingerprint;
                candidatesByFingerprint.computeIfAbsent(groupingKey, ignored -> new ArrayList<>())
                        .add(new ProgressiveCandidate(candidate.file(), groupingKey));
            }

            List<ProgressiveCandidate> remainingCandidates = new ArrayList<>();
            for (List<ProgressiveCandidate> fingerprintGroup : candidatesByFingerprint.values()) {
                boolean requiresMoreWork = fingerprintGroup.size() > 1;
                if (!requiresMoreWork && round < 2) {
                    progress.itemsCompleted((long) fingerprintGroup.size() * (2 - round));
                }
                if (requiresMoreWork) {
                    remainingCandidates.addAll(fingerprintGroup);
                }
            }
            activeCandidates = remainingCandidates;
        }
        activeCandidates.stream().map(ProgressiveCandidate::file).forEach(fullHashCandidates::add);
        return new ConcurrentLinkedQueue<>(orderForIo(fullHashCandidates));
    }

    private List<FileMetadata> orderForIo(Collection<FileMetadata> candidates) {
        if (!scanProfile.pathOrderedIo()) {
            return new ArrayList<>(candidates);
        }
        return candidates.stream()
                .sorted(Comparator.comparing(metadata -> metadata.path().toString()))
                .toList();
    }

    private void groupFilesByHash(
            ConcurrentLinkedQueue<FileMetadata> candidates,
            ExecutorService executor,
            ConcurrentMap<HashKey, ConcurrentLinkedQueue<FileMetadata>> filesByHash
    ) throws IOException, NoSuchAlgorithmException {
        progress.begin(ScanProgress.Stage.HASHING, candidates.size());

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

    private static String getSampledFingerprint(
            FileMetadata file,
            MessageDigest digest,
            ByteBuffer sample,
            int round
    ) throws IOException {
        long filePosition = switch (round) {
            case 0 -> 0;
            case 1 -> (file.size() - FINGERPRINT_SAMPLE_SIZE) / 2;
            case 2 -> file.size() - FINGERPRINT_SAMPLE_SIZE;
            default -> throw new IllegalArgumentException("Unsupported sampling round: " + round);
        };
        digest.reset();
        try (FileChannel channel = FileChannel.open(file.path(), StandardOpenOption.READ)) {
            if (channel.size() != file.size()) {
                throw new IOException("File size changed while scanning: " + file.path());
            }
            updateDigestFromPosition(channel, digest, sample, filePosition);
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
        private static final String SNAPSHOT_HEADER = "# duplicate-file-remover-hash-cache-v2";
        private static final String JOURNAL_HEADER = "# duplicate-file-remover-hash-cache-journal-v2";
        private static final String JOURNAL_SUFFIX = ".journal";
        private static final long MAX_UNUSED_DAYS = 180;
        private static final int MIN_COMPACTION_RECORDS = 1_024;
        private static final Base64.Encoder PATH_ENCODER = Base64.getUrlEncoder().withoutPadding();
        private static final Base64.Decoder PATH_DECODER = Base64.getUrlDecoder();

        private final Path path;
        private final Path journalPath;
        private final ConcurrentMap<String, CachedHash> entries = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, CachedHash> pendingUpserts = new ConcurrentHashMap<>();
        private final Set<String> pendingDeletes = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean dirty = new AtomicBoolean();
        private final long currentEpochDay = System.currentTimeMillis() / Duration.ofDays(1).toMillis();
        private boolean legacyFormat;
        private int journalRecordCount;

        private HashCache(Path path) {
            this.path = path == null ? null : path.toAbsolutePath().normalize();
            this.journalPath = this.path == null
                    ? null
                    : this.path.resolveSibling(this.path.getFileName() + JOURNAL_SUFFIX);
        }

        private void load(ScanProgress progress) {
            if (path == null) {
                return;
            }
            try {
                if (Files.isRegularFile(path)) {
                    loadSnapshotOrLegacy();
                }
                if (Files.isRegularFile(journalPath)) {
                    loadJournal();
                }
                removeExpiredEntries();
            } catch (IOException | RuntimeException exception) {
                entries.clear();
                pendingUpserts.clear();
                pendingDeletes.clear();
                dirty.set(false);
                progress.warning("Ignoring unreadable hash cache [%s]: %s".formatted(path, exception.getMessage()));
            }
        }

        private void loadSnapshotOrLegacy() throws IOException {
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String firstLine = reader.readLine();
                if (SNAPSHOT_HEADER.equals(firstLine)) {
                    loadSnapshotLines(reader);
                    return;
                }
            }
            loadLegacyProperties();
            legacyFormat = true;
            dirty.set(true);
        }

        private void loadSnapshotLines(BufferedReader reader) throws IOException {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    CacheEntry entry = parseSnapshotEntry(line);
                    entries.put(entry.path(), entry.hash());
                }
            }
        }

        private void loadLegacyProperties() throws IOException {
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(path)) {
                properties.load(input);
            }
            for (String filePath : properties.stringPropertyNames()) {
                CachedHash cached = parseLegacyHash(properties.getProperty(filePath));
                if (cached != null) {
                    entries.put(filePath, cached);
                }
            }
        }

        private CachedHash parseLegacyHash(String value) {
            int first = value.indexOf(',');
            int second = first < 0 ? -1 : value.indexOf(',', first + 1);
            int third = second < 0 ? -1 : value.indexOf(',', second + 1);
            if (first < 1 || second < 0 || third < 0 || third == value.length() - 1) {
                return null;
            }
            return new CachedHash(
                    Long.parseLong(value.substring(0, first)),
                    Long.parseLong(value.substring(first + 1, second)),
                    Long.parseLong(value.substring(second + 1, third)),
                    currentEpochDay,
                    value.substring(third + 1)
            );
        }

        private void loadJournal() throws IOException {
            try (BufferedReader reader = Files.newBufferedReader(journalPath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    journalRecordCount++;
                    if (line.startsWith("P\t")) {
                        CacheEntry entry = parseSnapshotEntry(line.substring(2));
                        entries.put(entry.path(), entry.hash());
                    } else if (line.startsWith("D\t")) {
                        entries.remove(decodePath(line.substring(2)));
                    } else {
                        throw new IOException("Unsupported hash cache journal record");
                    }
                }
            }
        }

        private CacheEntry parseSnapshotEntry(String line) throws IOException {
            StringTokenizer fields = new StringTokenizer(line, "\t");
            if (fields.countTokens() != 6) {
                throw new IOException("Invalid hash cache record");
            }
            String filePath = decodePath(fields.nextToken());
            CachedHash cached = new CachedHash(
                    Long.parseLong(fields.nextToken()),
                    Long.parseLong(fields.nextToken()),
                    Long.parseLong(fields.nextToken()),
                    Long.parseLong(fields.nextToken()),
                    fields.nextToken()
            );
            return new CacheEntry(filePath, cached);
        }

        private void removeExpiredEntries() {
            long oldestAllowedDay = currentEpochDay - MAX_UNUSED_DAYS;
            entries.forEach((filePath, cached) -> {
                if (cached.lastSeenEpochDay() < oldestAllowedDay) {
                    removeEntry(filePath);
                }
            });
        }

        private String find(FileMetadata metadata) {
            String filePath = metadata.path().toString();
            CachedHash cached = entries.get(filePath);
            if (cached == null
                    || cached.size() != metadata.size()
                    || cached.creationMillis() != metadata.creationTime().toMillis()
                    || cached.modifiedMillis() != metadata.lastModifiedTime().toMillis()) {
                return null;
            }
            touch(filePath, cached);
            return cached.hash();
        }

        private void touch(String filePath, CachedHash cached) {
            if (cached.lastSeenEpochDay() == currentEpochDay) {
                return;
            }
            CachedHash touched = cached.withLastSeen(currentEpochDay);
            if (entries.replace(filePath, cached, touched)) {
                markUpsert(filePath, touched);
            }
        }

        private int size() {
            return entries.size();
        }

        private void reconcileWithScan(
                Collection<List<FileMetadata>> filesBySize,
                List<Path> scanRoots,
                ScanProgress progress
        ) {
            Set<String> discoveredPaths = filesBySize.stream()
                    .flatMap(Collection::stream)
                    .map(metadata -> metadata.path().toString())
                    .collect(Collectors.toUnmodifiableSet());
            discoveredPaths.forEach(filePath -> {
                CachedHash cached = entries.get(filePath);
                if (cached != null) {
                    touch(filePath, cached);
                }
            });
            for (String filePath : entries.keySet()) {
                try {
                    Path cachedPath = Path.of(filePath).toAbsolutePath().normalize();
                    boolean belongsToCurrentScan = scanRoots.stream().anyMatch(cachedPath::startsWith);
                    if (belongsToCurrentScan
                            && !discoveredPaths.contains(cachedPath.toString())
                            && entries.containsKey(filePath)) {
                        removeEntry(filePath);
                    }
                } catch (InvalidPathException exception) {
                    removeEntry(filePath);
                } finally {
                    progress.itemCompleted();
                }
            }
        }

        private void put(FileMetadata metadata, String hash) {
            CachedHash replacement = new CachedHash(
                    metadata.size(),
                    metadata.creationTime().toMillis(),
                    metadata.lastModifiedTime().toMillis(),
                    currentEpochDay,
                    hash
            );
            CachedHash previous = entries.put(metadata.path().toString(), replacement);
            if (!replacement.equals(previous)) {
                markUpsert(metadata.path().toString(), replacement);
            }
        }

        private void removeEntry(String filePath) {
            if (entries.remove(filePath) != null) {
                pendingUpserts.remove(filePath);
                pendingDeletes.add(filePath);
                dirty.set(true);
            }
        }

        private void markUpsert(String filePath, CachedHash cached) {
            pendingDeletes.remove(filePath);
            pendingUpserts.put(filePath, cached);
            dirty.set(true);
        }

        private void save(ScanProgress progress) {
            if (path == null || !dirty.get()) {
                return;
            }
            try {
                Path parent = path.getParent();
                Files.createDirectories(parent);
                if (shouldCompact()) {
                    writeSnapshot(parent);
                } else {
                    appendJournal();
                }
                pendingUpserts.clear();
                pendingDeletes.clear();
                dirty.set(false);
            } catch (IOException exception) {
                progress.warning("Could not update hash cache [%s]: %s".formatted(path, exception.getMessage()));
            }
        }

        private boolean shouldCompact() {
            int pendingRecords = pendingUpserts.size() + pendingDeletes.size();
            int threshold = Math.max(MIN_COMPACTION_RECORDS, Math.max(1, entries.size() / 4));
            return legacyFormat
                    || !Files.isRegularFile(path)
                    || journalRecordCount + pendingRecords >= threshold;
        }

        private void writeSnapshot(Path parent) throws IOException {
            Path temporary = Files.createTempFile(parent, "hash-cache-", ".tmp");
            try {
                try (BufferedWriter writer = Files.newBufferedWriter(
                        temporary,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.TRUNCATE_EXISTING
                )) {
                    writer.write(SNAPSHOT_HEADER);
                    writer.newLine();
                    entries.entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .forEach(entry -> writeUnchecked(writer, snapshotLine(entry.getKey(), entry.getValue())));
                } catch (UncheckedIOException exception) {
                    throw exception.getCause();
                }
                moveReplacing(temporary, path);
                Files.deleteIfExists(journalPath);
                legacyFormat = false;
                journalRecordCount = 0;
            } finally {
                Files.deleteIfExists(temporary);
            }
        }

        private void appendJournal() throws IOException {
            boolean newJournal = !Files.exists(journalPath);
            try (BufferedWriter writer = Files.newBufferedWriter(
                    journalPath,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            )) {
                if (newJournal) {
                    writer.write(JOURNAL_HEADER);
                    writer.newLine();
                }
                for (String filePath : new TreeSet<>(pendingDeletes)) {
                    writer.write("D\t" + encodePath(filePath));
                    writer.newLine();
                    journalRecordCount++;
                }
                for (Map.Entry<String, CachedHash> entry : new TreeMap<>(pendingUpserts).entrySet()) {
                    writer.write("P\t" + snapshotLine(entry.getKey(), entry.getValue()));
                    writer.newLine();
                    journalRecordCount++;
                }
            }
        }

        private static void writeUnchecked(BufferedWriter writer, String line) {
            try {
                writer.write(line);
                writer.newLine();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        private static String snapshotLine(String filePath, CachedHash cached) {
            return "%s\t%d\t%d\t%d\t%d\t%s".formatted(
                    encodePath(filePath),
                    cached.size(),
                    cached.creationMillis(),
                    cached.modifiedMillis(),
                    cached.lastSeenEpochDay(),
                    cached.hash()
            );
        }

        private static String encodePath(String filePath) {
            return PATH_ENCODER.encodeToString(filePath.getBytes(StandardCharsets.UTF_8));
        }

        private static String decodePath(String encoded) {
            return new String(PATH_DECODER.decode(encoded), StandardCharsets.UTF_8);
        }

        private static void moveReplacing(Path source, Path target) throws IOException {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private record CacheEntry(String path, CachedHash hash) { }
    }

    public record ScanProfile(
            int traversalWorkers,
            int samplingWorkers,
            int hashingWorkers,
            int hashBufferSize,
            boolean progressiveSampling,
            boolean pathOrderedIo,
            int deletionWorkers,
            int deletionHashBufferSize
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

    private record ProgressiveCandidate(FileMetadata file, String groupingKey) {
    }

    private record CachedCandidate(FileMetadata file, String hash) {
    }

    private record CachedHash(
            long size,
            long creationMillis,
            long modifiedMillis,
            long lastSeenEpochDay,
            String hash
    ) {
        private CachedHash withLastSeen(long epochDay) {
            return new CachedHash(size, creationMillis, modifiedMillis, epochDay, hash);
        }
    }
}
