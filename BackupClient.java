//main entry point for backup client application
import java.io.*;
import java.util.*;

/**
 * FTP File Backup Client
 *
 * Monitors a local directory and automatically uploads changed files
 * to a remote FTP server using raw socket FTP protocol (RFC 959).
 *
 * Features:
 *   - Detects new, modified, and deleted files via timestamp polling
 *   - Uploads changed files via FTP PASV mode
 *   - Never deletes backup files when local files are deleted
 *   - Versioned backups (file.txt.000, file.txt.001, etc.)
 *   - Persists file timestamps across restarts
 */
public class BackupClient {

    // ----------------------------------------------------------------
    // CONFIGURATION
    // ----------------------------------------------------------------
    private static final String FTP_HOST       = "localhost";
    private static final int    FTP_PORT       = 21;
    private static final String FTP_USER       = "chichi";
    private static final String FTP_PASS       = "butter";
    private static final String REMOTE_DIR     = "ftp_backup";

    // Local directory to monitor
    private static final String WATCH_DIR      = "watched_files";

    // Poll interval in milliseconds (60 seconds)
    private static final int    POLL_INTERVAL  = 60_000;

    // Only monitor these file extensions
    // Empty array means monitor all files
    private static final String[] EXTENSIONS   = { ".txt" };

    // ----------------------------------------------------------------
    // INSTANCE FIELDS
    // ----------------------------------------------------------------
    private final FileMonitor    monitor;
    private final TimestampStore store;
    private boolean              running = true;

    // Version counter per filename for versioned backups
    // filename -> next version number
    private final Map<String, Integer> versionCounters = new HashMap<>();

    // ----------------------------------------------------------------
    // CONSTRUCTOR
    // ----------------------------------------------------------------
    public BackupClient() {
        this.store   = new TimestampStore();
        this.monitor = new FileMonitor(WATCH_DIR, store, EXTENSIONS);
    }

    // ----------------------------------------------------------------
    // MAIN ENTRY POINT
    // ----------------------------------------------------------------
    public static void main(String[] args) {
        BackupClient client = new BackupClient();
        client.printBanner();
        client.showMenu();
    }

    // ----------------------------------------------------------------
    // MENU
    // ----------------------------------------------------------------
    private void showMenu() {
        Scanner scanner = new Scanner(System.in);

        // Create watch directory if it doesn't exist
        new File(WATCH_DIR).mkdirs();

        while (true) {
            System.out.println("\n┌──────────────────────────────────────┐");
            System.out.println("│  FTP BACKUP CLIENT MENU               │");
            System.out.println("│  1. Start automatic backup            │");
            System.out.println("│  2. Run single backup check (test)    │");
            System.out.println("│  3. Show watched files                │");
            System.out.println("│  4. Show timestamp store              │");
            System.out.println("│  5. Test FTP connection               │");
            System.out.println("│  6. Exit                              │");
            System.out.println("└──────────────────────────────────────┘");
            System.out.print("Enter choice: ");

            String choice = scanner.nextLine().trim();

            switch (choice) {
                case "1": startBackupLoop();     break;
                case "2": runOnce();             break;
                case "3": monitor.printCurrentState(); break;
                case "4": store.printAll();      break;
                case "5": testConnection();      break;
                case "6":
                    System.out.println("Goodbye!");
                    return;
                default:
                    System.out.println("Invalid choice.");
            }
        }
    }

    // ----------------------------------------------------------------
    // START CONTINUOUS BACKUP LOOP
    // Polls every POLL_INTERVAL milliseconds
    // Press Ctrl+C to stop
    // ----------------------------------------------------------------
    private void startBackupLoop() {
        System.out.println("\n[BACKUP] Starting automatic backup...");
        System.out.println("[BACKUP] Watching: " + WATCH_DIR);
        System.out.println("[BACKUP] Remote:   " + FTP_HOST +
            ":" + FTP_PORT + "/" + REMOTE_DIR);
        System.out.println("[BACKUP] Polling every " +
            (POLL_INTERVAL / 1000) + " seconds.");
        System.out.println("[BACKUP] Press Ctrl+C to stop.\n");

        running    = true;
        int cycles = 0;

        while (running) {
            cycles++;
            System.out.println("\n[BACKUP] === Cycle #" + cycles +
                " at " + new Date() + " ===");

            runOnce();

            System.out.println("[BACKUP] Next check in " +
                (POLL_INTERVAL / 1000) + " seconds...");

            try {
                Thread.sleep(POLL_INTERVAL);
            } catch (InterruptedException e) {
                System.out.println("[BACKUP] Interrupted — stopping.");
                running = false;
            }
        }
    }

    // ----------------------------------------------------------------
    // RUN ONE BACKUP CYCLE
    // Scans for changes and uploads any changed files
    // ----------------------------------------------------------------
    private void runOnce() {
        System.out.println("[BACKUP] Scanning " + WATCH_DIR + "...");

        // Scan for changes
        List<FileMonitor.FileChange> changes = monitor.scanForChanges();

        if (changes.isEmpty()) {
            System.out.println("[BACKUP] No changes detected.");
            return;
        }

        System.out.println("[BACKUP] Detected " + changes.size() +
            " change(s).");

        // Process each change
        int uploaded = 0;
        int skipped  = 0;

        for (FileMonitor.FileChange change : changes) {
            switch (change.type) {
                case NEW:
                case MODIFIED:
                    // Upload the file
                    boolean success = uploadFile(change);
                    if (success) {
                        // Update stored timestamp after successful upload
                        store.updateTimestamp(
                            change.filename, change.timestamp);
                        uploaded++;
                    } else {
                        skipped++;
                    }
                    break;

                case DELETED:
                    // Assignment requirement: DO NOT delete backup
                    // Just stop tracking the file locally
                    System.out.println("[BACKUP] File deleted locally: " +
                        change.filename);
                    System.out.println("[BACKUP] Keeping backup on " +
                        "server — removing from local tracking only.");
                    store.removeFile(change.filename);
                    skipped++;
                    break;
            }
        }

        System.out.println("[BACKUP] Cycle complete. Uploaded: " +
            uploaded + " Skipped: " + skipped);
    }

    // ----------------------------------------------------------------
    // UPLOAD A SINGLE FILE VIA FTP
    // Uses passive mode (PASV) for data connection
    // Implements versioned backups:
    //   first upload  → filename.txt.000
    //   second upload → filename.txt.001
    //   etc.
    // Returns true if upload was successful
    // ----------------------------------------------------------------
    private boolean uploadFile(FileMonitor.FileChange change) {
        FtpConnection ftp = new FtpConnection(FTP_HOST, FTP_PORT);

        try {
            // Step 1 — Connect and authenticate
            ftp.connect();
            ftp.authenticate(FTP_USER, FTP_PASS);

            // Step 2 — Navigate to backup directory
            // Create it if it doesn't exist
            ftp.makeDirectory(REMOTE_DIR);
            ftp.changeDirectory(REMOTE_DIR);

            // Step 3 — Set binary mode for exact byte transfer
            ftp.setBinaryMode();

            // Step 4 — Generate versioned remote filename
            String remoteFilename = getVersionedFilename(
                change.filename);

            System.out.println("[BACKUP] Uploading: " +
                change.filename + " → " + remoteFilename);

            // Step 5 — Upload the file
            ftp.uploadFile(
                WATCH_DIR + File.separator + change.filename,
                remoteFilename);

            // Step 6 — Also upload as the current version
            // (overwrites previous current copy)
            ftp.uploadFile(
                WATCH_DIR + File.separator + change.filename,
                change.filename);

            System.out.println("[BACKUP] Successfully backed up: " +
                change.filename);

            return true;

        } catch (Exception e) {
            System.out.println("[BACKUP] Upload failed for " +
                change.filename + ": " + e.getMessage());
            return false;

        } finally {
            try { ftp.quit(); } catch (Exception ignored) {}
        }
    }

    // ----------------------------------------------------------------
    // GENERATE VERSIONED FILENAME
    // file.txt → file.txt.000 (first backup)
    // file.txt → file.txt.001 (second backup)
    // etc.
    // ----------------------------------------------------------------
    private String getVersionedFilename(String filename) {
        int version = versionCounters.getOrDefault(filename, 0);
        versionCounters.put(filename, version + 1);
        return String.format("%s.%03d", filename, version);
    }

    // ----------------------------------------------------------------
    // TEST FTP CONNECTION
    // Verifies we can connect, authenticate and reach backup directory
    // ----------------------------------------------------------------
    private void testConnection() {
        System.out.println("\n[TEST] Testing FTP connection...");
        FtpConnection ftp = new FtpConnection(FTP_HOST, FTP_PORT);

        try {
            ftp.connect();
            ftp.authenticate(FTP_USER, FTP_PASS);

            // Try to get current directory
            String pwd = ftp.printWorkingDirectory();
            System.out.println("[TEST] Current directory: " + pwd);

            // Create and navigate to backup directory
            ftp.makeDirectory(REMOTE_DIR);
            ftp.changeDirectory(REMOTE_DIR);

            System.out.println("[TEST] Successfully accessed: " +
                REMOTE_DIR);
            System.out.println("[TEST] FTP connection test PASSED.");

        } catch (Exception e) {
            System.out.println("[TEST] FTP connection test FAILED: " +
                e.getMessage());
        } finally {
            try { ftp.quit(); } catch (Exception ignored) {}
        }
    }

    // ----------------------------------------------------------------
    // PRINT STARTUP BANNER
    // ----------------------------------------------------------------
    private void printBanner() {
        System.out.println(
            "╔══════════════════════════════════════════╗");
        System.out.println(
            "║      FTP FILE BACKUP CLIENT              ║");
        System.out.println(
            "║      COS332 Practical 8                  ║");
        System.out.println(
            "║      RFC 959 FTP Protocol                ║");
        System.out.println(
            "╚══════════════════════════════════════════╝");
        System.out.println();
        System.out.println("FTP Server  : " + FTP_HOST +
            ":" + FTP_PORT);
        System.out.println("Remote Dir  : " + REMOTE_DIR);
        System.out.println("Watch Dir   : " + WATCH_DIR);
        System.out.println("Extensions  : " +
            (EXTENSIONS.length == 0 ? "all files" :
             String.join(", ", EXTENSIONS)));
        System.out.println("Poll Every  : " +
            (POLL_INTERVAL / 1000) + " seconds");
        System.out.println();
    }
}