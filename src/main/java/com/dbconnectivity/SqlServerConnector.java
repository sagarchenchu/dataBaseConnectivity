package com.dbconnectivity;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * SqlServerConnector connects to Microsoft SQL Server via the mssql-jdbc driver,
 * executes a SQL statement, and returns the result as a JSON byte array.
 *
 * <h2>Security</h2>
 * <ul>
 *   <li>Credentials (user / password) are <strong>never written to any log</strong>.</li>
 *   <li>Log messages contain only: driver type, host, port, and database name.</li>
 * </ul>
 */
public class SqlServerConnector {

    private static final Logger LOG = Logger.getLogger(SqlServerConnector.class.getName());

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Immutable connection settings. Password is excluded from toString/logs. */
    public static class Config {
        public final String host;
        public final int    port;
        public final String database;
        public final String user;
        public final String password;
        public final boolean encrypt;
        public final boolean trustServerCertificate;

        public Config(String host, int port, String database,
                      String user, String password,
                      boolean encrypt, boolean trustServerCertificate) {
            this.host                  = host;
            this.port                  = port;
            this.database              = database;
            this.user                  = user;
            this.password              = password;
            this.encrypt               = encrypt;
            this.trustServerCertificate = trustServerCertificate;
        }

        /** Log-safe description — does NOT include user or password. */
        public String safeDescription() {
            return "driver=sqlserver host=" + host + " port=" + port + " database=" + database;
        }

        /**
         * Builds a JDBC URL for the Microsoft SQL Server driver.
         * Credentials are passed separately via {@link DriverManager#getConnection(String, String, String)}
         * so they do not appear in the URL (which could end up in logs).
         */
        public String jdbcUrl() {
            return "jdbc:sqlserver://" + host + ":" + port
                    + ";databaseName=" + database
                    + ";encrypt=" + encrypt
                    + ";trustServerCertificate=" + trustServerCertificate;
        }
    }

    /** Result returned for every query — serialised as JSON. */
    public static final class QueryResult {
        public final List<String>              columns;
        public final List<Map<String, Object>> rows;
        public final int                       count;

        QueryResult(List<String> columns, List<Map<String, Object>> rows) {
            this.columns = Collections.unmodifiableList(columns);
            this.rows    = Collections.unmodifiableList(rows);
            this.count   = rows.size();
        }
    }

    /**
     * Connects to SQL Server, executes {@code sql}, and returns the results
     * serialised as a JSON byte array.
     *
     * <p>For DML/DDL statements (INSERT, UPDATE, DELETE, CREATE, …) that
     * produce no result-set, the returned JSON has {@code "columns":[]},
     * {@code "rows":[]}, and {@code "count":0}.
     *
     * @param cfg connection configuration (credentials are not logged)
     * @param sql SQL statement to execute
     * @return UTF-8 JSON bytes
     * @throws SQLException if the connection or query fails
     */
    public static byte[] runQuery(Config cfg, String sql) throws Exception {
        LOG.info("[INFO] Connecting to database — " + cfg.safeDescription());

        try (Connection conn = DriverManager.getConnection(cfg.jdbcUrl(), cfg.user, cfg.password)) {
            LOG.info("[INFO] Connected successfully — " + cfg.safeDescription());
            LOG.info("[INFO] Executing query");

            try (Statement stmt = conn.createStatement()) {
                boolean hasResultSet = stmt.execute(sql);

                List<String>              columns = new ArrayList<>();
                List<Map<String, Object>> rows    = new ArrayList<>();

                if (hasResultSet) {
                    try (ResultSet rs = stmt.getResultSet()) {
                        ResultSetMetaData meta = rs.getMetaData();
                        int colCount = meta.getColumnCount();
                        for (int i = 1; i <= colCount; i++) {
                            columns.add(meta.getColumnLabel(i));
                        }

                        while (rs.next()) {
                            Map<String, Object> row = new LinkedHashMap<>(colCount);
                            for (int i = 1; i <= colCount; i++) {
                                row.put(columns.get(i - 1), rs.getObject(i));
                            }
                            rows.add(row);
                        }
                    }
                }

                LOG.info("[INFO] Query returned " + rows.size() + " row(s)");
                return MAPPER.writeValueAsBytes(new QueryResult(columns, rows));
            }
        }
    }
}
