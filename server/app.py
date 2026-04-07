"""
server/app.py — OpenEnv IT Support Ticket Triage
Python server entry point required by OpenEnv spec.

This module starts the Java Spring Boot backend via subprocess
and exposes the OpenEnv-compliant REST API on port 7860.
For pure-Python evaluation, it also implements the full
OpenEnv interface directly using FastAPI.
"""

import os
import sys
import json
import subprocess
import threading
import time
from typing import Optional, Any

from fastapi import FastAPI, HTTPException
from fastapi.responses import RedirectResponse, JSONResponse
from pydantic import BaseModel
import uvicorn

# ── App ───────────────────────────────────────────────────────────
app = FastAPI(
    title="OpenEnv — IT Support Ticket Triage",
    description="Real-world RL environment for IT support ticket triage",
    version="1.0.0",
)

# ── Models ────────────────────────────────────────────────────────

class ResetRequest(BaseModel):
    task_type: Optional[str] = "EASY"
    ticket_id: Optional[str] = None
    seed: Optional[int] = 42

class Action(BaseModel):
    priority: str
    category: Optional[str] = None
    assigned_team: Optional[str] = None
    resolution_suggestion: Optional[str] = None
    similar_ticket_ids: Optional[list] = None
    estimated_resolution_hours: Optional[int] = None
    reasoning: Optional[str] = None

# ── In-memory ticket data ─────────────────────────────────────────

TICKETS = {
    "TKT-001": {
        "ticket_id": "TKT-001",
        "title": "Laptop screen flickering and going black",
        "description": "My laptop screen has been flickering since this morning and sometimes goes completely black. I have a critical client presentation at 2 PM today.",
        "user_name": "Priya Sharma", "user_department": "Sales",
        "submitted_at": "2025-03-15T09:15:00",
        "priority": "HIGH", "category": "HARDWARE", "team": "HELPDESK",
        "resolution_hint": "Check display driver, inspect physical cable. Replace if hardware fault."
    },
    "TKT-004": {
        "ticket_id": "TKT-004",
        "title": "SAP ERP application crashes on startup",
        "description": "SAP ERP crashes immediately after login on all Finance workstations since overnight Windows update. Finance team cannot process payroll due tomorrow. Affects 12 users.",
        "user_name": "Anand Kumar", "user_department": "Finance",
        "submitted_at": "2025-03-15T08:30:00",
        "priority": "CRITICAL", "category": "SOFTWARE", "team": "SYSADMIN",
        "resolution_hint": "Roll back Windows update KB5023698. Reinstall SAP GUI 7.60 patch 11."
    },
    "TKT-007": {
        "ticket_id": "TKT-007",
        "title": "Entire building has no internet access",
        "description": "All staff in Building B (200 employees) have lost internet connectivity as of 7:45 AM. ISP link appears down. All customer-facing services unreachable.",
        "user_name": "IT Duty Manager", "user_department": "IT Operations",
        "submitted_at": "2025-03-15T07:50:00",
        "priority": "CRITICAL", "category": "NETWORK", "team": "NETWORK_OPS",
        "resolution_hint": "Contact ISP immediately. Activate 4G failover link. Escalate to NOC."
    },
    "TKT-010": {
        "ticket_id": "TKT-010",
        "title": "Phishing email received — employee clicked link",
        "description": "Finance employee received phishing email impersonating CEO for wire transfer. Clicked link and entered corporate credentials before realizing fraud.",
        "user_name": "Ramesh Krishnan", "user_department": "Finance",
        "submitted_at": "2025-03-15T10:30:00",
        "priority": "CRITICAL", "category": "SECURITY", "team": "SECURITY_OPS",
        "resolution_hint": "Reset credentials, revoke sessions, audit logs, notify CISO, file incident report."
    },
}

TICKET_LIST = list(TICKETS.keys())
PRIORITY_ORDER = ["LOW", "MEDIUM", "HIGH", "CRITICAL"]
VALID_CATEGORIES = ["HARDWARE", "SOFTWARE", "NETWORK", "SECURITY", "ACCESS", "OTHER"]
VALID_TEAMS = ["HELPDESK", "SYSADMIN", "NETWORK_OPS", "SECURITY_OPS", "DEVOPS", "MANAGEMENT"]
MAX_STEPS = {"EASY": 1, "MEDIUM": 1, "HARD": 3}

# ── Episode state ─────────────────────────────────────────────────

episode = {
    "id": None, "task_type": "EASY", "ticket": None,
    "step_count": 0, "max_steps": 1, "done": True,
    "cumulative_reward": 0.0, "action_history": []
}

import random, uuid

def get_ticket(ticket_id=None, seed=42):
    rng = random.Random(seed)
    if ticket_id and ticket_id in TICKETS:
        return TICKETS[ticket_id]
    return TICKETS[rng.choice(TICKET_LIST)]

def build_observation():
    t = episode["ticket"]
    schema = {
        "required_fields": ["priority"] + (
            ["category", "assigned_team"] if episode["task_type"] in ("MEDIUM","HARD") else []
        ) + (
            ["resolution_suggestion","similar_ticket_ids","estimated_resolution_hours"]
            if episode["task_type"] == "HARD" else []
        ),
        "optional_fields": ["reasoning"],
        "valid_priorities": PRIORITY_ORDER,
        "valid_categories": VALID_CATEGORIES,
        "valid_teams": VALID_TEAMS,
    }
    return {
        "ticket_id": t["ticket_id"],
        "ticket_title": t["title"],
        "ticket_description": t["description"],
        "user_name": t["user_name"],
        "user_department": t["user_department"],
        "submitted_at": t["submitted_at"],
        "task_type": episode["task_type"],
        "task_description": {
            "EASY":   "Classify the priority of this support ticket: LOW, MEDIUM, HIGH, or CRITICAL.",
            "MEDIUM": "Classify priority, category, and assign to the correct support team.",
            "HARD":   "Full triage: priority, category, team, resolution suggestion, similar tickets, estimated hours.",
        }[episode["task_type"]],
        "step_count": episode["step_count"],
        "max_steps": episode["max_steps"],
        "done": episode["done"],
        "previous_actions": episode["action_history"],
        "cumulative_reward": episode["cumulative_reward"],
        "action_schema": schema,
        "similar_tickets": [],
    }

def grade(action: Action):
    t = episode["ticket"]
    expected_priority = t["priority"]
    expected_category = t["category"]
    expected_team     = t["team"]
    task = episode["task_type"]
    scores = {}
    penalty = 0.0
    feedback = []

    # Priority
    submitted = (action.priority or "").upper().strip()
    if submitted not in PRIORITY_ORDER:
        scores["priority_score"] = 0.0; penalty += 0.1
        feedback.append(f"Invalid priority '{action.priority}'")
    else:
        diff = abs(PRIORITY_ORDER.index(submitted) - PRIORITY_ORDER.index(expected_priority))
        ps = {0: 1.0 if task=="EASY" else 0.40 if task=="MEDIUM" else 0.25,
              1: 0.5 if task=="EASY" else 0.20 if task=="MEDIUM" else 0.13,
              2: 0.2 if task=="EASY" else 0.08 if task=="MEDIUM" else 0.05}.get(diff, 0.0)
        scores["priority_score"] = ps
        feedback.append(f"Priority '{submitted}' {'✓' if diff==0 else f'off by {diff}'} (+{ps:.2f})")

    if task in ("MEDIUM", "HARD"):
        cat = (action.category or "").upper().strip()
        if cat not in VALID_CATEGORIES:
            scores["category_score"] = 0.0; penalty += 0.05
            feedback.append("Missing/invalid category")
        else:
            cs = (0.35 if task=="MEDIUM" else 0.20) if cat == expected_category else 0.0
            scores["category_score"] = cs
            feedback.append(f"Category '{cat}' {'✓' if cat==expected_category else '✗'} (+{cs:.2f})")

        tm = (action.assigned_team or "").upper().strip()
        if tm not in VALID_TEAMS:
            scores["team_score"] = 0.0; penalty += 0.05
            feedback.append("Missing/invalid team")
        else:
            ts = (0.25 if task=="MEDIUM" else 0.15) if tm == expected_team else 0.0
            scores["team_score"] = ts
            feedback.append(f"Team '{tm}' {'✓' if tm==expected_team else '✗'} (+{ts:.2f})")

    if task == "HARD":
        res = action.resolution_suggestion or ""
        if not res.strip():
            scores["resolution_score"] = 0.0; penalty += 0.05
            feedback.append("Missing resolution suggestion")
        else:
            hint_words = set(w for w in t["resolution_hint"].lower().split() if len(w) > 3)
            res_words  = set(w for w in res.lower().split() if len(w) > 3)
            coverage   = len(hint_words & res_words) / max(len(hint_words), 1)
            rs = min(0.25, coverage * 0.25)
            scores["resolution_score"] = rs
            feedback.append(f"Resolution coverage {coverage*100:.0f}% (+{rs:.2f})")

        hours = action.estimated_resolution_hours
        ranges = {"CRITICAL":(1,4),"HIGH":(4,24),"MEDIUM":(24,72),"LOW":(72,168)}
        lo, hi = ranges.get(expected_priority, (24,72))
        if hours and lo <= hours <= hi:
            scores["time_score"] = 0.05
        else:
            scores["time_score"] = 0.0
        feedback.append(f"Time estimate {hours}h (+{scores['time_score']:.2f})")

    raw = sum(scores.values())
    value = max(0.0, raw - penalty)
    return value, scores, " | ".join(feedback), penalty

# ── Routes ────────────────────────────────────────────────────────

@app.get("/")
def root():
    return RedirectResponse(url="/docs")

@app.get("/api/v1/health")
@app.get("/health")
def health():
    return {"status": "UP", "environment": "ticket-triage-v1", "version": "1.0.0"}

@app.post("/reset")
@app.post("/api/v1/reset")
def reset_env(request: ResetRequest = None):
    global episode
    if request is None:
        request = ResetRequest()
    task = (request.task_type or "EASY").upper()
    if task not in MAX_STEPS:
        task = "EASY"
    ticket = get_ticket(request.ticket_id, request.seed or 42)
    episode = {
        "id": "EP-" + str(uuid.uuid4())[:8].upper(),
        "task_type": task,
        "ticket": ticket,
        "step_count": 0,
        "max_steps": MAX_STEPS[task],
        "done": False,
        "cumulative_reward": 0.0,
        "action_history": [],
    }
    return build_observation()

@app.post("/step")
@app.post("/api/v1/step")
def step_env(action: Action):
    global episode
    if episode["ticket"] is None:
        raise HTTPException(status_code=400, detail="No active episode. Call /reset first.")
    if episode["done"]:
        return {
            "observation": build_observation(),
            "reward": {"value": 0.0, "component_scores": {}, "feedback": "Episode done. Call /reset.",
                       "penalty_applied": 0.0, "episode_complete": True,
                       "cumulative_reward": episode["cumulative_reward"]},
            "done": True,
            "info": {"episode_id": episode["id"], "step_count": episode["step_count"],
                     "task_type": episode["task_type"], "truncated": False, "validation_errors": []}
        }

    episode["step_count"] += 1
    episode["action_history"].append({
        "step": episode["step_count"],
        "priority": action.priority,
        "category": action.category,
        "assigned_team": action.assigned_team,
    })

    value, scores, feedback, penalty = grade(action)
    is_final = episode["step_count"] >= episode["max_steps"]
    episode["cumulative_reward"] += value
    if is_final:
        episode["done"] = True

    reward = {
        "value": round(value, 4),
        "component_scores": scores,
        "feedback": feedback,
        "penalty_applied": round(penalty, 4),
        "episode_complete": is_final,
        "cumulative_reward": round(episode["cumulative_reward"], 4),
    }
    return {
        "observation": build_observation(),
        "reward": reward,
        "done": episode["done"],
        "info": {
            "episode_id": episode["id"],
            "step_count": episode["step_count"],
            "task_type": episode["task_type"],
            "truncated": False,
            "validation_errors": [],
        }
    }

@app.get("/state")
@app.get("/api/v1/state")
def state():
    if episode["ticket"] is None:
        return {"episode_id": "NOT_STARTED", "done": True, "environment_version": "1.0.0"}
    t = episode["ticket"]
    return {
        "episode_id": episode["id"],
        "task_type": episode["task_type"],
        "current_ticket_id": t["ticket_id"],
        "step_count": episode["step_count"],
        "max_steps": episode["max_steps"],
        "done": episode["done"],
        "cumulative_reward": episode["cumulative_reward"],
        "action_history": episode["action_history"],
        "ground_truth": {
            "priority": t["priority"],
            "category": t["category"],
            "team": t["team"],
            "resolution_hint": t["resolution_hint"],
        },
        "environment_version": "1.0.0",
    }

@app.get("/api/v1/tickets")
@app.get("/tickets")
def list_tickets():
    return [{"ticket_id": v["ticket_id"], "title": v["title"],
             "user_name": v["user_name"], "user_department": v["user_department"]}
            for v in TICKETS.values()]

@app.get("/api/v1/info")
def info():
    return {
        "name": "IT Support Ticket Triage",
        "version": "1.0.0",
        "task_types": [
            {"id": "EASY",   "max_steps": 1, "difficulty": "easy"},
            {"id": "MEDIUM", "max_steps": 1, "difficulty": "medium"},
            {"id": "HARD",   "max_steps": 3, "difficulty": "hard"},
        ],
        "reward_range": [0.0, 1.0],
    }

def main():
    port = int(os.getenv("PORT", 7860))
    uvicorn.run("server.app:app", host="0.0.0.0", port=port, reload=False)

if __name__ == "__main__":
    main()
