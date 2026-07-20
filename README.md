# Duplicate File Remover

Duplicate File Remover scans one or more directories recursively for supported image and video files. Files are first grouped by size; large candidates are sampled at the beginning, middle, and end before sample-matching files are compared using full SHA-256. Equal-sized files with different content are therefore not reported as duplicates. Within each matching group, the oldest file by creation time and then modification time is treated as the origin; the path is used as a deterministic tie-breaker.

The generated Bootstrap report shows the original file, every duplicate path, media previews, scan statistics, and the disk space occupied by duplicates. The report can remove one duplicate or all reported duplicates while preserving every origin file.

## Requirements

- JDK 25
- Maven Daemon (`mvnd`) with Apache Maven 3.9 or newer; `mvn` is supported as a fallback
- A modern browser
- Internet access is optional for report styling because Bootstrap CSS is packaged in the application; Bootstrap's JavaScript enhancements are loaded from jsDelivr when available

## Build

From the repository root:

```powershell
mvnd clean package
```

If `mvnd` is not available, run the same command with `mvn`.

The executable JAR is created at `target/DuplicateFileRemover-1.0-SNAPSHOT.jar`.

## Run

Pass one or more directories to scan. Use `--disk=HDD` or `--disk=NVMe` to select a storage profile; HDD is the default. The separated form `--disk NVMe` is also accepted. Quote paths that contain spaces:

```powershell
java -jar target/DuplicateFileRemover-1.0-SNAPSHOT.jar --disk=NVMe "D:\Photos" "E:\Videos"
```

The final directory name may contain `*` or `?` wildcards. Matching directories are selected at that level and then scanned recursively without restricting descendant names. For example, this scans every direct child directory of `A:\` whose name starts with `20`:

```powershell
java -jar target/DuplicateFileRemover-1.0-SNAPSHOT.jar "A:\20*"
```

On Windows, a wildcard directly after a drive letter is also treated as rooted, so `A:fotki*` is equivalent to `A:\fotki*`. Wildcards select directories only; matching files at the same level are ignored.

On Windows, the bundled launcher accepts the same directory arguments:

```powershell
.\run.bat --disk=NVMe "D:\Photos" "E:\Videos"
```

The application:

1. Recursively scans supported media files using disk-specific traversal, sampling, hashing, deletion, and buffer settings. HDD scans keep content I/O path-ordered and progressively sample the beginning, middle, and end only for still-colliding files; NVMe scans favor higher concurrency.
2. Writes `reports/duplicates-report.html`.
3. Starts a loopback-only report server and attempts to open the report in your browser.
4. Keeps the local report server running until you close all report tabs or press Enter in the terminal.

The terminal shows elapsed time for every active step. Steps with a known total also show a smoothed estimated time remaining and expected completion time after a short warm-up. Discovery shows elapsed time only because its total workload is not known until traversal finishes. The HTML report shows both the total scan time and the duration of every completed scan step below the main summary.

Keep the terminal window open while using **Remove** or **Remove All Duplicates**. The browser buttons call the local server, which accepts deletion only for files verified as duplicates during this scan. Before deletion, the application verifies that the file still has the same size and SHA-256 hash. Files changed after scanning are preserved. After the last report tab closes, the server stops automatically following a short grace period; pressing Enter remains available as an immediate fallback.

If the browser does not open automatically, copy the `http://127.0.0.1:...` URL printed in the terminal. Opening the saved HTML file directly still requires the application and its local report server to be running for previews and removal actions.

Full SHA-256 results are cached in `reports/hash-cache.properties` and reused only while a file's normalized path, size, creation time, and modification time still match. Same-size groups whose files are all cached bypass content sampling and hashing. The cache location can be overridden with the `duplicate.hash.cache.path` system property. Files are always rehashed immediately before deletion using disk-specific worker and buffer settings.

## Supported media extensions

Images: `jpg`, `jpeg`, `png`, `gif`, `bmp`, `webp`

Videos: `mp4`, `mov`, `3gp`, `m4v`

Extension matching is case-insensitive. Browser preview support depends on the media codecs installed in the browser and operating system.

## Test

```powershell
mvnd test
```

If `mvnd` is not available, run `mvn test`.

Tests use temporary directories and do not scan or modify personal media folders.
