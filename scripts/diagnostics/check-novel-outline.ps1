$ErrorActionPreference = "Continue"
$outputDir = Join-Path $PSScriptRoot 'output'
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$log = Join-Path $outputDir 'outline-result.txt'
"" | Out-File -FilePath $log -Encoding utf8
function Log([string]$msg) { Add-Content -Path $log -Value $msg -Encoding utf8 }

Log "== start =="
try {
    $neo4jUser = if ($env:MYHELPER_NEO4J_USER) { $env:MYHELPER_NEO4J_USER } else { 'neo4j' }
    $neo4jPassword = $env:MYHELPER_NEO4J_PASSWORD
    if (-not $neo4jPassword) { throw '请先设置 MYHELPER_NEO4J_PASSWORD 环境变量' }
    $auth = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${neo4jUser}:${neo4jPassword}"))
    $headers = @{ Authorization = "Basic $auth"; "Content-Type" = "application/json" }
    $body = '{"statements":[{"statement":"MATCH (v:NovelVolume) RETURN v.novelName AS novel, v.volumeNumber AS vol, v.title AS title, v.chapterStart AS cs, v.chapterEnd AS ce, toString(size(v.chapterOutlines)) AS outlineLen ORDER BY v.novelName, v.volumeNumber LIMIT 100","parameters":{}}]}'
    $r = Invoke-RestMethod -Uri "http://localhost:7474/db/neo4j/tx/commit" -Method Post -Headers $headers -Body $body -TimeoutSec 30
    Log "rows=" + ($r.results[0].data.Count)
    if ($r.errors.Count -gt 0) { Log ("NEO4J-ERROR: " + ($r.errors | ConvertTo-Json -Compress)) }
    $r.results[0].data | ForEach-Object {
        $row = $_.row
        Log ("novel=" + $row[0] + " | vol=" + $row[1] + " | title=" + $row[2] + " | ch=" + $row[3] + "-" + $row[4] + " | outlineChars=" + $row[5])
    }
} catch {
    Log ("HTTP-ERR: " + $_.Exception.Message)
}
Log "== end =="
