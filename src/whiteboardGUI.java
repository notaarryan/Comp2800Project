/*******
Marko Simic
Whiteboard GUI Application
3/10/25
Updated: collaborative room support
*******************/
import java.awt.*;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.border.*;

public class whiteboardGUI {

    private WhiteboardClient client;
    private boolean inRoom = false;
    private JLabel roomInfoLabel;
    private String serverIp;
    private int serverPort = WhiteboardServer.DEFAULT_PORT;
    private String currentRoomId = null;
    private String currentRoomCode = null;
    private JFrame frame;

    public static void main(String[] args) {
        // Start the embedded server in the background; if port is already taken, it exits quietly
        try {
            UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> new whiteboardGUI().buildAndShowGUI());
    }

    private void buildAndShowGUI() {
        frame = new JFrame("Whiteboard");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1100, 750);
        frame.setMinimumSize(new Dimension(700, 500));
        frame.setLocationRelativeTo(null);

        // Disconnect cleanly when the window is closed
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                if (client != null && client.isConnected()) client.disconnect();
            }
        });

        // --- Header bar ---
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(new Color(30, 30, 40));
        header.setBorder(new EmptyBorder(12, 20, 12, 20));

        JLabel title = new JLabel("Whiteboard");
        title.setForeground(Color.WHITE);
        title.setFont(new Font("SansSerif", Font.BOLD, 20));
        header.add(title, BorderLayout.WEST);

        // Room status label — shown on the right side of the header
        roomInfoLabel = new JLabel("No Room");
        roomInfoLabel.setForeground(new Color(140, 190, 255));
        roomInfoLabel.setFont(new Font("SansSerif", Font.PLAIN, 13));
        header.add(roomInfoLabel, BorderLayout.EAST);

        // --- Canvas ---
        CanvasPanel canvas = new CanvasPanel();

        // All listener callbacks arrive on the network thread — invokeLater() marshals to EDT
        WhiteboardClient.MessageListener networkListener = new WhiteboardClient.MessageListener() {

            @Override
            public void onDrawSegment(int x1, int y1, int x2, int y2, String colorHex, float brushSize) {
                SwingUtilities.invokeLater(() ->
                    canvas.applyRemoteSegment(x1, y1, x2, y2,
                        WhiteboardClient.hexToColor(colorHex), brushSize));
            }

            @Override
            public void onShape(String type, int startX, int startY,
                                int endX, int endY, String colorHex, float brushSize) {
                SwingUtilities.invokeLater(() ->
                    canvas.applyRemoteShape(type, startX, startY, endX, endY,
                        WhiteboardClient.hexToColor(colorHex), brushSize));
            }

            @Override
            public void onClear() {
                SwingUtilities.invokeLater(canvas::applyRemoteClear);
            }

            @Override
            public void onRoomCreated(String roomId, String roomCode) {
                SwingUtilities.invokeLater(() -> {
                    inRoom = true;
                    currentRoomId   = roomId;
                    currentRoomCode = roomCode;
                    canvas.setNetworkClient(client);
                    updateRoomLabel();
                    JOptionPane.showMessageDialog(frame,
                        "Room created!\n\n"
                        + "Room ID:   " + roomId + "\n"
                        + "Room Code: " + roomCode + "\n"
                        + "IP:        " + serverIp + "\n"
                        + "Port:      " + serverPort + "\n\n"
                        + "Share these details with collaborators.",
                        "Room Created", JOptionPane.INFORMATION_MESSAGE);
                });
            }

            @Override
            public void onRoomJoined(String roomId, int userCount) {
                SwingUtilities.invokeLater(() -> {
                    inRoom = true;
                    currentRoomId = roomId;
                    canvas.setNetworkClient(client);
                    updateRoomLabel();
                });
            }

            @Override
            public void onUserJoined(String username) {
                SwingUtilities.invokeLater(() -> {
                    roomInfoLabel.setText(roomLabel() + "  [+" + username + " joined]");
                    new javax.swing.Timer(3000, ev -> updateRoomLabel()) {{ setRepeats(false); start(); }};
                });
            }

            @Override
            public void onUserLeft(String username) {
                SwingUtilities.invokeLater(() -> {
                    canvas.removeRemoteCursor(username);
                    roomInfoLabel.setText(roomLabel() + "  [-" + username + " left]");
                    new javax.swing.Timer(3000, ev -> updateRoomLabel()) {{ setRepeats(false); start(); }};
                });
            }

            @Override
            public void onEndSync(ArrayList<CanvasPanel.Stroke> strokes,
                                  ArrayList<CanvasPanel.ShapeItem> shapes) {
                SwingUtilities.invokeLater(() -> canvas.applyFullSync(strokes, shapes));
            }

            @Override
            public void onCursor(String username, int x, int y) {
                SwingUtilities.invokeLater(() -> canvas.applyRemoteCursor(username, x, y));
            }

            @Override
            public void onSyncRequested() {
                if (client != null)
                    client.sendFullSync(canvas.getStrokes(), canvas.getShapes());
            }

            @Override
            public void onError(String message) {
                SwingUtilities.invokeLater(() -> {
                    if (!inRoom) {
                        if (client != null) { client.disconnect(); client = null; }
                        currentRoomId   = null;
                        currentRoomCode = null;
                        serverIp        = null;
                        canvas.setNetworkClient(null);
                        canvas.setLocalUsername(null);
                        canvas.clearRemoteCursors();
                        roomInfoLabel.setText("No Room");
                    }
                    JOptionPane.showMessageDialog(frame, "Server error:\n" + message,
                        "Error", JOptionPane.ERROR_MESSAGE);
                });
            }
        };

        // --- Toolbar ---
        JPanel toolbar = new JPanel();
        toolbar.setBackground(new Color(45, 45, 58));
        toolbar.setBorder(new EmptyBorder(10, 16, 10, 16));
        toolbar.setLayout(new FlowLayout(FlowLayout.LEFT, 10, 0));

        // --- Eraser toggle ---
        JToggleButton eraserBtn = createToolButton("Eraser");
        eraserBtn.addActionListener(e -> canvas.setEraserMode(eraserBtn.isSelected()));
        toolbar.add(eraserBtn);

        // --- Color picker button ---
        JButton colorBtn = createToolButton("Color");

        JPanel colorPreview = new JPanel();
        colorPreview.setPreferredSize(new Dimension(20, 20));
        colorPreview.setBackground(Color.BLACK);

        colorBtn.addActionListener(e -> {
            Color selectedColor = JColorChooser.showDialog(null, "Choose Brush Color", colorPreview.getBackground());
            if (selectedColor != null) {
                canvas.setCurrentColor(selectedColor);
                colorPreview.setBackground(selectedColor);
            }
        });

        toolbar.add(colorBtn);
        toolbar.add(colorPreview);

        // --- Brush size slider ---
        toolbar.add(Box.createHorizontalStrut(10));

        JLabel brushLabel = new JLabel("Brush:");
        brushLabel.setForeground(new Color(210, 210, 220));
        toolbar.add(brushLabel);

        JSlider brushSlider = new JSlider(1, 30, 6);
        brushSlider.setPreferredSize(new Dimension(120, 40));
        brushSlider.setBackground(new Color(45, 45, 58));

        var brushPreview = new JPanel() {
            private int size = 6;
            public void setSizeValue(int s) { size = s; repaint(); }
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                g.setColor(Color.BLACK);
                int x = (getWidth() - size) / 2;
                int y = (getHeight() - size) / 2;
                g.fillOval(x, y, size, size);
            }
        };

        brushPreview.setPreferredSize(new Dimension(40, 40));
        brushPreview.setBackground(new Color(45, 45, 58));

        brushSlider.addChangeListener(e -> {
            int size = brushSlider.getValue();
            canvas.setBrushSize(size);
            brushPreview.setSizeValue(size);
        });

        toolbar.add(brushSlider);
        toolbar.add(brushPreview);

        // --- Shape / mode selector ---
        toolbar.add(Box.createHorizontalStrut(10));

        String[] shapes = {"Free Draw", "Select", "Text", "Rectangle", "Square", "Circle", "Triangle", "Diamond", "Star"};
        JComboBox<String> shapeBox = new JComboBox<>(shapes);
        shapeBox.setBackground(new Color(65, 65, 80));
        shapeBox.setForeground(new Color(210, 210, 220));

        shapeBox.addActionListener(e -> {
            String selected = (String) shapeBox.getSelectedItem();
            canvas.setShapeMode(selected);
            eraserBtn.setSelected(false);
            canvas.setEraserMode(false);
        });

        toolbar.add(shapeBox);

        // --- Undo button ---
        JButton undoBtn = createToolButton("Undo");
        undoBtn.addActionListener(e -> canvas.undo());
        toolbar.add(undoBtn);

        // --- Redo button ---
        JButton redoBtn = createToolButton("Redo");
        redoBtn.addActionListener(e -> canvas.redo());
        toolbar.add(redoBtn);

        // --- Clear button ---
        JButton clearBtn = createToolButton("Clear");
        clearBtn.addActionListener(e -> canvas.clearCanvas());
        toolbar.add(clearBtn);

        // --- DB separator ---
        JSeparator sep2 = new JSeparator(JSeparator.VERTICAL);
        sep2.setPreferredSize(new Dimension(1, 26));
        sep2.setForeground(new Color(80, 80, 95));
        toolbar.add(Box.createHorizontalStrut(4));
        toolbar.add(sep2);
        toolbar.add(Box.createHorizontalStrut(4));

        // --- Save button ---
        JButton saveBtn = createToolButton("Save");
        saveBtn.addActionListener(e -> handleSave(canvas, frame));
        toolbar.add(saveBtn);

        // --- Load button ---
        JButton loadBtn = createToolButton("Load");
        loadBtn.addActionListener(e -> handleLoad(canvas, frame));
        toolbar.add(loadBtn);

        // --- Delete button ---
        JButton deleteBtn = createToolButton("Delete");
        deleteBtn.addActionListener(e -> handleDelete(frame));
        toolbar.add(deleteBtn);

        // Collab buttons in a second row so they don't overflow the main toolbar
        JPanel collabBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        collabBar.setBackground(new Color(36, 36, 48));
        collabBar.setBorder(new EmptyBorder(0, 16, 0, 16));

        JLabel collabLabel = new JLabel("Collab:");
        collabLabel.setForeground(new Color(140, 190, 255));
        collabLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        collabBar.add(collabLabel);

        // --- Create Room ---
        JButton createRoomBtn = createToolButton("Create Room");
        createRoomBtn.addActionListener(e -> {
            if (inRoom) {
                JOptionPane.showMessageDialog(frame,
                    "Already in a room. Click 'Leave Room' first.",
                    "Info", JOptionPane.INFORMATION_MESSAGE);
                return;
            }

            JPanel inputPanel = new JPanel(new GridLayout(3, 2, 6, 6));
            JTextField ipField   = new JTextField(getLocalIP());
            JTextField portField = new JTextField(String.valueOf(WhiteboardServer.DEFAULT_PORT));
            JTextField nameField = new JTextField();
            inputPanel.add(new JLabel("Server IP:"));   inputPanel.add(ipField);
            inputPanel.add(new JLabel("Port:"));        inputPanel.add(portField);
            inputPanel.add(new JLabel("Your Name:"));   inputPanel.add(nameField);

            int res = JOptionPane.showConfirmDialog(frame, inputPanel,
                "Create Room", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (res != JOptionPane.OK_OPTION) return;

            String ip       = ipField.getText().trim();
            String username = nameField.getText().trim();
            int parsedPort;
            try { parsedPort = Integer.parseInt(portField.getText().trim()); }
            catch (NumberFormatException ex) { parsedPort = WhiteboardServer.DEFAULT_PORT; }
            final int port = parsedPort;

            if (ip.isEmpty() || username.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "Please fill in all fields.",
                    "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            // Server always binds on DEFAULT_PORT locally; the dialog's IP/port is the ngrok/public address clients connect through
            Thread st = new Thread(() -> new WhiteboardServer(WhiteboardServer.DEFAULT_PORT).start(), "EmbeddedServer");
            st.setDaemon(true);
            st.start();
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}

            serverIp = ip;
            client   = new WhiteboardClient(networkListener);
            if (!client.connect(ip, port)) {
                JOptionPane.showMessageDialog(frame,
                    "Could not connect to server at " + ip + ":" + port
                    + "\n\nMake sure WhiteboardServer is running on that machine.",
                    "Connection Failed", JOptionPane.ERROR_MESSAGE);
                client = null;
                return;
            }
            serverPort = port;
            canvas.setLocalUsername(username);
            client.createRoom(username);
        });
        collabBar.add(createRoomBtn);

        // --- Join Room ---
        JButton joinRoomBtn = createToolButton("Join Room");
        joinRoomBtn.addActionListener(e -> {
            if (inRoom) {
                JOptionPane.showMessageDialog(frame,
                    "Already in a room. Click 'Leave Room' first.",
                    "Info", JOptionPane.INFORMATION_MESSAGE);
                return;
            }

            // Prompt for all join credentials
            JPanel inputPanel = new JPanel(new GridLayout(5, 2, 6, 6));
            JTextField ipField       = new JTextField(getLocalIP());
            JTextField portField     = new JTextField(String.valueOf(WhiteboardServer.DEFAULT_PORT));
            JTextField roomIdField   = new JTextField();
            JTextField roomCodeField = new JTextField();
            JTextField nameField     = new JTextField();
            inputPanel.add(new JLabel("Server IP:"));   inputPanel.add(ipField);
            inputPanel.add(new JLabel("Port:"));        inputPanel.add(portField);
            inputPanel.add(new JLabel("Room ID:"));     inputPanel.add(roomIdField);
            inputPanel.add(new JLabel("Room Code:"));   inputPanel.add(roomCodeField);
            inputPanel.add(new JLabel("Your Name:"));   inputPanel.add(nameField);

            int res = JOptionPane.showConfirmDialog(frame, inputPanel,
                "Join Room", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (res != JOptionPane.OK_OPTION) return;

            String ip       = ipField.getText().trim();
            String roomId   = roomIdField.getText().trim().toUpperCase();
            String roomCode = roomCodeField.getText().trim();
            String username = nameField.getText().trim();
            int port;
            try { port = Integer.parseInt(portField.getText().trim()); }
            catch (NumberFormatException ex) { port = WhiteboardServer.DEFAULT_PORT; }

            if (ip.isEmpty() || roomId.isEmpty() || roomCode.isEmpty() || username.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "Please fill in all fields.",
                    "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            serverIp        = ip;
            serverPort      = port;
            currentRoomCode = roomCode;
            client   = new WhiteboardClient(networkListener);
            if (!client.connect(ip, port)) {
                JOptionPane.showMessageDialog(frame,
                    "Could not connect to server at " + ip + ":" + port,
                    "Connection Failed", JOptionPane.ERROR_MESSAGE);
                client = null;
                return;
            }
            canvas.setLocalUsername(username);
            client.joinRoom(roomId, roomCode, username);
            // Response handled asynchronously in onRoomJoined() / onError()
        });
        collabBar.add(joinRoomBtn);

        // --- Leave Room ---
        JButton leaveRoomBtn = createToolButton("Leave Room");
        leaveRoomBtn.addActionListener(e -> {
            if (!inRoom) {
                JOptionPane.showMessageDialog(frame, "Not currently in a room.",
                    "Info", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (client != null) { client.disconnect(); client = null; }
            inRoom = false;
            currentRoomId   = null;
            currentRoomCode = null;
            serverIp        = null;
            canvas.setNetworkClient(null);
            canvas.setLocalUsername(null);
            canvas.clearRemoteCursors();
            roomInfoLabel.setText("No Room");
        });
        collabBar.add(leaveRoomBtn);

        // --- Canvas wrapper ---
        JPanel canvasWrapper = new JPanel(new BorderLayout());
        canvasWrapper.setBackground(new Color(60, 60, 75));
        canvasWrapper.setBorder(new CompoundBorder(
            new EmptyBorder(16, 20, 20, 20),
            new CompoundBorder(
                new LineBorder(new Color(20, 20, 28), 1),
                new EmptyBorder(0, 0, 0, 0)
            )
        ));
        canvasWrapper.add(canvas, BorderLayout.CENTER);

        // Stack the two toolbar rows, then canvas below
        JPanel toolbarArea = new JPanel();
        toolbarArea.setLayout(new BoxLayout(toolbarArea, BoxLayout.Y_AXIS));
        toolbarArea.add(toolbar);
        toolbarArea.add(collabBar);

        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBackground(new Color(60, 60, 75));
        mainPanel.add(toolbarArea, BorderLayout.NORTH);
        mainPanel.add(canvasWrapper, BorderLayout.CENTER);

        frame.add(header, BorderLayout.NORTH);
        frame.add(mainPanel, BorderLayout.CENTER);
        frame.setVisible(true);
    }

    private void handleDelete(JFrame parent) {
        try {
            WhiteboardDB.initSchema();
            List<WhiteboardDB.SavedBoard> boards = WhiteboardDB.listSaved();

            if (boards.isEmpty()) {
                JOptionPane.showMessageDialog(parent,
                    "No saved whiteboards found.", "Delete", JOptionPane.INFORMATION_MESSAGE);
                return;
            }

            JList<WhiteboardDB.SavedBoard> list = new JList<>(boards.toArray(new WhiteboardDB.SavedBoard[0]));
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setSelectedIndex(0);
            list.setVisibleRowCount(8);

            int result = JOptionPane.showConfirmDialog(parent,
                new JScrollPane(list), "Select a whiteboard to delete",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

            if (result != JOptionPane.OK_OPTION || list.getSelectedValue() == null) return;

            WhiteboardDB.SavedBoard selected = list.getSelectedValue();
            int confirm = JOptionPane.showConfirmDialog(parent,
                "Delete \"" + selected.name + "\"? This cannot be undone.",
                "Confirm Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

            if (confirm != JOptionPane.YES_OPTION) return;

            WhiteboardDB.delete(selected.id);
            JOptionPane.showMessageDialog(parent,
                "\"" + selected.name + "\" deleted.",
                "Deleted", JOptionPane.INFORMATION_MESSAGE);

        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent,
                "Delete failed:\n" + ex.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleSave(CanvasPanel canvas, JFrame parent) {
        String name = JOptionPane.showInputDialog(parent,
            "Enter a name for this whiteboard:", "Save Whiteboard",
            JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) return;

        try {
            WhiteboardDB.initSchema();
            int id = WhiteboardDB.save(name.trim(), canvas.getStrokes(), canvas.getShapes());
            JOptionPane.showMessageDialog(parent,
                "Saved as \"" + name.trim() + "\" (id " + id + ")",
                "Saved", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent,
                "Save failed:\n" + ex.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleLoad(CanvasPanel canvas, JFrame parent) {
        try {
            WhiteboardDB.initSchema();
            List<WhiteboardDB.SavedBoard> boards = WhiteboardDB.listSaved();

            if (boards.isEmpty()) {
                JOptionPane.showMessageDialog(parent,
                    "No saved whiteboards found.", "Load", JOptionPane.INFORMATION_MESSAGE);
                return;
            }

            JList<WhiteboardDB.SavedBoard> list = new JList<>(boards.toArray(new WhiteboardDB.SavedBoard[0]));
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setSelectedIndex(0);
            list.setVisibleRowCount(8);

            int result = JOptionPane.showConfirmDialog(parent,
                new JScrollPane(list), "Select a whiteboard to load",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

            if (result != JOptionPane.OK_OPTION || list.getSelectedValue() == null) return;

            WhiteboardDB.SavedBoard selected = list.getSelectedValue();
            WhiteboardDB.LoadedData data = WhiteboardDB.load(selected.id);
            canvas.loadStrokes(data.strokes);
            canvas.loadShapes(data.shapes);

        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent,
                "Load failed:\n" + ex.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private String roomLabel() {
        if (!inRoom || currentRoomId == null) return "No Room";
        String code = (currentRoomCode != null) ? currentRoomCode : "—";
        String ip   = (serverIp != null) ? serverIp : "—";
        return "Room: " + currentRoomId
             + "  |  Code: " + code
             + "  |  IP: " + ip + ":" + serverPort;
    }

    private void updateRoomLabel() {
        roomInfoLabel.setText(roomLabel());
    }

    // Returns this machine's LAN IP — "localhost" only works on the machine running the server
    private static String getLocalIP() {
        try {
            java.util.Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address) return addr.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "localhost";
    }

    private static <T extends AbstractButton> T createToolButton(String label) {
        AbstractButton btn;
        if (label.equals("Eraser")) {
            btn = new JToggleButton(label);
        } else {
            btn = new JButton(label);
        }
        btn.setFont(new Font("SansSerif", Font.PLAIN, 12));
        btn.setForeground(new Color(210, 210, 220));
        btn.setBackground(new Color(65, 65, 80));
        btn.setBorder(new CompoundBorder(
            new LineBorder(new Color(80, 80, 100), 1, true),
            new EmptyBorder(4, 12, 4, 12)
        ));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setOpaque(true);

        @SuppressWarnings("unchecked")
        T result = (T) btn;
        return result;
    }
}
