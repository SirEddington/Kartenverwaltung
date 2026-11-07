# Statischer HTTP-Server fuer den Probelauf KARV-131 (nur localhost). GET/HEAD und ein Range-Bereich.
param([Parameter(Mandatory = $true)][string]$Root, [int]$Port = 8765)
$types = @{ '.appinstaller' = 'application/appinstaller'; '.msix' = 'application/msix'; '.properties' = 'text/plain; charset=utf-8'; '.crt' = 'application/x-x509-ca-cert' }
$listener = New-Object System.Net.HttpListener
$listener.Prefixes.Add("http://localhost:$Port/"); $listener.Start()
"[serve] bereit auf http://localhost:$Port/ fuer $Root"
while ($listener.IsListening) {
  $ctx = $listener.GetContext(); $req = $ctx.Request; $res = $ctx.Response
  $rel = [Uri]::UnescapeDataString($req.Url.AbsolutePath.TrimStart('/')); $status = 200; $sent = 0
  try {
    $full = [IO.Path]::GetFullPath((Join-Path $Root $rel))
    if (-not $full.StartsWith([IO.Path]::GetFullPath($Root)) -or -not (Test-Path -LiteralPath $full -PathType Leaf)) { $status = 404; $res.StatusCode = 404 }
    else {
      $len = (Get-Item -LiteralPath $full).Length; $ext = [IO.Path]::GetExtension($full).ToLower()
      $res.ContentType = $(if ($types.ContainsKey($ext)) { $types[$ext] } else { 'application/octet-stream' })
      $res.Headers['Accept-Ranges'] = 'bytes'; $start = 0; $end = $len - 1
      if ($req.Headers['Range'] -match '^bytes=(\d*)-(\d*)$') {
        if ($Matches[1] -ne '') { $start = [long]$Matches[1]; if ($Matches[2] -ne '') { $end = [Math]::Min([long]$Matches[2], $len - 1) } }
        elseif ($Matches[2] -ne '') { $start = [Math]::Max(0, $len - [long]$Matches[2]) }
        $status = 206; $res.StatusCode = 206; $res.Headers['Content-Range'] = "bytes $start-$end/$len"
      }
      $count = $end - $start + 1; $res.ContentLength64 = $count
      if ($req.HttpMethod -ne 'HEAD') {
        $fs = [IO.File]::OpenRead($full)
        try { $fs.Seek($start, 'Begin') | Out-Null; $buf = New-Object byte[] 65536; $left = $count
          while ($left -gt 0) { $n = $fs.Read($buf, 0, [int][Math]::Min($buf.Length, $left)); if ($n -le 0) { break }; $res.OutputStream.Write($buf, 0, $n); $left -= $n; $sent += $n } }
        finally { $fs.Close() }
      }
    }
  } catch { $status = "ERR $($_.Exception.Message)" }
  try { $res.Close() } catch {}
  "[serve] $(Get-Date -Format HH:mm:ss) $($req.HttpMethod) /$rel $($req.Headers['Range']) -> $status ($sent B) UA=$($req.UserAgent)"
}
