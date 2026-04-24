# dataBaseConnectivity

A **`dbquery`** executable that connects to **Microsoft SQL Server** using the
official [`com.microsoft.sqlserver:mssql-jdbc:13.4.0.jre11`](https://github.com/microsoft/mssql-jdbc)
driver, executes a SQL query, and returns the results as JSON — designed to be
called from any language (including Java) through a simple subprocess interface.

---

## How it works

```
  Your Java app
       │
       │  ProcessBuilder / Runtime.exec()
       ▼
  dbquery.bat / dbquery.sh  ──► java -jar dbquery.jar
                                        │
                                        │  mssql-jdbc 13.4.0.jre11
                                        ▼
                                  SQL Server
                                        │
                           ┌───────────┴────────────┐
                     stdout (JSON)             stderr (safe logs)
                     {"columns":...             [INFO] Connected …
                      "rows":...                [INFO] Query returned …
                      "count":N}
```

Credentials (`DB_USER`, `DB_PASSWORD`) are passed via **environment variables**,
never via command-line flags, so they never appear in the OS process list.

---

## Features

| Feature | Details |
|---|---|
| **JDBC driver** | `com.microsoft.sqlserver:mssql-jdbc:13.4.0.jre11` |
| **Executable** | Fat-JAR (`dbquery.jar`) + launcher scripts (`dbquery.sh` / `dbquery.bat`) |
| **Safe logging** | Logs host / port / database name only — **credentials are never logged** |
| **JSON output** | Results on stdout; log lines on stderr (capture independently) |
| **Flexible config** | CLI flags *or* environment variables |
| **Java integration** | `DatabaseQueryClient.java` — drop-in helper class for calling the exe from Java |

---

## Quick start

### 1. Build the fat-JAR

```bash
mvn package
# Produces: target/dbquery.jar
```

### 2. Deploy

Copy the three files to the same directory:

```
dbquery.jar   ← built by mvn package
dbquery.sh    ← Unix/macOS launcher  (chmod +x dbquery.sh)
dbquery.bat   ← Windows launcher
```

### 3. Run

```bash
# Linux / macOS
./dbquery.sh \
  --host     sqlserver.example.com \
  --port     1433 \
  --database MyDatabase \
  --user     appuser \
  --password s3cr3t \
  --query    "SELECT TOP 5 id, name FROM dbo.Customers"

# Windows
dbquery.bat --host sqlserver.example.com --port 1433 --database MyDatabase ^
            --user appuser --password s3cr3t ^
            --query "SELECT TOP 5 id, name FROM dbo.Customers"
```

---

## Download pre-built release

Pre-built artifacts are attached to every
[GitHub Release](../../releases):

| File | Description |
|---|---|
| `dbquery.jar` | Fat-JAR (all dependencies bundled, including mssql-jdbc) |
| `dbquery.sh` | Unix/macOS launcher |
| `dbquery.bat` | Windows launcher |

Place all three files in the same directory and run `chmod +x dbquery.sh`.

---

## CLI reference

```
Usage: dbquery [flags]

Flags (all can also be set via environment variables):
  --host         DB_HOST      SQL Server host           (default: localhost)
  --port         DB_PORT      SQL Server port           (default: 1433)
  --database     DB_NAME      Database / schema name
  --user         DB_USER      Username
  --password     DB_PASSWORD  Password  *** never logged ***
  --query        DB_QUERY     SQL statement to execute
  --query-file                Path to a .sql file containing the query
  --encrypt      true|false   TLS encryption            (default: true)
  --trust-cert   true|false   Trust server certificate  (default: false)
  --log-level    info|error|none  Log verbosity         (default: info)
```

### Environment variables

```bash
export DB_HOST=sqlserver.example.com
export DB_PORT=1433
export DB_NAME=MyDatabase
export DB_USER=appuser
export DB_PASSWORD=s3cr3t          # never appears in logs
./dbquery.sh --query "SELECT COUNT(*) AS total FROM dbo.Orders"
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
[INFO] Connecting to database — driver=sqlserver host=sqlserver.example.com port=1433 database=MyDatabase
[INFO] Connected successfully — driver=sqlserver host=sqlserver.example.com port=1433 database=MyDatabase
[INFO] Executing query
[INFO] Query returned 2 row(s)
```

Use `--log-level none` to suppress all log output.

---

## Calling from Java

Copy [`java/DatabaseQueryClient.java`](java/DatabaseQueryClient.java) into your project
(it has no extra dependencies — just standard Java SE).

```java
import com.dbconnectivity.DatabaseQueryClient;

DatabaseQueryClient client = new DatabaseQueryClient.Builder()
    // Path to dbquery.bat (Windows) or dbquery.sh (Linux/macOS)
    .launcherPath("C:/tools/dbquery.bat")
    .host("sqlserver.example.com")
    .port(1433)
    .database("MyDatabase")
    .user("appuser")
    .password("s3cr3t")              // passed via env var — NEVER logged
    .encrypt(true)
    .trustServerCertificate(false)   // set true only for local/dev servers
    .logLevel("info")
    .build();

// Returns JSON string: {"columns":[...], "rows":[{...}], "count":N}
String json = client.query("SELECT TOP 10 id, name FROM dbo.Customers");
System.out.println(json);
```

The Java client:
- Passes credentials as **environment variables** (not CLI flags), invisible in the OS process list
- Forwards the tool's log lines (safe — no credentials) to `java.util.logging`
- Drains stdout and stderr concurrently to prevent OS pipe-buffer deadlocks
- Throws `DatabaseQueryClient.DatabaseQueryException` on non-zero exit

---

## Local / self-signed SQL Server

For Azure SQL or SQL Server with a trusted CA certificate, the defaults
(`--encrypt true`, `--trust-cert false`) work without changes.

For a **local or Docker SQL Server** using a self-signed certificate:

```bash
./dbquery.sh --host localhost --port 1433 --database testdb \
  --user sa --password YourPassword123 \
  --encrypt true --trust-cert true \
  --query "SELECT @@VERSION"
```

Or in Java:

```java
.encrypt(true)
.trustServerCertificate(true)   // local/dev only
```

---

## Security notes

1. **Credentials are never logged.** Log lines contain only host / port / database.
2. **Use environment variables or a secrets manager** — avoid passing `--password` as a CLI
   flag since flags are visible in `ps` and Task Manager.
3. The executable is **stateless** — no connection parameters are stored between invocations.

---

## Running tests

```bash
# Java tests (H2 in-memory — no SQL Server required)
mvn test

# Go connector tests
go test ./...
```
