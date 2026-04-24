# dataBaseConnectivity — `dbquery.exe`

A **native executable** (`dbquery.exe` on Windows, `dbquery` on Linux/macOS)
that connects to **Microsoft SQL Server**, executes a SQL query, and returns
the results as JSON — with **no Java runtime required** on the target machine.

Built with Go and [`github.com/microsoft/go-mssqldb`](https://github.com/microsoft/go-mssqldb),
the official Microsoft Go SQL Server driver.

---

## How it works

```
  Your Java app (or any other caller)
         │
         │  ProcessBuilder / Runtime.exec() / shell
         ▼
    dbquery.exe   ──────────────────────────────► SQL Server
    (native binary,                                     │
     no JVM needed)                       ┌─────────────┴────────────┐
                                    stdout (JSON)              stderr (safe logs)
                                    {"columns":...              [INFO] Connecting …
                                     "rows":...                 [INFO] Connected …
                                     "count":N}                 [INFO] Query returned …
```

Credentials (`--password` / `DB_PASSWORD`) are passed via **environment variables**,
never via command-line flags, so they never appear in the OS process list.

---

## Download

Pre-built binaries are attached to every [GitHub Release](../../releases):

| Platform | File |
|---|---|
| **Windows 64-bit** | `dbquery.exe` |
| Linux 64-bit | `dbquery-linux-amd64` |
| macOS 64-bit | `dbquery-macos-amd64` |

---

## Build from source

```bash
git clone https://github.com/sagarchenchu/dataBaseConnectivity.git
cd dataBaseConnectivity

# Windows .exe (cross-compile from Linux/macOS)
GOOS=windows GOARCH=amd64 CGO_ENABLED=0 \
  go build -ldflags="-s -w" -o dbquery.exe ./cmd/dbquery

# Linux binary
CGO_ENABLED=0 go build -ldflags="-s -w" -o dbquery ./cmd/dbquery
```

---

## CLI reference

```
Usage of dbquery:
  --driver       DB_DRIVER      Database driver (default: sqlserver)
  --host         DB_HOST        SQL Server host (default: localhost)
  --port         DB_PORT        SQL Server port (default: 1433)
  --database     DB_NAME        Database / schema name
  --user         DB_USER        Username
  --password     DB_PASSWORD    Password  *** NEVER logged ***
  --query        DB_QUERY       SQL statement to execute
  --query-file                  Path to a .sql file containing the query
  --encrypt      DB_ENCRYPT     Use TLS encryption  true|false  (default: true)
  --trust-cert   DB_TRUST_CERT  Trust server certificate  true|false  (default: false)
  --log-level                   info | error | none  (default: info)
```

### Quick example

```bat
rem Windows
dbquery.exe ^
  --host     sqlserver.example.com ^
  --port     1433 ^
  --database MyDatabase ^
  --user     appuser ^
  --password s3cr3t ^
  --query    "SELECT TOP 5 id, name FROM dbo.Customers"
```

```bash
# Linux / macOS
./dbquery \
  --host     sqlserver.example.com \
  --port     1433 \
  --database MyDatabase \
  --user     appuser \
  --password s3cr3t \
  --query    "SELECT TOP 5 id, name FROM dbo.Customers"
```

### Using environment variables (recommended — hides password from process list)

```bash
export DB_HOST=sqlserver.example.com
export DB_PORT=1433
export DB_NAME=MyDatabase
export DB_USER=appuser
export DB_PASSWORD=s3cr3t     # never written to any log
./dbquery --query "SELECT COUNT(*) AS total FROM dbo.Orders"
```

### Read query from a file

```bash
./dbquery \
  --host sqlserver.example.com --database MyDatabase \
  --user appuser --password s3cr3t \
  --query-file report.sql
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

Safe log messages (no credentials) are written to **stderr**:

```
2026/04/24 10:00:00 [INFO] Connecting to database — driver=sqlserver host=sqlserver.example.com port=1433 database=MyDatabase
2026/04/24 10:00:00 [INFO] Connected successfully — driver=sqlserver host=sqlserver.example.com port=1433 database=MyDatabase
2026/04/24 10:00:00 [INFO] Executing query
2026/04/24 10:00:00 [INFO] Query returned 2 row(s)
```

Use `--log-level none` to suppress all log output.

---

## Calling from Java

Copy [`java/DatabaseQueryClient.java`](java/DatabaseQueryClient.java) into your
project (no extra dependencies — plain Java SE).

```java
import com.dbconnectivity.DatabaseQueryClient;

DatabaseQueryClient client = new DatabaseQueryClient.Builder()
    .executablePath("C:/tools/dbquery.exe")   // full path to the native binary
    .host("sqlserver.example.com")
    .port(1433)
    .database("MyDatabase")
    .user("appuser")
    .password("s3cr3t")               // passed via env var — NEVER logged
    .encrypt(true)
    .trustServerCertificate(false)    // set true only for local/Docker SQL Server
    .logLevel("info")
    .build();

// Returns JSON: {"columns":[...], "rows":[{...}], "count":N}
String json = client.query("SELECT TOP 10 id, name FROM dbo.Customers");
System.out.println(json);
```

The Java client:
- Passes credentials as **environment variables** (invisible in Task Manager / ps)
- Forwards the tool's safe log lines to `java.util.logging`
- Drains stdout and stderr concurrently to prevent OS pipe-buffer deadlocks
- Throws `DatabaseQueryClient.DatabaseQueryException` on non-zero exit

---

## Local / self-signed SQL Server

For Azure SQL or on-prem SQL Server with a trusted CA certificate, the defaults
(`--encrypt true`, `--trust-cert false`) work without any changes.

For a **local or Docker SQL Server** that uses a self-signed certificate:

```bat
dbquery.exe --host localhost --port 1433 --database testdb ^
  --user sa --password YourPassword123 ^
  --encrypt true --trust-cert true ^
  --query "SELECT @@VERSION"
```

Or in Java:
```java
.encrypt(true)
.trustServerCertificate(true)   // local/dev only
```

---

## Security notes

1. **Credentials are never logged.** Log lines only contain `driver / host / port / database`.
2. **Use environment variables or a secrets manager** — avoid `--password` as a CLI flag
   since flags are visible in `ps` and Task Manager.
3. The binary is **stateless** — no connection parameters persist between invocations.

---

## Running tests

```bash
go test ./...
```
