import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import javax.swing.*;

class CanvasPanel extends JPanel {

    static class Stroke {
        ArrayList<Point> points = new ArrayList<>();
        Color color;
        float width;

        Stroke(Color c, float w){
            color = c;
            width = w;
        }
    }

    static class ShapeItem {
        String type;
        Point start, end;
        Color color;
        float width;

        ShapeItem(String t, Point s, Point e, Color c, float w) {
            type = t;
            start = s;
            end = e;
            color = c;
            width = w;
        }
    }

    private ArrayList<Stroke> strokes = new ArrayList<>();
    private ArrayList<ShapeItem> shapes = new ArrayList<>();

    // --- Undo/Redo ---
    private ArrayList<Object> history = new ArrayList<>();
    private ArrayList<Object> redoStack = new ArrayList<>();

    private Stroke currentStroke;
    private Color currentColor = Color.BLACK;
    private Color previousColor = Color.BLACK;

    private float brushSize = 6.0f;
    private float currentWidth = brushSize;

    private String currentShape = "Free Draw";
    private Point shapeStart = null;
    private Point currentDragPoint = null;

    // Networking: null when not in a collaborative session
    private WhiteboardClient networkClient = null;

    public CanvasPanel() {
        setBackground(Color.WHITE);
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                currentWidth = brushSize;

                if (!currentShape.equals("Free Draw")) {
                    shapeStart = e.getPoint();
                } else {
                    currentStroke = new Stroke(currentColor, currentWidth);
                    currentStroke.points.add(e.getPoint());
                    strokes.add(currentStroke);

                    // save for undo
                    saveState(currentStroke);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (!currentShape.equals("Free Draw") && shapeStart != null) {
                    shapes.add(new ShapeItem(
                        currentShape,
                        shapeStart,
                        e.getPoint(),
                        currentColor,
                        currentWidth
                    ));

                    // save for undo
                    saveState(shapes.get(shapes.size() - 1));

                    // Network: broadcast the completed shape to other clients
                    if (networkClient != null) {
                        ShapeItem added = shapes.get(shapes.size() - 1);
                        networkClient.sendShape(
                            added.type,
                            added.start.x, added.start.y,
                            added.end.x,   added.end.y,
                            WhiteboardClient.colorToHex(added.color),
                            added.width
                        );
                    }

                    shapeStart = null;
                    currentDragPoint = null;
                    repaint();

                } else if (currentShape.equals("Free Draw") && currentStroke != null) {
                    // Handle single click (no drag): send a dot so remote clients see it
                    if (networkClient != null && currentStroke.points.size() == 1) {
                        Point p = currentStroke.points.get(0);
                        networkClient.sendDrawSegment(
                            p.x, p.y, p.x, p.y,
                            WhiteboardClient.colorToHex(currentColor),
                            currentWidth
                        );
                    }
                }
            }
        });

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (currentShape.equals("Free Draw")) {
                    currentStroke.points.add(e.getPoint());

                    // Network: send the segment between the last two drag points
                    if (networkClient != null && currentStroke.points.size() >= 2) {
                        int n = currentStroke.points.size();
                        Point p1 = currentStroke.points.get(n - 2);
                        Point p2 = currentStroke.points.get(n - 1);
                        networkClient.sendDrawSegment(
                            p1.x, p1.y, p2.x, p2.y,
                            WhiteboardClient.colorToHex(currentColor),
                            currentWidth
                        );
                    }
                } else {
                    currentDragPoint = e.getPoint();
                }
                repaint();
            }
        });
    }

    private void saveState(Object obj) {
        history.add(obj);
        redoStack.clear();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Draw strokes
        for (Stroke stroke : strokes) {
            g2.setColor(stroke.color);
            g2.setStroke(new BasicStroke(stroke.width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            for (int i = 1; i < stroke.points.size(); i++) {
                Point p1 = stroke.points.get(i - 1);
                Point p2 = stroke.points.get(i);
                g2.drawLine(p1.x, p1.y, p2.x, p2.y);
            }
        }

        // Draw shapes
        for (ShapeItem s : shapes) {
            drawShape(g2, s.type, s.start, s.end, s.color, s.width);
        }

        // Preview
        if (!currentShape.equals("Free Draw") && shapeStart != null && currentDragPoint != null) {
            drawShape(g2, currentShape, shapeStart, currentDragPoint, currentColor, currentWidth);
        }
    }

    private void drawShape(Graphics2D g2, String type, Point start, Point end, Color color, float width) {
        g2.setColor(color);
        g2.setStroke(new BasicStroke(width));

        int x = Math.min(start.x, end.x);
        int y = Math.min(start.y, end.y);
        int w = Math.abs(start.x - end.x);
        int h = Math.abs(start.y - end.y);

        switch (type) { // switch statement to handle type of shapes being drawn 
            case "Rectangle":
                g2.drawRect(x, y, w, h);
                break;

            case "Square":
                int size = Math.min(w, h);
                g2.drawRect(x, y, size, size);
                break;

            case "Circle":
                g2.drawOval(x, y, w, h);
                break;

            case "Triangle":
                int[] tx = {start.x, end.x, (start.x + end.x)/2};
                int[] ty = {end.y, end.y, start.y};
                g2.drawPolygon(tx, ty, 3);
                break;

            case "Diamond":
                int midX = (start.x + end.x)/2;
                int midY = (start.y + end.y)/2;
                int[] dx = {midX, end.x, midX, start.x};
                int[] dy = {start.y, midY, end.y, midY};
                g2.drawPolygon(dx, dy, 4);
                break;

            case "Star":
                int cx = (start.x + end.x)/2;
                int cy = (start.y + end.y)/2;
                int r = Math.min(w, h)/2;

                Polygon star = new Polygon();
                for (int i = 0; i < 10; i++) {
                    double angle = Math.PI / 5 * i;
                    int rad = (i % 2 == 0) ? r : r / 2;
                    int px = (int)(cx + Math.cos(angle) * rad);
                    int py = (int)(cy + Math.sin(angle) * rad);
                    star.addPoint(px, py);
                }
                g2.drawPolygon(star);
                break;
        }
    }

    public void undo() { // undo method 
        if (history.isEmpty()) return;

        Object last = history.remove(history.size() - 1);
        redoStack.add(last);

        if (last instanceof Stroke) {
            strokes.remove(last);
        } else if (last instanceof ShapeItem) {
            shapes.remove(last);
        }

        repaint();
    }

    public void redo() { // redo method 
        if (redoStack.isEmpty()) return;

        Object obj = redoStack.remove(redoStack.size() - 1);
        history.add(obj);

        if (obj instanceof Stroke) {
            strokes.add((Stroke) obj);
        } else if (obj instanceof ShapeItem) {
            shapes.add((ShapeItem) obj);
        }

        repaint();
    }

    public void setShapeMode(String mode) {
        currentShape = mode;
    }

    public ArrayList<ShapeItem> getShapes() { // getShapes method 
        return shapes;
    }

    public void loadShapes(ArrayList<ShapeItem> loaded) {
        shapes.clear();
        shapes.addAll(loaded);
        repaint();
    }

    public void setBrushSize(int size) {
        brushSize = size;
        currentWidth = brushSize;
    }

    public void setCurrentColor(Color c) {
        currentColor = c;
    }

    public void setEraserMode(boolean on) {
        if (on) {
            previousColor = currentColor;
            currentColor = Color.WHITE;
        } else {
            currentColor = previousColor;
        }
    }

    public void clearCanvas() {
        // Network: broadcast clear before wiping local state
        if (networkClient != null) networkClient.sendClear();
        strokes.clear();
        shapes.clear();
        history.clear();
        redoStack.clear();
        repaint();
    }

    public ArrayList<Stroke> getStrokes() {
        return strokes;
    }

    public void loadStrokes(ArrayList<Stroke> loaded) {
        strokes.clear();
        strokes.addAll(loaded);
        repaint();
    }

    // =========================================================================
    // Collaborative networking
    // =========================================================================

    /**
     * Attach (or detach) the network client.
     * Call with a live WhiteboardClient after joining a room,
     * and with null after leaving.
     */
    public void setNetworkClient(WhiteboardClient client) {
        this.networkClient = client;
    }

    /**
     * Apply a freehand segment received from a remote user.
     * Creates a minimal 2-point Stroke so it renders with the correct
     * color, width, and CAP_ROUND join — visually identical to local drawing.
     *
     * NOT added to the undo history: remote actions are not locally undoable.
     * Must be called on the Swing EDT.
     */
    public void applyRemoteSegment(int x1, int y1, int x2, int y2,
                                   java.awt.Color color, float width) {
        Stroke seg = new Stroke(color, width);
        seg.points.add(new Point(x1, y1));
        seg.points.add(new Point(x2, y2));
        strokes.add(seg); // directly into strokes list, bypassing saveState
        repaint();
    }

    /**
     * Apply a shape received from a remote user.
     * NOT added to undo history.
     * Must be called on the Swing EDT.
     */
    public void applyRemoteShape(String type, int startX, int startY,
                                  int endX, int endY,
                                  java.awt.Color color, float width) {
        shapes.add(new ShapeItem(type, new Point(startX, startY),
                                       new Point(endX, endY), color, width));
        repaint();
    }

    /**
     * Clear the canvas in response to a remote CLEAR event.
     * Does NOT send another CLEAR back to the server (avoids loops).
     * Must be called on the Swing EDT.
     */
    public void applyRemoteClear() {
        strokes.clear();
        shapes.clear();
        history.clear();
        redoStack.clear();
        repaint();
    }
}