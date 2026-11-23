# Clood Local Agent REST API Reference

Base URL: `http://127.0.0.1:19481` (loopback-only; port configurable in Clood desktop settings).

---

## `GET /api/v1/health`

Lightweight liveness probe.

```json
{
  "ok": true,
  "app": "clood",
  "version": "1.0.0",
  "model": "gpt-live-1.1"
}
```

---

## `GET /api/v1/status`

Checks Tailscale availability, phone WebSocket connection, OpenAI API key configuration, and whether a call is currently active.

```json
{
  "ok": true,
  "ready_to_call": true,
  "model": "gpt-live-1.1",
  "agent_name": "Clood",
  "voice": "marin",
  "orb_color": "#7c8cff",
  "tailscale": {
    "available": true,
    "ipv4": "100.113.41.57",
    "dns_name": "desktop.tail1234.ts.net",
    "tailnet_name": "tail1234.ts.net",
    "error": null
  },
  "phone_connected": true,
  "phone_device_name": "SM-S911B",
  "openai_configured": true,
  "active_call": null
}
```

---

## `POST /api/v1/calls`

Starts a voice call to the paired Android device.

### Request Body

| Field | Type | Default | Description |
| --- | --- | --- | --- |
| `context` | `string` | `""` | Distilled summary of conversation, code changes, and questions for the voice agent. |
| `opening_line` | `string?` | `null` | Optional first greeting / topic the voice agent opens with right after pickup. |
| `system_prompt` | `string?` | `null` | Optional extra behavioral rules appended to Clood's base system prompt. |
| `caller_name` | `string?` | config | Optional display name shown on the phone's incoming call screen. |
| `voice` | `string?` | config | Optional `gpt-live-1.1` voice override (e.g., `marin`, `cedar`, `alloy`, `ash`, `coral`, `vesper`). |
| `orb_color` | `string?` | config | Optional hex color override for the glowing sphere on this call (e.g., `#38e8d6`). |
| `ring_timeout_sec` | `number` | `45` | Seconds to ring before marking the call `missed` (10–180). |
| `max_duration_sec` | `number` | `900` | Maximum call duration in seconds (30–3600). |
| `wait` | `boolean` | `true` | If `true`, holds the HTTP response until the call ends and returns the full transcript. If `false`, returns `202 Accepted` immediately with `call_id`. |

### Response (`wait: true`)

```json
{
  "ok": true,
  "call_id": "call_8f3e92ab4c1d4c9ba201f3149d5b1023",
  "status": "completed",
  "duration_sec": 48,
  "transcript": [
    { "speaker": "agent", "text": "Hey, I finished the auth refactor...", "timestamp_ms": 1763918100120 },
    { "speaker": "user", "text": "Go ahead and run the migration now, skip SameSite for now.", "timestamp_ms": 1763918114500 }
  ],
  "transcript_text": "Clood: Hey, I finished the auth refactor...\nUser: Go ahead and run the migration now, skip SameSite for now.",
  "error": null
}
```

### Response (`wait: false`) — HTTP `202 Accepted`

```json
{
  "ok": true,
  "call_id": "call_8f3e92ab4c1d4c9ba201f3149d5b1023",
  "status": "ringing"
}
```

---

## `GET /api/v1/calls/{id}/wait?timeout_sec=1800`

Blocks until the specified call reaches a terminal state (`completed`, `declined`, `missed`, `failed`, `cancelled`) and returns the full saved transcript payload. Safe to call even if the call already ended.

---

## `GET /api/v1/calls/{id}`

Returns the current or saved `CallRecord` including its transcript.

---

## `GET /api/v1/calls?limit=20`

Lists recent calls (newest first), including full saved transcripts.

---

## `POST /api/v1/calls/{id}/hangup`

Ends an active or ringing call programmatically and returns the finalized `CallRecord`.

---

## PowerShell One-Liner Example

```powershell
$body = @{
  context = "Summary of changes: updated billing webhook. Question: deploy to staging or prod?"
  opening_line = "Oi, terminei o webhook de cobrança. Quer subir pra staging ou direto pra produção?"
  wait = $true
} | ConvertTo-Json -Depth 4

$res = Invoke-RestMethod -Uri "http://127.0.0.1:19481/api/v1/calls" -Method Post -ContentType "application/json" -Body $body -TimeoutSec 1000
$res.transcript_text
```
