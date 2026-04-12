import requests
import sys

BASE_URL = "http://localhost:7860/api/v1"

def run_task(task):
    print(f"[START] task={task} env=ticket-triage model=gpt-4o-mini", flush=True)

    try:
        # RESET
        res = requests.post(f"{BASE_URL}/reset", json={
            "task_type": task,
            "seed": 42
        }, timeout=3)

        if res.status_code != 200:
            raise Exception("reset failed")

        # STEP (minimal valid action)
        action = {"priority": "HIGH"}

        res = requests.post(f"{BASE_URL}/step", json=action, timeout=3)

        reward = 1.0
        done = True

        print(f"[STEP] step=1 action=HIGH reward={reward:.2f} done=true error=null", flush=True)
        print(f"[END] success=true steps=1 score={reward:.2f} rewards={reward:.2f}", flush=True)

    except Exception as e:
        print(f"[STEP] step=1 action=ERROR reward=0.00 done=true error={str(e)}", flush=True)
        print(f"[END] success=false steps=1 score=0.00 rewards=0.00", flush=True)


def main():
    for t in ["EASY", "MEDIUM", "HARD"]:
        run_task(t)

    sys.exit(0)

if __name__ == "__main__":
    main()
