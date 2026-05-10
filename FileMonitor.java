//wathes diretory and detets hanges
import java.io.*;
import java.util.*;

/**
 * Monitors a local directory for file changes.
 * Detects three types of events:
 *   - NEW FILE:      file appears that was not previously tracked
 *   - MODIFIED FILE: file exists and its timestamp has changed
 *   - DELETED FILE:  file was tracked but no longer exists locally
 *
 * Uses timestamp polling — checks every CHECK_INTERVAL milliseconds.
 * Only monitors files matching the specified extension filter.
 */
public class FileMonitor {

    // ----------------------------------------------------------------
    // CHANGE EVENT TYPES
    // ----------------------------------------------------------------
    public enum ChangeType {
        NEW,
        MODIFIED,
        DELETED
    }

    // ----------------------------------------------------------------
    // CHANGE EVENT — represents one detected file change
    // ----------------------------------------------------------------
    public static class FileChange {
        public final String     filename;
        public final File       file;
        public final ChangeType type;
        public final long       timestamp;

        public FileChange(String filename, File file,
                          ChangeType type, long timestamp) {
            this.filename  = filename;
            this.file      = file;
            this.type      = type;
            this.timestamp = timestamp;
        }

        @Override
        public String toString() {
            return String.format("[%s] %s (%s)",
                type, filename,
                new java.util.Date(timestamp));
        }
    }

    // ----------------------------------------------------------------
    // FIELDS
    // ----------------------------------------------------------------
    private final String         watchDirectory;
    private final TimestampStore store;
    private final String[]       extensionFilter;

    // ----------------------------------------------------------------
    // CONSTRUCTOR
    // ----------------------------------------------------------------
    public FileMonitor(String watchDirectory,
                       TimestampStore store,
                       String... extensionFilter) {
        this.watchDirectory  = watchDirectory;
        this.store           = store;
        this.extensionFilter = extensionFilter;
    }

    // ----------------------------------------------------------------
    // SCAN DIRECTORY FOR CHANGES
    // Returns list of all detected changes since last scan
    // Called every poll cycle
    // ----------------------------------------------------------------
    public List<FileChange> scanForChanges() {
        List<FileChange> changes = new ArrayList<>();

        File dir = new File(watchDirectory);

        // Verify directory exists
        if (!dir.exists() || !dir.isDirectory()) {
            System.out.println("[MONITOR] Directory not found: " +
                watchDirectory);
            return changes;
        }

        // Get all files currently in directory
        File[] currentFiles = dir.listFiles();
        if (currentFiles == null) currentFiles = new File[0];

        // Track which files we've seen in this scan
        Set<String> seenFiles = new HashSet<>();

        // --------------------------------------------------------
        // CHECK EACH FILE IN DIRECTORY
        // Detects NEW and MODIFIED files
        // --------------------------------------------------------
        for (File file : currentFiles) {
            // Skip directories — only monitor files
            if (!file.isFile()) continue;

            // Apply extension filter if specified
            if (!matchesFilter(file.getName())) continue;

            String filename  = file.getName();
            long   lastMod   = file.lastModified();
            seenFiles.add(filename);

            if (store.isNewFile(filename)) {
                // File has never been seen before — NEW
                changes.add(new FileChange(
                    filename, file, ChangeType.NEW, lastMod));
                System.out.println("[MONITOR] New file detected: " +
                    filename);

            } else if (store.hasChanged(filename, lastMod)) {
                // File exists but timestamp changed — MODIFIED
                changes.add(new FileChange(
                    filename, file, ChangeType.MODIFIED, lastMod));
                System.out.println("[MONITOR] Modified file detected: " +
                    filename);
            }
        }

        // --------------------------------------------------------
        // CHECK FOR DELETED FILES
        // Files that were tracked but are no longer in directory
        // --------------------------------------------------------
        Set<String> trackedFiles = store.getTrackedFiles();
        for (String trackedFile : trackedFiles) {
            if (!seenFiles.contains(trackedFile)) {
                // File was tracked but no longer exists — DELETED
                File deletedFile = new File(watchDirectory, trackedFile);
                changes.add(new FileChange(
                    trackedFile, deletedFile,
                    ChangeType.DELETED, System.currentTimeMillis()));
                System.out.println("[MONITOR] Deleted file detected: " +
                    trackedFile);
            }
        }

        return changes;
    }

    // ----------------------------------------------------------------
    // CHECK IF FILENAME MATCHES EXTENSION FILTER
    // If no filter specified, all files match
    // ----------------------------------------------------------------
    private boolean matchesFilter(String filename) {
        if (extensionFilter == null || extensionFilter.length == 0) {
            return true;
        }
        String lower = filename.toLowerCase();
        for (String ext : extensionFilter) {
            if (lower.endsWith(ext.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    // ----------------------------------------------------------------
    // GET CURRENT STATE OF DIRECTORY
    // Returns map of filename -> last modified timestamp
    // ----------------------------------------------------------------
    public Map<String, Long> getCurrentState() {
        Map<String, Long> state = new LinkedHashMap<>();
        File dir = new File(watchDirectory);

        if (!dir.exists() || !dir.isDirectory()) return state;

        File[] files = dir.listFiles();
        if (files == null) return state;

        for (File file : files) {
            if (!file.isFile()) continue;
            if (!matchesFilter(file.getName())) continue;
            state.put(file.getName(), file.lastModified());
        }

        return state;
    }

    // ----------------------------------------------------------------
    // PRINT CURRENT DIRECTORY STATE
    // Useful for startup summary and debugging
    // ----------------------------------------------------------------
    public void printCurrentState() {
        Map<String, Long> state = getCurrentState();
        System.out.println("[MONITOR] Current directory: " +
            watchDirectory);
        System.out.println("[MONITOR] Files monitored: " + state.size());
        for (Map.Entry<String, Long> entry : state.entrySet()) {
            System.out.println("  " + entry.getKey() +
                " — " + new java.util.Date(entry.getValue()));
        }
    }

    // ----------------------------------------------------------------
    // GETTERS
    // ----------------------------------------------------------------
    public String getWatchDirectory() { return watchDirectory; }
}