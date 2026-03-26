import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import javax.swing.*;

class CanvasPanel extends JPanel {

    // ── inner data classes ──────────────────────────────────────────────────

    static class Stroke {
        ArrayList<Point> points = new ArrayList<>();
        Color color;
        float width;
        Stroke(Color c, float w) { color = c; width = w; }
    }

    static class ShapeItem {
        String type;
        Point start, end;
        Color color;
        float width;
        ShapeItem(String t, Point s, Point e, Color c, float w) {
            type = t; start = s; end = e; color = c; width = w;
        }
    }

    static class TextItem {
        String text;
        Rectangle bounds;
        Color color;
        int fontSize;
        TextItem(String t, Rectangle b, Color c, int fs) {
            text = t; bounds = new Rectangle(b); color = c; fontSize = fs;
        }
    }

    static class MoveAction {
        ArrayList<Object> items;
        int dx, dy;
        MoveAction(ArrayList<Object> items, int dx, int dy) {
            this.items = new ArrayList<>(items);
            this.dx = dx;
            this.dy = dy;
        }
    }

    // ── fields ───────────────────────────────────────────────────────────────

    private ArrayList<Stroke>    strokes   = new ArrayList<>();
    private ArrayList<ShapeItem> shapes    = new ArrayList<>();
    private ArrayList<TextItem>  texts     = new ArrayList<>();
    private ArrayList<Object>    drawOrder = new ArrayList<>();

    private ArrayList<Object> history   = new ArrayList<>();
    private ArrayList<Object> redoStack = new ArrayList<>();

    private Stroke currentStroke;
    private Color  currentColor  = Color.BLACK;
    private Color  previousColor = Color.BLACK;
    private float  brushSize     = 6.0f;
    private float  currentWidth  = brushSize;
    private int    fontSize      = 16;

    private String currentShape     = "Free Draw";
    private Point  shapeStart       = null;
    private Point  currentDragPoint = null;

    // select-mode state
    private Rectangle            marquee           = null;
    private ArrayList<Object>    selectedItems     = new ArrayList<>();
    private Point                lastDragPoint     = null;
    private boolean              draggingSelection = false;
    private int                  totalDragDx       = 0;
    private int                  totalDragDy       = 0;

    // inline text-box overlay
    private JTextArea  textOverlay   = null;
    private Rectangle  textBoxBounds = null;

    // ── constructor ──────────────────────────────────────────────────────────

    public CanvasPanel() {
        setBackground(Color.WHITE);
        setLayout(null);
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                currentWidth = brushSize;

                // ── SELECT ──
                if (currentShape.equals("Select")) {
                    if (!selectedItems.isEmpty() && selectionBounds().contains(e.getPoint())) {
                        draggingSelection = true;
                        lastDragPoint = e.getPoint();
                        totalDragDx = 0;
                        totalDragDy = 0;
                    } else {
                        selectedItems.clear();
                        marquee = new Rectangle(e.getX(), e.getY(), 0, 0);
                        draggingSelection = false;
                    }
                    repaint();
                    return;
                }

                // ── TEXT ──
                if (currentShape.equals("Text")) {
                    shapeStart = e.getPoint();
                    return;
                }

                // ── FREE DRAW ──
                if (currentShape.equals("Free Draw")) {
                    currentStroke = new Stroke(currentColor, currentWidth);
                    currentStroke.points.add(e.getPoint());
                    strokes.add(currentStroke);
                    drawOrder.add(currentStroke);
                    saveState(currentStroke);
                    return;
                }

                // ── SHAPES ──
                shapeStart = e.getPoint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {

                // ── SELECT: finish marquee or save move ──
                if (currentShape.equals("Select")) {
                    if (draggingSelection && (totalDragDx != 0 || totalDragDy != 0)) {
                        saveState(new MoveAction(selectedItems, totalDragDx, totalDragDy));
                    }
                    if (!draggingSelection && marquee != null) {
                        marquee = normalise(marquee);
                        collectSelected(marquee);
                        marquee = null;
                    }
                    draggingSelection = false;
                    lastDragPoint = null;
                    repaint();
                    return;
                }

                // ── TEXT: finish drag → show inline textarea ──
                if (currentShape.equals("Text") && shapeStart != null) {
                    Rectangle box = normalise(new Rectangle(
                        shapeStart.x, shapeStart.y,
                        e.getX() - shapeStart.x, e.getY() - shapeStart.y));
                    if (box.width > 20 && box.height > 20) showTextOverlay(box);
                    shapeStart = null;
                    currentDragPoint = null;
                    repaint();
                    return;
                }

                // ── SHAPES: commit ──
                if (!currentShape.equals("Free Draw") && shapeStart != null) {
                    ShapeItem si = new ShapeItem(
                        currentShape, shapeStart, e.getPoint(), currentColor, currentWidth);
                    shapes.add(si);
                    drawOrder.add(si);
                    saveState(si);
                    shapeStart = null;
                    currentDragPoint = null;
                    repaint();
                }
            }
        });

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {

                // ── SELECT ──
                if (currentShape.equals("Select")) {
                    if (draggingSelection && lastDragPoint != null) {
                        int dx = e.getX() - lastDragPoint.x;
                        int dy = e.getY() - lastDragPoint.y;
                        totalDragDx += dx;
                        totalDragDy += dy;
                        lastDragPoint = e.getPoint();
                        moveSelected(dx, dy);
                    } else if (marquee != null) {
                        marquee.width  = e.getX() - marquee.x;
                        marquee.height = e.getY() - marquee.y;
                    }
                    repaint();
                    return;
                }

                // ── FREE DRAW ──
                if (currentShape.equals("Free Draw") && currentStroke != null) {
                    currentStroke.points.add(e.getPoint());
                    repaint();
                    return;
                }

                // ── SHAPE / TEXT preview ──
                currentDragPoint = e.getPoint();
                repaint();
            }
        });
    }

    // ── inline text overlay ──────────────────────────────────────────────────

    private void showTextOverlay(Rectangle box) {
        commitTextOverlay();
        textBoxBounds = box;
        textOverlay = new JTextArea();
        textOverlay.setBounds(box.x, box.y, box.width, box.height);
        textOverlay.setFont(new Font("SansSerif", Font.PLAIN, fontSize));
        textOverlay.setForeground(currentColor);
        textOverlay.setBackground(new Color(255, 255, 240));
        textOverlay.setLineWrap(true);
        textOverlay.setWrapStyleWord(true);
        textOverlay.setBorder(BorderFactory.createDashedBorder(Color.GRAY, 2, 4));
        textOverlay.setOpaque(true);
        add(textOverlay);
        revalidate();
        textOverlay.requestFocusInWindow();

        textOverlay.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) commitTextOverlay();
            }
        });

        textOverlay.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) { commitTextOverlay(); }
        });
    }

    private void commitTextOverlay() {
        if (textOverlay == null) return;
        String text = textOverlay.getText().trim();
        if (!text.isEmpty()) {
            TextItem ti = new TextItem(text, textBoxBounds,
                textOverlay.getForeground(), fontSize);
            texts.add(ti);
            drawOrder.add(ti);
            saveState(ti);
        }
        remove(textOverlay);
        revalidate();
        repaint();
        textOverlay   = null;
        textBoxBounds = null;
    }

    // ── painting ─────────────────────────────────────────────────────────────

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // draw everything in insertion order so newer items paint on top
        for (Object obj : drawOrder) {
            if (obj instanceof Stroke) {
                Stroke stroke = (Stroke) obj;
                g2.setColor(stroke.color);
                g2.setStroke(new BasicStroke(stroke.width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (int i = 1; i < stroke.points.size(); i++) {
                    Point p1 = stroke.points.get(i - 1);
                    Point p2 = stroke.points.get(i);
                    g2.drawLine(p1.x, p1.y, p2.x, p2.y);
                }
            } else if (obj instanceof ShapeItem) {
                ShapeItem s = (ShapeItem) obj;
                drawShape(g2, s.type, s.start, s.end, s.color, s.width);
            } else if (obj instanceof TextItem) {
                TextItem ti = (TextItem) obj;
                g2.setColor(ti.color);
                g2.setFont(new Font("SansSerif", Font.PLAIN, ti.fontSize));
                drawWrappedText(g2, ti.text, ti.bounds);
            }
        }

        // selection highlight
        if (!selectedItems.isEmpty()) {
            Rectangle sb = selectionBounds();
            g2.setColor(new Color(0, 120, 255, 50));
            g2.fillRect(sb.x, sb.y, sb.width, sb.height);
            g2.setColor(new Color(0, 120, 255, 180));
            float[] dash = {6, 4};
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND, 0, dash, 0));
            g2.drawRect(sb.x, sb.y, sb.width, sb.height);
        }

        // rubber-band marquee
        if (marquee != null) {
            Rectangle r = normalise(marquee);
            g2.setColor(new Color(0, 120, 255, 40));
            g2.fillRect(r.x, r.y, r.width, r.height);
            g2.setColor(new Color(0, 120, 255, 180));
            float[] dash = {6, 4};
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND, 0, dash, 0));
            g2.drawRect(r.x, r.y, r.width, r.height);
        }

        // shape / text-box drag preview
        if (shapeStart != null && currentDragPoint != null) {
            if (currentShape.equals("Text")) {
                Rectangle preview = normalise(new Rectangle(
                    shapeStart.x, shapeStart.y,
                    currentDragPoint.x - shapeStart.x,
                    currentDragPoint.y - shapeStart.y));
                g2.setColor(new Color(255, 255, 180, 120));
                g2.fillRect(preview.x, preview.y, preview.width, preview.height);
                g2.setColor(Color.GRAY);
                float[] dash = {4, 4};
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND, 0, dash, 0));
                g2.drawRect(preview.x, preview.y, preview.width, preview.height);
            } else {
                drawShape(g2, currentShape, shapeStart, currentDragPoint,
                    currentColor, currentWidth);
            }
        }
    }

    // word-wrap text inside a bounding rectangle
    private void drawWrappedText(Graphics2D g2, String text, Rectangle box) {
        FontMetrics fm = g2.getFontMetrics();
        int lineHeight = fm.getHeight();
        int y = box.y + fm.getAscent();
        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();

        for (String word : words) {
            String test = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(test) > box.width && line.length() > 0) {
                if (y + lineHeight > box.y + box.height) break;
                g2.drawString(line.toString(), box.x + 2, y);
                y += lineHeight;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(test);
            }
        }
        if (line.length() > 0 && y <= box.y + box.height)
            g2.drawString(line.toString(), box.x + 2, y);
    }

    // ── shape drawing ────────────────────────────────────────────────────────

    private void drawShape(Graphics2D g2, String type, Point start, Point end,
                           Color color, float width) {
        g2.setColor(color);
        g2.setStroke(new BasicStroke(width));
        int x = Math.min(start.x, end.x), y = Math.min(start.y, end.y);
        int w = Math.abs(start.x - end.x), h = Math.abs(start.y - end.y);

        switch (type) {
            case "Rectangle": g2.drawRect(x, y, w, h); break;
            case "Square":    int s = Math.min(w, h); g2.drawRect(x, y, s, s); break;
            case "Circle":    g2.drawOval(x, y, w, h); break;
            case "Triangle":
                g2.drawPolygon(new int[]{start.x, end.x, (start.x + end.x) / 2},
                               new int[]{end.y, end.y, start.y}, 3); break;
            case "Diamond":
                int mx = (start.x + end.x) / 2, my = (start.y + end.y) / 2;
                g2.drawPolygon(new int[]{mx, end.x, mx, start.x},
                               new int[]{start.y, my, end.y, my}, 4); break;
            case "Star":
                int cx = (start.x + end.x) / 2, cy = (start.y + end.y) / 2;
                int r = Math.min(w, h) / 2;
                Polygon star = new Polygon();
                for (int i = 0; i < 10; i++) {
                    double angle = Math.PI / 5 * i;
                    int rad = (i % 2 == 0) ? r : r / 2;
                    star.addPoint((int)(cx + Math.cos(angle) * rad),
                                  (int)(cy + Math.sin(angle) * rad));
                }
                g2.drawPolygon(star); break;
        }
    }

    // ── select helpers ───────────────────────────────────────────────────────

    private void collectSelected(Rectangle r) {
        selectedItems.clear();
        for (Stroke st : strokes)
            for (Point p : st.points)
                if (r.contains(p)) { selectedItems.add(st); break; }
        for (ShapeItem si : shapes)
            if (r.intersects(new Rectangle(
                    Math.min(si.start.x, si.end.x), Math.min(si.start.y, si.end.y),
                    Math.abs(si.start.x - si.end.x), Math.abs(si.start.y - si.end.y))))
                selectedItems.add(si);
        for (TextItem ti : texts)
            if (r.intersects(ti.bounds)) selectedItems.add(ti);
    }

    private void moveSelected(int dx, int dy) {
        for (Object obj : selectedItems) translateItem(obj, dx, dy);
    }

    private void translateItem(Object obj, int dx, int dy) {
        if (obj instanceof Stroke) {
            for (Point p : ((Stroke) obj).points) p.translate(dx, dy);
        } else if (obj instanceof ShapeItem) {
            ShapeItem si = (ShapeItem) obj;
            si.start.translate(dx, dy);
            si.end.translate(dx, dy);
        } else if (obj instanceof TextItem) {
            ((TextItem) obj).bounds.translate(dx, dy);
        }
    }

    private Rectangle selectionBounds() {
        int x1 = Integer.MAX_VALUE, y1 = Integer.MAX_VALUE, x2 = 0, y2 = 0;
        for (Object obj : selectedItems) {
            Rectangle r = getBoundsOf(obj);
            if (r == null) continue;
            x1 = Math.min(x1, r.x);          y1 = Math.min(y1, r.y);
            x2 = Math.max(x2, r.x + r.width); y2 = Math.max(y2, r.y + r.height);
        }
        return new Rectangle(x1 - 4, y1 - 4, x2 - x1 + 8, y2 - y1 + 8);
    }

    private Rectangle getBoundsOf(Object obj) {
        if (obj instanceof ShapeItem) {
            ShapeItem si = (ShapeItem) obj;
            return new Rectangle(Math.min(si.start.x, si.end.x),
                Math.min(si.start.y, si.end.y),
                Math.abs(si.start.x - si.end.x), Math.abs(si.start.y - si.end.y));
        } else if (obj instanceof TextItem) {
            return ((TextItem) obj).bounds;
        } else if (obj instanceof Stroke) {
            Stroke st = (Stroke) obj;
            int x1 = Integer.MAX_VALUE, y1 = Integer.MAX_VALUE, x2 = 0, y2 = 0;
            for (Point p : st.points) {
                x1 = Math.min(x1, p.x); y1 = Math.min(y1, p.y);
                x2 = Math.max(x2, p.x); y2 = Math.max(y2, p.y);
            }
            return new Rectangle(x1, y1, x2 - x1, y2 - y1);
        }
        return null;
    }

    private Rectangle normalise(Rectangle r) {
        int x = r.width  < 0 ? r.x + r.width  : r.x;
        int y = r.height < 0 ? r.y + r.height : r.y;
        return new Rectangle(x, y, Math.abs(r.width), Math.abs(r.height));
    }

    // ── undo / redo ──────────────────────────────────────────────────────────

    private void saveState(Object obj) { history.add(obj); redoStack.clear(); }

    public void undo() {
        if (history.isEmpty()) return;
        Object last = history.remove(history.size() - 1);
        redoStack.add(last);
        if      (last instanceof Stroke)     { strokes.remove(last);  drawOrder.remove(last); }
        else if (last instanceof ShapeItem)  { shapes.remove(last);   drawOrder.remove(last); }
        else if (last instanceof TextItem)   { texts.remove(last);    drawOrder.remove(last); }
        else if (last instanceof MoveAction) {
            MoveAction ma = (MoveAction) last;
            for (Object obj : ma.items) translateItem(obj, -ma.dx, -ma.dy);
        }
        repaint();
    }

    public void redo() {
        if (redoStack.isEmpty()) return;
        Object obj = redoStack.remove(redoStack.size() - 1);
        history.add(obj);
        if      (obj instanceof Stroke)     { strokes.add((Stroke) obj);     drawOrder.add(obj); }
        else if (obj instanceof ShapeItem)  { shapes.add((ShapeItem) obj);   drawOrder.add(obj); }
        else if (obj instanceof TextItem)   { texts.add((TextItem) obj);     drawOrder.add(obj); }
        else if (obj instanceof MoveAction) {
            MoveAction ma = (MoveAction) obj;
            for (Object item : ma.items) translateItem(item, ma.dx, ma.dy);
        }
        repaint();
    }

    // ── public API ───────────────────────────────────────────────────────────

    public void setShapeMode(String mode) {
        currentShape = mode;
        selectedItems.clear();
        marquee = null;
        commitTextOverlay();
        setCursor(Cursor.getPredefinedCursor(
            mode.equals("Select") ? Cursor.DEFAULT_CURSOR : Cursor.CROSSHAIR_CURSOR));
        repaint();
    }

    public void setBrushSize(int size)   { brushSize = size; currentWidth = size; }
    public void setFontSize(int size)    { fontSize = size; }
public void setCurrentColor(Color c) {
    currentColor = c;

    if (textOverlay != null) {
        textOverlay.setForeground(c);
    }
}
    public void setEraserMode(boolean on) {
        if (on) { previousColor = currentColor; currentColor = Color.WHITE; }
        else    { currentColor = previousColor; }
    }

    public void clearCanvas() {
        strokes.clear(); shapes.clear(); texts.clear(); drawOrder.clear();
        history.clear(); redoStack.clear();
        selectedItems.clear(); marquee = null;
        commitTextOverlay();
        repaint();
    }

    public ArrayList<Stroke>    getStrokes() { return strokes; }
    public ArrayList<ShapeItem> getShapes()  { return shapes;  }
    public ArrayList<TextItem>  getTexts()   { return texts;   }

    public void loadStrokes(ArrayList<Stroke> loaded)   { strokes.clear(); strokes.addAll(loaded); drawOrder.addAll(loaded); repaint(); }
    public void loadShapes(ArrayList<ShapeItem> loaded) { shapes.clear();  shapes.addAll(loaded);  drawOrder.addAll(loaded); repaint(); }
    public void loadTexts(ArrayList<TextItem> loaded)   { texts.clear();   texts.addAll(loaded);   drawOrder.addAll(loaded); repaint(); }
}