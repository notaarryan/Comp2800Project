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

    private static class Stroke {
        ArrayList<Point> points = new ArrayList<>();
        Color color;
        Stroke(Color c){ color = c; }
    }
    private ArrayList<Stroke> strokes = new ArrayList<>();

    private Stroke currentStroke;
    private Color currentColor = Color.BLACK;

    public CanvasPanel() {
        setBackground(Color.WHITE);
        setVisible(true);

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                currentStroke = new Stroke(currentColor);
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
        for (Stroke stroke : strokes) {
            g.setColor(stroke.color);
            for (int i = 1; i < stroke.points.size(); i++) {
                Point p1 = stroke.points.get(i - 1);
                Point p2 = stroke.points.get(i);
                g.drawLine(p1.x, p1.y, p2.x, p2.y);
            }
        }
    }

    public void setCurrentColor(Color c) {
        currentColor = c;
    }
}