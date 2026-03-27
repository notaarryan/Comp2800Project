import java.awt.Color;
import java.awt.Point;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

public class WhiteboardDB {

    private static final String URL;

    static {
        Properties props = new Properties();
        try (BufferedReader reader = new BufferedReader(new FileReader(".env"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                props.setProperty(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not read .env file: " + e.getMessage(), e);
        }
        URL = props.getProperty("DATABASE_URL");
        if (URL == null || URL.isBlank())
            throw new RuntimeException("DATABASE_URL not set in .env");
    }

    static class SavedBoard {
        final int id; final String name, savedAt;
        SavedBoard(int id, String name, String savedAt) { this.id = id; this.name = name; this.savedAt = savedAt; }
        @Override public String toString() { return name + "  (" + savedAt + ")"; }
    }

    static class Snapshot {
        final int id; final String label, createdAt;
        Snapshot(int id, String label, String createdAt) { this.id = id; this.label = label; this.createdAt = createdAt; }
        @Override public String toString() { return label + "  (" + createdAt + ")"; }
    }

    static Connection connect() throws SQLException { return DriverManager.getConnection(URL); }

    static String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) { throw new RuntimeException(e); }
    }

    static int registerUser(String username, String password) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (username, password_hash) VALUES (?,?) RETURNING id")) {
            ps.setString(1, username.trim()); ps.setString(2, hashPassword(password));
            try {
                ResultSet rs = ps.executeQuery(); rs.next(); return rs.getInt(1);
            } catch (SQLException e) {
                if (e.getSQLState() != null && e.getSQLState().startsWith("23")) return -1;
                throw e;
            }
        }
    }

    static int loginUser(String username, String password) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM users WHERE username = ? AND password_hash = ?")) {
            ps.setString(1, username.trim()); ps.setString(2, hashPassword(password));
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt("id") : -1;
        }
    }


    static int save(String name, int userId, ArrayList<CanvasPanel.Page> pages) throws SQLException {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);

            int boardId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO whiteboards (user_id, name) VALUES (?,?) RETURNING id")) {
                ps.setInt(1, userId); ps.setString(2, name);
                ResultSet rs = ps.executeQuery(); rs.next(); boardId = rs.getInt(1);
            }

            for (int pi = 0; pi < pages.size(); pi++) {
                CanvasPanel.Page page = pages.get(pi);

                for (int si = 0; si < page.strokes.size(); si++) {
                    CanvasPanel.Stroke stroke = page.strokes.get(si);
                    int strokeId;
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO strokes (whiteboard_id,page_number,color_r,color_g,color_b,stroke_width,stroke_order) VALUES (?,?,?,?,?,?,?) RETURNING id")) {
                        ps.setInt(1, boardId); ps.setInt(2, pi);
                        ps.setInt(3, stroke.color.getRed()); ps.setInt(4, stroke.color.getGreen()); ps.setInt(5, stroke.color.getBlue());
                        ps.setFloat(6, stroke.width); ps.setInt(7, si);
                        ResultSet rs = ps.executeQuery(); rs.next(); strokeId = rs.getInt(1);
                    }
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO stroke_points (stroke_id,x,y,point_order) VALUES (?,?,?,?)")) {
                        for (int poi = 0; poi < stroke.points.size(); poi++) {
                            Point p = stroke.points.get(poi);
                            ps.setInt(1, strokeId); ps.setInt(2, p.x); ps.setInt(3, p.y); ps.setInt(4, poi);
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO shapes (whiteboard_id,page_number,type,start_x,start_y,end_x,end_y,color_r,color_g,color_b,stroke_width) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
                    for (CanvasPanel.ShapeItem s : page.shapes) {
                        ps.setInt(1, boardId); ps.setInt(2, pi); ps.setString(3, s.type);
                        ps.setInt(4, s.start.x); ps.setInt(5, s.start.y); ps.setInt(6, s.end.x); ps.setInt(7, s.end.y);
                        ps.setInt(8, s.color.getRed()); ps.setInt(9, s.color.getGreen()); ps.setInt(10, s.color.getBlue());
                        ps.setFloat(11, s.width); ps.addBatch();
                    }
                    ps.executeBatch();
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO texts (whiteboard_id,page_number,content,x,y,width,height,color_r,color_g,color_b,font_size) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
                    for (CanvasPanel.TextItem t : page.texts) {
                        ps.setInt(1, boardId); ps.setInt(2, pi); ps.setString(3, t.text);
                        ps.setInt(4, t.bounds.x); ps.setInt(5, t.bounds.y); ps.setInt(6, t.bounds.width); ps.setInt(7, t.bounds.height);
                        ps.setInt(8, t.color.getRed()); ps.setInt(9, t.color.getGreen()); ps.setInt(10, t.color.getBlue());
                        ps.setInt(11, t.fontSize); ps.addBatch();
                    }
                    ps.executeBatch();
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO stickies (whiteboard_id,page_number,content,x,y,width,height,bg_r,bg_g,bg_b) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                    for (CanvasPanel.StickyNote sn : page.stickies) {
                        ps.setInt(1, boardId); ps.setInt(2, pi); ps.setString(3, sn.text);
                        ps.setInt(4, sn.pos.x); ps.setInt(5, sn.pos.y); ps.setInt(6, sn.width); ps.setInt(7, sn.height);
                        ps.setInt(8, sn.bgColor.getRed()); ps.setInt(9, sn.bgColor.getGreen()); ps.setInt(10, sn.bgColor.getBlue());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }

            conn.commit();
            return boardId;
        }
    }

    static ArrayList<CanvasPanel.Page> loadPages(int boardId) throws SQLException {
        int maxPage = 0;
        try (Connection conn = connect()) {
            for (String q : new String[]{
                    "SELECT MAX(page_number) FROM strokes WHERE whiteboard_id=?",
                    "SELECT MAX(page_number) FROM shapes  WHERE whiteboard_id=?",
                    "SELECT MAX(page_number) FROM texts   WHERE whiteboard_id=?",
                    "SELECT MAX(page_number) FROM stickies WHERE whiteboard_id=?"}) {
                try (PreparedStatement ps = conn.prepareStatement(q)) {
                    ps.setInt(1, boardId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next() && rs.getObject(1) != null)
                        maxPage = Math.max(maxPage, rs.getInt(1));
                }
            }
        }

        ArrayList<CanvasPanel.Page> pages = new ArrayList<>();
        for (int i = 0; i <= maxPage; i++) pages.add(new CanvasPanel.Page("Page " + (i + 1)));

        try (Connection conn = connect()) {
            try (PreparedStatement strokeStmt = conn.prepareStatement(
                    "SELECT id,page_number,color_r,color_g,color_b,stroke_width FROM strokes WHERE whiteboard_id=? ORDER BY page_number,stroke_order");
                 PreparedStatement pointStmt = conn.prepareStatement(
                    "SELECT x,y FROM stroke_points WHERE stroke_id=? ORDER BY point_order")) {
                strokeStmt.setInt(1, boardId);
                ResultSet rs = strokeStmt.executeQuery();
                while (rs.next()) {
                    CanvasPanel.Page pg = pages.get(rs.getInt("page_number"));
                    Color c = new Color(rs.getInt("color_r"), rs.getInt("color_g"), rs.getInt("color_b"));
                    CanvasPanel.Stroke stroke = new CanvasPanel.Stroke(c, rs.getFloat("stroke_width"));
                    pointStmt.setInt(1, rs.getInt("id"));
                    ResultSet pts = pointStmt.executeQuery();
                    while (pts.next()) stroke.points.add(new Point(pts.getInt("x"), pts.getInt("y")));
                    pg.strokes.add(stroke); pg.drawOrder.add(stroke);
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM shapes WHERE whiteboard_id=? ORDER BY page_number")) {
                ps.setInt(1, boardId);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    CanvasPanel.Page pg = pages.get(rs.getInt("page_number"));
                    CanvasPanel.ShapeItem si = new CanvasPanel.ShapeItem(
                        rs.getString("type"),
                        new Point(rs.getInt("start_x"), rs.getInt("start_y")),
                        new Point(rs.getInt("end_x"),   rs.getInt("end_y")),
                        new Color(rs.getInt("color_r"), rs.getInt("color_g"), rs.getInt("color_b")),
                        rs.getFloat("stroke_width"));
                    pg.shapes.add(si); pg.drawOrder.add(si);
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM texts WHERE whiteboard_id=? ORDER BY page_number")) {
                ps.setInt(1, boardId);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    CanvasPanel.Page pg = pages.get(rs.getInt("page_number"));
                    CanvasPanel.TextItem ti = new CanvasPanel.TextItem(
                        rs.getString("content"),
                        new java.awt.Rectangle(rs.getInt("x"), rs.getInt("y"), rs.getInt("width"), rs.getInt("height")),
                        new Color(rs.getInt("color_r"), rs.getInt("color_g"), rs.getInt("color_b")),
                        rs.getInt("font_size"));
                    pg.texts.add(ti); pg.drawOrder.add(ti);
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM stickies WHERE whiteboard_id=? ORDER BY page_number")) {
                ps.setInt(1, boardId);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    CanvasPanel.Page pg = pages.get(rs.getInt("page_number"));
                    CanvasPanel.StickyNote sn = new CanvasPanel.StickyNote(
                        rs.getString("content"),
                        new Point(rs.getInt("x"), rs.getInt("y")),
                        new Color(rs.getInt("bg_r"), rs.getInt("bg_g"), rs.getInt("bg_b")));
                    sn.width  = rs.getInt("width");
                    sn.height = rs.getInt("height");
                    pg.stickies.add(sn); pg.drawOrder.add(sn);
                }
            }
        }

        if (pages.isEmpty()) pages.add(new CanvasPanel.Page("Page 1"));
        return pages;
    }

    static List<SavedBoard> listSaved(int userId) throws SQLException {
        List<SavedBoard> boards = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT id,name,saved_at FROM whiteboards WHERE user_id=? ORDER BY saved_at DESC")) {
            ps.setInt(1, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next())
                boards.add(new SavedBoard(rs.getInt("id"), rs.getString("name"),
                    rs.getTimestamp("saved_at").toLocalDateTime().toString().replace("T","  ").substring(0,19)));
        }
        return boards;
    }

    static boolean delete(int boardId) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM whiteboards WHERE id=?")) {
            ps.setInt(1, boardId); return ps.executeUpdate() > 0;
        }
    }

    static void saveSnapshot(int userId, String label, ArrayList<CanvasPanel.Page> pages) throws SQLException {
        String data = serializePages(pages);
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO snapshots (user_id, label, data) VALUES (?,?,?)")) {
            ps.setInt(1, userId); ps.setString(2, label); ps.setString(3, data);
            ps.executeUpdate();
        }
    }

    static List<Snapshot> listSnapshots(int userId) throws SQLException {
        List<Snapshot> list = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT id,label,created_at FROM snapshots WHERE user_id=? ORDER BY created_at DESC")) {
            ps.setInt(1, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next())
                list.add(new Snapshot(rs.getInt("id"), rs.getString("label"),
                    rs.getTimestamp("created_at").toLocalDateTime().toString().replace("T","  ").substring(0,19)));
        }
        return list;
    }

    static ArrayList<CanvasPanel.Page> loadSnapshot(int snapshotId) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT data FROM snapshots WHERE id=?")) {
            ps.setInt(1, snapshotId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return deserializePages(rs.getString("data"));
        }
        return new ArrayList<>();
    }

    static boolean deleteSnapshot(int snapshotId) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM snapshots WHERE id=?")) {
            ps.setInt(1, snapshotId); return ps.executeUpdate() > 0;
        }
    }

    private static String serializePages(ArrayList<CanvasPanel.Page> pages) {
        StringBuilder sb = new StringBuilder();
        for (CanvasPanel.Page page : pages) {
            sb.append("PAGE ").append(b64(page.name)).append('\n');
            for (CanvasPanel.Stroke s : page.strokes) {
                sb.append("S:").append(s.color.getRed()).append(',')
                  .append(s.color.getGreen()).append(',').append(s.color.getBlue()).append(',').append(s.width);
                for (Point p : s.points) sb.append(',').append(p.x).append(':').append(p.y);
                sb.append('\n');
            }
            for (CanvasPanel.ShapeItem si : page.shapes) {
                sb.append("H:").append(si.type).append(',')
                  .append(si.color.getRed()).append(',').append(si.color.getGreen()).append(',').append(si.color.getBlue()).append(',')
                  .append(si.width).append(',').append(si.start.x).append(',').append(si.start.y)
                  .append(',').append(si.end.x).append(',').append(si.end.y).append('\n');
            }
            for (CanvasPanel.TextItem ti : page.texts) {
                sb.append("T:").append(ti.color.getRed()).append(',').append(ti.color.getGreen()).append(',').append(ti.color.getBlue()).append(',')
                  .append(ti.fontSize).append(',').append(ti.bounds.x).append(',').append(ti.bounds.y)
                  .append(',').append(ti.bounds.width).append(',').append(ti.bounds.height)
                  .append(',').append(b64(ti.text)).append('\n');
            }
            for (CanvasPanel.StickyNote sn : page.stickies) {
                sb.append("N:").append(sn.bgColor.getRed()).append(',').append(sn.bgColor.getGreen()).append(',').append(sn.bgColor.getBlue()).append(',')
                  .append(sn.pos.x).append(',').append(sn.pos.y).append(',').append(sn.width).append(',').append(sn.height)
                  .append(',').append(b64(sn.text)).append('\n');
            }
        }
        return sb.toString();
    }

    private static ArrayList<CanvasPanel.Page> deserializePages(String data) {
        ArrayList<CanvasPanel.Page> pages = new ArrayList<>();
        CanvasPanel.Page current = null;
        for (String line : data.split("\n")) {
            if (line.startsWith("PAGE ")) {
                current = new CanvasPanel.Page(unb64(line.substring(5)));
                pages.add(current);
            } else if (current == null) {
                continue;
            } else if (line.startsWith("S:")) {
                String[] p = line.substring(2).split(",");
                Color c = new Color(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                CanvasPanel.Stroke s = new CanvasPanel.Stroke(c, Float.parseFloat(p[3]));
                for (int i = 4; i < p.length; i++) {
                    String[] xy = p[i].split(":");
                    if (xy.length == 2) s.points.add(new Point(Integer.parseInt(xy[0]), Integer.parseInt(xy[1])));
                }
                current.strokes.add(s); current.drawOrder.add(s);
            } else if (line.startsWith("H:")) {
                String[] p = line.substring(2).split(",", 9);
                Color c = new Color(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                CanvasPanel.ShapeItem si = new CanvasPanel.ShapeItem(p[0],
                    new Point(Integer.parseInt(p[5]), Integer.parseInt(p[6])),
                    new Point(Integer.parseInt(p[7]), Integer.parseInt(p[8])),
                    c, Float.parseFloat(p[4]));
                current.shapes.add(si); current.drawOrder.add(si);
            } else if (line.startsWith("T:")) {
                String[] p = line.substring(2).split(",", 9);
                Color c = new Color(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                java.awt.Rectangle b = new java.awt.Rectangle(
                    Integer.parseInt(p[4]), Integer.parseInt(p[5]),
                    Integer.parseInt(p[6]), Integer.parseInt(p[7]));
                CanvasPanel.TextItem ti = new CanvasPanel.TextItem(unb64(p[8]), b, c, Integer.parseInt(p[3]));
                current.texts.add(ti); current.drawOrder.add(ti);
            } else if (line.startsWith("N:")) {
                String[] p = line.substring(2).split(",", 8);
                Color bg = new Color(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                CanvasPanel.StickyNote sn = new CanvasPanel.StickyNote(unb64(p[7]),
                    new Point(Integer.parseInt(p[3]), Integer.parseInt(p[4])), bg);
                sn.width = Integer.parseInt(p[5]); sn.height = Integer.parseInt(p[6]);
                current.stickies.add(sn); current.drawOrder.add(sn);
            }
        }
        if (pages.isEmpty()) pages.add(new CanvasPanel.Page("Page 1"));
        return pages;
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
    private static String unb64(String s) {
        try { return new String(Base64.getDecoder().decode(s.trim()), StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }
}
