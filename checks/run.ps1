param(
    [Parameter(Mandatory = $true)]
    [string]$JdkHome,
    [Parameter(Mandatory = $true)]
    [string]$DependencyClasspathFile
)

$ErrorActionPreference = 'Stop'
$checksRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$projectRoot = Split-Path -Parent $checksRoot
$jdkRoot = (Resolve-Path -LiteralPath $JdkHome).Path
$dependencyFile = (Resolve-Path -LiteralPath $DependencyClasspathFile).Path
$javac = Join-Path $jdkRoot 'bin/javac.exe'
$java = Join-Path $jdkRoot 'bin/java.exe'
if (-not (Test-Path -LiteralPath $javac -PathType Leaf) -or -not (Test-Path -LiteralPath $java -PathType Leaf)) {
    throw 'JdkHome must contain bin/javac.exe and bin/java.exe (JDK 25).'
}

# 显式使用调用方提供的现有依赖，不安装依赖、不修改 JAVA_HOME/IDE/pom。
$dependencyEntries = [Collections.Generic.List[string]]::new()
$dependencyText = [IO.File]::ReadAllText($dependencyFile)
foreach ($entry in ($dependencyText -split '[;\r\n]+')) {
    $jar = $entry.Trim()
    if ($jar.Length -eq 0) { continue }
    if (-not [IO.Path]::IsPathRooted($jar) -or -not $jar.EndsWith('.jar', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Dependency classpath must contain absolute JAR paths only: $jar"
    }
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
        throw "Dependency JAR does not exist: $jar"
    }
    $dependencyEntries.Add($jar)
}
if ($dependencyEntries.Count -eq 0) { throw 'Dependency classpath is empty.' }
$dependencies = [string]::Join([IO.Path]::PathSeparator, $dependencyEntries)

# 每次输出到新目录，避免把已经删除的测试 class 当作本次测试发现结果。
$runRoot = Join-Path $checksRoot ('build/run-' + [Guid]::NewGuid().ToString('N'))
$classes = Join-Path $runRoot 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sourceFiles = [Collections.Generic.List[string]]::new()
foreach ($sourceRoot in @((Join-Path $projectRoot 'src/main/java'), (Join-Path $checksRoot 'java'))) {
    foreach ($file in (Get-ChildItem -LiteralPath $sourceRoot -Filter '*.java' -Recurse -File | Sort-Object FullName)) {
        $sourceFiles.Add($file.FullName)
    }
}
$sourceFiles.Add((Join-Path $checksRoot 'tools/RunChecks.java'))

# javac 参数写入文件，兼容中文目录和带空格的依赖路径。
$arguments = [Collections.Generic.List[string]]::new()
foreach ($argument in @('-encoding', 'UTF-8', '--release', '25', '-cp', $dependencies, '-d', $classes)) {
    $arguments.Add($argument)
}
foreach ($file in $sourceFiles) { $arguments.Add($file) }
$quotedArguments = [Collections.Generic.List[string]]::new()
foreach ($argument in $arguments) {
    $quotedArguments.Add('"' + $argument.Replace('\', '/').Replace('"', '\"') + '"')
}
$argumentFile = Join-Path $runRoot 'javac.args'
$utf8 = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllLines($argumentFile, $quotedArguments, $utf8)

Push-Location -LiteralPath $projectRoot
try {
    & $javac "@$argumentFile"
    if ($LASTEXITCODE -ne 0) { throw "Compilation failed with exit code $LASTEXITCODE" }
    Write-Output "Compiled $($sourceFiles.Count) source files into $classes"
    $runtimeEntries = @($classes, (Join-Path $projectRoot 'src/main/resources'), (Join-Path $checksRoot 'resources'), $dependencies)
    $runtime = [string]::Join([IO.Path]::PathSeparator, $runtimeEntries)
    [IO.File]::WriteAllText((Join-Path $runRoot 'runtime-classpath.txt'), $runtime, $utf8)
    & $java '-cp' $runtime 'RunChecks'
    if ($LASTEXITCODE -ne 0) { throw "Checks failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}
