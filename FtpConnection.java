//manages ftp ontrol onnetion
import java.io.*;
import java.net.*;

/**
 * Low-level FTP control connection handler.
 * Implements RFC 959 FTP protocol using raw socket operations.
 *
 * Handles:
 *   - Control connection on port 21
 *   - Authentication (USER/PASS)
 *   - Passive mode data connections (PASV)
 *   - File upload (STOR)
 *   - Directory operations (CWD, MKD, PWD)
 *   - Binary and ASCII transfer modes (TYPE I / TYPE A)
 */
public class FtpConnection {

    private final String host;
    private final int    controlPort;

    private Socket         controlSocket;
    private BufferedReader reader;
    private PrintWriter    writer;

    // ----------------------------------------------------------------
    // CONSTRUCTOR
    // ----------------------------------------------------------------
    public FtpConnection(String host, int controlPort) {
        this.host        = host;
        this.controlPort = controlPort;
    }

    // ----------------------------------------------------------------
    // CONNECT TO FTP SERVER
    // Opens control connection to port 21
    // RFC 959 Section 3.1 — Control Connection
    // ----------------------------------------------------------------
    public void connect() throws IOException {
        System.out.println("[FTP] Connecting to " + host +
            ":" + controlPort + "...");

        controlSocket = new Socket(host, controlPort);
        reader = new BufferedReader(
            new InputStreamReader(controlSocket.getInputStream()));
        writer = new PrintWriter(
            new OutputStreamWriter(controlSocket.getOutputStream()),
            true);

        // Read 220 welcome banner
        String response = readResponse();
        expectCode(response, "220");
        System.out.println("[FTP] Connected: " + response);
    }

    // ----------------------------------------------------------------
    // AUTHENTICATE
    // RFC 959 Section 4.1 — USER and PASS commands
    // ----------------------------------------------------------------
    public void authenticate(String username, String password)
            throws IOException {
        // Send username
        sendCommand("USER " + username);
        String response = readResponse();

        // 230 means logged in directly (no password needed)
        // 331 means password required
        if (response.startsWith("331")) {
            sendCommand("PASS " + password);
            response = readResponse();
        }

        expectCode(response, "230");
        System.out.println("[FTP] Authenticated as: " + username);
    }

    // ----------------------------------------------------------------
    // SET BINARY TRANSFER MODE
    // RFC 959 Section 3.1.1 — TYPE command
    // TYPE I = Image (binary) — preserves exact bytes
    // Essential for non-text files
    // ----------------------------------------------------------------
    public void setBinaryMode() throws IOException {
        sendCommand("TYPE I");
        expectCode(readResponse(), "200");
        System.out.println("[FTP] Transfer mode: BINARY");
    }

    // ----------------------------------------------------------------
    // SET ASCII TRANSFER MODE
    // RFC 959 Section 3.1.1 — TYPE command
    // TYPE A = ASCII — converts line endings
    // Used for plain text files
    // ----------------------------------------------------------------
    public void setAsciiMode() throws IOException {
        sendCommand("TYPE A");
        expectCode(readResponse(), "200");
        System.out.println("[FTP] Transfer mode: ASCII");
    }

    // ----------------------------------------------------------------
    // CHANGE WORKING DIRECTORY
    // RFC 959 Section 4.1 — CWD command
    // ----------------------------------------------------------------
    public void changeDirectory(String directory) throws IOException {
        sendCommand("CWD " + directory);
        expectCode(readResponse(), "250");
        System.out.println("[FTP] Changed directory to: " + directory);
    }

    // ----------------------------------------------------------------
    // PRINT WORKING DIRECTORY
    // RFC 959 Section 4.1 — PWD command
    // Returns current directory path
    // ----------------------------------------------------------------
    public String printWorkingDirectory() throws IOException {
        sendCommand("PWD");
        String response = readResponse();
        expectCode(response, "257");
        // Response format: 257 "/path/to/dir" is current directory
        int start = response.indexOf('"');
        int end   = response.lastIndexOf('"');
        if (start >= 0 && end > start) {
            return response.substring(start + 1, end);
        }
        return "";
    }

    // ----------------------------------------------------------------
    // MAKE DIRECTORY
    // RFC 959 Section 4.1 — MKD command
    // Creates directory if it doesn't exist
    // ----------------------------------------------------------------
    public void makeDirectory(String directory) throws IOException {
        sendCommand("MKD " + directory);
        String response = readResponse();
        // 257 = created, 550 = already exists (both acceptable)
        if (!response.startsWith("257") && !response.startsWith("550")) {
            throw new IOException("MKD failed: " + response);
        }
    }

    // ----------------------------------------------------------------
    // ENTER PASSIVE MODE
    // RFC 959 Section 4.1 — PASV command
    // Server opens a data port and tells us where to connect
    // Returns a connected Socket for the data connection
    //
    // Response format: 227 Entering Passive Mode (h1,h2,h3,h4,p1,p2)
    // IP address = h1.h2.h3.h4
    // Port = p1 * 256 + p2
    // ----------------------------------------------------------------
    public Socket enterPassiveMode() throws IOException {
        sendCommand("PASV");
        String response = readResponse();
        expectCode(response, "227");

        // Parse the IP and port from response
        // Example: 227 Entering Passive Mode (127,0,0,1,156,116)
        int start = response.indexOf('(');
        int end   = response.indexOf(')');
        if (start < 0 || end < 0) {
            throw new IOException("Invalid PASV response: " + response);
        }

        String[] parts = response.substring(start + 1, end).split(",");
        if (parts.length != 6) {
            throw new IOException("Invalid PASV address format: " + response);
        }

        // Reconstruct IP address
        String dataHost = parts[0] + "." + parts[1] + "." +
                          parts[2] + "." + parts[3];

        // Calculate port from p1 and p2
        int p1       = Integer.parseInt(parts[4].trim());
        int p2       = Integer.parseInt(parts[5].trim());
        int dataPort = p1 * 256 + p2;

        System.out.println("[FTP] Passive mode: connecting to " +
            dataHost + ":" + dataPort);

        // Open data connection to server's passive port
        return new Socket(dataHost, dataPort);
    }

    // ----------------------------------------------------------------
    // UPLOAD A FILE USING PASSIVE MODE
    // RFC 959 Section 4.1 — STOR command
    //
    // Process:
    //   1. Enter passive mode (get data port)
    //   2. Send STOR command on control connection
    //   3. Send file bytes on data connection
    //   4. Close data connection
    //   5. Read 226 transfer complete on control connection
    // ----------------------------------------------------------------
    public void uploadFile(String localPath, String remoteFilename)
            throws IOException {

        File localFile = new File(localPath);
        if (!localFile.exists()) {
            throw new IOException("Local file not found: " + localPath);
        }

        System.out.println("[FTP] Uploading: " + localFile.getName() +
            " (" + localFile.length() + " bytes)");

        // Step 1 — Enter passive mode and get data socket
        Socket dataSocket = enterPassiveMode();

        try {
            // Step 2 — Send STOR command on control connection
            sendCommand("STOR " + remoteFilename);
            String response = readResponse();

            // Expect 150 (ready to receive) or 125 (transfer starting)
            if (!response.startsWith("150") &&
                !response.startsWith("125")) {
                throw new IOException("STOR failed: " + response);
            }

            // Step 3 — Send file bytes on data connection
            OutputStream dataOut = dataSocket.getOutputStream();
            FileInputStream fileIn = new FileInputStream(localFile);

            byte[] buffer    = new byte[4096];
            int    bytesRead;
            long   totalSent = 0;

            while ((bytesRead = fileIn.read(buffer)) != -1) {
                dataOut.write(buffer, 0, bytesRead);
                totalSent += bytesRead;
            }

            dataOut.flush();
            fileIn.close();

            System.out.println("[FTP] Sent " + totalSent + " bytes.");

        } finally {
            // Step 4 — Close data connection
            dataSocket.close();
            System.out.println("[FTP] Data connection closed.");
        }

        // Step 5 — Read transfer complete response on control connection
        String finalResponse = readResponse();
        expectCode(finalResponse, "226");
        System.out.println("[FTP] Upload complete: " + remoteFilename);
    }

    // ----------------------------------------------------------------
    // LIST DIRECTORY CONTENTS
    // RFC 959 Section 4.1 — LIST command
    // Returns directory listing as string
    // ----------------------------------------------------------------
    public String list(String directory) throws IOException {
        // Enter passive mode for data connection
        Socket dataSocket = enterPassiveMode();

        try {
            // Send LIST command
            if (directory != null && !directory.isEmpty()) {
                sendCommand("LIST " + directory);
            } else {
                sendCommand("LIST");
            }

            String response = readResponse();
            if (!response.startsWith("150") &&
                !response.startsWith("125")) {
                throw new IOException("LIST failed: " + response);
            }

            // Read directory listing from data connection
            BufferedReader dataReader = new BufferedReader(
                new InputStreamReader(dataSocket.getInputStream()));

            StringBuilder listing = new StringBuilder();
            String line;
            while ((line = dataReader.readLine()) != null) {
                listing.append(line).append("\n");
            }

            return listing.toString();

        } finally {
            dataSocket.close();
        }

        // Read 226 transfer complete
        // (after finally block but still in method)
    }

    // ----------------------------------------------------------------
    // DELETE A REMOTE FILE
    // RFC 959 Section 4.1 — DELE command
    // ----------------------------------------------------------------
    public void deleteFile(String remoteFilename) throws IOException {
        sendCommand("DELE " + remoteFilename);
        expectCode(readResponse(), "250");
        System.out.println("[FTP] Deleted remote file: " + remoteFilename);
    }

    // ----------------------------------------------------------------
    // RENAME A REMOTE FILE
    // RFC 959 Section 4.1 — RNFR and RNTO commands
    // ----------------------------------------------------------------
    public void renameFile(String from, String to) throws IOException {
        sendCommand("RNFR " + from);
        expectCode(readResponse(), "350");
        sendCommand("RNTO " + to);
        expectCode(readResponse(), "250");
        System.out.println("[FTP] Renamed: " + from + " → " + to);
    }

    // ----------------------------------------------------------------
    // QUIT — close FTP session cleanly
    // RFC 959 Section 4.1 — QUIT command
    // ----------------------------------------------------------------
    public void quit() throws IOException {
        try {
            sendCommand("QUIT");
            readResponse(); // 221 Goodbye
        } catch (IOException ignored) {
        } finally {
            if (controlSocket != null && !controlSocket.isClosed()) {
                controlSocket.close();
            }
            System.out.println("[FTP] Disconnected.");
        }
    }

    // ----------------------------------------------------------------
    // READ A COMPLETE FTP RESPONSE
    // RFC 959 Section 4.2 — FTP Replies
    // Handles multi-line responses where continuation lines
    // start with "code-" instead of "code "
    // Example multi-line:
    //   220-Welcome to FTP server
    //   220-Please read the rules
    //   220 Ready.
    // ----------------------------------------------------------------
    private String readResponse() throws IOException {
        String line = reader.readLine();
        if (line == null) {
            throw new IOException("Connection closed by server");
        }
        System.out.println("[FTP] <- " + line);

        // Check for multi-line response
        // RFC 959: if 4th character is '-' more lines follow
        // until a line starting with same code followed by space
        if (line.length() >= 4 && line.charAt(3) == '-') {
            String code = line.substring(0, 3);
            while (true) {
                String next = reader.readLine();
                if (next == null) break;
                System.out.println("[FTP] <- " + next);
                // Final line starts with code followed by space
                if (next.startsWith(code + " ")) {
                    line = next;
                    break;
                }
            }
        }

        return line;
    }

    // ----------------------------------------------------------------
    // SEND AN FTP COMMAND ON CONTROL CONNECTION
    // ----------------------------------------------------------------
    private void sendCommand(String command) throws IOException {
        System.out.println("[FTP] -> " + command);
        writer.print(command + "\r\n");
        writer.flush();
    }

    // ----------------------------------------------------------------
    // VALIDATE RESPONSE CODE
    // RFC 959 Section 4.2 — reply codes
    // ----------------------------------------------------------------
    private void expectCode(String response, String expectedCode)
            throws IOException {
        if (!response.startsWith(expectedCode)) {
            throw new IOException("Expected " + expectedCode +
                " but got: " + response);
        }
    }

    // ----------------------------------------------------------------
    // GETTERS
    // ----------------------------------------------------------------
    public boolean isConnected() {
        return controlSocket != null &&
               !controlSocket.isClosed() &&
               controlSocket.isConnected();
    }

    public String getHost() { return host; }
    public int    getPort() { return controlPort; }
}