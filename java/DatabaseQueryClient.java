package com.dbconnectivity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

/**
 * DatabaseQueryClient invokes the native {@code dbquery.exe} (Windows) or
 * {@code dbquery} (Linux/macOS) executable, passes all connection parameters
 * via <strong>environment variables</strong> (never via command-line arguments,
 * so credentials never appear in the OS process list), and returns the JSON
 * result from stdout.
 *
 * <p>The executable is a self-contained native binary built from Go — it
 * requires <strong>no Java runtime</strong> on the target machine.</p>
 *
 * <h2>Usage example</h2>
 * <pre>{@code
 * DatabaseQueryClient client = new DatabaseQueryClient.Builder()
 *         .executablePath("C:/tools/dbquery.exe")  // or "/opt/tools/dbquery"
 *         .host("sqlserver.example.com")
 *         .port(1433)
 *         .database("MyDatabase")
 *         .user("appuser")
 *         .password("s3cr3t")           // passed as env var — NEVER logged
 *         .encrypt(true)
 *         .trustServerCertificate(false)
 *         .logLevel("info")
 *         .build();
 *
 * // Returns JSON: {"columns":[...], "rows":[{...}], "count": N}
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
 * non-zero status code. The error message comes from the tool's stderr output
 * (safe — no credentials).
 */
public class DatabaseQueryClient {

    private static final Logger LOGGER = Logger.getLogger(DatabaseQueryClient.class.getName());

    private final String  executablePath;
    private final String  host;
    private final int     port;
    private final String  database;
    private final String  user;
    private final String  password;
    private final boolean encrypt;
    private final boolean trustServerCertificate;
    private final String  logLevel;

    private DatabaseQueryClient(Builder b) {
        this.executablePath         = b.executablePath;
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
     * Executes {@code sql} against the configured SQL Server database and
     * returns the JSON result as a String.
     *
     * @param sql SQL statement to execute
     * @return JSON string: {@code {"columns":[...],"rows":[{...}],"count":N}}
     * @throws DatabaseQueryException if the executable exits with a non-zero code
     * @throws IOException            if the process cannot be started
     * @throws InterruptedException   if the calling thread is interrupted
     */
    public String query(String sql)
            throws DatabaseQueryException, IOException, InterruptedException {

        // Log-safe — no user/password
        LOGGER.info("Invoking dbquery — driver=sqlserver host=" + host
                + " port=" + port + " database=" + database);

        ProcessBuilder pb = new ProcessBuilder(buildCommand());

        // Credentials travel as environment variables, not CLI flags.
        // This keeps them out of the OS process list (ps / Task Manager).
        pb.environment().put("DB_DRIVER",     "sqlserver");
        pb.environment().put("DB_HOST",       host);
        pb.environment().put("DB_PORT",       String.valueOf(port));
        pb.environment().put("DB_NAME",       database);
        pb.environment().put("DB_USER",       user);
        pb.environment().put("DB_PASSWORD",   password);
        pb.environment().put("DB_QUERY",      sql);
        pb.environment().put("DB_ENCRYPT",    String.valueOf(encrypt));
        pb.environment().put("DB_TRUST_CERT", String.valueOf(trustServerCertificate));

        pb.redirectErrorStream(false);
        Process process = pb.start();

        // Drain stdout (JSON) and stderr (log lines) concurrently to prevent
        // the process blocking on a full OS pipe buffer.
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
        return Arrays.asList(executablePath, "--log-level", logLevel);
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
        public DatabaseQueryException(String message) { super(message); }
    }

    // ─── Builder ─────────────────────────────────────────────────────────────

    /** Fluent builder for {@link DatabaseQueryClient}. */
    public static class Builder {
        private String  executablePath         = isWindows() ? "dbquery.exe" : "dbquery";
        private String  host                   = "localhost";
        private int     port                   = 1433;
        private String  database               = "";
        private String  user                   = "";
        private String  password               = "";
        private boolean encrypt                = true;
        private boolean trustServerCertificate = false;
        private String  logLevel               = "info";

        /**
         * Full path to the {@code dbquery.exe} (Windows) or {@code dbquery} (Linux/macOS)
         * binary. Default: {@code dbquery.exe} / {@code dbquery} looked up on {@code PATH}.
         */
        public Builder executablePath(String path)          { this.executablePath = path;          return this; }
        /** SQL Server host name or IP (default: {@code localhost}). */
        public Builder host(String host)                    { this.host = host;                    return this; }
        /** SQL Server port (default: {@code 1433}). */
        public Builder port(int port)                       { this.port = port;                    return this; }
        /** Database / schema name. */
        public Builder database(String database)            { this.database = database;            return this; }
        /** SQL Server username. */
        public Builder user(String user)                    { this.user = user;                    return this; }
        /** SQL Server password. <strong>Never logged.</strong> */
        public Builder password(String password)            { this.password = password;            return this; }
        /** Use TLS encryption (default: {@code true}). */
        public Builder encrypt(boolean encrypt)             { this.encrypt = encrypt;              return this; }
        /**
         * Trust server certificate without CA validation (default: {@code false}).
         * Set to {@code true} only for local / Docker SQL Server instances.
         */
        public Builder trustServerCertificate(boolean v)   { this.trustServerCertificate = v;    return this; }
        /** Log verbosity forwarded to the exe: {@code info | error | none} (default: {@code info}). */
        public Builder logLevel(String logLevel)            { this.logLevel = logLevel;            return this; }

        /** Builds the configured {@link DatabaseQueryClient}. */
        public DatabaseQueryClient build() {
            if (executablePath == null || executablePath.isBlank())
                throw new IllegalStateException("executablePath must not be blank");
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
     * Quick command-line smoke test:
     * <pre>
     * java -cp . com.dbconnectivity.DatabaseQueryClient \
     *     &lt;exe&gt; &lt;host&gt; &lt;port&gt; &lt;database&gt; &lt;user&gt; &lt;password&gt; &lt;query&gt;
     * </pre>
     *
     * Example (Windows):
     * <pre>
     * java -cp . com.dbconnectivity.DatabaseQueryClient \
     *     C:\tools\dbquery.exe sqlserver.example.com 1433 MyDB sa mypass \
     *     "SELECT TOP 5 id, name FROM dbo.Customers"
     * </pre>
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 7) {
            System.err.println("Usage: DatabaseQueryClient"
                    + " <exe> <host> <port> <database> <user> <password> <query>");
            System.exit(1);
        }
        DatabaseQueryClient client = new Builder()
                .executablePath(args[0])
                .host(args[1])
                .port(Integer.parseInt(args[2]))
                .database(args[3])
                .user(args[4])
                .password(args[5])
                .encrypt(true)
                .trustServerCertificate(false)
                .build();

        System.out.println(client.query(args[6]));
    }
}
