import java.awt.Color;
import java.awt.Point;
import java.io.*;
import java.net.*;
import java.util.ArrayList;

// Client-side networking layer. Manages one TCP connection to WhiteboardServer.
// A daemon thread reads incoming messages and dispatches them to MessageListener.
// All listener callbacks arrive on the network thread — use SwingUtilities.invokeLater() for UI updates.
public class WhiteboardClient {

    public interface MessageListener {
        void onDrawSegment(int x1, int y1, int x2, int y2, String colorHex, float brushSize);
        void onShape(String type, int startX, int startY, int endX, int endY, String colorHex, float brushSize);
        void onClear();
        void onRoomCreated(String roomId, String roomCode);
        void onRoomJoined(String roomId, int userCount);
        void onUserJoined(String username);
        void onUserLeft(String username);
        void onError(String message);
        void onEndSync(ArrayList<CanvasPanel.Stroke> strokes, ArrayList<CanvasPanel.ShapeItem> shapes);
    }

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private final MessageListener listener;
    private volatile boolean connected = false;

    // Accumulates state during a BEGIN_SYNC … END_SYNC block
    private boolean syncing = false;
    private final ArrayList<CanvasPanel.Stroke> syncStrokes = new ArrayList<>();
    private final ArrayList<CanvasPanel.ShapeItem> syncShapes = new ArrayList<>();

    public WhiteboardClient(MessageListener listener) {
        this.listener = listener;
    }

    public boolean connect(String host, int port) {
        try {
            socket = new Socket(host, port);
            out = new PrintWriter(new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream())), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            connected = true;
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

    private void parseServerMessage(String message) {
        if (message.isEmpty() || listener == null) return;
        String[] parts = message.split(" ");
        String cmd = parts[0].toUpperCase();

        switch (cmd) {
            case "DRAW" -> {
                if (parts.length >= 7)
                    listener.onDrawSegment(
                        Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3]), Integer.parseInt(parts[4]),
                        parts[5], Float.parseFloat(parts[6]));
            }
            case "SHAPE" -> {
                if (parts.length >= 8) {
                    if (syncing) {
                        // during sync: accumulate instead of applying immediately
                        syncShapes.add(new CanvasPanel.ShapeItem(
                            parts[1],
                            new Point(Integer.parseInt(parts[2]), Integer.parseInt(parts[3])),
                            new Point(Integer.parseInt(parts[4]), Integer.parseInt(parts[5])),
                            hexToColor(parts[6]), Float.parseFloat(parts[7])
                        ));
                    } else {
                        listener.onShape(parts[1],
                            Integer.parseInt(parts[2]), Integer.parseInt(parts[3]),
                            Integer.parseInt(parts[4]), Integer.parseInt(parts[5]),
                            parts[6], Float.parseFloat(parts[7]));
                    }
                }
            }
            case "CLEAR"        -> listener.onClear();
            case "ROOM_CREATED" -> { if (parts.length >= 3) listener.onRoomCreated(parts[1], parts[2]); }
            case "ROOM_JOINED"  -> { if (parts.length >= 3) listener.onRoomJoined(parts[1], Integer.parseInt(parts[2])); }
            case "USER_JOINED"  -> { if (parts.length >= 2) listener.onUserJoined(parts[1]); }
            case "USER_LEFT"    -> { if (parts.length >= 2) listener.onUserLeft(parts[1]); }
            case "ERROR"        -> listener.onError(message.length() > 6 ? message.substring(6).trim() : "Unknown error");
            case "BEGIN_SYNC"   -> { syncing = true;  syncStrokes.clear(); syncShapes.clear(); }
            case "STROKE_LINE"  -> {
                if (syncing && parts.length >= 4) {
                    CanvasPanel.Stroke stroke = new CanvasPanel.Stroke(
                            hexToColor(parts[1]), Float.parseFloat(parts[2]));
                    int n = Integer.parseInt(parts[3]);
                    for (int i = 0; i < n; i++) {
                        int base = 4 + i * 2;
                        if (base + 1 < parts.length)
                            stroke.points.add(new Point(
                                Integer.parseInt(parts[base]), Integer.parseInt(parts[base + 1])));
                    }
                    syncStrokes.add(stroke);
                }
            }
            case "END_SYNC" -> {
                if (syncing) listener.onEndSync(new ArrayList<>(syncStrokes), new ArrayList<>(syncShapes));
                syncing = false;
            }
        }
    }

    public void createRoom(String username)                          { send("CREATE_ROOM " + username); }
    public void joinRoom(String roomId, String roomCode, String username) { send("JOIN_ROOM " + roomId + " " + roomCode + " " + username); }
    public void sendDrawSegment(int x1, int y1, int x2, int y2, String colorHex, float brushSize) {
        send("DRAW " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + colorHex + " " + brushSize);
    }
    public void sendShape(String type, int sx, int sy, int ex, int ey, String colorHex, float brushSize) {
        send("SHAPE " + type + " " + sx + " " + sy + " " + ex + " " + ey + " " + colorHex + " " + brushSize);
    }
    public void sendClear() { send("CLEAR"); }

    // Sends full canvas state — called after undo/redo so all peers stay in sync
    public void sendFullSync(ArrayList<CanvasPanel.Stroke> strokes, ArrayList<CanvasPanel.ShapeItem> shapes) {
        send("BEGIN_SYNC");
        for (CanvasPanel.Stroke stroke : strokes) {
            StringBuilder sb = new StringBuilder("STROKE_LINE ");
            sb.append(colorToHex(stroke.color)).append(' ')
              .append(stroke.width).append(' ')
              .append(stroke.points.size());
            for (java.awt.Point p : stroke.points)
                sb.append(' ').append(p.x).append(' ').append(p.y);
            send(sb.toString());
        }
        for (CanvasPanel.ShapeItem s : shapes)
            send("SHAPE " + s.type + " " + s.start.x + " " + s.start.y + " "
                          + s.end.x + " " + s.end.y + " " + colorToHex(s.color) + " " + s.width);
        send("END_SYNC");
    }

    public void disconnect() {
        if (!connected) return;
        connected = false;
        send("LEAVE_ROOM");
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }

    private void send(String message) {
        if (out != null && connected) out.println(message);
    }

    public boolean isConnected() { return connected; }

    public static String colorToHex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    public static Color hexToColor(String hex) {
        try { return Color.decode(hex); } catch (NumberFormatException e) { return Color.BLACK; }
    }
}
