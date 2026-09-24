# list_tags.ps1 —— 拉取云端标签库并美化输出
#
# 用法：
#   .\cloud\list_tags.ps1                # 表格
#   .\cloud\list_tags.ps1 -Card          # 卡片（一条一块，中文长名字更好看）
#   .\cloud\list_tags.ps1 -Unnamed       # 只看没命名的
#   .\cloud\list_tags.ps1 -Sort seen     # 按读到次数排（默认按名称）
#   .\cloud\list_tags.ps1 -Json          # 原样打印 JSON（给别的脚本用）
#
# key 的取法（按顺序找）：
#   1. 参数 -Key
#   2. 环境变量 RFID_API_KEY
#   3. Z:\rfid\.tools\cf-key.txt
param(
    [string]$Key,
    [switch]$Card,
    [switch]$Unnamed,
    [switch]$Json,
    [ValidateSet('name', 'seen', 'written', 'suffix')]
    [string]$Sort = 'name'
)

$ErrorActionPreference = 'Stop'

$base = 'https://rfid-tags.root-60a.workers.dev'

if (-not $Key) { $Key = $env:RFID_API_KEY }
if (-not $Key -and (Test-Path 'Z:\rfid\.tools\cf-key.txt')) {
    $Key = (Get-Content 'Z:\rfid\.tools\cf-key.txt' -Raw).Trim()
}
if (-not $Key) {
    Write-Host '找不到 API key。用 -Key 传，或设环境变量 RFID_API_KEY。' -ForegroundColor Red
    exit 1
}

$url = "$base/api/tags"
if ($Unnamed) { $url += '?unnamed=1' }

# User-Agent 不能省 —— Cloudflare 会 403 掉没有 UA 的请求
try {
    $raw = curl.exe -sS -f `
        -H "Authorization: Bearer $Key" `
        -H 'User-Agent: uhf-rfid-cli/1.0' `
        $url
} catch {
    Write-Host "请求失败：$_" -ForegroundColor Red
    exit 1
}

if ($Json) { $raw; exit 0 }

$data = $raw | ConvertFrom-Json
$tags = @($data.tags)

if ($tags.Count -eq 0) {
    Write-Host '（库里没有标签）' -ForegroundColor DarkGray
    exit 0
}

# 排序
$tags = switch ($Sort) {
    'seen'    { $tags | Sort-Object -Property @{E = { $_.seenCount } } -Descending }
    'written' { $tags | Sort-Object -Property @{E = { $_.writeCount } } -Descending }
    'suffix'  { $tags | Sort-Object suffix }
    default   { $tags | Sort-Object -Property @{E = { if ($_.name) { $_.name } else { [char]0xFFFF } } } }
}

function Name-Of($t) { if ($t.name) { $t.name } else { '(未命名)' } }

if ($Card) {
    # ---- 卡片式：一条一块
    $i = 0
    foreach ($t in $tags) {
        $i++
        $bad = ($t.status -eq 'anomaly')
        $head = "{0,2}. {1}" -f $i, (Name-Of $t)
        Write-Host $head -ForegroundColor $(if ($bad) { 'Yellow' } else { 'Cyan' })

        $bits = @("后 5 位 $($t.suffix)")
        if ($bad) { $bits += '异常' }
        if ($t.seenCount -gt 0) { $bits += "读到 $($t.seenCount) 次" }
        if ($t.writeCount -gt 0) { $bits += "写过 $($t.writeCount) 次" }
        if ($t.operator) { $bits += $t.operator }

        Write-Host ("    " + ($bits -join '  ·  ')) -ForegroundColor DarkGray
        Write-Host "    EPC  $($t.epc)"
        Write-Host "    TID  $($t.tid)"
        Write-Host ''
    }
} else {
    # ---- 表格：一行一条
    $rows = foreach ($t in $tags) {
        [pscustomobject]@{
            '名称'   = Name-Of $t
            '后5位'  = $t.suffix
            '状态'   = if ($t.status -eq 'anomaly') { '异常' } else { '' }
            '读到'   = $t.seenCount
            '写过'   = $t.writeCount
            '使用者' = $t.operator
            'EPC'    = $t.epc
        }
    }
    Write-Host ($rows | Format-Table -AutoSize | Out-String)
}

# 统计
$unnamedCount = @($tags | Where-Object { [string]::IsNullOrEmpty($_.name) }).Count
$anomalyCount = @($tags | Where-Object { $_.status -eq 'anomaly' }).Count
$tail = "共 $($tags.Count) 条"
if ($unnamedCount) { $tail += "，$unnamedCount 条未命名" }
if ($anomalyCount) { $tail += "，$anomalyCount 条异常" }
Write-Host $tail -ForegroundColor DarkGray
