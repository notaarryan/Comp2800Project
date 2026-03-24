import java.io.*;
import java.net.*;
import java.util.*;

/**
 * ClientHandler — Manages communication with a single connected client.
 *
 * One instance is created (and one Thread is started) per accepted connection.
 * It reads text lines from the client socket, parses commands, and either
 * modifies server state (room create/join/leave) or forwards drawing events
 * to all other clients in the same room.
 *
 * ============================================================
 * TEXT PROTOCOL (newline-delimited UTF-8)
 * ============================================================
 *
 * Client → Server:
 *   CREATE_ROOM <username>
 *   JOIN_ROOM   <roomId> <roomCode> <username>
 *   LEAVE_ROOM
 *   DRAW        <x1> <y1> <x2> <y2> <#RRGGBB> <brushSize>
 *   SHAPE       <type> <startX> <startY> <endX> <endY> <#RRGGBB> <brushSize>
 *   CLEAR
 *
 * Server → Client:
 *   ROOM_CREATED  <roomId> <roomCode>
 *   ROOM_JOINED   <roomId> <userCount>
 *   USER_JOINED   <username>
 *   USER_LEFT     <username>
 *   DRAW          <x1> <y1> <x2> <y2> <#RRGGBB> <brushSize>   (forwarded)
 *   SHAPE         <type> <startX> <startY> <endX> <endY> <#RRGGBB> <brushSize>
 *   CLEAR
 *   ERROR         <message>
 * ============================================================
 */
public class ClientHandler implements Runnable {

    private final Socket socket;
    private final Map<String, Room> rooms;  // shared server state

    private PrintWriter    out;
    private BufferedReader in;

    private Room   currentRoom = null;
    private String username    = "Unknown";

    // Characters used to generate room IDs (uppercase alphanumeric, no ambiguous chars)
    private static final String ID_CHARS = "ABCDEFGHIJKLMNPQRSTUVWXYZ23456789";
    private static final Random RANDOM   = new Random();

    public ClientHandler(Socket socket, Map<String, Room> rooms) {
        this.socket = socket;
        this.rooms  = rooms;
    }

    // -------------------------------------------------------------------------
    // Main thread loop
    // -------------------------------------------------------------------------

    @Override
    public void run() {
        try {
            // Auto-flush = true ensures each println() is sent immediately
            out = new PrintWriter(
                    new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())), true);
            in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            String line;
            while ((line = in.readLine()) != null) {
                processMessage(line.trim());
            }
        } catch (IOException e) {
            System.out.println("[Server] Connection lost for client: " + username);
        } finally {
            // Always clean up the room on disconnect (network drop or normal leave)
            handleLeave();
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Command dispatch
    // -------------------------------------------------------------------------

    /**
     * Parse one incoming line and dispatch to the appropriate handler.
     */
    private void processMessage(String message) {
        if (message.isEmpty()) return;

        // Split command from payload: "JOIN_ROOM A7K2 123456 Alice"
        //   → command = "JOIN_ROOM", payload = "A7K2 123456 Alice"
        int spaceIdx = message.indexOf(' ');
        String command = (spaceIdx == -1 ? message : message.substring(0, spaceIdx)).toUpperCase();
        String payload = (spaceIdx == -1 ? "" : message.substring(spaceIdx + 1).trim());

        switch (command) {
            case "CREATE_ROOM" -> handleCreateRoom(payload);
            case "JOIN_ROOM"   -> handleJoinRoom(payload);
            case "LEAVE_ROOM"  -> handleLeave();
            // Drawing events: forward to everyone else in the room unchanged
            case "DRAW"        -> forwardToRoom("DRAW "  + payload);
            case "SHAPE"       -> forwardToRoom("SHAPE " + payload);
            case "CLEAR"       -> forwardToRoom("CLEAR");
            default            -> sendMessage("ERROR Unknown command: " + command);
        }
    }

    // -------------------------------------------------------------------------
    // Room management
    // -------------------------------------------------------------------------

    /**
     * CREATE_ROOM <username>
     *
     * 1. Generate a unique 4-char roomId (e.g. "A7K2")
     * 2. Generate a 6-digit roomCode (e.g. "583219")
     * 3. Register the room and add this client as the first member
     * 4. Reply: ROOM_CREATED <roomId> <roomCode>
     */
    private void handleCreateRoom(String payload) {
        username = payload.isBlank() ? "User" : payload.trim();

        // Generate a room ID that is not already in use
        String roomId;
        do {
            roomId = generateId(4);
        } while (rooms.containsKey(roomId));

        String roomCode = String.format("%06d", RANDOM.nextInt(1_000_000));

        Room room = new Room(roomId, roomCode);
        room.addClient(this);
        rooms.put(roomId, room);
        currentRoom = room;

        System.out.printf("[Server] Room created: %s  code: %s  by: %s%n",
                roomId, roomCode, username);
        sendMessage("ROOM_CREATED " + roomId + " " + roomCode);
    }

    /**
     * JOIN_ROOM <roomId> <roomCode> <username>
     *
     * Validates roomId and roomCode, then adds this client to the room.
     * Notifies existing members via USER_JOINED broadcast.
     */
    private void handleJoinRoom(String payload) {
        // Expect exactly 3 space-separated tokens
        String[] parts = payload.split(" ", 3);
        if (parts.length < 3) {
            sendMessage("ERROR Bad JOIN_ROOM syntax. Expected: <roomId> <roomCode> <username>");
            return;
        }

        String roomId   = parts[0].toUpperCase();
        String roomCode = parts[1];
        username        = parts[2].trim();

        // --- Validate ---
        Room room = rooms.get(roomId);
        if (room == null) {
            sendMessage("ERROR Room not found: " + roomId);
            return;
        }
        if (!room.roomCode.equals(roomCode)) {
            sendMessage("ERROR Incorrect room code");
            return;
        }

        // Leave any previous room first
        if (currentRoom != null) handleLeave();

        // --- Join ---
        room.addClient(this);
        currentRoom = room;

        System.out.printf("[Server] %s joined room %s (%d users now)%n",
                username, roomId, room.getClientCount());

        // Confirm to the joining client
        sendMessage("ROOM_JOINED " + roomId + " " + room.getClientCount());
        // Notify existing members (exclude the client who just joined)
        room.broadcast("USER_JOINED " + username, this);
    }

    /**
     * LEAVE_ROOM (or called automatically on socket close).
     *
     * Removes this client from the room, notifies remaining members,
     * and deletes the room if it is now empty.
     */
    private void handleLeave() {
        if (currentRoom == null) return;

        currentRoom.removeClient(this);
        currentRoom.broadcast("USER_LEFT " + username, this);
        System.out.printf("[Server] %s left room %s%n", username, currentRoom.roomId);

        // Clean up empty rooms so memory doesn't grow indefinitely
        if (currentRoom.isEmpty()) {
            rooms.remove(currentRoom.roomId);
            System.out.printf("[Server] Room %s deleted (empty)%n", currentRoom.roomId);
        }

        currentRoom = null;
    }

    // -------------------------------------------------------------------------
    // Event forwarding
    // -------------------------------------------------------------------------

    /**
     * Forward a drawing event to every other client in the same room.
     * The sender already applied the change locally, so they are excluded.
     */
    private void forwardToRoom(String message) {
        if (currentRoom == null) {
            sendMessage("ERROR Not in a room");
            return;
        }
        currentRoom.broadcast(message, this);
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /** Write a newline-terminated message to this client's socket. */
    public void sendMessage(String message) {
        if (out != null) {
            out.println(message);
        }
    }

    /** Generate a random uppercase alphanumeric ID of the given length. */
    private String generateId(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ID_CHARS.charAt(RANDOM.nextInt(ID_CHARS.length())));
        }
        return sb.toString();
    }
}
