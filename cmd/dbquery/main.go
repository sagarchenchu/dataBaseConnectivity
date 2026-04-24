// Command dbquery executes a SQL query against a supported database and prints
// the result as JSON to stdout. All connection parameters (including credentials)
// are read from environment variables so they never appear in the OS process list.
//
// Environment variables:
//
//	DB_DRIVER     – sqlserver | mysql | postgres | sqlite  (default: sqlserver)
//	DB_HOST       – host name or IP                        (default: localhost)
//	DB_PORT       – TCP port                               (default: 1433)
//	DB_NAME       – database / schema name
//	DB_USER       – login username
//	DB_PASSWORD   – login password
//	DB_QUERY      – SQL statement to execute
//	DB_ENCRYPT    – true|false  (SQL Server TLS, default: true)
//	DB_TRUST_CERT – true|false  (skip CA validation, default: false)
//
// Flags:
//
//	--log-level info|error|none   controls verbosity (default: info)
//
// Exit codes:
//
//	0  success — JSON written to stdout
//	1  usage / configuration error
//	2  query execution error
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"os"
	"strconv"
	"strings"

	"github.com/sagarchenchu/dataBaseConnectivity/internal/connector"
)

// Version is set at build time via -ldflags "-X main.Version=vX.Y".
var Version = "dev"

func main() {
	logLevel := parseLogLevel(os.Args[1:])
	configureLogger(logLevel)

	cfg, query, err := configFromEnv()
	if err != nil {
		fmt.Fprintf(os.Stderr, "configuration error: %v\n", err)
		os.Exit(1)
	}

	result, err := connector.RunQuery(cfg, query)
	if err != nil {
		fmt.Fprintf(os.Stderr, "query error: %v\n", err)
		os.Exit(2)
	}

	// Pretty-print so that human readers get readable output; JSON is still
	// valid for programmatic consumers.
	var pretty map[string]interface{}
	if jsonErr := json.Unmarshal(result, &pretty); jsonErr == nil {
		enc := json.NewEncoder(os.Stdout)
		enc.SetIndent("", "  ")
		_ = enc.Encode(pretty)
	} else {
		fmt.Println(string(result))
	}
}

// configFromEnv reads all connection parameters from environment variables.
func configFromEnv() (connector.Config, string, error) {
	driver := envOr("DB_DRIVER", "sqlserver")
	host := envOr("DB_HOST", "localhost")
	portStr := envOr("DB_PORT", "1433")
	database := os.Getenv("DB_NAME")
	user := os.Getenv("DB_USER")
	password := os.Getenv("DB_PASSWORD")
	query := os.Getenv("DB_QUERY")
	encryptStr := envOr("DB_ENCRYPT", "true")
	trustStr := envOr("DB_TRUST_CERT", "false")

	if database == "" {
		return connector.Config{}, "", fmt.Errorf("DB_NAME must be set")
	}
	if query == "" {
		return connector.Config{}, "", fmt.Errorf("DB_QUERY must be set")
	}

	port, err := strconv.Atoi(portStr)
	if err != nil {
		return connector.Config{}, "", fmt.Errorf("invalid DB_PORT %q: %w", portStr, err)
	}

	encrypt, _ := strconv.ParseBool(encryptStr)
	trustCert, _ := strconv.ParseBool(trustStr)

	cfg := connector.Config{
		Driver:    driver,
		Host:      host,
		Port:      port,
		Database:  database,
		User:      user,
		Password:  password,
		Encrypt:   encrypt,
		TrustCert: trustCert,
	}
	return cfg, query, nil
}

// parseLogLevel extracts --log-level <value> from the argument list.
func parseLogLevel(args []string) string {
	for i, arg := range args {
		if arg == "--log-level" && i+1 < len(args) {
			return strings.ToLower(args[i+1])
		}
		if strings.HasPrefix(arg, "--log-level=") {
			return strings.ToLower(strings.TrimPrefix(arg, "--log-level="))
		}
	}
	return "info"
}

// configureLogger adjusts the standard logger based on the requested level.
func configureLogger(level string) {
	switch level {
	case "none":
		log.SetOutput(io.Discard)
	case "error":
		// Keep default output but mark messages so callers can filter.
		log.SetFlags(log.LstdFlags)
	default: // "info" or anything else
		log.SetFlags(log.LstdFlags)
	}
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
