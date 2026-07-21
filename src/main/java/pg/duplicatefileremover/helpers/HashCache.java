package pg.duplicatefileremover.helpers;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

final class HashCache {
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
    private boolean journalNeedsCompaction;
    private int journalRecordCount;

    HashCache(Path path) {
        this.path = path == null ? null : path.toAbsolutePath().normalize();
        this.journalPath = this.path == null
                ? null
                : this.path.resolveSibling(this.path.getFileName() + JOURNAL_SUFFIX);
    }

    void load(ScanProgress progress) {
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
            dirty.set(true);
            journalNeedsCompaction = true;
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
            String header = reader.readLine();
            if (!JOURNAL_HEADER.equals(header)) {
                throw new IOException("Unsupported hash cache journal version");
            }
            String line = reader.readLine();
            while (line != null) {
                String nextLine = reader.readLine();
                if (line.isBlank() || line.startsWith("#")) {
                    line = nextLine;
                    continue;
                }
                try {
                    if (line.startsWith("P\t")) {
                        CacheEntry entry = parseSnapshotEntry(line.substring(2));
                        entries.put(entry.path(), entry.hash());
                    } else if (line.startsWith("D\t")) {
                        entries.remove(decodePath(line.substring(2)));
                    } else {
                        throw new IOException("Unsupported hash cache journal record");
                    }
                    journalRecordCount++;
                } catch (IOException | RuntimeException exception) {
                    if (nextLine == null && !journalEndsWithLineTerminator()) {
                        journalNeedsCompaction = true;
                        dirty.set(true);
                        break;
                    }
                    if (exception instanceof IOException ioException) {
                        throw ioException;
                    }
                    throw new IOException("Invalid hash cache journal record", exception);
                }
                line = nextLine;
            }
        }
    }

    private boolean journalEndsWithLineTerminator() throws IOException {
        long size = Files.size(journalPath);
        if (size == 0) {
            return false;
        }
        ByteBuffer lastByte = ByteBuffer.allocate(1);
        try (FileChannel channel = FileChannel.open(journalPath, StandardOpenOption.READ)) {
            channel.position(size - 1);
            if (channel.read(lastByte) != 1) {
                return false;
            }
        }
        byte value = lastByte.array()[0];
        return value == '\n' || value == '\r';
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

    String find(FileMetadata metadata) {
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

    int size() {
        return entries.size();
    }

    void reconcileWithScan(
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

    void put(FileMetadata metadata, String hash) {
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

    void save(ScanProgress progress) {
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
                || journalNeedsCompaction
                || !Files.isRegularFile(path)
                || journalRecordCount + pendingRecords >= threshold;
    }

    private void writeSnapshot(Path parent) throws IOException {
        Path temporary = Files.createTempFile(parent, "hash-cache-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            ); BufferedWriter writer = new BufferedWriter(Channels.newWriter(channel, StandardCharsets.UTF_8))) {
                writer.write(SNAPSHOT_HEADER);
                writer.newLine();
                entries.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> writeUnchecked(writer, snapshotLine(entry.getKey(), entry.getValue())));
                writer.flush();
                channel.force(true);
            } catch (UncheckedIOException exception) {
                throw exception.getCause();
            }
            moveReplacing(temporary, path);
            Files.deleteIfExists(journalPath);
            legacyFormat = false;
            journalNeedsCompaction = false;
            journalRecordCount = 0;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void appendJournal() throws IOException {
        boolean newJournal = !Files.isRegularFile(journalPath) || Files.size(journalPath) == 0;
        try (FileChannel channel = FileChannel.open(
                journalPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
        ); BufferedWriter writer = new BufferedWriter(Channels.newWriter(channel, StandardCharsets.UTF_8))) {
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
            writer.flush();
            channel.force(true);
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
