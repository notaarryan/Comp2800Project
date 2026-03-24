import java.awt.Color;
import java.awt.Point;
import java.io.*;
import java.sql.*;
import java.util.ArrayList;
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

    // holds strokes and shapes together 
    static class LoadedData {
        ArrayList<CanvasPanel.Stroke> strokes;
        ArrayList<CanvasPanel.ShapeItem> shapes;

        LoadedData(ArrayList<CanvasPanel.Stroke> s,
                   ArrayList<CanvasPanel.ShapeItem> sh) {
            strokes = s;
            shapes = sh;
        }
    }

    static class SavedBoard {
        final int id;
        final String name;
        final String savedAt;
        SavedBoard(int id, String name, String savedAt) {
            this.id = id;
            this.name = name;
            this.savedAt = savedAt;
        }
        @Override
        public String toString() {
            return name + "  (" + savedAt + ")";
        }
    }

    static Connection connect() throws SQLException {
        return DriverManager.getConnection(URL);
    }

    static void initSchema() {
        String sql = """
            CREATE TABLE IF NOT EXISTS whiteboards (
                id       SERIAL PRIMARY KEY,
                name     VARCHAR(255) NOT NULL,
                saved_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
            );
            CREATE TABLE IF NOT EXISTS strokes (
                id           SERIAL PRIMARY KEY,
                whiteboard_id INT  NOT NULL REFERENCES whiteboards(id) ON DELETE CASCADE,
                color_r      INT  NOT NULL,
                color_g      INT  NOT NULL,
                color_b      INT  NOT NULL,
                stroke_width FLOAT NOT NULL,
                stroke_order INT  NOT NULL
            );
            CREATE TABLE IF NOT EXISTS stroke_points (
                id          SERIAL PRIMARY KEY,
                stroke_id   INT NOT NULL REFERENCES strokes(id) ON DELETE CASCADE,
                x           INT NOT NULL,
                y           INT NOT NULL,
                point_order INT NOT NULL
            );
            -- UPDATED SHAPES TABLE (with ordering)
            CREATE TABLE IF NOT EXISTS shapes (
                id SERIAL PRIMARY KEY,
                whiteboard_id INT REFERENCES whiteboards(id) ON DELETE CASCADE,
                type VARCHAR(50),
                start_x INT,
                start_y INT,
                end_x INT,
                end_y INT,
                color_r INT,
                color_g INT,
                color_b INT,
                stroke_width FLOAT,
                shape_order INT
            );
            """;

        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            conn.setAutoCommit(false);
            for (String s : sql.split(";")) {
                s = s.strip();
                if (!s.isEmpty()) stmt.execute(s);
            }
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialise schema: " + e.getMessage(), e);
        }
    }

    // updated to save shapes now 
    static int save(String name,
                    ArrayList<CanvasPanel.Stroke> strokes,
                    ArrayList<CanvasPanel.ShapeItem> shapes) throws SQLException {

        String insertBoard  = "INSERT INTO whiteboards (name) VALUES (?) RETURNING id";
        String insertStroke = "INSERT INTO strokes (whiteboard_id, color_r, color_g, color_b, stroke_width, stroke_order) VALUES (?,?,?,?,?,?) RETURNING id";
        String insertPoint  = "INSERT INTO stroke_points (stroke_id, x, y, point_order) VALUES (?,?,?,?)";

        // UPDATED INSERT (with shape_order)
        String insertShape  = "INSERT INTO shapes (whiteboard_id, type, start_x, start_y, end_x, end_y, color_r, color_g, color_b, stroke_width, shape_order) VALUES (?,?,?,?,?,?,?,?,?,?,?)";

        try (Connection conn = connect()) {
            conn.setAutoCommit(false);

            int boardId;
            try (PreparedStatement ps = conn.prepareStatement(insertBoard)) {
                ps.setString(1, name);
                ResultSet rs = ps.executeQuery();
                rs.next();
                boardId = rs.getInt(1);
            }

            // --- save strokes ---
            for (int si = 0; si < strokes.size(); si++) {
                CanvasPanel.Stroke stroke = strokes.get(si);
                int strokeId;

                try (PreparedStatement ps = conn.prepareStatement(insertStroke)) {
                    ps.setInt(1, boardId);
                    ps.setInt(2, stroke.color.getRed());
                    ps.setInt(3, stroke.color.getGreen());
                    ps.setInt(4, stroke.color.getBlue());
                    ps.setFloat(5, stroke.width);
                    ps.setInt(6, si);
                    ResultSet rs = ps.executeQuery();
                    rs.next();
                    strokeId = rs.getInt(1);
                }

                try (PreparedStatement ps = conn.prepareStatement(insertPoint)) {
                    for (int pi = 0; pi < stroke.points.size(); pi++) {
                        Point p = stroke.points.get(pi);
                        ps.setInt(1, strokeId);
                        ps.setInt(2, p.x);
                        ps.setInt(3, p.y);
                        ps.setInt(4, pi);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }

            // save shapes with order
            try (PreparedStatement ps = conn.prepareStatement(insertShape)) {
                for (int i = 0; i < shapes.size(); i++) {
                    CanvasPanel.ShapeItem s = shapes.get(i);

                    ps.setInt(1, boardId);
                    ps.setString(2, s.type);
                    ps.setInt(3, s.start.x);
                    ps.setInt(4, s.start.y);
                    ps.setInt(5, s.end.x);
                    ps.setInt(6, s.end.y);
                    ps.setInt(7, s.color.getRed());
                    ps.setInt(8, s.color.getGreen());
                    ps.setInt(9, s.color.getBlue());
                    ps.setFloat(10, s.width);
                    ps.setInt(11, i); // order fix 
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            conn.commit();
            return boardId;
        }
    }

    // updated to load strokes and shapes now 
    static LoadedData load(int boardId) throws SQLException {

        String queryStrokes = """
            SELECT id, color_r, color_g, color_b, stroke_width
            FROM strokes
            WHERE whiteboard_id = ?
            ORDER BY stroke_order
            """;

        String queryPoints = """
            SELECT x, y
            FROM stroke_points
            WHERE stroke_id = ?
            ORDER BY point_order
            """;

        // order fix 
        String queryShapes = "SELECT * FROM shapes WHERE whiteboard_id = ? ORDER BY shape_order";

        ArrayList<CanvasPanel.Stroke> strokes = new ArrayList<>();
        ArrayList<CanvasPanel.ShapeItem> shapes = new ArrayList<>();

        try (Connection conn = connect();
             PreparedStatement strokeStmt = conn.prepareStatement(queryStrokes)) {

            strokeStmt.setInt(1, boardId);
            ResultSet strokeRs = strokeStmt.executeQuery();

            try (PreparedStatement pointStmt = conn.prepareStatement(queryPoints)) {
                while (strokeRs.next()) {
                    Color color = new Color(
                        strokeRs.getInt("color_r"),
                        strokeRs.getInt("color_g"),
                        strokeRs.getInt("color_b")
                    );

                    float width = strokeRs.getFloat("stroke_width");
                    CanvasPanel.Stroke stroke = new CanvasPanel.Stroke(color, width);

                    pointStmt.setInt(1, strokeRs.getInt("id"));
                    ResultSet pointRs = pointStmt.executeQuery();

                    while (pointRs.next()) {
                        stroke.points.add(new Point(
                            pointRs.getInt("x"),
                            pointRs.getInt("y")
                        ));
                    }

                    strokes.add(stroke);
                }
            }

            // load shapes 
            try (PreparedStatement shapeStmt = conn.prepareStatement(queryShapes)) {
                shapeStmt.setInt(1, boardId);
                ResultSet rs = shapeStmt.executeQuery();

                while (rs.next()) {
                    shapes.add(new CanvasPanel.ShapeItem(
                        rs.getString("type"),
                        new Point(rs.getInt("start_x"), rs.getInt("start_y")),
                        new Point(rs.getInt("end_x"), rs.getInt("end_y")),
                        new Color(
                            rs.getInt("color_r"),
                            rs.getInt("color_g"),
                            rs.getInt("color_b")
                        ),
                        rs.getFloat("stroke_width")
                    ));
                }
            }
        }

        return new LoadedData(strokes, shapes);
    }

    static boolean delete(int boardId) throws SQLException {
        String sql = "DELETE FROM whiteboards WHERE id = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, boardId);
            return ps.executeUpdate() > 0;
        }
    }

    static List<SavedBoard> listSaved() throws SQLException {
        String sql = "SELECT id, name, saved_at FROM whiteboards ORDER BY saved_at DESC";
        List<SavedBoard> boards = new ArrayList<>();

        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                boards.add(new SavedBoard(
                    rs.getInt("id"),
                    rs.getString("name"),
                    rs.getTimestamp("saved_at").toLocalDateTime()
                        .toString().replace("T", "  ").substring(0, 19)
                ));
            }
        }
        return boards;
    }
}