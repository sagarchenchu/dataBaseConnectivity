package com.dbconnectivity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

/**
 * DatabaseQueryClient invokes the {@code dbquery} executable, passes connection
 * parameters via environment variables (never via command-line arguments, to
 * avoid credentials appearing in the process list), and parses the JSON result
 * returned on stdout.
 *
 * <h2>Usage example</h2>
 * <pre>{@code
 * DatabaseQueryClient client = new DatabaseQueryClient.Builder()
 *         .executablePath("/opt/tools/dbquery")   // or "dbquery.exe" on Windows
 *         .driver("mysql")
 *         .host("db.example.com")
 *         .port(3306)
 *         .database("myapp")
 *         .user("appuser")
 *         .password("s3cr3t")          // NOT logged
 *         .logLevel("info")
 *         .build();
 *
 * String json = client.query("SELECT id, name FROM customers LIMIT 10");
 * System.out.println(json);
 * }</pre>
 *
 * <h2>Result format</h2>
 * The returned String is a JSON object:
 * <pre>{@code
 * {
 *   "columns": ["id", "name"],
 *   "rows": [
 *     {"id": 1, "name": "Alice"},
 *     {"id": 2, "name": "Bob"}
 *   ],
 *   "count": 2
 * }
 * }</pre>
 *
 * <h2>Error handling</h2>
 * If the executable exits with a non-zero status a {@link DatabaseQueryException}
 * is thrown that includes the error message written by the tool to stderr.
 * Credentials are never included in the error message or logs.
 */
public class DatabaseQueryClient {

    private static final Logger LOGGER = Logger.getLogger(DatabaseQueryClient.class.getName());

    private final String executablePath;
    private final String driver;
    private final String host;
    private final int    port;
    private final String database;
    private final String user;
    private final String password;
    private final String logLevel;

    private DatabaseQueryClient(Builder b) {
        this.executablePath = b.executablePath;
        this.driver         = b.driver;
        this.host           = b.host;
        this.port           = b.port;
        this.database       = b.database;
        this.user           = b.user;
        this.password       = b.password;
        this.logLevel       = b.logLevel;
    }

    /**
     * Executes {@code sql} against the configured database and returns the
     * JSON result produced by the {@code dbquery} executable.
     *
     * @param sql SQL query to execute (SELECT, or any statement the DB supports)
     * @return JSON string with keys {@code columns}, {@code rows}, {@code count}
     * @throws DatabaseQueryException if the executable exits with a non-zero code
     * @throws IOException            if the process cannot be started
     * @throws InterruptedException   if the calling thread is interrupted while
     *                                waiting for the process to finish
     */
    public String query(String sql)
            throws DatabaseQueryException, IOException, InterruptedException {

        LOGGER.info("Invoking dbquery — driver=" + driver
                + " host=" + host
                + " port=" + port
                + " database=" + database);
        // NOTE: user/password are intentionally omitted from the log line above.

        List<String> command = buildCommand();

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("DB_DRIVER",   driver);
        pb.environment().put("DB_HOST",     host);
        pb.environment().put("DB_PORT",     String.valueOf(port));
        pb.environment().put("DB_NAME",     database);
        pb.environment().put("DB_USER",     user);
        pb.environment().put("DB_PASSWORD", password);   // env var, not a flag
        pb.environment().put("DB_QUERY",    sql);

        // stderr from the tool (log lines) is forwarded to the JVM's stderr
        pb.redirectErrorStream(false);

        Process process = pb.start();

        // Read stdout (JSON result) and stderr (log lines) concurrently to
        // avoid blocking on full output buffers.
        StreamDrainer stdoutDrainer = new StreamDrainer(process.getInputStream());
        StreamDrainer stderrDrainer = new StreamDrainer(process.getErrorStream());
        Thread t1 = new Thread(stdoutDrainer, "dbquery-stdout");
        Thread t2 = new Thread(stderrDrainer, "dbquery-stderr");
        t1.start();
        t2.start();

        int exitCode = process.waitFor();
        t1.join();
        t2.join();

        String stderr = stderrDrainer.getContent();
        if (!stderr.isEmpty()) {
            // Forward tool log lines to the JVM logger (they are already safe — no credentials)
            for (String line : stderr.split("\n")) {
                if (!line.isBlank()) {
                    LOGGER.info("[dbquery] " + line);
                }
            }
        }

        if (exitCode != 0) {
            throw new DatabaseQueryException(
                    "dbquery exited with status " + exitCode
                    + ": " + stderr.trim());
        }

        return stdoutDrainer.getContent().trim();
    }

    // ─── internal helpers ────────────────────────────────────────────────────

    private List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(executablePath);
        cmd.add("--log-level");
        cmd.add(logLevel);
        return cmd;
    }

    /** Drains an InputStream into a String on a background thread. */
    private static class StreamDrainer implements Runnable {
        private final InputStream in;
        private final StringBuilder sb = new StringBuilder();

        StreamDrainer(InputStream in) { this.in = in; }

        @Override
        public void run() {
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            } catch (IOException ignored) { /* stream closed */ }
        }

        String getContent() { return sb.toString(); }
    }

    // ─── Exception ───────────────────────────────────────────────────────────

    /** Thrown when the dbquery executable exits with a non-zero exit code. */
    public static class DatabaseQueryException extends Exception {
        public DatabaseQueryException(String message) {
            super(message);
        }
    }

    // ─── Builder ─────────────────────────────────────────────────────────────

    /** Fluent builder for {@link DatabaseQueryClient}. */
    public static class Builder {
        private String executablePath = isWindows() ? "dbquery.exe" : "dbquery";
        private String driver   = "";
        private String host     = "localhost";
        private int    port     = 0;
        private String database = "";
        private String user     = "";
        private String password = "";
        private String logLevel = "info";

        /** Path to the dbquery executable (default: {@code dbquery} / {@code dbquery.exe}). */
        public Builder executablePath(String path) { this.executablePath = path; return this; }

        /** Database driver: {@code mysql}, {@code postgres}, {@code sqlserver}, {@code sqlite}. */
        public Builder driver(String driver) { this.driver = driver; return this; }

        /** Database host name or IP address (default: {@code localhost}). */
        public Builder host(String host) { this.host = host; return this; }

        /** Database port (default: driver-specific default). */
        public Builder port(int port) { this.port = port; return this; }

        /** Database / schema name, or SQLite file path. */
        public Builder database(String database) { this.database = database; return this; }

        /** Database username. */
        public Builder user(String user) { this.user = user; return this; }

        /** Database password. <strong>Never logged.</strong> */
        public Builder password(String password) { this.password = password; return this; }

        /** Log verbosity forwarded to the executable: {@code info}, {@code error}, {@code none}. */
        public Builder logLevel(String logLevel) { this.logLevel = logLevel; return this; }

        /** Builds the configured {@link DatabaseQueryClient}. */
        public DatabaseQueryClient build() {
            if (executablePath == null || executablePath.isBlank()) {
                throw new IllegalStateException("executablePath must not be blank");
            }
            if (driver == null || driver.isBlank()) {
                throw new IllegalStateException("driver must not be blank");
            }
            if (database == null || database.isBlank()) {
                throw new IllegalStateException("database must not be blank");
            }
            return new DatabaseQueryClient(this);
        }

        private static boolean isWindows() {
            return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        }
    }

    // ─── Quick smoke-test main ────────────────────────────────────────────────

    /**
     * Simple command-line smoke test.
     *
     * <pre>
     * java DatabaseQueryClient &lt;exe&gt; &lt;driver&gt; &lt;host&gt; &lt;port&gt; &lt;database&gt; &lt;user&gt; &lt;password&gt; &lt;query&gt;
     * </pre>
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 8) {
            System.err.println("Usage: DatabaseQueryClient <exe> <driver> <host> <port>"
                    + " <database> <user> <password> <query>");
            System.exit(1);
        }
        DatabaseQueryClient client = new Builder()
                .executablePath(args[0])
                .driver(args[1])
                .host(args[2])
                .port(Integer.parseInt(args[3]))
                .database(args[4])
                .user(args[5])
                .password(args[6])
                .logLevel("info")
                .build();

        String result = client.query(args[7]);
        System.out.println(result);
    }
}
