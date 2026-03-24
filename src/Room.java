import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Room — Represents one collaborative whiteboard session on the server.
 *
 * Thread-safety:
 *   CopyOnWriteArrayList allows multiple ClientHandler threads to iterate
 *   the client list (during broadcast) while other threads add/remove clients,
 *   without requiring explicit synchronization.
 *
 * Lifecycle:
 *   - Created by ClientHandler when a user sends CREATE_ROOM.
 *   - Destroyed (removed from server map) automatically when the last client leaves.
 */
public class Room {

    /** Short alphanumeric identifier shared with joiners (e.g. "A7K2"). */
    final String roomId;

    /** 6-digit numeric password that joiners must provide (e.g. "583219"). */
    final String roomCode;

    // Thread-safe: safe to iterate while other threads mutate
    private final List<ClientHandler> clients = new CopyOnWriteArrayList<>();

    public Room(String roomId, String roomCode) {
        this.roomId   = roomId;
        this.roomCode = roomCode;
    }

    // -------------------------------------------------------------------------
    // Client management
    // -------------------------------------------------------------------------

    public void addClient(ClientHandler client) {
        clients.add(client);
    }

    public void removeClient(ClientHandler client) {
        clients.remove(client);
    }

    public int getClientCount() {
        return clients.size();
    }

    public boolean isEmpty() {
        return clients.isEmpty();
    }

    // -------------------------------------------------------------------------
    // Broadcasting
    // -------------------------------------------------------------------------

    /**
     * Send a message to every client in this room EXCEPT the sender.
     *
     * Used for drawing events (DRAW, SHAPE, CLEAR): the sender already
     * applied the change locally, so we skip them to avoid duplication.
     *
     * @param message  Newline-terminated protocol string
     * @param sender   The originating ClientHandler (excluded from delivery)
     */
    public void broadcast(String message, ClientHandler sender) {
        for (ClientHandler client : clients) {
            if (client != sender) {
                client.sendMessage(message);
            }
        }
    }

    /**
     * Send a message to ALL clients in this room including the sender.
     *
     * Used for system notifications (USER_JOINED, USER_LEFT).
     */
    public void broadcastAll(String message) {
        for (ClientHandler client : clients) {
            client.sendMessage(message);
        }
    }
}
