#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
echo "Building Glint examples..."
mvn -pl glint-examples -am -q -DskipTests package
echo "Starting StandaloneDemo on :8080 (Ctrl+C to stop)"
mvn -pl glint-examples -q exec:java -Dexec.mainClass=io.github.glint.examples.StandaloneDemo
