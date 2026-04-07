#!/usr/bin/env python3
"""
OpenEnv — IT Support Ticket Triage
Baseline inference script

Runs an LLM agent (via OpenAI API) against all 3 task types and
produces reproducible baseline scores.

Usage:
    pip install openai requests
    export OPENAI_API_KEY=your_key_here
    python baseline_runner.py

    # Custom options:
    python baseline_runner.py --task HARD --ticket TKT-010 --seed 42 --model gpt-4o
    python baseline_runner.py --task ALL --output results.json

Requirements:
    - openai >= 1.0
    - requests >= 2.28
    - Environment running at http://localhost:7860 (or set ENV_URL)
"""

import os
import sys
import json
import time
import argparse
import requests
from typing import Optional

try:
    from openai import OpenAI
except ImportError:
    print("Error: openai package not found. Run: pip install openai requests")
    sys.exit(1)


# ─────────────────────────────────────────────────────────────────
# Configuration
# ─────────────────────────────────────────────────────────────────

ENV_URL     = os.getenv("ENV_URL", "http://localhost:7860/api/v1")
API_KEY     = os.getenv("OPENAI_API_KEY", "")
MODEL       = os.getenv("OPENAI_MODEL", "gpt-4o-mini")
SEED        = 42
TEMPERATURE = 0.2

# Fixed tickets for reproducible baseline (one per task type)
BASELINE_TICKETS = {
    "EASY":   "TKT-010",  # CRITICAL / SECURITY
    "MEDIUM": "TKT-004",  # CRITICAL / SOFTWARE / SYSADMIN
    "HARD":   "TKT-007",  # CRITICAL / NETWORK / NETWORK_OPS
}


# ─────────────────────────────────────────────────────────────────
# Environment client
# ─────────────────────────────────────────────────────────────────

def reset(task_type: str, ticket_id: Optional[str] = None, seed: int = SEED) -> dict:
    payload = {"task_type": task_type, "seed": seed}
    if ticket_id:
        payload["ticket_id"] = ticket_id
    r = requests.post(f"{ENV_URL}/reset", json=payload, timeout=10)
    r.raise_for_status()
    return r.json()


def step(action: dict) -> dict:
    r = requests.post(f"{ENV_URL}/step", json=action, timeout=10)
    r.raise_for_status()
    return r.json()


def state() -> dict:
    r = requests.get(f"{ENV_URL}/state", timeout=10)
    r.raise_for_status()
    return r.json()


def health_check() -> bool:
    try:
        r = requests.get(f"{ENV_URL}/health", timeout=5)
        return r.status_code == 200 and r.json().get("status") == "UP"
    except Exception:
        return False


# ─────────────────────────────────────────────────────────────────
# Prompt builder
# ─────────────────────────────────────────────────────────────────

def build_prompt(observation: dict, task_type: str) -> str:
    schema = observation.get("action_schema", {})
    required = schema.get("required_fields", [])

    lines = [
        "You are an expert IT support triage agent.",
        "Analyze the support ticket below and respond with a single valid JSON object.",
        "",
        "=== TICKET ===",
        f"ID:          {observation['ticket_id']}",
        f"Title:       {observation['ticket_title']}",
        f"Description: {observation['ticket_description']}",
        f"From:        {observation['user_name']} ({observation['user_department']})",
        f"Submitted:   {observation['submitted_at']}",
        "",
        "=== YOUR TASK ===",
        observation["task_description"],
        "",
        "=== VALID VALUES ===",
        f"Priorities: {schema.get('valid_priorities', [])}",
        f"Categories: {schema.get('valid_categories', [])}",
        f"Teams:      {schema.get('valid_teams', [])}",
        "",
        f"=== REQUIRED FIELDS: {required} ===",
    ]

    # For HARD task, include similar ticket context
    similar = observation.get("similar_tickets", [])
    if similar:
        lines += ["", "=== SIMILAR RESOLVED TICKETS ==="]
        for t in similar:
            lines.append(
                f"  {t['ticket_id']}: {t['title']}"
                f" → {t['resolution_summary']} (resolved in ~{t['resolved_in_hours']}h)"
            )

    # Previous actions context for multi-step episodes
    prev = observation.get("previous_actions", [])
    if prev:
        lines += ["", "=== YOUR PREVIOUS ACTIONS ==="]
        for a in prev:
            lines.append(f"  Step {a.get('step')}: {json.dumps({k: v for k, v in a.items() if k != 'step' and v is not None})}")
        lines += [
            "",
            "=== FEEDBACK FROM LAST STEP ===",
            f"Cumulative reward so far: {observation.get('cumulative_reward', 0):.3f}",
            "Refine your answer based on the above feedback.",
        ]

    lines += [
        "",
        "=== OUTPUT FORMAT ===",
        "Respond with ONLY a valid JSON object. No markdown fences, no explanation.",
        "Include all required fields. Example structure:",
        json.dumps({
            "priority":                   "CRITICAL",
            "category":                   "SECURITY",
            "assigned_team":              "SECURITY_OPS",
            "resolution_suggestion":      "Reset credentials, revoke sessions, check audit logs, notify CISO.",
            "similar_ticket_ids":         ["TKT-011"],
            "estimated_resolution_hours": 2,
            "reasoning":                  "Brief explanation of your triage decision."
        }, indent=2),
        "",
        "Only include fields that are required for the current task type.",
    ]

    return "\n".join(lines)


# ─────────────────────────────────────────────────────────────────
# LLM call
# ─────────────────────────────────────────────────────────────────

def call_llm(client: OpenAI, prompt: str, model: str) -> dict:
    """Call the OpenAI API and parse the JSON action from the response."""
    response = client.chat.completions.create(
        model=model,
        messages=[{"role": "user", "content": prompt}],
        temperature=TEMPERATURE,
        seed=SEED,
        max_tokens=600,
    )
    content = response.choices[0].message.content.strip()

    # Strip markdown code fences if present
    if content.startswith("```"):
        content = content.split("```")[1]
        if content.startswith("json"):
            content = content[4:]
        content = content.strip()

    return json.loads(content)


def mock_action(task_type: str) -> dict:
    """Deterministic mock action when no API key is provided (for smoke testing)."""
    base = {
        "priority":  "HIGH",
        "reasoning": "Mock baseline — no OPENAI_API_KEY set.",
    }
    if task_type in ("MEDIUM", "HARD"):
        base.update({"category": "NETWORK", "assigned_team": "NETWORK_OPS"})
    if task_type == "HARD":
        base.update({
            "resolution_suggestion":      "Contact ISP, activate failover link, notify NOC.",
            "similar_ticket_ids":         ["TKT-008"],
            "estimated_resolution_hours": 4,
        })
    return base


# ─────────────────────────────────────────────────────────────────
# Episode runner
# ─────────────────────────────────────────────────────────────────

def run_episode(
    client: Optional[OpenAI],
    task_type: str,
    ticket_id: Optional[str],
    model: str,
    verbose: bool = True,
) -> dict:
    print(f"\n{'─'*60}")
    print(f"  Task: {task_type}  |  Ticket: {ticket_id or 'random'}  |  Model: {model}")
    print(f"{'─'*60}")

    obs = reset(task_type, ticket_id)
    max_steps    = obs["max_steps"]
    cumulative   = 0.0
    step_results = []

    for step_num in range(1, max_steps + 1):
        if verbose:
            print(f"\n  [Step {step_num}/{max_steps}]")
            print(f"  Ticket: {obs['ticket_title']}")

        # Build prompt and get action
        prompt = build_prompt(obs, task_type)
        if client and API_KEY:
            try:
                action = call_llm(client, prompt, model)
            except Exception as e:
                print(f"  ⚠ LLM call failed: {e} — using mock action")
                action = mock_action(task_type)
        else:
            action = mock_action(task_type)

        if verbose:
            print(f"  Action: {json.dumps({k: v for k, v in action.items() if k != 'reasoning'}, indent=2)}")

        # Submit to environment
        result = step(action)
        reward = result["reward"]
        cumulative = reward["cumulative_reward"]

        step_results.append({
            "step":       step_num,
            "action":     action,
            "reward":     reward["value"],
            "components": reward["component_scores"],
            "feedback":   reward["feedback"],
            "penalty":    reward["penalty_applied"],
        })

        if verbose:
            print(f"  Reward: {reward['value']:.3f}  |  Cumulative: {cumulative:.3f}")
            print(f"  Feedback: {reward['feedback']}")
            if reward["penalty_applied"] > 0:
                print(f"  ⚠ Penalty: -{reward['penalty_applied']:.3f}")

        obs = result["observation"]
        if result["done"]:
            break
        time.sleep(0.5)  # Rate limit courtesy pause

    # Fetch ground truth via state()
    env_state = state()
    gt = env_state.get("ground_truth", {})

    print(f"\n  ✓ Episode complete  |  Final score: {cumulative:.3f}")
    print(f"  Ground truth → Priority: {gt.get('priority')}  "
          f"Category: {gt.get('category')}  Team: {gt.get('team')}")

    return {
        "task_type":        task_type,
        "ticket_id":        obs["ticket_id"],
        "score":            round(cumulative, 4),
        "steps_taken":      len(step_results),
        "step_details":     step_results,
        "ground_truth":     gt,
    }


# ─────────────────────────────────────────────────────────────────
# Main
# ─────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="OpenEnv Ticket Triage — Baseline Inference Runner"
    )
    parser.add_argument("--task",   default="ALL",
                        help="Task type: EASY | MEDIUM | HARD | ALL (default: ALL)")
    parser.add_argument("--ticket", default=None,
                        help="Specific ticket ID (default: baseline preset per task)")
    parser.add_argument("--model",  default=MODEL,
                        help=f"OpenAI model name (default: {MODEL})")
    parser.add_argument("--seed",   default=SEED, type=int,
                        help=f"Random seed (default: {SEED})")
    parser.add_argument("--output", default=None,
                        help="Write results to JSON file (optional)")
    parser.add_argument("--quiet",  action="store_true",
                        help="Suppress per-step output")
    args = parser.parse_args()

    # Banner
    print("╔══════════════════════════════════════════════════════╗")
    print("║    OpenEnv — IT Support Ticket Triage Baseline       ║")
    print("╚══════════════════════════════════════════════════════╝")
    print(f"  Env URL: {ENV_URL}")
    print(f"  Model:   {args.model}")
    print(f"  Seed:    {args.seed}")
    print(f"  API key: {'set ✓' if API_KEY else 'NOT SET — using mock agent'}")

    # Health check
    print("\n  Checking environment health...")
    if not health_check():
        print(f"  ✗ Environment not reachable at {ENV_URL}")
        print("    Start with: docker run -p 7860:7860 ticket-triage-env")
        print("            or: java -jar target/ticket-triage-env-1.0.0.jar")
        sys.exit(1)
    print("  ✓ Environment is UP")

    # OpenAI client
    client = OpenAI(api_key=API_KEY) if API_KEY else None

    # Determine which tasks to run
    task_arg = args.task.upper()
    tasks = ["EASY", "MEDIUM", "HARD"] if task_arg == "ALL" else [task_arg]

    # Run episodes
    results = []
    for task in tasks:
        ticket = args.ticket or BASELINE_TICKETS.get(task)
        result = run_episode(client, task, ticket, args.model, verbose=not args.quiet)
        results.append(result)

    # Summary table
    print(f"\n{'═'*60}")
    print("  BASELINE RESULTS SUMMARY")
    print(f"{'═'*60}")
    print(f"  {'Task':<10} {'Score':>8}  {'Steps':>7}  Ticket")
    print(f"  {'─'*48}")
    total = 0.0
    for r in results:
        print(f"  {r['task_type']:<10} {r['score']:>8.4f}  {r['steps_taken']:>7}  {r['ticket_id']}")
        total += r["score"]
    avg = total / len(results) if results else 0.0
    print(f"  {'─'*48}")
    print(f"  {'AVERAGE':<10} {avg:>8.4f}")
    print(f"{'═'*60}")

    # Published baseline for comparison
    PUBLISHED = {"EASY": 0.62, "MEDIUM": 0.48, "HARD": 0.34}
    print("\n  Comparison vs published baseline (gpt-4o-mini, seed 42):")
    for r in results:
        pub = PUBLISHED.get(r["task_type"], 0.0)
        delta = r["score"] - pub
        sign  = "+" if delta >= 0 else ""
        print(f"    {r['task_type']:<8}: {r['score']:.4f}  (published: {pub:.2f},  delta: {sign}{delta:.4f})")

    # Write to file
    output = {
        "model":        args.model,
        "seed":         args.seed,
        "env_url":      ENV_URL,
        "task_results": results,
        "average_score": round(avg, 4),
        "published_baseline": PUBLISHED,
    }

    if args.output:
        with open(args.output, "w") as f:
            json.dump(output, f, indent=2)
        print(f"\n  Results written to: {args.output}")

    print()
    return output


if __name__ == "__main__":
    main()
