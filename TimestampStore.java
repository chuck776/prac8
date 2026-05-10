//saves and loads file timestamps to disk
import java.io.*;
import java.util.*;

/**
 * Stores and loads file modification timestamps to/from disk.
 * Used to detect which files have changed since the last poll.
 *
 * Format of timestamps.dat:
 *   filename|lastModified
 *   notes.txt|1746912345678
 *   report.txt|1746912456789
 */
public class TimestampStore {

    private static final String STORE_FILE = "timestamps.dat";

    // In-memory map of filename -> last known modification time
    private Map<String, Long> timestamps = new HashMap<>();

    // ----------------------------------------------------------------
    // CONSTRUCTOR — load existing timestamps from disk
    // ----------------------------------------------------------------
    public TimestampStore() {
        loadFromFile();
    }

    // ----------------------------------------------------------------
    // GET STORED TIMESTAMP FOR A FILE
    // Returns -1 if file has never been seen before
    // ----------------------------------------------------------------
    public long getTimestamp(String filename) {
        return timestamps.getOrDefault(filename, -1L);
    }

    // ----------------------------------------------------------------
    // UPDATE TIMESTAMP FOR A FILE
    // Called after successful upload
    // ----------------------------------------------------------------
    public void updateTimestamp(String filename, long lastModified) {
        timestamps.put(filename, lastModified);
        saveToFile();
    }

    // ----------------------------------------------------------------
    // CHECK IF FILE IS NEW (never seen before)
    // ----------------------------------------------------------------
    public boolean isNewFile(String filename) {
        return !timestamps.containsKey(filename);
    }

    // ----------------------------------------------------------------
    // CHECK IF FILE HAS CHANGED
    // Returns true if timestamp differs from stored value
    // ----------------------------------------------------------------
    public boolean hasChanged(String filename, long currentTimestamp) {
        long stored = getTimestamp(filename);
        return stored != currentTimestamp;
    }

    // ----------------------------------------------------------------
    // GET ALL TRACKED FILENAMES
    // ----------------------------------------------------------------
    public Set<String> getTrackedFiles() {
        return new HashSet<>(timestamps.keySet());
    }

    // ----------------------------------------------------------------
    // REMOVE A FILE FROM TRACKING
    // Called when local file is deleted
    // We stop tracking it but do NOT delete the backup
    // ----------------------------------------------------------------
    public void removeFile(String filename) {
        timestamps.remove(filename);
        saveToFile();
    }

    // ----------------------------------------------------------------
    // LOAD TIMESTAMPS FROM FILE
    // ----------------------------------------------------------------
    private void loadFromFile() {
        File file = new File(STORE_FILE);
        if (!file.exists()) {
            System.out.println("[STORE] No timestamp store found " +
                "— starting fresh.");
            return;
        }

        try (BufferedReader reader = new BufferedReader(
                new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split("\\|");
                if (parts.length == 2) {
                    String filename     = parts[0];
                    long   lastModified = Long.parseLong(parts[1]);
                    timestamps.put(filename, lastModified);
                }
            }
            System.out.println("[STORE] Loaded " + timestamps.size() +
                " tracked file(s).");
        } catch (IOException e) {
            System.out.println("[STORE] Could not load timestamps: " +
                e.getMessage());
        }
    }

    // ----------------------------------------------------------------
    // SAVE ALL TIMESTAMPS TO FILE
    // Called after every update so data persists across restarts
    // ----------------------------------------------------------------
    private void saveToFile() {
        try (PrintWriter writer = new PrintWriter(
                new FileWriter(STORE_FILE))) {
            for (Map.Entry<String, Long> entry : timestamps.entrySet()) {
                writer.println(entry.getKey() + "|" + entry.getValue());
            }
        } catch (IOException e) {
            System.out.println("[STORE] Could not save timestamps: " +
                e.getMessage());
        }
    }

    // ----------------------------------------------------------------
    // PRINT ALL TRACKED FILES — useful for debugging
    // ----------------------------------------------------------------
    public void printAll() {
        System.out.println("[STORE] Tracked files (" +
            timestamps.size() + "):");
        for (Map.Entry<String, Long> entry : timestamps.entrySet()) {
            System.out.println("  " + entry.getKey() +
                " → " + new java.util.Date(entry.getValue()));
        }
    }
}