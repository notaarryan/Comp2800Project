# Comp2800Project

A Java Swing whiteboard application with PostgreSQL persistence.

## Setup

**1. Configure the database**

Create a `.env` file in the project root (copy the format below). Use your own Neon or PostgreSQL connection string:

```
DATABASE_URL=jdbc:postgresql://user:password@host/dbname?sslmode=require
```

The `.env` file is gitignored — each teammate must create their own.

**2. Build & Run**

The PostgreSQL JDBC driver is included in `lib/`. No additional downloads needed.

**IntelliJ IDEA:**
Open `Comp2800Project.iml` and run `whiteboardGUI` as the main class.

**Terminal (macOS/Linux):**
```bash
javac -cp lib/postgresql-42.7.10.jar -d out/production/Comp2800Project src/*.java
java -cp "out/production/Comp2800Project:lib/postgresql-42.7.10.jar" whiteboardGUI
```

**Terminal (Windows):**
```bash
javac -cp lib/postgresql-42.7.10.jar -d out/production/Comp2800Project src/*.java
java -cp "out/production/Comp2800Project;lib/postgresql-42.7.10.jar" whiteboardGUI
```

Requires **JDK 22**.