#!/usr/bin/env python3
"""
Zero-dependency CLI client for Clood's local Agent HTTP API (default: http://127.0.0.1:19481).

Examples:
  python clood_call.py status
  python clood_call.py call --context "Summary..." --opening "Quick update on the deploy."
  python clood_call.py call --context-file brief.txt --no-wait
  python clood_call.py wait call_1234abcd
  python clood_call.py get call_1234abcd
  python clood_call.py history --limit 10
  python clood_call.py hangup call_1234abcd
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from typing import Any


DEFAULT_BASE_URL = os.environ.get("CLOOD_API_URL", "http://127.0.0.1:19481").rstrip("/")


def request_json(
    method: str,
    path: str,
    body: dict[str, Any] | None = None,
    timeout: float = 30.0,
    base_url: str = DEFAULT_BASE_URL,
) -> tuple[int, dict[str, Any]]:
    url = f"{base_url}{path}"
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Accept": "application/json"}
    if data is not None:
        headers["Content-Type"] = "application/json"

    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError:
            payload = {"ok": False, "error": raw or str(exc)}
        return exc.code, payload
    except urllib.error.URLError as exc:
        return 0, {
            "ok": False,
            "error": f"Cannot reach Clood at {base_url}. Is the Clood desktop app running? ({exc.reason})",
        }


def emit(payload: dict[str, Any], code: int) -> int:
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    if code == 0 or code >= 400 or payload.get("ok") is False:
        return 1
    return 0


def cmd_status(args: argparse.Namespace) -> int:
    code, data = request_json("GET", "/api/v1/status", timeout=10.0, base_url=args.url)
    return emit(data, code)


def cmd_call(args: argparse.Namespace) -> int:
    context = args.context or ""
    if args.context_file:
        with open(args.context_file, "r", encoding="utf-8") as fh:
            context = fh.read()
    elif not context and not sys.stdin.isatty():
        context = sys.stdin.read()

    payload: dict[str, Any] = {
        "context": context.strip(),
        "ring_timeout_sec": args.ring_timeout,
        "max_duration_sec": args.max_duration,
        "wait": not args.no_wait,
    }
    if args.opening:
        payload["opening_line"] = args.opening
    if args.system_prompt:
        payload["system_prompt"] = args.system_prompt
    if args.caller_name:
        payload["caller_name"] = args.caller_name
    if args.voice:
        payload["voice"] = args.voice
    if args.orb_color:
        payload["orb_color"] = args.orb_color

    http_timeout = (
        float(args.ring_timeout + args.max_duration + 60) if not args.no_wait else 20.0
    )
    code, data = request_json("POST", "/api/v1/calls", body=payload, timeout=http_timeout, base_url=args.url)
    return emit(data, code)


def cmd_wait(args: argparse.Namespace) -> int:
    qs = urllib.parse.urlencode({"timeout_sec": args.timeout})
    code, data = request_json(
        "GET",
        f"/api/v1/calls/{urllib.parse.quote(args.call_id)}/wait?{qs}",
        timeout=float(args.timeout + 30),
        base_url=args.url,
    )
    return emit(data, code)


def cmd_get(args: argparse.Namespace) -> int:
    code, data = request_json(
        "GET",
        f"/api/v1/calls/{urllib.parse.quote(args.call_id)}",
        timeout=15.0,
        base_url=args.url,
    )
    return emit(data, code)


def cmd_history(args: argparse.Namespace) -> int:
    qs = urllib.parse.urlencode({"limit": args.limit})
    code, data = request_json("GET", f"/api/v1/calls?{qs}", timeout=15.0, base_url=args.url)
    return emit(data, code)


def cmd_hangup(args: argparse.Namespace) -> int:
    code, data = request_json(
        "POST",
        f"/api/v1/calls/{urllib.parse.quote(args.call_id)}/hangup",
        body={},
        timeout=15.0,
        base_url=args.url,
    )
    return emit(data, code)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Clood local Agent API CLI")
    parser.add_argument("--url", default=DEFAULT_BASE_URL, help="Clood API base URL")
    sub = parser.add_subparsers(dest="command", required=True)

    p_status = sub.add_parser("status", help="Check Clood & Tailscale phone readiness")
    p_status.set_defaults(func=cmd_status)

    p_call = sub.add_parser("call", help="Place a call to the paired phone")
    p_call.add_argument("--context", help="Summarized context passed to the voice agent")
    p_call.add_argument("--context-file", help="Path to UTF-8 text file with call context")
    p_call.add_argument("--opening", help="Optional first sentence / topic for the voice agent")
    p_call.add_argument("--system-prompt", help="Optional extra behavioral instructions")
    p_call.add_argument("--caller-name", help="Optional caller display name on phone screen")
    p_call.add_argument("--voice", help="Optional GPT Live 1.1 voice override (e.g. marin, ash)")
    p_call.add_argument("--orb-color", help="Optional hex orb color override (e.g. #38e8d6)")
    p_call.add_argument("--ring-timeout", type=int, default=45, help="Ring timeout in seconds")
    p_call.add_argument("--max-duration", type=int, default=900, help="Max call duration in seconds")
    p_call.add_argument("--no-wait", action="store_true", help="Return immediately without waiting for transcript")
    p_call.set_defaults(func=cmd_call)

    p_wait = sub.add_parser("wait", help="Wait for an in-progress call to finish and return transcript")
    p_wait.add_argument("call_id", help="Call identifier returned by `call --no-wait`")
    p_wait.add_argument("--timeout", type=int, default=1800, help="Wait timeout in seconds")
    p_wait.set_defaults(func=cmd_wait)

    p_get = sub.add_parser("get", help="Fetch a call record and saved transcript")
    p_get.add_argument("call_id", help="Call identifier")
    p_get.set_defaults(func=cmd_get)

    p_hist = sub.add_parser("history", help="List recent calls and transcripts")
    p_hist.add_argument("--limit", type=int, default=10, help="Max number of records")
    p_hist.set_defaults(func=cmd_history)

    p_hang = sub.add_parser("hangup", help="End an active call")
    p_hang.add_argument("call_id", help="Call identifier")
    p_hang.set_defaults(func=cmd_hangup)

    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
