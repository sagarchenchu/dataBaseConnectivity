package connector_test

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/sagarchenchu/dataBaseConnectivity/internal/connector"
)

// TestRunQuery_SQLite exercises the full pipeline against a temporary SQLite
// database so no external database server is required.
func TestRunQuery_SQLite(t *testing.T) {
	// Create a temp directory and SQLite database file
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "test.db")

	cfg := connector.Config{
		Driver:   "sqlite",
		Database: dbPath,
	}

	// Create table and insert rows
	setup := []string{
		`CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, email TEXT)`,
		`INSERT INTO users VALUES (1, 'Alice', 'alice@example.com')`,
		`INSERT INTO users VALUES (2, 'Bob',   'bob@example.com')`,
	}
	for _, stmt := range setup {
		if _, err := connector.RunQuery(cfg, stmt); err != nil {
			t.Fatalf("setup query %q failed: %v", stmt, err)
		}
	}

	// SELECT query
	raw, err := connector.RunQuery(cfg, `SELECT id, name FROM users ORDER BY id`)
	if err != nil {
		t.Fatalf("SELECT failed: %v", err)
	}

	var result struct {
		Columns []string                 `json:"columns"`
		Rows    []map[string]interface{} `json:"rows"`
		Count   int                      `json:"count"`
	}
	if err := json.Unmarshal(raw, &result); err != nil {
		t.Fatalf("JSON unmarshal failed: %v\nraw: %s", err, raw)
	}

	if result.Count != 2 {
		t.Errorf("expected 2 rows, got %d", result.Count)
	}
	if len(result.Columns) != 2 {
		t.Errorf("expected 2 columns, got %v", result.Columns)
	}
	if result.Columns[0] != "id" || result.Columns[1] != "name" {
		t.Errorf("unexpected columns: %v", result.Columns)
	}
	if result.Rows[0]["name"] != "Alice" {
		t.Errorf("unexpected first row: %v", result.Rows[0])
	}
}

// TestRunQuery_EmptyResult verifies that a query returning no rows still
// returns valid JSON with an empty rows array.
func TestRunQuery_EmptyResult(t *testing.T) {
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "empty.db")

	cfg := connector.Config{Driver: "sqlite", Database: dbPath}

	if _, err := connector.RunQuery(cfg, `CREATE TABLE t (x INTEGER)`); err != nil {
		t.Fatalf("create table: %v", err)
	}

	raw, err := connector.RunQuery(cfg, `SELECT * FROM t`)
	if err != nil {
		t.Fatalf("SELECT from empty table: %v", err)
	}

	var result struct {
		Count int `json:"count"`
	}
	if err := json.Unmarshal(raw, &result); err != nil {
		t.Fatalf("JSON unmarshal: %v", err)
	}
	if result.Count != 0 {
		t.Errorf("expected 0 rows, got %d", result.Count)
	}
}

// TestRunQuery_InvalidQuery verifies that a bad SQL statement returns an error.
func TestRunQuery_InvalidQuery(t *testing.T) {
	dir := t.TempDir()
	cfg := connector.Config{Driver: "sqlite", Database: filepath.Join(dir, "x.db")}

	_, err := connector.RunQuery(cfg, `SELECT * FROM table_does_not_exist`)
	if err == nil {
		t.Fatal("expected an error for unknown table, got nil")
	}
}

// TestRunQuery_UnsupportedDriver ensures an unsupported driver name returns an
// error rather than panicking.
func TestRunQuery_UnsupportedDriver(t *testing.T) {
	cfg := connector.Config{Driver: "oracle", Database: "x"}
	_, err := connector.RunQuery(cfg, `SELECT 1`)
	if err == nil {
		t.Fatal("expected error for unsupported driver")
	}
}

// TestConfig_SafeDescription confirms that SafeDescription never reveals
// credentials.
func TestConfig_SafeDescription(t *testing.T) {
	cfg := connector.Config{
		Driver:   "mysql",
		Host:     "db.example.com",
		Port:     3306,
		Database: "myapp",
		User:     "secret_user",
		Password: "super_secret",
	}

	desc := cfg.SafeDescription()

	for _, sensitive := range []string{cfg.User, cfg.Password} {
		if sensitive != "" && containsString(desc, sensitive) {
			t.Errorf("SafeDescription() leaked sensitive value %q in: %s", sensitive, desc)
		}
	}

	// Should contain the non-sensitive parts
	for _, expected := range []string{"mysql", "db.example.com", "myapp"} {
		if !containsString(desc, expected) {
			t.Errorf("SafeDescription() missing %q in: %s", expected, desc)
		}
	}
}

func containsString(s, substr string) bool {
	return len(substr) > 0 && len(s) >= len(substr) && (s == substr ||
		func() bool {
			for i := 0; i <= len(s)-len(substr); i++ {
				if s[i:i+len(substr)] == substr {
					return true
				}
			}
			return false
		}())
}

// TestRunQuery_WithQueryFile verifies reading a query from a file works via the
// OS (exercised at the connector level using os.ReadFile style).
func TestRunQuery_ViaEnvQuery(t *testing.T) {
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "env.db")
	cfg := connector.Config{Driver: "sqlite", Database: dbPath}

	if _, err := connector.RunQuery(cfg, `CREATE TABLE items (id INTEGER, val TEXT)`); err != nil {
		t.Fatalf("create: %v", err)
	}
	if _, err := connector.RunQuery(cfg, `INSERT INTO items VALUES (42, 'hello')`); err != nil {
		t.Fatalf("insert: %v", err)
	}

	// Simulate passing query via env by reading from environment in the test
	os.Setenv("_TEST_Q", `SELECT val FROM items WHERE id=42`)
	q := os.Getenv("_TEST_Q")

	raw, err := connector.RunQuery(cfg, q)
	if err != nil {
		t.Fatalf("query: %v", err)
	}

	var result struct {
		Rows  []map[string]interface{} `json:"rows"`
		Count int                      `json:"count"`
	}
	if err := json.Unmarshal(raw, &result); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if result.Count != 1 || result.Rows[0]["val"] != "hello" {
		t.Errorf("unexpected result: %v", result)
	}
}
