import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import javax.swing.*;

class CanvasPanel extends JPanel {

    static class Stroke {
        ArrayList<Point> points = new ArrayList<>();
        Color color; float width;
        Stroke(Color c, float w) { color = c; width = w; }
    }

    static class ShapeItem {
        String type; Point start, end; Color color; float width;
        ShapeItem(String t, Point s, Point e, Color c, float w) {
            type = t; start = s; end = e; color = c; width = w;
        }
    }

    static class TextItem {
        String text; Rectangle bounds; Color color; int fontSize;
        TextItem(String t, Rectangle b, Color c, int fs) {
            text = t; bounds = new Rectangle(b); color = c; fontSize = fs;
        }
    }

    static class StickyNote {
        String text; Point pos; int width = 160, height = 110; Color bgColor;
        StickyNote(String t, Point p, Color bg) { text = t; pos = new Point(p); bgColor = bg; }
    }

    static class ImageItem {
        BufferedImage image; Point pos; int width, height;
        ImageItem(BufferedImage img, Point p) {
            image = img; pos = new Point(p); width = img.getWidth(); height = img.getHeight();
        }
    }

    static class MoveAction {
        ArrayList<Object> items; int dx, dy;
        MoveAction(ArrayList<Object> items, int dx, int dy) {
            this.items = new ArrayList<>(items); this.dx = dx; this.dy = dy;
        }
    }

    static class Page {
        String name;
        ArrayList<Stroke>     strokes   = new ArrayList<>();
        ArrayList<ShapeItem>  shapes    = new ArrayList<>();
        ArrayList<TextItem>   texts     = new ArrayList<>();
        ArrayList<StickyNote> stickies  = new ArrayList<>();
        ArrayList<ImageItem>  images    = new ArrayList<>();
        ArrayList<Object>     drawOrder = new ArrayList<>();
        ArrayList<Object>     history   = new ArrayList<>();
        ArrayList<Object>     redoStack = new ArrayList<>();
        Page(String name) { this.name = name; }
    }

    private final ArrayList<Page> pages = new ArrayList<>();
    private int currentPageIdx = 0;
    private Page pg() { return pages.get(currentPageIdx); }
    private double vpX = 0, vpY = 0, vpScale = 1.0;
    private Point  panStart  = null;
    private double panVpX0, panVpY0;
    private Stroke currentStroke;
    private Color  currentColor   = Color.BLACK;
    private Color  previousColor  = Color.BLACK;
    private float  brushSize      = 6.0f;
    private float  currentWidth   = brushSize;
    private int    fontSize       = 16;
    private String currentShape   = "Free Draw";
    private Point  shapeStart     = null;
    private Point  currentDragPoint = null;
    private Color  stickyColor    = new Color(255, 255, 180);
    private Rectangle         marquee           = null;
    private ArrayList<Object> selectedItems     = new ArrayList<>();
    private Point             lastDragPoint     = null;
    private boolean           draggingSelection = false;
    private int               totalDragDx = 0, totalDragDy = 0;
    private JTextArea textOverlay   = null;
    private Rectangle textBoxBounds = null;
    private WhiteboardClient       networkClient = null;
    private HashMap<String, Point> remoteCursors = new HashMap<>();
    private String                 localUsername = null;
    private Runnable onPagesChanged = null;
    private boolean spaceDown = false;

    private Point s2c(int sx, int sy) {
        return new Point((int)((sx - vpX) / vpScale), (int)((sy - vpY) / vpScale));
    }
    private Point s2c(Point p) { return s2c(p.x, p.y); }

    private Point c2s(int cx, int cy) {
        return new Point((int)(cx * vpScale + vpX), (int)(cy * vpScale + vpY));
    }
    private Point c2s(Point p) { return c2s(p.x, p.y); }

    public CanvasPanel() {
        pages.add(new Page("Page 1"));
        setBackground(Color.WHITE);
        setLayout(null);
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        setupMouseListeners();
        setupZoomPan();
        setupPaste();
        setupSpacePan();
        setupDeleteKey();
    }

    private void setupMouseListeners() {
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {

                if (e.getButton() == MouseEvent.BUTTON2 || spaceDown) {
                    panStart = e.getPoint();
                    panVpX0 = vpX; panVpY0 = vpY;
                    return;
                }

                currentWidth = brushSize;
                Point cp = s2c(e.getPoint());

                if (currentShape.equals("Select")) {
                    if (!selectedItems.isEmpty() && selectionBounds().contains(cp)) {

                        draggingSelection = true;
                        lastDragPoint = cp;
                        totalDragDx = 0; totalDragDy = 0;
                    } else {

                        Object hit = hitTest(cp);
                        if (hit != null) {
                            selectedItems.clear();
                            selectedItems.add(hit);
                            draggingSelection = true;
                            lastDragPoint = cp;
                            totalDragDx = 0; totalDragDy = 0;
                        } else {
                            selectedItems.clear();
                            marquee = new Rectangle(cp.x, cp.y, 0, 0);
                            draggingSelection = false;
                        }
                    }
                    repaint(); return;
                }

                if (currentShape.equals("Sticky Note")) {
                    showStickyNoteDialog(cp); return;
                }

                if (currentShape.equals("Text")) { shapeStart = cp; return; }

                if (currentShape.equals("Free Draw")) {
                    currentStroke = new Stroke(currentColor, currentWidth);
                    currentStroke.points.add(cp);
                    pg().strokes.add(currentStroke);
                    pg().drawOrder.add(currentStroke);
                    saveState(currentStroke); return;
                }
                shapeStart = cp;
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON2 || (spaceDown && panStart != null)) { panStart = null; return; }
                Point cp = s2c(e.getPoint());
                if (currentShape.equals("Select")) {
                    if (draggingSelection && (totalDragDx != 0 || totalDragDy != 0))
                        saveState(new MoveAction(selectedItems, totalDragDx, totalDragDy));
                    if (!draggingSelection && marquee != null) {
                        marquee = normalise(marquee);
                        collectSelected(marquee);
                        marquee = null;
                    }
                    draggingSelection = false; lastDragPoint = null;
                    repaint(); return;
                }

                if (currentShape.equals("Text") && shapeStart != null) {
                    Rectangle box = normalise(new Rectangle(
                        shapeStart.x, shapeStart.y,
                        cp.x - shapeStart.x, cp.y - shapeStart.y));
                    if (box.width > 20 && box.height > 20) showTextOverlay(box);
                    shapeStart = null; currentDragPoint = null; repaint(); return;
                }

                if (!currentShape.equals("Free Draw") && !currentShape.equals("Sticky Note") && shapeStart != null) {
                    ShapeItem si = new ShapeItem(currentShape, shapeStart, cp, currentColor, currentWidth);
                    pg().shapes.add(si); pg().drawOrder.add(si); saveState(si);
                    if (networkClient != null)
                        networkClient.sendShape(si.type, si.start.x, si.start.y, si.end.x, si.end.y,
                            WhiteboardClient.colorToHex(si.color), si.width);
                    shapeStart = null; currentDragPoint = null; repaint();

                } else if (currentShape.equals("Free Draw") && currentStroke != null) {
                    if (networkClient != null && currentStroke.points.size() == 1) {
                        Point p = currentStroke.points.get(0);
                        networkClient.sendDrawSegment(p.x, p.y, p.x, p.y,
                            WhiteboardClient.colorToHex(currentColor), currentWidth);
                    }
                }
            }
        });

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if ((SwingUtilities.isMiddleMouseButton(e) || spaceDown) && panStart != null) {
                    vpX = panVpX0 + (e.getX() - panStart.x);
                    vpY = panVpY0 + (e.getY() - panStart.y);
                    repositionTextOverlay();
                    repaint(); return;
                }
                Point cp = s2c(e.getPoint());
                if (currentShape.equals("Select")) {
                    if (draggingSelection && lastDragPoint != null) {
                        int dx = cp.x - lastDragPoint.x, dy = cp.y - lastDragPoint.y;
                        totalDragDx += dx; totalDragDy += dy;
                        lastDragPoint = cp;
                        moveSelected(dx, dy);
                    } else if (marquee != null) {
                        marquee.width  = cp.x - marquee.x;
                        marquee.height = cp.y - marquee.y;
                    }
                    repaint(); return;
                }
                if (currentShape.equals("Free Draw") && currentStroke != null) {
                    currentStroke.points.add(cp);
                    if (networkClient != null && currentStroke.points.size() >= 2) {
                        int n = currentStroke.points.size();
                        Point p1 = currentStroke.points.get(n - 2);
                        Point p2 = currentStroke.points.get(n - 1);
                        networkClient.sendDrawSegment(p1.x, p1.y, p2.x, p2.y,
                            WhiteboardClient.colorToHex(currentColor), currentWidth);
                    }
                    repaint(); return;
                }
                currentDragPoint = cp;
                if (networkClient != null && localUsername != null)
                    networkClient.sendCursor(localUsername, cp.x, cp.y);
                repaint();
            }
            @Override
            public void mouseMoved(MouseEvent e) {
                if (networkClient != null && localUsername != null) {
                    Point cp = s2c(e.getPoint());
                    networkClient.sendCursor(localUsername, cp.x, cp.y);
                }
            }
        });
    }

    private void setupZoomPan() {
        addMouseWheelListener(e -> {
            if (e.isControlDown()) {
                double factor   = e.getWheelRotation() < 0 ? 1.12 : 0.89;
                double oldScale = vpScale;
                vpScale = Math.max(0.08, Math.min(8.0, vpScale * factor));
                double mx = e.getX(), my = e.getY();
                vpX = mx - (mx - vpX) * (vpScale / oldScale);
                vpY = my - (my - vpY) * (vpScale / oldScale);
                repositionTextOverlay();
                repaint();
            }
        });
    }

    public void zoomIn()    { zoom(1.2); }
    public void zoomOut()   { zoom(0.85); }
    public void resetView() { vpX = 0; vpY = 0; vpScale = 1.0; repositionTextOverlay(); repaint(); }
    private void zoom(double factor) {
        double cx = getWidth() / 2.0, cy = getHeight() / 2.0;
        double oldScale = vpScale;
        vpScale = Math.max(0.08, Math.min(8.0, vpScale * factor));
        vpX = cx - (cx - vpX) * (vpScale / oldScale);
        vpY = cy - (cy - vpY) * (vpScale / oldScale);
        repositionTextOverlay();
        repaint();
    }

    private void setupDeleteKey() {
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke("DELETE"), "deleteSelected");
        getActionMap().put("deleteSelected", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                java.awt.Component focused = java.awt.KeyboardFocusManager
                    .getCurrentKeyboardFocusManager().getFocusOwner();
                if (focused instanceof javax.swing.text.JTextComponent) return;
                if (selectedItems.isEmpty()) return;
                for (Object obj : selectedItems) {
                    pg().drawOrder.remove(obj);
                    if      (obj instanceof Stroke)     pg().strokes.remove(obj);
                    else if (obj instanceof ShapeItem)  pg().shapes.remove(obj);
                    else if (obj instanceof TextItem)   pg().texts.remove(obj);
                    else if (obj instanceof StickyNote) pg().stickies.remove(obj);
                    else if (obj instanceof ImageItem)  pg().images.remove(obj);
                }
                selectedItems.clear();
                repaint();
            }
        });
    }

    private void setupSpacePan() {
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke("SPACE"), "spaceDown");
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke("released SPACE"), "spaceUp");
        getActionMap().put("spaceDown", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (!spaceDown) {
                    spaceDown = true;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
            }
        });
        getActionMap().put("spaceUp", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                spaceDown = false;
                panStart = null;
                setCursor(Cursor.getPredefinedCursor(
                    currentShape.equals("Select") ? Cursor.DEFAULT_CURSOR : Cursor.CROSSHAIR_CURSOR));
            }
        });
    }

    private void setupPaste() {
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke("control V"), "paste");
        getActionMap().put("paste", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                pasteFromClipboard();
            }
        });
    }

    public void pasteFromClipboard() {
        try {
            java.awt.datatransfer.Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (cb.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.imageFlavor)) {
                java.awt.Image img = (java.awt.Image) cb.getData(java.awt.datatransfer.DataFlavor.imageFlavor);
                placeImage(toBufferedImage(img));
            }
        } catch (Exception ignored) {}
    }

    public void importImage(java.io.File file) {
        try {
            BufferedImage img = javax.imageio.ImageIO.read(file);
            if (img != null) placeImage(img);
        } catch (Exception ignored) {}
    }

    private void placeImage(BufferedImage img) {

        int maxDim = 900;
        if (img.getWidth() > maxDim || img.getHeight() > maxDim) {
            double sc = (double) maxDim / Math.max(img.getWidth(), img.getHeight());
            int nw = (int)(img.getWidth() * sc), nh = (int)(img.getHeight() * sc);
            BufferedImage scaled = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = scaled.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(img, 0, 0, nw, nh, null);
            g2.dispose();
            img = scaled;
        }
        int cx = (int)((getWidth()  / 2.0 - vpX) / vpScale) - img.getWidth()  / 2;
        int cy = (int)((getHeight() / 2.0 - vpY) / vpScale) - img.getHeight() / 2;
        ImageItem ii = new ImageItem(img, new Point(cx, cy));
        pg().images.add(ii); pg().drawOrder.add(ii); saveState(ii);
        repaint();
    }

    private static BufferedImage toBufferedImage(java.awt.Image img) {
        if (img instanceof BufferedImage) return (BufferedImage) img;
        BufferedImage bi = new BufferedImage(
            img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = bi.createGraphics();
        g2.drawImage(img, 0, 0, null); g2.dispose();
        return bi;
    }

    private static final Color[] STICKY_COLORS = {
        new Color(255, 255, 180), new Color(180, 220, 255),
        new Color(180, 255, 200), new Color(255, 200, 220), new Color(255, 220, 180)
    };
    private static final String[] STICKY_COLOR_NAMES = {"Yellow","Blue","Green","Pink","Orange"};

    private void showStickyNoteDialog(Point canvasPos) {
        JTextArea ta = new JTextArea(4, 22);
        ta.setLineWrap(true); ta.setWrapStyleWord(true);
        JComboBox<String> colorBox = new JComboBox<>(STICKY_COLOR_NAMES);
        JPanel colorRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        colorRow.add(new JLabel("Color:")); colorRow.add(colorBox);
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.add(new JScrollPane(ta), BorderLayout.CENTER);
        panel.add(colorRow, BorderLayout.SOUTH);

        if (JOptionPane.showConfirmDialog(this, panel, "Add Sticky Note",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            String text = ta.getText().trim();
            if (!text.isEmpty()) {
                StickyNote sn = new StickyNote(text, canvasPos, STICKY_COLORS[colorBox.getSelectedIndex()]);
                pg().stickies.add(sn); pg().drawOrder.add(sn); saveState(sn);
                repaint();
            }
        }
    }

    private void showTextOverlay(Rectangle canvasBox) {
        commitTextOverlay();
        textBoxBounds = canvasBox;
        Point sp = c2s(canvasBox.x, canvasBox.y);
        int sw = Math.max(80,  (int)(canvasBox.width  * vpScale));
        int sh = Math.max(30,  (int)(canvasBox.height * vpScale));

        textOverlay = new JTextArea();
        textOverlay.setBounds(sp.x, sp.y, sw, sh);
        textOverlay.setFont(new Font("SansSerif", Font.PLAIN, Math.max(8, (int)(fontSize * vpScale))));
        textOverlay.setForeground(currentColor);
        textOverlay.setBackground(new Color(255, 255, 240));
        textOverlay.setLineWrap(true); textOverlay.setWrapStyleWord(true);
        textOverlay.setBorder(BorderFactory.createDashedBorder(Color.GRAY, 2, 4));
        textOverlay.setOpaque(true);
        add(textOverlay); revalidate(); textOverlay.requestFocusInWindow();

        textOverlay.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) commitTextOverlay();
            }
        });
        textOverlay.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { commitTextOverlay(); }
        });
    }

    private void repositionTextOverlay() {
        if (textOverlay == null || textBoxBounds == null) return;
        Point sp = c2s(textBoxBounds.x, textBoxBounds.y);
        int sw = Math.max(80,  (int)(textBoxBounds.width  * vpScale));
        int sh = Math.max(30,  (int)(textBoxBounds.height * vpScale));
        textOverlay.setBounds(sp.x, sp.y, sw, sh);
        textOverlay.setFont(new Font("SansSerif", Font.PLAIN, Math.max(8, (int)(fontSize * vpScale))));
        revalidate();
    }

    private void commitTextOverlay() {
        if (textOverlay == null) return;
        String text = textOverlay.getText().trim();
        if (!text.isEmpty()) {
            TextItem ti = new TextItem(text, textBoxBounds, textOverlay.getForeground(), fontSize);
            pg().texts.add(ti); pg().drawOrder.add(ti); saveState(ti);
        }
        remove(textOverlay); revalidate(); repaint();
        textOverlay = null; textBoxBounds = null;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.translate(vpX, vpY);
        g2.scale(vpScale, vpScale);

        for (Object obj : pg().drawOrder) {
            if (obj instanceof ImageItem) {
                ImageItem ii = (ImageItem) obj;
                g2.drawImage(ii.image, ii.pos.x, ii.pos.y, ii.width, ii.height, null);

            } else if (obj instanceof Stroke) {
                Stroke stroke = (Stroke) obj;
                g2.setColor(stroke.color);
                g2.setStroke(new BasicStroke(stroke.width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                if (stroke.points.size() == 1) {
                    int r = Math.max(1, (int)(stroke.width / 2));
                    Point p = stroke.points.get(0);
                    g2.fillOval(p.x - r, p.y - r, r * 2, r * 2);
                } else {
                    for (int i = 1; i < stroke.points.size(); i++) {
                        Point p1 = stroke.points.get(i - 1), p2 = stroke.points.get(i);
                        g2.drawLine(p1.x, p1.y, p2.x, p2.y);
                    }
                }
            } else if (obj instanceof ShapeItem) {
                ShapeItem s = (ShapeItem) obj;
                drawShape(g2, s.type, s.start, s.end, s.color, s.width);

            } else if (obj instanceof TextItem) {
                TextItem ti = (TextItem) obj;
                g2.setColor(ti.color);
                g2.setFont(new Font("SansSerif", Font.PLAIN, ti.fontSize));
                drawWrappedText(g2, ti.text, ti.bounds);

            } else if (obj instanceof StickyNote) {
                drawStickyNote(g2, (StickyNote) obj);
            }
        }

        if (!selectedItems.isEmpty()) drawSelectionHighlight(g2);
        if (marquee != null) drawMarquee(g2);

        if (shapeStart != null && currentDragPoint != null) {
            if (currentShape.equals("Text")) {
                Rectangle preview = normalise(new Rectangle(
                    shapeStart.x, shapeStart.y,
                    currentDragPoint.x - shapeStart.x, currentDragPoint.y - shapeStart.y));
                g2.setColor(new Color(255, 255, 180, 120));
                g2.fillRect(preview.x, preview.y, preview.width, preview.height);
                g2.setColor(Color.GRAY);
                float[] dash = {4, 4};
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, dash, 0));
                g2.drawRect(preview.x, preview.y, preview.width, preview.height);
            } else {
                drawShape(g2, currentShape, shapeStart, currentDragPoint, currentColor, currentWidth);
            }
        }

        g2.dispose();

        Graphics2D gUI = (Graphics2D) g;
        gUI.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        gUI.setFont(new Font("SansSerif", Font.BOLD, 11));
        for (java.util.Map.Entry<String, Point> entry : remoteCursors.entrySet()) {
            String name  = entry.getKey();
            Point  cp    = entry.getValue();
            Point  sp    = c2s(cp);
            Color  cc    = getCursorColor(name);
            gUI.setColor(cc);
            gUI.fillOval(sp.x - 5, sp.y - 5, 10, 10);
            gUI.setColor(cc.darker());
            gUI.drawOval(sp.x - 5, sp.y - 5, 10, 10);
            gUI.setColor(cc);
            gUI.drawString(name, sp.x + 8, sp.y - 4);
        }

        if (vpScale != 1.0) {
            String zoomStr = Math.round(vpScale * 100) + "%";
            gUI.setFont(new Font("SansSerif", Font.PLAIN, 11));
            gUI.setColor(new Color(0, 0, 0, 100));
            gUI.drawString(zoomStr, getWidth() - 40, getHeight() - 8);
        }
    }

    private void drawStickyNote(Graphics2D g2, StickyNote sn) {

        g2.setColor(new Color(0, 0, 0, 40));
        g2.fillRect(sn.pos.x + 4, sn.pos.y + 4, sn.width, sn.height);

        g2.setColor(sn.bgColor);
        g2.fillRect(sn.pos.x, sn.pos.y, sn.width, sn.height);

        Color darker = sn.bgColor.darker();
        g2.setColor(darker);
        g2.fillPolygon(
            new int[]{sn.pos.x + sn.width - 14, sn.pos.x + sn.width, sn.pos.x + sn.width},
            new int[]{sn.pos.y, sn.pos.y, sn.pos.y + 14}, 3);

        g2.setColor(darker);
        g2.setStroke(new BasicStroke(1.2f));
        g2.drawRect(sn.pos.x, sn.pos.y, sn.width, sn.height);

        g2.setColor(new Color(50, 40, 0));
        g2.setFont(new Font("SansSerif", Font.PLAIN, 13));
        drawWrappedText(g2, sn.text, new Rectangle(sn.pos.x + 6, sn.pos.y + 6, sn.width - 12, sn.height - 12));
    }

    private void drawSelectionHighlight(Graphics2D g2) {
        Rectangle sb = selectionBounds();
        g2.setColor(new Color(0, 120, 255, 50));
        g2.fillRect(sb.x, sb.y, sb.width, sb.height);
        g2.setColor(new Color(0, 120, 255, 180));
        float[] dash = {6, 4};
        g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, dash, 0));
        g2.drawRect(sb.x, sb.y, sb.width, sb.height);
    }

    private void drawMarquee(Graphics2D g2) {
        Rectangle r = normalise(marquee);
        g2.setColor(new Color(0, 120, 255, 40));
        g2.fillRect(r.x, r.y, r.width, r.height);
        g2.setColor(new Color(0, 120, 255, 180));
        float[] dash = {6, 4};
        g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, dash, 0));
        g2.drawRect(r.x, r.y, r.width, r.height);
    }

    private void drawWrappedText(Graphics2D g2, String text, Rectangle box) {
        FontMetrics fm = g2.getFontMetrics();
        int lineH = fm.getHeight();
        int y = box.y + fm.getAscent();
        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String test = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(test) > box.width && line.length() > 0) {
                if (y + lineH > box.y + box.height) break;
                g2.drawString(line.toString(), box.x + 2, y);
                y += lineH; line = new StringBuilder(word);
            } else { line = new StringBuilder(test); }
        }
        if (line.length() > 0 && y <= box.y + box.height)
            g2.drawString(line.toString(), box.x + 2, y);
    }

    private void drawShape(Graphics2D g2, String type, Point start, Point end,
                           Color color, float width) {
        g2.setColor(color);
        g2.setStroke(new BasicStroke(width));
        int x = Math.min(start.x, end.x), y = Math.min(start.y, end.y);
        int w = Math.abs(start.x - end.x), h = Math.abs(start.y - end.y);
        switch (type) {
            case "Rectangle" -> g2.drawRect(x, y, w, h);
            case "Square"    -> { int s = Math.min(w, h); g2.drawRect(x, y, s, s); }
            case "Circle"    -> g2.drawOval(x, y, w, h);
            case "Triangle"  -> g2.drawPolygon(
                new int[]{start.x, end.x, (start.x+end.x)/2},
                new int[]{end.y, end.y, start.y}, 3);
            case "Diamond"   -> {
                int mx = (start.x+end.x)/2, my = (start.y+end.y)/2;
                g2.drawPolygon(new int[]{mx, end.x, mx, start.x}, new int[]{start.y, my, end.y, my}, 4);
            }
            case "Star"      -> {
                int cx2 = (start.x+end.x)/2, cy2 = (start.y+end.y)/2;
                int r = Math.min(w, h) / 2;
                Polygon star = new Polygon();
                for (int i = 0; i < 10; i++) {
                    double angle = Math.PI / 5 * i;
                    int rad = (i % 2 == 0) ? r : r / 2;
                    star.addPoint((int)(cx2 + Math.cos(angle)*rad), (int)(cy2 + Math.sin(angle)*rad));
                }
                g2.drawPolygon(star);
            }
        }
    }

    private Object hitTest(Point cp) {
        ArrayList<Object> order = pg().drawOrder;
        for (int i = order.size() - 1; i >= 0; i--) {
            Object obj = order.get(i);
            if (obj instanceof StickyNote) {
                StickyNote sn = (StickyNote) obj;
                if (new Rectangle(sn.pos.x, sn.pos.y, sn.width, sn.height).contains(cp)) return obj;
            } else if (obj instanceof ImageItem) {
                ImageItem ii = (ImageItem) obj;
                if (new Rectangle(ii.pos.x, ii.pos.y, ii.width, ii.height).contains(cp)) return obj;
            } else if (obj instanceof TextItem) {
                if (((TextItem) obj).bounds.contains(cp)) return obj;
            } else if (obj instanceof ShapeItem) {
                ShapeItem si = (ShapeItem) obj;
                Rectangle r = new Rectangle(
                    Math.min(si.start.x, si.end.x), Math.min(si.start.y, si.end.y),
                    Math.abs(si.start.x - si.end.x) + 1, Math.abs(si.start.y - si.end.y) + 1);
                if (r.contains(cp)) return obj;
            }
        }
        return null;
    }

    private void collectSelected(Rectangle r) {
        selectedItems.clear();
        for (Stroke st : pg().strokes)
            for (Point p : st.points)
                if (r.contains(p)) { selectedItems.add(st); break; }
        for (ShapeItem si : pg().shapes)
            if (r.intersects(new Rectangle(
                    Math.min(si.start.x, si.end.x), Math.min(si.start.y, si.end.y),
                    Math.abs(si.start.x - si.end.x), Math.abs(si.start.y - si.end.y))))
                selectedItems.add(si);
        for (TextItem ti : pg().texts)    if (r.intersects(ti.bounds)) selectedItems.add(ti);
        for (StickyNote sn : pg().stickies)
            if (r.intersects(new Rectangle(sn.pos.x, sn.pos.y, sn.width, sn.height))) selectedItems.add(sn);
        for (ImageItem ii : pg().images)
            if (r.intersects(new Rectangle(ii.pos.x, ii.pos.y, ii.width, ii.height))) selectedItems.add(ii);
    }

    private void moveSelected(int dx, int dy) {
        for (Object obj : selectedItems) translateItem(obj, dx, dy);
    }

    private void translateItem(Object obj, int dx, int dy) {
        if      (obj instanceof Stroke)     { for (Point p : ((Stroke)obj).points) p.translate(dx, dy); }
        else if (obj instanceof ShapeItem)  { ShapeItem si = (ShapeItem)obj; si.start.translate(dx,dy); si.end.translate(dx,dy); }
        else if (obj instanceof TextItem)   { ((TextItem)obj).bounds.translate(dx, dy); }
        else if (obj instanceof StickyNote) { ((StickyNote)obj).pos.translate(dx, dy); }
        else if (obj instanceof ImageItem)  { ((ImageItem)obj).pos.translate(dx, dy); }
    }

    private Rectangle selectionBounds() {
        int x1 = Integer.MAX_VALUE, y1 = Integer.MAX_VALUE, x2 = Integer.MIN_VALUE, y2 = Integer.MIN_VALUE;
        for (Object obj : selectedItems) {
            Rectangle r = getBoundsOf(obj);
            if (r == null) continue;
            x1 = Math.min(x1, r.x);           y1 = Math.min(y1, r.y);
            x2 = Math.max(x2, r.x + r.width); y2 = Math.max(y2, r.y + r.height);
        }
        return new Rectangle(x1 - 4, y1 - 4, x2 - x1 + 8, y2 - y1 + 8);
    }

    private Rectangle getBoundsOf(Object obj) {
        if      (obj instanceof ShapeItem)  { ShapeItem si = (ShapeItem)obj;
            return new Rectangle(Math.min(si.start.x,si.end.x), Math.min(si.start.y,si.end.y),
                Math.abs(si.start.x-si.end.x), Math.abs(si.start.y-si.end.y)); }
        else if (obj instanceof TextItem)   { return ((TextItem)obj).bounds; }
        else if (obj instanceof StickyNote) { StickyNote sn = (StickyNote)obj;
            return new Rectangle(sn.pos.x, sn.pos.y, sn.width, sn.height); }
        else if (obj instanceof ImageItem)  { ImageItem ii = (ImageItem)obj;
            return new Rectangle(ii.pos.x, ii.pos.y, ii.width, ii.height); }
        else if (obj instanceof Stroke)     { Stroke st = (Stroke)obj;
            int x1=Integer.MAX_VALUE, y1=Integer.MAX_VALUE, x2=Integer.MIN_VALUE, y2=Integer.MIN_VALUE;
            for (Point p : st.points) { x1=Math.min(x1,p.x); y1=Math.min(y1,p.y); x2=Math.max(x2,p.x); y2=Math.max(y2,p.y); }
            if (x2 == Integer.MIN_VALUE) return null;
            return new Rectangle(x1, y1, x2-x1, y2-y1); }
        return null;
    }

    private Rectangle normalise(Rectangle r) {
        int x = r.width  < 0 ? r.x + r.width  : r.x;
        int y = r.height < 0 ? r.y + r.height : r.y;
        return new Rectangle(x, y, Math.abs(r.width), Math.abs(r.height));
    }

    private void saveState(Object obj) { pg().history.add(obj); pg().redoStack.clear(); }

    public void undo() {
        if (pg().history.isEmpty()) return;
        Object last = pg().history.remove(pg().history.size() - 1);
        pg().redoStack.add(last);
        if      (last instanceof Stroke)     { pg().strokes.remove(last);  pg().drawOrder.remove(last); }
        else if (last instanceof ShapeItem)  { pg().shapes.remove(last);   pg().drawOrder.remove(last); }
        else if (last instanceof TextItem)   { pg().texts.remove(last);    pg().drawOrder.remove(last); }
        else if (last instanceof StickyNote) { pg().stickies.remove(last); pg().drawOrder.remove(last); }
        else if (last instanceof ImageItem)  { pg().images.remove(last);   pg().drawOrder.remove(last); }
        else if (last instanceof MoveAction) {
            MoveAction ma = (MoveAction) last;
            for (Object obj : ma.items) translateItem(obj, -ma.dx, -ma.dy);
        }
        repaint();
        if (networkClient != null) networkClient.sendFullSync(pg().strokes, pg().shapes);
    }

    public void redo() {
        if (pg().redoStack.isEmpty()) return;
        Object obj = pg().redoStack.remove(pg().redoStack.size() - 1);
        pg().history.add(obj);
        if      (obj instanceof Stroke)     { pg().strokes.add((Stroke)obj);     pg().drawOrder.add(obj); }
        else if (obj instanceof ShapeItem)  { pg().shapes.add((ShapeItem)obj);   pg().drawOrder.add(obj); }
        else if (obj instanceof TextItem)   { pg().texts.add((TextItem)obj);     pg().drawOrder.add(obj); }
        else if (obj instanceof StickyNote) { pg().stickies.add((StickyNote)obj);pg().drawOrder.add(obj); }
        else if (obj instanceof ImageItem)  { pg().images.add((ImageItem)obj);   pg().drawOrder.add(obj); }
        else if (obj instanceof MoveAction) {
            MoveAction ma = (MoveAction) obj;
            for (Object item : ma.items) translateItem(item, ma.dx, ma.dy);
        }
        repaint();
        if (networkClient != null) networkClient.sendFullSync(pg().strokes, pg().shapes);
    }

    public void addPage() {
        pages.add(new Page("Page " + (pages.size() + 1)));
        if (onPagesChanged != null) onPagesChanged.run();
    }

    public void removePage(int idx) {
        if (pages.size() <= 1) return;
        commitTextOverlay();
        pages.remove(idx);
        if (currentPageIdx >= pages.size()) currentPageIdx = pages.size() - 1;
        if (onPagesChanged != null) onPagesChanged.run();
        repaint();
    }

    public void switchPage(int idx) {
        if (idx < 0 || idx >= pages.size() || idx == currentPageIdx) return;
        commitTextOverlay();
        selectedItems.clear();
        currentPageIdx = idx;
        if (onPagesChanged != null) onPagesChanged.run();
        repaint();
    }

    public int     getPageCount()         { return pages.size(); }
    public int     getCurrentPageIndex()  { return currentPageIdx; }
    public String  getPageName(int idx)   { return pages.get(idx).name; }
    public void    setOnPagesChanged(Runnable r) { onPagesChanged = r; }

    public void setShapeMode(String mode) {
        currentShape = mode;
        selectedItems.clear(); marquee = null;
        commitTextOverlay();
        setCursor(Cursor.getPredefinedCursor(
            mode.equals("Select") ? Cursor.DEFAULT_CURSOR : Cursor.CROSSHAIR_CURSOR));
        repaint();
    }

    public void setStickyColor(Color c) { stickyColor = c; }
    public void setBrushSize(int size)  { brushSize = size; currentWidth = size; }
    public void setFontSize(int size)   { fontSize = size; }

    public void setCurrentColor(Color c) {
        currentColor = c;
        if (textOverlay != null) textOverlay.setForeground(c);
    }

    public void setEraserMode(boolean on) {
        if (on) { previousColor = currentColor; currentColor = Color.WHITE; }
        else    { currentColor = previousColor; }
    }

    public void clearCanvas() {
        if (networkClient != null) networkClient.sendClear();
        pg().strokes.clear(); pg().shapes.clear(); pg().texts.clear();
        pg().stickies.clear(); pg().images.clear(); pg().drawOrder.clear();
        pg().history.clear(); pg().redoStack.clear();
        selectedItems.clear(); marquee = null;
        commitTextOverlay();
        repaint();
    }

    public ArrayList<Stroke>     getStrokes()  { return pg().strokes; }
    public ArrayList<ShapeItem>  getShapes()   { return pg().shapes; }
    public ArrayList<TextItem>   getTexts()    { return pg().texts; }
    public ArrayList<StickyNote> getStickies() { return pg().stickies; }
    public ArrayList<Page>       getPages()    { return pages; }

    public void loadPages(ArrayList<Page> loaded) {
        commitTextOverlay();
        pages.clear();
        pages.addAll(loaded);
        if (pages.isEmpty()) pages.add(new Page("Page 1"));
        currentPageIdx = 0;
        selectedItems.clear();
        marquee = null;
        vpX = 0; vpY = 0; vpScale = 1.0;
        if (onPagesChanged != null) onPagesChanged.run();
        repaint();
    }

    public BufferedImage renderPageToImage(int pageIdx) {
        int w = Math.max(getWidth(), 1200);
        int h = Math.max(getHeight(), 900);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = img.createGraphics();
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, w, h);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Page page = pages.get(pageIdx);
        for (Object obj : page.drawOrder) {
            if (obj instanceof ImageItem) {
                ImageItem ii = (ImageItem) obj;
                g2.drawImage(ii.image, ii.pos.x, ii.pos.y, ii.width, ii.height, null);
            } else if (obj instanceof Stroke) {
                Stroke stroke = (Stroke) obj;
                g2.setColor(stroke.color);
                g2.setStroke(new BasicStroke(stroke.width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                if (stroke.points.size() == 1) {
                    int r = Math.max(1, (int)(stroke.width / 2));
                    Point p = stroke.points.get(0);
                    g2.fillOval(p.x - r, p.y - r, r * 2, r * 2);
                } else {
                    for (int i = 1; i < stroke.points.size(); i++) {
                        Point p1 = stroke.points.get(i - 1), p2 = stroke.points.get(i);
                        g2.drawLine(p1.x, p1.y, p2.x, p2.y);
                    }
                }
            } else if (obj instanceof ShapeItem) {
                ShapeItem s = (ShapeItem) obj;
                drawShape(g2, s.type, s.start, s.end, s.color, s.width);
            } else if (obj instanceof TextItem) {
                TextItem ti = (TextItem) obj;
                g2.setColor(ti.color);
                g2.setFont(new Font("SansSerif", Font.PLAIN, ti.fontSize));
                drawWrappedText(g2, ti.text, ti.bounds);
            } else if (obj instanceof StickyNote) {
                drawStickyNote(g2, (StickyNote) obj);
            }
        }
        g2.dispose();
        return img;
    }

    public void loadStrokes(ArrayList<Stroke> loaded) {
        pg().strokes.clear(); pg().strokes.addAll(loaded);
        pg().drawOrder.removeIf(o -> o instanceof Stroke);
        pg().drawOrder.addAll(0, loaded); repaint();
    }
    public void loadShapes(ArrayList<ShapeItem> loaded) {
        pg().shapes.clear(); pg().shapes.addAll(loaded);
        pg().drawOrder.removeIf(o -> o instanceof ShapeItem);
        pg().drawOrder.addAll(loaded); repaint();
    }
    public void loadTexts(ArrayList<TextItem> loaded) {
        pg().texts.clear(); pg().texts.addAll(loaded);
        pg().drawOrder.removeIf(o -> o instanceof TextItem);
        pg().drawOrder.addAll(loaded); repaint();
    }

    public void setNetworkClient(WhiteboardClient client) { this.networkClient = client; }

    public void applyRemoteSegment(int x1, int y1, int x2, int y2, Color color, float width) {
        Stroke seg = new Stroke(color, width);
        seg.points.add(new Point(x1, y1)); seg.points.add(new Point(x2, y2));
        pg().strokes.add(seg); pg().drawOrder.add(seg); repaint();
    }

    public void applyRemoteShape(String type, int sx, int sy, int ex, int ey, Color color, float width) {
        ShapeItem si = new ShapeItem(type, new Point(sx, sy), new Point(ex, ey), color, width);
        pg().shapes.add(si); pg().drawOrder.add(si); repaint();
    }

    public void applyRemoteClear() {
        pg().strokes.clear(); pg().shapes.clear(); pg().texts.clear();
        pg().stickies.clear(); pg().images.clear(); pg().drawOrder.clear();
        pg().history.clear(); pg().redoStack.clear(); repaint();
    }

    public void applyFullSync(ArrayList<Stroke> newStrokes, ArrayList<ShapeItem> newShapes) {
        pg().strokes.clear(); pg().strokes.addAll(newStrokes);
        pg().shapes.clear();  pg().shapes.addAll(newShapes);
        pg().drawOrder.clear();
        pg().drawOrder.addAll(newStrokes); pg().drawOrder.addAll(newShapes);
        repaint();
    }

    public void setLocalUsername(String name) { localUsername = name; }

    public void applyRemoteCursor(String username, int x, int y) {
        remoteCursors.put(username, new Point(x, y)); repaint();
    }
    public void removeRemoteCursor(String username) { remoteCursors.remove(username); repaint(); }
    public void clearRemoteCursors() { remoteCursors.clear(); repaint(); }

    private Color getCursorColor(String username) {
        float hue = Math.abs(username.hashCode() % 360) / 360.0f;
        return Color.getHSBColor(hue, 0.75f, 0.90f);
    }
}
