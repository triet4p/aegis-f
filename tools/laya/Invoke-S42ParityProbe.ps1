#Requires -Version 5.1
<#
.SYNOPSIS
    S4.2 user-run on-device parity probe: install the debug APK, stage the
    pinned Laya bundle, run the debug-only probe activity, retrieve its report.

.DESCRIPTION
    User-only procedure (workers never contact the phone): this script performs
    every reachable device step for S4.2 against the real LayaRuntime through
    the debug-only LayaParityProbeActivity, then saves the fresh probe report.

    What it does, in order (each adb step fails loudly on a nonzero exit):

    1. Verifies the four pinned bundle files by exact size + SHA-256 against
       tools/laya/PROVENANCE.md before anything touches the phone.
    2. Installs the debug APK with `install -r` (in-place update: preserves
       app data and signing; never uninstalls, never clears data).
    3. Creates the shell-owned staging dir, pushes the four files, and applies
       narrow permissions only (dirs 755, files 644: readable, never
       world-writable). No `chmod 777`.
    4. Copies the files into app-private storage with `run-as`, verifies all
       four landed, then removes ONLY this run's staging dir (frees ~1.7 GB).
    5. Deletes any stale probe report BEFORE launching, so a retrieved report
       can only belong to this run; launches the probe (it auto-runs).
    6. Polls for the fresh report with a timeout, saves it, and validates it
       parses as JSON carrying `verdict` + `state`. A FAIL/NO_RUN verdict is
       still saved and printed verbatim -- never tuned, never swapped.

    What it never does: uninstall/clear the app, dump ledger or notification
    content, touch notification settings, or provision anything (S4.3 owns
    provisioning UI; S4.6 owns suggestion UI).

    Defaults are the verified values on this host; every path is overridable.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\laya\Invoke-S42ParityProbe.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\laya\Invoke-S42ParityProbe.ps1 -DryRun

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\laya\Invoke-S42ParityProbe.ps1 -BundleDir 'D:\other\snapshot' -TimeoutSeconds 600
#>
[CmdletBinding()]
param(
    # Pinned `receptron/laya-onnx` snapshot dir (machine-local, never in Git).
    # Default is the host-verified revision 68f27dfe snapshot.
    [string]$BundleDir = 'F:\llms\hf\models\hub\models--receptron--laya-onnx\snapshots\68f27dfe5a27a54fb2b1fefc432f43f972e90868',
    # Committed tokenizer asset (overrides only for a deliberate rebuild check).
    [string]$TokenizerAsset = '',
    # Debug APK carrying the probe (release APKs have no probe by construction).
    [string]$ApkPath = '',
    # Where the fresh report JSON is saved on the PC.
    [string]$ReportOut = '',
    # adb binary (override for testing only).
    [string]$AdbExe = 'adb',
    # Poll budget for the 1.6 GB cold load + 9 on-device inferences.
    [int]$TimeoutSeconds = 300,
    [int]$PollSeconds = 5,
    # Print every device command without executing (locals are still verified).
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if ([string]::IsNullOrWhiteSpace($TokenizerAsset)) {
    $TokenizerAsset = Join-Path $RepoRoot 'app\src\main\assets\laya\laya_tokenizer.bin'
}
if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $ApkPath = Join-Path $RepoRoot 'app\build\outputs\apk\debug\app-debug.apk'
}
if ([string]::IsNullOrWhiteSpace($ReportOut)) {
    $ReportOut = Join-Path $RepoRoot 'artifacts\sprint-4\reviews\S42-parity-report.json'
}

# Current build identity (must match app/build.gradle.kts + debug manifest).
$Package = 'io.github.triet4p.aegisf'
$Component = 'io.github.triet4p.aegisf/io.github.triet4p.aegisf.layaprobe.LayaParityProbeActivity'
$StagingDir = '/data/local/tmp/laya-model'
$DeviceReport = 'files/laya/parity/S42-parity-report.json'

function Invoke-NativeCapture {
    param(
        [string]$Exe,
        [string[]]$Argv
    )
    # One boring native launcher for every adb call in this script. Native
    # stderr (e.g. `adb push` transfer progress) is ordinary child output,
    # not a PowerShell error: redirecting both streams through
    # System.Diagnostics.Process keeps `$ErrorActionPreference = 'Stop'`
    # from mistaking benign progress for a terminating NativeCommandError
    # (the `2>&1` merge it replaces trips exactly that trap on PS 5.1).
    # Exit code alone decides success; both streams stay in the result so a
    # real failure still prints its original stdout/stderr diagnostics.
    # Argument boundaries stay exact by quoting only elements that need it
    # (whitespace/quote); shell subcommands travel as verbatim argv like the
    # old splat did, with no re-interpretation by a wrapping shell.
    # Both pipes are drained concurrently via ReadToEndAsync tasks started
    # before WaitForExit, so a chatty child can never block filling one
    # while we read the other. (Event-handler appends were rejected: they
    # run on threadpool threads without a PowerShell runspace and crash the
    # host.) `$ErrorActionPreference` is left untouched (callers rely on
    # `Stop` for real PowerShell errors); no stream is suppressed: stdout
    # and stderr are both returned, and nothing here special-cases any
    # progress text.
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Exe
    $psi.Arguments = ($Argv | ForEach-Object {
        if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
    }) -join ' '
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $proc = [System.Diagnostics.Process]::Start($psi)
    $outTask = $proc.StandardOutput.ReadToEndAsync()
    $errTask = $proc.StandardError.ReadToEndAsync()
    $proc.WaitForExit()
    $stdout = $outTask.Result
    $stderr = $errTask.Result
    return @{ ExitCode = $proc.ExitCode; Stdout = $stdout; Stderr = $stderr }
}

function Invoke-StepAdb {
    param(
        [string[]]$AdbArgs,
        [string]$What
    )
    $shown = 'adb ' + ($AdbArgs -join ' ')
    if ($DryRun) {
        Write-Host "[dry-run] $shown"
        return ''
    }
    Write-Host ""
    Write-Host "-- $What"
    Write-Host "   $shown"
    $cap = Invoke-NativeCapture -Exe $AdbExe -Argv $AdbArgs
    $detail = (($cap.Stdout -split "`n") + ($cap.Stderr -split "`n") | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join "`n"
    if ($cap.ExitCode -ne 0) {
        throw "FAILED: $What`nCommand: $shown`nExit code: $($cap.ExitCode)`nOutput: $($detail.Trim())"
    }
    return $detail
}

function Assert-PinnedFile {
    param(
        [string]$Path,
        [long]$ExpectedBytes,
        [string]$ExpectedSha256,
        [string]$Label
    )
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Missing ${Label} file: $Path"
    }
    # NOTE: the pinned snapshot files are content-addressed symlinks, so
    # (Get-Item).Length reads 0; the stream length follows the link target.
    # SHA-256 uses boring .NET file-stream hashing (not the Get-FileHash
    # cmdlet) so the check works in both Windows PowerShell 5.1 and
    # PowerShell 7 even when PSModulePath shadows the Utility module.
    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $actualBytes = $stream.Length
        if ($actualBytes -ne $ExpectedBytes) {
            throw ("Size mismatch for {0}: got {1} B, want {2} B ({3})" -f $Label, $actualBytes, $ExpectedBytes, $Path)
        }
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try {
            $hashBytes = $sha.ComputeHash($stream)
        } finally {
            $sha.Dispose()
        }
        $actualSha = ([System.BitConverter]::ToString($hashBytes)).Replace('-', '').ToLowerInvariant()
    } finally {
        $stream.Close()
    }
    if ($actualSha -ne $ExpectedSha256.ToLowerInvariant()) {
        throw ("SHA-256 mismatch for {0}:`n got  {1}`nwant {2}`n({3})" -f $Label, $actualSha, $ExpectedSha256, $Path)
    }
    Write-Host "OK: $Label ($actualBytes B, sha256 $actualSha)"
}

Write-Host "=== S4.2 parity probe (package $Package) ==="

# 1. Local pins first: no device contact until the bundle is proven correct.
# Pins from tools/laya/PROVENANCE.md (S4.1), re-verified on this host.
Assert-PinnedFile (Join-Path $BundleDir 'laya.onnx') 3807291 `
    'a874eb254b58b0fcb1e7ad56fbb188c29d64e08c9a46b689433e1f52c66dba1e' 'laya.onnx'
Assert-PinnedFile (Join-Path $BundleDir 'laya.onnx.data') 1685258240 `
    '487746363a8da57bcadb4345352997d22a0fb90d70aa22c6856668d023242aba' 'laya.onnx.data'
Assert-PinnedFile (Join-Path $BundleDir 'laya_config.json') 369 `
    '5049005dc6ae3ca5e82cc7d85c421357d5c543817300c8e8c5281ddbc69bb561' 'laya_config.json'
Assert-PinnedFile $TokenizerAsset 1317378 `
    '09b371ce4b613716bdad862985c37774dfd3ea6373919c8312f9a6cf8bb9ad1b' 'laya_tokenizer.bin (committed asset)'
if (-not (Test-Path -LiteralPath $ApkPath)) {
    throw ("Missing debug APK: {0}`nBuild it first: .\gradlew.bat --no-daemon :app:assembleDebug" -f $ApkPath)
}
Write-Host "OK: debug APK present ($ApkPath)"

$adbCmd = Get-Command $AdbExe -ErrorAction SilentlyContinue
if (-not $adbCmd) {
    throw "adb not found as '$AdbExe': install the SDK platform-tools and ensure adb is on PATH (docs/developer-setup.md section 2)."
}

# 2. Exactly one authorized USB device must be visible.
if ($DryRun) {
    Write-Host "[dry-run] adb devices  (expect exactly one '<serial>`tdevice' line)"
} else {
    # Same native-stderr trap as Invoke-StepAdb: `adb devices` prints daemon
    # chatter on stderr with exit 0, which must not abort the probe either.
    $devCap = Invoke-NativeCapture -Exe $AdbExe -Argv @('devices')
    $devText = (($devCap.Stdout -split "`n") + ($devCap.Stderr -split "`n") | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join "`n"
    $ready = @($devText -split "`n" | Where-Object { $_ -match "`tdevice\s*$" }).Count
    if ($ready -lt 1) {
        throw ("No authorized USB device visible.`n{0}`nConnect the phone, authorize USB debugging, and retry." -f $devText)
    }
    Write-Host "OK: authorized USB device visible."
}

# 3. In-place install (keeps app data + signing; never uninstall/clear).
Invoke-StepAdb @('-d', 'install', '-r', $ApkPath) 'Install debug APK (in-place, preserves data)' | Out-Null

# 4. Stage the bundle where `run-as` can read it. The dir is created first so
# `adb push` never fails on a missing target; narrow modes keep the 1.7 GB
# readable but never world-writable (deliberately NOT 777).
Invoke-StepAdb @('-d', 'shell', 'mkdir', '-p', $StagingDir) 'Create staging dir' | Out-Null
$stagedOnnx = "$StagingDir/laya.onnx"
$stagedData = "$StagingDir/laya.onnx.data"
$stagedConfig = "$StagingDir/laya_config.json"
$stagedTok = "$StagingDir/laya_tokenizer.bin"
Invoke-StepAdb @('-d', 'push', (Join-Path $BundleDir 'laya.onnx'), $stagedOnnx) 'Push laya.onnx' | Out-Null
Invoke-StepAdb @('-d', 'push', (Join-Path $BundleDir 'laya.onnx.data'), $stagedData) 'Push laya.onnx.data (1.7 GB)' | Out-Null
Invoke-StepAdb @('-d', 'push', (Join-Path $BundleDir 'laya_config.json'), $stagedConfig) 'Push laya_config.json' | Out-Null
Invoke-StepAdb @('-d', 'push', $TokenizerAsset, $stagedTok) 'Push laya_tokenizer.bin' | Out-Null
Invoke-StepAdb @('-d', 'shell', 'chmod', '755', $StagingDir) 'Staging dir traversable (755, no world-write)' | Out-Null
Invoke-StepAdb @('-d', 'shell', 'chmod', '644', $stagedOnnx, $stagedData, $stagedConfig, $stagedTok) 'Staged files readable (644, no world-write)' | Out-Null

# 5. Transfer into app-private storage (the exact four files LayaRuntime.open
# requires), verify all four landed, then remove ONLY this staging dir.
Invoke-StepAdb @('-d', 'shell', 'run-as', $Package, 'mkdir', '-p', 'files/laya/model/tokenizer', 'files/laya/parity') 'Create app-private model/parity dirs' | Out-Null
Invoke-StepAdb @('-d', 'shell', 'run-as', $Package, 'cp', $stagedOnnx, $stagedData, $stagedConfig, 'files/laya/model/') 'Copy graph+weights+config into app storage' | Out-Null
Invoke-StepAdb @('-d', 'shell', 'run-as', $Package, 'cp', $stagedTok, 'files/laya/model/tokenizer/laya_tokenizer.bin') 'Copy tokenizer asset into app storage' | Out-Null
Invoke-StepAdb @('-d', 'shell', 'run-as', $Package, 'ls', '-l', 'files/laya/model/laya.onnx', 'files/laya/model/laya.onnx.data', 'files/laya/model/laya_config.json', 'files/laya/model/tokenizer/laya_tokenizer.bin') 'Verify four bundle files in app storage' | Out-Null
Invoke-StepAdb @('-d', 'shell', 'rm', '-rf', $StagingDir) 'Remove staging dir (frees ~1.7 GB)' | Out-Null

# 6. Clear any stale report BEFORE launch, so only this run can produce one.
Invoke-StepAdb @('-d', 'shell', 'run-as', $Package, 'rm', '-f', $DeviceReport) 'Clear stale probe report' | Out-Null
Invoke-StepAdb @('-d', 'shell', 'am', 'start', '-n', $Component) 'Launch LayaParityProbeActivity (auto-runs)' | Out-Null

# 7. The 1.6 GB cold load + 9 inferences take a minute or more: poll until a
# payload containing "verdict" appears. Absence is retried, never accepted.
if ($DryRun) {
    Write-Host "[dry-run] adb -d exec-out run-as $Package cat $DeviceReport  (poll every $PollSeconds s, timeout $TimeoutSeconds s)"
    Write-Host "[dry-run] save + validate JSON to $ReportOut"
    Write-Host ""
    Write-Host "Dry run complete: no device command was executed."
    exit 0
}

Write-Host ""
Write-Host "-- Poll for fresh report (timeout ${TimeoutSeconds}s)"
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$elapsed = 0
$reportText = $null
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds $PollSeconds
    # Same trap as above: transient device stderr must not abort the poll.
    # The launcher captures it into the result instead of the error stream;
    # absence is retried until the timeout, and presence is proven by
    # `"verdict"` below, never by silence.
    $pollCap = Invoke-NativeCapture -Exe $AdbExe -Argv @('-d', 'exec-out', 'run-as', $Package, 'cat', $DeviceReport)
    $text = $pollCap.Stdout.Trim()
    if ($text -match '"verdict"') {
        $reportText = $text
        break
    }
    $elapsed += $PollSeconds
    Write-Host "   Probe still running... (${elapsed}s elapsed)"
}
if (-not $reportText) {
    throw ("Timed out after {0} s waiting for {1}. The stale report was cleared before launch, so no file means this run has not finished: check the phone stayed awake/connected (`adb devices`), look at the on-screen probe line, and re-run." -f $TimeoutSeconds, $DeviceReport)
}

# 8. Validate BEFORE saving: unparsable or verdict-less payloads fail loudly
# and are never written over (or mistaken for) a real report.
try {
    $parsed = $reportText | ConvertFrom-Json
} catch {
    throw ("Retrieved payload is not valid JSON and was NOT saved: {0}" -f $_)
}
if (-not $parsed.verdict -or -not $parsed.state) {
    throw "Report failed validation (needs verdict + state) and was NOT saved."
}
$parent = Split-Path $ReportOut -Parent
if ($parent -and -not (Test-Path -LiteralPath $parent)) {
    [void][System.IO.Directory]::CreateDirectory($parent)
}
[System.IO.File]::WriteAllText($ReportOut, $reportText, (New-Object System.Text.UTF8Encoding($false)))
(Get-Content -LiteralPath $ReportOut -Raw | ConvertFrom-Json) | Out-Null

Write-Host ""
Write-Host "=== PROBE REPORT RECEIVED ===" -ForegroundColor Green
Write-Host $reportText
Write-Host ""
Write-Host ("State:   {0}" -f $parsed.state) -ForegroundColor Green
Write-Host ("Verdict: {0}" -f $parsed.verdict) -ForegroundColor Green
Write-Host "Saved to: $ReportOut" -ForegroundColor Green
Write-Host "Return the JSON above plus the on-screen verdict line. A FAIL verdict is evidence too: report it verbatim, never tune it."
