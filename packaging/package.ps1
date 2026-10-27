# 一键打包 Windows 绿色版（技术文档 11.1）
# 用法：powershell -ExecutionPolicy Bypass -File packaging/package.ps1 [-SkipTests] [-SkipBuild]
param(
    [switch]$SkipTests,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$Root = Split-Path -Parent $PSScriptRoot
$Version = '1.8.1'
$AppName = 'OA-Archive-Agent'

# 打包用 Temurin 17（可再分发），自动下载到 packaging/.jdk，不影响本机 Java
$JdkVersion = '17.0.20.1+1'
$JdkZipName = 'OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip'
$JdkSha256 = 'e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0'
# 先走清华镜像（国内快），失败再用 GitHub 官方地址；无论来源都会校验 SHA-256
$JdkUrls = @(
    "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/$JdkZipName",
    "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/$JdkZipName"
)
$JdkCache = Join-Path $PSScriptRoot '.jdk'
$JdkHome = Join-Path $JdkCache "jdk-$JdkVersion"

function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }

Step "准备 Temurin JDK $JdkVersion"
if (-not (Test-Path (Join-Path $JdkHome 'bin\jpackage.exe'))) {
    New-Item -ItemType Directory -Force -Path $JdkCache | Out-Null
    $zip = Join-Path $JdkCache $JdkZipName
    if (-not (Test-Path $zip)) {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $ok = $false
        foreach ($url in $JdkUrls) {
            Write-Host "下载 $url"
            try {
                # Windows 10/11 自带 curl.exe；PowerShell 5.1 的 Invoke-WebRequest 只作为后备
                if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
                    & curl.exe -fsSL --retry 2 --speed-limit 50000 --speed-time 30 -o $zip $url
                    if ($LASTEXITCODE -ne 0) { throw "curl 退出码 $LASTEXITCODE" }
                } else {
                    Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
                }
                $ok = $true
                break
            } catch {
                Write-Host "下载失败：$_，尝试下一个地址" -ForegroundColor Yellow
                if (Test-Path $zip) { Remove-Item $zip -Force }
            }
        }
        if (-not $ok) { throw "JDK 下载失败，请检查网络" }
    }
    $hash = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
    if ($hash -ne $JdkSha256) {
        Remove-Item $zip -Force
        throw "JDK 校验失败（SHA-256 不一致），已删除下载文件，请重试"
    }
    Expand-Archive -Path $zip -DestinationPath $JdkCache -Force
}
$env:JAVA_HOME = $JdkHome
$env:Path = "$JdkHome\bin;$env:Path"
& java -version
$Target = Join-Path $Root 'target'
$Jar = Join-Path $Target 'oa-archive-agent.jar'
# jpackage 生成的 exe 带只读属性，不清掉的话 mvn clean 会删除失败
function Clear-ReadOnly($dir) {
    if (Test-Path $dir) {
        Get-ChildItem $dir -Recurse -Force | Where-Object { $_.IsReadOnly } | ForEach-Object { $_.IsReadOnly = $false }
    }
}
Clear-ReadOnly (Join-Path $Target 'dist')

if (-not $SkipBuild) {
    Step "Maven 构建（含前端）"
    Push-Location $Root
    try {
        $mvnArgs = @('-q', 'clean', 'package')
        if ($SkipTests) { $mvnArgs += '-DskipTests' }
        & mvn @mvnArgs
        if ($LASTEXITCODE -ne 0) { throw "Maven 构建失败" }
    } finally { Pop-Location }
}
if (-not (Test-Path $Jar)) { throw "找不到 $Jar" }

Step "jlink 生成精简运行时"
$Runtime = Join-Path $Target 'runtime'
if (Test-Path $Runtime) { Remove-Item $Runtime -Recurse -Force }
$modules = (Get-Content (Join-Path $PSScriptRoot 'jlink-modules.txt') |
        Where-Object { $_ -and -not $_.StartsWith('#') } | ForEach-Object { $_.Trim() }) -join ','
& jlink --add-modules $modules --strip-debug --no-header-files --no-man-pages --compress=2 --output $Runtime
if ($LASTEXITCODE -ne 0) { throw "jlink 失败" }

Step "jpackage 生成程序目录"
$JpInput = Join-Path $Target 'jpackage-input'
$Dist = Join-Path $Target 'dist'
Clear-ReadOnly $Dist
foreach ($d in @($JpInput, $Dist)) { if (Test-Path $d) { Remove-Item $d -Recurse -Force } }
New-Item -ItemType Directory -Force -Path $JpInput | Out-Null
Copy-Item $Jar $JpInput
$javaOptions = @('-Xmx1024m', '-Dfile.encoding=UTF-8', '-Djava.awt.headless=false')
$jpArgs = @(
    '--type', 'app-image',
    '--name', $AppName,
    '--app-version', $Version,
    '--vendor', 'oa-archive-agent',
    '--description', 'RGL-OA智能归档助手',
    '--input', $JpInput,
    '--main-jar', 'oa-archive-agent.jar',
    '--runtime-image', $Runtime,
    '--icon', (Join-Path $PSScriptRoot 'app.ico'),
    '--add-launcher', "$AppName-Debug=$(Join-Path $PSScriptRoot 'debug-launcher.properties')",
    '--dest', $Dist
)
foreach ($o in $javaOptions) { $jpArgs += @('--java-options', $o) }
& jpackage @jpArgs
if ($LASTEXITCODE -ne 0) { throw "jpackage 失败" }
Step "复制使用手册和示例文件"
$AppDir = Join-Path $Dist $AppName
$manual = Join-Path $Root 'docs\使用手册.pdf'
if (Test-Path $manual) { Copy-Item $manual $AppDir }
$samples = Join-Path $Root 'src\test\resources\samples\A'
if (Test-Path $samples) {
    $sampleDir = Join-Path $AppDir '示例文件'
    New-Item -ItemType Directory -Force -Path $sampleDir | Out-Null
    Copy-Item (Join-Path $samples '*') $sampleDir
    # 反面案例：待确认、失败、重复、重名、跳过等情况（v1.5.1）
    $negative = Join-Path $Root 'src/main/resources/samples-negative'
    if (Test-Path $negative) {
        $negDir = Join-Path $sampleDir '反面案例'
        New-Item -ItemType Directory -Force -Path $negDir | Out-Null
        Get-ChildItem -LiteralPath $negative -Force | Where-Object { $_.Name -ne 'cases.json' } | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $negDir }
        Copy-Item (Join-Path $Root 'docs\反面案例说明.md') (Join-Path $negDir '说明.md')
    }
    # 简历示例（v1.8）：虚构的简历，演示按应聘岗位推测部门
    $resume = Join-Path $Root 'src/main/resources/samples-resume'
    if (Test-Path $resume) {
        $resDir = Join-Path $sampleDir '简历示例'
        New-Item -ItemType Directory -Force -Path $resDir | Out-Null
        Get-ChildItem -LiteralPath $resume -Force | Where-Object { $_.Name -ne 'cases.json' } | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $resDir }
    }
}

Step "压缩发布包"
$Release = Join-Path $Root 'release'
New-Item -ItemType Directory -Force -Path $Release | Out-Null
$ZipPath = Join-Path $Release "$AppName-v$Version-win64.zip"
if (Test-Path $ZipPath) { Remove-Item $ZipPath -Force }
# 不用 Compress-Archive：它在 PowerShell 5.1 下会写入反斜杠路径、中文文件名也不带 UTF-8 标记，
# 用资源管理器或 7-Zip 解压时“示例文件”会变成乱码。jar 生成的 ZIP 是标准 UTF-8 格式。
Push-Location $Dist
try {
    & jar --create --no-manifest --file $ZipPath $AppName
    if ($LASTEXITCODE -ne 0) { throw "压缩失败" }
} finally { Pop-Location }
$zipHash = (Get-FileHash $ZipPath -Algorithm SHA256).Hash.ToLower()
Set-Content -Path "$ZipPath.sha256" -Value "$zipHash  $(Split-Path -Leaf $ZipPath)" -Encoding ascii

$sizeMb = [math]::Round((Get-Item $ZipPath).Length / 1MB, 1)
$dirMb = [math]::Round(((Get-ChildItem $AppDir -Recurse | Measure-Object Length -Sum).Sum) / 1MB, 1)
Write-Host "`n完成：$ZipPath（$sizeMb MB，解压后 $dirMb MB）" -ForegroundColor Green
Write-Host "SHA-256：$zipHash"