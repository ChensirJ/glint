#Requires -Version 5.1
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
Write-Host "Building Spring Boot demo (profile spring-demo)..." -ForegroundColor Cyan
mvn -pl glint-examples -am -q -Pspring-demo -DskipTests package
$jar = Get-ChildItem "$Root\glint-examples\target\glint-examples-*.jar" | Where-Object { $_.Name -notmatch "original" } | Select-Object -First 1
if (-not $jar) { throw "Repackaged jar not found. Ensure -Pspring-demo ran." }
Write-Host "Starting $($jar.Name) ..." -ForegroundColor Green
java -jar $jar.FullName
