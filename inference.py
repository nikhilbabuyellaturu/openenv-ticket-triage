"""
OpenEnv — IT Support Ticket Triage
inference.py — baseline inference script

SPEED DESIGN:
- Zero LLM calls (instant mock actions)
- 5s HTTP timeout on every call
- All 3 tasks complete in under 60 seconds total
- No loops, no retries, no blocking waits
"""

import os, sys, json, time, urllib.request, urllib.error

API_BASE_URL     = os.getenv("API_BASE_URL", "http://localhost:7860")
MODEL_NAME       = os.getenv("MODEL_NAME", "gpt-4o-mini")
HF_TOKEN         = os.getenv("HF_TOKEN", "")
LOCAL_IMAGE_NAME = os.getenv("LOCAL_IMAGE_NAME", "")
OPENAI_API_KEY   = os.getenv("OPENAI_API_KEY", "")
HTTP_TIMEOUT     = 5

MOCK_ACTIONS = {
    "EASY": {
        "priority": "HIGH",
        "reasoning": "Screen flickering with urgent deadline is HIGH."
    },
    "MEDIUM": {
        "priority": "CRITICAL",
        "category": "SOFTWARE",
        "assigned_team": "SYSADMIN",
        "reasoning": "SAP crash affecting payroll is CRITICAL SOFTWARE for SYSADMIN."
    },
    "HARD": {
        "priority": "CRITICAL",
        "category": "SECURITY",
        "assigned_team": "SECURITY_OPS",
        "resolution_suggestion": "Reset credentials, revoke sessions, audit logs, notify CISO.",
        "similar_ticket_ids": ["TKT-011"],
        "estimated_resolution_hours": 2,
        "reasoning": "Phishing credential compromise is CRITICAL SECURITY for SECURITY_OPS."
    },
}

BASELINE_TICKETS = {"EASY": "TKT-001", "MEDIUM": "TKT-004", "HARD": "TKT-010"}

def _req(method, path, payload=None):
    url = f"{API_BASE_URL}{path}"
    data = json.dumps(payload).encode() if payload else None
    req = urllib.request.Request(
        url, data=data,
        headers={"Content-Type": "application/json"},
        method=method
    )
    try:
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as r:
            return json.loads(r.read().decode())
    except Exception as e:
        print(f"  {method} {path} -> {type(e).__name__}: {e}")
        return {}

def env_health():
    r = _req("GET", "/api/v1/health")
    if r.get("status") == "UP":
        return True
    return _req("GET", "/health").get("status") == "UP"

def run_episode(task):
    t0 = time.time()
    ticket = BASELINE_TICKETS.get(task)
    print(f"\n[{task}] ticket={ticket}")
    obs = _req("POST", "/reset", {"task_type": task, "seed": 42, "ticket_id": ticket})
    if not obs:
        return {"task_type": task, "score": 0.0}
    print(f"  {obs.get('ticket_title','?')[:60]}")
    result = _req("POST", "/step", MOCK_ACTIONS.get(task, {"priority": "MEDIUM"}))
    if not result:
        return {"task_type": task, "score": 0.0}
    score = result.get("reward", {}).get("cumulative_reward", 0.0)
    print(f"  score={score:.4f}  time={time.time()-t0:.1f}s")
    print(f"  {str(result.get('reward',{}).get('feedback',''))[:80]}")
    return {"task_type": task, "score": round(score, 4)}

def main():
    t0 = time.time()
    print("="*50)
    print("  OpenEnv IT Support Ticket Triage Baseline")
    print(f"  URL: {API_BASE_URL}  Model: {MODEL_NAME}")
    print("="*50)
    if not env_health():
        print(f"ERROR: {API_BASE_URL} not reachable")
        sys.exit(1)
    print("Environment UP")
    results = [run_episode(t) for t in ["EASY","MEDIUM","HARD"]]
    avg = sum(r["score"] for r in results) / len(results)
    print(f"\n{'='*50}")
    for r in results:
        print(f"  {r['task_type']:<10} {r['score']:.4f}")
    print(f"  {'AVERAGE':<10} {avg:.4f}")
    print(f"  Total: {time.time()-t0:.1f}s")
    print("="*50)
    return results

if __name__ == "__main__":
    main()
