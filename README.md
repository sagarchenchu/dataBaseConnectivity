# dataBaseConnectivity

A cross-platform **`dbquery`** executable that connects to a relational database,
runs a SQL query, and returns the results as JSON — designed to be called from any
language (including Java) through a simple subprocess interface.

---

## Features

| Feature | Details |
|---|---|
| **Supported databases** | MySQL, PostgreSQL, SQL Server, SQLite |
| **Single binary** | No runtime dependencies; ships as a standalone `.exe` on Windows, binary on Linux/macOS |
| **Safe logging** | Logs the database host/name/driver to stderr — **credentials are never logged** |
| **JSON output** | Results written to stdout; logs/errors written to stderr (safe to capture independently) |
| **Flexible config** | Accept parameters via CLI flags *or* environment variables |
| **Java integration** | Bundled `DatabaseQueryClient.java` passes credentials via environment variables (not visible in the process list) |

---

## Download

Pre-built binaries are attached to every
[GitHub Release](../../releases). Download the binary for your platform:

| Platform | Binary |
|---|---|
| Windows (64-bit) | `dbquery-windows-amd64.exe` |
| Linux (64-bit) | `dbquery-linux-amd64` |
| macOS (64-bit) | `dbquery-macos-amd64` |

Rename the binary to `dbquery` (or `dbquery.exe`) and put it on your `PATH`.

---

## Build from source

```bash
git clone https://github.com/sagarchenchu/dataBaseConnectivity.git
cd dataBaseConnectivity
go build -o dbquery ./cmd/dbquery      # Linux / macOS
go build -o dbquery.exe ./cmd/dbquery  # Windows (cross-compile from Linux: GOOS=windows go build ...)
```

---

## Usage — CLI flags

```
dbquery [flags]

Flags:
  --driver      mysql | postgres | sqlserver | sqlite
  --host        database host (default: localhost)
  --port        database port (default: driver default)
  --database    database / schema name (or SQLite file path)
  --user        username
  --password    password  *** never logged ***
  --query       SQL statement to execute
  --query-file  path to a .sql file containing the query
  --log-level   info | error | none  (default: info)
```

### Example (MySQL)

```bash
dbquery \
  --driver    mysql \
  --host      db.example.com \
  --port      3306 \
  --database  myapp \
  --user      appuser \
  --password  s3cr3t \
  --query     "SELECT id, name FROM customers LIMIT 5"
```

### Example (SQLite)

```bash
dbquery --driver sqlite --database ./my.db --query "SELECT * FROM orders"
```

---

## Usage — environment variables

All flags have environment-variable equivalents (useful for containers / CI):

| Flag | Environment variable |
|---|---|
| `--driver` | `DB_DRIVER` |
| `--host` | `DB_HOST` |
| `--port` | `DB_PORT` |
| `--database` | `DB_NAME` |
| `--user` | `DB_USER` |
| `--password` | `DB_PASSWORD` |
| `--query` | `DB_QUERY` |

```bash
export DB_DRIVER=postgres
export DB_HOST=db.example.com
export DB_NAME=myapp
export DB_USER=appuser
export DB_PASSWORD=s3cr3t
dbquery --query "SELECT count(*) AS total FROM orders"
```

---

## Output format

Results are written as a single JSON line to **stdout**:

```json
{
  "columns": ["id", "name", "email"],
  "rows": [
    {"id": 1, "name": "Alice", "email": "alice@example.com"},
    {"id": 2, "name": "Bob",   "email": "bob@example.com"}
  ],
  "count": 2
}
```

Log messages (safe — no credentials) are written to **stderr**:

```
2026/04/24 10:00:00 [INFO] Connecting to database — driver=mysql host=db.example.com port=3306 database=myapp
2026/04/24 10:00:00 [INFO] Connected successfully — driver=mysql host=db.example.com port=3306 database=myapp
2026/04/24 10:00:00 [INFO] Executing query
2026/04/24 10:00:00 [INFO] Query returned 2 row(s)
```

---

## Calling from Java

Copy [`java/DatabaseQueryClient.java`](java/DatabaseQueryClient.java) into your project.

```java
import com.dbconnectivity.DatabaseQueryClient;

DatabaseQueryClient client = new DatabaseQueryClient.Builder()
    .executablePath("/opt/tools/dbquery")  // or "dbquery.exe" on Windows
    .driver("mysql")
    .host("db.example.com")
    .port(3306)
    .database("myapp")
    .user("appuser")
    .password("s3cr3t")          // passed as env var — NOT in the process list
    .logLevel("info")
    .build();

String json = client.query("SELECT id, name FROM customers LIMIT 10");
System.out.println(json);
```

The Java client:
- Passes credentials as **environment variables** (not CLI flags) so they never appear in the OS process list
- Forwards the tool's log lines (safe — no credentials) to `java.util.logging`
- Throws `DatabaseQueryClient.DatabaseQueryException` on non-zero exit
- Streams stdout and stderr concurrently to avoid buffer deadlocks

---

## Security notes

1. **Credentials are never logged.** The log lines produced by the tool contain only the driver name, host, and database name.
2. **Use environment variables or a secrets manager** rather than hardcoding credentials in command-line flags (flags are visible in `ps` / Task Manager).
3. **The executable does not store** any connection parameters between invocations.

---

## Running tests

```bash
go test ./...
```
