# 自动重连 SSH 隧道到 AutoDL
# 用法：powershell -File scripts/network/ollama-ssh-tunnel.ps1
$hostname = "region-41.seetacloud.com"
$port = 58859
$localPort = 11435
$remotePort = 11434

while ($true) {
    Write-Host "$(Get-Date -Format 'HH:mm:ss') 建立隧道: ${localPort} -> ${hostname}:${remotePort}"
    $process = Start-Process -FilePath "ssh" -ArgumentList @(
        "-o", "StrictHostKeyChecking=no",
        "-o", "ServerAliveInterval=30",
        "-o", "ServerAliveCountMax=3",
        "-o", "ExitOnForwardFailure=yes",
        "-N",
        "-L", "${localPort}:127.0.0.1:${remotePort}",
        "root@${hostname}",
        "-p", "$port"
    ) -NoNewWindow -PassThru

    $process.WaitForExit()
    Write-Host "$(Get-Date -Format 'HH:mm:ss') 隧道断开，3秒后重连..."
    Start-Sleep -Seconds 3
}
