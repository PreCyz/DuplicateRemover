package pg.duplicatefileremover.helpers;

import pg.duplicatefileremover.DiskType;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.DosFileAttributeView;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

final class DuplicateDeletionService implements AutoCloseable {
    private static final long[] HDD_RETRY_DELAYS_MILLIS = {250, 500, 1_000, 2_000, 4_000, 8_000};
    private static final long[] NVME_RETRY_DELAYS_MILLIS = {100, 200, 400, 800};

    private final ExecutorService executor;
    private final int workerCount;
    private final int hashBufferSize;
    private final long[] retryDelaysMillis;
    private final Consumer<Path> beforeDelete;
    private final FileDeletion fileDeletion;
    private final Set<Path> duplicatePaths = ConcurrentHashMap.newKeySet();
    private final Map<Path, ExpectedDuplicate> expectedByPath = new ConcurrentHashMap<>();
    private final Map<Path, String> failureReasons = new ConcurrentHashMap<>();
    private final Set<Path> deletionsInProgress = ConcurrentHashMap.newKeySet();

    DuplicateDeletionService(
            ScanResult scanResult,
            DiskType diskType,
            Consumer<Path> beforeDelete,
            FileDeletion fileDeletion
    ) {
        Objects.requireNonNull(scanResult, "scanResult");
        FileHelper.ScanProfile profile = FileHelper.scanProfile(Objects.requireNonNull(diskType, "diskType"));
        this.workerCount = profile.deletionWorkers();
        this.hashBufferSize = profile.deletionHashBufferSize();
        this.retryDelaysMillis = retryDelays(diskType);
        this.beforeDelete = Objects.requireNonNull(beforeDelete, "beforeDelete");
        this.fileDeletion = Objects.requireNonNull(fileDeletion, "fileDeletion");
        this.executor = Executors.newFixedThreadPool(
                workerCount,
                Thread.ofVirtual().name("duplicate-deletion-", 0).factory()
        );
        for (DuplicateGroup group : scanResult.duplicateGroups()) {
            for (Path duplicate : group.duplicates()) {
                Path normalized = duplicate.toAbsolutePath().normalize();
                duplicatePaths.add(normalized);
                expectedByPath.put(normalized, new ExpectedDuplicate(group.fileSize(), group.hash()));
            }
        }
    }

    int workerCount() {
        return workerCount;
    }

    DeleteResult delete(Path path) throws IOException {
        return await(executor.submit(() -> deleteNow(path)));
    }

    DeletionBatch deleteAll() {
        List<Path> paths = List.copyOf(duplicatePaths);
        CompletionService<DeleteResult> completions = new ExecutorCompletionService<>(executor);
        paths.forEach(path -> completions.submit(() -> deleteNow(path)));
        return new DeletionBatch(paths.size(), completions);
    }

    String takeFailureReason(Path path) {
        return failureReasons.remove(path);
    }

    private DeleteResult deleteNow(Path path) {
        if (!duplicatePaths.contains(path)) {
            return new DeleteResult(path, Status.NOT_REGISTERED);
        }
        if (!deletionsInProgress.add(path)) {
            return new DeleteResult(path, Status.IN_PROGRESS);
        }
        failureReasons.remove(path);
        try {
            if (!duplicatePaths.contains(path)) {
                return new DeleteResult(path, Status.NOT_REGISTERED);
            }
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return new DeleteResult(path, Status.NOT_FOUND);
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return new DeleteResult(path, Status.NOT_FILE);
            }
            ExpectedDuplicate expected = expectedByPath.get(path);
            if (expected == null) {
                return new DeleteResult(path, Status.NOT_REGISTERED);
            }
            try {
                if (Files.size(path) != expected.size()
                        || !expected.hash().equals(FileHelper.getSHAHashForFile(path, hashBufferSize))) {
                    return new DeleteResult(path, Status.CONTENT_CHANGED);
                }
            } catch (NoSuchAlgorithmException exception) {
                failureReasons.put(path, "SHA-256 is unavailable");
                return new DeleteResult(path, Status.DELETE_FAILED);
            } catch (IOException exception) {
                failureReasons.put(path, failureReason(exception));
                return new DeleteResult(path, Status.DELETE_FAILED);
            }
            beforeDelete.accept(path);
            try {
                deleteFileWithRetries(path);
            } catch (NoSuchFileException exception) {
                return new DeleteResult(path, Status.NOT_FOUND);
            } catch (IOException exception) {
                String reason = failureReason(exception);
                failureReasons.put(path, reason);
                System.err.printf(
                        "Could not remove [%s] after %d attempts: %s%n",
                        path,
                        retryDelaysMillis.length + 1,
                        reason
                );
                return new DeleteResult(path, Status.DELETE_FAILED);
            }
            duplicatePaths.remove(path);
            expectedByPath.remove(path);
            return new DeleteResult(path, Status.DELETED);
        } finally {
            deletionsInProgress.remove(path);
        }
    }

    private void deleteFileWithRetries(Path path) throws IOException {
        boolean restoreReadOnly = clearDosReadOnly(path);
        IOException lastFailure = null;
        try {
            for (int attempt = 0; attempt <= retryDelaysMillis.length; attempt++) {
                try {
                    fileDeletion.delete(path);
                    return;
                } catch (NoSuchFileException exception) {
                    throw exception;
                } catch (IOException exception) {
                    lastFailure = exception;
                    if (attempt == retryDelaysMillis.length) {
                        break;
                    }
                    try {
                        Thread.sleep(retryDelaysMillis[attempt]);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while retrying duplicate removal", interrupted);
                    }
                }
            }
            throw Objects.requireNonNull(lastFailure);
        } catch (IOException exception) {
            if (restoreReadOnly && Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    setDosReadOnly(path, true);
                } catch (IOException restoreFailure) {
                    exception.addSuppressed(restoreFailure);
                }
            }
            throw exception;
        }
    }

    private static boolean clearDosReadOnly(Path path) {
        try {
            DosFileAttributeView attributes = Files.getFileAttributeView(
                    path,
                    DosFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            if (attributes == null || !attributes.readAttributes().isReadOnly()) {
                return false;
            }
            attributes.setReadOnly(false);
            return true;
        } catch (IOException | UnsupportedOperationException exception) {
            return false;
        }
    }

    private static void setDosReadOnly(Path path, boolean readOnly) throws IOException {
        DosFileAttributeView attributes = Files.getFileAttributeView(
                path,
                DosFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (attributes != null) {
            attributes.setReadOnly(readOnly);
        }
    }

    private static String failureReason(IOException exception) {
        if (exception instanceof FileSystemException fileSystemException
                && fileSystemException.getReason() != null
                && !fileSystemException.getReason().isBlank()) {
            return fileSystemException.getReason();
        }
        if (exception instanceof FileSystemException) {
            return exception.getClass().getSimpleName()
                    + " (the filesystem supplied no reason; the file may be open, protected, or unavailable on the network)";
        }
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    static long retryWindowMillis(DiskType diskType) {
        return Arrays.stream(retryDelays(diskType)).sum();
    }

    private static long[] retryDelays(DiskType diskType) {
        return switch (diskType) {
            case HDD -> HDD_RETRY_DELAYS_MILLIS.clone();
            case NVME -> NVME_RETRY_DELAYS_MILLIS.clone();
        };
    }

    private static DeleteResult await(Future<DeleteResult> deletion) throws IOException {
        try {
            return deletion.get();
        } catch (InterruptedException exception) {
            deletion.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while removing duplicate", exception);
        } catch (ExecutionException exception) {
            throw new IOException("Concurrent duplicate removal failed", exception.getCause());
        }
    }

    @Override
    public void close() {
        executor.close();
    }

    record DeleteResult(Path path, Status status) { }

    record DeletionBatch(int size, CompletionService<DeleteResult> completions) { }

    enum Status {
        DELETED,
        NOT_FOUND,
        NOT_REGISTERED,
        NOT_FILE,
        CONTENT_CHANGED,
        DELETE_FAILED,
        IN_PROGRESS
    }

    @FunctionalInterface
    interface FileDeletion {
        void delete(Path path) throws IOException;
    }

    private record ExpectedDuplicate(long size, String hash) { }
}
