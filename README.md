# COS332 Practical 8 - RFC 959 FTP File Backup & Restore Client

A hand-rolled FTP client written in pure Java with **no external FTP libraries**.  
All FTP protocol interaction is implemented from scratch using raw sockets in compliance with RFC 959.

## Team Members

Uminathi Mdluli  
Zamokuhle Zwane


## Project Overview

This system monitors a local directory (`watched_files/`) and automatically uploads any changed files to a remote FTP server. It also provides a restore tool that can pull any previous version of a file back down from the server - useful for recovering from accidental deletions or overwrites.

The program demonstrates low-level socket programming and direct FTP protocol implementation without relying on any FTP client libraries.

### Key Features

- Pure socket-based FTP communication (no Apache Commons Net, no `sun.net.ftp`)
- RFC 959 compliant - implements the full FTP command/response cycle from scratch
- Passive mode (PASV) data connections - properly parses the `227` response to extract the server's IP and port
- File change detection via timestamp polling
- Versioned backups - every upload is saved as `file.txt.000`, `file.txt.001`, `file.txt.002` etc., persisted across restarts
- Safe deletion handling - local deletions never remove the server backup
- Bidirectional transfer - STOR for upload, RETR for restore
- Interactive restore CLI - `java RestoreClient notes.txt 2` pulls version 2 back to your machine


## How FTP Actually Works (The Weird Part)

FTP is one of the stranger protocols you'll encounter. Most protocols use a single connection for everything, but FTP uses **two separate TCP connections**:

**1. The Control Connection (port 21)**  
This is where all commands are sent and responses received. It stays open for the entire session. Every command you send (`USER`, `PASS`, `STOR`, `RETR`, etc.) goes here, and every server response (`220`, `230`, `226`, etc.) comes back here.

**2. The Data Connection (ephemeral port)**  
Every time actual data needs to move - whether that's a file upload, download, or even just a directory listing - a brand new TCP connection is opened just for that transfer, then closed immediately after. This is the part that trips people up.

### Active vs Passive Mode

There are two ways to establish the data connection:

**Active mode (PORT):** The *client* opens a port and tells the server "connect to me here." The server then initiates the data connection back to the client. This breaks behind firewalls and NAT because the server can't reach the client's port.

**Passive mode (PASV):** The *server* opens a port and tells the client "connect to me here." The client initiates the data connection. This works through firewalls because the client always initiates.

We use **passive mode** exclusively, which is the modern standard.

### The PASV Response

When you send `PASV`, the server replies like this:

```
227 Entering Passive Mode (127,0,0,1,44,127)
```

The six numbers in brackets are: `h1,h2,h3,h4,p1,p2`

- `h1.h2.h3.h4` = the IP address to connect to
- Port = `p1 * 256 + p2`

So `(127,0,0,1,44,127)` means connect to `127.0.0.1` on port `44*256 + 127 = 11391`.

This is what `enterPassiveMode()` in `FtpConnection.java` parses.

### The Full Upload Flow (STOR)

```
Client                          Server
  │                               │
  ├── [connect port 21] ─────────►│
  │◄──────────────────────────────┤ 220 ready
  ├── USER root ─────────────────►│
  │◄──────────────────────────────┤ 331 need password
  ├── PASS ****  ────────────────►│
  │◄──────────────────────────────┤ 230 logged in
  ├── MKD ftp_backup ────────────►│ (create dir if needed)
  │◄──────────────────────────────┤ 257/550 created/exists
  ├── CWD ftp_backup ────────────►│
  │◄──────────────────────────────┤ 250 directory changed
  ├── TYPE I ─────────────────────►│ (binary mode)
  │◄──────────────────────────────┤ 200 ok
  ├── PASV ───────────────────────►│ (request passive port)
  │◄──────────────────────────────┤ 227 (ip,p1,p2)
  ├── [open data connection] ─────►│
  ├── STOR notes.txt.000 ─────────►│
  │◄──────────────────────────────┤ 150 ready
  ├── [send file bytes on data] ──►│
  ├── [close data connection] ────►│
  │◄──────────────────────────────┤ 226 transfer complete
  ├── QUIT ───────────────────────►│
  │◄──────────────────────────────┤ 221 goodbye
```

### The Full Download Flow (RETR)

Identical to STOR except:
- `RETR filename` instead of `STOR filename`
- Bytes flow **from** server **to** client on the data connection

 

## How Change Detection Works

Every file's last-modified timestamp is stored in `timestamps.dat` on disk:

```
notes.txt|1746912345678
report.txt|1746912456789
```

Every poll cycle, `FileMonitor` scans the watched directory and compares current timestamps against stored ones:

| Situation | Detection | Action |
|-----------|-----------|--------|
| File in directory, not in store | NEW | Upload |
| File in directory, timestamp changed | MODIFIED | Upload |
| File in store, not in directory | DELETED | Keep backup, stop tracking |

Timestamps persist across restarts via `TimestampStore`. Version counters also persist in `versions.dat` so version numbering never resets.

## How Versioning Works

Every time a file is uploaded, two copies land on the server:

1. `notes.txt.000` / `notes.txt.001` etc. - the immutable version history
2. `notes.txt` - always the latest copy (overwritten each time)

Version numbers persist in `versions.dat`:

```
notes.txt|3
report.txt|1
```

So even if you restart the backup client, the next upload of `notes.txt` correctly becomes `.003`, not `.000` again.


## Project Structure

```
prac8/
├── BackupClient.java     # Main program - menu, backup loop, orchestration
├── FtpConnection.java    # Raw socket FTP - all protocol commands
├── FileMonitor.java      # Directory watcher - detects NEW/MODIFIED/DELETED
├── TimestampStore.java   # Persists file timestamps to timestamps.dat
├── RestoreClient.java    # Restore CLI - downloads versioned backups via RETR
├── timestamps.dat        # Auto-generated: tracked file timestamps
├── versions.dat          # Auto-generated: version counters per filename
└── watched_files/        # The directory being monitored
```


## Prerequisites

| Requirement | Version |
|-------------|---------|
| Java (JDK) | 11+ |
| vsftpd | 3.x |

## Server Setup

### 1. Install vsftpd

```bash
sudo apt install vsftpd -y
```

### 2. Configure vsftpd

```bash
sudo nano /etc/vsftpd.conf
```

Find and set these fields:

```
anonymous_enable=NO
local_enable=YES
write_enable=YES
local_umask=022
chroot_local_user=NO
```

Add at the bottom:

```
allow_writeable_chroot=YES
pasv_enable=YES
pasv_min_port=40000
pasv_max_port=50000
```

### 3. Allow root login (if using root)

```bash
sudo sed -i '/^root$/d' /etc/ftpusers
```

### 4. Restart and verify

```bash
sudo systemctl restart vsftpd
sudo systemctl status vsftpd
```

Look for `Active: active`.

### 5. Create the backup directory

```bash
mkdir -p /root/ftp_backup
chmod 755 /root/ftp_backup
```

### 6. Verify port 21 is listening

```bash
sudo ss -tlnp | grep :21
```

### 7. Test with raw FTP (manual verification)

```bash
ftp localhost
# Name: root
# Password: yourpassword
ftp> pwd
ftp> quit
```


## Configuration

Open `BackupClient.java` and update these constants at the top:

```java
private static final String FTP_HOST  = "localhost";
private static final int    FTP_PORT  = 21;
private static final String FTP_USER  = "root";       // your Linux username
private static final String FTP_PASS  = "yourpass";   // your Linux password
private static final String REMOTE_DIR = "ftp_backup";
private static final String WATCH_DIR  = "watched_files";
private static final int    POLL_INTERVAL = 60_000;   // 60 seconds
```

`RestoreClient.java` has the same constants - update them to match.


## Compile and Run

```bash
cd ~/prac8
javac *.java
```

### Run the backup client

```bash
java BackupClient
```

Menu options:

```
1. Start automatic backup       ← polls every 60 seconds until Ctrl+C
2. Run single backup check      ← useful for testing
3. Show watched files           ← lists files currently in watched_files/
4. Show timestamp store         ← shows what timestamps are recorded
5. Test FTP connection          ← verifies login and directory access
6. Exit
```

### Run the restore client

```bash
java RestoreClient notes.txt list       # list all available versions
java RestoreClient notes.txt 0          # restore version 0 (notes.txt.000)
java RestoreClient notes.txt 3          # restore version 3 (notes.txt.003)
java RestoreClient notes.txt latest     # restore most recent version
java RestoreClient                      # interactive menu
```


## Example Output

### Backup

```
╔══════════════════════════════════════════╗
║      FTP FILE BACKUP CLIENT              ║
║      COS332 Practical 8                  ║
║      RFC 959 FTP Protocol                ║
╚══════════════════════════════════════════╝

[BACKUP] Scanning watched_files...
[MONITOR] Modified file detected: notes.txt
[BACKUP] Detected 1 change(s).
[FTP] Connecting to localhost:21...
[FTP] <- 220 (vsFTPd 3.0.5)
[FTP] -> USER root
[FTP] <- 230 Login successful.
[FTP] -> TYPE I
[FTP] <- 200 Switching to Binary mode.
[FTP] -> PASV
[FTP] <- 227 Entering Passive Mode (127,0,0,1,44,127).
[FTP] Passive mode: connecting to 127.0.0.1:11391
[FTP] -> STOR notes.txt.002
[FTP] <- 150 Ok to send data.
[FTP] Sent 43 bytes.
[FTP] <- 226 Transfer complete.
[BACKUP] Successfully backed up: notes.txt
```

### Restore

```
[RESTORE] ════════════════════════════
[RESTORE] File    : notes.txt
[RESTORE] Version : 0  (notes.txt.000)
[RESTORE] Target  : watched_files/notes.txt
[RESTORE] ════════════════════════════
[FTP] -> RETR notes.txt.000
[FTP] <- 150 Opening BINARY mode data connection (43 bytes).
[FTP] Received 43 bytes.
[FTP] <- 226 Transfer complete.

[RESTORE] ✓ SUCCESS
[RESTORE] Restored notes.txt.000 → watched_files/notes.txt
```

## Demo Script

A step-by-step walkthrough for demonstrating the full system.

### Step 1 - Confirm vsftpd is running

```bash
sudo systemctl status vsftpd
```

Look for `Active: active`. If not: `sudo systemctl start vsftpd`

### Step 2 - Compile

```bash
cd ~/prac8
javac *.java
```

### Step 3 - Create a test file with real content

```bash
echo "Important meeting notes - do not lose this" > watched_files/notes.txt
cat watched_files/notes.txt
```

### Step 4 - Back it up (first version)

```bash
java BackupClient
# choose option 2, then 6
```

Point out: `STOR notes.txt.000` in the output - this is version 0 being written to the server via raw FTP socket.

### Step 5 - Destroy the file

```bash
echo "" > watched_files/notes.txt
cat watched_files/notes.txt    # shows empty
```

### Step 6 - Back up the destroyed version

```bash
java BackupClient
# choose option 2, then 6
```

Point out: `STOR notes.txt.001` - the empty version is now saved as version 1. Version 0 (the good one) is untouched on the server.

### Step 7 - Verify both versions exist on server

```bash
ls /root/ftp_backup/
```

You should see: `notes.txt`, `notes.txt.000`, `notes.txt.001`

### Step 8 - Restore the original

```bash
java RestoreClient notes.txt 0
```

Point out: `RETR notes.txt.000` - the client opens a new control connection, negotiates PASV, and pulls the file back down via the data channel.

### Step 9 - Confirm the content is back

```bash
cat watched_files/notes.txt
# Important meeting notes - do not lose this
```

### Step 10 - Show version listing (bonus)

```bash
java RestoreClient notes.txt list
```

Shows all available versions with their numbers - useful for picking exactly which state to restore to.

### Step 11 - Show the deletion safety feature (optional)

```bash
rm watched_files/notes.txt
java BackupClient   # option 2
```

Point out: `[BACKUP] Keeping backup on server - removing from local tracking only.`

Then verify:

```bash
ls /root/ftp_backup/   # notes.txt.000 and notes.txt.001 still there
```

The backup is never deleted even when the local file is gone.


## RFC 959 Response Codes Used

| Code | Meaning | When we see it |
|------|---------|----------------|
| `220` | Service ready | On connect |
| `230` | User logged in | After PASS |
| `200` | Command OK | After TYPE I |
| `227` | Entering passive mode | After PASV |
| `150` | File status OK, opening data connection | After STOR/RETR |
| `226` | Transfer complete | After data connection closes |
| `250` | Action completed | After CWD |
| `257` | Directory created | After MKD |
| `221` | Goodbye | After QUIT |
| `331` | Password required | After USER |
| `550` | Permission denied / file unavailable | MKD on existing dir |


## Subproblems Solved

**Subproblem 1 - Detecting file changes:**  
We store the `lastModified()` timestamp of every file in `timestamps.dat`. Every poll cycle we compare current timestamps against stored ones. Three cases: new file (not in store), modified file (timestamp changed), deleted file (in store but not on disk).

**Subproblem 2 - The deletion problem:**  
The assignment specifically warns against deleting backup files when local files are deleted. We handle this safely - when a deletion is detected, we remove the file from local tracking only. The server backup is never touched. You might have accidentally deleted it and want it back; that's exactly what the restore client is for.

**Subproblem 3 - Version history:**  
Every upload is stored as a numbered version (`file.txt.000`, `file.txt.001` etc.) so you can recover not just the latest backup but any historical state. Version counters persist in `versions.dat` so they survive restarts.

**Subproblem 4 - Bidirectional transfer (bonus):**  
The restore client uses `RETR` over a PASV data connection - the same infrastructure as uploads but in reverse. This means you can type `java RestoreClient notes.txt 2` to pull any version back to your local machine at any time.


## Troubleshooting

| Error | Cause | Fix |
|-------|-------|-----|
| `Connection refused` | vsftpd not running | `sudo systemctl start vsftpd` |
| `530 Login incorrect` | Wrong credentials in code | Update `FTP_USER`/`FTP_PASS` in `BackupClient.java` |
| `550 Permission denied` on STOR | `write_enable` not set | Add `write_enable=YES` to `/etc/vsftpd.conf` and restart |
| `550` on MKD | Directory already exists | Normal - code handles this, CWD still works |
| Root can't log in | Root blocked in ftpusers | `sudo sed -i '/^root$/d' /etc/ftpusers` |
| 

## Submission Package

```bash
cd ~/prac8
tar -cvf ../prac8.tar *.java README.md
tar -tvf ../prac8.tar
```

MD5 hash (of compiled Main/BackupClient class):

```bash
md5sum BackupClient.class
d93eda69993b7c0a21573c979a43eebb  BackupClient.class
```


## References

- [RFC 959](https://datatracker.ietf.org/doc/html/rfc959) - File Transfer Protocol (1985)
- [RFC 2428](https://datatracker.ietf.org/doc/html/rfc2428) - FTP Extensions for IPv6 and NATs (EPSV/EPRT)
- [RFC 3659](https://datatracker.ietf.org/doc/html/rfc3659) - Extensions to FTP (MLST, SIZE, MDTM)


## License

Submitted as coursework for COS332 Computer Networks at the University of Pretoria.
