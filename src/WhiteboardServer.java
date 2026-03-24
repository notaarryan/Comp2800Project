import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WhiteboardServer — Standalone collaborative whiteboard backend.
 *
 * Run with:  java WhiteboardServer [port]
 * Default port: 5000
 *
 * Deployment: This class has no GUI or desktop dependencies.
 * It can be compiled and run on any cloud VM (Azure, AWS EC2, Render, Railway, etc.)
 * as a headless background process:
 *
 *   javac WhiteboardServer.java Room.java ClientHandler.java
 *   java WhiteboardServer 5000
 *
 * Architecture:
 *   - Listens for incoming TCP connections on the configured port.
 *   - Each connection spawns a ClientHandler thread.
 *   - Rooms are stored in a thread-safe ConcurrentHashMap keyed by roomId.
 *   - All drawing events are forwarded (broadcast) to every other client in the same room.
 */
public class WhiteboardServer {

    /** Default port. Override by passing a port argument on the command line. */
    public static final int DEFAULT_PORT = 8000;

    // Thread-safe map:  roomId (e.g. "A7K2")  →  Room
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final int port;

    public WhiteboardServer(int port) {
        this.port = port;
    }

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("[Server] Invalid port argument '" + args[0]
                        + "', using default: " + DEFAULT_PORT);
            }
        }
        new WhiteboardServer(port).start();
    }

    // -------------------------------------------------------------------------
    // Server loop
    // -------------------------------------------------------------------------

    /**
     * Starts listening for client connections.
     * Blocks indefinitely — each accepted connection is handed off to a
     * daemon ClientHandler thread so this loop never stalls.
     */
    public void start() {
        System.out.println("[Server] Starting WhiteboardServer on port " + port);
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            serverSocket.setReuseAddress(true);
            System.out.println("[Server] Ready. Waiting for connections...");

            while (true) {
                // Accept blocks until a client connects
                Socket clientSocket = serverSocket.accept();
                String clientAddr = clientSocket.getInetAddress().getHostAddress();
                System.out.println("[Server] New connection from " + clientAddr);

                // Each client gets its own handler thread
                ClientHandler handler = new ClientHandler(clientSocket, rooms);
                Thread t = new Thread(handler, "ClientHandler-" + clientSocket.getPort());
                t.setDaemon(true); // won't prevent JVM shutdown
                t.start();
            }
        } catch (IOException e) {
            System.err.println("[Server] Fatal error: " + e.getMessage());
        }
    }

    /** Expose room map for diagnostics / testing. */
    public Map<String, Room> getRooms() {
        return Collections.unmodifiableMap(rooms);
    }
}
