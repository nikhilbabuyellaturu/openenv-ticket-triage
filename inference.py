"""
OpenEnv — IT Support Ticket Triage
inference.py — required at repo root by OpenEnv spec

Runs the LLM baseline agent against all 3 task types using the OpenAI API.
Reads credentials from environment variables.

Usage:
    export OPENAI_API_KEY=your_key
    export API_BASE_URL=https://nikhilbabuy-openenv-ticket-triage.hf.space
    python inference.py
"""

import os
import json
import requests

# ── Config ────────────────────────────────────────────────────────
API_BASE_URL = os.getenv("API_BASE_URL", "http://localhost:7860")
MODEL_NAME   = os.getenv("MODEL_NAME", "gpt-4o-mini")
HF_TOKEN     = os.getenv("HF_TOKEN", "")

# Optional: override with local Docker image
LOCAL_IMAGE_NAME = os.getenv("LOCAL_IMAGE_NAME", "")

OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "")

TASKS = ["EASY", "MEDIUM", "HARD"]

BASELINE_TICKETS = {
    "EASY":   "TKT-001",
    "MEDIUM": "TKT-004",
    "HARD":   "TKT-010",
}

# ── Env client ────────────────────────────────────────────────────

def reset(task_type, ticket_id=None, seed=42):
    payload = {"task_type": task_type, "seed": seed}
    if ticket_id:
        payload["ticket_id"] = ticket_id
    r = requests.post(f"{API_BASE_URL}/reset", json=payload, timeout=30)
    r.raise_for_status()
    return r.json()


def step(action):
    r = requests.post(f"{API_BASE_URL}/step", json=action, timeout=30)
    r.raise_for_status()
    return r.json()


def get_state():
    r = requests.get(f"{API_BASE_URL}/state", timeout=30)
    r.raise_for_status()
    return r.json()

# ── Agent ─────────────────────────────────────────────────────────

def build_prompt(obs, task_type):
    schema = obs.get("action_schema", {})
    lines = [
        "You are an expert IT support triage agent.",
        "Analyze the ticket and respond with a valid JSON action only.",
        "",
        f"Title: {obs['ticket_title']}",
        f"Description: {obs['ticket_description']}",
        f"From: {obs['user_name']} ({obs['user_department']})",
        "",
        f"Task: {obs['task_description']}",
        f"Required fields: {schema.get('required_fields', [])}",
        f"Valid priorities: {schema.get('valid_priorities', [])}",
        f"Valid categories: {schema.get('valid_categories', [])}",
        f"Valid teams: {schema.get('valid_teams', [])}",
    ]
    similar = obs.get("similar_tickets", [])
    if similar:
        lines.append("\nSimilar resolved tickets:")
        for t in similar:
            lines.append(f"  {t['ticket_id']}: {t['title']} → {t['resolution_summary']}")
    lines += [
        "",
        "Respond ONLY with a JSON object. Example:",
        json.dumps({
            "priority": "HIGH",
            "category": "SECURITY",
            "assigned_team": "SECURITY_OPS",
            "resolution_suggestion": "Reset credentials, revoke sessions, notify CISO.",
            "similar_ticket_ids": ["TKT-011"],
            "estimated_resolution_hours": 4,
            "reasoning": "Brief explanation."
        })
    ]
    return "\n".join(lines)


def call_llm(prompt):
    if not OPENAI_API_KEY:
        # Mock agent for testing without API key
        return {
            "priority": "HIGH",
            "category": "SOFTWARE",
            "assigned_team": "SYSADMIN",
            "resolution_suggestion": "Restart the affected service and check system logs.",
            "similar_ticket_ids": ["TKT-004"],
            "estimated_resolution_hours": 8,
            "reasoning": "Mock baseline response."
        }
    try:
        from openai import OpenAI
        client = OpenAI(api_key=OPENAI_API_KEY)
        resp = client.chat.completions.create(
            model=MODEL_NAME,
            messages=[{"role": "user", "content": prompt}],
            temperature=0.2,
            seed=42,
            max_tokens=500,
        )
        content = resp.choices[0].message.content.strip()
        content = content.replace("```json", "").replace("```", "").strip()
        return json.loads(content)
    except Exception as e:
        print(f"  LLM error: {e} — using mock action")
        return {
            "priority": "HIGH",
            "category": "SOFTWARE",
            "assigned_team": "HELPDESK",
            "resolution_suggestion": "Check logs and restart the service.",
            "similar_ticket_ids": [],
            "estimated_resolution_hours": 8,
            "reasoning": "Fallback mock action."
        }

# ── Episode runner ────────────────────────────────────────────────

def run_episode(task_type, ticket_id=None):
    print(f"\n--- Task: {task_type} | Ticket: {ticket_id or 'random'} ---")
    obs = reset(task_type, ticket_id)
    max_steps = obs.get("max_steps", 1)
    cumulative = 0.0

    for s in range(1, max_steps + 1):
        prompt = build_prompt(obs, task_type)
        action = call_llm(prompt)
        result = step(action)
        reward = result["reward"]
        cumulative = reward["cumulative_reward"]
        print(f"  Step {s}: reward={reward['value']:.3f} | feedback={reward['feedback'][:80]}")
        obs = result["observation"]
        if result["done"]:
            break

    env_state = get_state()
    gt = env_state.get("ground_truth", {})
    print(f"  Score: {cumulative:.4f} | GT: {gt.get('priority')} / {gt.get('category')} / {gt.get('team')}")
    return {"task_type": task_type, "score": round(cumulative, 4)}

# ── Main ─────────────────────────────────────────────────────────

def main():
    print("=" * 55)
    print("  OpenEnv — IT Support Ticket Triage Baseline")
    print(f"  URL:   {API_BASE_URL}")
    print(f"  Model: {MODEL_NAME}")
    print("=" * 55)

    # Health check
    try:
        r = requests.get(f"{API_BASE_URL}/api/v1/health", timeout=10)
        print(f"  Health: {r.json()}")
    except Exception as e:
        print(f"  Health check failed: {e}")
        return

    results = []
    for task in TASKS:
        ticket = BASELINE_TICKETS.get(task)
        result = run_episode(task, ticket)
        results.append(result)

    print("\n" + "=" * 55)
    print(f"  {'Task':<10} {'Score':>8}")
    print(f"  {'-'*30}")
    total = 0.0
    for r in results:
        print(f"  {r['task_type']:<10} {r['score']:>8.4f}")
        total += r["score"]
    avg = total / len(results) if results else 0.0
    print(f"  {'-'*30}")
    print(f"  {'AVERAGE':<10} {avg:>8.4f}")
    print("=" * 55)

    return results


if __name__ == "__main__":
    main()
