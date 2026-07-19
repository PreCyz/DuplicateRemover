# Duplicate File Remover agent guidance

## Project map

- `src/main/java/pg/duplicatefileremover`: Java 25 command-line entry point and supported file extensions.
- `src/main/java/pg/duplicatefileremover/helpers`: directory scanning, SHA-256 comparison, duplicate handling, and HTML report generation.
- `src/main/resources/reportTemplate.html`: report template used by `ReportHelper`.
- `src/test/java`: JUnit 5 tests; filesystem tests use `@TempDir`.
- `pom.xml`: Maven build and test dependencies. The project has no Maven wrapper and no JPMS module.
- `.agents/references/code-review.md`: project-specific review checklist.

## Commands

Use `mvnd` for every Maven invocation. If `mvnd` is not available, use `mvn` as a direct substitute. Run the narrowest relevant check first:

- `mvnd -Dtest=<TestClass> test`
- `mvnd test`
- `mvnd package`
- `python .codex/scripts/validate_ai_config.py` after changing agent configuration
- `git diff --check`

## Safety boundaries

- Never run the application against user-owned directories during tests or routine verification. It recursively reads files and can be configured to move duplicates.
- Use JUnit `@TempDir` for tests that create, hash, report on, or move files. Do not write test output to `reports/` or the repository root.
- Treat duplicate detection as data-sensitive logic: compare content hashes, preserve the original file, and test filename collisions before changing move behavior.
- Preserve deterministic behavior under concurrent directory processing. Review the static shared collections in `DuplicateFileRemover` and `FileHelper` whenever traversal or grouping changes.
- Keep report output valid and escape file-derived values before embedding them in HTML. Load packaged resources in a way that works outside the source checkout when changing report generation.
- Do not add frameworks or dependencies when the JDK already provides the required functionality.

## Working conventions

- Keep changes focused and follow the existing `pg.duplicatefileremover` package layout.
- Add or update a focused JUnit 5 test for behavior changes; prefer AssertJ where the existing tests use it.
- Use try-with-resources for files and streams. Check complete reads and handle inaccessible directories explicitly.
- Do not modify or discard unrelated working-tree changes. Report skipped checks and their reason.
