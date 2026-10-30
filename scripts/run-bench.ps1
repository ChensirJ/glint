# Reproduce every number in docs/bench-results.md (run from any directory).
# Order matters: ComparisonBench rewrites the file, StaticBench and RealisticBench
# then replace their own sections at the end.
#   powershell -ExecutionPolicy Bypass -File scripts/run-bench.ps1 [-Rounds 3] [-Warmup 3] [-Duration 8]
param([int]$Rounds = 3, [int]$Warmup = 3, [int]$Duration = 8)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot\..
mvn -B -q -pl glint-bench -am -DskipTests package
mvn -B -q -pl glint-bench -DincludeScope=runtime dependency:build-classpath "-Dmdep.outputFile=glint-bench\cp.txt"
$cp = "glint-bench\target\classes;glint-core\target\classes;" + (Get-Content glint-bench\cp.txt -Raw).Trim()
Write-Host "=== 1/3 ComparisonBench (/hello, 64 clients) ==="
java -cp $cp io.github.glint.bench.ComparisonBench 2 5 64
Write-Host "=== 2/3 StaticBench (1 KiB / 100 KiB / 2 MiB) ==="
java -cp $cp io.github.glint.bench.StaticBench 2 5 64
Write-Host "=== 3/3 RealisticBench (separate server JVMs) ==="
java -cp $cp io.github.glint.bench.RealisticBench $Rounds $Warmup $Duration
