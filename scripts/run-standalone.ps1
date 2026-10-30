#Requires -Version 5.1
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
Write-Host "Building Glint examples..." -ForegroundColor Cyan
mvn -pl glint-examples -am -q -DskipTests package
Write-Host "Starting StandaloneDemo on :8080 (Ctrl+C to stop)" -ForegroundColor Green
mvn -pl glint-examples -q exec:java "-Dexec.mainClass=io.github.glint.examples.StandaloneDemo"
