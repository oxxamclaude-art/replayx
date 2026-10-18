# Demo backends for ReplayX (stdlib-only Python, no extra dependencies).
FROM python:3.12-slim
WORKDIR /app
COPY backend.py payment-stub.py ./
EXPOSE 8081 8082 8083
# Overridden per service in docker-compose.yml.
CMD ["python3", "payment-stub.py", "--port", "8082"]
