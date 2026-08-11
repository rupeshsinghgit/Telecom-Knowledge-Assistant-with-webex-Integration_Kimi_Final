# register-webhook.ps1
# Run this after "docker compose up -d" once all containers are healthy.
# It reads the current public ngrok URL from ngrok's local API (no copy-pasting needed)
# and registers the Webex webhook against it automatically.

$maxRetries = 15
$publicUrl = $null

Write-Host "Waiting for ngrok tunnel to come up..."
for ($i = 0; $i -lt $maxRetries; $i++) {
    try {
        $tunnels = Invoke-RestMethod -Uri "http://localhost:4040/api/tunnels" -ErrorAction Stop
        $httpsTunnel = $tunnels.tunnels | Where-Object { $_.proto -eq "https" } | Select-Object -First 1
        if ($httpsTunnel) {
            $publicUrl = $httpsTunnel.public_url
            break
        }
    } catch {
        Start-Sleep -Seconds 2
    }
}

if (-not $publicUrl) {
    Write-Error "Could not find an active ngrok tunnel at http://localhost:4040/api/tunnels. Is 'docker compose up -d' running?"
    exit 1
}

Write-Host "Found ngrok public URL: $publicUrl"

$targetUrl = "$publicUrl/webex/webhook"
Write-Host "Registering Webex webhook -> $targetUrl"

$result = Invoke-RestMethod -Method Post -Uri "$publicUrl/webex/admin/register-webhook?targetUrl=$targetUrl"
$result | Format-List

Write-Host "`nDone. Your bot is reachable at: $publicUrl"
