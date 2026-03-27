import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

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

    public ClientHandler getFirstClient(ClientHandler exclude) {
        for (ClientHandler c : clients) {
            if (c != exclude) return c;
        }
        return null;
    }

    public void broadcast(String message, ClientHandler sender) {
        for (ClientHandler client : clients) {
            if (client != sender) client.sendMessage(message);
        }
    }

    public void broadcastAll(String message) {
        for (ClientHandler client : clients) client.sendMessage(message);
    }
}
