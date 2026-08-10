$r = Invoke-RestMethod -Method Post -Uri 'http://localhost:6333/collections/episodes/points/scroll' -ContentType 'application/json' -InFile 'd:\project\myhelper\scroll.json'
foreach ($p in $r.result.points) {
    $pl = $p.payload
    $tools = $pl.selectedToolNames -join ','
    Write-Host "sts=$($pl.status) goal=$($pl.userInput) lesson=$($pl.successLesson) tools=[$tools]"
}
