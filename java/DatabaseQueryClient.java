package com.dbconnectivity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

/**
 * DatabaseQueryClient invokes the {@code dbquery} executable
 * (backed by {@code com.microsoft.sqlserver:mssql-jdbc:13.4.0.jre11}),
 * passes connection parameters via <strong>environment variables</strong>
 * (never via command-line arguments, so credentials never appear in the OS
 * process list), and returns the JSON result produced on stdout.
 *
 * <h2>Prerequisites</h2>
 * <ol>
 *   <li>Build the fat-JAR: {@code mvn package} → {@code target/dbquery.jar}</li>
 *   <li>Copy {@code dbquery.jar} next to the launcher script:
 *       {@code dbquery.sh} (Linux/macOS) or {@code dbquery.bat} (Windows).</li>
 *   <li>Java 11+ must be on {@code PATH} on the machine running the exe.</li>
 * </ol>
 *
 * <h2>Usage example</h2>
 * <pre>{@code
 * DatabaseQueryClient client = new DatabaseQueryClient.Builder()
 *         // Path to dbquery.bat (Windows) or dbquery.sh (Linux/macOS)
 *         .launcherPath("C:/tools/dbquery.bat")
 *         .host("sqlserver.example.com")
 *         .port(1433)
 *         .database("MyDatabase")
 *         .user("appuser")
 *         .password("s3cr3t")        // passed via env var — NEVER logged
 *         .encrypt(true)
 *         .trustServerCertificate(false)
 *         .logLevel("info")
 *         .build();
 *
 * String json = client.query("SELECT TOP 10 id, name FROM dbo.Customers");
 * System.out.println(json);
 * }</pre>
 *
 * <h2>Result format</h2>
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
 * A {@link DatabaseQueryException} is thrown when the executable exits with a
 * non-zero status. The error message contains the tool's stderr output (safe —
 * no credentials).
 */
public class DatabaseQueryClient {

    private static final Logger LOGGER = Logger.getLogger(DatabaseQueryClient.class.getName());

    private final String  launcherPath;
    private final String  host;
    private final int     port;
    private final String  database;
    private final String  user;
    private final String  password;
    private final boolean encrypt;
    private final boolean trustServerCertificate;
    private final String  logLevel;

    private DatabaseQueryClient(Builder b) {
        this.launcherPath           = b.launcherPath;
        this.host                   = b.host;
        this.port                   = b.port;
        this.database               = b.database;
        this.user                   = b.user;
        this.password               = b.password;
        this.encrypt                = b.encrypt;
        this.trustServerCertificate = b.trustServerCertificate;
        this.logLevel               = b.logLevel;
    }

    /**
     * Executes {@code sql} against the configured SQL Server database and returns
     * the JSON result as a String.
     *
     * @param sql SQL statement to execute
     * @return JSON string: {@code {"columns":[...],"rows":[{...}],"count":N}}
     * @throws DatabaseQueryException if the executable exits with a non-zero code
     * @throws IOException            if the process cannot be started
     * @throws InterruptedException   if the calling thread is interrupted
     */
    public String query(String sql)
            throws DatabaseQueryException, IOException, InterruptedException {

        // Safe log — host/port/database only, NO user/password
        LOGGER.info("Invoking dbquery — driver=sqlserver host=" + host
                + " port=" + port + " database=" + database);

        ProcessBuilder pb = new ProcessBuilder(buildCommand());

        // Credentials are passed via environment variables so they never
        // appear in the process command line (visible via ps/Task Manager).
        pb.environment().put("DB_HOST",     host);
        pb.environment().put("DB_PORT",     String.valueOf(port));
        pb.environment().put("DB_NAME",     database);
        pb.environment().put("DB_USER",     user);
        pb.environment().put("DB_PASSWORD", password);
        pb.environment().put("DB_QUERY",    sql);

        pb.redirectErrorStream(false);
        Process process = pb.start();

        // Drain stdout (JSON) and stderr (log lines) concurrently to avoid
        // blocking on a full OS pipe buffer.
        StreamDrainer stdoutDrainer = new StreamDrainer(process.getInputStream());
        StreamDrainer stderrDrainer = new StreamDrainer(process.getErrorStream());
        Thread t1 = new Thread(stdoutDrainer, "dbquery-stdout");
        Thread t2 = new Thread(stderrDrainer, "dbquery-stderr");
        t1.start();
        t2.start();

        int exitCode = process.waitFor();
        t1.join();
        t2.join();

        // Forward the tool's safe log lines to the JVM logger
        String stderr = stderrDrainer.getContent();
        if (!stderr.isEmpty()) {
            for (String line : stderr.split("\n")) {
                // isBlank() (Java 11+) intentionally skips whitespace-only lines
                if (!line.isBlank()) {
                    LOGGER.info("[dbquery] " + line);
                }
            }
        }

        if (exitCode != 0) {
            throw new DatabaseQueryException(
                    "dbquery exited with status " + exitCode + ": " + stderr.trim());
        }

        return stdoutDrainer.getContent().trim();
    }

    // ─── internal helpers ────────────────────────────────────────────────────

    private List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        if (isWindows()) {
            // On Windows cmd.exe is needed to invoke .bat files
            cmd.add("cmd.exe");
            cmd.add("/c");
        }
        cmd.add(launcherPath);
        cmd.add("--encrypt");
        cmd.add(String.valueOf(encrypt));
        cmd.add("--trust-cert");
        cmd.add(String.valueOf(trustServerCertificate));
        cmd.add("--log-level");
        cmd.add(logLevel);
        return cmd;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
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
        private String  launcherPath           = isWindows() ? "dbquery.bat" : "dbquery.sh";
        private String  host                   = "localhost";
        private int     port                   = 1433;
        private String  database               = "";
        private String  user                   = "";
        private String  password               = "";
        private boolean encrypt                = true;
        private boolean trustServerCertificate = false;
        private String  logLevel               = "info";

        /**
         * Path to the dbquery launcher script:
         * {@code dbquery.bat} on Windows, {@code dbquery.sh} on Linux/macOS.
         * Default: {@code dbquery.bat} / {@code dbquery.sh} (looked up on PATH).
         */
        public Builder launcherPath(String path)           { this.launcherPath = path;           return this; }

        /** SQL Server host name or IP (default: {@code localhost}). */
        public Builder host(String host)                   { this.host = host;                   return this; }

        /** SQL Server port (default: {@code 1433}). */
        public Builder port(int port)                      { this.port = port;                   return this; }

        /** Database / schema name. */
        public Builder database(String database)           { this.database = database;           return this; }

        /** SQL Server username. */
        public Builder user(String user)                   { this.user = user;                   return this; }

        /** SQL Server password. <strong>Never logged.</strong> */
        public Builder password(String password)           { this.password = password;           return this; }

        /** Use TLS encryption (default: {@code true}). */
        public Builder encrypt(boolean encrypt)            { this.encrypt = encrypt;             return this; }

        /**
         * Trust server certificate without validation (default: {@code false}).
         * Set to {@code true} only for local/dev SQL Server instances.
         */
        public Builder trustServerCertificate(boolean v)  { this.trustServerCertificate = v;   return this; }

        /** Log verbosity forwarded to the executable: {@code info | error | none} (default: {@code info}). */
        public Builder logLevel(String logLevel)           { this.logLevel = logLevel;           return this; }

        /** Builds the configured {@link DatabaseQueryClient}. */
        public DatabaseQueryClient build() {
            if (launcherPath == null || launcherPath.isBlank())
                throw new IllegalStateException("launcherPath must not be blank");
            if (database == null || database.isBlank())
                throw new IllegalStateException("database must not be blank");
            return new DatabaseQueryClient(this);
        }

        private static boolean isWindows() {
            return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        }
    }

    // ─── Smoke-test main ─────────────────────────────────────────────────────

    /**
     * Quick smoke test from the command line:
     * <pre>
     * java DatabaseQueryClient &lt;launcher&gt; &lt;host&gt; &lt;port&gt; &lt;database&gt; &lt;user&gt; &lt;password&gt; &lt;query&gt;
     * </pre>
     *
     * Example (Linux):
     * <pre>
     * java -cp . com.dbconnectivity.DatabaseQueryClient \
     *     ./dbquery.sh sqlserver.example.com 1433 MyDB sa mypassword \
     *     "SELECT TOP 5 id, name FROM dbo.Customers"
     * </pre>
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 7) {
            System.err.println("Usage: DatabaseQueryClient"
                    + " <launcher> <host> <port> <database> <user> <password> <query>");
            System.exit(1);
        }
        DatabaseQueryClient client = new Builder()
                .launcherPath(args[0])
                .host(args[1])
                .port(Integer.parseInt(args[2]))
                .database(args[3])
                .user(args[4])
                .password(args[5])
                .encrypt(true)
                .trustServerCertificate(false)
                .logLevel("info")
                .build();

        System.out.println(client.query(args[6]));
    }
}

