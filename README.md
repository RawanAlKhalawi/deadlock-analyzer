# Deadlock Analyzer

Deadlock Analyzer is a Java command-line tool that loads pre-extracted transaction traces, analyzes SQL access and lock-order conflicts with a Z3-backed solver, and generates deadlock reports. It is the analyzer component of the artifact accompanying the paper on predicting database deadlocks in ORM/JPA-based applications.

## Requirements

- Java 17
- Maven 3.9 or later
- Docker, if using the container image

The project uses Maven and packages dependencies into an executable shaded JAR.

## Build and run

Build the executable JAR:

```bash
mvn clean package
```

Run the analyzer against a directory containing JSON trace files:

```bash
java -jar target/deadlock-analyzer-1.0-SNAPSHOT.jar \
  --traces src/test/resources/sample-traces \
  --output reports/example
```

The required options are `--traces` and `--output`. Optional options are:

- `--timeout <seconds>` — Z3 solver timeout; defaults to `30`
- `--format json|html|both` — report format; defaults to `both`
- `--verbose` — enable debug logging

The Docker image is published as:

```text
docker.io/rawanalkhalawi/deadlock-analyzer:latest
```

For example:

```bash
docker run --rm \
  -v "$PWD/src/test/resources/sample-traces:/input:ro" \
  -v "$PWD/reports/example:/output" \
  docker.io/rawanalkhalawi/deadlock-analyzer:latest \
  --traces /input --output /output
```

## Usage example

Each input JSON file must contain a top-level `transactions` array. A transaction contains an `id` and `statements`; statements include fields such as `type`, `rawSql`, `tableNames`, `queryConditions`, and `executionOrder`. The repository includes an example at `src/test/resources/sample-traces/finishOrder-trace.json`.

Running the example above produces output of this form:

```text
Loading traces from: src/test/resources/sample-traces
Loaded 2 transaction(s)
Running deadlock analysis...
Generating reports...
Analysis complete. Found 1 deadlock(s).
Reports saved to: reports/example
  - deadlock-report.json
  - deadlock-report.html
```

The JSON report contains `totalDeadlocksFound` and a `deadlocks` array with the involved transactions, conflict description, cycle, and suggested fix. The HTML report provides the same analysis in a browser-readable format.

## Paper

This repository accompanies the paper **"PredLock: Static Code Analysis for Detecting Database Deadlocks in CI/CD Pipelines"** (under review).

## AI Assistance

Parts of the code in this repository were developed with the assistance of AI tools (Claude and ChatGPT). All generated code was reviewed, tested, and validated by the authors.
