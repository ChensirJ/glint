# Run Glint vs Tomcat ComparisonBench and refresh docs/bench-results.md
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot\..
mvn -q -pl glint-bench -am package -DskipTests
Push-Location glint-bench
mvn -q dependency:build-classpath "-Dmdep.outputFile=cp.txt"
$cp = (Get-Content -Raw cp.txt).Trim()
$full = "target\glint-bench-0.1.0-SNAPSHOT.jar;..\glint-core\target\glint-core-0.1.0-SNAPSHOT.jar;$cp"
$warmup = if ($args.Count -ge 1) { $args[0] } else { 2 }
$dur = if ($args.Count -ge 2) { $args[1] } else { 5 }
$conc = if ($args.Count -ge 3) { $args[2] } else { 64 }
java -cp $full io.github.glint.bench.ComparisonBench $warmup $dur $conc
Pop-Location