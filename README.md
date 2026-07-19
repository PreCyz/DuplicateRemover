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

Pass one or more directories to scan. Quote paths that contain spaces:

```powershell
java -jar target/DuplicateFileRemover-1.0-SNAPSHOT.jar "D:\Photos" "E:\Videos"
```

On Windows, the bundled launcher accepts the same directory arguments:

```powershell
.\run.bat "D:\Photos" "E:\Videos"
```

The application:

1. Recursively scans supported media files.
2. Writes `reports/duplicates-report.html`.
3. Starts a loopback-only report server and attempts to open the report in your browser.
4. Keeps the local report server running until you close all report tabs or press Enter in the terminal.

Keep the terminal window open while using **Remove** or **Remove All Duplicates**. The browser buttons call the local server, which accepts deletion only for files verified as duplicates during this scan. Before deletion, the application verifies that the file still has the same size and SHA-256 hash. Files changed after scanning are preserved. After the last report tab closes, the server stops automatically following a short grace period; pressing Enter remains available as an immediate fallback.

If the browser does not open automatically, copy the `http://127.0.0.1:...` URL printed in the terminal. Opening the saved HTML file directly still requires the application and its local report server to be running for previews and removal actions.

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
