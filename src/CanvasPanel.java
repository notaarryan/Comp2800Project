/*******
 Aryan Parmar
 Implement mouse-based drawing and Rendering it
 3/14/25
 *******************/
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

    private ArrayList<Stroke> strokes = new ArrayList<>();

    private Stroke currentStroke;
    private Color currentColor = Color.BLACK;
    private Color previousColor = Color.BLACK;

    private float brushSize = 6.0f;   // NEW
    private float currentWidth = brushSize;

    public CanvasPanel() {
        setBackground(Color.WHITE);
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                currentWidth = brushSize;
                currentStroke = new Stroke(currentColor, currentWidth);
                currentStroke.points.add(e.getPoint());
                strokes.add(currentStroke);
            }
        });

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                currentStroke.points.add(e.getPoint());
                repaint();
            }
        });
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        for (Stroke stroke : strokes) {
            g2.setColor(stroke.color);
            g2.setStroke(new BasicStroke(stroke.width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            for (int i = 1; i < stroke.points.size(); i++) {
                Point p1 = stroke.points.get(i - 1);
                Point p2 = stroke.points.get(i);
                g2.drawLine(p1.x, p1.y, p2.x, p2.y);
            }
        }
    }

    // new brush size setter 3/23/26 - Marko
    public void setBrushSize(int size) {
        brushSize = size;
        currentWidth = brushSize;
    }

    public void setCurrentColor(Color c) {
        currentColor = c;
        currentWidth = brushSize;
    }

    public void setEraserMode(boolean on) {
        if (on) {
            previousColor = currentColor;
            currentColor = Color.WHITE;
        } else {
            currentColor = previousColor;
        }
        currentWidth = brushSize;
    }

    public void clearCanvas() {
        strokes.clear();
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