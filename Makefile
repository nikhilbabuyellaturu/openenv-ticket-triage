# OpenEnv — IT Support Ticket Triage
# Makefile for common development, build, test, and deployment commands

ENV_URL   ?= http://localhost:7860/api/v1
MODEL     ?= gpt-4o-mini
IMAGE     ?= ticket-triage-env:1.0.0

.PHONY: help build run stop test baseline-java baseline-python \
        docker-build docker-run docker-stop logs clean reset-easy reset-medium reset-hard

# ─────────────────────────────────────────────────────────────────────
# Help
# ─────────────────────────────────────────────────────────────────────
help:
	@echo ""
	@echo "  OpenEnv — IT Support Ticket Triage"
	@echo "  ─────────────────────────────────────────────────────"
	@echo "  Development:"
	@echo "    make build          Build the Spring Boot JAR"
	@echo "    make run            Run locally (requires Java 17)"
	@echo "    make test           Run all tests"
	@echo "    make stop           Stop local server (kills port 7860)"
	@echo ""
	@echo "  Docker:"
	@echo "    make docker-build   Build the Docker image"
	@echo "    make docker-run     Run the container on port 7860"
	@echo "    make docker-stop    Stop and remove the container"
	@echo "    make logs           Follow container logs"
	@echo ""
	@echo "  Baseline:"
	@echo "    make baseline-java  Run Java baseline via API endpoint"
	@echo "    make baseline-python Run Python baseline script (all tasks)"
	@echo ""
	@echo "  Quick env test:"
	@echo "    make reset-easy     Reset env with EASY task"
	@echo "    make reset-medium   Reset env with MEDIUM task"
	@echo "    make reset-hard     Reset env with HARD task"
	@echo "    make health         Check environment health"
	@echo ""

# ─────────────────────────────────────────────────────────────────────
# Local development
# ─────────────────────────────────────────────────────────────────────
build:
	mvn clean package -DskipTests

run:
	java -jar target/ticket-triage-env-1.0.0.jar

stop:
	@pkill -f "ticket-triage-env" 2>/dev/null || true
	@lsof -ti:7860 | xargs kill -9 2>/dev/null || true
	@echo "Server stopped."

test:
	mvn test

# ─────────────────────────────────────────────────────────────────────
# Docker
# ─────────────────────────────────────────────────────────────────────
docker-build:
	docker build -t $(IMAGE) .

docker-run:
	docker run -d \
		--name ticket-triage-env \
		-p 7860:7860 \
		-e OPENAI_API_KEY=$(OPENAI_API_KEY) \
		-e OPENAI_MODEL=$(MODEL) \
		$(IMAGE)
	@echo "Container started. Waiting for health check..."
	@sleep 5
	@docker exec ticket-triage-env wget -qO- http://localhost:7860/api/v1/health || true

docker-stop:
	docker stop ticket-triage-env 2>/dev/null || true
	docker rm   ticket-triage-env 2>/dev/null || true

logs:
	docker logs -f ticket-triage-env

# ─────────────────────────────────────────────────────────────────────
# Baseline
# ─────────────────────────────────────────────────────────────────────
baseline-java:
	curl -sX POST "$(ENV_URL)/baseline/run?taskTypes=EASY,MEDIUM,HARD" | python3 -m json.tool

baseline-python:
	pip install -q openai requests
	python3 baseline_runner.py --task ALL --output baseline_results.json

# ─────────────────────────────────────────────────────────────────────
# Quick env tests (curl shortcuts)
# ─────────────────────────────────────────────────────────────────────
health:
	@curl -s $(ENV_URL)/health | python3 -m json.tool

reset-easy:
	curl -sX POST $(ENV_URL)/reset \
		-H "Content-Type: application/json" \
		-d '{"task_type":"EASY","ticket_id":"TKT-001","seed":42}' | python3 -m json.tool

reset-medium:
	curl -sX POST $(ENV_URL)/reset \
		-H "Content-Type: application/json" \
		-d '{"task_type":"MEDIUM","ticket_id":"TKT-004","seed":42}' | python3 -m json.tool

reset-hard:
	curl -sX POST $(ENV_URL)/reset \
		-H "Content-Type: application/json" \
		-d '{"task_type":"HARD","ticket_id":"TKT-010","seed":42}' | python3 -m json.tool

step-example:
	curl -sX POST $(ENV_URL)/step \
		-H "Content-Type: application/json" \
		-d '{"priority":"HIGH","reasoning":"Test action"}' | python3 -m json.tool

state:
	@curl -s $(ENV_URL)/state | python3 -m json.tool

tickets:
	@curl -s $(ENV_URL)/tickets | python3 -m json.tool

# ─────────────────────────────────────────────────────────────────────
# Cleanup
# ─────────────────────────────────────────────────────────────────────
clean:
	mvn clean
	rm -f baseline_results.json
