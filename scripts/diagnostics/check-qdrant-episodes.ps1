$requestFile = Join-Path $PSScriptRoot 'qdrant-scroll.json'
$r = Invoke-RestMethod -Method Post -Uri 'http://localhost:6333/collections/episodes/points/scroll' -ContentType 'application/json' -InFile $requestFile
foreach ($p in $r.result.points) {
    $pl = $p.payload
    $tools = $pl.selectedToolNames -join ','
    Write-Host "sts=$($pl.status) goal=$($pl.userInput) lesson=$($pl.successLesson) tools=[$tools]"
}
