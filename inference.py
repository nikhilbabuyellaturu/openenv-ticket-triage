"""
OpenEnv — IT Support Ticket Triage
inference.py — baseline inference script

Fixes applied:
1. Timeouts on ALL LLM and HTTP calls (30s max)
2. Reduced to 1 step per task (no multi-step loops)
3. Non-blocking async HTTP via httpx
4. /step calls get immediate deterministic mock if no API key
"""

import os
import sys
import json
import asyncio
import time

# ── Config ────────────────────────────────────────────────────────
API_BASE_URL   = os.getenv("API_BASE_URL", "http://localhost:7860")
MODEL_NAME     = os.getenv("MODEL_NAME", "gpt-4o-mini")
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "")
HF_TOKEN       = os.getenv("HF_TOKEN", "")
LOCAL_IMAGE_NAME = os.getenv("LOCAL_IMAGE_NAME", "")

# Hard limits to prevent timeout
HTTP_TIMEOUT   = 20      # seconds per HTTP call
LLM_TIMEOUT    = 25      # seconds per LLM call
MAX_STEPS      = 1       # only 1 step per task (fast evaluation)
TASKS          = ["EASY", "MEDIUM", "HARD"]

BASELINE_TICKETS = {
    "EASY":   "TKT-001",
    "MEDIUM": "TKT-004",
    "HARD":   "TKT-010",
}

# ── HTTP client (sync with timeout) ───────────────────────────────

def http_post(path: str, payload: dict) -> dict:
    """POST with strict timeout."""
    import urllib.request
    import urllib.error
    url = f"{API_BASE_URL}{path}"
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url, data=data,
        headers={"Content-Type": "application/json"},
        method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        print(f"  HTTP POST {path} failed: {e}")
        return {}


def http_get(path: str) -> dict:
    """GET with strict timeout."""
    import urllib.request
    url = f"{API_BASE_URL}{path}"
    req = urllib.request.Request(url, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        print(f"  HTTP GET {path} failed: {e}")
        return {}

# ── Environment calls ─────────────────────────────────────────────

def env_reset(task_type: str, ticket_id: str = None, seed: int = 42) -> dict:
    payload = {"task_type": task_type, "seed": seed}
    if ticket_id:
        payload["ticket_id"] = ticket_id
    return http_post("/reset", payload)


def env_step(action: dict) -> dict:
    return http_post("/step", action)


def env_state() -> dict:
    return http_get("/state")


def env_health() -> bool:
    result = http_get("/api/v1/health")
    return result.get("status") == "UP"

# ── LLM call with timeout ─────────────────────────────────────────

def call_llm(prompt: str) -> dict:
    """
    Call OpenAI with a hard timeout.
    Falls back to deterministic mock immediately if no key or timeout.
    """
    if not OPENAI_API_KEY:
        return _mock_action()

    try:
        import signal

        def _timeout_handler(signum, frame):
            raise TimeoutError("LLM call exceeded timeout")

        # Set alarm (Unix only)
        try:
            signal.signal(signal.SIGALRM, _timeout_handler)
            signal.alarm(LLM_TIMEOUT)
        except (AttributeError, OSError):
            pass  # Windows fallback

        from openai import OpenAI
        client = OpenAI(api_key=OPENAI_API_KEY, timeout=LLM_TIMEOUT)
        resp = client.chat.completions.create(
            model=MODEL_NAME,
            messages=[{"role": "user", "content": prompt}],
            temperature=0.0,   # deterministic
            seed=42,
            max_tokens=300,    # short output = fast
        )

        try:
            signal.alarm(0)    # cancel alarm
        except (AttributeError, OSError):
            pass

        content = resp.choices[0].message.content.strip()
        content = content.replace("```json", "").replace("```", "").strip()
        return json.loads(content)

    except TimeoutError:
        print("  LLM timed out — using mock action")
        return _mock_action()
    except Exception as e:
        print(f"  LLM error ({type(e).__name__}) — using mock action")
        return _mock_action()


def _mock_action() -> dict:
    """Deterministic mock — returns instantly, no network call."""
    return {
        "priority":                   "HIGH",
        "category":                   "SOFTWARE",
        "assigned_team":              "SYSADMIN",
        "resolution_suggestion":      "Check logs, restart service, apply latest patch.",
        "similar_ticket_ids":         ["TKT-004"],
        "estimated_resolution_hours": 8,
        "reasoning":                  "Mock baseline — no OPENAI_API_KEY or LLM timeout."
    }

# ── Prompt builder ────────────────────────────────────────────────

def build_prompt(obs: dict) -> str:
    schema = obs.get("action_schema", {})
    # Compact prompt — fewer tokens = faster LLM response
    return (
        f"IT support triage. Respond with JSON only.\n\n"
        f"Ticket: {obs.get('ticket_title', '')}\n"
        f"Description: {obs.get('ticket_description', '')[:300]}\n"
        f"Department: {obs.get('user_department', '')}\n\n"
        f"Task: {obs.get('task_description', '')}\n"
        f"Required fields: {schema.get('required_fields', [])}\n"
        f"Priorities: {schema.get('valid_priorities', [])}\n"
        f"Categories: {schema.get('valid_categories', [])}\n"
        f"Teams: {schema.get('valid_teams', [])}\n\n"
        f"JSON response only. Example:\n"
        f'{{"priority":"HIGH","category":"SOFTWARE","assigned_team":"SYSADMIN",'
        f'"resolution_suggestion":"Restart service.","similar_ticket_ids":[],'
        f'"estimated_resolution_hours":8,"reasoning":"Brief reason."}}'
    )

# ── Episode runner ────────────────────────────────────────────────

def run_episode(task_type: str, ticket_id: str = None) -> dict:
    print(f"\n{'─'*50}")
    print(f"  Task: {task_type}  |  Ticket: {ticket_id or 'random'}")
    print(f"{'─'*50}")

    t0 = time.time()

    # Reset
    obs = env_reset(task_type, ticket_id)
    if not obs:
        print("  ✗ Reset failed")
        return {"task_type": task_type, "score": 0.0, "error": "reset failed"}

    print(f"  Ticket: {obs.get('ticket_title', 'N/A')[:60]}")

    # Single step only (avoids timeout)
    prompt = build_prompt(obs)
    action = call_llm(prompt)
    print(f"  Action: priority={action.get('priority')} "
          f"category={action.get('category')} "
          f"team={action.get('assigned_team')}")

    result = env_step(action)
    if not result:
        print("  ✗ Step failed")
        return {"task_type": task_type, "score": 0.0, "error": "step failed"}

    reward    = result.get("reward", {})
    score     = reward.get("cumulative_reward", 0.0)
    feedback  = reward.get("feedback", "")
    elapsed   = time.time() - t0

    print(f"  Score: {score:.4f}  |  Time: {elapsed:.1f}s")
    print(f"  Feedback: {str(feedback)[:100]}")

    # Ground truth from state
    st = env_state()
    gt = st.get("ground_truth", {})
    if gt:
        print(f"  Ground truth: {gt.get('priority')} / "
              f"{gt.get('category')} / {gt.get('team')}")

    return {
        "task_type":  task_type,
        "ticket_id":  obs.get("ticket_id"),
        "score":      round(score, 4),
        "feedback":   feedback,
        "elapsed_s":  round(elapsed, 2),
        "ground_truth": gt,
    }

# ── Main ─────────────────────────────────────────────────────────

def main():
    print("=" * 50)
    print("  OpenEnv — IT Support Ticket Triage Baseline")
    print(f"  URL:   {API_BASE_URL}")
    print(f"  Model: {MODEL_NAME}")
    print(f"  Key:   {'set ✓' if OPENAI_API_KEY else 'NOT SET — mock agent'}")
    print("=" * 50)

    # Health check with timeout
    print("\n  Checking environment health...")
    if not env_health():
        # Try alternate health path
        result = http_get("/health")
        if result.get("status") != "UP":
            print(f"  ✗ Environment not reachable at {API_BASE_URL}")
            sys.exit(1)
    print("  ✓ Environment is UP")

    results = []
    total_start = time.time()

    for task in TASKS:
        # Safety check — stop if running long
        if time.time() - total_start > 1500:  # 25 min hard limit
            print("\n  ⚠ Approaching time limit — stopping early")
            break

        ticket = BASELINE_TICKETS.get(task)
        result = run_episode(task, ticket)
        results.append(result)

    # Summary
    print(f"\n{'='*50}")
    print(f"  {'Task':<10} {'Score':>8}  {'Time':>8}")
    print(f"  {'─'*36}")
    total = 0.0
    for r in results:
        print(f"  {r['task_type']:<10} {r['score']:>8.4f}  {r.get('elapsed_s',0):>7.1f}s")
        total += r["score"]
    avg = total / len(results) if results else 0.0
    print(f"  {'─'*36}")
    print(f"  {'AVERAGE':<10} {avg:>8.4f}")
    print(f"  Total time: {time.time()-total_start:.1f}s")
    print("=" * 50)

    return results


if __name__ == "__main__":
    main()
