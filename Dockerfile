FROM python:3.10-slim

WORKDIR /app

COPY inference.py .

CMD ["python", "-u", "inference.py"]
