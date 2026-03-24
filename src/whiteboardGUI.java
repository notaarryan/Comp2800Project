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
        JComboBox<String> shapeBox = new JComboBox<>(shapes);
        shapeBox.setBackground(new Color(65,65,80));
        shapeBox.setForeground(new Color(210,210,220));

        shapeBox.addActionListener(e -> {
            String selected = (String) shapeBox.getSelectedItem();
            canvas.setShapeMode(selected);
        });

        toolbar.add(shapeBox);

        // new undo button - Marko - 3/24/26
        JButton undoBtn = createToolButton("Undo");
        undoBtn.addActionListener(e -> canvas.undo());
        toolbar.add(undoBtn);

        // new redo button - Marko - 3/24/26
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

}  