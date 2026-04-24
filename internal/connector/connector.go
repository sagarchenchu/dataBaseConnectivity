// Package connector handles database connectivity and query execution.
// Credentials are never written to logs; only safe metadata (driver, host, database) is logged.
package connector

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"strings"

	// MySQL driver
	_ "github.com/go-sql-driver/mysql"
	// PostgreSQL driver
	_ "github.com/lib/pq"
	// SQL Server driver
	_ "github.com/microsoft/go-mssqldb"
	// SQLite driver (pure Go, no CGO required)
	_ "modernc.org/sqlite"
)

// Config holds the connection parameters for a database.
// Credentials (User/Password) are intentionally excluded from the String() method
// and from all log output.
type Config struct {
	Driver   string // mysql | postgres | sqlserver | sqlite
	Host     string
	Port     int
	Database string
	User     string
	Password string
}

// SafeDescription returns a log-safe description of the connection (no credentials).
func (c Config) SafeDescription() string {
	if c.Driver == "sqlite" {
		return fmt.Sprintf("driver=%s database=%s", c.Driver, c.Database)
	}
	return fmt.Sprintf("driver=%s host=%s port=%d database=%s", c.Driver, c.Host, c.Port, c.Database)
}

// dsn builds the driver-specific Data Source Name.
func (c Config) dsn() (string, error) {
	switch strings.ToLower(c.Driver) {
	case "mysql":
		return fmt.Sprintf("%s:%s@tcp(%s:%d)/%s?parseTime=true", c.User, c.Password, c.Host, c.Port, c.Database), nil
	case "postgres", "postgresql":
		return fmt.Sprintf("host=%s port=%d user=%s password=%s dbname=%s sslmode=disable", c.Host, c.Port, c.User, c.Password, c.Database), nil
	case "sqlserver", "mssql":
		return fmt.Sprintf("sqlserver://%s:%s@%s:%d?database=%s", c.User, c.Password, c.Host, c.Port, c.Database), nil
	case "sqlite", "sqlite3":
		return c.Database, nil
	default:
		return "", fmt.Errorf("unsupported driver %q; supported drivers: mysql, postgres, sqlserver, sqlite", c.Driver)
	}
}

// driverName returns the registered driver name for database/sql.
func (c Config) driverName() string {
	switch strings.ToLower(c.Driver) {
	case "postgres", "postgresql":
		return "postgres"
	case "sqlserver", "mssql":
		return "sqlserver"
	case "sqlite", "sqlite3":
		return "sqlite"
	default:
		return strings.ToLower(c.Driver)
	}
}

// QueryResult holds the columns and rows returned from a query.
type QueryResult struct {
	Columns []string                 `json:"columns"`
	Rows    []map[string]interface{} `json:"rows"`
	Count   int                      `json:"count"`
}

// RunQuery connects to the database, executes query, and returns a JSON-encoded result.
// Credentials are not logged at any point.
func RunQuery(cfg Config, query string) ([]byte, error) {
	log.Printf("[INFO] Connecting to database — %s", cfg.SafeDescription())

	dsn, err := cfg.dsn()
	if err != nil {
		return nil, err
	}

	db, err := sql.Open(cfg.driverName(), dsn)
	if err != nil {
		return nil, fmt.Errorf("failed to open connection: %w", err)
	}
	defer db.Close()

	if err := db.Ping(); err != nil {
		return nil, fmt.Errorf("failed to reach database (%s): %w", cfg.SafeDescription(), err)
	}

	log.Printf("[INFO] Connected successfully — %s", cfg.SafeDescription())
	log.Printf("[INFO] Executing query")

	rows, err := db.Query(query)
	if err != nil {
		return nil, fmt.Errorf("query execution failed: %w", err)
	}
	defer rows.Close()

	cols, err := rows.Columns()
	if err != nil {
		return nil, fmt.Errorf("failed to retrieve column names: %w", err)
	}

	var records []map[string]interface{}
	for rows.Next() {
		values := make([]interface{}, len(cols))
		valuePtrs := make([]interface{}, len(cols))
		for i := range values {
			valuePtrs[i] = &values[i]
		}

		if err := rows.Scan(valuePtrs...); err != nil {
			return nil, fmt.Errorf("error scanning row: %w", err)
		}

		record := make(map[string]interface{}, len(cols))
		for i, col := range cols {
			v := values[i]
			if b, ok := v.([]byte); ok {
				record[col] = string(b)
			} else {
				record[col] = v
			}
		}
		records = append(records, record)
	}

	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("error iterating rows: %w", err)
	}

	result := QueryResult{
		Columns: cols,
		Rows:    records,
		Count:   len(records),
	}

	log.Printf("[INFO] Query returned %d row(s)", result.Count)

	return json.Marshal(result)
}
