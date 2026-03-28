import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.print.*;
import javax.print.attribute.*;
import javax.print.attribute.standard.Destination;
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
    private int currentUserId = -1;
    private int currentBoardId = -1;
    private String currentUsername = "";
    private JTextArea chatArea;
    private JSplitPane mainSplit;

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> new whiteboardGUI().buildAndShowGUI());
    }

    private void buildAndShowGUI() {
        if (!showLoginDialog()) return;

        frame = new JFrame("Whiteboard — " + currentUsername);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1200, 800);
        frame.setMinimumSize(new Dimension(800, 550));
        frame.setLocationRelativeTo(null);

        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                if (client != null && client.isConnected()) client.disconnect();
            }
        });

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(new Color(30, 30, 40));
        header.setBorder(new EmptyBorder(12, 20, 12, 20));

        JLabel title = new JLabel("Whiteboard");
        title.setForeground(Color.WHITE);
        title.setFont(new Font("SansSerif", Font.BOLD, 20));
        header.add(title, BorderLayout.WEST);

        roomInfoLabel = new JLabel("No Room");
        roomInfoLabel.setForeground(new Color(140, 190, 255));
        roomInfoLabel.setFont(new Font("SansSerif", Font.PLAIN, 13));
        header.add(roomInfoLabel, BorderLayout.EAST);

        CanvasPanel canvas = new CanvasPanel();

        JPanel chatPanel = buildChatPanel(canvas);
        chatPanel.setPreferredSize(new Dimension(260, 400));
        chatPanel.setVisible(false);

        JPanel pageBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        pageBar.setBackground(new Color(36, 36, 48));
        pageBar.setBorder(new EmptyBorder(2, 8, 2, 8));

        Runnable rebuildPageBar = () -> SwingUtilities.invokeLater(() -> {
            pageBar.removeAll();
            int count   = canvas.getPageCount();
            int current = canvas.getCurrentPageIndex();
            for (int i = 0; i < count; i++) {
                final int idx = i;
                JButton tab = new JButton(canvas.getPageName(idx));
                tab.setFont(new Font("SansSerif", Font.PLAIN, 11));
                tab.setForeground(idx == current ? Color.WHITE : new Color(160, 160, 180));
                tab.setBackground(idx == current ? new Color(70, 70, 90) : new Color(45, 45, 58));
                tab.setBorder(new CompoundBorder(
                    new LineBorder(new Color(80, 80, 100), 1, true),
                    new EmptyBorder(3, 10, 3, 10)));
                tab.setFocusPainted(false);
                tab.setOpaque(true);
                tab.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                tab.addActionListener(ev -> canvas.switchPage(idx));
                pageBar.add(tab);
            }
            JButton addTab = smallBarBtn("+");
            addTab.setToolTipText("Add page");
            addTab.addActionListener(ev -> canvas.addPage());
            pageBar.add(addTab);
            if (count > 1) {
                JButton delTab = smallBarBtn("–");
                delTab.setToolTipText("Remove current page");
                delTab.addActionListener(ev -> canvas.removePage(canvas.getCurrentPageIndex()));
                pageBar.add(delTab);
            }
            pageBar.revalidate();
            pageBar.repaint();
        });
        canvas.setOnPagesChanged(rebuildPageBar);
        rebuildPageBar.run();

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
                    chatPanel.setVisible(true);
                    mainSplit.setDividerLocation(frame.getWidth() - 270);
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
                    currentBoardId = -1;
                    canvas.loadPages(new java.util.ArrayList<>());
                    canvas.setNetworkClient(client);
                    updateRoomLabel();
                    chatPanel.setVisible(true);
                    mainSplit.setDividerLocation(frame.getWidth() - 270);
                });
            }

            @Override
            public void onUserJoined(String username) {
                SwingUtilities.invokeLater(() -> {
                    roomInfoLabel.setText(roomLabel() + "  [+" + username + " joined]");
                    new javax.swing.Timer(3000, ev -> updateRoomLabel()) {{ setRepeats(false); start(); }};
                    appendChat("* " + username + " joined the room.");
                });
            }

            @Override
            public void onUserLeft(String username) {
                SwingUtilities.invokeLater(() -> {
                    canvas.removeRemoteCursor(username);
                    roomInfoLabel.setText(roomLabel() + "  [-" + username + " left]");
                    new javax.swing.Timer(3000, ev -> updateRoomLabel()) {{ setRepeats(false); start(); }};
                    appendChat("* " + username + " left the room.");
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
            public void onChat(String username, String message) {
                SwingUtilities.invokeLater(() -> appendChat(username + ": " + message));
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

        JPanel toolbar = new JPanel();
        toolbar.setBackground(new Color(45, 45, 58));
        toolbar.setBorder(new EmptyBorder(8, 16, 8, 16));
        toolbar.setLayout(new FlowLayout(FlowLayout.LEFT, 8, 0));

        JToggleButton eraserBtn = createToolButton("Eraser");
        eraserBtn.addActionListener(e -> canvas.setEraserMode(eraserBtn.isSelected()));
        toolbar.add(eraserBtn);

        JButton colorBtn = createToolButton("Color");
        JPanel colorPreview = new JPanel();
        colorPreview.setPreferredSize(new Dimension(20, 20));
        colorPreview.setBackground(Color.BLACK);
        colorBtn.addActionListener(e -> {
            Color c = JColorChooser.showDialog(null, "Choose Brush Color", colorPreview.getBackground());
            if (c != null) { canvas.setCurrentColor(c); colorPreview.setBackground(c); }
        });
        toolbar.add(colorBtn);
        toolbar.add(colorPreview);

        toolbar.add(Box.createHorizontalStrut(6));
        JLabel brushLabel = new JLabel("Brush:");
        brushLabel.setForeground(new Color(210, 210, 220));
        toolbar.add(brushLabel);

        JSlider brushSlider = new JSlider(1, 30, 6);
        brushSlider.setPreferredSize(new Dimension(100, 36));
        brushSlider.setBackground(new Color(45, 45, 58));

        var brushPreview = new JPanel() {
            private int size = 6;
            public void setSizeValue(int s) { size = s; repaint(); }
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                g.setColor(Color.BLACK);
                int x = (getWidth() - size) / 2, y = (getHeight() - size) / 2;
                g.fillOval(x, y, size, size);
            }
        };
        brushPreview.setPreferredSize(new Dimension(36, 36));
        brushPreview.setBackground(new Color(45, 45, 58));
        brushSlider.addChangeListener(e -> {
            int s = brushSlider.getValue();
            canvas.setBrushSize(s);
            brushPreview.setSizeValue(s);
        });
        toolbar.add(brushSlider);
        toolbar.add(brushPreview);

        toolbar.add(Box.createHorizontalStrut(8));
        String[] shapes = {"Free Draw", "Select", "Text", "Sticky Note",
                           "Rectangle", "Square", "Circle", "Triangle", "Diamond", "Star"};
        JComboBox<String> shapeBox = new JComboBox<>(shapes);
        shapeBox.setBackground(new Color(65, 65, 80));
        shapeBox.setForeground(new Color(210, 210, 220));
        shapeBox.addActionListener(e -> {
            String sel = (String) shapeBox.getSelectedItem();
            canvas.setShapeMode(sel);
            eraserBtn.setSelected(false);
            canvas.setEraserMode(false);
        });
        toolbar.add(shapeBox);

        JButton undoBtn = createToolButton("Undo");
        undoBtn.addActionListener(e -> canvas.undo());
        canvas.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke("control Z"), "undoAction");
        canvas.getActionMap().put("undoAction", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { canvas.undo(); }
        });
        toolbar.add(undoBtn);

        JButton redoBtn = createToolButton("Redo");
        redoBtn.addActionListener(e -> canvas.redo());
        canvas.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke("control shift Z"), "redoAction");
        canvas.getActionMap().put("redoAction", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { canvas.redo(); }
        });
        toolbar.add(redoBtn);

        JButton clearBtn = createToolButton("Clear");
        clearBtn.addActionListener(e -> canvas.clearCanvas());
        toolbar.add(clearBtn);

        JButton importBtn = createToolButton("Import Image");
        importBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter("Image Files", "png","jpg","jpeg","bmp","gif"));
            if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION)
                canvas.importImage(fc.getSelectedFile());
        });
        toolbar.add(importBtn);

        JLabel pasteHint = new JLabel("Ctrl+V = Paste");
        pasteHint.setForeground(new Color(130, 130, 150));
        pasteHint.setFont(new Font("SansSerif", Font.ITALIC, 11));
        toolbar.add(pasteHint);

        JPanel toolbar2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        toolbar2.setBackground(new Color(40, 40, 52));
        toolbar2.setBorder(new EmptyBorder(0, 16, 0, 16));

        JButton saveBtn = createToolButton("Save");
        saveBtn.addActionListener(e -> handleSave(canvas, frame));
        toolbar2.add(saveBtn);

        JButton loadBtn = createToolButton("Load");
        loadBtn.addActionListener(e -> handleLoad(canvas, frame));
        toolbar2.add(loadBtn);

        JButton deleteBtn = createToolButton("Delete");
        deleteBtn.addActionListener(e -> handleDelete(frame));
        toolbar2.add(deleteBtn);

        JButton exportBtn = createToolButton("Export");
        exportBtn.addActionListener(e -> handleExport(canvas, frame));
        toolbar2.add(exportBtn);

        addSep(toolbar2);

        JButton snapshotBtn = createToolButton("Snapshot");
        snapshotBtn.setToolTipText("Save a version snapshot of the current canvas");
        snapshotBtn.addActionListener(e -> handleSaveSnapshot(canvas, frame));
        toolbar2.add(snapshotBtn);

        JButton historyBtn = createToolButton("History");
        historyBtn.setToolTipText("Browse and restore version snapshots");
        historyBtn.addActionListener(e -> handleLoadSnapshot(canvas, frame));
        toolbar2.add(historyBtn);

        addSep(toolbar2);

        JLabel zoomLabel = new JLabel("Zoom:");
        zoomLabel.setForeground(new Color(210, 210, 220));
        toolbar2.add(zoomLabel);

        JButton zoomInBtn  = createToolButton("+");
        JButton zoomOutBtn = createToolButton("−");
        JButton zoomReset  = createToolButton("1:1");
        zoomInBtn.addActionListener(e  -> canvas.zoomIn());
        zoomOutBtn.addActionListener(e -> canvas.zoomOut());
        zoomReset.addActionListener(e  -> canvas.resetView());
        toolbar2.add(zoomInBtn);
        toolbar2.add(zoomOutBtn);
        toolbar2.add(zoomReset);

        addSep(toolbar2);

        JLabel collabLabel = new JLabel("Collab:");
        collabLabel.setForeground(new Color(140, 190, 255));
        collabLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        toolbar2.add(collabLabel);

        JButton createRoomBtn = createToolButton("Create Room");
        createRoomBtn.addActionListener(e -> {
            if (inRoom) {
                JOptionPane.showMessageDialog(frame, "Already in a room. Click 'Leave Room' first.",
                    "Info", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            JPanel p = new JPanel(new GridLayout(3, 2, 6, 6));
            JTextField ipField   = new JTextField(getLocalIP());
            JTextField portField = new JTextField(String.valueOf(WhiteboardServer.DEFAULT_PORT));
            JTextField nameField = new JTextField(currentUsername);
            p.add(new JLabel("Server IP:")); p.add(ipField);
            p.add(new JLabel("Port:"));      p.add(portField);
            p.add(new JLabel("Your Name:")); p.add(nameField);
            int res = JOptionPane.showConfirmDialog(frame, p, "Create Room",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (res != JOptionPane.OK_OPTION) return;
            String ip       = ipField.getText().trim();
            String username = nameField.getText().trim();
            int port;
            try { port = Integer.parseInt(portField.getText().trim()); }
            catch (NumberFormatException ex) { port = WhiteboardServer.DEFAULT_PORT; }
            if (ip.isEmpty() || username.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "Please fill in all fields.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            Thread st = new Thread(() -> new WhiteboardServer(WhiteboardServer.DEFAULT_PORT).start(), "EmbeddedServer");
            st.setDaemon(true);
            st.start();
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}
            serverIp = ip;
            client = new WhiteboardClient(networkListener);
            if (!client.connect(ip, port)) {
                JOptionPane.showMessageDialog(frame,
                    "Could not connect to server at " + ip + ":" + port, "Connection Failed", JOptionPane.ERROR_MESSAGE);
                client = null; return;
            }
            serverPort = port;
            canvas.setLocalUsername(username);
            client.createRoom(username);
        });
        toolbar2.add(createRoomBtn);

        JButton joinRoomBtn = createToolButton("Join Room");
        joinRoomBtn.addActionListener(e -> {
            if (inRoom) {
                JOptionPane.showMessageDialog(frame, "Already in a room. Click 'Leave Room' first.",
                    "Info", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            JPanel p = new JPanel(new GridLayout(5, 2, 6, 6));
            JTextField ipField       = new JTextField(getLocalIP());
            JTextField portField     = new JTextField(String.valueOf(WhiteboardServer.DEFAULT_PORT));
            JTextField roomIdField   = new JTextField();
            JTextField roomCodeField = new JTextField();
            JTextField nameField     = new JTextField(currentUsername);
            p.add(new JLabel("Server IP:"));   p.add(ipField);
            p.add(new JLabel("Port:"));        p.add(portField);
            p.add(new JLabel("Room ID:"));     p.add(roomIdField);
            p.add(new JLabel("Room Code:"));   p.add(roomCodeField);
            p.add(new JLabel("Your Name:"));   p.add(nameField);
            int res = JOptionPane.showConfirmDialog(frame, p, "Join Room",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (res != JOptionPane.OK_OPTION) return;
            String ip       = ipField.getText().trim();
            String roomId   = roomIdField.getText().trim().toUpperCase();
            String roomCode = roomCodeField.getText().trim();
            String username = nameField.getText().trim();
            int port;
            try { port = Integer.parseInt(portField.getText().trim()); }
            catch (NumberFormatException ex) { port = WhiteboardServer.DEFAULT_PORT; }
            if (ip.isEmpty() || roomId.isEmpty() || roomCode.isEmpty() || username.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "Please fill in all fields.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            serverIp = ip; serverPort = port; currentRoomCode = roomCode;
            client = new WhiteboardClient(networkListener);
            if (!client.connect(ip, port)) {
                JOptionPane.showMessageDialog(frame,
                    "Could not connect to server at " + ip + ":" + port, "Connection Failed", JOptionPane.ERROR_MESSAGE);
                client = null; return;
            }
            canvas.setLocalUsername(username);
            client.joinRoom(roomId, roomCode, username);
        });
        toolbar2.add(joinRoomBtn);

        JButton leaveRoomBtn = createToolButton("Leave Room");
        leaveRoomBtn.addActionListener(e -> {
            if (!inRoom) {
                JOptionPane.showMessageDialog(frame, "Not currently in a room.",
                    "Info", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (client != null) { client.disconnect(); client = null; }
            inRoom = false;
            currentRoomId = null; currentRoomCode = null; serverIp = null;
            canvas.setNetworkClient(null);
            canvas.setLocalUsername(null);
            canvas.clearRemoteCursors();
            roomInfoLabel.setText("No Room");
            chatPanel.setVisible(false);
            mainSplit.resetToPreferredSizes();
        });
        toolbar2.add(leaveRoomBtn);

        addSep(toolbar2);

        JButton helpBtn = createToolButton("?  Help");
        helpBtn.setForeground(new Color(255, 220, 80));
        helpBtn.addActionListener(e -> showHelpDialog(frame));
        toolbar2.add(helpBtn);

        JPanel canvasWrapper = new JPanel(new BorderLayout());
        canvasWrapper.setBackground(new Color(60, 60, 75));
        canvasWrapper.setBorder(new CompoundBorder(
            new EmptyBorder(12, 16, 0, 16),
            new CompoundBorder(new LineBorder(new Color(20, 20, 28), 1), new EmptyBorder(0, 0, 0, 0))));
        canvasWrapper.add(canvas, BorderLayout.CENTER);

        JPanel leftPane = new JPanel(new BorderLayout());
        leftPane.setBackground(new Color(60, 60, 75));
        leftPane.add(canvasWrapper, BorderLayout.CENTER);
        leftPane.add(pageBar, BorderLayout.SOUTH);

        mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPane, chatPanel);
        mainSplit.setResizeWeight(1.0);
        mainSplit.setDividerSize(4);
        mainSplit.setBorder(null);
        mainSplit.setBackground(new Color(60, 60, 75));

        JPanel toolbarArea = new JPanel();
        toolbarArea.setLayout(new BoxLayout(toolbarArea, BoxLayout.Y_AXIS));
        toolbarArea.add(toolbar);
        toolbarArea.add(toolbar2);

        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBackground(new Color(60, 60, 75));
        mainPanel.add(toolbarArea, BorderLayout.NORTH);
        mainPanel.add(mainSplit, BorderLayout.CENTER);

        frame.add(header, BorderLayout.NORTH);
        frame.add(mainPanel, BorderLayout.CENTER);
        frame.setVisible(true);
    }

    private JPanel buildChatPanel(CanvasPanel canvas) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(new Color(36, 36, 48));
        panel.setBorder(new MatteBorder(0, 1, 0, 0, new Color(60, 60, 80)));

        JLabel chatTitle = new JLabel(" Chat", JLabel.LEFT);
        chatTitle.setFont(new Font("SansSerif", Font.BOLD, 13));
        chatTitle.setForeground(new Color(140, 190, 255));
        chatTitle.setBorder(new EmptyBorder(8, 8, 6, 8));
        chatTitle.setBackground(new Color(30, 30, 42));
        chatTitle.setOpaque(true);
        panel.add(chatTitle, BorderLayout.NORTH);

        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        chatArea.setFont(new Font("SansSerif", Font.PLAIN, 12));
        chatArea.setBackground(new Color(28, 28, 38));
        chatArea.setForeground(new Color(210, 210, 220));
        chatArea.setBorder(new EmptyBorder(6, 8, 6, 8));
        JScrollPane chatScroll = new JScrollPane(chatArea);
        chatScroll.setBorder(null);
        panel.add(chatScroll, BorderLayout.CENTER);

        JPanel inputRow = new JPanel(new BorderLayout(4, 0));
        inputRow.setBackground(new Color(36, 36, 48));
        inputRow.setBorder(new EmptyBorder(4, 6, 6, 6));

        JTextField chatInput = new JTextField();
        chatInput.setFont(new Font("SansSerif", Font.PLAIN, 12));
        chatInput.setBackground(new Color(50, 50, 65));
        chatInput.setForeground(new Color(210, 210, 220));
        chatInput.setCaretColor(Color.WHITE);
        chatInput.setBorder(new CompoundBorder(
            new LineBorder(new Color(70, 70, 90), 1, true),
            new EmptyBorder(4, 6, 4, 6)));

        JButton sendBtn = new JButton("Send");
        sendBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        sendBtn.setBackground(new Color(65, 100, 160));
        sendBtn.setForeground(Color.WHITE);
        sendBtn.setFocusPainted(false);
        sendBtn.setOpaque(true);
        sendBtn.setBorder(new CompoundBorder(
            new LineBorder(new Color(80, 120, 180), 1, true),
            new EmptyBorder(4, 10, 4, 10)));

        Runnable sendMsg = () -> {
            String text = chatInput.getText().trim();
            if (text.isEmpty() || client == null || !client.isConnected()) return;
            client.sendChat(currentUsername, text);
            appendChat(currentUsername + " (you): " + text);
            chatInput.setText("");
        };
        sendBtn.addActionListener(e -> sendMsg.run());
        chatInput.addActionListener(e -> sendMsg.run());

        inputRow.add(chatInput, BorderLayout.CENTER);
        inputRow.add(sendBtn, BorderLayout.EAST);
        panel.add(inputRow, BorderLayout.SOUTH);

        return panel;
    }

    private void appendChat(String line) {
        if (chatArea == null) return;
        chatArea.append(line + "\n");
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }

    private boolean showLoginDialog() {
        while (true) {
            JPanel panel = new JPanel(new GridBagLayout());
            panel.setBackground(new Color(40, 40, 52));
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(6, 8, 6, 8);
            gbc.fill = GridBagConstraints.HORIZONTAL;

            JLabel titleLbl = new JLabel("Whiteboard — Sign In", JLabel.CENTER);
            titleLbl.setFont(new Font("SansSerif", Font.BOLD, 16));
            titleLbl.setForeground(Color.WHITE);
            gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
            panel.add(titleLbl, gbc);

            gbc.gridwidth = 1;
            JTextField userField   = new JTextField(18);
            JPasswordField passField = new JPasswordField(18);

            gbc.gridy = 1; gbc.gridx = 0; panel.add(new JLabel("Username:") {{ setForeground(new Color(200,200,210)); }}, gbc);
            gbc.gridx = 1; panel.add(userField, gbc);
            gbc.gridy = 2; gbc.gridx = 0; panel.add(new JLabel("Password:") {{ setForeground(new Color(200,200,210)); }}, gbc);
            gbc.gridx = 1; panel.add(passField, gbc);

            String[] options = {"Login", "Register", "Cancel"};
            int choice = JOptionPane.showOptionDialog(null, panel, "Welcome",
                JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);

            if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) { System.exit(0); return false; }

            String username = userField.getText().trim();
            String password = new String(passField.getPassword()).trim();

            if (username.isEmpty() || password.isEmpty()) {
                JOptionPane.showMessageDialog(null, "Username and password are required.", "Error", JOptionPane.ERROR_MESSAGE);
                continue;
            }

            try {
                if (choice == 0) {
                    int id = WhiteboardDB.loginUser(username, password);
                    if (id == -1) {
                        JOptionPane.showMessageDialog(null, "Incorrect username or password.", "Login Failed", JOptionPane.ERROR_MESSAGE);
                        continue;
                    }
                    currentUserId = id; currentUsername = username; return true;
                } else {
                    if (password.length() < 4) {
                        JOptionPane.showMessageDialog(null, "Password must be at least 4 characters.", "Error", JOptionPane.ERROR_MESSAGE);
                        continue;
                    }
                    int id = WhiteboardDB.registerUser(username, password);
                    if (id == -1) {
                        JOptionPane.showMessageDialog(null, "Username \"" + username + "\" is already taken.", "Register Failed", JOptionPane.ERROR_MESSAGE);
                        continue;
                    }
                    currentUserId = id; currentUsername = username;
                    JOptionPane.showMessageDialog(null, "Account created! Welcome, " + username + ".", "Registered", JOptionPane.INFORMATION_MESSAGE);
                    return true;
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(null, "Database error:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void handleSave(CanvasPanel canvas, JFrame parent) {
        String name = JOptionPane.showInputDialog(parent,
            "Enter a name for this whiteboard:", "Save Whiteboard", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) return;
        try {
            int id = WhiteboardDB.save(name.trim(), currentUserId, canvas.getPages());
            currentBoardId = id;
            JOptionPane.showMessageDialog(parent, "Saved as \"" + name.trim() + "\"",
                "Saved", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent, "Save failed:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleLoad(CanvasPanel canvas, JFrame parent) {
        try {
            List<WhiteboardDB.SavedBoard> boards = WhiteboardDB.listSaved(currentUserId);
            if (boards.isEmpty()) {
                JOptionPane.showMessageDialog(parent, "No saved whiteboards found.", "Load", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            JList<WhiteboardDB.SavedBoard> list = new JList<>(boards.toArray(new WhiteboardDB.SavedBoard[0]));
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setSelectedIndex(0);
            list.setVisibleRowCount(8);
            int result = JOptionPane.showConfirmDialog(parent, new JScrollPane(list),
                "Select a whiteboard to load", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION || list.getSelectedValue() == null) return;
            currentBoardId = list.getSelectedValue().id;
            ArrayList<CanvasPanel.Page> pages = WhiteboardDB.loadPages(currentBoardId);
            canvas.loadPages(pages);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent, "Load failed:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleDelete(JFrame parent) {
        try {
            List<WhiteboardDB.SavedBoard> boards = WhiteboardDB.listSaved(currentUserId);
            if (boards.isEmpty()) {
                JOptionPane.showMessageDialog(parent, "No saved whiteboards found.", "Delete", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            JList<WhiteboardDB.SavedBoard> list = new JList<>(boards.toArray(new WhiteboardDB.SavedBoard[0]));
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setSelectedIndex(0);
            list.setVisibleRowCount(8);
            int result = JOptionPane.showConfirmDialog(parent, new JScrollPane(list),
                "Select a whiteboard to delete", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION || list.getSelectedValue() == null) return;
            WhiteboardDB.SavedBoard sel = list.getSelectedValue();
            int confirm = JOptionPane.showConfirmDialog(parent,
                "Delete \"" + sel.name + "\"? This cannot be undone.",
                "Confirm Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm != JOptionPane.YES_OPTION) return;
            WhiteboardDB.delete(sel.id);
            if (sel.id == currentBoardId) currentBoardId = -1;
            JOptionPane.showMessageDialog(parent, "\"" + sel.name + "\" deleted.", "Deleted", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent, "Delete failed:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleSaveSnapshot(CanvasPanel canvas, JFrame parent) {
        String label = JOptionPane.showInputDialog(parent,
            "Label for this snapshot (e.g. \"v1 — before edits\"):",
            "Save Snapshot", JOptionPane.PLAIN_MESSAGE);
        if (label == null || label.isBlank()) return;
        try {
            WhiteboardDB.saveSnapshot(currentUserId, currentBoardId, label.trim(), canvas.getPages());
            JOptionPane.showMessageDialog(parent, "Snapshot \"" + label.trim() + "\" saved.",
                "Snapshot Saved", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent, "Snapshot failed:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleLoadSnapshot(CanvasPanel canvas, JFrame parent) {
        try {
            List<WhiteboardDB.Snapshot> snaps = WhiteboardDB.listSnapshots(currentUserId, currentBoardId);
            if (snaps.isEmpty()) {
                JOptionPane.showMessageDialog(parent,
                    "No snapshots found. Use the Snapshot button to save one.",
                    "History", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            JList<WhiteboardDB.Snapshot> list = new JList<>(snaps.toArray(new WhiteboardDB.Snapshot[0]));
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setSelectedIndex(0);
            list.setVisibleRowCount(8);

            String[] opts = {"Restore", "Delete", "Cancel"};
            int choice = JOptionPane.showOptionDialog(parent, new JScrollPane(list),
                "Version History", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE,
                null, opts, opts[0]);

            if (choice == 2 || choice == JOptionPane.CLOSED_OPTION || list.getSelectedValue() == null) return;
            WhiteboardDB.Snapshot sel = list.getSelectedValue();

            if (choice == 0) { 
                int confirm = JOptionPane.showConfirmDialog(parent,
                    "Restore \"" + sel.label + "\"? Current canvas will be replaced.",
                    "Confirm Restore", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (confirm != JOptionPane.YES_OPTION) return;
                ArrayList<CanvasPanel.Page> pages = WhiteboardDB.loadSnapshot(sel.id);
                canvas.loadPages(pages);
                JOptionPane.showMessageDialog(parent, "Restored to \"" + sel.label + "\".",
                    "Restored", JOptionPane.INFORMATION_MESSAGE);
            } else { 
                int confirm = JOptionPane.showConfirmDialog(parent,
                    "Delete snapshot \"" + sel.label + "\"?",
                    "Confirm Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (confirm != JOptionPane.YES_OPTION) return;
                WhiteboardDB.deleteSnapshot(sel.id);
                JOptionPane.showMessageDialog(parent, "Snapshot deleted.", "Deleted", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent, "History error:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleExport(CanvasPanel canvas, JFrame parent) {
        int pageCount = canvas.getPageCount();

        int[] pageIndices;
        if (pageCount == 1) {
            pageIndices = new int[]{0};
        } else {

            String[] pageNames = new String[pageCount];
            for (int i = 0; i < pageCount; i++) pageNames[i] = canvas.getPageName(i);

            JPanel selPanel = new JPanel(new BorderLayout(6, 6));
            JLabel lbl = new JLabel("Select pages to export:");
            selPanel.add(lbl, BorderLayout.NORTH);

            JCheckBox[] boxes = new JCheckBox[pageCount];
            JPanel boxPanel = new JPanel(new GridLayout(0, 1, 2, 2));
            for (int i = 0; i < pageCount; i++) {
                boxes[i] = new JCheckBox(pageNames[i], i == canvas.getCurrentPageIndex());
                boxPanel.add(boxes[i]);
            }
            selPanel.add(new JScrollPane(boxPanel), BorderLayout.CENTER);

            JButton allBtn  = new JButton("Select All");
            JButton noneBtn = new JButton("None");
            allBtn.addActionListener(e  -> { for (JCheckBox b : boxes) b.setSelected(true); });
            noneBtn.addActionListener(e -> { for (JCheckBox b : boxes) b.setSelected(false); });
            JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            btnRow.add(allBtn); btnRow.add(noneBtn);
            selPanel.add(btnRow, BorderLayout.SOUTH);

            int res = JOptionPane.showConfirmDialog(parent, selPanel,
                "Export Pages", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (res != JOptionPane.OK_OPTION) return;

            java.util.List<Integer> chosen = new java.util.ArrayList<>();
            for (int i = 0; i < pageCount; i++) if (boxes[i].isSelected()) chosen.add(i);
            if (chosen.isEmpty()) return;
            pageIndices = chosen.stream().mapToInt(Integer::intValue).toArray();
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export Whiteboard");
        chooser.setFileFilter(new FileNameExtensionFilter("PNG Image", "png"));
        chooser.addChoosableFileFilter(new FileNameExtensionFilter("JPEG Image", "jpg", "jpeg"));
        chooser.addChoosableFileFilter(new FileNameExtensionFilter("PDF Document", "pdf"));
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;

        File file = chooser.getSelectedFile();
        String fname = file.getName().toLowerCase();
        String filterDesc = chooser.getFileFilter().getDescription();
        boolean isPDF  = fname.endsWith(".pdf")  || (!fname.contains(".") && filterDesc.contains("PDF"));
        boolean isJPEG = fname.endsWith(".jpg") || fname.endsWith(".jpeg")
                      || (!fname.contains(".") && filterDesc.contains("JPEG"));

        try {
            if (pageIndices.length == 1) {

                BufferedImage image = canvas.renderPageToImage(pageIndices[0]);
                File out = ensureExtension(file, isPDF ? "pdf" : isJPEG ? "jpg" : "png");
                if (isPDF)       exportAsPDF(new BufferedImage[]{image}, out);
                else if (isJPEG) ImageIO.write(image, "jpg", out);
                else             ImageIO.write(image, "png", out);
                JOptionPane.showMessageDialog(parent, "Exported to:\n" + out.getAbsolutePath(),
                    "Export", JOptionPane.INFORMATION_MESSAGE);
            } else {

                BufferedImage[] images = new BufferedImage[pageIndices.length];
                for (int i = 0; i < pageIndices.length; i++)
                    images[i] = canvas.renderPageToImage(pageIndices[i]);

                if (isPDF) {
                    File out = ensureExtension(file, "pdf");
                    exportAsPDF(images, out);
                    JOptionPane.showMessageDialog(parent, "Exported " + images.length
                        + " pages to:\n" + out.getAbsolutePath(), "Export", JOptionPane.INFORMATION_MESSAGE);
                } else {

                    String base = file.getAbsolutePath();
                    if (base.contains(".")) base = base.substring(0, base.lastIndexOf('.'));
                    String ext  = isJPEG ? "jpg" : "png";
                    for (int i = 0; i < images.length; i++) {
                        File out = new File(base + "_page" + (pageIndices[i] + 1) + "." + ext);
                        ImageIO.write(images[i], ext, out);
                    }
                    JOptionPane.showMessageDialog(parent, "Exported " + images.length
                        + " pages to:\n" + new File(base + "_page1." + ext).getParent(),
                        "Export", JOptionPane.INFORMATION_MESSAGE);
                }
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent, "Export failed:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static File ensureExtension(File f, String ext) {
        String p = f.getAbsolutePath();
        return p.toLowerCase().endsWith("." + ext) ? f : new File(p + "." + ext);
    }

    private void exportAsPDF(BufferedImage[] images, File file) throws Exception {
        PrinterJob job = PrinterJob.getPrinterJob();
        job.setPageable(new java.awt.print.Pageable() {
            @Override public int getNumberOfPages() { return images.length; }
            @Override public PageFormat getPageFormat(int pi) { return job.defaultPage(); }
            @Override public Printable getPrintable(int pi) {
                BufferedImage img = images[pi];
                return (graphics, pageFormat, pageIndex) -> {
                    if (pageIndex > 0) return Printable.NO_SUCH_PAGE;
                    Graphics2D g2 = (Graphics2D) graphics;
                    double scaleX = pageFormat.getImageableWidth()  / img.getWidth();
                    double scaleY = pageFormat.getImageableHeight() / img.getHeight();
                    double scale  = Math.min(scaleX, scaleY);
                    g2.drawImage(img,
                        (int) pageFormat.getImageableX(), (int) pageFormat.getImageableY(),
                        (int)(img.getWidth() * scale),    (int)(img.getHeight() * scale), null);
                    return Printable.PAGE_EXISTS;
                };
            }
        });
        PrintRequestAttributeSet attr = new HashPrintRequestAttributeSet();
        attr.add(new Destination(file.toURI()));
        job.print(attr);
    }

    private String roomLabel() {
        if (!inRoom || currentRoomId == null) return "No Room";
        String code = (currentRoomCode != null) ? currentRoomCode : "—";
        String ip   = (serverIp != null) ? serverIp : "—";
        return "Room: " + currentRoomId + "  |  Code: " + code + "  |  IP: " + ip + ":" + serverPort;
    }

    private void updateRoomLabel() { roomInfoLabel.setText(roomLabel()); }

    private void showHelpDialog(JFrame parent) {
        String[][] sections = {
            {"Drawing Tools", null},
            {"Free Draw",       "Click and drag to draw freehand strokes"},
            {"Select",          "Click any item to select and drag it\nDrag on empty space to select multiple items\nDelete selected items with the Delete key"},
            {"Text",            "Click and drag to define a text box, then type\nPress Escape or click outside to commit"},
            {"Sticky Note",     "Click anywhere to place a sticky note\nChoose colour and type your text"},
            {"Shapes",          "Click and drag to draw Rectangle, Circle, Triangle, etc."},
            {"Eraser",          "Toggle the Eraser button, then draw over strokes to erase"},
            {null, null},
            {"Navigation", null},
            {"Pan canvas",      "Hold Space + left-click drag\nOR middle-mouse drag"},
            {"Zoom",            "Ctrl + scroll wheel (zoom in/out)\nToolbar +  /  −  /  1:1 buttons"},
            {"Reset view",      "Click 1:1 in the toolbar"},
            {null, null},
            {"Keyboard Shortcuts", null},
            {"Ctrl + Z",        "Undo"},
            {"Ctrl + Shift + Z","Redo"},
            {"Ctrl + V",        "Paste image from clipboard"},
            {"Space + drag",    "Pan the canvas"},
            {"Delete",          "Delete selected items (when using Select tool)"},
            {"Escape",          "Cancel current text entry"},
            {null, null},
            {"Pages", null},
            {"Add page",        "Click the + button in the page bar at the bottom"},
            {"Remove page",     "Click the − button (only visible when there are 2+ pages)"},
            {"Switch page",     "Click the page tab in the page bar"},
            {null, null},
            {"Saving & Exporting", null},
            {"Save",            "Saves the whiteboard to the database under your account"},
            {"Load",            "Opens one of your saved whiteboards"},
            {"Export",          "Saves current canvas as PNG, JPEG, or PDF\nWith multiple pages you can choose which pages to export"},
            {"Snapshot",        "Saves a named version snapshot of the current canvas"},
            {"History",         "Browse past snapshots — restore or delete them"},
            {null, null},
            {"Collaboration", null},
            {"Create Room",     "Starts an embedded server and creates a room\nShare the Room ID, Code, and IP with others"},
            {"Join Room",       "Connect to an existing room using the Room ID and Code"},
            {"Leave Room",      "Disconnect from the current room"},
            {"Chat panel",      "Appears on the right when you are in a room"},
        };

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(new Color(30, 30, 42));
        content.setBorder(new EmptyBorder(12, 16, 12, 16));

        Font headerFont = new Font("SansSerif", Font.BOLD, 13);
        Font keyFont    = new Font("Monospaced", Font.BOLD, 12);
        Font valFont    = new Font("SansSerif", Font.PLAIN, 12);

        for (String[] row : sections) {
            if (row[0] == null) {
                content.add(Box.createVerticalStrut(6));
                continue;
            }
            if (row[1] == null) {
                JLabel lbl = new JLabel(row[0]);
                lbl.setFont(headerFont);
                lbl.setForeground(new Color(140, 190, 255));
                lbl.setBorder(new MatteBorder(0, 0, 1, 0, new Color(60, 70, 100)));
                lbl.setAlignmentX(0f);
                content.add(lbl);
                content.add(Box.createVerticalStrut(4));
            } else {
                JPanel row2 = new JPanel(new BorderLayout(16, 0));
                row2.setBackground(new Color(30, 30, 42));
                row2.setMaximumSize(new Dimension(Integer.MAX_VALUE, 999));

                JLabel key = new JLabel("<html><b>" + row[0] + "</b></html>");
                key.setFont(keyFont);
                key.setForeground(new Color(255, 220, 80));
                key.setPreferredSize(new Dimension(170, 1));
                key.setVerticalAlignment(JLabel.TOP);

                JLabel val = new JLabel("<html>" + row[1].replace("\n", "<br>") + "</html>");
                val.setFont(valFont);
                val.setForeground(new Color(200, 200, 215));

                row2.add(key, BorderLayout.WEST);
                row2.add(val, BorderLayout.CENTER);
                row2.setBorder(new EmptyBorder(1, 10, 1, 0));
                content.add(row2);
            }
        }

        JScrollPane scroll = new JScrollPane(content);
        scroll.setPreferredSize(new Dimension(620, 480));
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        JOptionPane.showMessageDialog(parent, scroll, "Keyboard Shortcuts & Help",
            JOptionPane.PLAIN_MESSAGE);
    }

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

    private static void addSep(JPanel panel) {
        JSeparator sep = new JSeparator(JSeparator.VERTICAL);
        sep.setPreferredSize(new Dimension(1, 24));
        sep.setForeground(new Color(80, 80, 95));
        panel.add(Box.createHorizontalStrut(2));
        panel.add(sep);
        panel.add(Box.createHorizontalStrut(2));
    }

    private static JButton smallBarBtn(String label) {
        JButton btn = new JButton(label);
        btn.setFont(new Font("SansSerif", Font.BOLD, 13));
        btn.setForeground(new Color(180, 180, 200));
        btn.setBackground(new Color(55, 55, 70));
        btn.setBorder(new CompoundBorder(
            new LineBorder(new Color(80, 80, 100), 1, true),
            new EmptyBorder(2, 8, 2, 8)));
        btn.setFocusPainted(false);
        btn.setOpaque(true);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private static <T extends AbstractButton> T createToolButton(String label) {
        AbstractButton btn = label.equals("Eraser") ? new JToggleButton(label) : new JButton(label);
        btn.setFont(new Font("SansSerif", Font.PLAIN, 12));
        btn.setForeground(new Color(210, 210, 220));
        btn.setBackground(new Color(65, 65, 80));
        btn.setBorder(new CompoundBorder(
            new LineBorder(new Color(80, 80, 100), 1, true),
            new EmptyBorder(4, 10, 4, 10)));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setOpaque(true);
        @SuppressWarnings("unchecked") T result = (T) btn;
        return result;
    }
}
