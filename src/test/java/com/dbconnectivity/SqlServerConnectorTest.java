package com.dbconnectivity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SqlServerConnector} using an H2 in-memory database
 * so that no external SQL Server instance is required.
 *
 * <p>H2's SQL Server compatibility mode is enabled via the JDBC URL so that
 * the same SQL syntax used in production works in the tests.
 */
class SqlServerConnectorTest {

    /**
     * Returns a {@link SqlServerConnector.Config} that points at an H2 in-memory
     * database with SQL Server compatibility mode.
     *
     * <p>H2 registers itself under the {@code jdbc:h2:} prefix.  We override
     * the JDBC URL by sub-classing Config so all other connector logic (logging,
     * JSON serialisation, etc.) is exercised unchanged.
     */
    private static SqlServerConnector.Config h2Config(String dbName) {
        return new SqlServerConnector.Config(
                "localhost", 1433, dbName, "sa", "", false, true) {
            @Override
            public String jdbcUrl() {
                // H2 in-memory DB with SQL Server compatibility mode
                return "jdbc:h2:mem:" + database
                        + ";MODE=MSSQLServer;DB_CLOSE_DELAY=-1";
            }
        };
    }

    /** Set up tables shared by multiple tests. */
    @BeforeAll
    static void createSchema() throws Exception {
        SqlServerConnector.Config cfg = h2Config("testdb");
        SqlServerConnector.runQuery(cfg, "CREATE TABLE IF NOT EXISTS employees ("
                + "id INT PRIMARY KEY, name VARCHAR(100), department VARCHAR(100), salary DOUBLE PRECISION)");
        SqlServerConnector.runQuery(cfg, "INSERT INTO employees VALUES (1, 'Alice', 'Engineering', 95000.0)");
        SqlServerConnector.runQuery(cfg, "INSERT INTO employees VALUES (2, 'Bob',   'Marketing',   72000.0)");
        SqlServerConnector.runQuery(cfg, "INSERT INTO employees VALUES (3, 'Carol', 'Engineering', 88000.0)");
    }

    // ─── SELECT ──────────────────────────────────────────────────────────────

    @Test
    void selectAllRows() throws Exception {
        byte[] raw = SqlServerConnector.runQuery(h2Config("testdb"),
                "SELECT id, name FROM employees ORDER BY id");
        JsonNode result = new ObjectMapper().readTree(raw);

        assertEquals(3, result.get("count").asInt(), "count");
        // H2 uppercases unquoted identifiers; actual SQL Server preserves definition case.
        // We compare lower-case to work with both.
        String col0 = result.get("columns").get(0).asText();
        String col1 = result.get("columns").get(1).asText();
        assertEquals("id",   col0.toLowerCase());
        assertEquals("name", col1.toLowerCase());
        assertEquals("Alice", result.get("rows").get(0).get(col1).asText());
        assertEquals("Carol", result.get("rows").get(2).get(col1).asText());
    }

    @Test
    void selectWithWhereClause() throws Exception {
        byte[] raw = SqlServerConnector.runQuery(h2Config("testdb"),
                "SELECT name FROM employees WHERE department = 'Engineering' ORDER BY name");
        JsonNode result = new ObjectMapper().readTree(raw);

        assertEquals(2, result.get("count").asInt());
        // Column may be uppercase in H2
        String nameCol = result.get("columns").get(0).asText();
        assertEquals("name", nameCol.toLowerCase());
        assertEquals("Alice", result.get("rows").get(0).get(nameCol).asText());
        assertEquals("Carol", result.get("rows").get(1).get(nameCol).asText());
    }

    @Test
    void selectReturnsEmptyRows() throws Exception {
        byte[] raw = SqlServerConnector.runQuery(h2Config("testdb"),
                "SELECT * FROM employees WHERE id = 9999");
        JsonNode result = new ObjectMapper().readTree(raw);

        assertEquals(0, result.get("count").asInt());
        assertTrue(result.get("rows").isArray());
        assertEquals(0, result.get("rows").size());
    }

    // ─── DDL / DML ───────────────────────────────────────────────────────────

    @Test
    void createTableAndInsertRow() throws Exception {
        SqlServerConnector.Config cfg = h2Config("ddldb");

        // CREATE TABLE
        byte[] raw1 = SqlServerConnector.runQuery(cfg,
                "CREATE TABLE IF NOT EXISTS items (id INT, val VARCHAR(50))");
        JsonNode r1 = new ObjectMapper().readTree(raw1);
        assertEquals(0, r1.get("count").asInt(), "DDL should return 0 rows");

        // INSERT
        byte[] raw2 = SqlServerConnector.runQuery(cfg,
                "INSERT INTO items VALUES (1, 'hello')");
        assertEquals(0, new ObjectMapper().readTree(raw2).get("count").asInt());

        // SELECT to confirm insert — column name may be uppercase in H2
        byte[] raw3 = SqlServerConnector.runQuery(cfg, "SELECT val FROM items WHERE id = 1");
        JsonNode r3 = new ObjectMapper().readTree(raw3);
        assertEquals(1, r3.get("count").asInt());
        String valCol = r3.get("columns").get(0).asText();
        assertEquals("val", valCol.toLowerCase());
        assertEquals("hello", r3.get("rows").get(0).get(valCol).asText());
    }

    // ─── Error cases ─────────────────────────────────────────────────────────

    @Test
    void unknownTableThrowsException() {
        assertThrows(Exception.class, () ->
                SqlServerConnector.runQuery(h2Config("testdb"),
                        "SELECT * FROM table_does_not_exist"));
    }

    // ─── Safe logging (no credentials in log output) ─────────────────────────

    @Test
    void safeDescriptionNeverContainsCredentials() {
        SqlServerConnector.Config cfg = new SqlServerConnector.Config(
                "db.example.com", 1433, "myapp",
                "secret_user", "super_secret_password",
                true, false);

        String desc = cfg.safeDescription();

        assertFalse(desc.contains(cfg.user),     "safeDescription must not contain user");
        assertFalse(desc.contains(cfg.password), "safeDescription must not contain password");
        assertTrue(desc.contains("db.example.com"), "safeDescription should contain host");
        assertTrue(desc.contains("myapp"),           "safeDescription should contain database");
        assertTrue(desc.contains("sqlserver"),        "safeDescription should contain driver");
    }

    @Test
    void jdbcUrlNeverContainsCredentials() {
        SqlServerConnector.Config cfg = new SqlServerConnector.Config(
                "db.example.com", 1433, "myapp",
                "secret_user", "super_secret_password",
                true, false);

        String url = cfg.jdbcUrl();

        assertFalse(url.contains(cfg.user),     "JDBC URL must not contain username");
        assertFalse(url.contains(cfg.password), "JDBC URL must not contain password");
    }

    // ─── JSON structure ───────────────────────────────────────────────────────

    @Test
    void jsonOutputHasExpectedStructure() throws Exception {
        byte[] raw = SqlServerConnector.runQuery(h2Config("testdb"),
                "SELECT id, name, department, salary FROM employees WHERE id = 1");
        JsonNode result = new ObjectMapper().readTree(raw);

        assertTrue(result.has("columns"), "result must have 'columns'");
        assertTrue(result.has("rows"),    "result must have 'rows'");
        assertTrue(result.has("count"),   "result must have 'count'");
        assertEquals(1, result.get("count").asInt());
        assertEquals(4, result.get("columns").size());
    }
}
