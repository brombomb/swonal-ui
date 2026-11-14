FROM python:3.12-slim

WORKDIR /app

COPY pyproject.toml config.example.yaml ./
COPY src ./src

RUN pip install --no-cache-dir .

ENV MOCK_TONAL=true
ENV DAEMON_PORT=8765

EXPOSE 8765

CMD ["python", "-m", "src.main", "--mock"]
