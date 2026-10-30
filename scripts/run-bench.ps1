param(
  [int]$Warmup = 2,
  [int]$Duration = 5,
  [int]$Concurrency = 64
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot\..
Write-Host "Building and running Glint microbench..."
mvn -q -pl glint-bench -am exec:java "-Dexec.args=$Warmup $Duration $Concurrency"
