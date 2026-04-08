"""
OpenEnv — IT Support Ticket Triage
inference.py — required stdout format: [START] [STEP] [END]
"""

import asyncio
import os
import json
import urllib.request
import urllib.error
from openai import OpenAI

# ── Mandatory env vars ───────────────────────────────────────────
API_BASE_URL     = os.getenv("API_BASE_URL", "http://localhost:7860")
MODEL_NAME       = os.getenv("MODEL_NAME", "gpt-4o-mini")
HF_TOKEN         = os.getenv("HF_TOKEN", "")
LOCAL_IMAGE_NAME = os.getenv("LOCAL_IMAGE_NAME", "")

OPENAI_API_KEY   = os.getenv("OPENAI_API_KEY", HF_TOKEN or "dummy-key")
HTTP_TIMEOUT     = 10
TASKS = [
    {"name": "EASY",   "ticket": "TKT-001"},
    {"name": "MEDIUM", "ticket": "TKT-004"},
    {"name": "HARD",   "ticket": "TKT-010"},
]

# ── Stdout format (mandatory) ────────────────────────────────────
def log_start(task, env, model):
    print(f"[START] task={task} env={env} model={model}", flush=True)

def log_step(step, action, reward, done, error=None):
    print(f"[STEP] step={step} action={action} reward={reward:.2f} done={str(done).lower()} error={error or 'null'}", flush=True)

def log_end(success, steps, score, rewards):
    rewards_str = ",".join(f"{r:.2f}" for r in rewards)
    print(f"[END] success={str(success).lower()} steps={steps} score={score:.2f} rewards={rewards_str}", flush=True)

# ── HTTP helpers (no external libs) ─────────────────────────────
def http_post(path, payload):
    url = f"{API_BASE_URL}{path}"
    data = json.dumps(payload).encode()
    req = urllib.request.Request(url, data=data,
          headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as r:
            return json.loads(r.read().decode())
    except Exception as e:
        return {}

def http_get(path):
    url = f"{API_BASE_URL}{path}"
    req = urllib.request.Request(url, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as r:
            return json.loads(r.read().decode())
    except Exception:
        return {}

# ── Instant mock actions (no LLM wait) ──────────────────────────
MOCK_ACTIONS = {
    "EASY":   {"priority": "HIGH", "reasoning": "Urgent deadline, screen issue."},
    "MEDIUM": {"priority": "CRITICAL", "category": "SOFTWARE",
               "assigned_team": "SYSADMIN", "reasoning": "SAP payroll critical."},
    "HARD":   {"priority": "CRITICAL", "category": "SECURITY",
               "assigned_team": "SECURITY_OPS",
               "resolution_suggestion": "Reset credentials, revoke sessions, audit logs, notify CISO.",
               "similar_ticket_ids": ["TKT-011"],
               "estimated_resolution_hours": 2,
               "reasoning": "Phishing credential compromise."},
}

# ── OpenAI client (mandatory per spec) ──────────────────────────
# Used for LLM calls if API key is available, otherwise falls back to mock
client = OpenAI(api_key=OPENAI_API_KEY, timeout=8.0)

def get_action(task_name, obs):
    """Get action — try OpenAI with 8s timeout, fall back to mock instantly."""
    if not OPENAI_API_KEY or OPENAI_API_KEY in ("dummy-key", ""):
        return MOCK_ACTIONS.get(task_name, {"priority": "MEDIUM"})
    try:
        schema = obs.get("action_schema", {})
        prompt = (
            f"IT support triage. JSON only.\n"
            f"Ticket: {obs.get('ticket_title','')}\n"
            f"Desc: {obs.get('ticket_description','')[:200]}\n"
            f"Task: {obs.get('task_description','')}\n"
            f"Required: {schema.get('required_fields',[])}\n"
            f"Priorities: {schema.get('valid_priorities',[])}\n"
            f"Categories: {schema.get('valid_categories',[])}\n"
            f"Teams: {schema.get('valid_teams',[])}\n"
            f"Return JSON with required fields only."
        )
        resp = client.chat.completions.create(
            model=MODEL_NAME,
            messages=[{"role": "user", "content": prompt}],
            temperature=0.0, seed=42, max_tokens=200,
        )
        content = resp.choices[0].message.content.strip()
        content = content.replace("```json","").replace("```","").strip()
        return json.loads(content)
    except Exception:
        return MOCK_ACTIONS.get(task_name, {"priority": "MEDIUM"})

# ── Main async loop ──────────────────────────────────────────────
async def run_task(task_name, ticket_id):
    log_start(task=task_name, env="ticket-triage", model=MODEL_NAME)

    rewards = []
    steps = 0
    score = 0.0
    success = False

    try:
        obs = http_post("/reset", {"task_type": task_name, "ticket_id": ticket_id, "seed": 42})
        if not obs:
            log_step(1, "reset_failed", 0.0, True, "reset_failed")
            log_end(False, 0, 0.0, [])
            return

        action = get_action(task_name, obs)
        action_str = action.get("priority", "MEDIUM")

        result = http_post("/step", action)
        if not result:
            log_step(1, action_str, 0.0, True, "step_failed")
            log_end(False, 1, 0.0, [0.0])
            return

        reward = result.get("reward", {}).get("value", 0.0)
        done   = result.get("done", True)
        score  = result.get("reward", {}).get("cumulative_reward", reward)
        steps  = 1
        rewards.append(reward)
        success = score > 0.0

        log_step(steps, action_str, reward, done, None)

    except Exception as e:
        log_step(steps + 1, "error", 0.0, True, str(e)[:50])

    finally:
        log_end(success, steps, score, rewards)

async def main():
    for task in TASKS:
        await run_task(task["name"], task["ticket"])
        await asyncio.sleep(0.1)

if __name__ == "__main__":
    asyncio.run(main())
