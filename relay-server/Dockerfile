FROM python:3.12-slim
WORKDIR /app
RUN useradd -m relay && mkdir -p /app/state && chown relay /app/state
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY relay.py .
USER relay
EXPOSE 8080
HEALTHCHECK CMD python -c "import urllib.request,sys; sys.exit(0)"
CMD ["uvicorn", "relay:app", "--host", "0.0.0.0", "--port", "8080", "--proxy-headers"]
