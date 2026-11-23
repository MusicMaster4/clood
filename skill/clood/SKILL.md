---
name: clood
description: Place a private voice call to the user's phone over Tailscale using the local Clood app (GPT Live 1.1), pass summarized conversation/change context into the voice agent, wait for the call to finish, and retrieve the full call transcript to guide next decisions. Use whenever the user asks to be called, when verbal approval or clarification is needed while the user is away from the keyboard, or when reporting completion of a long-running task by phone.
---

# Clood — Voice Call Skill for AI Agents

Clood lets you **call the user's phone privately over Tailscale** and hold a live, full-duplex voice conversation powered by **OpenAI `gpt-live-1.1`** (no internet access on the voice model).

Your role as the orchestrating agent is to:
1. Check that Clood is running and the phone is connected.
2. **Distill a clean context brief** (summary of the conversation, changes made, and exact questions/decisions needed).
3. **Trigger the call via Clood's local HTTP API** (passing your context brief into the voice agent's instructions) and **wait for the call to end**.
4. **Read the returned transcript**, analyze what the user decided or requested, and continue your work accordingly.

---

## Architecture & Guarantees

- **Local Agent API**: `http://127.0.0.1:19481` (loopback-only on the user's PC; port configurable in Clood settings, override with `CLOOD_API_URL`).
- **Voice Model**: `gpt-live-1.1` with **no internet access and no direct tools**. The voice model ONLY knows what you pass in `context`, `opening_line`, and `system_prompt` — plus whatever the user says during the call.
- **Privacy**: Audio travels strictly between the PC and the paired Android phone over the user's private Tailnet (`100.64.0.0/10`).
- **Transcript Persistence**: Every call transcript is returned directly in the API response AND persisted locally by Clood so you can retrieve it anytime via `GET /api/v1/calls/{call_id}`.

---

## Step-by-Step Workflow

### Step 1 — Preflight Check

Always verify Clood is ready before dialing:

```bash
python scripts/clood_call.py status
```

Or directly via HTTP:

```bash
curl -s http://127.0.0.1:19481/api/v1/status
```

Check these fields in the response:
- `ready_to_call`: must be `true`.
- If `phone_connected` is `false`, tell the user their phone is currently offline on Tailscale or not paired in Clood.
- If `openai_configured` is `false`, inform the user to set their OpenAI API key in Clood's settings.
- If `active_call` is non-null, a call is already in progress; do not start another until it finishes.

---

### Step 2 — Craft the Call Context Brief (CRITICAL)

Because the voice agent (`gpt-live-1.1`) has **zero access** to your conversation history, files, terminal output, or the internet, **everything it needs MUST be distilled into `context`**.

Before placing the call, prepare a concise, structured brief covering:

1. **Purpose**: 1–2 sentences explaining *why* you are calling right now.
2. **Summary of Work / State**: Concrete bullets of what was done, changed, discovered, or blocked (file names, test outcomes, errors, metrics — keep it speakable, avoid dumping raw code or huge diffs).
3. **Questions / Decisions Needed**: Explicit numbered questions with clear options (e.g., Option A vs Option B, whether to proceed with a destructive change, preferred design direction).
4. **Conversation Boundaries**: Tell the voice agent what it should confirm before the user hangs up (e.g., *"Confirm their choice clearly, summarize what the coding agent will do next, and let them know they can hang up when ready"*).

#### Good `context` Example

```text
Purpose: I finished refactoring the authentication flow and need your decision before running the database migration on production.

Summary of changes:
- Replaced legacy session cookies with rotating JWT refresh tokens in src/auth/.
- All 42 unit tests and 8 integration tests pass locally.
- The migration drops the legacy `user_sessions` table, which will log out currently active users.

Decisions needed from the user:
1. Should I run the migration now or schedule it for off-peak hours?
2. Do you want me to also enable strict SameSite cookies in this release or leave that for a follow-up PR?

Call instructions:
- Speak in the user's language (Portuguese if they speak Portuguese, English otherwise).
- Keep explanations concise and natural for a phone call.
- Once both questions are answered, briefly confirm their decisions so the transcript is unambiguous.
```

---

### Step 3 — Place the Call and Wait for the Transcript

#### Recommended: Using the bundled helper script (zero dependencies)

```bash
python scripts/clood_call.py call \
  --opening "Oi! Terminei o refactor de autenticação e preciso confirmar dois detalhes rápidos antes de rodar a migration." \
  --context "Purpose: ... Summary of changes: ... Decisions needed: ..."
```

For longer context briefs, write to a temporary file or pipe via stdin:

```bash
python scripts/clood_call.py call --context-file brief.txt --opening "Quick check-in on the deployment plan."
```

By default, the script waits until the call finishes (`wait: true`) and prints a JSON payload containing:
- `call_id`
- `status`: `"completed"`, `"declined"`, `"missed"`, `"failed"`, or `"cancelled"`
- `duration_sec`
- `transcript`: structured list of `{ "speaker": "agent" | "user", "text": "...", "timestamp_ms": ... }`
- `transcript_text`: clean human-readable dialogue (`Clood: ...\nUser: ...`)

#### Alternative: Direct HTTP REST API

Synchronous (waits until call ends and returns transcript in one request):

```bash
curl -s -X POST http://127.0.0.1:19481/api/v1/calls \
  -H "Content-Type: application/json" \
  -d '{
    "context": "Summary of changes and questions...",
    "opening_line": "Quick update on the build — can we decide on two items?",
    "ring_timeout_sec": 45,
    "max_duration_sec": 600,
    "wait": true
  }'
```

Asynchronous (if your shell or tool imposes short HTTP timeouts):

1. Start without waiting:
   ```bash
   python scripts/clood_call.py call --no-wait --context "..."
   ```
   Returns `call_id` immediately (`status: "ringing"`).
2. Block until completion (can be re-called safely if a tool call times out):
   ```bash
   python scripts/clood_call.py wait <call_id> --timeout 1800
   ```
3. Or fetch a saved transcript at any time:
   ```bash
   python scripts/clood_call.py get <call_id>
   ```

---

### Step 4 — Handle Call Outcomes & Act on the Transcript

After the call finishes, inspect `status` and `transcript_text`:

| `status` | Meaning | What you should do |
| --- | --- | --- |
| `completed` | User answered and the call finished | Carefully read `transcript_text`, extract every decision, constraint, or follow-up instruction from the `User:` lines, summarize your understanding briefly, and continue executing the task! |
| `declined` | User rejected the incoming call | Do **not** immediately redial. Leave a clear written summary of the pending question/state for the user in chat and pause any unapproved risky action. |
| `missed` | Phone rang until `ring_timeout_sec` expired | Do **not** spam redials. Inform the user you tried calling and summarize what you needed in text (or wait if the user instructed you to retry later). |
| `failed` | Connection or OpenAI Live error (`error` field) | Inspect `error`, report the root cause clearly, and fall back to text communication. |
| `cancelled` | Call was terminated via `/hangup` or PC UI | Respect cancellation and check with the user in text. |

#### Analyzing the Transcript Properly

- Trust `User:` utterances as the source of truth when there is any tension between your original assumptions and what the user said on the call.
- Speech-to-text transcripts may contain minor phonetic typos for code identifiers (e.g., `"tail net"` for `Tailnet`, `"end point"` for `endpoint`) — resolve them against your known repository symbols carefully.
- If the user gave a partial answer or hung up before answering a critical destructive-action question, **never guess** on destructive steps; execute only what was clearly approved and note the remaining question in writing.

---

## Quick Reference

See [references/api.md](references/api.md) for the complete REST API schema, optional overrides (`voice`, `caller_name`, `orb_color`, `system_prompt`), and PowerShell / curl / Python examples.
