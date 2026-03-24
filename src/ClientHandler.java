import java.io.*;
import java.net.*;
import java.util.*;

// Handles one connected client in its own thread.
// Protocol (newline-delimited text):
//   Client→Server: CREATE_ROOM <user> | JOIN_ROOM <id> <code> <user> | LEAVE_ROOM
//                  DRAW <x1> <y1> <x2> <y2> <#color> <size> | SHAPE <type> <sx> <sy> <ex> <ey> <#color> <size>
//                  CLEAR | BEGIN_SYNC | STROKE_LINE <#color> <size> <n> <x y>... | END_SYNC
//   Server→Client: ROOM_CREATED <id> <code> | ROOM_JOINED <id> <count>
//                  USER_JOINED <user> | USER_LEFT <user> | ERROR <msg>
//                  + all draw/sync commands forwarded from other clients
public class ClientHandler implements Runnable {

    private final Socket socket;
    private final Map<String, Room> rooms;

    private PrintWriter out;
    private BufferedReader in;

    private Room currentRoom = null;
    private String username = "Unknown";

    private static final String ID_CHARS = "ABCDEFGHIJKLMNPQRSTUVWXYZ23456789";
    private static final Random RANDOM = new Random();

    public ClientHandler(Socket socket, Map<String, Room> rooms) {
        this.socket = socket;
        this.rooms  = rooms;
    }

    @Override
    public void run() {
        try {
            out = new PrintWriter(new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream())), true);
            in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String line;
            while ((line = in.readLine()) != null) {
                processMessage(line.trim());
            }
        } catch (IOException e) {
            System.out.println("[Server] Connection lost: " + username);
        } finally {
            handleLeave();
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void processMessage(String message) {
        if (message.isEmpty()) return;
        int spaceIdx = message.indexOf(' ');
        String command = (spaceIdx == -1 ? message : message.substring(0, spaceIdx)).toUpperCase();
        String payload = (spaceIdx == -1 ? "" : message.substring(spaceIdx + 1).trim());

        switch (command) {
            case "CREATE_ROOM"  -> handleCreateRoom(payload);
            case "JOIN_ROOM"    -> handleJoinRoom(payload);
            case "LEAVE_ROOM"   -> handleLeave();
            case "DRAW"         -> forwardToRoom("DRAW "        + payload);
            case "SHAPE"        -> forwardToRoom("SHAPE "       + payload);
            case "CLEAR"        -> forwardToRoom("CLEAR");
            case "BEGIN_SYNC"   -> forwardToRoom("BEGIN_SYNC");
            case "STROKE_LINE"  -> forwardToRoom("STROKE_LINE " + payload);
            case "END_SYNC"     -> forwardToRoom("END_SYNC");
            default             -> sendMessage("ERROR Unknown command: " + command);
        }
    }

    private void handleCreateRoom(String payload) {
        username = payload.isBlank() ? "User" : payload.trim();

        String roomId;
        do { roomId = generateId(4); } while (rooms.containsKey(roomId));
        String roomCode = String.format("%06d", RANDOM.nextInt(1_000_000));

        Room room = new Room(roomId, roomCode);
        room.addClient(this);
        rooms.put(roomId, room);
        currentRoom = room;

        System.out.printf("[Server] Room created: %s  code: %s  by: %s%n", roomId, roomCode, username);
        sendMessage("ROOM_CREATED " + roomId + " " + roomCode);
    }

    private void handleJoinRoom(String payload) {
        String[] parts = payload.split(" ", 3);
        if (parts.length < 3) {
            sendMessage("ERROR Bad JOIN_ROOM syntax. Expected: <roomId> <roomCode> <username>");
            return;
        }
        String roomId   = parts[0].toUpperCase();
        String roomCode = parts[1];
        username        = parts[2].trim();

        Room room = rooms.get(roomId);
        if (room == null) { sendMessage("ERROR Room not found: " + roomId); return; }
        if (!room.roomCode.equals(roomCode)) { sendMessage("ERROR Incorrect room code"); return; }

        if (currentRoom != null) handleLeave();

        room.addClient(this);
        currentRoom = room;

        System.out.printf("[Server] %s joined room %s (%d users)%n", username, roomId, room.getClientCount());
        sendMessage("ROOM_JOINED " + roomId + " " + room.getClientCount());
        room.broadcast("USER_JOINED " + username, this);
    }

    private void handleLeave() {
        if (currentRoom == null) return;
        currentRoom.removeClient(this);
        currentRoom.broadcast("USER_LEFT " + username, this);
        System.out.printf("[Server] %s left room %s%n", username, currentRoom.roomId);
        if (currentRoom.isEmpty()) {
            rooms.remove(currentRoom.roomId);
            System.out.printf("[Server] Room %s deleted (empty)%n", currentRoom.roomId);
        }
        currentRoom = null;
    }

    private void forwardToRoom(String message) {
        if (currentRoom == null) { sendMessage("ERROR Not in a room"); return; }
        currentRoom.broadcast(message, this);
    }

    public void sendMessage(String message) {
        if (out != null) out.println(message);
    }

    private String generateId(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++)
            sb.append(ID_CHARS.charAt(RANDOM.nextInt(ID_CHARS.length())));
        return sb.toString();
    }
}
