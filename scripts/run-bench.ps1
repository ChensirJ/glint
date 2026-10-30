锘?ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot\..
mvn -pl glint-bench -am -q package
Write-Host "=== ComparisonBench (/hello) ==="
mvn -pl glint-bench -q exec:java "-Dexec.mainClass=io.github.glint.bench.ComparisonBench" "-Dexec.args=2 5 64"
Write-Host "=== StaticBench ==="
mvn -pl glint-bench -q -DincludeScope=runtime dependency:build-classpath "-Dmdep.outputFile=glint-bench\cp.txt"
$cp = Get-Content glint-bench\cp.txt -Raw
java -cp "glint-bench\target\classes;glint-core\target\classes;$cp" io.github.glint.bench.StaticBench 2 5 64
