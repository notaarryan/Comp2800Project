import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class WhiteboardServer {

    public static final int DEFAULT_PORT = 8000;

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final int port;

    public WhiteboardServer(int port) {
        this.port = port;
    }

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("[Server] Invalid port, using default: " + DEFAULT_PORT);
            }
        }
        new WhiteboardServer(port).start();
    }

    public void start() {
        System.out.println("[Server] Starting on port " + port);
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            serverSocket.setReuseAddress(true);
            System.out.println("[Server] Ready. Waiting for connections...");
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("[Server] New connection from "
                        + clientSocket.getInetAddress().getHostAddress());
                ClientHandler handler = new ClientHandler(clientSocket, rooms);
                Thread t = new Thread(handler, "ClientHandler-" + clientSocket.getPort());
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            System.err.println("[Server] Fatal error: " + e.getMessage());
        }
    }

    public Map<String, Room> getRooms() {
        return Collections.unmodifiableMap(rooms);
    }
}
