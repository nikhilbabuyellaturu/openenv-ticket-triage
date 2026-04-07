---
title: OpenEnv — IT Support Ticket Triage
emoji: 🎫
colorFrom: blue
colorTo: green
sdk: docker
app_port: 7860
tags: [openenv, reinforcement-learning, nlp, classification, enterprise, java, spring-boot]
pinned: false
---

# 🎫 OpenEnv — IT Support Ticket Triage

> A production-grade reinforcement learning environment where an AI agent learns to triage IT support tickets — classifying priority, category, team assignment, and resolution steps.

[![OpenEnv Compliant](https://img.shields.io/badge/OpenEnv-1.0-green)](https://openenv.dev)
[![Java 17](https://img.shields.io/badge/Java-17-orange)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot 3.2](https://img.shields.io/badge/Spring%20Boot-3.2-brightgreen)](https://spring.io/projects/spring-boot)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

---

## 🧠 Environment Description

IT support ticket triage is a high-volume, expert-knowledge task performed daily by Level 1/2 support engineers. A triage agent must read a natural-language ticket, assess its urgency, classify its domain, assign it to the correct team, and propose a resolution — all from the ticket text alone.

This environment simulates that workflow with **15 realistic support tickets** across 6 categories (Hardware, Software, Network, Security, Access, Other), and 3 tasks of escalating difficulty.

**Why this task?**
- Real-world: This exact task is done by humans in every company with an IT department
- Text-grounded: Requires understanding natural language, not pattern matching
- Multi-objective: Priority, category, team, resolution, and time estimate all interact
- Gradable: Has clear ground truth with partial credit available

---

## 🎮 Tasks

| Task | Description | Max Steps | Difficulty | Baseline Score |
|------|-------------|-----------|------------|---------------|
| **EASY** | Classify priority (LOW / MEDIUM / HIGH / CRITICAL) | 1 | ⭐ Easy | 0.62 |
| **MEDIUM** | Priority + Category + Team assignment | 1 | ⭐⭐ Medium | 0.48 |
| **HARD** | Full triage: all fields + resolution suggestion + similar tickets + time estimate | 3 | ⭐⭐⭐ Hard | 0.34 |

---

## 🔭 Observation Space

All observations are structured JSON. Ground truth is **never** included — only the ticket text and task context.

```json
{
  "ticket_id": "TKT-010",
  "ticket_title": "Phishing email received — employee clicked link",
  "ticket_description": "An employee in the Finance department received a phishing email...",
  "user_name": "Ramesh Krishnan",
  "user_department": "Finance",
  "submitted_at": "2025-03-15T10:30:00",
  "task_type": "HARD",
  "task_description": "Perform complete triage: priority, category, team...",
  "step_count": 0,
  "max_steps": 3,
  "done": false,
  "previous_actions": [],
  "cumulative_reward": 0.0,
  "action_schema": {
    "required_fields": ["priority", "category", "assigned_team", "resolution_suggestion",
                        "similar_ticket_ids", "estimated_resolution_hours"],
    "optional_fields": ["reasoning"],
    "valid_priorities": ["LOW", "MEDIUM", "HIGH", "CRITICAL"],
    "valid_categories": ["HARDWARE", "SOFTWARE", "NETWORK", "SECURITY", "ACCESS", "OTHER"],
    "valid_teams": ["HELPDESK", "SYSADMIN", "NETWORK_OPS", "SECURITY_OPS", "DEVOPS", "MANAGEMENT"]
  },
  "similar_tickets": [
    {
      "ticket_id": "TKT-011",
      "title": "Suspicious login attempts on server",
      "resolution_summary": "Block source IP, enable fail2ban, rotate SSH keys.",
      "resolved_in_hours": 3
    }
  ]
}
```

---

## ⚡ Action Space

Actions are structured JSON objects. Required fields vary by task type.

```json
{
  "priority": "CRITICAL",
  "category": "SECURITY",
  "assigned_team": "SECURITY_OPS",
  "resolution_suggestion": "Reset user credentials immediately. Revoke all active sessions. Check audit logs for unauthorized access. Notify CISO. File security incident report.",
  "similar_ticket_ids": ["TKT-011", "TKT-012"],
  "estimated_resolution_hours": 2,
  "reasoning": "Phishing with credential entry is a CRITICAL SECURITY incident requiring immediate credential reset."
}
```

| Field | Required For | Valid Values |
|-------|-------------|--------------|
| `priority` | EASY, MEDIUM, HARD | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` |
| `category` | MEDIUM, HARD | `HARDWARE`, `SOFTWARE`, `NETWORK`, `SECURITY`, `ACCESS`, `OTHER` |
| `assigned_team` | MEDIUM, HARD | `HELPDESK`, `SYSADMIN`, `NETWORK_OPS`, `SECURITY_OPS`, `DEVOPS`, `MANAGEMENT` |
| `resolution_suggestion` | HARD | Free text string |
| `similar_ticket_ids` | HARD | Array of ticket IDs |
| `estimated_resolution_hours` | HARD | Integer (1–720) |
| `reasoning` | Never required | Free text (never penalized) |

---

## 🏆 Reward Function

Rewards are **dense and multi-dimensional** — not binary end-of-episode.

### EASY Task
| Component | Weight | Scoring |
|-----------|--------|---------|
| Priority exact match | 1.0 | 1.0 → 0.5 → 0.2 → 0.0 for 0/1/2/3+ levels off |

### MEDIUM Task
| Component | Weight | Scoring |
|-----------|--------|---------|
| Priority | 0.40 | Partial credit (same as EASY) |
| Category | 0.35 | Binary: correct=0.35, wrong=0.0 |
| Team | 0.25 | Binary: correct=0.25, wrong=0.0 |

### HARD Task
| Component | Weight | Scoring |
|-----------|--------|---------|
| Priority | 0.25 | Partial credit |
| Category | 0.20 | Binary |
| Team | 0.15 | Binary |
| Resolution | 0.25 | Keyword overlap with ground truth hint |
| Similar Tickets | 0.10 | Fraction of correct references |
| Time Estimate | 0.05 | Full credit if in priority-expected range, partial for near-miss |

**Penalties:**
- Missing required field: **-0.05** per field
- Invalid enum value: **-0.03 to -0.10**

All rewards are clamped to `[0.0, 1.0]`. Reward signal is provided at **every step**, not just end-of-episode.

---

## 🚀 Setup & Usage

### Quick Start (Docker)

```bash
# Build
docker build -t ticket-triage-env .

# Run (with OpenAI baseline)
docker run -p 7860:7860 \
  -e OPENAI_API_KEY=your_key_here \
  ticket-triage-env

# Run (without OpenAI — mock baseline)
docker run -p 7860:7860 ticket-triage-env
```

### Local Development

```bash
# Requirements: Java 17+, Maven 3.8+

# Build
mvn clean package -DskipTests

# Run
java -jar target/ticket-triage-env-1.0.0.jar

# Run tests
mvn test
```

### API Usage

```bash
# 1. Reset environment (start episode)
curl -X POST http://localhost:7860/api/v1/reset \
  -H "Content-Type: application/json" \
  -d '{"task_type": "EASY", "seed": 42}'

# 2. Submit action
curl -X POST http://localhost:7860/api/v1/step \
  -H "Content-Type: application/json" \
  -d '{"priority": "HIGH", "reasoning": "Screen flickering with client deadline is HIGH"}'

# 3. Inspect ground truth
curl http://localhost:7860/api/v1/state

# 4. Run baseline
curl -X POST "http://localhost:7860/api/v1/baseline/run?taskTypes=EASY,MEDIUM,HARD"
```

### Interactive API Docs

Visit: `http://localhost:7860/swagger-ui`

---

## 📊 Baseline Scores

Baseline agent: `gpt-4o-mini` with temperature 0.2, seed 42.

| Task | Score | Notes |
|------|-------|-------|
| EASY | **0.62** | LLM correctly identifies most priorities; struggles with MEDIUM vs HIGH edge cases |
| MEDIUM | **0.48** | Category and team assignment errors reduce score significantly |
| HARD | **0.34** | Resolution keyword coverage and time estimation are challenging |
| **Average** | **0.48** | Room for significant improvement via fine-tuning or RAG |

To reproduce:
```bash
export OPENAI_API_KEY=your_key
curl -X POST "http://localhost:7860/api/v1/baseline/run?taskTypes=EASY,MEDIUM,HARD"
```

---

## 🗂 Ticket Dataset

15 real-world style tickets across 6 categories:

| Category | Tickets | Example |
|----------|---------|---------|
| Hardware | TKT-001, TKT-002, TKT-003 | Laptop screen flickering before a presentation |
| Software | TKT-004, TKT-005, TKT-006 | SAP crash affecting payroll for 12 users |
| Network | TKT-007, TKT-008, TKT-009 | Building-wide internet outage (200 employees) |
| Security | TKT-010, TKT-011, TKT-012 | Phishing attack with credential compromise |
| Access | TKT-013, TKT-014, TKT-015 | New joiner with no system access |

---

## 🏗 Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                   Spring Boot Application                        │
│  Port 7860                                                      │
│                                                                 │
│  ┌──────────────────┐    ┌──────────────────────────────────┐  │
│  │  OpenEnvController│    │      EnvironmentService          │  │
│  │                  │───▶│  reset() / step() / state()      │  │
│  │  POST /reset     │    └──────────┬───────────────────────┘  │
│  │  POST /step      │               │                          │
│  │  GET  /state     │    ┌──────────▼───────────────────────┐  │
│  │  GET  /info      │    │        Grader Pipeline            │  │
│  └──────────────────┘    │  EasyTaskGrader  (priority)       │  │
│                           │  MediumTaskGrader(+cat+team)     │  │
│  ┌──────────────────┐    │  HardTaskGrader  (+resolution)   │  │
│  │ BaselineController│    └──────────┬───────────────────────┘  │
│  │  POST /baseline  │               │                          │
│  │       /run       │    ┌──────────▼───────────────────────┐  │
│  └────────┬─────────┘    │      TicketDataStore              │  │
│           │               │  15 realistic IT tickets         │  │
│  ┌────────▼─────────┐    └──────────────────────────────────┘  │
│  │  BaselineRunner  │                                          │
│  │  OpenAI API      │                                          │
│  │  step/reset loop │                                          │
│  └──────────────────┘                                          │
└─────────────────────────────────────────────────────────────────┘
```

---

## 🧪 Tests

```bash
mvn test
```

- `OpenEnvIntegrationTest` — Full episode lifecycle for all 3 tasks (50+ assertions)
- `GraderUnitTest` — Isolated reward function tests for all graders

---

## 📄 OpenEnv Spec Compliance

| Requirement | Implementation |
|-------------|---------------|
| Typed Observation model | `Observation.java` (Jackson/Spring) |
| Typed Action model | `Action.java` (with validation) |
| Typed Reward model | `Reward.java` (with component breakdown) |
| `reset()` → initial observation | `POST /api/v1/reset` |
| `step(action)` → obs, reward, done, info | `POST /api/v1/step` |
| `state()` → current state | `GET /api/v1/state` |
| `openenv.yaml` metadata | `src/main/resources/openenv.yaml` |
| Minimum 3 tasks | EASY, MEDIUM, HARD |
| Graders (0.0–1.0) | EasyTaskGrader, MediumTaskGrader, HardTaskGrader |
| Dense reward function | Per-step, multi-component, partial credit |
| Baseline inference script | BaselineRunner.java (OpenAI API) |
| Containerized (Dockerfile) | Multi-stage Docker build |
| HF Space tagged openenv | `sdk: docker`, tags in README front matter |

---

## 📜 License

MIT License — see [LICENSE](LICENSE)
