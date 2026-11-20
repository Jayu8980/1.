FROM python:3.12-slim
ENV PYTHONUNBUFFERED=1 TRADELENS_HOME=/app
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 && rm -rf /var/lib/apt/lists/* \
 && useradd -m engine && mkdir -p /app/state /app/data_cache && chown -R engine /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY tradelens ./tradelens
USER engine
EXPOSE 8000
CMD ["python", "-m", "tradelens", "serve", "--host", "0.0.0.0", "--port", "8000"]
