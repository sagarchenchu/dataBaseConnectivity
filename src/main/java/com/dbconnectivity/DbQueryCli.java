package com.dbconnectivity;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.logging.*;

/**
 * DbQueryCli is the {@code main} entry point for the {@code dbquery} executable.
 *
 * <h2>Usage</h2>
 * <pre>
 * dbquery [flags]
 *
 * Flags (all can also be set via environment variables):
 *   --host             DB_HOST      SQL Server host          (default: localhost)
 *   --port             DB_PORT      SQL Server port          (default: 1433)
 *   --database         DB_NAME      Database / schema name
 *   --user             DB_USER      Username
 *   --password         DB_PASSWORD  Password  *** never logged ***
 *   --query            DB_QUERY     SQL statement to execute
 *   --query-file                    Path to a .sql file containing the query
 *   --encrypt                       Use TLS encryption        (default: true)
 *   --trust-cert                    Trust server certificate  (default: false)
 *   --log-level                     info | error | none       (default: info)
 * </pre>
 *
 * <h2>Output</h2>
 * <ul>
 *   <li><strong>stdout</strong> — JSON result ({@code columns}, {@code rows}, {@code count})</li>
 *   <li><strong>stderr</strong> — Log lines (safe: no credentials)</li>
 * </ul>
 *
 * <h2>Exit codes</h2>
 * <ul>
 *   <li>0 — success</li>
 *   <li>1 — bad arguments or connection / query failure</li>
 * </ul>
 */
public class DbQueryCli {

    public static void main(String[] args) {
        // Redirect java.util.logging to stderr so stdout stays clean for JSON.
        configureLogging();

        String host      = envOr("DB_HOST",     "localhost");
        int    port      = envIntOr("DB_PORT",   1433);
        String database  = envOr("DB_NAME",      "");
        String user      = envOr("DB_USER",      "");
        String password  = envOr("DB_PASSWORD",  "");
        String query     = envOr("DB_QUERY",     "");
        String queryFile = "";
        String logLevel  = "info";
        boolean encrypt      = true;
        boolean trustCert    = false;

        // Parse CLI flags — flags take precedence over environment variables
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host":         host      = args[++i]; break;
                case "--port":         port      = Integer.parseInt(args[++i]); break;
                case "--database":     database  = args[++i]; break;
                case "--user":         user      = args[++i]; break;
                case "--password":     password  = args[++i]; break;
                case "--query":        query     = args[++i]; break;
                case "--query-file":   queryFile = args[++i]; break;
                case "--log-level":    logLevel  = args[++i]; break;
                case "--encrypt":      encrypt   = Boolean.parseBoolean(args[++i]); break;
                case "--trust-cert":   trustCert = Boolean.parseBoolean(args[++i]); break;
                case "--help": case "-h":
                    printHelp(System.err);
                    System.exit(0);
                    break;
                default:
                    System.err.println("[ERROR] Unknown flag: " + args[i]);
                    printHelp(System.err);
                    System.exit(1);
            }
        }

        applyLogLevel(logLevel);

        // Read query from file if specified
        if (!queryFile.isEmpty()) {
            try {
                query = new String(Files.readAllBytes(Paths.get(queryFile))).trim();
            } catch (Exception e) {
                System.err.println("[ERROR] Cannot read query file '" + queryFile + "': " + e.getMessage());
                System.exit(1);
            }
        }

        // Validate required inputs
        if (database.isEmpty()) {
            System.err.println("[ERROR] --database (or DB_NAME) is required");
            System.exit(1);
        }
        if (query.isEmpty()) {
            System.err.println("[ERROR] --query (or DB_QUERY, or --query-file) is required");
            System.exit(1);
        }

        SqlServerConnector.Config cfg = new SqlServerConnector.Config(
                host, port, database, user, password, encrypt, trustCert);

        try {
            byte[] jsonBytes = SqlServerConnector.runQuery(cfg, query);
            System.out.println(new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("[ERROR] " + e.getMessage());
            System.exit(1);
        }
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return (v != null && !v.isEmpty()) ? v : fallback;
    }

    private static int envIntOr(String key, int fallback) {
        String v = System.getenv(key);
        if (v != null && !v.isEmpty()) {
            try { return Integer.parseInt(v); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    private static void configureLogging() {
        // Remove default handlers so nothing goes to stdout
        Logger root = Logger.getLogger("");
        for (Handler h : root.getHandlers()) {
            root.removeHandler(h);
        }
        StreamHandler stderrHandler = new StreamHandler(System.err, new SimpleFormatter()) {
            @Override
            public synchronized void publish(LogRecord lr) {
                super.publish(lr);
                flush();
            }
        };
        stderrHandler.setLevel(Level.ALL);
        root.addHandler(stderrHandler);
        root.setLevel(Level.INFO);
    }

    private static void applyLogLevel(String level) {
        Logger root = Logger.getLogger("");
        switch (level.toLowerCase()) {
            case "none":
                root.setLevel(Level.OFF);
                for (Handler h : root.getHandlers()) h.setLevel(Level.OFF);
                break;
            case "error":
                root.setLevel(Level.SEVERE);
                for (Handler h : root.getHandlers()) h.setLevel(Level.SEVERE);
                break;
            default: // "info"
                root.setLevel(Level.INFO);
                for (Handler h : root.getHandlers()) h.setLevel(Level.INFO);
        }
    }

    private static void printHelp(PrintStream out) {
        out.println("Usage: dbquery [flags]");
        out.println();
        out.println("Flags (can also be set via environment variables):");
        out.println("  --host         DB_HOST      SQL Server host         (default: localhost)");
        out.println("  --port         DB_PORT      SQL Server port         (default: 1433)");
        out.println("  --database     DB_NAME      Database / schema name");
        out.println("  --user         DB_USER      Username");
        out.println("  --password     DB_PASSWORD  Password  *** never logged ***");
        out.println("  --query        DB_QUERY     SQL statement to execute");
        out.println("  --query-file                Path to a .sql file containing the query");
        out.println("  --encrypt      true|false   TLS encryption          (default: true)");
        out.println("  --trust-cert   true|false   Trust server certificate (default: false)");
        out.println("  --log-level    info|error|none  Log verbosity        (default: info)");
        out.println();
        out.println("Output:");
        out.println("  stdout — JSON: {\"columns\":[...], \"rows\":[{...}], \"count\":N}");
        out.println("  stderr — Log lines (safe: no credentials)");
        out.println();
        out.println("Exit codes: 0=success, 1=error");
    }
}
