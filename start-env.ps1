# MyHelper 环境一键启动（PowerShell 版，UTF-8）
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$MYHELPER = 'D:\project\myhelper'
$ROBOTMCP = 'D:\project\robot-mcp'

Write-Host ''
Write-Host '=============================================='
Write-Host '       MyHelper 环境一键启动'
Write-Host '=============================================='
Write-Host ''

# ============ 1. Docker Desktop ============
Write-Host '[1/5] 检查 Docker Desktop ...'
docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host '      未运行，正在启动（约 30~60 秒）...'
    $dockerExe = 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
    if (Test-Path $dockerExe) {
        Start-Process $dockerExe
    } else {
        Write-Host '      [错误] 未找到 Docker Desktop，请手动启动'
    }
    $n = 0
    while ($n -lt 40) {
        Start-Sleep -Seconds 3
        docker info *> $null
        if ($LASTEXITCODE -eq 0) { break }
        $n++
    }
    if ($n -ge 40) { Write-Host '      [警告] Docker 启动超时，请手动确认后重试' }
}
Write-Host '      Docker 就绪'
Write-Host ''

# ============ 2. 容器 ============
Write-Host '[2/5] 启动容器 Neo4j + Qdrant + Home Assistant ...'
Set-Location $MYHELPER
docker compose up -d
Write-Host '      容器已启动'
Write-Host ''

# ============ 3. 本地 Ollama ============
Write-Host '[3/5] 检查本地 Ollama (11434) ...'
try {
    Invoke-WebRequest -Uri 'http://localhost:11434/api/tags' -UseBasicParsing -TimeoutSec 3 | Out-Null
    Write-Host '      Ollama 运行中'
} catch {
    Write-Host '      [警告] Ollama 未运行，嵌入向量不可用（可手动执行 ollama serve）'
}
Write-Host ''

# ============ 4. robot-mcp ============
Write-Host '[4/5] 启动 robot-mcp (8081) ...'
$listening = Get-NetTCPConnection -LocalPort 8081 -State Listen -ErrorAction SilentlyContinue
if ($listening) {
    Write-Host '      robot-mcp 已在运行，跳过'
} else {
    Start-Process cmd -ArgumentList '/k', "cd /d $ROBOTMCP && mvn spring-boot:run"
    Write-Host '      robot-mcp 已在新窗口启动'
}
Write-Host ''

# ============ 5. myhelper ============
Write-Host '[5/5] 启动 myhelper (8082) ...'
$listening2 = Get-NetTCPConnection -LocalPort 8082 -State Listen -ErrorAction SilentlyContinue
if ($listening2) {
    Write-Host '      myhelper 已在运行，跳过'
} else {
    Start-Process cmd -ArgumentList '/k', "cd /d $MYHELPER && mvn spring-boot:run"
    Write-Host '      myhelper 已在新窗口启动'
}
Write-Host ''

Write-Host '=============================================='
Write-Host '  启动完成！'
Write-Host ''
Write-Host '  robot-mcp  : http://127.0.0.1:8081/mcp'
Write-Host '  myhelper   : http://localhost:8082'
Write-Host '  Neo4j      : http://localhost:7474'
Write-Host '  Qdrant     : http://localhost:6333'
Write-Host '  HA         : http://localhost:8123'
Write-Host '=============================================='
Write-Host ''
Write-Host '  提示：'
Write-Host '  1) 各服务在独立窗口运行，关窗口即停该服务'
Write-Host '  2) 如需本地大模型(model1)，另开窗口运行 tunnel.ps1'
Write-Host ''
