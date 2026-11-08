$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$traceName = 'run-40c64cb9bc0419251ba5f6fb32e899b1.trace.json'
$traceDir = Join-Path $PSScriptRoot 'evidence/S9.9-a6/device'
$tracePath = Join-Path $traceDir $traceName
$trace = Get-Content -LiteralPath $tracePath -Raw -Encoding UTF8 |
    ConvertFrom-Json
$request = ($trace | Where-Object kind -eq 'PROVIDER_REQUEST' |
    Select-Object -First 1).payload
if (!$request) { throw 'Saved provider request not found.' }
$body = $request.body | ConvertFrom-Json
if ($body.model -ne 'deepseek-v4.1-flash') {
    throw 'Unexpected model; request blocked.'
}
$endpoint = 'https://opencode.ai/zen/go/v1/chat/completions'
if ($request.url -ne $endpoint) { throw 'Unexpected endpoint.' }
$envPath = Join-Path $root '.env'
$line = Get-Content -LiteralPath $envPath |
    Where-Object { $_ -match '^\s*OPENCODE_GO_API_KEY\s*=' } |
    Select-Object -First 1
if (!$line) { throw 'OPENCODE_GO_API_KEY not found in .env.' }
$apiKey = ($line -split '=', 2)[1].Trim().Trim([char]34, [char]39)
if (!$apiKey -or $apiKey -match '[\r\n"\\]') {
    throw 'Invalid API key format.'
}
$session = $request.headers.'x-opencode-session'
if (!$session -or $session -notmatch '^[a-zA-Z0-9-]+$') {
    throw 'Invalid saved session ID.'
}
$bodyPath = [IO.Path]::GetTempFileName()
try {
    $utf8 = [Text.UTF8Encoding]::new($false)
    [IO.File]::WriteAllText($bodyPath, $request.body, $utf8)
    $config = @(
        "url = `"$endpoint`""
        "header = `"Authorization: Bearer $apiKey`""
        "header = `"x-opencode-session: $session`""
        'user-agent = "aegis-f-terminal-replay/1.0"'
        'header = "Content-Type: application/json"'
        'header = "Accept: text/event-stream"'
    )
    $curlArgs = @(
        '--config', '-'
        '--data-binary', "@$bodyPath"
        '--silent', '--show-error', '--include', '--no-buffer'
    )
    $config | & curl.exe @curlArgs
    if ($LASTEXITCODE -ne 0) { throw "curl exit code: $LASTEXITCODE" }
}
finally {
    Remove-Item -LiteralPath $bodyPath -ErrorAction SilentlyContinue
    Remove-Variable apiKey, line, config -ErrorAction SilentlyContinue
}
