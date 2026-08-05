<#
.SYNOPSIS
  Converts a Neon connection string into the three environment variables Render
  needs, applying the two conversions that are easy to get wrong.

.DESCRIPTION
  Neon hands you a libpq URL:

    postgresql://USER:PASS@ep-xxx.ap-southeast-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require

  The PostgreSQL JDBC driver needs:

    - the jdbc: prefix;
    - credentials out of the URL and into their own properties;
    - channel_binding removed, because libpq supports it and PgJDBC does not,
      which otherwise fails at connection time with an unhelpful message.

  It also warns if you pasted the pooled ("-pooler") endpoint: Flyway serialises
  migrations with a session-scoped advisory lock, and Neon's pooler runs
  PgBouncer in transaction mode, which does not pin a session to a connection.

  Nothing is sent anywhere - this runs locally and only prints to your terminal.

.EXAMPLE
  .\scripts\neon-to-render-env.ps1
  (prompts, with the string hidden as you type)

.EXAMPLE
  .\scripts\neon-to-render-env.ps1 -ConnectionString 'postgresql://...'
#>
[CmdletBinding()]
param(
  [string]$ConnectionString
)

$ErrorActionPreference = "Stop"

if (-not $ConnectionString) {
  $secure = Read-Host -Prompt "Paste the Neon connection string" -AsSecureString
  $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
  try { $ConnectionString = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
  finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

$ConnectionString = $ConnectionString.Trim()
if ($ConnectionString -notmatch '^postgres(ql)?://') {
  Write-Host "That does not look like a Neon connection string (expected it to start with postgresql://)." -ForegroundColor Red
  exit 1
}

$uri = [Uri]$ConnectionString
$userInfo = $uri.UserInfo -split ':', 2
$username = [Uri]::UnescapeDataString($userInfo[0])
$password = if ($userInfo.Count -gt 1) { [Uri]::UnescapeDataString($userInfo[1]) } else { "" }
$database = $uri.AbsolutePath.TrimStart('/')

# Keep sslmode (PgJDBC supports and needs it), drop channel_binding (it does not).
$keptParams = @()
if ($uri.Query.Length -gt 1) {
  foreach ($pair in $uri.Query.TrimStart('?') -split '&') {
    $name = ($pair -split '=', 2)[0]
    if ($name -eq 'channel_binding') { continue }
    $keptParams += $pair
  }
}
if ($keptParams -notmatch '^sslmode=') { $keptParams += 'sslmode=require' }

$hostPort = if ($uri.IsDefaultPort -or $uri.Port -eq 5432) { $uri.Host } else { "$($uri.Host):$($uri.Port)" }
$jdbcUrl = "jdbc:postgresql://$hostPort/$database`?" + ($keptParams -join '&')

Write-Host ""
Write-Host "Paste these into Render (Environment tab, or when the Blueprint prompts):" -ForegroundColor Cyan
Write-Host ""
Write-Host "  DATABASE_URL       " -NoNewline -ForegroundColor DarkGray; Write-Host $jdbcUrl
Write-Host "  DATABASE_USERNAME  " -NoNewline -ForegroundColor DarkGray; Write-Host $username
Write-Host "  DATABASE_PASSWORD  " -NoNewline -ForegroundColor DarkGray; Write-Host $password
Write-Host ""

if ($uri.Host -like '*-pooler.*') {
  Write-Host "WARNING: that is the POOLED endpoint (-pooler in the hostname)." -ForegroundColor Yellow
  Write-Host "  Flyway takes a session-scoped advisory lock to serialise migrations, and Neon's" -ForegroundColor Yellow
  Write-Host "  pooler runs PgBouncer in transaction mode, which does not keep a session pinned." -ForegroundColor Yellow
  Write-Host "  Turn 'Connection pooling' OFF in the Neon dashboard and copy the direct string." -ForegroundColor Yellow
  Write-Host ""
}
if ($ConnectionString -match 'channel_binding') {
  Write-Host "Note: channel_binding was removed - the PostgreSQL JDBC driver rejects it." -ForegroundColor DarkGray
}
Write-Host "These are secrets. They belong in Render's dashboard, never in the repository." -ForegroundColor DarkGray
