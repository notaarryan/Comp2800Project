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

    private Stroke currentStroke;
    private Color currentColor = Color.BLACK;
    private Color previousColor = Color.BLACK;

    private float brushSize = 6.0f;
    private float currentWidth = brushSize;

    private String currentShape = "Free Draw";
    private Point shapeStart = null;
    private Point currentDragPoint = null;

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
                    shapeStart = null;
                    currentDragPoint = null;
                    repaint();
                }
            }
        });

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (currentShape.equals("Free Draw")) {
                    currentStroke.points.add(e.getPoint());
                } else {
                    currentDragPoint = e.getPoint();
                }
                repaint();
            }
        });
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

        switch (type) {
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

    public void setShapeMode(String mode) {
        currentShape = mode;
    }

    public ArrayList<ShapeItem> getShapes() {
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
        strokes.clear();
        shapes.clear();
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
}