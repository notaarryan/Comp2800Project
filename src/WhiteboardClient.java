import java.awt.Color;
import java.io.*;
import java.net.*;

/**
 * WhiteboardClient — Client-side networking layer.
 *
 * Owns one TCP connection to WhiteboardServer.
 * A daemon background thread continuously reads incoming messages
 * and dispatches them to the registered {@link MessageListener}.
 *
 * IMPORTANT: All MessageListener callbacks are invoked on the network
 * thread, NOT the Swing EDT. Callers must wrap any UI updates in
 * SwingUtilities.invokeLater().
 *
 * Typical usage:
 * <pre>
 *   WhiteboardClient client = new WhiteboardClient(myListener);
 *   if (client.connect("192.168.1.10", WhiteboardServer.DEFAULT_PORT)) {
 *       client.createRoom("Alice");      // or client.joinRoom(...)
 *   }
 *   // Later, from mouse events:
 *   client.sendDrawSegment(x1, y1, x2, y2, colorHex, size);
 *   client.sendClear();
 *   client.disconnect();
 * </pre>
 */
public class WhiteboardClient {

    // =========================================================================
    // MessageListener — implemented by the GUI layer (whiteboardGUI)
    // =========================================================================

    /**
     * Callback interface.  One method per server-to-client event type.
     * All methods are called on the network thread — wrap UI calls with
     * SwingUtilities.invokeLater().
     */
    public interface MessageListener {

        /** A remote user drew one freehand segment (two consecutive drag points). */
        void onDrawSegment(int x1, int y1, int x2, int y2, String colorHex, float brushSize);

        /** A remote user completed a shape. */
        void onShape(String type, int startX, int startY, int endX, int endY,
                     String colorHex, float brushSize);

        /** A remote user cleared the canvas. */
        void onClear();

        /** Server confirmed our CREATE_ROOM request. */
        void onRoomCreated(String roomId, String roomCode);

        /** Server confirmed our JOIN_ROOM request. */
        void onRoomJoined(String roomId, int userCount);

        /** Another user joined our room. */
        void onUserJoined(String username);

        /** Another user left our room. */
        void onUserLeft(String username);

        /** Server sent an error (bad command, wrong code, etc.). */
        void onError(String message);
    }

    // =========================================================================

    private Socket         socket;
    private PrintWriter    out;
    private BufferedReader in;
    private final MessageListener listener;
    private volatile boolean connected = false;

    public WhiteboardClient(MessageListener listener) {
        this.listener = listener;
    }

    // -------------------------------------------------------------------------
    // Connection
    // -------------------------------------------------------------------------

    /**
     * Open a TCP connection to the server and start the background listener thread.
     *
     * @param host Server hostname or IP (e.g. "192.168.1.10" or "myserver.azure.com")
     * @param port Server port (use WhiteboardServer.DEFAULT_PORT = 5000)
     * @return true on success, false if connection refused / timed out
     */
    public boolean connect(String host, int port) {
        try {
            socket = new Socket(host, port);
            out = new PrintWriter(
                    new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            connected = true;

            // Daemon thread: will not prevent JVM shutdown
            Thread t = new Thread(this::listenLoop, "WB-Listener");
            t.setDaemon(true);
            t.start();

            System.out.println("[Client] Connected to " + host + ":" + port);
            return true;
        } catch (IOException e) {
            System.err.println("[Client] Connection failed: " + e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Background listener loop
    // -------------------------------------------------------------------------

    /**
     * Continuously read lines from the server socket and dispatch to parseServerMessage().
     * Runs on the WB-Listener daemon thread until the connection is closed.
     */
    private void listenLoop() {
        try {
            String line;
            while (connected && (line = in.readLine()) != null) {
                parseServerMessage(line.trim());
            }
        } catch (IOException e) {
            if (connected) {
                System.err.println("[Client] Connection lost: " + e.getMessage());
                if (listener != null) listener.onError("Connection lost: " + e.getMessage());
            }
        } finally {
            connected = false;
        }
    }

    /**
     * Parse one server message and call the appropriate listener method.
     *
     * All known message formats:
     *   DRAW        x1 y1 x2 y2 #color size
     *   SHAPE       type sx sy ex ey #color size
     *   CLEAR
     *   ROOM_CREATED roomId roomCode
     *   ROOM_JOINED  roomId userCount
     *   USER_JOINED  username
     *   USER_LEFT    username
     *   ERROR        message...
     */
    private void parseServerMessage(String message) {
        if (message.isEmpty() || listener == null) return;

        String[] parts = message.split(" ");
        String cmd = parts[0].toUpperCase();

        switch (cmd) {

            case "DRAW" -> {
                // DRAW x1 y1 x2 y2 #RRGGBB brushSize
                if (parts.length >= 7) {
                    listener.onDrawSegment(
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3]),
                        Integer.parseInt(parts[4]),
                        parts[5],
                        Float.parseFloat(parts[6])
                    );
                }
            }

            case "SHAPE" -> {
                // SHAPE type startX startY endX endY #RRGGBB brushSize
                if (parts.length >= 8) {
                    listener.onShape(
                        parts[1],
                        Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3]),
                        Integer.parseInt(parts[4]),
                        Integer.parseInt(parts[5]),
                        parts[6],
                        Float.parseFloat(parts[7])
                    );
                }
            }

            case "CLEAR"        -> listener.onClear();

            case "ROOM_CREATED" -> {
                // ROOM_CREATED roomId roomCode
                if (parts.length >= 3) listener.onRoomCreated(parts[1], parts[2]);
            }

            case "ROOM_JOINED"  -> {
                // ROOM_JOINED roomId userCount
                if (parts.length >= 3) listener.onRoomJoined(parts[1], Integer.parseInt(parts[2]));
            }

            case "USER_JOINED"  -> {
                if (parts.length >= 2) listener.onUserJoined(parts[1]);
            }

            case "USER_LEFT"    -> {
                if (parts.length >= 2) listener.onUserLeft(parts[1]);
            }

            case "ERROR" -> {
                // ERROR rest of message...
                String errMsg = message.length() > 6 ? message.substring(6).trim() : "Unknown error";
                listener.onError(errMsg);
            }
        }
    }

    // =========================================================================
    // Outgoing messages (called from Swing EDT — safe because PrintWriter is thread-safe)
    // =========================================================================

    /** Request the server to create a new room for this user. */
    public void createRoom(String username) {
        send("CREATE_ROOM " + username);
    }

    /** Request to join an existing room. */
    public void joinRoom(String roomId, String roomCode, String username) {
        send("JOIN_ROOM " + roomId + " " + roomCode + " " + username);
    }

    /**
     * Send one freehand segment.
     * Called from CanvasPanel.mouseDragged for each pair of consecutive drag points.
     *
     * @param colorHex Hex string like "#FF0000"
     */
    public void sendDrawSegment(int x1, int y1, int x2, int y2, String colorHex, float brushSize) {
        send("DRAW " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + colorHex + " " + brushSize);
    }

    /**
     * Send a completed shape.
     * Called from CanvasPanel.mouseReleased when in shape mode.
     */
    public void sendShape(String type, int startX, int startY, int endX, int endY,
                          String colorHex, float brushSize) {
        send("SHAPE " + type + " " + startX + " " + startY + " "
                      + endX   + " " + endY   + " " + colorHex + " " + brushSize);
    }

    /** Broadcast a canvas clear to everyone in the room. */
    public void sendClear() {
        send("CLEAR");
    }

    /** Gracefully disconnect: notify server and close the socket. */
    public void disconnect() {
        if (!connected) return;
        connected = false;
        send("LEAVE_ROOM");
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }

    private void send(String message) {
        if (out != null && connected) {
            out.println(message);
        }
    }

    public boolean isConnected() { return connected; }

    // =========================================================================
    // Color conversion utilities (shared between client and caller)
    // =========================================================================

    /** java.awt.Color  →  "#RRGGBB" */
    public static String colorToHex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    /** "#RRGGBB"  →  java.awt.Color  (returns Color.BLACK on parse failure) */
    public static Color hexToColor(String hex) {
        try {
            return Color.decode(hex);
        } catch (NumberFormatException e) {
            return Color.BLACK;
        }
    }
}
