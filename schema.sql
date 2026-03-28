CREATE TABLE users (
    id            SERIAL PRIMARY KEY,
    username      VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(64)  NOT NULL,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE whiteboards (
    id         SERIAL PRIMARY KEY,
    user_id    INT          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name       VARCHAR(255) NOT NULL,
    page_count INT          NOT NULL DEFAULT 1,
    saved_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE strokes (
    id            SERIAL PRIMARY KEY,
    whiteboard_id INT   NOT NULL REFERENCES whiteboards(id) ON DELETE CASCADE,
    page_number   INT   NOT NULL DEFAULT 0,
    color_r       INT,
    color_g       INT,
    color_b       INT,
    stroke_width  FLOAT NOT NULL,
    stroke_order  INT   NOT NULL
);

CREATE TABLE stroke_points (
    id          SERIAL PRIMARY KEY,
    stroke_id   INT NOT NULL REFERENCES strokes(id) ON DELETE CASCADE,
    x           INT,
    y           INT,
    point_order INT NOT NULL
);

CREATE TABLE shapes (
    id            SERIAL PRIMARY KEY,
    whiteboard_id INT         NOT NULL REFERENCES whiteboards(id) ON DELETE CASCADE,
    page_number   INT         NOT NULL DEFAULT 0,
    type          VARCHAR(50),
    start_x       INT,
    start_y       INT,
    end_x         INT,
    end_y         INT,
    color_r       INT,
    color_g       INT,
    color_b       INT,
    stroke_width  FLOAT
);

CREATE TABLE texts (
    id            SERIAL PRIMARY KEY,
    whiteboard_id INT  NOT NULL REFERENCES whiteboards(id) ON DELETE CASCADE,
    page_number   INT  NOT NULL DEFAULT 0,
    content       TEXT,
    x             INT,
    y             INT,
    width         INT,
    height        INT,
    color_r       INT,
    color_g       INT,
    color_b       INT,
    font_size     INT
);

CREATE TABLE stickies (
    id            SERIAL PRIMARY KEY,
    whiteboard_id INT  NOT NULL REFERENCES whiteboards(id) ON DELETE CASCADE,
    page_number   INT  NOT NULL DEFAULT 0,
    content       TEXT,
    x             INT,
    y             INT,
    width         INT,
    height        INT,
    bg_r          INT,
    bg_g          INT,
    bg_b          INT
);

CREATE TABLE images (
    id            SERIAL PRIMARY KEY,
    whiteboard_id INT  NOT NULL REFERENCES whiteboards(id) ON DELETE CASCADE,
    page_number   INT  NOT NULL DEFAULT 0,
    x             INT,
    y             INT,
    width         INT,
    height        INT,
    data          TEXT NOT NULL
);

CREATE TABLE snapshots (
    id            SERIAL PRIMARY KEY,
    user_id       INT          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    whiteboard_id INT          REFERENCES whiteboards(id) ON DELETE CASCADE,
    label         VARCHAR(255),
    data          TEXT         NOT NULL,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
