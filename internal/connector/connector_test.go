package connector_test

import (
"encoding/json"
"os"
"path/filepath"
"strings"
"testing"

"github.com/sagarchenchu/dataBaseConnectivity/internal/connector"
)

// TestRunQuery_SQLite exercises the full pipeline against a temporary SQLite
// database so no external SQL Server instance is required.
func TestRunQuery_SQLite(t *testing.T) {
dir := t.TempDir()
dbPath := filepath.Join(dir, "test.db")

cfg := connector.Config{Driver: "sqlite", Database: dbPath}

// Create table and insert rows
for _, stmt := range []string{
`CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, email TEXT)`,
`INSERT INTO users VALUES (1, 'Alice', 'alice@example.com')`,
`INSERT INTO users VALUES (2, 'Bob',   'bob@example.com')`,
} {
if _, err := connector.RunQuery(cfg, stmt); err != nil {
t.Fatalf("setup query %q failed: %v", stmt, err)
}
}

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
cfg := connector.Config{Driver: "sqlite", Database: filepath.Join(dir, "empty.db")}

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
// credentials for any driver.
func TestConfig_SafeDescription(t *testing.T) {
for _, tc := range []struct {
driver string
}{
{"sqlserver"},
{"mysql"},
{"postgres"},
} {
cfg := connector.Config{
Driver:    tc.driver,
Host:      "db.example.com",
Port:      1433,
Database:  "myapp",
User:      "secret_user",
Password:  "super_secret",
Encrypt:   true,
TrustCert: false,
}

desc := cfg.SafeDescription()

if strings.Contains(desc, cfg.User) {
t.Errorf("[%s] SafeDescription leaked User in: %s", tc.driver, desc)
}
if strings.Contains(desc, cfg.Password) {
t.Errorf("[%s] SafeDescription leaked Password in: %s", tc.driver, desc)
}
for _, want := range []string{tc.driver, "db.example.com", "myapp"} {
if !strings.Contains(desc, want) {
t.Errorf("[%s] SafeDescription missing %q in: %s", tc.driver, want, desc)
}
}
}
}

// TestSqlServerDSN_NoCredentials verifies that the SQL Server DSN contains
// the user/password only in the URL authority (where the driver needs them),
// and that SafeDescription never exposes them at all.
func TestSqlServerDSN_NoCredentials(t *testing.T) {
cfg := connector.Config{
Driver:    "sqlserver",
Host:      "sqlserver.example.com",
Port:      1433,
Database:  "MyDB",
User:      "sa",
Password:  "P@$$w0rd!",
Encrypt:   true,
TrustCert: false,
}

desc := cfg.SafeDescription()
if strings.Contains(desc, cfg.User) || strings.Contains(desc, cfg.Password) {
t.Errorf("SafeDescription leaked credentials: %s", desc)
}
if !strings.Contains(desc, "sqlserver.example.com") {
t.Errorf("SafeDescription missing host: %s", desc)
}
}

// TestRunQuery_ViaEnvQuery tests reading a query value from a variable
// (simulating how the CLI passes DB_QUERY from an environment variable).
func TestRunQuery_ViaEnvQuery(t *testing.T) {
dir := t.TempDir()
cfg := connector.Config{Driver: "sqlite", Database: filepath.Join(dir, "env.db")}

for _, stmt := range []string{
`CREATE TABLE items (id INTEGER, val TEXT)`,
`INSERT INTO items VALUES (42, 'hello')`,
} {
if _, err := connector.RunQuery(cfg, stmt); err != nil {
t.Fatalf("setup: %v", err)
}
}

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
