/*******
Marko Simic
Whiteboard GUI Application
3/10/25
*******************/
import java.awt.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.*;

public class whiteboardGUI {

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> buildAndShowGUI());
    }

    private static void buildAndShowGUI() {
        JFrame frame = new JFrame("Whiteboard");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1100, 750);
        frame.setMinimumSize(new Dimension(700, 500));
        frame.setLocationRelativeTo(null);

        // --- Header bar ---
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(new Color(30, 30, 40));
        header.setBorder(new EmptyBorder(12, 20, 12, 20));

        JLabel title = new JLabel("Whiteboard");
        title.setForeground(Color.WHITE);
        title.setFont(new Font("SansSerif", Font.BOLD, 20));
        header.add(title, BorderLayout.WEST);

        // --- Canvas ---
        CanvasPanel canvas = new CanvasPanel();

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
            Color selectedColor = JColorChooser.showDialog(
                null,
                "Choose Brush Color",
                Color.BLACK
            );

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
        brushLabel.setForeground(new Color(210,210,220));
        toolbar.add(brushLabel);

        JSlider brushSlider = new JSlider(1, 30, 6);
        brushSlider.setPreferredSize(new Dimension(120, 40));
        brushSlider.setBackground(new Color(45,45,58));

        var brushPreview = new JPanel() {
            private int size = 6;

            public void setSizeValue(int s) {
                size = s;
                repaint();
            }

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
        brushPreview.setBackground(new Color(45,45,58));

        brushSlider.addChangeListener(e -> {
            int size = brushSlider.getValue();
            canvas.setBrushSize(size);
            brushPreview.setSizeValue(size);
        });

        toolbar.add(brushSlider);
        toolbar.add(brushPreview);

        // shape selector added
        toolbar.add(Box.createHorizontalStrut(10));

        String[] shapes = {"Free Draw", "Rectangle", "Square", "Circle", "Triangle", "Diamond", "Star"};
        JComboBox<String> shapeBox = new JComboBox<>(shapes); // combo box to select the shapes 
        shapeBox.setBackground(new Color(65,65,80));
        shapeBox.setForeground(new Color(210,210,220));

        shapeBox.addActionListener(e -> {
            String selected = (String) shapeBox.getSelectedItem();
            canvas.setShapeMode(selected);
        });

        toolbar.add(shapeBox);

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

        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBackground(new Color(60, 60, 75));
        mainPanel.add(toolbar, BorderLayout.NORTH);
        mainPanel.add(canvasWrapper, BorderLayout.CENTER);

        frame.add(header, BorderLayout.NORTH);
        frame.add(mainPanel, BorderLayout.CENTER);
        frame.setVisible(true);
    }

    private static void handleDelete(JFrame parent) {
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

    private static void handleSave(CanvasPanel canvas, JFrame parent) {
        String name = JOptionPane.showInputDialog(parent,
            "Enter a name for this whiteboard:", "Save Whiteboard",
            JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) return;

        try {
            WhiteboardDB.initSchema();
            int id = WhiteboardDB.save(name.trim(), canvas.getStrokes(), canvas.getShapes()); // updated to handle shapes aswell
            JOptionPane.showMessageDialog(parent,
                "Saved as \"" + name.trim() + "\" (id " + id + ")",
                "Saved", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent,
                "Save failed:\n" + ex.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void handleLoad(CanvasPanel canvas, JFrame parent) {
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
            canvas.loadStrokes(data.strokes);         // updated to handle shapes aswell 
            canvas.loadShapes(data.shapes);

        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent,
                "Load failed:\n" + ex.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
        }
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