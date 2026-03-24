import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

// One collaborative whiteboard session on the server.
// CopyOnWriteArrayList keeps the client list thread-safe during broadcast.
public class Room {

    final String roomId;
    final String roomCode;

    private final List<ClientHandler> clients = new CopyOnWriteArrayList<>();

    public Room(String roomId, String roomCode) {
        this.roomId   = roomId;
        this.roomCode = roomCode;
    }

    public void addClient(ClientHandler client)    { clients.add(client); }
    public void removeClient(ClientHandler client) { clients.remove(client); }
    public int  getClientCount()                   { return clients.size(); }
    public boolean isEmpty()                       { return clients.isEmpty(); }

    // Send to everyone except the sender (used for draw events)
    public void broadcast(String message, ClientHandler sender) {
        for (ClientHandler client : clients) {
            if (client != sender) client.sendMessage(message);
        }
    }

    // Send to everyone including the sender (used for system notifications)
    public void broadcastAll(String message) {
        for (ClientHandler client : clients) client.sendMessage(message);
    }
}
